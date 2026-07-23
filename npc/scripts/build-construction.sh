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

while IFS='=' read -r key value; do
  [[ $key =~ ^[A-Z][A-Z0-9_]*$ && $value != *$'\n'* && $value != *$'\r'* ]] || {
    echo "拒绝加载非法 profile 字段：$key" >&2; exit 2;
  }
  printf -v "$key" '%s' "$value"
  export "$key"
done < "$profile"

mkdir -p "$stage/abi/rtl" "$stage/abi/verilator" "$stage/abi/nemu" \
  "$stage/abi/softfloat" "$stage/abi/glue/include" "$stage/abi/glue/src" \
  "$stage/logs/build"

phase_log="$npc_root/scripts/phase-log.sh"
phase_logs="$stage/logs/build"
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
  shift 4
  "$phase_log" run "$phase_logs" "$phase" "$index" "$total" -- \
    make -C "$npc_root" "$target" "$@" INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" \
    CONSTRUCTION_PROFILE="$profile" NPC_VCD_TRACE=0
}

run_root_phase_visible() {
  local phase=$1 index=$2 total=$3 target=$4
  shift 4
  PHASE_LOG_PASSTHROUGH=1 "$phase_log" run "$phase_logs" "$phase" "$index" "$total" -- \
    make -C "$npc_root" "$target" "$@" INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" \
    CONSTRUCTION_PROFILE="$profile" NPC_VCD_TRACE=0
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
  run_phase nemu-host "$index" "$total" \
    "$npc_root/scripts/refresh-simulation-host.sh" "$workspace" "$stage"
}

dry_run() {
  local total
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
    run:fpga)
      total=5
      note_phase elaborate 1 "$total" 'dry-run FPGA elaboration'
      note_phase ip 2 "$total" 'dry-run FPGA IP 生成'
      note_phase synth 3 "$total" 'dry-run FPGA 综合'
      note_phase link 4 "$total" 'dry-run FPGA 链接'
      ;;
    *) echo "Config $CONFIG_FQCN 的能力/作用域不支持 dry-run：$CAPABILITY/$SCOPE" >&2; exit 2 ;;
  esac

  printf 'dry-run\n' > "$stage/abi/rtl/placeholder.sv"
  if [[ $SCOPE == fpga ]]; then
    local artifacts asset
    artifacts="$stage/fpga/artifacts"
    mkdir -p "$stage/fpga/rtl" "$stage/fpga/ip" "$stage/fpga/synth" "$stage/fpga/link" "$artifacts"
    mkdir -p "$stage/fpga/ip/logs"
    printf '%s\n' 'dry-run Vivado multiplier IP' > "$stage/fpga/ip/logs/npc_int_multiplier_ip.log"
    printf '%s\n' 'dry-run Vivado divider IP' > "$stage/fpga/ip/logs/npc_int_divider_ip.log"
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
  if [[ $CAPABILITY == run ]]; then
    refresh_host "$total" "$total"
  fi
}

if [[ ${CONSTRUCTION_DRY_RUN:-0} == 1 ]]; then
  dry_run
  copy_glue
  exit 0
fi

# NEMU 编译通过保存构造的 glue include 引用 NPC 调试 ABI；必须先冻结这些
# 头文件，再进入末尾的 host 阶段。源码副本不依赖 Chisel/Verilator 产物。
copy_glue

case "$CAPABILITY:$SCOPE" in
  generate-only:npc)
    run_root_phase chisel 1 1 chisel
    cp -a "$npc_root/generated/." "$stage/abi/rtl/"
    ;;
  generate-only:soc)
    run_phase chisel 1 1 make -C "$npc_root/chisel/ysyxSoC" verilog \
      INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" CONSTRUCTION_PROFILE="$profile"
    cp "$npc_root/chisel/ysyxSoC/build/ysyxSoCFull.v" "$stage/abi/rtl/"
    cp -a "$npc_root/chisel/ysyxSoC/perip" "$stage/abi/rtl/perip"
    ;;
  run:npc)
    run_root_phase chisel 1 4 chisel-dpi
    run_root_phase softfloat 2 4 softfloat-lib
    run_root_phase verilator 3 4 chisel-cpu-lib CONSTRUCTION_PHASE_PREREQUISITES=0
    cp -a "$npc_root/generated-dpi/." "$stage/abi/rtl/"
    cp -a "$npc_root/intermediate/chisel-cpu-lib/." "$stage/abi/verilator/"
    cp -a "$npc_root/intermediate/softfloat/." "$stage/abi/softfloat/"
    refresh_host 4 4
    ;;
  run:soc)
    run_root_phase chisel 1 4 soc-sim-verilog
    run_root_phase softfloat 2 4 softfloat-lib
    run_root_phase verilator 3 4 soc-nemu-lib CONSTRUCTION_PHASE_PREREQUISITES=0
    cp "$npc_root/chisel/ysyxSoC/build-sim/ysyxSoCFull.v" "$stage/abi/rtl/"
    cp -a "$npc_root/chisel/ysyxSoC/perip" "$stage/abi/rtl/perip"
    cp -a "$npc_root/intermediate/soc-nemu-lib/." "$stage/abi/verilator/"
    cp -a "$npc_root/intermediate/softfloat/." "$stage/abi/softfloat/"
    refresh_host 4 4
    ;;
  run:fpga)
    run_root_phase elaborate 1 5 fpga-elaborate FPGA_WORK_DIR="$stage/fpga"
    run_root_phase_visible ip 2 5 fpga-ip FPGA_WORK_DIR="$stage/fpga"
    run_root_phase_visible synth 3 5 fpga-synth FPGA_WORK_DIR="$stage/fpga" FPGA_PHASE_PREREQUISITES=0
    run_root_phase_visible link 4 5 fpga-link FPGA_WORK_DIR="$stage/fpga" FPGA_PHASE_PREREQUISITES=0
    refresh_host 5 5
    ;;
  *) echo "Config $CONFIG_FQCN 的能力/作用域不可构造：$CAPABILITY/$SCOPE" >&2; exit 2 ;;
esac
