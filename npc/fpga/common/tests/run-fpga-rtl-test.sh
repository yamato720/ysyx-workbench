#!/usr/bin/env bash
set -euo pipefail

npc_root=${1:?用法：run-fpga-rtl-test.sh <npc-root>}
npc_root=$(realpath "$npc_root")
manager="$npc_root/scripts/construction-manager.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

"$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
export NPC_CONFIG_CATALOG_READY=1

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
u55c_rv64_npc="$work/u55c-rv64-npc"
u55c_rv64_debug="$work/u55c-rv64-debug"
elaborate Zcu102YsyxSocFpgaConfig "$zcu_soc"
elaborate Zcu102NpcFpgaConfig "$zcu_npc"
elaborate U55cYsyxSocFpgaConfig "$u55c_soc"
elaborate U55cRv64Npc300MHzFpgaConfig "$u55c_rv64_npc"
elaborate U55cRv64Npc300MHzDebugFpgaConfig "$u55c_rv64_debug"

mapfile -d '' -t zcu_soc_rtl < <(find "$zcu_soc/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
mapfile -d '' -t zcu_npc_rtl < <(find "$zcu_npc/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
mapfile -d '' -t u55c_rv64_npc_rtl < <(find "$u55c_rv64_npc/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
(( ${#zcu_soc_rtl[@]} > 1 )) || { echo 'ysyx FPGA elaboration 未按模块拆分 RTL' >&2; exit 1; }
(( ${#zcu_npc_rtl[@]} > 1 )) || { echo '裸 NPC FPGA elaboration 未按模块拆分 RTL' >&2; exit 1; }
[[ -f $u55c_soc/rtl/NpcFpgaTop.sv ]] || { echo 'U55C 未生成 NpcFpgaTop' >&2; exit 1; }
grep -q 'Zcu102YsyxFpgaShell.scala' "$zcu_soc/rtl/NpcFpgaTop.sv" || {
  echo 'ZCU102 elaboration 未选择 ZCU102 SoC shell' >&2; exit 1;
}
grep -q 'U55cYsyxFpgaShell.scala' "$u55c_soc/rtl/NpcFpgaTop.sv" || {
  echo 'U55C elaboration 未选择 U55C SoC shell' >&2; exit 1;
}
grep -qx 'CONFIG_FQCN=npc.fpga.zcu102.Zcu102YsyxSocFpgaConfig' "$zcu_soc/rtl/fpga-parameters.env"
grep -qx 'FPGA_BOARD=zcu102' "$zcu_soc/rtl/fpga-parameters.env"
grep -qx 'FPGA_BOARD=u55c' "$u55c_soc/rtl/fpga-parameters.env"

grep -Rqs '^module ysyxSoCASIC' "$zcu_soc/rtl" || { echo 'SoC interconnect 缺失' >&2; exit 1; }
if grep -Rqs '^module ysyxSoCASIC' "$zcu_npc/rtl"; then
  echo '裸 NPC 构造意外包含 ysyxSoC' >&2; exit 1
fi
if grep -RqsE 'DPI-C|mrom_read|pmem_read|mmio_read_impl' "$zcu_soc/rtl"; then
  echo 'ysyx FPGA RTL 仍含 DPI 依赖' >&2; exit 1
fi
if grep -RqsE '^module (FloatingAlu|FloatingRegisterFile|FpgaFloating)' \
    "$zcu_soc/rtl" "$zcu_npc/rtl" "$u55c_soc/rtl"; then
  echo 'FPGA RTL 仍含已禁止的算子 assist 或本地浮点模块' >&2; exit 1
fi
if grep -q '^OPERATOR_ROUTE_F_' "$zcu_npc/rtl/fpga-parameters.env"; then
  echo 'FPGA elaboration profile 仍含浮点算子路由' >&2; exit 1
fi
grep -Rqs '^module MulDivAlu' "$zcu_npc/rtl" || { echo 'FPGA RTL 缺少整数乘除单元' >&2; exit 1; }
grep -Rqs '^module FpgaRuntimeMailbox' "$zcu_npc/rtl" || { echo 'FPGA RTL 缺少 v6 runtime mailbox' >&2; exit 1; }
if grep -Rqs 'io_trace_aw_valid' "$u55c_rv64_npc/rtl"; then
  echo '普通 U55C RTL 不应包含 trace AXI 端口' >&2; exit 1
fi
grep -Rqs 'io_trace_aw_valid' "$u55c_rv64_debug/rtl" || { echo 'Debug U55C RTL 缺少 trace AXI 端口' >&2; exit 1; }
grep -Rqs 'trace_uram_fifo' "$u55c_rv64_debug/rtl" || { echo 'Debug U55C RTL 缺少命名 URAM FIFO' >&2; exit 1; }
[[ -f $u55c_rv64_debug/rtl/trace_uram_fifo_4096x576.sv ]] || {
  echo 'Debug U55C RTL 未按 Config 生成 4096x576 trace URAM FIFO' >&2; exit 1;
}

verilator --binary --timing -Wno-fatal -Wno-PINMISSING --top-module FpgaIntegerMultiplierAdapterTb \
  --Mdir "$work/integer-multiplier" "$npc_root/fpga-ip-generator/common/compute/source/sv/npc-integer-ip-adapters.sv" \
  "$npc_root/fpga/common/tests/fpga-integer-multiplier-adapter-tb.sv" >/dev/null
"$work/integer-multiplier/VFpgaIntegerMultiplierAdapterTb"

verilator --binary --timing -Wno-fatal -Wno-PINMISSING --top-module FpgaIntegerDividerAdapterTb -GNON_BLOCKING=0 \
  --Mdir "$work/integer-divider-blocking" "$npc_root/fpga-ip-generator/common/compute/source/sv/npc-integer-ip-adapters.sv" \
  "$npc_root/fpga/common/tests/fpga-integer-divider-adapter-tb.sv" >/dev/null
"$work/integer-divider-blocking/VFpgaIntegerDividerAdapterTb"

verilator --binary --timing -Wno-fatal -Wno-PINMISSING -DNPC_TEST_DIVIDER_NON_BLOCKING \
  --top-module FpgaIntegerDividerAdapterTb -GNON_BLOCKING=1 \
  --Mdir "$work/integer-divider-nonblocking" "$npc_root/fpga-ip-generator/common/compute/source/sv/npc-integer-ip-adapters.sv" \
  "$npc_root/fpga/common/tests/fpga-integer-divider-adapter-tb.sv" >/dev/null
"$work/integer-divider-nonblocking/VFpgaIntegerDividerAdapterTb"

verilator --binary --timing -Wno-fatal -Wno-PINMISSING --top-module FpgaDebugControlTb \
  --Mdir "$work/debug" "${zcu_npc_rtl[@]}" "$npc_root/fpga/common/tests/fpga-debug-control-tb.sv" >/dev/null
"$work/debug/VFpgaDebugControlTb"

verilator --binary --timing -Wno-fatal -Wno-PINMISSING -Wno-WIDTHEXPAND --top-module FpgaMachineExternalInterruptTb \
  --Mdir "$work/machine-external-interrupt" "${zcu_npc_rtl[@]}" \
  "$npc_root/fpga-ip-generator/common/compute/source/sv/npc-integer-ip-adapters.sv" \
  "$npc_root/fpga/common/tests/fpga-integer-ip-stubs.sv" \
  "$npc_root/fpga/common/tests/fpga-machine-external-interrupt-tb.sv" >/dev/null
"$work/machine-external-interrupt/VFpgaMachineExternalInterruptTb"

verilator --binary --timing -Wno-fatal -Wno-PINMISSING -Wno-WIDTHEXPAND -DNPC_TEST_RV64 \
  --top-module FpgaMachineExternalInterruptTb --Mdir "$work/machine-external-interrupt-rv64" \
  "${u55c_rv64_npc_rtl[@]}" "$npc_root/fpga-ip-generator/common/compute/source/sv/npc-integer-ip-adapters.sv" \
  "$npc_root/fpga/common/tests/fpga-integer-ip-stubs.sv" \
  "$npc_root/fpga/common/tests/fpga-machine-external-interrupt-tb.sv" >/dev/null
"$work/machine-external-interrupt-rv64/VFpgaMachineExternalInterruptTb"

verilator --binary --timing -Wno-fatal -Wno-PINMISSING -Wno-WIDTHEXPAND \
  --top-module FpgaMachineExternalInterruptBackendTb --Mdir "$work/machine-external-interrupt-backend" \
  "${zcu_npc_rtl[@]}" "$npc_root/fpga-ip-generator/common/compute/source/sv/npc-integer-ip-adapters.sv" \
  "$npc_root/fpga/common/tests/fpga-integer-ip-stubs.sv" \
  "$npc_root/fpga/common/tests/fpga-machine-external-interrupt-backend-tb.sv" >/dev/null
"$work/machine-external-interrupt-backend/VFpgaMachineExternalInterruptBackendTb"
