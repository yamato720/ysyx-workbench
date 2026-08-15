#!/usr/bin/env bash
# 在临时构造目录中生成硬件、Verilator ABI、NEMU host 和 FPGA 资产。
set -euo pipefail

usage() {
  echo "用法：$0 <workspace-root> <stage-dir> <profile.env>" >&2
  exit 2
}

[[ $# == 3 ]] || usage
workspace=$(realpath "$1")
stage=$(realpath "$2")
profile=$(realpath "$3")
npc_root="$workspace/npc"
# construction-manager 在启动时会把本脚本与直接调用的 helper 固定到构造目录。
# 因而长时间 FPGA 构造期间可以继续修改工作区脚本，而不会让已经开始的 shell
# 在后续阶段读取到半写入的版本。直接执行本脚本时仍自然使用 npc/scripts/。
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
phase_log="$script_dir/phase-log.sh"
refresh_simulation_host="$script_dir/refresh-simulation-host.sh"
ip_source_manifest="$script_dir/ip-source-manifest.sh"
for helper in "$phase_log" "$refresh_simulation_host" "$ip_source_manifest"; do
  [[ -x $helper ]] || { echo "构造 helper 不可执行：$helper" >&2; exit 2; }
done

while IFS='=' read -r key value; do
  [[ $key =~ ^[A-Z][A-Z0-9_]*$ && $value != *$'\n'* && $value != *$'\r'* ]] || {
    echo "拒绝加载非法 profile 字段：$key" >&2; exit 2;
  }
  printf -v "$key" '%s' "$value"
  export "$key"
done < "$profile"

mkdir -p "$stage/logs/build"
if [[ $SCOPE == spmv ]]; then
  mkdir -p "$stage/abi/rtl" "$stage/abi/verilator" "$stage/abi/spmv"
elif [[ $CAPABILITY == synthesize-only || $CAPABILITY == bitstream-only ]]; then
  mkdir -p "$stage/fpga"
else
  mkdir -p "$stage/abi/rtl" "$stage/abi/verilator" "$stage/abi/nemu" \
    "$stage/abi/softfloat" "$stage/abi/glue/include" "$stage/abi/glue/src"
fi

phase_logs="$stage/logs/build"
printf 'BUILD_DRIVER_SNAPSHOT=%s\n' "$script_dir" >> "$phase_logs/all.log"
local_work_dir="$stage/.work"
local_obj_dir="$local_work_dir/intermediate"
local_chisel_out="$local_work_dir/generated"
local_chisel_dpi_out="$local_work_dir/generated-dpi"
local_soc_sim_dir="$local_work_dir/soc-sim"

root_construction_work_args() {
  if [[ $SCOPE == npc || $SCOPE == soc || $SCOPE == spmv ]]; then
    printf '%s\0' \
      "obj_dir=$local_obj_dir" \
      "CHISEL_OUT=$local_chisel_out" \
      "CHISEL_DPI_OUT=$local_chisel_dpi_out" \
      "SOC_SIM_DIR=$local_soc_sim_dir" \
      "SPMV_SIM_OUT=$local_work_dir/spmv-rtl" \
      "SPMV_SIM_MODEL_DIR=$local_work_dir/spmv-verilator"
  fi
}

run_phase() {
  local phase=$1 index=$2 total=$3
  shift 3
  "$phase_log" run "$phase_logs" "$phase" "$index" "$total" -- "$@"
}

note_phase() {
  "$phase_log" note "$phase_logs" "$1" "$2" "$3" "$4"
}

run_root_phase() {
  local phase=$1 index=$2 total=$3 target=$4
  local -a work_args=()
  shift 4
  while IFS= read -r -d '' argument; do work_args+=("$argument"); done < <(root_construction_work_args)
  "$phase_log" run "$phase_logs" "$phase" "$index" "$total" -- \
    make -C "$npc_root" "$target" "$@" INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" \
    CONSTRUCTION_PROFILE="$profile" NPC_VCD_TRACE="${NEMU_VCD:-0}" "${work_args[@]}"
}

run_root_phase_visible() {
  local phase=$1 index=$2 total=$3 target=$4
  local -a work_args=()
  shift 4
  while IFS= read -r -d '' argument; do work_args+=("$argument"); done < <(root_construction_work_args)
  PHASE_LOG_PASSTHROUGH=1 "$phase_log" run "$phase_logs" "$phase" "$index" "$total" -- \
    make -C "$npc_root" "$target" "$@" INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" \
    CONSTRUCTION_PROFILE="$profile" NPC_VCD_TRACE="${NEMU_VCD:-0}" "${work_args[@]}"
}

copy_glue() {
  local file
  for file in "$npc_root"/csrc/*.h; do cp "$file" "$stage/abi/glue/include/"; done
  for file in "$npc_root"/csrc/npc_core.cpp "$npc_root"/csrc/pmem.cpp \
    "$npc_root"/csrc/soc_dpi.cpp "$npc_root"/csrc/fp_dpi.cpp; do
    [[ -f $file ]] && cp "$file" "$stage/abi/glue/src/"
  done
}

refresh_host() {
  local index=$1 total=$2
  if [[ ${CONSTRUCTION_TEST_HOST_FAIL:-0} == 1 ]]; then
    run_phase nemu-host "$index" "$total" bash -c 'echo "按测试请求模拟 NEMU host 构造失败" >&2; exit 1'
    return
  fi
  run_phase nemu-host "$index" "$total" \
    "$refresh_simulation_host" "$workspace" "$stage"
}

dry_run() {
  local total
  if [[ -n ${CONSTRUCTION_TEST_HOLD_DIR:-} ]]; then
    mkdir -p "$CONSTRUCTION_TEST_HOLD_DIR"
    : > "$CONSTRUCTION_TEST_HOLD_DIR/ready"
    while [[ ! -e $CONSTRUCTION_TEST_HOLD_DIR/release ]]; do sleep 0.02; done
  fi
  case "$CAPABILITY:$SCOPE" in
    generate-only:npc|generate-only:soc)
      total=1
      note_phase chisel 1 "$total" "dry-run $SCOPE Chisel 生成"
      ;;
    run:npc|run:soc)
      total=4
      note_phase chisel 1 "$total" "dry-run $SCOPE Chisel 生成"
      note_phase softfloat 2 "$total" 'dry-run SoftFloat 构建'
      note_phase verilator 3 "$total" 'dry-run Verilator 库构建'
      ;;
    run:spmv)
      total=3
      note_phase elaborate 1 "$total" 'dry-run SPMV elaboration'
      note_phase verilator 2 "$total" 'dry-run SPMV Verilator 模型构建'
      note_phase accelerator-host 3 "$total" 'dry-run SPMV accelerator host 构建'
      ;;
    run:fpga|batch:fpga)
      total=5
      note_phase elaborate 1 "$total" 'dry-run FPGA elaboration'
      note_phase ip 2 "$total" 'dry-run FPGA IP 生成'
      note_phase synth 3 "$total" 'dry-run FPGA 综合'
      note_phase link 4 "$total" 'dry-run FPGA 链接'
      ;;
    synthesize-only:fpga)
      total=2
      note_phase elaborate 1 "$total" 'dry-run SPMV elaboration'
      note_phase ooc-synth 2 "$total" 'dry-run SPMV OOC 综合'
      ;;
    bitstream-only:fpga)
      total=3
      note_phase elaborate 1 "$total" 'dry-run SPMV elaboration'
      note_phase ooc-synth 2 "$total" 'dry-run SPMV OOC 综合'
      note_phase link 3 "$total" 'dry-run SPMV Vitis link'
      ;;
    *) echo "Config $CONFIG_FQCN 的能力/作用域不支持 dry-run：$CAPABILITY/$SCOPE" >&2; exit 2 ;;
  esac

  if [[ $SCOPE == spmv ]]; then
    printf 'module SpmvInputTop; endmodule\n' > \
      "$stage/abi/rtl/SpmvInputTop.sv"
  elif [[ $CAPABILITY != synthesize-only && $CAPABILITY != bitstream-only ]]; then
    printf 'dry-run\n' > "$stage/abi/rtl/placeholder.sv"
  fi
  if [[ $SCOPE == spmv ]]; then
    printf 'dry-run header\n' > "$stage/abi/verilator/VSpmvInputTop.h"
    printf 'dry-run model\n' > "$stage/abi/verilator/libVSpmvInputTop.a"
    printf 'dry-run runtime\n' > "$stage/abi/verilator/libverilated.a"
    printf '#!/usr/bin/env bash\necho "SPMV construction dry-run host"\n' > "$stage/abi/spmv/spmv-host"
    chmod +x "$stage/abi/spmv/spmv-host"
    {
      printf '%s\n' 'HOST_FORMAT=12' "CONFIG_FQCN=$CONFIG_FQCN" \
        'ACCELERATOR_HOST_KIND=spmv' 'ACCELERATOR_HOST_ABI=spmv-input-report-v12' \
        'PROTOCOL_ABI=spmv-input-windowed-v11'
      printf 'SPMV_INPUT_A_READER_COUNT=%s\nSPMV_INPUT_X_READER_COUNT=%s\nSPMV_INPUT_CTRL_READER_COUNT=%s\n' \
        "$SPMV_INPUT_A_READER_COUNT" "$SPMV_INPUT_X_READER_COUNT" "$SPMV_INPUT_CTRL_READER_COUNT"
      printf 'SPMV_INPUT_HBM_CHANNEL_COUNT=%s\nSPMV_INPUT_HBM_BASE=%s\nSPMV_INPUT_HBM_BYTES=%s\nSPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES=%s\n' \
        "$SPMV_INPUT_HBM_CHANNEL_COUNT" "$SPMV_INPUT_HBM_BASE" "$SPMV_INPUT_HBM_BYTES" "$SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES"
      printf 'SPMV_INPUT_AXI_ADDR_WIDTH=%s\nSPMV_INPUT_AXI_DATA_WIDTH=%s\nSPMV_INPUT_AXI_ID_WIDTH=%s\nSPMV_INPUT_MAX_OUTSTANDING_BURSTS=%s\n' \
        "$SPMV_INPUT_AXI_ADDR_WIDTH" "$SPMV_INPUT_AXI_DATA_WIDTH" "$SPMV_INPUT_AXI_ID_WIDTH" "$SPMV_INPUT_MAX_OUTSTANDING_BURSTS"
      printf 'SPMV_INPUT_CONSUMER_COUNT=%s\nSPMV_INPUT_X_BROADCAST=%s\nSPMV_INPUT_CTRL_BROADCAST=%s\n' \
        "$SPMV_INPUT_CONSUMER_COUNT" "$SPMV_INPUT_X_BROADCAST" "$SPMV_INPUT_CTRL_BROADCAST"
      printf 'SPMV_INPUT_X_WINDOW_SIZE=%s\nSPMV_INPUT_X_REPLICA_COUNT=%s\nSPMV_INPUT_X_BANK_COUNT=%s\nSPMV_INPUT_X_ELEMENT_WIDTH=%s\n' \
        "$SPMV_INPUT_X_WINDOW_SIZE" "$SPMV_INPUT_X_REPLICA_COUNT" "$SPMV_INPUT_X_BANK_COUNT" "$SPMV_INPUT_X_ELEMENT_WIDTH"
      printf 'SPMV_CUPER_SLOT_ABI=%s\nSPMV_CUPER_SLOT_COLUMN_BITS=%s\nSPMV_CUPER_SLOT_TAG_BITS=%s\nSPMV_CUPER_SLOT_ROW_BITS=%s\n' \
        "$SPMV_CUPER_SLOT_ABI" "$SPMV_CUPER_SLOT_COLUMN_BITS" "$SPMV_CUPER_SLOT_TAG_BITS" "$SPMV_CUPER_SLOT_ROW_BITS"
      printf 'SPMV_FP64_MUL_INTERFACE=%s\nSPMV_FP64_MUL_LATENCY=%s\nSPMV_FP64_MUL_II=%s\nSPMV_FP64_MUL_RESPONSE_FIFO_DEPTH=%s\nSPMV_FP64_MUL_LANES=%s\nSPMV_FP64_MUL_CORE_COUNT=%s\nSPMV_FP64_MUL_TOTAL_LANES=%s\n' \
        "$SPMV_FP64_MUL_INTERFACE" "$SPMV_FP64_MUL_LATENCY" "$SPMV_FP64_MUL_II" "$SPMV_FP64_MUL_RESPONSE_FIFO_DEPTH" "$SPMV_FP64_MUL_LANES" "$SPMV_FP64_MUL_CORE_COUNT" "$SPMV_FP64_MUL_TOTAL_LANES"
      printf 'SPMV_PERFORMANCE_HTML=%s\nSPMV_PIPELINE_HTML=%s\n' \
        "$SPMV_PERFORMANCE_HTML" "$SPMV_PIPELINE_HTML"
    } > "$stage/abi/spmv/host.env"
  fi
  if [[ $SCOPE == fpga ]]; then
    local artifacts asset
    artifacts="$stage/fpga/artifacts"
    mkdir -p "$stage/fpga/rtl" "$stage/fpga/synth" "$artifacts"
    if [[ $CAPABILITY == synthesize-only ]]; then
      printf '%s\n' 'module SpmvResourceProbeTop; endmodule' > "$stage/fpga/rtl/SpmvResourceProbeTop.sv"
      printf '%s\n' 'module SpmvResourceProbeKernel; endmodule' > "$stage/fpga/rtl/spmv-resource-probe-kernel.sv"
      "$ip_source_manifest" write synthesis \
        "$stage/fpga/synthesis-sources.manifest" "$npc_root" --absolute \
        --rtl-dir "$stage/fpga/rtl"
      local -a spmv_assets=(
        spmv-resource-probe.xo
        spmv-resource-probe.dcp
        spmv-utilization.rpt
        spmv-utilization-hierarchical.rpt
        spmv-timing-summary.rpt
      )
      for asset in "${spmv_assets[@]}"; do printf 'dry-run %s\n' "$asset" > "$artifacts/$asset"; done
      local -a spmv_manifest_args=()
      for asset in "${spmv_assets[@]}"; do spmv_manifest_args+=(--asset "$asset"); done
      "$npc_root/fpga/common/scripts/artifact-manifest.sh" write \
        --directory "$artifacts" --source-root "$workspace" --release-tag UNRELEASED \
        --board "$FPGA_BOARD" --variant "$CONFIG_FQCN" --type "$FPGA_TYPE" \
        --platform "${FPGA_PLATFORM:-none}" \
        --config-fqcn "$CONFIG_FQCN" --host-abi "$HOST_ABI" --protocol-abi "$PROTOCOL_ABI" \
        "${spmv_manifest_args[@]}"
      [[ ${CONSTRUCTION_TEST_FAIL:-0} != 1 ]] || {
        printf '%s\n' '按测试请求模拟构造失败' | tee -a "$phase_logs/all.log" >&2
        exit 1
      }
      return
    fi
    if [[ $CAPABILITY == bitstream-only ]]; then
      printf '%s\n' 'module SpmvResourceProbeTop; endmodule' > "$stage/fpga/rtl/SpmvResourceProbeTop.sv"
      printf '%s\n' 'module SpmvResourceProbeKernel; endmodule' > "$stage/fpga/rtl/spmv-resource-probe-kernel.sv"
	      "$ip_source_manifest" write synthesis \
	        "$stage/fpga/synthesis-sources.manifest" "$npc_root" --absolute \
	        --rtl-dir "$stage/fpga/rtl"
	      local -a spmv_assets=(
	        spmv-resource-probe.xclbin
	        spmv-resource-probe.xo
	        spmv-resource-probe.dcp
	        spmv-utilization.rpt
	        spmv-utilization-hierarchical.rpt
	        spmv-timing-summary.rpt
	      )
	      for asset in "${spmv_assets[@]}"; do printf 'dry-run %s\n' "$asset" > "$artifacts/$asset"; done
	      printf '0.000\n' > "$artifacts/spmv-resource-probe.wns"
	      local -a spmv_manifest_args=()
	      for asset in "${spmv_assets[@]}"; do spmv_manifest_args+=(--asset "$asset"); done
	      "$npc_root/fpga/common/scripts/artifact-manifest.sh" write \
	        --directory "$artifacts" --source-root "$workspace" --release-tag UNRELEASED \
	        --board "$FPGA_BOARD" --variant "$CONFIG_FQCN" --type "$FPGA_TYPE" \
	        --platform "${FPGA_PLATFORM:-none}" --config-fqcn "$CONFIG_FQCN" \
	        --host-abi "$HOST_ABI" --protocol-abi "$PROTOCOL_ABI" --timing-wns 0.000 \
	        "${spmv_manifest_args[@]}"
      [[ ${CONSTRUCTION_TEST_FAIL:-0} != 1 ]] || {
        printf '%s\n' '按测试请求模拟构造失败' | tee -a "$phase_logs/all.log" >&2
        exit 1
      }
      return
    fi
    mkdir -p "$stage/fpga/ip-generated" "$stage/fpga/link"
    printf '%s\n' 'module NpcFpgaTop; endmodule' > "$stage/fpga/rtl/NpcFpgaTop.sv"
    "$ip_source_manifest" write synthesis \
      "$stage/fpga/rtl/ip-sources.manifest" "$npc_root" --absolute \
      --rtl "$stage/fpga/rtl/NpcFpgaTop.sv"
    "$ip_source_manifest" write synthesis \
      "$stage/fpga/synthesis-sources.manifest" "$npc_root" --absolute \
      --rtl "$stage/fpga/rtl/NpcFpgaTop.sv" \
      --rtl-dir "$npc_root/fpga/$FPGA_BOARD/rtl" \
      --rtl-dir "$npc_root/fpga-ip-generator/common/compute/source/sv"
    mkdir -p "$stage/fpga/ip-generated/logs"
    printf '%s\n' 'dry-run Vivado multiplier IP' > "$stage/fpga/ip-generated/logs/npc_int_multiplier_ip.log"
    printf '%s\n' 'dry-run Vivado divider IP' > "$stage/fpga/ip-generated/logs/npc_int_divider_ip.log"
    assets=()
    case "$FPGA_BOARD" in
      u55c)
        assets+=("npc-$FPGA_PLATFORM.xclbin")
        printf 'dry-run xclbin\n' > "$artifacts/${assets[0]}"
        ;;
      zcu102)
        assets+=(npc.bit npc.xsa system-user.dtsi npc-zcu102.env)
        for asset in "${assets[@]}"; do printf 'dry-run %s\n' "$asset" > "$artifacts/$asset"; done
        ;;
      *) echo "dry-run 不支持 FPGA 板卡 $FPGA_BOARD" >&2; exit 2 ;;
    esac
    manifest_args=()
    for asset in "${assets[@]}"; do manifest_args+=(--asset "$asset"); done
    "$npc_root/fpga/common/scripts/artifact-manifest.sh" write \
      --directory "$artifacts" --source-root "$workspace" --release-tag UNRELEASED \
      --board "$FPGA_BOARD" --variant "$CONFIG_FQCN" --type "$FPGA_TYPE" \
      --platform "${FPGA_PLATFORM:-none}" \
      --config-fqcn "$CONFIG_FQCN" --host-abi "$HOST_ABI" --protocol-abi "$PROTOCOL_ABI" \
      --timing-wns 0.000 "${manifest_args[@]}"
  fi
  [[ ${CONSTRUCTION_TEST_FAIL:-0} != 1 ]] || {
    printf '%s\n' '按测试请求模拟构造失败' | tee -a "$phase_logs/all.log" >&2
    exit 1
  }
  if [[ $SCOPE != spmv && ($CAPABILITY == run || $CAPABILITY == batch) ]]; then
    refresh_host "$total" "$total"
  fi
}

if [[ ${CONSTRUCTION_DRY_RUN:-0} == 1 ]]; then
  dry_run
  [[ $SCOPE == spmv || $CAPABILITY == synthesize-only || $CAPABILITY == bitstream-only ]] || copy_glue
  exit 0
fi

# NEMU 编译通过保存构造的 glue include 引用 NPC 调试 ABI；必须先冻结这些
# 头文件，再进入末尾的 host 阶段。源码副本不依赖 Chisel/Verilator 产物。
[[ $SCOPE == spmv || $CAPABILITY == synthesize-only || $CAPABILITY == bitstream-only ]] || copy_glue

case "$CAPABILITY:$SCOPE" in
  generate-only:npc)
    run_root_phase chisel 1 1 chisel
    cp -a "$local_chisel_out/." "$stage/abi/rtl/"
    ;;
  generate-only:soc)
    run_phase chisel 1 1 make -C "$npc_root/chisel/ysyxSoC" verilog \
      INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" CONSTRUCTION_PROFILE="$profile"
    cp "$npc_root/chisel/ysyxSoC/build/ysyxSoCFull.v" "$stage/abi/rtl/"
    "$ip_source_manifest" copy \
      "$npc_root/chisel/ysyxSoC/build/ip-sources.manifest" "$npc_root" "$stage/abi/rtl/sources"
    cp "$npc_root/chisel/ysyxSoC/build/ip-sources.manifest" "$stage/abi/rtl/ip-sources.manifest"
    ;;
  run:npc)
    run_root_phase chisel 1 4 chisel-dpi
    run_root_phase softfloat 2 4 softfloat-lib
    run_root_phase verilator 3 4 chisel-cpu-lib CONSTRUCTION_PHASE_PREREQUISITES=0
    cp -a "$local_chisel_dpi_out/." "$stage/abi/rtl/"
    cp -a "$local_obj_dir/chisel-cpu-lib/." "$stage/abi/verilator/"
    cp -a "$local_obj_dir/softfloat/." "$stage/abi/softfloat/"
    refresh_host 4 4
    ;;
  run:soc)
    run_root_phase chisel 1 4 soc-sim-verilog
    run_root_phase softfloat 2 4 softfloat-lib
    run_root_phase verilator 3 4 soc-nemu-lib CONSTRUCTION_PHASE_PREREQUISITES=0
    cp "$local_soc_sim_dir/ysyxSoCFull.v" "$stage/abi/rtl/"
    "$ip_source_manifest" copy \
      "$local_soc_sim_dir/ip-sources.manifest" "$npc_root" "$stage/abi/rtl/sources"
    cp "$local_soc_sim_dir/ip-sources.manifest" "$stage/abi/rtl/ip-sources.manifest"
    cp -a "$local_obj_dir/soc-nemu-lib/." "$stage/abi/verilator/"
    cp -a "$local_obj_dir/softfloat/." "$stage/abi/softfloat/"
    refresh_host 4 4
    ;;
  run:spmv)
    run_root_phase elaborate 1 3 spmv-sim-elaborate
    run_root_phase verilator 2 3 spmv-sim-verilator CONSTRUCTION_PHASE_PREREQUISITES=0
    cp -a "$local_work_dir/spmv-rtl/." "$stage/abi/rtl/"
    cp -a "$local_work_dir/spmv-verilator/." "$stage/abi/verilator/"
    run_phase accelerator-host 3 3 make --no-print-directory -C "$workspace/accelerator-sim/spmv" \
      build-simulation-host CONSTRUCTION_DIR="$stage" BUILD_DIR="$stage/abi/spmv"
    ;;
  run:fpga|batch:fpga)
    run_root_phase elaborate 1 5 fpga-elaborate FPGA_WORK_DIR="$stage/fpga"
    run_root_phase_visible ip 2 5 fpga-ip FPGA_WORK_DIR="$stage/fpga"
    run_root_phase_visible synth 3 5 fpga-synth FPGA_WORK_DIR="$stage/fpga" FPGA_PHASE_PREREQUISITES=0
    run_root_phase_visible link 4 5 fpga-link FPGA_WORK_DIR="$stage/fpga" FPGA_PHASE_PREREQUISITES=0
    refresh_host 5 5
    ;;
  synthesize-only:fpga)
    run_root_phase elaborate 1 2 spmv-elaborate SPMV_WORK_DIR="$stage/fpga"
    run_root_phase_visible ooc-synth 2 2 spmv-ooc-synth SPMV_WORK_DIR="$stage/fpga" \
      SPMV_PHASE_PREREQUISITES=0
    ;;
	  bitstream-only:fpga)
	    run_root_phase elaborate 1 3 spmv-elaborate SPMV_WORK_DIR="$stage/fpga"
	    run_root_phase_visible ooc-synth 2 3 spmv-ooc-synth SPMV_WORK_DIR="$stage/fpga" \
	      SPMV_PHASE_PREREQUISITES=0
	    run_root_phase_visible link 3 3 spmv-link SPMV_WORK_DIR="$stage/fpga" \
	      SPMV_PHASE_PREREQUISITES=0
	    ;;
	  *) echo "Config $CONFIG_FQCN 的能力/作用域不可构造：$CAPABILITY/$SCOPE" >&2; exit 2 ;;
esac

# 本地构造已把工作输出复制到冻结 ABI；成功后不把可再生的 Make 中间目录带入
# 保存构造。失败时故意保留，便于 construction manager 归档现场。
if [[ $SCOPE == npc || $SCOPE == soc || $SCOPE == spmv ]]; then
  rm -rf "$local_work_dir"
fi
