#!/usr/bin/env bash
# 记录并展示 Config/版本批次运行的稳定性能汇总。
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
用法：
  performance-summary.sh record <construction-dir> <test> <output.log> <PASS|FAIL> <summary.tsv>
  performance-summary.sh show <summary.tsv>
EOF
  exit 2
}

value() {
  sed -n "s/^${2}=//p" "$1" | tail -n 1
}

safe_field() {
  local value=$1
  [[ $value != *$'\t'* && $value != *$'\n'* && $value != *$'\r'* ]] || {
    echo "汇总字段含非法控制字符" >&2
    exit 2
  }
}

record() {
  [[ $# == 5 ]] || usage
  local construction=$1 test_name=$2 output=$3 result=$4 summary=$5
  local profile="$construction/profile.env" metadata="$construction/construction.env"
  [[ -f $profile && -f $metadata && -f $output ]] || {
    echo "汇总所需的构造记录或输出日志不存在" >&2
    exit 1
  }
  [[ $result == PASS || $result == FAIL ]] || usage

  local performance cycles=N/A commits=N/A cpi=N/A ipc=N/A mips=N/A
  performance=$(grep -E '^\[NPC(-SoC)?\] performance: cycles=[0-9]+' "$output" | tail -n 1 || true)
  if [[ -n $performance ]]; then
    cycles=$(sed -E 's/.*cycles=([0-9]+).*/\1/' <<< "$performance")
    commits=$(sed -E 's/.*commits=([0-9]+).*/\1/' <<< "$performance")
    if [[ $performance == *CPI=* ]]; then cpi=$(sed -E 's/.*CPI=([0-9.]+).*/\1/' <<< "$performance"); fi
    if [[ $performance == *IPC=* ]]; then ipc=$(sed -E 's/.*IPC=([0-9.]+).*/\1/' <<< "$performance"); fi
    if [[ $performance == *MIPS* ]]; then mips=$(sed -E 's/.*: ([0-9.]+) MIPS.*/\1/' <<< "$performance"); fi
  fi

  local version_index fqcn short capability board log_directory detail temporary
  version_index=$(value "$metadata" VERSION_INDEX)
  [[ $version_index =~ ^[1-9][0-9]*$ ]] || { echo "构造缺少有效 VERSION_INDEX：$construction" >&2; exit 1; }
  fqcn=$(value "$profile" CONFIG_FQCN)
  short=$(value "$profile" CONFIG_SHORT_NAME)
  capability=$(value "$profile" CAPABILITY)
  board=$(value "$profile" FPGA_BOARD)
  board=${board:--}
  for field in "$version_index" "$fqcn" "$short" "$capability" "$board" "$test_name" \
    "$cycles" "$commits" "$cpi" "$ipc" "$mips" "$result"; do safe_field "$field"; done

  log_directory=$(dirname "$output")
  detail="$log_directory/summary.env"
  temporary="$detail.tmp.$$"
  {
    printf 'VERSION_INDEX=%s\n' "$version_index"
    printf 'CONFIG_FQCN=%s\n' "$fqcn"
    printf 'CAPABILITY=%s\n' "$capability"
    printf 'FPGA_BOARD=%s\n' "$board"
    printf 'TEST=%s\n' "$test_name"
    printf 'CYCLES=%s\nCOMMITS=%s\nCPI=%s\nIPC=%s\nMIPS=%s\nRESULT=%s\n' \
      "$cycles" "$commits" "$cpi" "$ipc" "$mips" "$result"
  } > "$temporary"
  mv "$temporary" "$detail"

  mkdir -p "$(dirname "$summary")"
  exec 9>"$summary.lock"
  flock 9
  if [[ ! -s $summary ]]; then
    printf 'VERSION\tCONFIG\tCAPABILITY\tBOARD\tTEST\tCYCLES\tCOMMITS\tCPI\tIPC\tMIPS\tRESULT\n' > "$summary"
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$version_index" "$short" "$capability" "$board" "$test_name" "$cycles" "$commits" "$cpi" "$ipc" "$mips" "$result" >> "$summary"
}

show() {
  [[ $# == 1 ]] || usage
  local summary=$1
  [[ -s $summary ]] || { echo "批次汇总为空：$summary" >&2; exit 1; }
  awk -F '\t' '
    NR == 1 {
      printf "%-8s %-30s %-13s %-8s %-18s %-11s %-11s %-8s %-8s %-8s %s\n",
        $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11
      next
    }
    {
      printf "%-8s %-30s %-13s %-8s %-18s %-11s %-11s %-8s %-8s %-8s %s\n",
        $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11
    }
  ' "$summary"
}

[[ $# -ge 1 ]] || usage
command=$1
shift
case "$command" in
  record) record "$@" ;;
  show) show "$@" ;;
  *) usage ;;
esac
