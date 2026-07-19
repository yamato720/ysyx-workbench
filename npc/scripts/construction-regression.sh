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

"$manager" build "$npc_root" NpcDpiConfig 0
"$manager" build "$npc_root" NpcPipelineDpiConfig 0
dpi="$CONSTRUCTION_TEST_ROOT/scpu.NpcDpiConfig"
pipeline="$CONSTRUCTION_TEST_ROOT/scpu.NpcPipelineDpiConfig"
[[ $(value "$dpi/construction.env" CONSTRUCTION_ID) == 2026071815304200 ]] || fail '首个编号不是 00'
[[ $(value "$pipeline/construction.env" CONSTRUCTION_ID) == 2026071815304201 ]] || fail '同秒编号没有递增'
[[ $(value "$dpi/construction.env" VERSION_INDEX) == 1 ]] || fail '首个版本序号不是 1'
[[ $(value "$pipeline/construction.env" VERSION_INDEX) == 2 ]] || fail '第二个版本序号不是 2'

dpi_id=$(value "$dpi/construction.env" CONSTRUCTION_ID)
pipeline_id=$(value "$pipeline/construction.env" CONSTRUCTION_ID)
dpi_version=$(value "$dpi/construction.env" VERSION_INDEX)
pipeline_version=$(value "$pipeline/construction.env" VERSION_INDEX)
version_list=$("$manager" list "$npc_root")
grep -Eq '^1[[:space:]]+NpcDpiConfig[[:space:]]' <<< "$version_list" ||
  fail '版本列表没有显示短序号'
[[ $version_list != *"$dpi_id"* && $version_list != *"$pipeline_id"* ]] ||
  fail '版本列表泄漏了内部时间 ID'
cpu_tests=$(realpath "$npc_root/../am-kernels/tests/cpu-tests")
make -C "$cpu_tests" run-bat ALL=add version="$dpi_version,$pipeline_version" LOG_ROOT="$work/log"
summary=$(find "$work/log/runs" -name summary.tsv -type f -print -quit)
[[ -n $summary && $(wc -l < "$summary") == 3 ]] || fail '多版本 batch 未生成完整汇总矩阵'
grep -q $'VERSION\tCONFIG\tCAPABILITY\tBOARD\tTEST\tCYCLES\tCOMMITS\tCPI\tIPC\tMIPS\tRESULT' "$summary" ||
  fail '批次汇总字段不完整'
if make -C "$cpu_tests" run ALL=add version="$dpi_version,$pipeline_version" LOG_ROOT="$work/log" >/dev/null 2>&1; then
  fail 'run 接受了多个版本编号'
fi

created=$(value "$dpi/construction.env" CREATED_AT)
"$manager" ensure "$npc_root" NpcDpiConfig 0 0
[[ $(value "$dpi/construction.env" CONSTRUCTION_ID) == "$dpi_id" ]] || fail '复用构造改变了稳定编号'
[[ $(value "$dpi/construction.env" VERSION_INDEX) == "$dpi_version" ]] || fail '复用构造改变了版本序号'
[[ $(value "$dpi/construction.env" CREATED_AT) == "$created" ]] || fail '复用构造改变了首次构造时间'
[[ $(value "$dpi/construction.env" REBUILD_COUNT) == 0 ]] || fail '未请求 rebuild 却发生重构'

before=$(sha256sum "$dpi/construction.env" | cut -d' ' -f1)
if failure=$(CONSTRUCTION_TEST_FAIL=1 "$manager" build "$npc_root" NpcDpiConfig 1 2>&1); then
  fail '模拟失败的重构意外成功'
fi
[[ $failure == *"make -C $npc_root build config=scpu.NpcDpiConfig rebuild=1"* ]] ||
  fail '失败重构未提示可复制的 rebuild=1 命令'
[[ $failure == *'失败原因（关键日志）：'* && $failure == *'按测试请求模拟构造失败'* ]] ||
  fail '失败重构未输出关键日志片段'
[[ $(sha256sum "$dpi/construction.env" | cut -d' ' -f1) == "$before" ]] || fail '失败重构破坏了旧构造'
find "$CONSTRUCTION_TEST_ROOT/.failed" -type f -name 'scpu.NpcDpiConfig-*.log' -print -quit | grep -q . ||
  fail '失败重构没有保存日志'

if "$manager" resolve "$npc_root" NpcDpiConfig "$pipeline_version" >/dev/null 2>&1; then
  fail 'config/version 不一致未被拒绝'
fi
if "$manager" delete "$npc_root" "$pipeline_version" 0 </dev/null >/dev/null 2>&1; then
  fail '非交互删除未要求 yes=1'
fi

u55c="$CONSTRUCTION_TEST_ROOT/scpu.fpga.u55c.U55cYsyxSocFpgaConfig"
if "$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0 >/dev/null 2>&1; then
  fail '缺失 FPGA 构造未要求 build=1'
fi
"$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 1 0
[[ -f $u55c/fpga/artifacts/artifact-manifest.env ]] || fail 'FPGA dry-run 未生成资产清单'
"$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0

platform=$(value "$u55c/profile.env" FPGA_PLATFORM)
printf 'tampered\n' > "$u55c/fpga/artifacts/npc-$platform.xclbin"
if "$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 0 >/dev/null 2>&1; then
  fail '损坏的 FPGA 资产被放行'
fi
"$manager" ensure "$npc_root" U55cYsyxSocFpgaConfig 0 1

"$manager" delete "$npc_root" "$pipeline_version" 1
[[ ! -d $pipeline ]] || fail 'yes=1 未删除构造'
"$manager" build "$npc_root" NpcExternalAxiConfig 0
external="$CONSTRUCTION_TEST_ROOT/scpu.NpcExternalAxiConfig"
[[ $(value "$external/construction.env" VERSION_INDEX) == 4 ]] ||
  fail '删除后新构造复用了已有版本序号'
printf 'Config 构造生命周期回归通过\n'
