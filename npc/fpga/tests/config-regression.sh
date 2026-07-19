#!/usr/bin/env bash
set -euo pipefail

npc_root=${1:?用法：config-regression.sh <npc-root>}
npc_root=$(realpath "$npc_root")
catalog="$npc_root/chisel/configs/resources/scpu-config-catalog.tsv"
resolver="$npc_root/scripts/resolve-config.sh"
manager="$npc_root/scripts/construction-manager.sh"
manifest_tool="$npc_root/fpga/common/scripts/manifest.sh"
ip_generator="$npc_root/fpga/ip/generators/xilinx/create-arithmetic-ip.tcl"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

fail() {
  printf 'FPGA Config 回归失败：%s\n' "$*" >&2
  exit 1
}

"$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
export SCPU_CONFIG_CATALOG_READY=1

for name in U55cNpcFpgaConfig U55cFullIsa64NpcFpgaConfig U55cYsyxSocFpgaConfig Zcu102NpcFpgaConfig Zcu102YsyxSocFpgaConfig; do
  grep -Eq "^${name}[[:space:]]" "$catalog" || fail "自动目录缺少 $name"
done
[[ $($resolver "$catalog" U55cYsyxSocFpgaConfig fpga-soc) == 'scpu.fpga.u55c.U55cYsyxSocFpgaConfig|fpga-soc|u55c|SOC' ]] ||
  fail '短名解析结果错误'
[[ $($resolver "$catalog" scpu.fpga.u55c.U55cYsyxSocFpgaConfig fpga-soc) == 'scpu.fpga.u55c.U55cYsyxSocFpgaConfig|fpga-soc|u55c|SOC' ]] ||
  fail 'FQCN 解析结果错误'
if $resolver "$catalog" U55cYsyxSocFpgaConfig npc >/dev/null 2>&1; then fail '作用域错误未被拒绝'; fi
if $resolver "$catalog" UnknownConfig fpga-soc >/dev/null 2>&1; then fail '未知 Config 未被拒绝'; fi

check_terminal() {
  local config=$1 expected_board=$2 expected_target=$3 expected_xlen=$4 resolved profile output
  resolved=$($manager resolve "$npc_root" "$config" '')
  profile=${resolved##*|}
  output=$(make --no-print-directory -s -C "$npc_root" fpga-config \
    INTERNAL_CONSTRUCTION=1 config="$config" CONSTRUCTION_PROFILE="$profile" FPGA_TOOL_DRY_RUN=1)
  grep -qx "board=$expected_board" <<< "$output" || fail "$config 板卡错误"
  grep -qx "target=$expected_target" <<< "$output" || fail "$config 目标错误"
  grep -qx "xlen=$expected_xlen" <<< "$output" || fail "$config XLEN 错误"
  grep -qx 'clock_mhz=300' <<< "$output" || fail "$config 频率错误"
  grep -qx 'vivado_synth_jobs=4' <<< "$output" || fail "$config 综合并行度错误"
  grep -qx 'vivado_impl_jobs=8' <<< "$output" || fail "$config 实现并行度错误"
  grep -qx 'vivado_impl_strategy_search=0' <<< "$output" || fail "$config 默认不应启用多策略实现"
  grep -qx 'floating_fallback=host-mailbox' <<< "$output" || fail "$config 浮点回退策略错误"
  grep -qx 'backend=fpga' <<< "$output" || fail "$config 未使用 FPGA 算术策略"
  grep -qx 'HOST_ABI=nemu-construction-v1' "$profile" || fail "$config host ABI 缺失"
  grep -qx 'PROTOCOL_ABI=npc-fpga-mailbox-v2' "$profile" || fail "$config 协议 ABI 缺失"
  grep -qx 'FPGA_VIVADO_SYNTH_JOBS=4' "$profile" || fail "$config 综合并行 profile 缺失"
  grep -qx 'FPGA_VIVADO_IMPL_JOBS=8' "$profile" || fail "$config 实现并行 profile 缺失"
  grep -qx 'FPGA_VIVADO_IMPL_STRATEGY_SEARCH=0' "$profile" || fail "$config 多策略实现 profile 缺失"
  grep -qx 'FPGA_FLOATING_FALLBACK=host-mailbox' "$profile" || fail "$config 浮点回退 profile 缺失"
}

check_terminal U55cNpcFpgaConfig u55c NPC 32
check_terminal U55cFullIsa64NpcFpgaConfig u55c NPC 64
check_terminal U55cYsyxSocFpgaConfig u55c SOC 32
check_terminal Zcu102NpcFpgaConfig zcu102 NPC 32
check_terminal Zcu102YsyxSocFpgaConfig zcu102 SOC 32

profile=$($manager resolve "$npc_root" U55cYsyxSocFpgaConfig '' | awk -F'|' '{print $NF}')
bad_profile="$work/bad-profile.env"
sed 's/^FPGA_CLOCK_MHZ=.*/FPGA_CLOCK_MHZ=301/' "$profile" > "$bad_profile"
if make --no-print-directory -s -C "$npc_root" fpga-config INTERNAL_CONSTRUCTION=1 \
  config=U55cYsyxSocFpgaConfig CONSTRUCTION_PROFILE="$bad_profile" FPGA_TOOL_DRY_RUN=1 >/dev/null 2>&1; then
  fail 'Scala profile 与板卡 config.mk 的漂移未被拒绝'
fi
if make --no-print-directory -s -C "$npc_root" build config=U55cYsyxSocFpgaConfig NPC_XLEN=64 >/dev/null 2>&1; then
  fail '公开结构覆盖变量 NPC_XLEN 未被拒绝'
fi
if make --no-print-directory -s -C "$npc_root" fpga-link config=U55cYsyxSocFpgaConfig >/dev/null 2>&1; then
  fail '旧 FPGA 目标仍可公开调用'
fi

if grep -Eq 'create_fpo|create_ip[[:space:]]+-name[[:space:]]+floating_point|npc_fp_(addsub|multiplier|divider|fma|sqrt|convert|compare)_ip' "$ip_generator"; then
  fail '整数 FPGA IP 生成器仍包含未使用的浮点 IP'
fi
make --no-print-directory -s -C "$npc_root/../nemu" fpga-mailbox-test
manifest="$work/manifest.env"
"$manifest_tool" write "$manifest" BOARD=u55c LATENCY=3 II=1
"$manifest_tool" verify "$manifest" II=1 BOARD=u55c LATENCY=3
sed -i 's/LATENCY=3/LATENCY=4/' "$manifest"
if "$manifest_tool" verify "$manifest" BOARD=u55c LATENCY=3 II=1 >/dev/null 2>&1; then
  fail '实现清单接受了被篡改的时序参数'
fi

printf 'FPGA Config 回归通过\n'
