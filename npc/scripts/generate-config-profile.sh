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
catalog="$npc_root/chisel/configs/resources/npc-config-catalog.tsv"

if [[ ${NPC_CONFIG_CATALOG_READY:-0} != 1 ]]; then
  "$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
fi
resolved=$("$npc_root/scripts/resolve-config.sh" "$catalog" "$request" 'npc,soc,spmv,fpga')
[[ $resolved != !* ]] || { echo "${resolved#!}" >&2; exit 2; }
IFS='|' read -r fqcn scope board target <<< "$resolved"

mkdir -p "$(dirname "$output")"
temporary="$output.tmp.$$"
log="$output.log.tmp.$$"
trap 'rm -f "$temporary" "$log"' EXIT

case "$scope" in
  npc)
    if ! (cd "$npc_root" && NPC_SCALA_CONFIG="$fqcn" sbt \
      "root/runMain npc.DescribeNpcConfig $temporary") >"$log" 2>&1; then
      echo "生成 $fqcn profile 失败：" >&2
      cat "$log" >&2
      exit 1
    fi
    ;;
  soc)
    if ! (cd "$npc_root/chisel/ysyxSoC" && NPC_SCALA_CONFIG="$fqcn" mill -i \
      ysyxsoc.runMain ysyx.DescribeConfig "$temporary") >"$log" 2>&1; then
      echo "生成 $fqcn profile 失败：" >&2
      cat "$log" >&2
      exit 1
    fi
    ;;
  spmv)
    if ! (cd "$npc_root" && NPC_SCALA_CONFIG="$fqcn" sbt \
      "root/runMain accelerators.spmv.DescribeSpmvInputSimulationConfig $temporary") >"$log" 2>&1; then
      echo "生成 $fqcn profile 失败：" >&2
      cat "$log" >&2
      exit 1
    fi
    ;;
  fpga)
    if [[ $target == SPMV ]]; then
      profile_main=accelerators.spmv.fpga.DescribeSpmvConfig
    else
      profile_main=ysyx.DescribeFpgaConfig
    fi
    if ! (cd "$npc_root/chisel/ysyxSoC" && NPC_SCALA_CONFIG="$fqcn" mill -i \
      ysyxsoc.runMain "$profile_main" "$temporary") >"$log" 2>&1; then
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
  $1 == "SCOPE" { scope=$2 }
  $1 == "TARGET" { target=$2 }
  $1 == "CAPABILITY" { capability=$2 }
  $1 == "HOST_ABI" { host_abi=$2 }
  $1 == "ACCELERATOR_HOST_KIND" { accelerator_host_kind=$2 }
  $1 == "ACCELERATOR_HOST_ABI" { accelerator_host_abi=$2 }
  $1 == "PROTOCOL_ABI" { protocol_abi=$2 }
  $1 == "SPMV_INPUT_X_READER_COUNT" { spmv_input_x_reader_count=$2 }
  $1 == "SPMV_CUPER_SLOT_ABI" { spmv_cuper_slot_abi=$2 }
  $1 == "SPMV_CUPER_SLOT_COLUMN_BITS" { spmv_cuper_slot_column_bits=$2 }
  $1 == "SPMV_CUPER_SLOT_TAG_BITS" { spmv_cuper_slot_tag_bits=$2 }
  $1 == "SPMV_CUPER_SLOT_ROW_BITS" { spmv_cuper_slot_row_bits=$2 }
  $1 == "SPMV_FP64_MUL_PROVIDER" { spmv_fp64_mul_provider=$2 }
  $1 == "SPMV_FP64_MUL_LATENCY" { spmv_fp64_mul_latency=$2 }
  $1 == "SPMV_FP64_MUL_CORE_COUNT" { spmv_fp64_mul_core_count=$2 }
  $1 == "SPMV_FP64_MUL_TOTAL_LANES" { spmv_fp64_mul_total_lanes=$2 }
  $1 == "SPMV_CUPERFLOW_HBM_PC_COUNT" { spmv_cuperflow_pc_count=$2 }
  $1 == "SPMV_CUPERFLOW_HBM_BASE" { spmv_cuperflow_hbm_base=$2 }
  $1 == "SPMV_CUPERFLOW_HBM_BYTES" { spmv_cuperflow_hbm_bytes=$2 }
  $1 == "SPMV_CUPERFLOW_X_REGION_BYTES" { spmv_cuperflow_x_region_bytes=$2 }
  $1 == "SPMV_CUPERFLOW_AXI_ADDR_WIDTH" { spmv_cuperflow_addr_width=$2 }
  $1 == "SPMV_CUPERFLOW_AXI_DATA_WIDTH" { spmv_cuperflow_data_width=$2 }
  $1 == "SPMV_CUPERFLOW_AXI_ID_WIDTH" { spmv_cuperflow_id_width=$2 }
  $1 == "SPMV_CUPERFLOW_MAX_OUTSTANDING_BURSTS" { spmv_cuperflow_outstanding=$2 }
  $1 == "SPMV_CUPERFLOW_ROW_BATCH_SIZE" { spmv_cuperflow_row_batch_size=$2 }
  $1 == "SPMV_CUPERFLOW_X_WINDOW_SIZE" { spmv_cuperflow_window=$2 }
  $1 == "SPMV_CUPERFLOW_X_REPLICA_COUNT" { spmv_cuperflow_replicas=$2 }
  $1 == "SPMV_CUPERFLOW_X_ELEMENT_WIDTH" { spmv_cuperflow_element_width=$2 }
  $1 == "SPMV_CUPERFLOW_X_LOAD_LANES" { spmv_cuperflow_load_lanes=$2 }
  $1 == "SPMV_CUPERFLOW_SLOT_ABI" { spmv_cuperflow_slot_abi=$2 }
  $1 == "SPMV_CUPERFLOW_MAP_ABI" { spmv_cuperflow_map_abi=$2 }
  $1 == "SPMV_CUPERFLOW_BATCH_DESCRIPTOR_ABI" { spmv_cuperflow_batch_descriptor_abi=$2 }
  $1 == "SPMV_CUPERFLOW_XRT_KERNEL" { spmv_cuperflow_xrt_kernel=$2 }
  $1 == "SPMV_PERFORMANCE_HTML" { spmv_performance_html=$2 }
  $1 == "SPMV_PIPELINE_HTML" { spmv_pipeline_html=$2 }
  seen[$1]++ { exit 1 }
  index(substr($0, index($0, "=") + 1), "\r") { exit 1 }
  END {
    if (!seen["PROFILE_FORMAT"] || !seen["CONFIG_SHORT_NAME"] || !seen["CONFIG_FQCN"] ||
        !seen["SCOPE"] || !seen["CAPABILITY"] || !seen["HOST_ABI"] ||
        !seen["PROTOCOL_ABI"] || !seen["TARGET"]) exit 1
    if (scope == "spmv" && accelerator_host_abi == "spmv-cuperflow-rtl-v4") {
      if (capability != "run" || target != "SPMV" || host_abi != "none" ||
          accelerator_host_kind != "spmv" || protocol_abi != "spmv-cuperflow-l1-v0" ||
          !seen["SPMV_CUPERFLOW_HBM_PC_COUNT"] || !seen["SPMV_CUPERFLOW_HBM_BASE"] ||
          !seen["SPMV_CUPERFLOW_HBM_BYTES"] || !seen["SPMV_CUPERFLOW_X_REGION_BYTES"] ||
          !seen["SPMV_CUPERFLOW_AXI_ADDR_WIDTH"] || !seen["SPMV_CUPERFLOW_AXI_DATA_WIDTH"] ||
          !seen["SPMV_CUPERFLOW_AXI_ID_WIDTH"] ||
          !seen["SPMV_CUPERFLOW_MAX_OUTSTANDING_BURSTS"] || !seen["SPMV_CUPERFLOW_ROW_BATCH_SIZE"] ||
          !seen["SPMV_CUPERFLOW_X_WINDOW_SIZE"] || !seen["SPMV_CUPERFLOW_X_REPLICA_COUNT"] ||
          !seen["SPMV_CUPERFLOW_X_ELEMENT_WIDTH"] || !seen["SPMV_CUPERFLOW_X_LOAD_LANES"] ||
          !seen["SPMV_CUPERFLOW_SLOT_ABI"] || !seen["SPMV_CUPERFLOW_MAP_ABI"] ||
          !seen["SPMV_CUPERFLOW_BATCH_DESCRIPTOR_ABI"] ||
          !seen["SPMV_FP64_MUL_INTERFACE"] || !seen["SPMV_FP64_MUL_PROVIDER"] ||
          !seen["SPMV_FP64_MUL_LATENCY"] || !seen["SPMV_FP64_MUL_II"] ||
          !seen["SPMV_FP64_MUL_RESPONSE_FIFO_DEPTH"] || !seen["SPMV_FP64_MUL_LANES"] ||
          !seen["SPMV_FP64_MUL_CORE_COUNT"] || !seen["SPMV_FP64_MUL_TOTAL_LANES"] ||
          !seen["SPMV_PERFORMANCE_HTML"] || !seen["SPMV_PIPELINE_HTML"] ||
          spmv_cuperflow_load_lanes != "8" || spmv_cuperflow_row_batch_size != "8192" ||
          spmv_cuperflow_slot_abi != "cuperflow-a-slot-v6" ||
          spmv_cuperflow_map_abi != "cuperflow-map-multisegment-v4" ||
          spmv_cuperflow_batch_descriptor_abi != "cuperflow-batch-desc-v1" ||
          spmv_cuperflow_pc_count !~ /^([1-9]|1[0-6])$/ ||
          spmv_fp64_mul_core_count != spmv_cuperflow_pc_count ||
          spmv_fp64_mul_total_lanes != spmv_cuperflow_pc_count * 8 ||
          seen["XLEN"] || seen["ISA_STRING"] || seen["NEMU_PRESET"] ||
          seen["NEMU_BACKEND"] || seen["PIPELINE"] || seen["FPGA_BOARD"] ||
          seen["SPMV_INPUT_A_READER_COUNT"] || seen["SPMV_INPUT_X_READER_COUNT"] ||
          seen["SPMV_INPUT_HBM_CHANNEL_COUNT"]) exit 1
      accelerator_only=1
    }
    if (scope == "spmv" && accelerator_host_abi != "spmv-cuperflow-rtl-v4") {
      if (capability != "run" || target != "SPMV" || host_abi != "none" ||
          !seen["ACCELERATOR_HOST_KIND"] || !seen["ACCELERATOR_HOST_ABI"] ||
          accelerator_host_kind != "spmv" || accelerator_host_abi != "spmv-input-report-v13" ||
          spmv_input_x_reader_count != "2" ||
          !seen["SPMV_INPUT_A_READER_COUNT"] || !seen["SPMV_INPUT_X_READER_COUNT"] ||
          !seen["SPMV_INPUT_CTRL_READER_COUNT"] ||
          !seen["SPMV_INPUT_HBM_CHANNEL_COUNT"] || !seen["SPMV_INPUT_HBM_BASE"] ||
          !seen["SPMV_INPUT_HBM_BYTES"] || !seen["SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES"] ||
          !seen["SPMV_INPUT_AXI_ADDR_WIDTH"] || !seen["SPMV_INPUT_AXI_DATA_WIDTH"] ||
          !seen["SPMV_INPUT_AXI_ID_WIDTH"] || !seen["SPMV_INPUT_MAX_OUTSTANDING_BURSTS"] ||
          !seen["SPMV_INPUT_CONSUMER_COUNT"] ||
          !seen["SPMV_INPUT_X_BROADCAST"] || !seen["SPMV_INPUT_CTRL_BROADCAST"] ||
          !seen["SPMV_INPUT_X_WINDOW_SIZE"] || !seen["SPMV_INPUT_X_REPLICA_COUNT"] ||
          !seen["SPMV_INPUT_X_BANK_COUNT"] || !seen["SPMV_INPUT_X_ELEMENT_WIDTH"] ||
          !seen["SPMV_INPUT_X_PORT_SCHEDULE"] || !seen["SPMV_INPUT_X_WRITE_LANES"] ||
          !seen["SPMV_INPUT_X_OVERLAP_LANES"] ||
          !seen["SPMV_CUPER_SLOT_ABI"] || !seen["SPMV_CUPER_SLOT_COLUMN_BITS"] ||
          !seen["SPMV_CUPER_SLOT_TAG_BITS"] || !seen["SPMV_CUPER_SLOT_ROW_BITS"] ||
          spmv_cuper_slot_abi != "cuper-a-slot-v4" ||
          spmv_cuper_slot_column_bits != "13" || spmv_cuper_slot_tag_bits != "3" ||
          spmv_cuper_slot_row_bits != "16" ||
          !seen["SPMV_FP64_MUL_INTERFACE"] || !seen["SPMV_FP64_MUL_LATENCY"] ||
          !seen["SPMV_FP64_MUL_II"] || !seen["SPMV_FP64_MUL_RESPONSE_FIFO_DEPTH"] ||
          !seen["SPMV_FP64_MUL_LANES"] || !seen["SPMV_FP64_MUL_CORE_COUNT"] ||
          !seen["SPMV_FP64_MUL_TOTAL_LANES"] ||
          !seen["SPMV_PERFORMANCE_HTML"] ||
          !seen["SPMV_PIPELINE_HTML"] || spmv_performance_html !~ /^[01]$/ ||
          spmv_pipeline_html !~ /^[01]$/ ||
          (spmv_pipeline_html == "1" && spmv_performance_html != "1") ||
          seen["XLEN"] || seen["ISA_STRING"] || seen["NEMU_PRESET"] ||
          seen["NEMU_BACKEND"] || seen["PIPELINE"] || seen["FPGA_BOARD"] ||
          seen["ICACHE_ENABLED"] || seen["DCACHE_ENABLED"] || seen["L2CACHE_ENABLED"]) exit 1
      accelerator_only=1
    }
    if (scope == "fpga" && accelerator_host_abi == "spmv-cuperflow-u55c-v4") {
      if (capability !~ /^(synthesize-only|bitstream-only)$/ || target != "SPMV" || host_abi != "none" ||
          accelerator_host_kind != "spmv" || protocol_abi != "spmv-cuperflow-l1-v0" ||
          spmv_cuperflow_xrt_kernel != "SpmvCuperflowKernel" ||
          !seen["FPGA_BOARD"] || !seen["FPGA_PART"] || !seen["FPGA_PLATFORM"] ||
          !seen["FPGA_CLOCK_MHZ"] || !seen["FPGA_PLATFORM_CLOCK_MHZ"] ||
          !seen["FPGA_VIVADO_SYNTH_JOBS"] || !seen["SPMV_CUPERFLOW_HBM_PC_COUNT"] ||
          !seen["SPMV_CUPERFLOW_HBM_BASE"] || !seen["SPMV_CUPERFLOW_HBM_BYTES"] ||
          !seen["SPMV_CUPERFLOW_X_REGION_BYTES"] || !seen["SPMV_CUPERFLOW_AXI_ADDR_WIDTH"] ||
          !seen["SPMV_CUPERFLOW_AXI_DATA_WIDTH"] || !seen["SPMV_CUPERFLOW_AXI_ID_WIDTH"] ||
          !seen["SPMV_CUPERFLOW_MAX_OUTSTANDING_BURSTS"] || !seen["SPMV_CUPERFLOW_ROW_BATCH_SIZE"] ||
          !seen["SPMV_CUPERFLOW_X_WINDOW_SIZE"] ||
          !seen["SPMV_CUPERFLOW_X_REPLICA_COUNT"] || !seen["SPMV_CUPERFLOW_X_ELEMENT_WIDTH"] ||
          !seen["SPMV_CUPERFLOW_X_LOAD_LANES"] || !seen["SPMV_CUPERFLOW_SLOT_ABI"] ||
          !seen["SPMV_CUPERFLOW_MAP_ABI"] || !seen["SPMV_CUPERFLOW_BATCH_DESCRIPTOR_ABI"] ||
          !seen["SPMV_FP64_MUL_INTERFACE"] ||
          !seen["SPMV_FP64_MUL_PROVIDER"] || !seen["SPMV_FP64_MUL_LATENCY"] ||
          !seen["SPMV_FP64_MUL_II"] || !seen["SPMV_FP64_MUL_RESPONSE_FIFO_DEPTH"] ||
          !seen["SPMV_FP64_MUL_LANES"] || !seen["SPMV_FP64_MUL_CORE_COUNT"] ||
          !seen["SPMV_FP64_MUL_TOTAL_LANES"] ||
          spmv_cuperflow_load_lanes != "8" || spmv_cuperflow_row_batch_size != "8192" ||
          spmv_cuperflow_slot_abi != "cuperflow-a-slot-v6" ||
          spmv_cuperflow_map_abi != "cuperflow-map-multisegment-v4" ||
          spmv_cuperflow_batch_descriptor_abi != "cuperflow-batch-desc-v1" ||
          spmv_cuperflow_pc_count !~ /^([1-9]|1[0-6])$/ ||
          spmv_fp64_mul_core_count != spmv_cuperflow_pc_count ||
          spmv_fp64_mul_total_lanes != spmv_cuperflow_pc_count * 8 ||
          seen["XLEN"] || seen["NEMU_PRESET"] ||
          seen["NEMU_BACKEND"] || seen["PIPELINE"] || seen["SPMV_HBM_PC_COUNT"]) exit 1
      asset_only=1
    }
    if ((capability == "synthesize-only" || capability == "bitstream-only") &&
        accelerator_host_abi != "spmv-cuperflow-u55c-v4") {
      if (scope != "fpga" || target != "SPMV" || host_abi != "none" ||
          !seen["ACCELERATOR_HOST_KIND"] || !seen["ACCELERATOR_HOST_ABI"] ||
          accelerator_host_kind != "spmv" || accelerator_host_abi != "spmv-golden-v1" ||
          !seen["FPGA_BOARD"] || !seen["FPGA_PART"] || !seen["FPGA_PLATFORM"] ||
          !seen["FPGA_CLOCK_MHZ"] || !seen["FPGA_PLATFORM_CLOCK_MHZ"] ||
          !seen["FPGA_VIVADO_SYNTH_JOBS"] || !seen["SPMV_HBM_PC_COUNT"] ||
          !seen["SPMV_AXI_ADDR_WIDTH"] || !seen["SPMV_AXI_DATA_WIDTH"] ||
          !seen["SPMV_AXI_ID_WIDTH"] || !seen["SPMV_ELEMENT_WIDTH"] ||
          !seen["SPMV_ELEMENT_FORMAT"] || !seen["SPMV_X_ELEMENTS_PER_PC"] ||
          !seen["SPMV_X_READ_ELEMENTS_PER_CYCLE"] || !seen["SPMV_X_WRITE_ELEMENTS_PER_CYCLE"] ||
          !seen["SPMV_URAM_BANKS_PER_PC"] || !seen["SPMV_URAM_BANK_DEPTH"] ||
          !seen["SPMV_PARALLEL_READ_LANES"] || !seen["SPMV_PARALLEL_WRITE_LANES"] || !seen["SPMV_X_STORAGE"] ||
          !seen["SPMV_BURST_BEATS"] || !seen["SPMV_BASE_ALIGNMENT_BYTES"] ||
          !seen["SPMV_OUTSTANDING_BURSTS_PER_PC"] ||
          !seen["SPMV_BEATS_PER_PC"] || !seen["SPMV_BURSTS_PER_PC"] ||
          !seen["SPMV_X_BYTES_PER_PC"] || !seen["SPMV_TOTAL_CACHE_BYTES"] ||
          !seen["SPMV_CLOCK_MHZ"] || seen["XLEN"] || seen["ISA_STRING"] ||
          seen["NEMU_PRESET"] || seen["NEMU_BACKEND"] || seen["PIPELINE"] ||
          seen["ICACHE_ENABLED"] || seen["DCACHE_ENABLED"] || seen["L2CACHE_ENABLED"]) exit 1
      asset_only=1
    }
    if (scope == "fpga" && capability == "run" && target == "SPMV" &&
        accelerator_host_kind == "spmv" && accelerator_host_abi == "spmv-input-u55c-runtime-v1") {
      if (host_abi != "none" || !seen["FPGA_BOARD"] || !seen["FPGA_PART"] ||
          !seen["FPGA_PLATFORM"] || !seen["FPGA_CLOCK_MHZ"] ||
          !seen["FPGA_PLATFORM_CLOCK_MHZ"] || !seen["FPGA_VIVADO_SYNTH_JOBS"] ||
          !seen["SPMV_XRT_KERNEL"] || !seen["SPMV_INPUT_HBM_MASTER_COUNT"] ||
          !seen["SPMV_INPUT_A_READER_COUNT"] || !seen["SPMV_INPUT_X_READER_COUNT"] ||
          !seen["SPMV_INPUT_CTRL_READER_COUNT"] || !seen["SPMV_INPUT_HBM_CHANNEL_COUNT"] ||
          !seen["SPMV_INPUT_HBM_BASE"] || !seen["SPMV_INPUT_HBM_BYTES"] ||
          !seen["SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES"] || !seen["SPMV_INPUT_AXI_ADDR_WIDTH"] ||
          !seen["SPMV_INPUT_AXI_DATA_WIDTH"] || !seen["SPMV_INPUT_AXI_ID_WIDTH"] ||
          !seen["SPMV_INPUT_MAX_OUTSTANDING_BURSTS"] || !seen["SPMV_INPUT_CONSUMER_COUNT"] ||
          !seen["SPMV_INPUT_X_BROADCAST"] || !seen["SPMV_INPUT_CTRL_BROADCAST"] ||
          !seen["SPMV_INPUT_X_WINDOW_SIZE"] || !seen["SPMV_INPUT_X_REPLICA_COUNT"] ||
          !seen["SPMV_INPUT_X_BANK_COUNT"] || !seen["SPMV_INPUT_X_ELEMENT_WIDTH"] ||
          !seen["SPMV_INPUT_X_PORT_SCHEDULE"] || !seen["SPMV_INPUT_X_WRITE_LANES"] ||
          !seen["SPMV_INPUT_X_OVERLAP_LANES"] || !seen["SPMV_CUPER_SLOT_ABI"] ||
          !seen["SPMV_CUPER_SLOT_COLUMN_BITS"] || !seen["SPMV_CUPER_SLOT_TAG_BITS"] ||
          !seen["SPMV_CUPER_SLOT_ROW_BITS"] || !seen["SPMV_FP64_MUL_INTERFACE"] ||
          !seen["SPMV_FP64_MUL_PROVIDER"] || !seen["SPMV_FP64_MUL_LATENCY"] ||
          !seen["SPMV_FP64_MUL_II"] || !seen["SPMV_FP64_MUL_RESPONSE_FIFO_DEPTH"] ||
          !seen["SPMV_FP64_MUL_LANES"] || !seen["SPMV_FP64_MUL_CORE_COUNT"] ||
          !seen["SPMV_FP64_MUL_TOTAL_LANES"] || spmv_input_x_reader_count != "2" ||
          spmv_cuper_slot_abi != "cuper-a-slot-v4" || spmv_cuper_slot_column_bits != "13" ||
          spmv_cuper_slot_tag_bits != "3" || spmv_cuper_slot_row_bits != "16" ||
          spmv_fp64_mul_provider != "xilinx-floating-point-v7.1" ||
          spmv_fp64_mul_latency != "12" || seen["XLEN"] || seen["ISA_STRING"] ||
          seen["NEMU_PRESET"] || seen["NEMU_BACKEND"] || seen["PIPELINE"] ||
          seen["ICACHE_ENABLED"] || seen["DCACHE_ENABLED"] || seen["L2CACHE_ENABLED"]) exit 1
      accelerator_only=1
    }
    if (!asset_only && !accelerator_only && (!seen["XLEN"] || !seen["NEMU_PRESET"] || !seen["NEMU_CACHE_HTML"] ||
        !seen["NPC_TRACE"] || !seen["NPC_SDB_DEBUG"] || !seen["NPC_FINAL_LOG"] ||
        !seen["NPC_INSTRUCTION_LOG"] || !seen["NPC_PIPELINE_LOG"] ||
        !seen["NPC_CACHE_LOG"] || !seen["NPC_BP_LOG"] ||
        !seen["INTEGER_EXECUTE_STAGES"] || !seen["SERIAL_EXECUTE_STAGES"] || !seen["REGISTER_INITIAL_FETCH_REQUEST"] ||
        !seen["SEPARATE_SERIAL_INTEGER_ALU"] || !seen["SERIAL_EXECUTE_RESULT_FORWARDING"] || !seen["BRANCH_PREDICTOR"] ||
        !seen["DPI_MEMORY_TIMING_ENABLED"] || !seen["DPI_MEMORY_READ_RESPONSE_MIN_CYCLES"] ||
        !seen["DPI_MEMORY_READ_RESPONSE_MAX_CYCLES"] || !seen["DPI_MEMORY_WRITE_RESPONSE_MIN_CYCLES"] ||
        !seen["DPI_MEMORY_WRITE_RESPONSE_MAX_CYCLES"] || !seen["DPI_MEMORY_TIMING_SEED"] ||
        (scope == "fpga" && (!seen["FPGA_PLATFORM_CLOCK_MHZ"] || !seen["FPGA_DIVIDER_NON_BLOCKING"] || !seen["FPGA_RUNTIME_SDB"] || !seen["FPGA_RUNTIME_TRACE"] ||
          !seen["FPGA_TRACE_HBM_BANK"] || !seen["FPGA_TRACE_BUFFER_BYTES"] || !seen["FPGA_TRACE_MAX_RECORDS"] ||
          !seen["FPGA_TRACE_CACHE_RECORDS"] || !seen["FPGA_TRACE_FORMAT"] ||
          !seen["FPGA_TRACE_RECORD_BYTES"] || !seen["FPGA_TRACE_DATA_WIDTH"] ||
          !seen["FPGA_TRACE_BURST_RECORDS"])))) exit 1
  }
' "$temporary" || { echo "Scala profile 格式无效：$temporary" >&2; exit 1; }
mv "$temporary" "$output"
