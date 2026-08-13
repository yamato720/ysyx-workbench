#!/usr/bin/env bash
set -euo pipefail

npc_root=${1:?用法：run-fpga-rtl-test.sh <npc-root>}
npc_root=$(realpath "$npc_root")
manager="$npc_root/scripts/construction-manager.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
export CONSTRUCTION_TEST_ROOT="$work/constructions"

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
u55c_cache_npc="$work/u55c-cache-npc"
u55c_performance_monitor="$work/u55c-performance-monitor"
u55c_cache_performance_monitor="$work/u55c-cache-performance-monitor"
u55c_cache_150mhz_performance_monitor="$work/u55c-cache-150mhz-performance-monitor"
u55c_hbm512_cache_150mhz_performance_monitor="$work/u55c-hbm512-cache-150mhz-performance-monitor"
u55c_hbm512_l2_cache_150mhz_performance_monitor="$work/u55c-hbm512-l2-cache-150mhz-performance-monitor"
u55c_100mhz_performance_monitor="$work/u55c-100mhz-performance-monitor"
elaborate Zcu102YsyxSocFpgaConfig "$zcu_soc"
elaborate Zcu102NpcFpgaConfig "$zcu_npc"
elaborate U55cYsyxSocFpgaConfig "$u55c_soc"
elaborate U55cRv64Npc300MHzFpgaConfig "$u55c_rv64_npc"
elaborate U55cCacheNpcFpgaConfig "$u55c_cache_npc"
elaborate U55cRv64Npc300MHzPerformanceMonitorFpgaConfig "$u55c_performance_monitor"
elaborate U55cRv64CacheNpc300MHzPerformanceMonitorFpgaConfig "$u55c_cache_performance_monitor"
elaborate U55cRv64CacheNpc150MHzPerformanceMonitorFpgaConfig "$u55c_cache_150mhz_performance_monitor"
elaborate U55cRv64Hbm512CacheNpc150MHzPerformanceMonitorFpgaConfig "$u55c_hbm512_cache_150mhz_performance_monitor"
elaborate U55cRv64Hbm512L2CacheNpc150MHzPerformanceMonitorFpgaConfig "$u55c_hbm512_l2_cache_150mhz_performance_monitor"
elaborate U55cRv64Npc100MHzPerformanceMonitorFpgaConfig "$u55c_100mhz_performance_monitor"

mapfile -d '' -t zcu_soc_rtl < <(find "$zcu_soc/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
mapfile -d '' -t zcu_npc_rtl < <(find "$zcu_npc/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
mapfile -d '' -t u55c_rv64_npc_rtl < <(find "$u55c_rv64_npc/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
mapfile -d '' -t u55c_cache_npc_rtl < <(find "$u55c_cache_npc/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
mapfile -d '' -t u55c_performance_monitor_rtl < <(find "$u55c_performance_monitor/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
mapfile -d '' -t u55c_cache_performance_monitor_rtl < <(find "$u55c_cache_performance_monitor/rtl" -type f \( -name '*.v' -o -name '*.sv' \) -print0 | sort -z)
(( ${#zcu_soc_rtl[@]} > 1 )) || { echo 'ysyx FPGA elaboration 未按模块拆分 RTL' >&2; exit 1; }
(( ${#zcu_npc_rtl[@]} > 1 )) || { echo '裸 NPC FPGA elaboration 未按模块拆分 RTL' >&2; exit 1; }
[[ -f $u55c_soc/rtl/NpcFpgaTop.sv ]] || { echo 'U55C 未生成 NpcFpgaTop' >&2; exit 1; }
grep -q 'Zcu102YsyxFpgaShell.scala' "$zcu_soc/rtl/NpcFpgaTop.sv" || {
  echo 'ZCU102 elaboration 未选择 ZCU102 SoC shell' >&2; exit 1;
}
grep -q 'U55cYsyxFpgaShell.scala' "$u55c_soc/rtl/NpcFpgaTop.sv" || {
  echo 'U55C elaboration 未选择 U55C SoC shell' >&2; exit 1;
}
grep -qx 'CONFIG_FQCN=ysyx.fpga.zcu102.Zcu102YsyxSocFpgaConfig' "$zcu_soc/rtl/fpga-parameters.env"
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
grep -qx 'FPGA_RUNTIME_TRACE=1' "$u55c_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'FPGA_RUNTIME_SDB=0' "$u55c_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'FPGA_RUNTIME_TRACE=1' "$u55c_cache_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'FPGA_RUNTIME_SDB=0' "$u55c_cache_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'ICACHE_ENABLED=1' "$u55c_cache_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'DCACHE_ENABLED=1' "$u55c_cache_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'FPGA_CLOCK_MHZ=150' "$u55c_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'FPGA_PLATFORM_CLOCK_MHZ=300' "$u55c_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'ICACHE_ENABLED=1' "$u55c_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'DCACHE_ENABLED=1' "$u55c_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'NPC_AXI_MEMORY_DATA_WIDTH=512' "$u55c_hbm512_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'ICACHE_LINE_BYTES=64' "$u55c_hbm512_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'DCACHE_LINE_BYTES=64' "$u55c_hbm512_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -Eq '\[511:0\].*io_master_w_bits_data' "$u55c_hbm512_cache_150mhz_performance_monitor/rtl/NpcFpgaTop.sv" || {
  echo '512-bit HBM cache RTL 的主写数据端口不是 512 位' >&2; exit 1;
}
grep -Eq '\[511:0\].*io_master_r_bits_data' "$u55c_hbm512_cache_150mhz_performance_monitor/rtl/NpcFpgaTop.sv" || {
  echo '512-bit HBM cache RTL 的主读数据端口不是 512 位' >&2; exit 1;
}
grep -qx 'NPC_AXI_MEMORY_DATA_WIDTH=512' "$u55c_hbm512_l2_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'L2CACHE_ENABLED=1' "$u55c_hbm512_l2_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'L2CACHE_CAPACITY_BYTES=262144' "$u55c_hbm512_l2_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'L2CACHE_LINE_BYTES=64' "$u55c_hbm512_l2_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'L2CACHE_WAYS=8' "$u55c_hbm512_l2_cache_150mhz_performance_monitor/rtl/fpga-parameters.env"
grep -Rqs '^module UnifiedL2Cache' "$u55c_hbm512_l2_cache_150mhz_performance_monitor/rtl" || {
  echo 'L2 HBM cache RTL 缺少 UnifiedL2Cache' >&2; exit 1;
}
grep -Eq '\[511:0\].*io_master_w_bits_data' "$u55c_hbm512_l2_cache_150mhz_performance_monitor/rtl/NpcFpgaTop.sv" || {
  echo '512-bit L2 HBM cache RTL 的主写数据端口不是 512 位' >&2; exit 1;
}
grep -Eq '\[511:0\].*io_master_r_bits_data' "$u55c_hbm512_l2_cache_150mhz_performance_monitor/rtl/NpcFpgaTop.sv" || {
  echo '512-bit L2 HBM cache RTL 的主读数据端口不是 512 位' >&2; exit 1;
}
grep -qx 'FPGA_RUNTIME_SDB=1' "$u55c_rv64_npc/rtl/fpga-parameters.env"
grep -qx 'ICACHE_ENABLED=1' "$u55c_cache_npc/rtl/fpga-parameters.env"
grep -qx 'DCACHE_ENABLED=1' "$u55c_cache_npc/rtl/fpga-parameters.env"
grep -Rqs '^module InstructionCache' "$u55c_cache_npc/rtl" || {
  echo '缓存 U55C RTL 缺少 InstructionCache' >&2; exit 1;
}
grep -Rqs '^module DataCache' "$u55c_cache_npc/rtl" || {
  echo '缓存 U55C RTL 缺少 DataCache' >&2; exit 1;
}
grep -Rqs '^module CacheMaintenanceController' "$u55c_cache_npc/rtl" || {
  echo '缓存 U55C RTL 缺少维护控制器' >&2; exit 1;
}
grep -qx 'FPGA_TRACE_DATA_WIDTH=256' "$u55c_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'FPGA_TRACE_BURST_RECORDS=16' "$u55c_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'FPGA_CLOCK_MHZ=100' "$u55c_100mhz_performance_monitor/rtl/fpga-parameters.env"
grep -qx 'FPGA_PLATFORM_CLOCK_MHZ=300' "$u55c_100mhz_performance_monitor/rtl/fpga-parameters.env"
grep -Rqs 'performance_monitor_uram_fifo' "$u55c_performance_monitor/rtl" || {
  echo '性能监测 RTL 缺少命名 URAM FIFO' >&2; exit 1;
}
if grep -Rqs '^module FpgaDebugController' "$u55c_performance_monitor/rtl"; then
  echo '性能监测 RTL 不应综合 SDB halt/step 控制器' >&2; exit 1
fi
if grep -Rqs '^module FpgaDebugController' "$u55c_cache_performance_monitor/rtl"; then
  echo '缓存性能监测 RTL 不应综合 SDB halt/step 控制器' >&2; exit 1
fi
grep -Rqs '^module InstructionCache' "$u55c_cache_performance_monitor/rtl" || {
  echo '缓存性能监测 RTL 缺少 InstructionCache' >&2; exit 1;
}
grep -Rqs '^module DataCache' "$u55c_cache_performance_monitor/rtl" || {
  echo '缓存性能监测 RTL 缺少 DataCache' >&2; exit 1;
}
grep -Eq '\[255:0\].*io_trace_w_bits_data' "$u55c_performance_monitor/rtl/NpcFpgaTop.sv" || {
  echo '性能监测 trace 写数据端口不是 256 位' >&2; exit 1;
}
grep -Eq '\[7:0\].*io_trace_aw_bits_len' "$u55c_performance_monitor/rtl/NpcFpgaTop.sv" || {
  echo '性能监测 trace burst length 端口缺失' >&2; exit 1;
}

verilator --binary --timing -Wno-fatal --top-module FpgaRuntimeTraceWriterTb \
  --Mdir "$work/runtime-trace-writer" \
  "$u55c_performance_monitor/rtl/FpgaRuntimeTraceWriter.sv" \
  "$u55c_performance_monitor/rtl/performance_monitor_uram_fifo_2048x256.sv" \
  "$npc_root/fpga/common/tests/fpga-runtime-trace-writer-tb.sv" >/dev/null
"$work/runtime-trace-writer/VFpgaRuntimeTraceWriterTb"

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

verilator --binary --timing -Wno-fatal -Wno-PINMISSING --top-module FpgaCacheDrainTb \
  --Mdir "$work/cache-drain" "${u55c_cache_npc_rtl[@]}" \
  "$npc_root/fpga/common/tests/fpga-cache-drain-tb.sv" >/dev/null
"$work/cache-drain/VFpgaCacheDrainTb"

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
