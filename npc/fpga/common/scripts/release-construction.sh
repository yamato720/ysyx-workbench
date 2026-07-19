#!/usr/bin/env bash
# 校验源码 Release 中允许构造的终端 FPGA Config。
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
用法：
  release-construction.sh verify <constructions.env> <npc-root> <Config>
  release-construction.sh show <constructions.env> <npc-root> <Config>
EOF
  exit 2
}

fail() {
  echo "release construction: $*" >&2
  exit 1
}

value() {
  local file=$1 key=$2 values
  values=$(sed -n "s/^${key}=//p" "$file")
  [[ $(printf '%s\n' "$values" | sed '/^$/d' | wc -l) == 1 ]] || return 1
  printf '%s\n' "$values" | tail -n 1
}

resolve_release_config() {
  local file=$1 npc_root=$2 request=$3 resolved fqcn capability profile entries
  [[ -f $file && -d $npc_root ]] || fail '构造清单或 NPC 根目录不存在'
  [[ $(value "$file" RELEASE_CONSTRUCTIONS_FORMAT) == 2 ]] || fail '不支持的 Release 构造清单格式'
  [[ -n $(value "$file" RELEASE_TAG) ]] || fail 'Release tag 为空'
  entries=" $(value "$file" CONFIG_FQCNS) "
  resolved=$("$npc_root/scripts/construction-manager.sh" resolve "$npc_root" "$request" '') || exit $?
  IFS='|' read -r fqcn _ capability _ _ _ _ _ _ profile <<< "$resolved"
  [[ $entries == *" $fqcn "* ]] || fail "$fqcn 未列入该 Release"
  [[ $capability == fpga-soc ]] || fail "$fqcn 不是可发布的 FPGA SoC 终端 Config"
  [[ -f $profile ]] || fail "Config profile 不存在：$profile"
  printf '%s|%s\n' "$fqcn" "$profile"
}

verify() {
  local resolved
  resolved=$(resolve_release_config "$@")
  printf 'Release Config 校验通过：%s\n' "${resolved%%|*}"
}

show() {
  local resolved fqcn profile
  resolved=$(resolve_release_config "$@")
  fqcn=${resolved%%|*}
  profile=${resolved#*|}
  printf 'RELEASE_TAG=%s\nCONFIG_FQCN=%s\n' "$(value "$1" RELEASE_TAG)" "$fqcn"
  grep -E '^(CAPABILITY|TARGET|XLEN|ISA_STRING|HOST_ABI|PROTOCOL_ABI|FPGA_BOARD|FPGA_CLOCK_MHZ|FPGA_TYPE|FPGA_PLATFORM|FPGA_VIVADO_VERSION|FPGA_VITIS_VERSION|FPGA_VITIS_TARGET|FPGA_TIMING_WNS_MIN_NS)=' "$profile"
}

[[ $# -ge 1 ]] || usage
command=$1
shift
case "$command" in
  verify) [[ $# == 3 ]] || usage; verify "$@" ;;
  show) [[ $# == 3 ]] || usage; show "$@" ;;
  *) usage ;;
esac
