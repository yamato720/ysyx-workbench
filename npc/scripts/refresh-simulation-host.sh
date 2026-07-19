#!/usr/bin/env bash
# 刷新已保存仿真构造中的 NEMU host；硬件 ABI 不在这里重建。
set -euo pipefail

usage() {
  echo "用法：$0 <workspace-root> <construction-dir>" >&2
  exit 2
}

[[ $# == 2 ]] || usage
workspace=$(realpath "$1")
construction=$(realpath "$2")
profile="$construction/profile.env"
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

case "${CAPABILITY:-}" in
  verilator-npc|verilator-soc) ;;
  *)
    echo "Config ${CONFIG_FQCN:-unknown} 不是可刷新的仿真构造" >&2
    exit 2
    ;;
esac
[[ ${XLEN:-} == 32 || ${XLEN:-} == 64 ]] || {
  echo "Config ${CONFIG_FQCN:-unknown} 的 XLEN 非法：${XLEN:-empty}" >&2
  exit 2
}

abi="$construction/abi/nemu"
config_root="$abi/config"
build_root="$abi/build"
objects="$abi/objects"
host="$abi/nemu-exec"
mkdir -p "$config_root" "$build_root" "$objects"

# 同一 Config 可能被多个 batch 进程同时使用；串行化这个 Make 目录即可。
exec 9>"$abi/.refresh.lock"
flock 9

isa="riscv${XLEN}"
defconfig="${isa}-nemu-interpreter-defconfig"
if [[ $CAPABILITY == verilator-npc ]]; then
  usenpc=1
  npc_soc=0
else
  usenpc=1
  npc_soc=1
fi

echo "刷新仿真 host 的 Make 依赖：${CONFIG_FQCN}"
make -C "$nemu_root" "$defconfig" \
  NEMU_CONFIG_ROOT="$config_root" NEMU_BUILD_ROOT="$build_root"
make -C "$nemu_root" ISA="$isa" app \
  NEMU_CONFIG_ROOT="$config_root" NEMU_BUILD_ROOT="$build_root" NEMU_OBJ_DIR="$objects" \
  USENPC="$usenpc" NPC_SOC="$npc_soc" NPC_OBJ_DIR="$construction/abi/verilator" \
  NPC_GLUE_DIR="$construction/abi/glue/include" \
  NPC_SOFTFLOAT_LIB="$construction/abi/softfloat/softfloat.a"

host_binary=$(find "$build_root" -maxdepth 1 -type f -perm -u+x -print -quit)
[[ -n $host_binary ]] || { echo "NEMU host 未生成可执行文件" >&2; exit 1; }
temporary="$host.tmp.$$"
install -m 0755 "$host_binary" "$temporary"
mv -f "$temporary" "$host"
