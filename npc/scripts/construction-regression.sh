#!/usr/bin/env bash
# 使用隔离的 dry-run 构造库验证编号、原子更新和失配策略。
set -euo pipefail

npc_root=${1:?用法：construction-regression.sh <npc-root>}
npc_root=$(realpath "$npc_root")
manager="$npc_root/scripts/construction-manager.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

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
export SCPU_CONFIG_CATALOG_READY=1

"$manager" build "$npc_root" SimulationConfig 0
"$manager" build "$npc_root" PipelineSimulationConfig 0
dpi="$CONSTRUCTION_TEST_ROOT/scpu.SimulationConfig"
pipeline="$CONSTRUCTION_TEST_ROOT/scpu.PipelineSimulationConfig"
[[ $(value "$dpi/construction.env" CONSTRUCTION_ID) == 2026071815304200 ]] || fail '首个编号不是 00'
[[ $(value "$pipeline/construction.env" CONSTRUCTION_ID) == 2026071815304201 ]] || fail '同秒编号没有递增'
[[ $(value "$dpi/construction.env" VERSION_INDEX) == 1 ]] || fail '首个版本序号不是 1'
[[ $(value "$pipeline/construction.env" VERSION_INDEX) == 2 ]] || fail '第二个版本序号不是 2'
[[ $(value "$dpi/.complete" CONSTRUCTION_COMPLETE) == 1 &&
  $(value "$pipeline/.complete" CONSTRUCTION_COMPLETE) == 1 ]] ||
  fail '成功构造没有写入完成标志'
[[ ! -e $dpi/.incomplete && ! -e $pipeline/.incomplete ]] ||
  fail '成功构造仍保留未完成标志'
[[ $(value "$dpi/profile.env" NEMU_PERFORMANCE_HTML) == 1 &&
  $(value "$pipeline/profile.env" NEMU_PERFORMANCE_HTML) == 1 &&
  $(value "$dpi/profile.env" NEMU_PIPELINE_HTML) == 1 &&
  $(value "$pipeline/profile.env" NEMU_PIPELINE_HTML) == 1 ]] ||
  fail '本地性能/流水 HTML profile 选择不正确'
[[ -s $dpi/logs/build/all.log && -s $dpi/logs/build/chisel.log ]] ||
  fail '成功构造没有保存最新阶段日志'

# 旧 L1 Config 去掉了冗余的 Npc 前缀；保存构造必须迁移到当前目录名，且不能
# 改变用户引用的版本序号。profile 与 construction.env 需要作为同一引用单元更新。
legacy_dpi="$CONSTRUCTION_TEST_ROOT/scpu.NpcDpiConfig"
mv "$dpi" "$legacy_dpi"
sed -i -e 's/^CONFIG_SHORT_NAME=SimulationConfig$/CONFIG_SHORT_NAME=NpcDpiConfig/' \
  -e 's/^CONFIG_FQCN=scpu.SimulationConfig$/CONFIG_FQCN=scpu.NpcDpiConfig/' "$legacy_dpi/profile.env"
sed -i 's/^CONFIG_FQCN=scpu.SimulationConfig$/CONFIG_FQCN=scpu.NpcDpiConfig/' "$legacy_dpi/construction.env"
legacy_resolution=$("$manager" resolve "$npc_root" '' 1)
[[ $legacy_resolution == scpu.SimulationConfig\|* ]] || fail '旧 Config FQCN 没有规范化'
[[ -d $dpi && ! -d $legacy_dpi ]] || fail '旧 Config 构造目录没有迁移'
[[ $(value "$dpi/construction.env" VERSION_INDEX) == 1 ]] || fail 'Config 迁移改变了版本序号'
[[ $(value "$dpi/profile.env" CONFIG_SHORT_NAME) == SimulationConfig ]] || fail 'Config 迁移没有更新短名'

dpi_id=$(value "$dpi/construction.env" CONSTRUCTION_ID)
pipeline_id=$(value "$pipeline/construction.env" CONSTRUCTION_ID)
dpi_version=$(value "$dpi/construction.env" VERSION_INDEX)
pipeline_version=$(value "$pipeline/construction.env" VERSION_INDEX)
version_list=$("$manager" list "$npc_root")
grep -Eq '^1[[:space:]]+SimulationConfig$' <<< "$version_list" ||
  fail 'Config 名称表没有显示版本与短名映射'
grep -q '^=== 构造属性位图（+ 表示启用）===$' <<< "$version_list" ||
  fail '版本列表没有显示属性位图'
attribute_header=$(grep '^Version  *RV32  *RV64  *M  *F  *Zicsr  *Pipe  *ID  *EX  *NPC  *SoC  *FPGA  *U55C  *ZCU102' <<< "$version_list" || true)
[[ -n $attribute_header ]] || fail '属性位图表头不完整'
dpi_attributes=$(awk '/^=== 构造属性位图/{in_attributes=1; next} /^=== Config 名称 ===/{in_attributes=0} in_attributes && $1 == 1 {print}' <<< "$version_list")
pipeline_attributes=$(awk '/^=== 构造属性位图/{in_attributes=1; next} /^=== Config 名称 ===/{in_attributes=0} in_attributes && $1 == 2 {print}' <<< "$version_list")
[[ ${dpi_attributes:9:1} == ' ' && ${dpi_attributes:14:1} == + &&
  ${dpi_attributes:19:1} == + && ${dpi_attributes:22:1} == ' ' && ${dpi_attributes:25:1} == + &&
  ${dpi_attributes:31:1} == ' ' && ${dpi_attributes:36:1} == ' ' && ${dpi_attributes:40:1} == ' ' &&
  ${dpi_attributes:44:1} == + && ${dpi_attributes:48:1} == ' ' && ${dpi_attributes:52:1} == ' ' ]] ||
  fail '标量 RV64IM_Zicsr 构造的属性位图不正确'
[[ ${pipeline_attributes:9:1} == ' ' && ${pipeline_attributes:14:1} == + &&
  ${pipeline_attributes:19:1} == + && ${pipeline_attributes:22:1} == ' ' && ${pipeline_attributes:25:1} == + &&
  ${pipeline_attributes:31:1} == + && ${pipeline_attributes:36:1} == + && ${pipeline_attributes:40:1} == + &&
  ${pipeline_attributes:44:1} == + && ${pipeline_attributes:48:1} == ' ' && ${pipeline_attributes:52:1} == ' ' ]] ||
  fail '流水线三格或 RV64IM_Zicsr 属性位图不正确'
[[ $version_list != *"$dpi_id"* && $version_list != *"$pipeline_id"* ]] ||
  fail '版本列表泄漏了内部时间 ID'
incomplete="$CONSTRUCTION_TEST_ROOT/.staging-regression-incomplete"
mkdir -p "$incomplete"
cp "$dpi/construction.env" "$dpi/profile.env" "$incomplete/"
printf 'CONSTRUCTION_INCOMPLETE=1\n' > "$incomplete/.incomplete"
[[ $("$manager" list "$npc_root") != *"$incomplete"* ]] ||
  fail '版本列表暴露了未完成 staging 构造'

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
"$manager" ensure "$npc_root" SimulationConfig 0 0 0
[[ $(sha256sum "$dpi/abi/nemu/nemu-exec" | cut -d' ' -f1) == "$host_before" ]] ||
  fail '普通 ensure 不应调用 NEMU Make 或替换已保存 host'
[[ $(value "$dpi/construction.env" CONSTRUCTION_ID) == "$dpi_id" ]] || fail '复用构造改变了稳定编号'
[[ $(value "$dpi/construction.env" VERSION_INDEX) == "$dpi_version" ]] || fail '复用构造改变了版本序号'
[[ $(value "$dpi/construction.env" CREATED_AT) == "$created" ]] || fail '复用构造改变了首次构造时间'
[[ $(value "$dpi/construction.env" REBUILD_COUNT) == 0 ]] || fail '未请求 rebuild 却发生重构'

"$manager" ensure "$npc_root" SimulationConfig 0 1 0
[[ $(value "$dpi/construction.env" CONSTRUCTION_ID) == "$dpi_id" ]] || fail '成功 rebuild 改变了稳定编号'
[[ $(value "$dpi/construction.env" VERSION_INDEX) == "$dpi_version" ]] || fail '成功 rebuild 改变了版本序号'
[[ $(value "$dpi/construction.env" CREATED_AT) == "$created" ]] || fail '成功 rebuild 改变了首次构造时间'
[[ $(value "$dpi/construction.env" REBUILD_COUNT) == 1 ]] || fail '成功 rebuild 没有计数'
[[ ! -e $dpi/runtime ]] || fail '成功 rebuild 继承了旧硬件 ABI 的 runtime trace'

sed -i -e 's/^PROFILE_FORMAT=.*/PROFILE_FORMAT=2/' -e 's/^CAPABILITY=run$/CAPABILITY=verilator/' "$dpi/profile.env"
sed -i -e 's/^CAPABILITY=run$/CAPABILITY=verilator/' \
  -e 's/^NEMU_PRESET=LocalPipelineTrace$/NEMU_CONFIG_FQCN=scpu.nemu.LocalVerilatorPipelineTraceConfig/' \
  "$dpi/construction.env"
sed -i 's/^NEMU_PRESET=LocalPipelineTrace$/NEMU_CONFIG_FQCN=scpu.nemu.LocalVerilatorPipelineTraceConfig/' \
  "$dpi/profile.env"
sed -i -e 's/^HOST_FORMAT=.*/HOST_FORMAT=4/' \
  -e 's/^NEMU_PRESET=LocalPipelineTrace$/NEMU_CONFIG_FQCN=scpu.nemu.LocalVerilatorPipelineTraceConfig/' \
  "$dpi/abi/nemu/host.env"
"$manager" ensure "$npc_root" SimulationConfig 0 0 0
[[ $(value "$dpi/profile.env" PROFILE_FORMAT) == 10 && $(value "$dpi/profile.env" CAPABILITY) == run ]] ||
  fail '已保存 profile 未迁移到 run 模式'
[[ $(value "$dpi/construction.env" CAPABILITY) == run ]] ||
  fail '已保存 construction.env 未迁移到 run 模式'
[[ $(value "$dpi/profile.env" NEMU_PRESET) == LocalPipelineTrace &&
  $(value "$dpi/construction.env" NEMU_PRESET) == LocalPipelineTrace &&
  $(value "$dpi/abi/nemu/host.env" NEMU_PRESET) == LocalPipelineTrace &&
  $(value "$dpi/abi/nemu/host.env" HOST_FORMAT) == 5 &&
  $(value "$dpi/profile.env" NEMU_BACKEND) == local &&
  $(value "$dpi/profile.env" NEMU_PERFORMANCE_HTML) == 1 ]] ||
  fail '历史仿真 profile 升级时改变了已保存 NEMU host 预设'
if rg -q '^NEMU_CONFIG_FQCN=' "$dpi/profile.env" "$dpi/construction.env" "$dpi/abi/nemu/host.env"; then
  fail '历史 NEMU 配置类名迁移后仍残留在保存元数据中'
fi
mkdir -p "$dpi/runtime/preserve"; printf 'keep\n' > "$dpi/runtime/preserve/trace"
"$manager" host-build "$npc_root" SimulationConfig 0 1
[[ -f $dpi/abi/nemu/host.defconfig && -f $dpi/abi/nemu/host.env ]] ||
  fail 'host-build 没有保存生成的 host 元数据和 defconfig'
[[ -s $dpi/logs/host/all.log && -s $dpi/logs/host/nemu-host.log ]] ||
  fail 'host-build 没有保存最新阶段日志'
[[ -f $dpi/runtime/preserve/trace ]] || fail 'host-build 删除了已有 runtime trace'
[[ $(value "$dpi/abi/nemu/host.env" HOST_FORMAT) == 5 &&
  $(value "$dpi/abi/nemu/host.env" NEMU_PRESET) == LocalPipelineTrace &&
  $(value "$dpi/abi/nemu/host.env" NEMU_PERFORMANCE_HTML) == 1 &&
  $(value "$dpi/abi/nemu/host.env" NEMU_PIPELINE_HTML) == 1 ]] ||
  fail 'host 元数据没有升级性能/流水 HTML ABI'
"$manager" host-build "$npc_root" '' 1 -1
"$manager" ensure "$npc_root" SimulationConfig 0 0 1
if "$manager" ensure "$npc_root" SimulationConfig 0 1 1 >/dev/null 2>&1; then
  fail 'rebuild=1 与 host-rebuild=1 可以同时使用'
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
  -name '.host-staging-*' -o -name '.host-previous-*' | rg -q .; then
  fail 'host 发布故障回滚后残留 staging 或 backup'
fi

before=$(sha256sum "$dpi/construction.env" | cut -d' ' -f1)
if failure=$(CONSTRUCTION_TEST_FAIL=1 "$manager" build "$npc_root" SimulationConfig 1 2>&1); then
  fail '模拟失败的重构意外成功'
fi
[[ $failure == *"make -C $npc_root build config=scpu.SimulationConfig rebuild=1"* ]] ||
  fail '失败重构未提示可复制的 rebuild=1 命令'
[[ $failure == *'失败原因（关键日志）：'* && $failure == *'按测试请求模拟构造失败'* ]] ||
  fail '失败重构未输出关键日志片段'
[[ $(sha256sum "$dpi/construction.env" | cut -d' ' -f1) == "$before" ]] || fail '失败重构破坏了旧构造'
[[ -s "$CONSTRUCTION_TEST_ROOT/.failed/scpu.SimulationConfig/build/all.log" ]] ||
  fail '失败重构没有保存日志'

if "$manager" resolve "$npc_root" SimulationConfig "$pipeline_version" >/dev/null 2>&1; then
  fail 'config/version 不一致未被拒绝'
fi
if "$manager" delete "$npc_root" "$pipeline_version" 0 </dev/null >/dev/null 2>&1; then
  fail '非交互删除未要求 yes=1'
fi

u55c="$CONSTRUCTION_TEST_ROOT/scpu.fpga.u55c.U55cYsyxSocFpgaConfig"
if "$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0 0 >/dev/null 2>&1; then
  fail '缺失 FPGA 构造未要求 build=1'
fi
"$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 1 0 0
[[ -f $u55c/fpga/artifacts/artifact-manifest.env ]] || fail 'FPGA dry-run 未生成资产清单'
[[ $(value "$u55c/.complete" FPGA_ARTIFACT) == "fpga/artifacts/npc-$(value "$u55c/profile.env" FPGA_PLATFORM).xclbin" ]] ||
  fail 'FPGA 完成标志没有记录实际 xclbin'
[[ -s $u55c/fpga/ip/logs/npc_int_multiplier_ip.log && -s $u55c/fpga/ip/logs/npc_int_divider_ip.log ]] ||
  fail 'FPGA dry-run 未生成逐 IP 日志'

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
fpga_before=$(sha256sum "$u55c/construction.env" | cut -d' ' -f1)
if CONSTRUCTION_TEST_FAIL=1 "$manager" build "$npc_root" U55cYsyxSocFpgaConfig 1 >/dev/null 2>&1; then
  fail '模拟失败的 FPGA 重构意外成功'
fi
fpga_failed="$CONSTRUCTION_TEST_ROOT/.failed/scpu.fpga.u55c.U55cYsyxSocFpgaConfig/build"
[[ -f $fpga_failed/profile.env && -s $fpga_failed/fpga/ip/logs/npc_int_multiplier_ip.log &&
  -s $fpga_failed/fpga/ip/logs/npc_int_divider_ip.log ]] ||
  fail '失败的 FPGA 重构没有保存 profile 与逐 IP 证据'
[[ $(sha256sum "$u55c/construction.env" | cut -d' ' -f1) == "$fpga_before" ]] ||
  fail '失败的 FPGA 重构破坏了旧构造'
"$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0 0
sed -i -e 's/^PROFILE_FORMAT=.*/PROFILE_FORMAT=3/' -e 's/^SCOPE=fpga$/SCOPE=fpga-soc/' "$u55c/profile.env"
"$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0 0
[[ $(value "$u55c/profile.env" PROFILE_FORMAT) == 10 && $(value "$u55c/profile.env" SCOPE) == fpga ]] ||
  fail '已保存 FPGA profile 未迁移到统一 fpga 作用域'

platform=$(value "$u55c/profile.env" FPGA_PLATFORM)
mv "$u55c/fpga/artifacts/npc-$platform.xclbin" "$u55c/fpga/artifacts/npc-$platform.xclbin.missing"
[[ $("$manager" list "$npc_root") != *"U55cYsyxSocFpgaConfig"* ]] ||
  fail '缺少实际 xclbin 的 FPGA 构造仍被 version 视为完成'
mv "$u55c/fpga/artifacts/npc-$platform.xclbin.missing" "$u55c/fpga/artifacts/npc-$platform.xclbin"
printf 'tampered\n' > "$u55c/fpga/artifacts/npc-$platform.xclbin"
if "$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0 0 >/dev/null 2>&1; then
  fail '损坏的 FPGA 资产被放行'
fi
"$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 1 0
[[ $(value "$u55c/profile.env" FPGA_VIVADO_IMPL_JOBS) == 8 ]] ||
  fail 'rebuild=1 没有吸收当前 FPGA 工具链字段'

"$manager" delete "$npc_root" "$pipeline_version" 1
[[ ! -d $pipeline ]] || fail 'yes=1 未删除构造'
"$manager" build "$npc_root" StandaloneConfig 0
standalone="$CONSTRUCTION_TEST_ROOT/scpu.StandaloneConfig"
[[ $(value "$standalone/construction.env" VERSION_INDEX) == 4 ]] ||
  fail '删除后新构造复用了已有版本序号'
printf 'Config 构造生命周期回归通过\n'
