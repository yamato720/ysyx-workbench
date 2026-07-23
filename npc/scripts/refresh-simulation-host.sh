#!/usr/bin/env bash
# 构造或更新已保存硬件 ABI 对应的 NEMU host。普通 run 不调用本脚本。
set -euo pipefail

usage() {
  echo "用法：$0 <workspace-root> <construction-dir> [<profile.env> <host-stage-dir>]" >&2
  exit 2
}

[[ $# == 2 || $# == 4 ]] || usage
workspace=$(realpath "$1")
construction=$(realpath "$2")
profile=$(realpath "${3:-$construction/profile.env}")
host_stage=${4:-}
nemu_root="$workspace/nemu"

[[ -f $profile ]] || { echo "构造缺少 profile.env：$construction" >&2; exit 1; }
[[ -d $nemu_root ]] || { echo "找不到 NEMU 源目录：$nemu_root" >&2; exit 1; }

while IFS='=' read -r key value; do
  [[ $key =~ ^[A-Z][A-Z0-9_]*$ && $value != *$'\n'* && $value != *$'\r'* ]] || {
    echo "拒绝加载非法 profile 字段：$key" >&2
    exit 2
  }
  printf -v "$key" '%s' "$value"
  export "$key"
done < "$profile"

bit() {
  case "$1" in
    0|1) ;;
    *) echo "host profile 字段 $2 必须为 0 或 1，实际为 '$1'" >&2; exit 2 ;;
  esac
}

[[ ${CAPABILITY:-} == run && ${HOST_ABI:-} == nemu-construction-v1 ]] || {
  echo "Config ${CONFIG_FQCN:-unknown} 不是可构造 NEMU host 的运行 Config" >&2
  exit 2
}
[[ ${XLEN:-} == 32 || ${XLEN:-} == 64 ]] || {
  echo "Config ${CONFIG_FQCN:-unknown} 的 XLEN 非法：${XLEN:-empty}" >&2
  exit 2
}
[[ ${NEMU_PRESET:-none} != none ]] || {
  echo "Config ${CONFIG_FQCN:-unknown} 缺少 NEMU_PRESET" >&2
  exit 2
}
for field in NEMU_TRACE NEMU_WATCHPOINT NEMU_VCD NEMU_PERFORMANCE_HTML NEMU_PIPELINE_HTML NEMU_NPC_DIFFTEST NEMU_DEVICES NEMU_DEBUG NEMU_LTO NEMU_ASAN; do
  bit "${!field:-}" "$field"
done
[[ ${NEMU_OPTIMIZATION:-} =~ ^O[0-3]$ ]] || {
  echo "NEMU_OPTIMIZATION 必须为 O0/O1/O2/O3" >&2; exit 2;
}

case "${SCOPE:-}" in
  npc|soc)
    [[ ${NEMU_BACKEND:-} == local ]] || {
      echo "$SCOPE 构造只能绑定 local Verilator host，实际为 ${NEMU_BACKEND:-empty}" >&2; exit 2;
    }
    usenpc=1
    [[ $SCOPE == npc ]] && npc_soc=0 || npc_soc=1
    ;;
  fpga)
    case "${FPGA_BOARD:-}" in
      u55c) expected_backend=u55c ;;
      zcu102) expected_backend=zcu102 ;;
      *) echo "FPGA 构造的板卡非法：${FPGA_BOARD:-empty}" >&2; exit 2 ;;
    esac
    [[ ${NEMU_BACKEND:-} == "$expected_backend" ]] || {
      echo "FPGA 板卡 ${FPGA_BOARD} 必须绑定 $expected_backend host，实际为 ${NEMU_BACKEND:-empty}" >&2
      exit 2
    }
    usenpc=0
    npc_soc=0
    ;;
  *) echo "Config ${CONFIG_FQCN:-unknown} 的作用域非法：${SCOPE:-empty}" >&2; exit 2 ;;
esac

if [[ $NEMU_PIPELINE_HTML == 1 ]]; then
  [[ $NEMU_PERFORMANCE_HTML == 1 ]] || {
    echo "流水线 HTML 必须同时启用性能 HTML" >&2
    exit 2
  }
  [[ $NEMU_BACKEND == local ]] || {
    echo "指令阶段 HTML 只支持本地 Verilator 构造" >&2
    exit 2
  }
fi

render_defconfig() {
  local output=$1 backend_symbol=CONFIG_FPGA_BACKEND_NONE
  case "$NEMU_BACKEND" in
    local) backend_symbol=CONFIG_FPGA_BACKEND_NONE ;;
    u55c) backend_symbol=CONFIG_FPGA_BACKEND_U55C ;;
    zcu102) backend_symbol=CONFIG_FPGA_BACKEND_ZCU102 ;;
  esac
  {
    echo 'CONFIG_ISA_riscv=y'
    if [[ $XLEN == 64 ]]; then echo 'CONFIG_RV64=y'; else echo '# CONFIG_RV64 is not set'; fi
    echo 'CONFIG_ENGINE_INTERPRETER=y'
    echo 'CONFIG_MODE_SYSTEM=y'
    echo 'CONFIG_TARGET_NATIVE_ELF=y'
    echo "$backend_symbol=y"
    if [[ $F == 1 ]]; then echo 'CONFIG_RISCV_F=y'; else echo '# CONFIG_RISCV_F is not set'; fi
    if [[ $NEMU_TRACE == 1 ]]; then echo 'CONFIG_TRACE=y'; else echo '# CONFIG_TRACE is not set'; fi
    if [[ $NEMU_VCD == 1 ]]; then echo 'CONFIG_NPC_VCD_TRACE=y'; else echo '# CONFIG_NPC_VCD_TRACE is not set'; fi
    if [[ $NEMU_PERFORMANCE_HTML == 1 ]]; then echo 'CONFIG_NPC_PERFORMANCE_HTML=y'; else echo '# CONFIG_NPC_PERFORMANCE_HTML is not set'; fi
    if [[ $NEMU_PIPELINE_HTML == 1 ]]; then echo 'CONFIG_NPC_PIPELINE_HTML=y'; else echo '# CONFIG_NPC_PIPELINE_HTML is not set'; fi
    if [[ $NEMU_NPC_DIFFTEST == 1 ]]; then echo 'CONFIG_NPC_DIFFTEST_NEMU=y'; else echo '# CONFIG_NPC_DIFFTEST_NEMU is not set'; fi
    if [[ $NEMU_WATCHPOINT == 1 ]]; then echo 'CONFIG_WATCHPOINT=y'; else echo '# CONFIG_WATCHPOINT is not set'; fi
    if [[ $NEMU_DEVICES == 1 ]]; then echo 'CONFIG_DEVICE=y'; else echo '# CONFIG_DEVICE is not set'; fi
    echo "CONFIG_CC_$NEMU_OPTIMIZATION=y"
    if [[ $NEMU_DEBUG == 1 ]]; then echo 'CONFIG_CC_DEBUG=y'; else echo '# CONFIG_CC_DEBUG is not set'; fi
    if [[ $NEMU_LTO == 1 ]]; then echo 'CONFIG_CC_LTO=y'; else echo '# CONFIG_CC_LTO is not set'; fi
    if [[ $NEMU_ASAN == 1 ]]; then echo 'CONFIG_CC_ASAN=y'; else echo '# CONFIG_CC_ASAN is not set'; fi
  } > "$output"
}

abi="$construction/abi"
mkdir -p "$abi"
exec 9>"$abi/.host-build.lock"
flock 9
publish=1
if [[ -n $host_stage ]]; then
  stage=$(realpath -m "$host_stage")
  [[ $(dirname "$stage") == "$abi" && $(basename "$stage") == .nemu-host-staging.* ]] || {
    echo "host staging 目录必须位于 $abi 且使用 .nemu-host-staging.* 名称" >&2
    exit 2
  }
  [[ ! -e $stage ]] || { echo "host staging 目录已存在：$stage" >&2; exit 2; }
  mkdir "$stage"
  publish=0
else
  stage=$(mktemp -d "$abi/.nemu-staging.XXXXXX")
fi
trap 'rm -rf "$stage"' EXIT INT TERM
# 复制上一代 host 的 config/build/objects，保留 NEMU Make 的 `.d` 与
# `.menuconfig-state`。这样 host-build 只重编译实际变动的 C/C++ 依赖，同时构造
# 结果仍在临时目录完成，直到最后一步才替换 `abi/nemu`。
if [[ -d $abi/nemu ]]; then
  cp -a "$abi/nemu/." "$stage/"
fi
config_root="$stage/config"
build_root="$stage/build"
objects="$stage/objects"
mkdir -p "$config_root" "$build_root" "$objects"
defconfig="$stage/host.defconfig"
render_defconfig "$defconfig"

if [[ ${CONSTRUCTION_DRY_RUN:-0} == 1 ]]; then
  printf '#!/usr/bin/env bash\necho "construction dry-run host"\n' > "$stage/nemu-exec"
  chmod +x "$stage/nemu-exec"
else
  echo "构造 NEMU host：$CONFIG_FQCN（preset=$NEMU_PRESET，backend=$NEMU_BACKEND）"
  make -C "$nemu_root" defconfig-file \
    NEMU_DEFCONFIG_FILE="$defconfig" NEMU_CONFIG_ROOT="$config_root" NEMU_BUILD_ROOT="$build_root"
  make -C "$nemu_root" ISA="riscv${XLEN}" app \
    NEMU_CONFIG_ROOT="$config_root" NEMU_BUILD_ROOT="$build_root" NEMU_OBJ_DIR="$objects" \
    USENPC="$usenpc" NPC_SOC="$npc_soc" NPC_OBJ_DIR="$construction/abi/verilator" \
    NPC_GLUE_DIR="$construction/abi/glue/include" NPC_SOFTFLOAT_LIB="$construction/abi/softfloat/softfloat.a"
  host_binary=$(find "$build_root" -maxdepth 1 -type f -perm -u+x -print -quit)
  [[ -n ${host_binary:-} ]] || { echo "NEMU host 未生成可执行文件" >&2; exit 1; }
  install -m 0755 "$host_binary" "$stage/nemu-exec"
  rm -rf "$stage/lib"
  if [[ $NEMU_PERFORMANCE_HTML == 1 ]]; then
    mkdir -p "$stage/lib"
    install -m 0755 "$nemu_root/tools/capstone/repo/libcapstone.so.5" \
      "$stage/lib/libcapstone.so.5"
  fi
fi

{
  echo 'HOST_FORMAT=5'
  echo "CONFIG_FQCN=$CONFIG_FQCN"
  echo "NEMU_PRESET=$NEMU_PRESET"
  echo "NEMU_BACKEND=$NEMU_BACKEND"
  echo "NEMU_TRACE=$NEMU_TRACE"
  echo "NEMU_WATCHPOINT=$NEMU_WATCHPOINT"
  echo "NEMU_VCD=$NEMU_VCD"
  echo "NEMU_PERFORMANCE_HTML=$NEMU_PERFORMANCE_HTML"
  echo "NEMU_PIPELINE_HTML=$NEMU_PIPELINE_HTML"
  echo "NEMU_NPC_DIFFTEST=$NEMU_NPC_DIFFTEST"
  echo "NEMU_DEVICES=$NEMU_DEVICES"
  echo "NEMU_OPTIMIZATION=$NEMU_OPTIMIZATION"
  echo "NEMU_DEBUG=$NEMU_DEBUG"
  echo "NEMU_LTO=$NEMU_LTO"
  echo "NEMU_ASAN=$NEMU_ASAN"
  echo "SCOPE=$SCOPE"
  echo "TARGET=$TARGET"
  echo "XLEN=$XLEN"
  echo "F=$F"
  echo "FPGA_BOARD=${FPGA_BOARD:-}"
  echo "HOST_ABI=$HOST_ABI"
  echo "PROTOCOL_ABI=$PROTOCOL_ABI"
  echo "BUILT_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$stage/host.env"

# host-build 的显式 staging 模式由构造管理器与 profile 一起发布；完整硬件构造仍在
# 此处原子替换 host 子树。
if [[ $publish == 0 ]]; then
  trap - EXIT INT TERM
  exit 0
fi

# 成功后只原子替换 host 子树，不触碰已保存的 RTL、Verilator ABI 或 FPGA 资产。
destination="$abi/nemu"
backup="$abi/.nemu-previous.$$"
if [[ -e $destination ]]; then mv "$destination" "$backup"; fi
if mv "$stage" "$destination"; then
  rm -rf "$backup"
else
  [[ -e $backup ]] && mv "$backup" "$destination"
  exit 1
fi
trap - EXIT INT TERM
