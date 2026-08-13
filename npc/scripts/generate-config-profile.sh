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
  $1 == "SPMV_PERFORMANCE_HTML" { spmv_performance_html=$2 }
  $1 == "SPMV_PIPELINE_HTML" { spmv_pipeline_html=$2 }
  seen[$1]++ { exit 1 }
  index(substr($0, index($0, "=") + 1), "\r") { exit 1 }
  END {
    if (!seen["PROFILE_FORMAT"] || !seen["CONFIG_SHORT_NAME"] || !seen["CONFIG_FQCN"] ||
        !seen["SCOPE"] || !seen["CAPABILITY"] || !seen["HOST_ABI"] ||
        !seen["PROTOCOL_ABI"] || !seen["TARGET"]) exit 1
    if (scope == "spmv") {
      if (capability != "run" || target != "SPMV" || host_abi != "none" ||
          !seen["ACCELERATOR_HOST_KIND"] || !seen["ACCELERATOR_HOST_ABI"] ||
          accelerator_host_kind != "spmv" || accelerator_host_abi != "spmv-input-report-v3" ||
          !seen["SPMV_INPUT_A_READER_COUNT"] || !seen["SPMV_INPUT_X_READER_COUNT"] ||
          !seen["SPMV_INPUT_HBM_CHANNEL_COUNT"] || !seen["SPMV_INPUT_HBM_BASE"] ||
          !seen["SPMV_INPUT_HBM_BYTES"] || !seen["SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES"] ||
          !seen["SPMV_INPUT_AXI_ADDR_WIDTH"] || !seen["SPMV_INPUT_AXI_DATA_WIDTH"] ||
          !seen["SPMV_INPUT_AXI_ID_WIDTH"] || !seen["SPMV_INPUT_MAX_OUTSTANDING_BURSTS"] ||
          !seen["SPMV_INPUT_CONSUMER_COUNT"] ||
          !seen["SPMV_INPUT_X_BROADCAST"] || !seen["SPMV_PERFORMANCE_HTML"] ||
          !seen["SPMV_PIPELINE_HTML"] || spmv_performance_html !~ /^[01]$/ ||
          spmv_pipeline_html !~ /^[01]$/ ||
          (spmv_pipeline_html == "1" && spmv_performance_html != "1") ||
          seen["XLEN"] || seen["ISA_STRING"] || seen["NEMU_PRESET"] ||
          seen["NEMU_BACKEND"] || seen["PIPELINE"] || seen["FPGA_BOARD"] ||
          seen["ICACHE_ENABLED"] || seen["DCACHE_ENABLED"] || seen["L2CACHE_ENABLED"]) exit 1
      accelerator_only=1
    }
    if (capability == "synthesize-only" || capability == "bitstream-only") {
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
    if (!asset_only && !accelerator_only && (!seen["XLEN"] || !seen["NEMU_PRESET"] || !seen["NEMU_CACHE_HTML"] ||
        !seen["INTEGER_EXECUTE_STAGES"] || !seen["SERIAL_EXECUTE_STAGES"] || !seen["REGISTER_INITIAL_FETCH_REQUEST"] ||
        !seen["SEPARATE_SERIAL_INTEGER_ALU"] || !seen["SERIAL_EXECUTE_RESULT_FORWARDING"] ||
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
