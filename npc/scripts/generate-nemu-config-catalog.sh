#!/usr/bin/env bash
# 从 companion object 显式登记的 NEMU Base 生成 `host-config-list` 临时目录。
set -euo pipefail

npc_root=${1:?usage: generate-nemu-config-catalog.sh NPC_ROOT OUTPUT_TSV}
output=${2:?usage: generate-nemu-config-catalog.sh NPC_ROOT OUTPUT_TSV}
[[ -d "$npc_root/chisel/configs/nemu" ]] || {
  printf '找不到 NEMU Host Config 源目录：%s\n' "$npc_root/chisel/configs/nemu" >&2
  exit 2
}

mkdir -p "$npc_root/intermediate"
exec 9>"$npc_root/intermediate/.nemu-config-catalog.lock"
flock 9

log=$(mktemp)
trap 'rm -f "$log"' EXIT
if ! (
  cd "$npc_root"
  sbt "root/runMain scpu.DescribeNemuConfigCatalog $output"
) >"$log" 2>&1; then
  printf '生成 Scala NEMU Host Config 目录失败：\n' >&2
  cat "$log" >&2
  exit 1
fi
