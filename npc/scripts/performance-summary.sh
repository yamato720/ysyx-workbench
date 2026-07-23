#!/usr/bin/env bash
# 记录并展示 Config/版本批次运行的稳定性能汇总。
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
用法：
  performance-summary.sh record <construction-dir> <test> <output.log> <PASS|FAIL> <summary.tsv> [finished-ns completion-counter]
  performance-summary.sh collect <items-root> <summary.tsv> <completion|stable>
  performance-summary.sh show <summary.tsv>
  performance-summary.sh details <items-root>
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
  [[ $# == 5 || $# == 7 ]] || usage
  local construction=$1 test_name=$2 output=$3 result=$4 summary=$5
  local finished_ns=${6:-} completion_counter=${7:-} completion_order=''
  local profile="$construction/profile.env" metadata="$construction/construction.env"
  [[ -f $profile && -f $metadata && -f $output ]] || {
    echo "汇总所需的构造记录或输出日志不存在" >&2
    exit 1
  }
  [[ $result == PASS || $result == FAIL ]] || usage
  [[ -z $finished_ns || $finished_ns =~ ^[0-9]+$ ]] || {
    echo "完成时间必须为纳秒整数：$finished_ns" >&2
    exit 2
  }
  [[ -z $completion_counter || -n $finished_ns ]] || {
    echo '提供完成序号计数器时必须同时提供完成时间' >&2
    exit 2
  }

  local performance cycles=N/A commits=N/A cpi=N/A ipc=N/A mips=N/A
  performance=$(grep -E '^\[NPC(-SoC)?\] performance: cycles=[0-9]+' "$output" | tail -n 1 || true)
  if [[ -n $performance ]]; then
    cycles=$(sed -E 's/.*cycles=([0-9]+).*/\1/' <<< "$performance")
    commits=$(sed -E 's/.*commits=([0-9]+).*/\1/' <<< "$performance")
    if [[ $performance == *CPI=* ]]; then cpi=$(sed -E 's/.*CPI=([0-9.]+).*/\1/' <<< "$performance"); fi
    if [[ $performance == *IPC=* ]]; then ipc=$(sed -E 's/.*IPC=([0-9.]+).*/\1/' <<< "$performance"); fi
    if [[ $performance == *MIPS* ]]; then mips=$(sed -E 's/.*: ([0-9.]+) MIPS.*/\1/' <<< "$performance"); fi
  fi

  local version_index fqcn short runtime board log_directory detail temporary performance_report pipeline_report
  version_index=$(value "$metadata" VERSION_INDEX)
  [[ $version_index =~ ^[1-9][0-9]*$ ]] || { echo "构造缺少有效 VERSION_INDEX：$construction" >&2; exit 1; }
  fqcn=$(value "$profile" CONFIG_FQCN)
  short=$(value "$profile" CONFIG_SHORT_NAME)
  runtime=$(value "$profile" NEMU_BACKEND)
  board=$(value "$profile" FPGA_BOARD)
  board=${board:--}
  performance_report=$(sed -n 's/^NEMU 性能 HTML：//p' "$output" | tail -n 1)
  pipeline_report=$(sed -n 's/^NEMU 流水线 HTML：//p' "$output" | tail -n 1)
  if [[ -n $performance_report && -f $performance_report ]]; then
    performance_report=$(readlink -f "$performance_report")
  else
    performance_report=N/A
  fi
  if [[ -n $pipeline_report && -f $pipeline_report ]]; then
    pipeline_report=$(readlink -f "$pipeline_report")
  else
    pipeline_report=N/A
  fi
  for field in "$version_index" "$fqcn" "$short" "$runtime" "$board" "$test_name" \
    "$cycles" "$commits" "$cpi" "$ipc" "$mips" "$result" "$performance_report" "$pipeline_report"; do safe_field "$field"; done

  if [[ -n $completion_counter ]]; then
    local current counter_temporary
    mkdir -p "$(dirname "$completion_counter")"
    exec 8>"$completion_counter.lock"
    flock 8
    current=0
    if [[ -s $completion_counter ]]; then
      current=$(<"$completion_counter")
      [[ $current =~ ^[0-9]+$ ]] || { echo "完成序号计数器损坏：$completion_counter" >&2; exit 1; }
    fi
    completion_order=$((current + 1))
    counter_temporary="$completion_counter.tmp.$$"
    printf '%s\n' "$completion_order" > "$counter_temporary"
    mv "$counter_temporary" "$completion_counter"
  fi

  log_directory=$(dirname "$output")
  detail="$log_directory/summary.env"
  temporary="$detail.tmp.$$"
  {
    printf 'VERSION_INDEX=%s\n' "$version_index"
    printf 'CONFIG_FQCN=%s\n' "$fqcn"
    printf 'CONFIG_SHORT=%s\n' "$short"
    printf 'RUNTIME=%s\n' "$runtime"
    printf 'FPGA_BOARD=%s\n' "$board"
    printf 'TEST=%s\n' "$test_name"
    printf 'CYCLES=%s\nCOMMITS=%s\nCPI=%s\nIPC=%s\nMIPS=%s\nRESULT=%s\n' \
      "$cycles" "$commits" "$cpi" "$ipc" "$mips" "$result"
    printf 'PERFORMANCE_HTML=%s\nPIPELINE_HTML=%s\n' "$performance_report" "$pipeline_report"
    [[ -z $finished_ns ]] || printf 'FINISHED_NS=%s\n' "$finished_ns"
    [[ -z $completion_order ]] || printf 'COMPLETION_ORDER=%s\n' "$completion_order"
  } > "$temporary"
  mv "$temporary" "$detail"

  mkdir -p "$(dirname "$summary")"
  exec 9>"$summary.lock"
  flock 9
  if [[ ! -s $summary ]]; then
    printf 'VERSION\tCONFIG\tRUNTIME\tBOARD\tTEST\tCYCLES\tCOMMITS\tCPI\tIPC\tMIPS\tRESULT\n' > "$summary"
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$version_index" "$short" "$runtime" "$board" "$test_name" "$cycles" "$commits" "$cpi" "$ipc" "$mips" "$result" >> "$summary"
}

collect() {
  [[ $# == 3 ]] || usage
  local items=$1 summary=$2 order=$3 detail item item_summary line key rows output_temporary
  [[ -d $items ]] || { echo "批次条目目录不存在：$items" >&2; exit 1; }
  case "$order" in completion|stable) ;; *) usage ;; esac
  rows=$(mktemp "${summary}.rows.XXXXXX")
  output_temporary="${summary}.tmp.$$"
  trap 'rm -f "$rows" "$output_temporary"' RETURN
  while IFS= read -r detail; do
    item=$(dirname "$(dirname "$detail")")
    item_summary="$item/summary.tsv"
    [[ -s $item_summary ]] || { echo "批次条目缺少 summary.tsv：$item" >&2; exit 1; }
    line=$(sed -n '2p' "$item_summary")
    [[ -n $line ]] || { echo "批次条目 summary.tsv 为空：$item" >&2; exit 1; }
    if [[ $order == completion ]]; then
      key=$(value "$detail" COMPLETION_ORDER)
      [[ $key =~ ^[1-9][0-9]*$ ]] || { echo "批次条目缺少完成序号：$detail" >&2; exit 1; }
      printf '%020d\t%s\n' "$key" "$line" >> "$rows"
    else
      printf '%s\n' "$line" >> "$rows"
    fi
  done < <(find "$items" -name summary.env -type f -print | LC_ALL=C sort)

  mkdir -p "$(dirname "$summary")"
  printf 'VERSION\tCONFIG\tRUNTIME\tBOARD\tTEST\tCYCLES\tCOMMITS\tCPI\tIPC\tMIPS\tRESULT\n' > "$output_temporary"
  if [[ $order == completion ]]; then
    LC_ALL=C sort -t $'\t' -k1,1n -k2,2 "$rows" | cut -f2- >> "$output_temporary"
  else
    LC_ALL=C sort -t $'\t' -k1,1n -k2,2 -k5,5 "$rows" >> "$output_temporary"
  fi
  mv "$output_temporary" "$summary"
  rm -f "$rows"
  trap - RETURN
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

details() {
  [[ $# == 1 ]] || usage
  local items=$1 detail rows version short test_name result performance_report
  [[ -d $items ]] || { echo "批次条目目录不存在：$items" >&2; exit 1; }
  rows=$(mktemp)
  trap 'rm -f "$rows"' RETURN
  while IFS= read -r detail; do
    version=$(value "$detail" VERSION_INDEX)
    short=$(value "$detail" CONFIG_SHORT)
    test_name=$(value "$detail" TEST)
    result=$(value "$detail" RESULT)
    performance_report=$(value "$detail" PERFORMANCE_HTML)
    [[ $version =~ ^[1-9][0-9]*$ && -n $short && -n $test_name ]] || {
      echo "批次条目缺少详细报告索引字段：$detail" >&2
      exit 1
    }
    printf '%020d\t%s\t%s\t%s\t%s\n' "$version" "$short" "$test_name" "$result" \
      "${performance_report:-N/A}" >> "$rows"
  done < <(find "$items" -name summary.env -type f -print | LC_ALL=C sort)

  while IFS=$'\t' read -r version short test_name result performance_report; do
    version=$((10#$version))
    printf '[version=%s] %s / %s (%s)\n' "$version" "$short" "$test_name" "$result"
    printf '  详细报告：%s\n' "$performance_report"
  done < <(LC_ALL=C sort -t $'\t' -k1,1n -k2,2 -k3,3 "$rows")
  rm -f "$rows"
  trap - RETURN
}

[[ $# -ge 1 ]] || usage
command=$1
shift
case "$command" in
  record) record "$@" ;;
  collect) collect "$@" ;;
  show) show "$@" ;;
  details) details "$@" ;;
  *) usage ;;
esac
