#!/usr/bin/env bash
# 从 Scala Config 源码重建 Make 在解析阶段需要的 TSV 快照。
set -euo pipefail

npc_root=${1:?usage: generate-config-catalog.sh NPC_ROOT}
[[ -d "$npc_root/chisel/configs" ]] || {
  printf '!missing NPC Config source root: %s\n' "$npc_root" >&2
  exit 2
}

mkdir -p "$npc_root/intermediate"
exec 9>"$npc_root/intermediate/.config-catalog.lock"
flock 9

log=$(mktemp)
trap 'rm -f "$log"' EXIT
if ! (
  cd "$npc_root"
  sbt "root/runMain scpu.GenerateConfigCatalog $npc_root"
) >"$log" 2>&1; then
  printf '!failed to generate Scala Config catalog:\n' >&2
  cat "$log" >&2
  exit 1
fi
