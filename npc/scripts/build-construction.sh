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
nemu_root="$workspace/nemu"

while IFS='=' read -r key value; do
  [[ $key =~ ^[A-Z][A-Z0-9_]*$ && $value != *$'\n'* && $value != *$'\r'* ]] || {
    echo "拒绝加载非法 profile 字段：$key" >&2; exit 2;
  }
  printf -v "$key" '%s' "$value"
  export "$key"
done < "$profile"

mkdir -p "$stage/abi/rtl" "$stage/abi/verilator" "$stage/abi/nemu" \
  "$stage/abi/softfloat" "$stage/abi/glue/include" "$stage/abi/glue/src" \
  "$stage/logs"

if [[ ${CONSTRUCTION_DRY_RUN:-0} == 1 ]]; then
  printf 'dry-run\n' > "$stage/abi/rtl/placeholder.sv"
  printf '#!/usr/bin/env bash\necho "construction dry run"\n' > "$stage/abi/nemu/nemu-exec"
  chmod +x "$stage/abi/nemu/nemu-exec"
  if [[ $CAPABILITY == fpga-* ]]; then
    artifacts="$stage/fpga/artifacts"
    mkdir -p "$stage/fpga/rtl" "$stage/fpga/ip" "$stage/fpga/synth" "$stage/fpga/link" "$artifacts"
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
  [[ ${CONSTRUCTION_TEST_FAIL:-0} != 1 ]] || { echo '按测试请求模拟构造失败' >&2; exit 1; }
  exit 0
fi

case "$CAPABILITY" in
  elaborate-only)
    case "$SCOPE" in
      npc)
        make -C "$npc_root" chisel INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" CONSTRUCTION_PROFILE="$profile"
        cp -a "$npc_root/generated/." "$stage/abi/rtl/"
        ;;
      soc)
        make -C "$npc_root/chisel/ysyxSoC" verilog INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" CONSTRUCTION_PROFILE="$profile"
        cp "$npc_root/chisel/ysyxSoC/build/ysyxSoCFull.v" "$stage/abi/rtl/"
        cp -a "$npc_root/chisel/ysyxSoC/perip" "$stage/abi/rtl/perip"
        ;;
      *) echo "elaborate-only 不支持作用域 $SCOPE" >&2; exit 2 ;;
    esac
    ;;
  verilator-npc)
    make -C "$npc_root" chisel-cpu-lib INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" \
      CONSTRUCTION_PROFILE="$profile" NPC_VCD_TRACE=0
    cp -a "$npc_root/generated-dpi/." "$stage/abi/rtl/"
    cp -a "$npc_root/intermediate/chisel-cpu-lib/." "$stage/abi/verilator/"
    cp -a "$npc_root/intermediate/softfloat/." "$stage/abi/softfloat/"
    ;;
  verilator-soc)
    make -C "$npc_root" soc-nemu-lib INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" \
      CONSTRUCTION_PROFILE="$profile" NPC_VCD_TRACE=0
    cp "$npc_root/chisel/ysyxSoC/build-sim/ysyxSoCFull.v" "$stage/abi/rtl/"
    cp -a "$npc_root/chisel/ysyxSoC/perip" "$stage/abi/rtl/perip"
    cp -a "$npc_root/intermediate/soc-nemu-lib/." "$stage/abi/verilator/"
    cp -a "$npc_root/intermediate/softfloat/." "$stage/abi/softfloat/"
    ;;
  fpga-npc|fpga-soc)
    make -C "$npc_root" fpga-link INTERNAL_CONSTRUCTION=1 config="$CONFIG_FQCN" \
      CONSTRUCTION_PROFILE="$profile" FPGA_WORK_DIR="$stage/fpga"
    ;;
  *) echo "Config $CONFIG_FQCN 的能力 $CAPABILITY 不可构造" >&2; exit 2 ;;
esac

for file in "$npc_root"/csrc/*.h; do cp "$file" "$stage/abi/glue/include/"; done
for file in "$npc_root"/csrc/npc_core.cpp "$npc_root"/csrc/pmem.cpp \
  "$npc_root"/csrc/soc_dpi.cpp "$npc_root"/csrc/fp_dpi.cpp; do
  [[ -f $file ]] && cp "$file" "$stage/abi/glue/src/"
done

if [[ $CAPABILITY == elaborate-only ]]; then
  exit 0
fi

isa="riscv${XLEN}"
# NEMU 的配置、对象和链接结果属于仿真 host；保存在构造目录后，后续运行可以
# 只让 Make 根据 C/C++ 与 menuconfig 依赖做增量刷新，而无需重新 elaboration。
config_root="$stage/abi/nemu/config"
build_root="$stage/abi/nemu/build"
objects="$stage/abi/nemu/objects"
mkdir -p "$config_root" "$build_root" "$objects"
if [[ $CAPABILITY == fpga-* ]]; then
  defconfig="${isa}-fpga-${FPGA_BOARD}-defconfig"
  usenpc=0
  npc_soc=0
else
  defconfig="${isa}-nemu-interpreter-defconfig"
  usenpc=1
  [[ $CAPABILITY == verilator-soc ]] && npc_soc=1 || npc_soc=0
fi
make -C "$nemu_root" "$defconfig" NEMU_CONFIG_ROOT="$config_root" NEMU_BUILD_ROOT="$build_root"
make -C "$nemu_root" ISA="$isa" app \
  NEMU_CONFIG_ROOT="$config_root" NEMU_BUILD_ROOT="$build_root" NEMU_OBJ_DIR="$objects" \
  USENPC="$usenpc" NPC_SOC="$npc_soc" NPC_OBJ_DIR="$stage/abi/verilator" \
  NPC_GLUE_DIR="$stage/abi/glue/include" NPC_SOFTFLOAT_LIB="$stage/abi/softfloat/softfloat.a"
host_binary=$(find "$build_root" -maxdepth 1 -type f -perm -u+x -print -quit)
[[ -n $host_binary ]] || { echo "NEMU host 未生成可执行文件" >&2; exit 1; }
cp "$host_binary" "$stage/abi/nemu/nemu-exec"
