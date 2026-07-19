#!/usr/bin/env bash
# Config 驱动的构造库、稳定版本序号和原子更新管理器。
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
用法：construction-manager.sh <命令> <npc-root> [参数]
命令：catalog | resolve <config> <version> | build <config> <rebuild>
      ensure <config> <build> <rebuild> | list [selector] | delete <version> <yes>
EOF
  exit 2
}

[[ $# -ge 2 ]] || usage
command=$1
npc_root=$(realpath "$2")
shift 2
workspace=$(realpath "$npc_root/..")
root=${CONSTRUCTION_TEST_ROOT:-$npc_root/constructions}
mkdir -p "$root"
root=$(realpath "$root")
catalog="$npc_root/chisel/configs/resources/scpu-config-catalog.tsv"
profile_tool="$npc_root/scripts/generate-config-profile.sh"
build_tool="$npc_root/scripts/build-construction.sh"
refresh_simulation_host_tool="$npc_root/scripts/refresh-simulation-host.sh"
artifact_tool="$npc_root/fpga/common/scripts/artifact-manifest.sh"
mkdir -p "$root/.profiles" "$root/.failed"
catalog_ready=${SCPU_CONFIG_CATALOG_READY:-0}
profile_format=2
[[ $catalog_ready == 0 || $catalog_ready == 1 ]] || { echo "SCPU_CONFIG_CATALOG_READY 只能是 0 或 1" >&2; exit 2; }

value() {
  sed -n "s/^${2}=//p" "$1" | tail -n 1
}

failure_excerpt() {
  local log=$1 property
  [[ -s $log ]] || { echo '（构造进程没有写入日志）' >&2; return; }

  # Vivado 经常只在 Tcl 调用栈中留下失败的属性设置；提取它以免根因被栈帧淹没。
  property=$(sed -n 's/^"rdi::set_property \([^ ]*\) \([^ ]*\) \([^" ]*\)".*/\3.\1=\2/p' "$log" | tail -n 1)
  [[ -z $property ]] || echo "Vivado 未接受 IP 属性：$property" >&2
  echo '日志末尾（40 行）：' >&2
  tail -n 40 "$log" >&2
}

require_version_index() {
  [[ ${1:-} =~ ^[1-9][0-9]*$ ]] || { echo "非法版本序号 '${1:-}'：应为从 1 开始的正整数" >&2; exit 2; }
}

construction_environments() {
  while IFS= read -r file; do
    printf '%s\t%s\n' "$(value "$file" CONSTRUCTION_ID)" "$file"
  done < <(find "$root" -mindepth 2 -maxdepth 2 -name construction.env -print) |
    LC_ALL=C sort -t $'\t' -k1,1 -k2,2 |
    cut -f2-
}

write_version_index() {
  local file=$1 version_index=$2 temporary
  temporary=$(mktemp "$file.version-index.XXXXXX")
  awk -v version_index="$version_index" '
    /^VERSION_INDEX=/ { next }
    /^CONSTRUCTION_ID=/ { print; print "VERSION_INDEX=" version_index; next }
    { print }
  ' "$file" > "$temporary"
  mv "$temporary" "$file"
}

strip_legacy_metadata() {
  local file=$1 temporary
  temporary=$(mktemp "$file.metadata.XXXXXX")
  awk -F= '
    $1 == "PROFILE_SHA256" ||
    $1 ~ /_FINGERPRINT$/ ||
    $1 ~ /^(HOST_TRIPLE|CC_PATH|CC_SHA256|CC_VERSION|CXX_PATH|CXX_SHA256|CXX_VERSION|VERILATOR_PATH|VERILATOR_SHA256|VERILATOR_VERSION|SBT_PATH|SBT_SHA256|SBT_VERSION|MILL_PATH|MILL_SHA256|MILL_VERSION)$/ { next }
    { print }
  ' "$file" > "$temporary"
  if cmp -s "$file" "$temporary"; then
    rm -f "$temporary"
  else
    mv "$temporary" "$file"
  fi
}

# VERSION_INDEX 是用户可见、不会因删除其他构造而重排的序号。旧构造没有该字段时，
# 首次访问构造库会按内部时间 ID 的顺序补齐，之后新构造始终取最大值加一。
migrate_version_indexes_locked() {
  local file index next=1
  local -A used=()

  while IFS= read -r file; do
    index=$(value "$file" VERSION_INDEX)
    [[ -z $index ]] && continue
    require_version_index "$index"
    [[ -z ${used[$index]+present} ]] || { echo "版本序号 $index 重复，构造库已损坏" >&2; exit 1; }
    used[$index]=present
  done < <(construction_environments)

  while IFS= read -r file; do
    index=$(value "$file" VERSION_INDEX)
    [[ -n $index ]] && continue
    while [[ -n ${used[$next]+present} ]]; do ((next++)); done
    write_version_index "$file" "$next"
    used[$next]=present
    ((next++))
  done < <(construction_environments)

  # 已保存构造仅保留可读的来源记录；旧版的摘要和工具链探测结果不再参与
  # 任何构造决策，迁移时一并移除，避免误导为仍会自动重构。
  while IFS= read -r file; do
    strip_legacy_metadata "$file"
  done < <(construction_environments)
}

next_version_index() {
  local file index maximum=0
  while IFS= read -r file; do
    index=$(value "$file" VERSION_INDEX)
    require_version_index "$index"
    (( index > maximum )) && maximum=$index
  done < <(construction_environments)
  printf '%s\n' "$((maximum + 1))"
}

ensure_version_indexes() {
  exec 9>"$root/.lock"
  flock 9
  migrate_version_indexes_locked
}

resolve_catalog() {
  local request=$1 resolved
  if [[ $catalog_ready == 0 ]]; then
    "$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
    catalog_ready=1
  fi
  resolved=$("$npc_root/scripts/resolve-config.sh" "$catalog" "$request" 'npc,soc,fpga-npc,fpga-soc')
  [[ $resolved != !* ]] || { echo "${resolved#!}" >&2; exit 2; }
  printf '%s\n' "$resolved"
}

profile_for() {
  local request=$1 refresh=${2:-0} resolved fqcn scope board target output
  resolved=$(resolve_catalog "$request")
  IFS='|' read -r fqcn scope board target <<< "$resolved"
  output="$root/.profiles/$fqcn.env"
  # 缓存只避免运行已保存构造时反复启动 SBT/Mill，不参与失效判断。实际 build
  # 总是强制由 Scala 重写 profile，因而新的硬件 ABI 只能通过 build/rebuild=1 生效。
  if [[ $refresh == 1 || ! -f $output || $(value "$output" CONFIG_FQCN) != "$fqcn" || $(value "$output" PROFILE_FORMAT) != "$profile_format" ]]; then
    SCPU_CONFIG_CATALOG_READY=1 "$profile_tool" "$npc_root" "$fqcn" "$output"
  fi
  printf '%s\n' "$output"
}

construction_by_version() {
  local index=$1 matches=()
  require_version_index "$index"
  while IFS= read -r file; do
    [[ $(value "$file" VERSION_INDEX) == "$index" ]] && matches+=("$(dirname "$file")")
  done < <(construction_environments)
  [[ ${#matches[@]} == 1 ]] || {
    if [[ ${#matches[@]} == 0 ]]; then echo "版本序号 $index 不存在" >&2; else echo "版本序号 $index 重复，构造库已损坏" >&2; fi
    exit 1
  }
  printf '%s\n' "${matches[0]}"
}

verify_assets() {
  local directory=$1 profile construction capability board platform artifacts manifest
  profile="$directory/profile.env"
  construction="$directory/construction.env"
  [[ -f $profile && -f $construction ]] || {
    echo "构造缺少 profile.env 或 construction.env：$directory" >&2; return 1;
  }
  [[ $(value "$profile" CONFIG_FQCN) == "$(value "$construction" CONFIG_FQCN)" ]] || {
    echo "构造的 Config FQCN 记录不一致：$directory" >&2; return 1;
  }
  capability=$(value "$profile" CAPABILITY)
  if [[ $capability != elaborate-only ]]; then
    [[ -x $directory/abi/nemu/nemu-exec ]] || {
      echo "构造缺少可执行 NEMU host：$directory/abi/nemu/nemu-exec" >&2; return 1;
    }
  fi
  [[ $capability == fpga-* ]] || return 0
  artifacts="$directory/fpga/artifacts"
  manifest="$artifacts/artifact-manifest.env"
  [[ -d $artifacts && -f $manifest && -f $artifacts/SHA256SUMS ]] || {
    echo "FPGA 构造缺少完整资产清单：$directory" >&2; return 1;
  }
  board=$(value "$profile" FPGA_BOARD)
  platform=$(value "$profile" FPGA_PLATFORM)
  "$artifact_tool" verify --directory "$artifacts" --board "$board" --platform "${platform:-none}" \
    --config-fqcn "$(value "$profile" CONFIG_FQCN)" \
    --host-abi "$(value "$profile" HOST_ABI)" \
    --protocol-abi "$(value "$profile" PROTOCOL_ABI)"
  [[ $(value "$manifest" FPGA_TYPE) == "$(value "$profile" FPGA_TYPE)" ]] || {
    echo "FPGA manifest 类型与 Config 不匹配" >&2; return 1;
  }
}

next_id() {
  local prefix sequence candidate
  prefix=${CONSTRUCTION_ID_PREFIX:-$(date +%Y%m%d%H%M%S)}
  [[ $prefix =~ ^[0-9]{14}$ ]] || { echo "内部构造编号时间前缀必须是 14 位数字" >&2; exit 2; }
  for sequence in $(seq -w 0 99); do
    candidate="$prefix$sequence"
    if ! rg -q "^CONSTRUCTION_ID=$candidate$" "$root" -g construction.env 2>/dev/null; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  echo "同一秒内已分配 100 个构造编号" >&2
  exit 1
}

do_build_locked() {
  local request=$1 force=$2 resolved scope board target profile fqcn capability final stage old_id version_index created rebuild_count now log backup
  migrate_version_indexes_locked
  resolved=$(resolve_catalog "$request")
  IFS='|' read -r fqcn scope board target <<< "$resolved"
  final="$root/$fqcn"
  if [[ -d $final && $force != 1 ]]; then
    if [[ $scope == fpga-* ]]; then
      verify_assets "$final"
    fi
    echo "复用已保存构造：$fqcn；需要重新生成请添加 rebuild=1。"
    return 0
  fi
  profile=$(profile_for "$fqcn" 1)
  capability=$(value "$profile" CAPABILITY)
  [[ $capability != check-only ]] || { echo "$fqcn 是检查 Config，不能构造" >&2; exit 2; }
  old_id=''
  version_index=''
  created=''
  rebuild_count=0
  if [[ -f $final/construction.env ]]; then
    old_id=$(value "$final/construction.env" CONSTRUCTION_ID)
    version_index=$(value "$final/construction.env" VERSION_INDEX)
    created=$(value "$final/construction.env" CREATED_AT)
    rebuild_count=$(value "$final/construction.env" REBUILD_COUNT)
  fi
  [[ -n $old_id ]] || old_id=$(next_id)
  [[ -n $version_index ]] || version_index=$(next_version_index)
  [[ -n $created ]] || created=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  rebuild_count=$(( ${rebuild_count:-0} + 1 ))
  [[ ! -d $final ]] && rebuild_count=0
  stage="$root/.staging-$fqcn-$$"
  rm -rf "$stage"
  mkdir -p "$stage/logs"
  trap 'rm -rf "$stage"' ERR INT TERM
  cp "$profile" "$stage/profile.env"
  log="$stage/logs/build.log"
  echo "开始构造 $fqcn（临时目录 $stage）"
  if ! "$build_tool" "$workspace" "$stage" "$stage/profile.env" > >(tee "$log") 2> >(tee -a "$log" >&2); then
    failed="$root/.failed/${fqcn}-$(date +%Y%m%d%H%M%S)-$$.log"
    cp "$log" "$failed" 2>/dev/null || true
    rm -rf "$stage"
    echo '构造失败；旧构造未变。' >&2
    echo '失败原因（关键日志）：' >&2
    failure_excerpt "$failed"
    echo "完整日志：$failed" >&2
    echo "需要重试并在成功后原子覆盖时，请执行：make -C $npc_root build config=$fqcn rebuild=1" >&2
    exit 1
  fi
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  {
    echo 'CONSTRUCTION_FORMAT=1'
    echo "CONSTRUCTION_ID=$old_id"
    echo "VERSION_INDEX=$version_index"
    echo "CONFIG_FQCN=$fqcn"
    echo "CAPABILITY=$capability"
    echo "HOST_ABI=$(value "$stage/profile.env" HOST_ABI)"
    echo "PROTOCOL_ABI=$(value "$stage/profile.env" PROTOCOL_ABI)"
    echo "TARGET=$(value "$stage/profile.env" TARGET)"
    echo "XLEN=$(value "$stage/profile.env" XLEN)"
    echo "ISA_STRING=$(value "$stage/profile.env" ISA_STRING)"
    echo "FPGA_BOARD=$(value "$stage/profile.env" FPGA_BOARD)"
    echo "FPGA_PLATFORM=$(value "$stage/profile.env" FPGA_PLATFORM)"
    echo "CREATED_AT=$created"
    echo "UPDATED_AT=$now"
    echo "REBUILD_COUNT=$rebuild_count"
    echo "SOURCE_REV=$(git -C "$workspace" rev-parse HEAD 2>/dev/null || echo unknown)"
  } > "$stage/construction.env"
  verify_assets "$stage"
  backup="$root/.previous-$fqcn-$$"
  if [[ -d $final ]]; then mv "$final" "$backup"; fi
  if mv "$stage" "$final"; then
    rm -rf "$backup"
  else
    [[ -d $backup ]] && mv "$backup" "$final"
    exit 1
  fi
  trap - ERR INT TERM
  echo "已保存构造版本 $version_index：$final"
}

do_build() {
  local request=$1 force=$2
  exec 9>"$root/.lock"
  flock 9
  do_build_locked "$request" "$force"
}

do_resolve() {
  local request=${1:-} version_index=${2:-} directory profile resolved fqcn scope board target saved_fqcn saved_version
  ensure_version_indexes
  if [[ -z $request && -z $version_index ]]; then
    echo "必须提供 config=<Config> 或 version=<版本序号>。可用 Config：" >&2
    "$0" catalog "$npc_root" >&2
    exit 2
  fi
  if [[ -n $version_index ]]; then
    directory=$(construction_by_version "$version_index")
    profile="$directory/profile.env"
    saved_fqcn=$(value "$profile" CONFIG_FQCN)
    saved_version=$(value "$directory/construction.env" VERSION_INDEX)
    if [[ -n $request ]]; then
      resolved=$(resolve_catalog "$request")
      IFS='|' read -r fqcn scope board target <<< "$resolved"
      [[ $fqcn == "$saved_fqcn" ]] || { echo "config=$fqcn 与 version=$version_index（$saved_fqcn）不一致" >&2; exit 2; }
    fi
  else
    resolved=$(resolve_catalog "$request")
    IFS='|' read -r fqcn scope board target <<< "$resolved"
    directory="$root/$fqcn"
    profile=$(profile_for "$fqcn")
    saved_version='-'
    if [[ -f $directory/construction.env ]]; then
      saved_version=$(value "$directory/construction.env" VERSION_INDEX)
      if grep -q '^CAPABILITY=fpga-' "$profile"; then profile="$directory/profile.env"; fi
    fi
  fi
  printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
    "$(value "$profile" CONFIG_FQCN)" "$(value "$profile" CONFIG_SHORT_NAME)" \
    "$(value "$profile" CAPABILITY)" "$(value "$profile" TARGET)" "$(value "$profile" XLEN)" \
    "$(value "$profile" SCOPE)" "$(value "$profile" FPGA_BOARD)" "$saved_version" "$directory" "$profile" |
    awk -F'|' 'BEGIN { OFS="|" } { if ($7 == "") $7="-"; print }'
}

case "$command" in
  catalog)
    [[ $# == 0 ]] || usage
    if [[ $catalog_ready == 0 ]]; then
      "$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
      catalog_ready=1
    fi
    printf '%-34s %-13s %-8s %-5s %s\n' Config Capability Scope XLEN Board
    # profile_for 会启动 SBT/Mill；不能让它们继承目录 TSV 作为 stdin，否则
    # 工具可能读走后续行并使 config-list 只显示第一项。
    exec 8< "$catalog"
    while IFS=$'\t' read -r short fqcn scope board target <&8; do
      [[ -z ${short:-} || $short == \#* ]] && continue
      profile=$(profile_for "$fqcn")
      printf '%-34s %-13s %-8s %-5s %s\n' "$short" "$(value "$profile" CAPABILITY)" "$scope" "$(value "$profile" XLEN)" "$board"
    done
    exec 8<&-
    ;;
  resolve)
    [[ $# == 2 ]] || usage
    do_resolve "$1" "$2"
    ;;
  build)
    [[ $# == 2 ]] || usage
    do_build "$1" "$2"
    ;;
  ensure)
    [[ $# == 3 ]] || usage
    request=$1 allow_build=$2 force=$3
    profile=$(profile_for "$request")
    fqcn=$(value "$profile" CONFIG_FQCN)
    capability=$(value "$profile" CAPABILITY)
    directory="$root/$fqcn"
    if [[ $force == 1 ]]; then do_build "$fqcn" 1
    elif [[ ! -d $directory ]]; then
      if [[ $capability == fpga-* && $allow_build != 1 ]]; then
        echo "FPGA 构造不存在：$fqcn；首次运行请添加 build=1" >&2; exit 1
      fi
      do_build "$fqcn" 0
    elif [[ $capability == fpga-* ]]; then
      verify_assets "$directory"
    else
      verify_assets "$directory"
      if [[ $capability == verilator-* && ${CONSTRUCTION_DRY_RUN:-0} != 1 ]]; then
        "$refresh_simulation_host_tool" "$workspace" "$directory"
      fi
    fi
    ;;
  list)
    [[ $# -le 1 ]] || usage
    selector=${1:-}
    selector_is_version=0
    if [[ -n $selector && $selector =~ ^[0-9]+$ ]]; then
      require_version_index "$selector"
      selector_is_version=1
    fi
    found=0
    ensure_version_indexes
    printf '%-8s %-34s %-13s %-5s %-20s %s\n' Version Config Capability XLEN Updated Directory
    while IFS= read -r env; do
      directory=$(dirname "$env")
      version_index=$(value "$env" VERSION_INDEX)
      fqcn=$(value "$env" CONFIG_FQCN)
      [[ -z $selector || $selector == "$version_index" || $selector == "$fqcn" || $selector == "$(value "$directory/profile.env" CONFIG_SHORT_NAME)" ]] || continue
      found=$((found + 1))
      printf '%-8s %-34s %-13s %-5s %-20s %s\n' "$version_index" "$(value "$directory/profile.env" CONFIG_SHORT_NAME)" \
        "$(value "$env" CAPABILITY)" "$(value "$directory/profile.env" XLEN)" "$(value "$env" UPDATED_AT)" "$directory"
    done < <(construction_environments)
    if [[ $selector_is_version == 1 && $found != 1 ]]; then
      echo "版本序号 $selector 不存在" >&2
      exit 1
    fi
    ;;
  delete)
    [[ $# == 2 ]] || usage
    version_index=$1 assume_yes=$2
    ensure_version_indexes
    directory=$(construction_by_version "$version_index")
    if [[ $assume_yes != 1 ]]; then
      [[ -t 0 ]] || { echo "非交互删除必须传 yes=1" >&2; exit 1; }
      read -r -p "删除构造版本 $version_index（$directory）？输入 Y 确认：" answer
      [[ $answer == Y ]] || { echo "已取消删除" >&2; exit 1; }
    fi
    rm -rf "$directory"
    echo "已删除构造版本 $version_index"
    ;;
  *) usage ;;
esac
