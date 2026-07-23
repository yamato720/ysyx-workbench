#!/usr/bin/env bash
# 反射实例化终端 Scala Config，并写出 Make/工具链消费的规范化 profile。
set -euo pipefail

usage() {
  echo "用法：$0 <npc-root> <Config短名或FQCN> <profile.env>" >&2
  exit 2
}

[[ $# == 3 ]] || usage
npc_root=$(realpath "$1")
request=$2
output=$3
catalog="$npc_root/chisel/configs/resources/scpu-config-catalog.tsv"

if [[ ${SCPU_CONFIG_CATALOG_READY:-0} != 1 ]]; then
  "$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
fi
resolved=$("$npc_root/scripts/resolve-config.sh" "$catalog" "$request" 'npc,soc,fpga')
[[ $resolved != !* ]] || { echo "${resolved#!}" >&2; exit 2; }
IFS='|' read -r fqcn scope board target <<< "$resolved"

mkdir -p "$(dirname "$output")"
temporary="$output.tmp.$$"
log="$output.log.tmp.$$"
trap 'rm -f "$temporary" "$log"' EXIT

case "$scope" in
  npc)
    if ! (cd "$npc_root" && NPC_SCALA_CONFIG="$fqcn" sbt \
      "root/runMain scpu.DescribeNpcConfig $temporary") >"$log" 2>&1; then
      echo "生成 $fqcn profile 失败：" >&2
      cat "$log" >&2
      exit 1
    fi
    ;;
  soc|fpga)
    if ! (cd "$npc_root/chisel/ysyxSoC" && NPC_SCALA_CONFIG="$fqcn" mill -i \
      ysyxsoc.runMain ysyx.DescribeConfig "$temporary") >"$log" 2>&1; then
      echo "生成 $fqcn profile 失败：" >&2
      cat "$log" >&2
      exit 1
    fi
    ;;
  *) echo "目录中存在未知作用域：$scope" >&2; exit 2 ;;
esac

profile_fqcn=$(sed -n 's/^CONFIG_FQCN=//p' "$temporary")
profile_scope=$(sed -n 's/^SCOPE=//p' "$temporary")
profile_target=$(sed -n 's/^TARGET=//p' "$temporary")
[[ $profile_fqcn == "$fqcn" && $profile_scope == "$scope" && $profile_target == "$target" ]] || {
  echo "Scala profile 与自动目录不一致：$fqcn/$scope/$target -> $profile_fqcn/$profile_scope/$profile_target" >&2
  exit 1
}
awk -F= '
  !/^[A-Z][A-Z0-9_]*=/ { exit 1 }
  seen[$1]++ { exit 1 }
  index(substr($0, index($0, "=") + 1), "\r") { exit 1 }
  END {
    if (!seen["PROFILE_FORMAT"] || !seen["CAPABILITY"] || !seen["XLEN"] || !seen["NEMU_PRESET"]) exit 1
  }
' "$temporary" || { echo "Scala profile 格式无效：$temporary" >&2; exit 1; }
mv "$temporary" "$output"
