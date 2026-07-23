#!/usr/bin/env bash
set -euo pipefail

npc_root=${1:?用法：config-regression.sh <npc-root>}
npc_root=$(realpath "$npc_root")
catalog="$npc_root/chisel/configs/resources/scpu-config-catalog.tsv"
resolver="$npc_root/scripts/resolve-config.sh"
manager="$npc_root/scripts/construction-manager.sh"
manifest_tool="$npc_root/fpga/common/scripts/manifest.sh"
ip_generator="$npc_root/fpga/ip/generators/xilinx/create-arithmetic-ip.tcl"
implementation_reports_tcl="$npc_root/fpga/common/tcl/implementation-reports.tcl"
u55c_build_mk="$npc_root/fpga/build.mk"
zcu102_link_tcl="$npc_root/fpga/boards/zcu102/tcl/link.tcl"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
export CONSTRUCTION_TEST_ROOT="$work/constructions"

fail() {
  printf 'FPGA Config 回归失败：%s\n' "$*" >&2
  exit 1
}

"$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
export SCPU_CONFIG_CATALOG_READY=1

if rg -q 'Fpga(ToolSettings|BuildSettings)Key|WithFpga(Tool|BuildSettings)Config' \
  "$npc_root/chisel/configs" "$npc_root/chisel/fpga-harness/src"; then
  fail 'FPGA CDE 图仍包含工具链或 build settings key'
fi

for name in U55cNpcFpgaConfig U55cFullIsa64NpcFpgaConfig U55cFullIsa64Npc250MHzFpgaConfig U55cYsyxSocFpgaConfig Zcu102NpcFpgaConfig Zcu102YsyxSocFpgaConfig; do
  grep -Eq "^${name}[[:space:]]" "$catalog" || fail "自动目录缺少 $name"
done
[[ $($resolver "$catalog" U55cYsyxSocFpgaConfig fpga) == 'scpu.fpga.u55c.U55cYsyxSocFpgaConfig|fpga|u55c|SOC' ]] ||
  fail '短名解析结果错误'
[[ $($resolver "$catalog" scpu.fpga.u55c.U55cYsyxSocFpgaConfig fpga) == 'scpu.fpga.u55c.U55cYsyxSocFpgaConfig|fpga|u55c|SOC' ]] ||
  fail 'FQCN 解析结果错误'
if $resolver "$catalog" U55cYsyxSocFpgaConfig npc >/dev/null 2>&1; then fail '作用域错误未被拒绝'; fi
if $resolver "$catalog" UnknownConfig fpga >/dev/null 2>&1; then fail '未知 Config 未被拒绝'; fi

check_terminal() {
  local config=$1 expected_board=$2 expected_target=$3 expected_xlen=$4 expected_clock=$5 expected_xrt_mode resolved profile output
  case "$config" in
    U55c*) expected_xrt_mode=unset ;;
    Zcu102*) expected_xrt_mode=inherit ;;
    *) fail "$config 缺少 Vitis XRT 环境策略预期" ;;
  esac
  resolved=$($manager resolve "$npc_root" "$config" '')
  profile=${resolved##*|}
  output=$(make --no-print-directory -s -C "$npc_root" fpga-config \
    INTERNAL_CONSTRUCTION=1 config="$config" CONSTRUCTION_PROFILE="$profile" FPGA_TOOL_DRY_RUN=1)
  grep -qx "board=$expected_board" <<< "$output" || fail "$config 板卡错误"
  grep -qx "target=$expected_target" <<< "$output" || fail "$config 目标错误"
  grep -qx "xlen=$expected_xlen" <<< "$output" || fail "$config XLEN 错误"
  grep -qx "clock_mhz=$expected_clock" <<< "$output" || fail "$config 频率错误"
  grep -qx 'vivado_synth_jobs=4' <<< "$output" || fail "$config 综合并行度错误"
  grep -qx 'vivado_impl_jobs=8' <<< "$output" || fail "$config 实现并行度错误"
  grep -qx 'vivado_impl_strategy_candidate=Performance_ExplorePostRoutePhysOpt' <<< "$output" || fail "$config 实现策略候选错误"
  grep -qx 'vivado_impl_strategy_search=0' <<< "$output" || fail "$config 默认不应启用多策略实现"
  grep -qx 'vivado_impl_strategy_mode=platform-default' <<< "$output" || fail "$config 实现策略模式错误"
  grep -qx 'vivado_report_timing_max_paths=50' <<< "$output" || fail "$config 时序报告最大路径数错误"
  grep -qx 'vivado_report_timing_paths_per_clock=10' <<< "$output" || fail "$config 每时钟时序路径数错误"
  grep -qx 'vivado_report_congestion=1' <<< "$output" || fail "$config 拥塞报告开关错误"
  grep -qx 'vivado_report_clock_utilization=1' <<< "$output" || fail "$config 时钟利用率报告开关错误"
  grep -qx 'vivado_report_control_sets=1' <<< "$output" || fail "$config 控制集报告开关错误"
  grep -qx 'vivado_report_high_fanout_nets=1' <<< "$output" || fail "$config 高扇出报告开关错误"
  grep -qx 'vivado_report_methodology=1' <<< "$output" || fail "$config 方法学报告开关错误"
  grep -qx 'vivado_report_qor_suggestions=1' <<< "$output" || fail "$config QoR 建议报告开关错误"
  grep -qx 'floating_fallback=host-mailbox' <<< "$output" || fail "$config 浮点回退策略错误"
  grep -qx "vitis_xrt_mode=$expected_xrt_mode" <<< "$output" || fail "$config Vitis XRT 环境策略错误"
  grep -qx 'backend=fpga' <<< "$output" || fail "$config 未使用 FPGA 算术策略"
  grep -qx 'HOST_ABI=nemu-construction-v1' "$profile" || fail "$config host ABI 缺失"
  grep -qx 'PROTOCOL_ABI=npc-fpga-mailbox-v3' "$profile" || fail "$config 协议 ABI 缺失"
  grep -qx 'FPGA_VIVADO_SYNTH_JOBS=4' "$profile" || fail "$config 综合并行 profile 缺失"
  grep -qx 'FPGA_VIVADO_IMPL_JOBS=8' "$profile" || fail "$config 实现并行 profile 缺失"
  grep -qx 'FPGA_VIVADO_IMPL_STRATEGY_SEARCH=0' "$profile" || fail "$config 多策略实现 profile 缺失"
  grep -qx 'FPGA_REPORT_TIMING_MAX_PATHS=50' "$profile" || fail "$config 时序报告最大路径数 profile 缺失"
  grep -qx 'FPGA_REPORT_TIMING_PATHS_PER_CLOCK=10' "$profile" || fail "$config 每时钟时序路径数 profile 缺失"
  grep -qx 'FPGA_REPORT_CONGESTION=1' "$profile" || fail "$config 拥塞报告 profile 缺失"
  grep -qx 'FPGA_REPORT_CLOCK_UTILIZATION=1' "$profile" || fail "$config 时钟利用率报告 profile 缺失"
  grep -qx 'FPGA_REPORT_CONTROL_SETS=1' "$profile" || fail "$config 控制集报告 profile 缺失"
  grep -qx 'FPGA_REPORT_HIGH_FANOUT_NETS=1' "$profile" || fail "$config 高扇出报告 profile 缺失"
  grep -qx 'FPGA_REPORT_METHODOLOGY=1' "$profile" || fail "$config 方法学报告 profile 缺失"
  grep -qx 'FPGA_REPORT_QOR_SUGGESTIONS=1' "$profile" || fail "$config QoR 建议报告 profile 缺失"
  grep -qx 'FPGA_FLOATING_FALLBACK=host-mailbox' "$profile" || fail "$config 浮点回退 profile 缺失"
  grep -qx "FPGA_VITIS_XRT_MODE=$expected_xrt_mode" "$profile" || fail "$config Vitis XRT 环境策略 profile 缺失"
  case "$config" in
    Zcu102*) grep -qx 'FPGA_NOTIFICATION_MODE=ps-uio-irq' "$profile" || fail "$config ZCU102 通知模式错误" ;;
    U55c*) grep -qx 'FPGA_NOTIFICATION_MODE=xrt-poll' "$profile" || fail "$config U55C 通知模式错误" ;;
  esac
  check_operator_routes "$profile" "$expected_xlen" "$config"
}

check_operator_routes() {
  local profile=$1 xlen=$2 config=$3 operation key
  local -a multiply=(mul mulh mulhsu mulhu mulw)
  local -a divide=(div divu rem remu divw divuw remw remuw)
  local -a numeric_f=(fadd fsub fmul fdiv fsqrt fmadd fmsub fnmsub fnmadd \
    fcvt_w fcvt_wu fcvt_l fcvt_lu fcvt_s_w fcvt_s_wu fcvt_s_l fcvt_s_lu)
  local -a direct_f=(fsgnj fsgnjn fsgnjx fmin fmax feq flt fle fmv_x_w fclass fmv_w_x)

  for operation in "${multiply[@]}"; do
    key=${operation^^}
    grep -Eq "^OPERATOR_ROUTE_M_${key}=vendor-ip:npc_int_multiplier_adapter:${xlen}:[1-9][0-9]*:[1-9][0-9]*:none$" "$profile" ||
      fail "$config 的 M 乘法路由错误：$operation"
  done
  for operation in "${divide[@]}"; do
    key=${operation^^}
    grep -Eq "^OPERATOR_ROUTE_M_${key}=vendor-ip:npc_int_divider_adapter:${xlen}:[1-9][0-9]*:[1-9][0-9]*:none$" "$profile" ||
      fail "$config 的 M 除法路由错误：$operation"
  done
  for operation in "${numeric_f[@]}"; do
    key=${operation^^}
    grep -Eq "^OPERATOR_ROUTE_F_${key}=host-fallback:host-mailbox:${xlen}:[1-9][0-9]*:[1-9][0-9]*:fpo-riscv-incompatible$" "$profile" ||
      fail "$config 的 F 数值回退路由错误：$operation"
  done
  for operation in "${direct_f[@]}"; do
    key=${operation^^}
    grep -Eq "^OPERATOR_ROUTE_F_${key}=direct-logic:fpga_f32_direct_logic:${xlen}:[1-9][0-9]*:[1-9][0-9]*:none$" "$profile" ||
      fail "$config 的 F 直接逻辑路由错误：$operation"
  done
}

check_terminal U55cNpcFpgaConfig u55c NPC 32 125
check_terminal U55cFullIsa64NpcFpgaConfig u55c NPC 64 125
check_terminal U55cFullIsa64Npc250MHzFpgaConfig u55c NPC 64 250
check_terminal U55cYsyxSocFpgaConfig u55c SOC 32 125
check_terminal Zcu102NpcFpgaConfig zcu102 NPC 32 300
check_terminal Zcu102YsyxSocFpgaConfig zcu102 SOC 32 300

# profile 缓存必须随着 Scala Config 输入变化失效；否则 `resolve` 能在板卡已调频后
# 仍返回旧 profile，直到构造阶段的强制刷新才暴露问题。
profile=$($manager resolve "$npc_root" U55cFullIsa64NpcFpgaConfig '' | awk -F'|' '{print $NF}')
sed -i 's/^FPGA_CLOCK_MHZ=.*/FPGA_CLOCK_MHZ=300/' "$profile"
printf 'stale-profile-inputs\n' > "$profile.inputs.sha256"
profile=$($manager resolve "$npc_root" U55cFullIsa64NpcFpgaConfig '' | awk -F'|' '{print $NF}')
grep -qx 'FPGA_CLOCK_MHZ=125' "$profile" || fail 'profile 输入变更后没有重新生成'

profile=$($manager resolve "$npc_root" U55cYsyxSocFpgaConfig '' | awk -F'|' '{print $NF}')
bad_profile="$work/bad-profile.env"
sed 's/^FPGA_CLOCK_MHZ=.*/FPGA_CLOCK_MHZ=301/' "$profile" > "$bad_profile"
if make --no-print-directory -s -C "$npc_root" fpga-config INTERNAL_CONSTRUCTION=1 \
  config=U55cYsyxSocFpgaConfig CONSTRUCTION_PROFILE="$bad_profile" FPGA_TOOL_DRY_RUN=1 >/dev/null 2>&1; then
  fail 'Scala profile 与板卡 config.mk 的漂移未被拒绝'
fi
custom_profile="$work/custom-toolchain-profile.env"
sed -e 's/^FPGA_PART=.*/FPGA_PART=custom-compatible-part/' \
  -e 's/^FPGA_VITIS_XRT_MODE=.*/FPGA_VITIS_XRT_MODE=inherit/' \
  -e 's/^FPGA_VIVADO_IMPL_JOBS=.*/FPGA_VIVADO_IMPL_JOBS=12/' \
  -e 's/^FPGA_REPORT_QOR_SUGGESTIONS=.*/FPGA_REPORT_QOR_SUGGESTIONS=0/' \
  "$profile" > "$custom_profile"
custom_output=$(make --no-print-directory -s -C "$npc_root" fpga-config INTERNAL_CONSTRUCTION=1 \
  config=U55cYsyxSocFpgaConfig CONSTRUCTION_PROFILE="$custom_profile" FPGA_TOOL_DRY_RUN=1)
make --no-print-directory -s -C "$npc_root" fpga-check INTERNAL_CONSTRUCTION=1 \
  config=U55cYsyxSocFpgaConfig CONSTRUCTION_PROFILE="$custom_profile" FPGA_TOOL_DRY_RUN=1
grep -qx 'part=custom-compatible-part' <<< "$custom_output" || fail '自定义 device.part 未被 FPGA recipe 消费'
grep -qx 'vitis_xrt_mode=inherit' <<< "$custom_output" || fail '自定义 flow.vitisXrtMode 未被 FPGA recipe 消费'
grep -qx 'vivado_impl_jobs=12' <<< "$custom_output" || fail '自定义 flow 实现并行度未被 FPGA recipe 消费'
grep -qx 'vivado_report_qor_suggestions=0' <<< "$custom_output" || fail '自定义 reports 开关未被 FPGA recipe 消费'
if make --no-print-directory -s -C "$npc_root" build config=U55cYsyxSocFpgaConfig NPC_XLEN=64 >/dev/null 2>&1; then
  fail '公开结构覆盖变量 NPC_XLEN 未被拒绝'
fi
if make --no-print-directory -s -C "$npc_root" fpga-link config=U55cYsyxSocFpgaConfig >/dev/null 2>&1; then
  fail '旧 FPGA 目标仍可公开调用'
fi

if grep -Eq 'create_fpo|create_ip[[:space:]]+-name[[:space:]]+floating_point|npc_fp_(addsub|multiplier|divider|fma|sqrt|convert|compare)_ip' "$ip_generator"; then
  fail '整数 FPGA IP 生成器仍包含未使用的浮点 IP'
fi
grep -q 'IP_LOG_DIR' "$ip_generator" || fail '整数 FPGA IP 生成器没有接收逐 IP 日志目录'
grep -q 'write_ip_log_header' "$ip_generator" || fail '整数 FPGA IP 生成器没有逐 IP 日志头'
grep -q 'create_integer_ip npc_int_multiplier_ip' "$ip_generator" || fail '整数 FPGA IP 生成器没有乘法 IP 日志调用'
grep -q 'create_integer_ip npc_int_divider_ip' "$ip_generator" || fail '整数 FPGA IP 生成器没有除法 IP 日志调用'
grep -q 'generate_target all' "$ip_generator" || fail '整数 FPGA IP 生成器没有生成 Vivado 输出产品'
grep -q 'append_ip_property' "$ip_generator" || fail '整数 FPGA IP 生成器没有记录已生效属性'
if grep -q 'redirect -tee -append -file' "$ip_generator"; then
  fail '整数 FPGA IP 生成器不能使用 Vivado 2022.2 不支持的 redirect 命令'
fi
[[ -f $implementation_reports_tcl ]] || fail '缺少共享实现报告 Tcl'
grep -q 'report_timing_summary' "$implementation_reports_tcl" || fail '共享实现报告 Tcl 缺少时序摘要'
grep -q 'report_timing ' "$implementation_reports_tcl" || fail '共享实现报告 Tcl 缺少多路径时序报告'
grep -q 'report_design_analysis' "$implementation_reports_tcl" || fail '共享实现报告 Tcl 缺少拥塞报告'
grep -q 'report_clock_utilization' "$implementation_reports_tcl" || fail '共享实现报告 Tcl 缺少时钟利用率报告'
grep -q 'report_control_sets' "$implementation_reports_tcl" || fail '共享实现报告 Tcl 缺少控制集报告'
grep -q 'report_high_fanout_nets' "$implementation_reports_tcl" || fail '共享实现报告 Tcl 缺少高扇出报告'
grep -q 'report_methodology' "$implementation_reports_tcl" || fail '共享实现报告 Tcl 缺少方法学报告'
grep -q 'report_qor_suggestions' "$implementation_reports_tcl" || fail '共享实现报告 Tcl 缺少 QoR 建议报告'
grep -q 'npc_optional_implementation_report' "$implementation_reports_tcl" || fail '共享实现报告 Tcl 没有隔离可选报告错误'
grep -q 'FPGA_U55C_REPORT_HOOK' "$u55c_build_mk" || fail 'U55C 构建未生成实现报告 hook'
grep -q 'STEPS.ROUTE_DESIGN.TCL.POST' "$u55c_build_mk" || fail 'U55C 构建未接入 post-route hook'
grep -q 'env -u XILINX_XRT' "$u55c_build_mk" || fail 'U55C 构建未按 Config 隔离 Vitis XRT 环境'
grep -q 'implementation_reports_tcl' "$zcu102_link_tcl" || fail 'ZCU102 链接 Tcl 未接入共享实现报告'
grep -q 'source \$implementation_reports_tcl' "$zcu102_link_tcl" || fail 'ZCU102 链接 Tcl 未执行共享实现报告'
make --no-print-directory -s -C "$npc_root/../nemu" fpga-mailbox-test
manifest="$work/manifest.env"
"$manifest_tool" write "$manifest" BOARD=u55c LATENCY=3 II=1
"$manifest_tool" verify "$manifest" II=1 BOARD=u55c LATENCY=3
sed -i 's/LATENCY=3/LATENCY=4/' "$manifest"
if "$manifest_tool" verify "$manifest" BOARD=u55c LATENCY=3 II=1 >/dev/null 2>&1; then
  fail '实现清单接受了被篡改的时序参数'
fi

printf 'FPGA Config 回归通过\n'
