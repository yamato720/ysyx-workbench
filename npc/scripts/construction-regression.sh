#!/usr/bin/env bash
# 使用隔离的 dry-run 构造库验证编号、原子更新和失配策略。
set -euo pipefail

npc_root=${1:?用法：construction-regression.sh <npc-root>}
npc_root=$(realpath "$npc_root")
manager="$npc_root/scripts/construction-manager.sh"
work=$(mktemp -d)
build_hold=''
build_pid=''
parallel_build_pid=''
cleanup() {
  if [[ -n ${build_hold:-} ]]; then : > "$build_hold/release" 2>/dev/null || true; fi
  if [[ -n ${build_pid:-} ]]; then wait "$build_pid" 2>/dev/null || true; fi
  if [[ -n ${parallel_build_pid:-} ]]; then wait "$parallel_build_pid" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT INT TERM

fail() {
  echo "构造回归失败：$*" >&2
  exit 1
}

value() {
  sed -n "s/^${2}=//p" "$1" | tail -n 1
}

export CONSTRUCTION_TEST_ROOT="$work/constructions"
export CONSTRUCTION_DRY_RUN=1
export CONSTRUCTION_ID_PREFIX=20260718153042
"$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
export NPC_CONFIG_CATALOG_READY=1

# 没有正式 FPGA 构造时，host-build 仍应能根据完整 Config 产生可与外部 xclbin
# 搭配使用的 U55C host；兼容目录不出现在正式版本库中，也不生成硬件资产。
host_only_fqcn=npc.fpga.u55c.U55cRv64Npc300MHzFpgaConfig
host_only="$CONSTRUCTION_TEST_ROOT/.compatible/$host_only_fqcn"
"$manager" host-build "$npc_root" U55cRv64Npc300MHzFpgaConfig 0 1
[[ -x $host_only/abi/nemu/nemu-exec && -f $host_only/abi/nemu/host.env &&
  -f $host_only/profile.env && -f $host_only/construction.env && -s $host_only/logs/host/nemu-host.log ]] ||
  fail 'host-only 缓存没有生成完整 NEMU host'
[[ $(value "$host_only/profile.env" CONFIG_FQCN) == "$host_only_fqcn" &&
  $(value "$host_only/abi/nemu/host.env" NEMU_BACKEND) == u55c &&
  $(value "$host_only/abi/nemu/host.env" CORE_CLOCK_MHZ) == $(value "$host_only/profile.env" FPGA_CLOCK_MHZ) &&
  $(value "$host_only/construction.env" HOST_ONLY) == 1 ]] ||
  fail 'host-only 缓存没有冻结匹配的 U55C Config profile'
[[ ! -e $host_only/version.tag && ! -d "$CONSTRUCTION_TEST_ROOT/$host_only_fqcn" &&
  ! -e $host_only/fpga/artifacts/npc-$(value "$host_only/profile.env" FPGA_PLATFORM).xclbin && ! -e $host_only/abi/rtl ]] ||
  fail 'host-only 缓存错误地生成正式构造或硬件资产'
if "$manager" resolve "$npc_root" '' 1 >/dev/null 2>&1; then
  fail 'host-only 缓存错误地成为正式 version'
fi

# 外部平台只需把匹配 FQCN/平台的运行资产放入兼容目录；一旦资产出现，
# resolve/ensure 都必须选择兼容 host，而不是要求本机 Vivado 构造。
compatible_asset="$host_only/fpga/artifacts/npc-$(value "$host_only/profile.env" FPGA_PLATFORM).xclbin"
printf 'external-compatible-xclbin\n' > "$compatible_asset"
resolved_compatible=$($manager resolve "$npc_root" U55cRv64Npc300MHzFpgaConfig '')
[[ $(printf '%s\n' "$resolved_compatible" | cut -d'|' -f9) == "$host_only" ]] ||
  fail '兼容 FPGA 资产出现后 resolve 没有选择兼容 host'
$manager ensure "$npc_root" "$host_only_fqcn" 0 0 >/dev/null

# The monitor is deliberately a separate v13, batch-only host profile.  A
# host-only cache can prepare an external v13 xclbin, but it must never turn a
# regular `run` invocation into an interactive monitor session.
monitor_fqcn=npc.fpga.u55c.U55cRv64Npc300MHzPerformanceMonitorFpgaConfig
monitor_host_only="$CONSTRUCTION_TEST_ROOT/.compatible/$monitor_fqcn"
"$manager" host-build "$npc_root" U55cRv64Npc300MHzPerformanceMonitorFpgaConfig 0 1
[[ -x $monitor_host_only/abi/nemu/nemu-exec && -f $monitor_host_only/profile.env &&
  $(value "$monitor_host_only/profile.env" CAPABILITY) == batch &&
  $(value "$monitor_host_only/profile.env" PROTOCOL_ABI) == npc-fpga-runtime-v13-performance-monitor &&
  $(value "$monitor_host_only/profile.env" FPGA_RUNTIME_SDB) == 0 &&
  $(value "$monitor_host_only/profile.env" FPGA_RUNTIME_TRACE) == 1 &&
  $(value "$monitor_host_only/profile.env" FPGA_TRACE_CACHE_RECORDS) == 2048 &&
  $(value "$monitor_host_only/abi/nemu/host.env" NEMU_PRESET) == U55cPerformanceMonitor ]] ||
  fail '性能监测 host-only 缓存没有冻结 v13 batch trace ABI'
cpu_tests=$(realpath "$npc_root/../am-kernels/tests/cpu-tests")
if interactive_monitor=$(make -C "$cpu_tests" run ALL=add \
    config=U55cRv64Npc300MHzPerformanceMonitorFpgaConfig LOG_ROOT="$work/log" 2>&1); then
  fail 'batch-only 性能监测 Config 错误地接受了 run'
fi
[[ $interactive_monitor == *'PerformanceMonitor Config 仅支持 run-bat'* ]] ||
  fail 'batch-only 性能监测 Config 的 run 拒绝提示不明确'

build_hold="$work/first-build-hold"
CONSTRUCTION_TEST_HOLD_DIR="$build_hold" "$manager" build "$npc_root" SimulationConfig > "$work/first-build.log" 2>&1 &
build_pid=$!
for _ in $(seq 1 1500); do [[ -e $build_hold/ready ]] && break; sleep 0.02; done
[[ -e $build_hold/ready ]] || fail '首次构造没有进入可观察状态'
initial="$CONSTRUCTION_TEST_ROOT/npc.SimulationConfig"
[[ -d $initial && $(value "$initial/version.tag" VERSION_INDEX) == 1 &&
  $(value "$initial/version.tag" STATE) == building &&
  $(value "$initial/version.info" CONFIG_SHORT_NAME) == SimulationConfig ]] ||
  fail '首次构造没有在稳定目录写入版本标签与信息文件'
building_list=$(timeout 1 "$manager" list "$npc_root")
building_line=$(awk '$1 == 1 { print; exit }' <<< "$building_list")
[[ -n $building_line && ${building_line:44:6} != *+* ]] ||
  fail '首次构造没有以无效版本显示'
if find "$CONSTRUCTION_TEST_ROOT" -mindepth 1 -maxdepth 1 -type d \( -name '.staging-*' -o -name 'staging-*' \) -print -quit | grep -q .; then
  fail '首次构造创建了非稳定的 staging 目录'
fi

# 不同 FQCN 的长构造不能继续占用全局元数据锁；第二个 dry-run 必须在第一个
# 构造仍被 test hold 阻塞时发布完成。相同 Config 重入和删除/重编号则必须被
# 对应 FQCN 锁拒绝，不能破坏正在使用的稳定目录或版本号。
parallel_pipeline="$CONSTRUCTION_TEST_ROOT/npc.PipelineSimulationConfig"
"$manager" build "$npc_root" PipelineSimulationConfig > "$work/parallel-build.log" 2>&1 &
parallel_build_pid=$!
for _ in $(seq 1 500); do
  kill -0 "$parallel_build_pid" 2>/dev/null || break
  sleep 0.02
done
if kill -0 "$parallel_build_pid" 2>/dev/null; then
  kill "$parallel_build_pid" 2>/dev/null || true
  wait "$parallel_build_pid" 2>/dev/null || true
  parallel_build_pid=''
  fail '不同 Config 构造仍被第一个长构造阻塞'
fi
wait "$parallel_build_pid" || fail '并行的不同 Config 构造失败'
parallel_build_pid=''
[[ -f $parallel_pipeline/construction.env && $(value "$parallel_pipeline/version.tag" STATE) == complete ]] ||
  fail '并行的不同 Config 没有在首个构造完成前发布'
if same_config=$($manager rebuild "$npc_root" SimulationConfig 2>&1); then
  fail '同一 Config 在构造中仍接受重入 rebuild'
fi
[[ $same_config == *'正在构造'* ]] || fail '同一 Config 构造锁拒绝提示不明确'
if deleting_build=$($manager delete "$npc_root" 1,2 2>&1); then
  fail '构造中仍接受删除/重编号'
fi
[[ $deleting_build == *'正在构造'* && -d $initial && -d $parallel_pipeline ]] ||
  fail '构造中删除没有拒绝或发生了部分删除'
: > "$build_hold/release"
wait "$build_pid" || fail '首次构造失败'
build_pid=''
build_hold=''
dpi="$CONSTRUCTION_TEST_ROOT/npc.SimulationConfig"
pipeline="$CONSTRUCTION_TEST_ROOT/npc.PipelineSimulationConfig"
[[ $(value "$dpi/construction.env" CONSTRUCTION_ID) == 2026071815304200 ]] || fail '首个编号不是 00'
[[ $(value "$pipeline/construction.env" CONSTRUCTION_ID) == 2026071815304201 ]] || fail '同秒编号没有递增'
[[ $(value "$dpi/construction.env" VERSION_INDEX) == 1 ]] || fail '首个版本序号不是 1'
[[ $(value "$pipeline/construction.env" VERSION_INDEX) == 2 ]] || fail '第二个版本序号不是 2'
[[ $(value "$dpi/.complete" CONSTRUCTION_COMPLETE) == 1 &&
  $(value "$pipeline/.complete" CONSTRUCTION_COMPLETE) == 1 ]] ||
  fail '成功构造没有写入完成标志'
[[ $(value "$dpi/version.tag" VERSION_INDEX) == 1 && $(value "$dpi/version.tag" STATE) == complete &&
  $(value "$dpi/version.info" ARCH) == NPC && $(value "$dpi/version.info" RUNNING_TIME) == SIM ]] ||
  fail '成功构造没有完成版本标签与信息文件'
[[ ! -e $dpi/.incomplete && ! -e $pipeline/.incomplete ]] ||
  fail '成功构造仍保留未完成标志'
[[ $(value "$dpi/profile.env" NEMU_PERFORMANCE_HTML) == 1 &&
  $(value "$pipeline/profile.env" NEMU_PERFORMANCE_HTML) == 1 &&
  $(value "$dpi/profile.env" NEMU_CACHE_HTML) == 1 &&
  $(value "$pipeline/profile.env" NEMU_CACHE_HTML) == 1 &&
  $(value "$dpi/profile.env" NEMU_PIPELINE_HTML) == 1 &&
  $(value "$pipeline/profile.env" NEMU_PIPELINE_HTML) == 1 ]] ||
  fail '本地性能/流水 HTML profile 选择不正确'
[[ -s $dpi/logs/build/all.log && -s $dpi/logs/build/chisel.log ]] ||
  fail '成功构造没有保存最新阶段日志'
[[ -x $dpi/logs/build/driver-scripts/build-construction.sh &&
  -f $dpi/logs/build/driver-scripts/SHA256SUMS &&
  $(grep -c "^BUILD_DRIVER_SNAPSHOT=$dpi/logs/build/driver-scripts$" "$dpi/logs/build/all.log") == 1 ]] ||
  fail '构造没有固定并使用启动时的 shell 驱动快照'
if valid_build=$($manager build "$npc_root" SimulationConfig 2>&1); then
  fail '有效构造仍接受 build'
fi
[[ $valid_build == *'make build 只允许创建或修复无效构造'* &&
  $valid_build == *"make -C $npc_root rebuild config=SimulationConfig"* ]] ||
  fail '有效构造的 build 拒绝提示不正确'

# 旧 L1 Config 去掉了冗余的 Npc 前缀；保存构造必须迁移到当前目录名，且不能
# 改变用户引用的版本序号。profile 与 construction.env 需要作为同一引用单元更新。
legacy_dpi="$CONSTRUCTION_TEST_ROOT/npc.NpcDpiConfig"
mv "$dpi" "$legacy_dpi"
sed -i -e 's/^CONFIG_SHORT_NAME=SimulationConfig$/CONFIG_SHORT_NAME=NpcDpiConfig/' \
  -e 's/^CONFIG_FQCN=npc.SimulationConfig$/CONFIG_FQCN=npc.NpcDpiConfig/' "$legacy_dpi/profile.env"
sed -i 's/^CONFIG_FQCN=npc.SimulationConfig$/CONFIG_FQCN=npc.NpcDpiConfig/' "$legacy_dpi/construction.env"
legacy_resolution=$("$manager" resolve "$npc_root" '' 1)
[[ $legacy_resolution == npc.SimulationConfig\|* ]] || fail '旧 Config FQCN 没有规范化'
[[ -d $dpi && ! -d $legacy_dpi ]] || fail '旧 Config 构造目录没有迁移'
[[ $(value "$dpi/construction.env" VERSION_INDEX) == 1 ]] || fail 'Config 迁移改变了版本序号'
[[ $(value "$dpi/profile.env" CONFIG_SHORT_NAME) == SimulationConfig ]] || fail 'Config 迁移没有更新短名'

dpi_id=$(value "$dpi/construction.env" CONSTRUCTION_ID)
pipeline_id=$(value "$pipeline/construction.env" CONSTRUCTION_ID)
dpi_version=$(value "$dpi/construction.env" VERSION_INDEX)
pipeline_version=$(value "$pipeline/construction.env" VERSION_INDEX)
version_list=$("$manager" list "$npc_root")
grep -q '^=== 构造属性位图（+ 表示启用）===$' <<< "$version_list" ||
  fail '版本列表没有显示属性位图'
attribute_header=$(grep '^Version  *RV32  *RV64  *M  *F  *Zicsr  *Pipe  *ID  *EX  *valid?  *Arch  *RunningTime  *Config' <<< "$version_list" || true)
[[ -n $attribute_header ]] || fail '属性位图表头不完整'
[[ $version_list != *'=== Config 名称 ==='* && $version_list != *'=== 可构造 Config ==='* ]] ||
  fail '版本列表仍显示 Config 补充表'
grep -Eq '^1.*SimulationConfig$' <<< "$version_list" || fail '版本列表没有显示对应 Config 短名'
[[ $(value "$dpi/version.info" RV32) == 0 && $(value "$dpi/version.info" RV64) == 1 &&
  $(value "$dpi/version.info" M) == 1 && $(value "$dpi/version.info" F) == 0 &&
  $(value "$dpi/version.info" ZICSR) == 1 && $(value "$dpi/version.info" PIPE) == 0 &&
  $(value "$dpi/version.info" ID) == 0 && $(value "$dpi/version.info" EX) == 0 ]] ||
  fail '标量构造的属性信息不正确'
[[ $(value "$pipeline/version.info" PIPE) == 1 && $(value "$pipeline/version.info" ID) == 1 &&
  $(value "$pipeline/version.info" EX) == 1 ]] ||
  fail '流水线三格属性信息不正确'
[[ $version_list != *"$dpi_id"* && $version_list != *"$pipeline_id"* ]] ||
  fail '版本列表泄漏了内部时间 ID'
incomplete="$CONSTRUCTION_TEST_ROOT/unindexed-regression-incomplete"
mkdir -p "$incomplete"
cp "$dpi/profile.env" "$incomplete/"
printf 'CONSTRUCTION_INCOMPLETE=1\n' > "$incomplete/.incomplete"
[[ $("$manager" list "$npc_root") != *"$incomplete"* ]] ||
  fail '版本列表暴露了没有索引文件的 staging 构造'

# version/list/resolve 只读取已发布快照；构造锁被长流程占用时不应被拖住。
lock_ready="$work/lock-ready"
(
  exec 9>"$CONSTRUCTION_TEST_ROOT/.lock"
  flock 9
  : > "$lock_ready"
  sleep 3
) &
lock_holder=$!
for _ in $(seq 1 50); do [[ -e $lock_ready ]] && break; sleep 0.02; done
[[ -e $lock_ready ]] || fail '无法建立构造锁并发测试'
if ! timeout 1 "$manager" list "$npc_root" >/dev/null; then
  kill "$lock_holder" 2>/dev/null || true
  wait "$lock_holder" 2>/dev/null || true
  fail 'make version 在构造锁占用时被阻塞'
fi
wait "$lock_holder" 2>/dev/null || true
cpu_tests=$(realpath "$npc_root/../am-kernels/tests/cpu-tests")
standalone_resolution=$("$manager" resolve "$npc_root" StandaloneConfig '')
[[ $(cut -d'|' -f3 <<< "$standalone_resolution") == run ]] ||
  fail 'StandaloneConfig 没有绑定 NEMU 运行能力'
make -C "$cpu_tests" run-bat ALL=add version="$dpi_version,$pipeline_version" jobs=2 LOG_ROOT="$work/log"
[[ -L $dpi/runtime/add/latest && -L $pipeline/runtime/add/latest ]] ||
  fail '批次运行没有创建隔离 runtime 目录或 latest 链接'
summary=$(find "$work/log/runs" -mindepth 2 -maxdepth 2 -name summary.tsv -type f -print -quit)
[[ -n $summary && $(wc -l < "$summary") == 3 ]] || fail '多版本 batch 未生成完整汇总矩阵'
grep -q $'VERSION\tCONFIG\tRUNTIME\tBOARD\tTEST\tCYCLES\tCOMMITS\tCPI\tIPC\tMIPS\tRESULT' "$summary" ||
  fail '批次汇总字段不完整'
completion=${summary%/summary.tsv}/completion.tsv
[[ -f $completion && $(wc -l < "$completion") == 3 ]] || fail '批次没有生成完成顺序汇总'
details=${summary%/summary.tsv}/details.txt
[[ -f $details ]] || fail '批次没有保存逐项详细报告索引'
grep -q '详细报告：N/A' "$details" || fail 'dry-run 批次详细报告索引没有标记未执行的性能 HTML'
if make -C "$cpu_tests" run ALL=add version="$dpi_version,$pipeline_version" LOG_ROOT="$work/log" >/dev/null 2>&1; then
  fail 'run 接受了多个版本编号'
fi

created=$(value "$dpi/construction.env" CREATED_AT)
host_before=$(sha256sum "$dpi/abi/nemu/nemu-exec" | cut -d' ' -f1)
"$manager" ensure "$npc_root" SimulationConfig 0 0
[[ $(sha256sum "$dpi/abi/nemu/nemu-exec" | cut -d' ' -f1) == "$host_before" ]] ||
  fail '普通 ensure 不应调用 NEMU Make 或替换已保存 host'
[[ $(value "$dpi/construction.env" CONSTRUCTION_ID) == "$dpi_id" ]] || fail '复用构造改变了稳定编号'
[[ $(value "$dpi/construction.env" VERSION_INDEX) == "$dpi_version" ]] || fail '复用构造改变了版本序号'
[[ $(value "$dpi/construction.env" CREATED_AT) == "$created" ]] || fail '复用构造改变了首次构造时间'
[[ $(value "$dpi/construction.env" REBUILD_COUNT) == 0 ]] || fail '未请求 rebuild 却发生重构'

build_hold="$work/rebuild-hold"
# version= 只替代 Config 选择；完整重构仍使用当前源码并保留原版本编号。
CONSTRUCTION_TEST_HOLD_DIR="$build_hold" "$manager" rebuild "$npc_root" "version=$dpi_version" > "$work/rebuild.log" 2>&1 &
build_pid=$!
for _ in $(seq 1 1500); do [[ -e $build_hold/ready ]] && break; sleep 0.02; done
[[ -e $build_hold/ready ]] || fail 'rebuild 没有进入可观察状态'
[[ -d $dpi && $(value "$dpi/version.tag" STATE) == building ]] ||
  fail 'rebuild 没有持续使用稳定 FQCN 目录'
if find "$CONSTRUCTION_TEST_ROOT" -mindepth 1 -maxdepth 1 -type d \( -name '.staging-*' -o -name 'staging-*' \) -print -quit | grep -q .; then
  fail 'rebuild 创建了非稳定的 staging 目录'
fi
if "$manager" resolve "$npc_root" '' "$dpi_version" >/dev/null 2>&1; then
  fail 'rebuild 期间 building 版本仍可运行'
fi
rebuild_list=$("$manager" list "$npc_root" "$dpi_version")
[[ $(awk -v version="$dpi_version" '$1 == version { count++ } END { print count + 0 }' <<< "$rebuild_list") == 1 ]] ||
  fail 'rebuild 期间同一版本显示了多行'
rebuild_line=$(awk -v version="$dpi_version" '$1 == version { print; exit }' <<< "$rebuild_list")
[[ ${rebuild_line:44:6} != *+* ]] || fail 'rebuild 期间版本被错误标为有效'
: > "$build_hold/release"
wait "$build_pid" || fail '成功 rebuild 失败'
build_pid=''
build_hold=''
[[ $(value "$dpi/construction.env" CONSTRUCTION_ID) == "$dpi_id" ]] || fail '成功 rebuild 改变了稳定编号'
[[ $(value "$dpi/construction.env" VERSION_INDEX) == "$dpi_version" ]] || fail '成功 rebuild 改变了版本序号'
[[ $(value "$dpi/construction.env" CREATED_AT) == "$created" ]] || fail '成功 rebuild 改变了首次构造时间'
[[ $(value "$dpi/construction.env" REBUILD_COUNT) == 1 ]] || fail '成功 rebuild 没有计数'
[[ ! -e $dpi/runtime ]] || fail '成功 rebuild 继承了旧硬件 ABI 的 runtime trace'

sed -i -e 's/^PROFILE_FORMAT=.*/PROFILE_FORMAT=2/' -e 's/^CAPABILITY=run$/CAPABILITY=verilator/' \
  -e '/^INTEGER_EXECUTE_STAGES=/d' -e '/^REGISTER_INITIAL_FETCH_REQUEST=/d' \
  -e '/^SERIAL_EXECUTE_STAGES=/d' \
  -e '/^SEPARATE_SERIAL_INTEGER_ALU=/d' -e '/^SERIAL_EXECUTE_RESULT_FORWARDING=/d' \
  -e '/^NEMU_CACHE_HTML=/d' \
  -e '/^FPGA_DIVIDER_NON_BLOCKING=/d' "$dpi/profile.env"
sed -i -e 's/^CAPABILITY=run$/CAPABILITY=verilator/' \
  -e 's/^NEMU_PRESET=LocalPipelineTrace$/NEMU_CONFIG_FQCN=npc.nemu.LocalVerilatorPipelineTraceConfig/' \
  "$dpi/construction.env"
sed -i 's/^NEMU_PRESET=LocalPipelineTrace$/NEMU_CONFIG_FQCN=npc.nemu.LocalVerilatorPipelineTraceConfig/' \
  "$dpi/profile.env"
sed -i -e 's/^HOST_FORMAT=.*/HOST_FORMAT=4/' \
  -e 's/^NEMU_PRESET=LocalPipelineTrace$/NEMU_CONFIG_FQCN=npc.nemu.LocalVerilatorPipelineTraceConfig/' \
  -e '/^NEMU_CACHE_HTML=/d' \
  "$dpi/abi/nemu/host.env"
"$manager" ensure "$npc_root" SimulationConfig 0 0
[[ $(value "$dpi/profile.env" PROFILE_FORMAT) == 22 && $(value "$dpi/profile.env" CAPABILITY) == run &&
  $(value "$dpi/profile.env" INTEGER_EXECUTE_STAGES) == 1 &&
  $(value "$dpi/profile.env" SERIAL_EXECUTE_STAGES) == 1 &&
  $(value "$dpi/profile.env" REGISTER_INITIAL_FETCH_REQUEST) == 0 &&
  $(value "$dpi/profile.env" SEPARATE_SERIAL_INTEGER_ALU) == 0 &&
  $(value "$dpi/profile.env" SERIAL_EXECUTE_RESULT_FORWARDING) == 1 ]] ||
  fail '已保存 profile 未迁移到 run 模式'
[[ $(value "$dpi/construction.env" CAPABILITY) == run ]] ||
  fail '已保存 construction.env 未迁移到 run 模式'
[[ $(value "$dpi/profile.env" NEMU_PRESET) == LocalPipelineTrace &&
  $(value "$dpi/construction.env" NEMU_PRESET) == LocalPipelineTrace &&
  $(value "$dpi/abi/nemu/host.env" NEMU_PRESET) == LocalPipelineTrace &&
  $(value "$dpi/abi/nemu/host.env" HOST_FORMAT) == 7 &&
  $(value "$dpi/abi/nemu/host.env" CORE_CLOCK_MHZ) == 300 &&
  $(value "$dpi/profile.env" NEMU_BACKEND) == local &&
  $(value "$dpi/profile.env" NEMU_PERFORMANCE_HTML) == 1 &&
  $(value "$dpi/profile.env" NEMU_CACHE_HTML) == 0 ]] ||
  fail '历史仿真 profile 升级时改变了已保存 NEMU host 预设'
if grep -q '^NEMU_CONFIG_FQCN=' "$dpi/profile.env" "$dpi/construction.env" "$dpi/abi/nemu/host.env"; then
  fail '历史 NEMU 配置类名迁移后仍残留在保存元数据中'
fi
mkdir -p "$dpi/runtime/preserve"; printf 'keep\n' > "$dpi/runtime/preserve/trace"
"$manager" host-build "$npc_root" SimulationConfig 0 1
[[ -f $dpi/abi/nemu/host.defconfig && -f $dpi/abi/nemu/host.env ]] ||
  fail 'host-build 没有保存生成的 host 元数据和 defconfig'
[[ -s $dpi/logs/host/all.log && -s $dpi/logs/host/nemu-host.log ]] ||
  fail 'host-build 没有保存最新阶段日志'
[[ -f $dpi/runtime/preserve/trace ]] || fail 'host-build 删除了已有 runtime trace'
[[ $(value "$dpi/abi/nemu/host.env" HOST_FORMAT) == 7 &&
  $(value "$dpi/abi/nemu/host.env" CORE_CLOCK_MHZ) == 300 &&
  $(value "$dpi/abi/nemu/host.env" NEMU_PRESET) == LocalPipelineTrace &&
  $(value "$dpi/abi/nemu/host.env" NEMU_PERFORMANCE_HTML) == 1 &&
  $(value "$dpi/abi/nemu/host.env" NEMU_CACHE_HTML) == 1 &&
  $(value "$dpi/abi/nemu/host.env" NEMU_PIPELINE_HTML) == 1 ]] ||
  fail 'host 元数据没有升级性能/流水 HTML ABI'
# 版本选择必须直接命中保存目录；即使旧 host 与保存 profile 失配，也不能退回当前 catalog 解析。
"$manager" host-build "$npc_root" "version=$dpi_version" 0 1
[[ -f $dpi/runtime/preserve/trace &&
  $(value "$dpi/abi/nemu/host.env" NEMU_PRESET) == LocalPipelineTrace ]] ||
  fail 'host-build version= 没有刷新已保存构造或破坏运行产物'
"$manager" host-build "$npc_root" '' 1 -1
"$manager" ensure "$npc_root" SimulationConfig 0 1
if "$manager" ensure "$npc_root" SimulationConfig 0 0 1 >/dev/null 2>&1; then
  fail 'ensure 仍接受已删除的 rebuild 位置参数'
fi

# profile、construction 元数据、host 与成功日志必须作为一个事务发布。备份阶段和
# 发布末段的故障都不能留下新旧内容混合的构造。
host_state_before=$(find "$dpi/profile.env" "$dpi/construction.env" "$dpi/abi/nemu" "$dpi/logs/host" \
  -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
for failure_phase in backup-construction publish-logs; do
  if CONSTRUCTION_TEST_HOST_PUBLISH_FAIL="$failure_phase" \
    "$manager" host-build "$npc_root" SimulationConfig 0 1 >/dev/null 2>&1; then
    fail "模拟 $failure_phase 发布故障意外成功"
  fi
  [[ $(find "$dpi/profile.env" "$dpi/construction.env" "$dpi/abi/nemu" "$dpi/logs/host" \
    -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1) == "$host_state_before" ]] ||
    fail "$failure_phase 发布故障破坏了旧 host 构造"
done
if find "$dpi" -name '.profile-host-previous.*' -o -name '.construction-host-previous.*' -o \
  -name '.nemu-host-previous.*' -o -name '.profile-host-staging.*' -o \
  -name '.construction-host-staging.*' -o -name '.nemu-host-staging.*' -o \
  -name '.host-staging-*' -o -name '.host-previous-*' | grep -q .; then
  fail 'host 发布故障回滚后残留 staging 或 backup'
fi

if failure=$(CONSTRUCTION_TEST_FAIL=1 "$manager" rebuild "$npc_root" SimulationConfig 2>&1); then
  fail '模拟失败的重构意外成功'
fi
[[ $failure == *"make -C $npc_root rebuild config=SimulationConfig"* ]] ||
  fail '失败重构未提示可复制的 rebuild 命令'
[[ $failure == *'失败原因（关键日志）：'* && $failure == *'按测试请求模拟构造失败'* ]] ||
  fail '失败重构未输出关键日志片段'
[[ -d $dpi && $(value "$dpi/version.tag" STATE) == failed && ! -f $dpi/construction.env ]] ||
  fail '失败重构没有在稳定目录保留 failed 状态'
[[ -s "$CONSTRUCTION_TEST_ROOT/.failed/npc.SimulationConfig/build/all.log" ]] ||
  fail '失败重构没有保存日志'
"$manager" build "$npc_root" SimulationConfig
[[ $(value "$dpi/version.tag" STATE) == complete && -f $dpi/construction.env ]] ||
  fail '无效构造不能通过 build 直接修复'

if "$manager" resolve "$npc_root" SimulationConfig "$pipeline_version" >/dev/null 2>&1; then
  fail 'config/version 不一致未被拒绝'
fi
if "$manager" delete "$npc_root" "$pipeline_version" 1 >/dev/null 2>&1; then
  fail 'delete 仍接受已删除的确认参数'
fi

u55c="$CONSTRUCTION_TEST_ROOT/npc.fpga.u55c.U55cYsyxSocFpgaConfig"
if "$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0 >/dev/null 2>&1; then
  fail '缺失 FPGA 构造未要求 build=1'
fi
"$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 1 0
[[ -f $u55c/fpga/artifacts/artifact-manifest.env ]] || fail 'FPGA dry-run 未生成资产清单'
[[ $(value "$u55c/version.info" ARCH) == SoC && $(value "$u55c/version.info" RUNNING_TIME) == FPGA ]] ||
  fail 'U55C SoC FPGA 构造的文本属性不正确'
[[ $(value "$u55c/.complete" FPGA_ARTIFACT) == "fpga/artifacts/npc-$(value "$u55c/profile.env" FPGA_PLATFORM).xclbin" ]] ||
  fail 'FPGA 完成标志没有记录实际 xclbin'
[[ -s $u55c/fpga/ip-generated/logs/npc_int_multiplier_ip.log && -s $u55c/fpga/ip-generated/logs/npc_int_divider_ip.log ]] ||
  fail 'FPGA dry-run 未生成逐 IP 日志'

# Vitis may have already completed implementation and xclbin packaging when a
# post-link validator fails.  Resume must validate that saved xclbin, produce
# its manifest, and build the host without rerunning the hardware phases.
u55c_platform=$(value "$u55c/profile.env" FPGA_PLATFORM)
u55c_version=$(value "$u55c/version.tag" VERSION_INDEX)
u55c_xclbin="$u55c/fpga/artifacts/npc-$u55c_platform.xclbin"
u55c_xclbin_sha_before=$(sha256sum "$u55c_xclbin" | cut -d' ' -f1)
rm -rf "$u55c/abi/nemu"
rm -f "$u55c/construction.env" "$u55c/.complete" "$u55c/fpga/.link.complete" \
  "$u55c/fpga/artifacts/artifact-manifest.env" "$u55c/fpga/artifacts/SHA256SUMS" \
  "$u55c/fpga/artifacts/npc.wns"
# The dry-run flow has no physical Vivado phases, so model the synthesis stamp
# that a genuine post-link recovery requires as evidence of implementation work.
: > "$u55c/fpga/.synthesis.complete"
printf 'CONSTRUCTION_INCOMPLETE=1\nCONFIG_FQCN=%s\nSTARTED_AT=2026-07-30T00:00:00Z\n' \
  "$(value "$u55c/profile.env" CONFIG_FQCN)" > "$u55c/.incomplete"
printf 'VERSION_TAG_FORMAT=1\nVERSION_INDEX=%s\nSTATE=failed\n' "$u55c_version" > "$u55c/version.tag"
printf 'INFO: [v++ 60-1307] Run completed.\n' > "$u55c/logs/build/link.log"
mkdir -p "$u55c/fpga/vitis-reports/link/imp"
cat > "$u55c/fpga/vitis-reports/link/imp/test_timing_summary_routed.rpt" <<'EOF'
WNS(ns)
-------
0.000
EOF
fake_xclbinutil="$work/xclbinutil"
printf '%s\n' '#!/usr/bin/env bash' \
  'printf "%s\\n" "Scalable Clocks" "   Name:      DATA_CLK" "   Frequency:  300 MHz" "System Clocks"' > "$fake_xclbinutil"
chmod +x "$fake_xclbinutil"
XCLBINUTIL="$fake_xclbinutil" "$manager" resume-post-link "$npc_root" U55cYsyxSocFpgaConfig
[[ $(value "$u55c/version.tag" STATE) == complete && ! -e $u55c/.incomplete &&
  -f $u55c/fpga/.link.complete && -f $u55c/fpga/artifacts/artifact-manifest.env &&
  -x $u55c/abi/nemu/nemu-exec ]] ||
  fail 'post-link 恢复没有完成资产校验、host 构造与版本发布'
[[ $(sha256sum "$u55c_xclbin" | cut -d' ' -f1) == "$u55c_xclbin_sha_before" ]] ||
  fail 'post-link 恢复意外重新生成了 xclbin'

# 完整 FPGA 资产已经生成而末尾 NEMU host 失败时，host-build 必须只恢复 host。
# 同时模拟旧版本失败目录没有 construction.env 的情形，确保无需重跑硬件即可迁移。
if fpga_host_failure=$(CONSTRUCTION_TEST_HOST_FAIL=1 "$manager" rebuild "$npc_root" U55cYsyxSocFpgaConfig 2>&1); then
  fail '模拟 FPGA host 失败的重构意外成功'
fi
[[ $fpga_host_failure == *'可使用 host-build 只重试失败的 NEMU host'* &&
  $(value "$u55c/version.tag" STATE) == failed && -f $u55c/construction.env && -e $u55c/.incomplete ]] ||
  fail 'FPGA host 失败没有保留可恢复的正式构造元数据'
u55c_assets_after_host_failure=$(find "$u55c/fpga" -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
rm -f "$u55c/construction.env"
"$manager" host-build "$npc_root" U55cYsyxSocFpgaConfig 0 1
[[ $(value "$u55c/version.tag" STATE) == complete && ! -e $u55c/.incomplete &&
  -f $u55c/construction.env && -x $u55c/abi/nemu/nemu-exec &&
  $(value "$u55c/abi/nemu/host.env" CORE_CLOCK_MHZ) == $(value "$u55c/profile.env" FPGA_CLOCK_MHZ) &&
  $(value "$u55c/construction.env" SOURCE_REV) == unknown ]] ||
  fail 'host-build 没有恢复旧失败 FPGA 构造'
[[ $(find "$u55c/fpga" -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1) == "$u55c_assets_after_host_failure" ]] ||
  fail '恢复失败 FPGA host 时重新构造或修改了硬件资产'

# host-build 重新读取当前终端的 NEMU case class，但保存 profile 的硬件和 FPGA
# 工具链字段以及全部 FPGA 资产必须保持冻结。
sed -i -e 's/^NEMU_PRESET=.*/NEMU_PRESET=Custom/' \
  -e 's/^NEMU_OPTIMIZATION=.*/NEMU_OPTIMIZATION=O0/' \
  -e 's/^FPGA_VIVADO_IMPL_JOBS=.*/FPGA_VIVADO_IMPL_JOBS=99/' "$u55c/profile.env"
non_nemu_before=$(awk '!/^NEMU_/' "$u55c/profile.env" | sha256sum | cut -d' ' -f1)
fpga_assets_before=$(find "$u55c/fpga" -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
"$manager" host-build "$npc_root" U55cYsyxSocFpgaConfig 0 1
[[ $(value "$u55c/profile.env" NEMU_PRESET) == U55cBase &&
  $(value "$u55c/profile.env" NEMU_OPTIMIZATION) == O2 &&
  $(value "$u55c/abi/nemu/host.env" NEMU_PRESET) == U55cBase ]] ||
  fail 'FPGA host-build 没有从当前终端刷新 NEMU case class'
[[ $(value "$u55c/profile.env" FPGA_VIVADO_IMPL_JOBS) == 99 ]] ||
  fail 'host-build 错误吸收了当前 FPGA 工具链字段'
[[ $(awk '!/^NEMU_/' "$u55c/profile.env" | sha256sum | cut -d' ' -f1) == "$non_nemu_before" ]] ||
  fail 'host-build 修改了保存 profile 的非 NEMU 字段'
[[ $(find "$u55c/fpga" -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1) == "$fpga_assets_before" ]] ||
  fail 'host-build 修改了 FPGA 资产'
if fpga_failure=$(CONSTRUCTION_TEST_FAIL=1 "$manager" rebuild "$npc_root" U55cYsyxSocFpgaConfig 2>&1); then
  fail '模拟失败的 FPGA 重构意外成功'
fi
[[ $fpga_failure == *"make -C $npc_root rebuild config=U55cYsyxSocFpgaConfig"* ]] ||
  fail '失败的 FPGA 重构未提示 Config 短名'
fpga_failed="$CONSTRUCTION_TEST_ROOT/.failed/npc.fpga.u55c.U55cYsyxSocFpgaConfig/build"
[[ -f $fpga_failed/profile.env && -s $fpga_failed/fpga/ip-generated/logs/npc_int_multiplier_ip.log &&
  -s $fpga_failed/fpga/ip-generated/logs/npc_int_divider_ip.log ]] ||
  fail '失败的 FPGA 重构没有保存 profile 与逐 IP 证据'
[[ -d $u55c && $(value "$u55c/version.tag" STATE) == failed && ! -f $u55c/construction.env ]] ||
  fail '失败的 FPGA 重构没有在稳定目录保留 failed 状态'
if "$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0 >/dev/null 2>&1; then
  fail '失败的 FPGA 构造仍被放行'
fi
"$manager" rebuild "$npc_root" U55cYsyxSocFpgaConfig
sed -i -e 's/^PROFILE_FORMAT=.*/PROFILE_FORMAT=3/' -e 's/^SCOPE=fpga$/SCOPE=fpga-soc/' "$u55c/profile.env"
"$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0
[[ $(value "$u55c/profile.env" PROFILE_FORMAT) == 22 && $(value "$u55c/profile.env" SCOPE) == fpga &&
  $(value "$u55c/profile.env" INTEGER_EXECUTE_STAGES) == 1 &&
  $(value "$u55c/profile.env" SERIAL_EXECUTE_STAGES) == 1 &&
  $(value "$u55c/profile.env" REGISTER_INITIAL_FETCH_REQUEST) == 0 &&
  $(value "$u55c/profile.env" SEPARATE_SERIAL_INTEGER_ALU) == 0 &&
  $(value "$u55c/profile.env" SERIAL_EXECUTE_RESULT_FORWARDING) == 1 &&
  $(value "$u55c/profile.env" FPGA_DIVIDER_NON_BLOCKING) == 0 &&
  $(value "$u55c/profile.env" FPGA_RUNTIME_SDB) == 1 &&
  $(value "$u55c/profile.env" FPGA_RUNTIME_TRACE) == 0 &&
  $(value "$u55c/profile.env" FPGA_TRACE_FORMAT) == 0 &&
  $(value "$u55c/profile.env" FPGA_TRACE_RECORD_BYTES) == 0 &&
  $(value "$u55c/profile.env" FPGA_TRACE_DATA_WIDTH) == 0 &&
  $(value "$u55c/profile.env" FPGA_TRACE_BURST_RECORDS) == 0 ]] ||
  fail '已保存 FPGA profile 未迁移到统一 fpga 作用域'

platform=$(value "$u55c/profile.env" FPGA_PLATFORM)
mv "$u55c/fpga/artifacts/npc-$platform.xclbin" "$u55c/fpga/artifacts/npc-$platform.xclbin.missing"
u55c_version=$(value "$u55c/construction.env" VERSION_INDEX)
missing_asset_line=$("$manager" list "$npc_root" "$u55c_version" | awk -v version="$u55c_version" '$1 == version { print; exit }')
[[ -n $missing_asset_line && ${missing_asset_line:44:6} != *+* ]] ||
  fail '缺少实际 xclbin 的 FPGA 构造没有保留为无效版本'
if "$manager" resolve "$npc_root" '' "$u55c_version" >/dev/null 2>&1; then
  fail '缺少实际 xclbin 的 FPGA 构造仍能作为版本运行'
fi
mv "$u55c/fpga/artifacts/npc-$platform.xclbin.missing" "$u55c/fpga/artifacts/npc-$platform.xclbin"
printf 'tampered\n' > "$u55c/fpga/artifacts/npc-$platform.xclbin"
if "$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0 >/dev/null 2>&1; then
  fail '损坏的 FPGA 资产被放行'
fi
"$manager" rebuild "$npc_root" U55cYsyxSocFpgaConfig
[[ $(value "$u55c/profile.env" FPGA_VIVADO_IMPL_JOBS) == 8 ]] ||
  fail 'rebuild 没有吸收当前 FPGA 工具链字段'

if make -C "$npc_root" version D=1,2 delete=1,3 >/dev/null 2>&1; then
  fail '不同版本集合的 D= 与 delete= 冲突仍被接受'
fi
if make -C "$npc_root" version D=1--2 >/dev/null 2>&1; then
  fail '非法版本列表仍被接受'
fi

# D=1-2-3 和 delete=1,2,3 必须按同一张删除前版本表解析；别名的顺序或
# 分隔符不同不应构成冲突。一次性删除后不应保留任何旧构造目录。
make -C "$npc_root" version D=1-2-3 delete=3,2,1 >/dev/null
[[ ! -d $dpi && ! -d $pipeline && ! -d $u55c ]] ||
  fail '多版本 D=/delete= 没有删除全部原始构造'

# 重新建立 1、2、3 后，只通过 delete= 删除原始 1、2。若实现是在每次删除
# 后立即重编号，第二次会错误地删除原始 3；这里必须保留它并只在最后压缩为 1。
"$manager" build "$npc_root" SimulationConfig
"$manager" build "$npc_root" PipelineSimulationConfig
"$manager" build "$npc_root" StandaloneConfig
standalone="$CONSTRUCTION_TEST_ROOT/npc.StandaloneConfig"
[[ $(value "$dpi/construction.env" VERSION_INDEX) == 1 &&
  $(value "$pipeline/construction.env" VERSION_INDEX) == 2 &&
  $(value "$standalone/construction.env" VERSION_INDEX) == 3 ]] ||
  fail '多版本删除回归没有建立连续的原始版本表'
if make -C "$npc_root" version delete=1,4 >/dev/null 2>&1; then
  fail '包含不存在编号的多版本删除仍被接受'
fi
[[ -d $dpi && -d $pipeline && -d $standalone ]] ||
  fail '包含不存在编号的多版本删除发生了部分删除'
make -C "$npc_root" version delete=1,2 >/dev/null
[[ ! -d $dpi && ! -d $pipeline && -d $standalone ]] ||
  fail 'delete=1,2 没有按原始版本表删除'
[[ $(value "$standalone/construction.env" VERSION_INDEX) == 1 &&
  $(value "$standalone/version.tag" VERSION_INDEX) == 1 ]] ||
  fail '多版本删除没有只在全部删除后紧凑重映射'
make -C "$npc_root" version D=1 delete=1 >/dev/null
[[ ! -d $standalone ]] || fail '相同 D=/delete= 未删除构造'
printf 'Config 构造生命周期回归通过\n'
