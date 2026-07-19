#!/usr/bin/env bash
set -euo pipefail

npc_root=${1:?用法：run-fpga-rtl-test.sh <npc-root>}
npc_root=$(realpath "$npc_root")
manager="$npc_root/scripts/construction-manager.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

"$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
export SCPU_CONFIG_CATALOG_READY=1

elaborate() {
  local config=$1 output=$2 resolved profile
  resolved=$($manager resolve "$npc_root" "$config" '')
  profile=${resolved##*|}
  make --no-print-directory -s -C "$npc_root" fpga-elaborate \
    INTERNAL_CONSTRUCTION=1 config="$config" CONSTRUCTION_PROFILE="$profile" \
    FPGA_WORK_DIR="$output" FPGA_SKIP_TOOL_VERSION_CHECK=1 >/dev/null
}

zcu_soc="$work/zcu102-soc"
zcu_npc="$work/zcu102-npc"
u55c_soc="$work/u55c-soc"
elaborate Zcu102YsyxSocFpgaConfig "$zcu_soc"
elaborate Zcu102NpcFpgaConfig "$zcu_npc"
elaborate U55cYsyxSocFpgaConfig "$u55c_soc"

mapfile -d '' -t zcu_soc_rtl < <(find "$zcu_soc/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
mapfile -d '' -t zcu_npc_rtl < <(find "$zcu_npc/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
(( ${#zcu_soc_rtl[@]} > 1 )) || { echo 'ysyx FPGA elaboration 未按模块拆分 RTL' >&2; exit 1; }
(( ${#zcu_npc_rtl[@]} > 1 )) || { echo '裸 NPC FPGA elaboration 未按模块拆分 RTL' >&2; exit 1; }
[[ -f $u55c_soc/rtl/NpcFpgaTop.sv ]] || { echo 'U55C 未生成 NpcFpgaTop' >&2; exit 1; }
grep -q 'Zcu102YsyxFpgaShell.scala' "$zcu_soc/rtl/NpcFpgaTop.sv" || {
  echo 'ZCU102 elaboration 未选择 ZCU102 SoC shell' >&2; exit 1;
}
grep -q 'U55cYsyxFpgaShell.scala' "$u55c_soc/rtl/NpcFpgaTop.sv" || {
  echo 'U55C elaboration 未选择 U55C SoC shell' >&2; exit 1;
}
grep -qx 'CONFIG_FQCN=scpu.fpga.zcu102.Zcu102YsyxSocFpgaConfig' "$zcu_soc/rtl/fpga-parameters.env"
grep -qx 'FPGA_BOARD=zcu102' "$zcu_soc/rtl/fpga-parameters.env"
grep -qx 'FPGA_BOARD=u55c' "$u55c_soc/rtl/fpga-parameters.env"

grep -Rqs '^module ysyxSoCASIC' "$zcu_soc/rtl" || { echo 'SoC interconnect 缺失' >&2; exit 1; }
if grep -Rqs '^module ysyxSoCASIC' "$zcu_npc/rtl"; then
  echo '裸 NPC 构造意外包含 ysyxSoC' >&2; exit 1
fi
if grep -RqsE 'DPI-C|mrom_read|pmem_read|mmio_read_impl' "$zcu_soc/rtl"; then
  echo 'ysyx FPGA RTL 仍含 DPI 依赖' >&2; exit 1
fi

verilator --binary --timing -Wno-fatal -Wno-PINMISSING --top-module FpgaFloatingDirectTb \
  --Mdir "$work/direct" "${zcu_npc_rtl[@]}" "$npc_root/fpga/tests/fpga-floating-direct-tb.sv" >/dev/null
"$work/direct/VFpgaFloatingDirectTb"

verilator --binary --timing -Wno-fatal -Wno-PINMISSING --top-module FpgaIntegerMultiplierAdapterTb \
  --Mdir "$work/integer-multiplier" "$npc_root/fpga/ip/adapters/xilinx/npc-integer-ip-adapters.sv" \
  "$npc_root/fpga/tests/fpga-integer-multiplier-adapter-tb.sv" >/dev/null
"$work/integer-multiplier/VFpgaIntegerMultiplierAdapterTb"

verilator --binary --timing -Wno-fatal -Wno-PINMISSING --top-module FpgaMailboxResetTb \
  --Mdir "$work/mailbox" "${zcu_soc_rtl[@]}" "$npc_root/fpga/tests/fpga-mailbox-reset-tb.sv" >/dev/null
"$work/mailbox/VFpgaMailboxResetTb"

verilator --binary --timing -Wno-fatal -Wno-PINMISSING --top-module FpgaDebugControlTb \
  --Mdir "$work/debug" "${zcu_npc_rtl[@]}" "$npc_root/fpga/tests/fpga-debug-control-tb.sv" >/dev/null
"$work/debug/VFpgaDebugControlTb"
