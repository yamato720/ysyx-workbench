#!/usr/bin/env bash
# Config 驱动的构造库、稳定版本序号和原子更新管理器。
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
用法：construction-manager.sh <命令> <npc-root> [参数]
命令：catalog | host-catalog | resolve <config> <version> | build <config> | rebuild <config>
      host-build <config> <all> <jobs> | ensure <config> <build> <host-rebuild>
      list [selector] | delete <version>
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
catalog="$npc_root/chisel/configs/resources/npc-config-catalog.tsv"
profile_tool="$npc_root/scripts/generate-config-profile.sh"
build_tool="$npc_root/scripts/build-construction.sh"
refresh_simulation_host_tool="$npc_root/scripts/refresh-simulation-host.sh"
phase_log_tool="$npc_root/scripts/phase-log.sh"
artifact_tool="$npc_root/fpga/common/scripts/artifact-manifest.sh"
mkdir -p "$root/.profiles" "$root/.failed"
catalog_ready=${NPC_CONFIG_CATALOG_READY:-0}
profile_format=16
profile_inputs_fingerprint_cache=''
[[ $catalog_ready == 0 || $catalog_ready == 1 ]] || { echo "NPC_CONFIG_CATALOG_READY 只能是 0 或 1" >&2; exit 2; }

value() {
  sed -n "s/^${2}=//p" "$1" | tail -n 1
}

feature_mark() {
  [[ ${1:-0} == 1 ]] && printf '+' || printf ''
}

matching_mark() {
  [[ ${1:-} == "$2" ]] && printf '+' || printf ''
}

expected_protocol_abi() {
  case "$1" in
    npc) printf '%s\n' npc-dpi-v1 ;;
    soc) printf '%s\n' ysyx-dpi-v1 ;;
    fpga) printf '%s\n' npc-fpga-runtime-v5 ;;
    *) printf '%s\n' none ;;
  esac
}

# 旧 profile 曾把 Verilator/FPGA 工具名写成能力。现在能力只描述生成、检查或
# 运行，具体工具由 scope 决定。迁移只改可再生描述，不改硬件 ABI 或 FPGA 资产。
normalize_capability() {
  case "$1" in
    elaborate-only) printf '%s\n' generate-only ;;
    verilator|fpga|verilator-npc|verilator-soc|fpga-npc|fpga-soc) printf '%s\n' run ;;
    *) printf '%s\n' "$1" ;;
  esac
}

normalize_scope() {
  case "$1" in
    fpga-npc|fpga-soc) printf '%s\n' fpga ;;
    *) printf '%s\n' "$1" ;;
  esac
}

# 历史 NEMU 配置类只用于迁移；新 profile 记录 companion object 中的稳定 preset。
canonical_nemu_preset() {
  case "$1" in
    LocalBase|LocalPerformance|LocalPipelineTrace|U55cBase|Zcu102Base|Custom|none) printf '%s\n' "$1" ;;
    npc.nemu.DpiConfig|npc.nemu.LocalVerilatorConfig) printf '%s\n' LocalBase ;;
    npc.nemu.LocalVerilatorPerformanceConfig) printf '%s\n' LocalPerformance ;;
    npc.nemu.LocalVerilatorPipelineTraceConfig) printf '%s\n' LocalPipelineTrace ;;
    npc.nemu.U55cConfig) printf '%s\n' U55cBase ;;
    npc.nemu.Zcu102Config) printf '%s\n' Zcu102Base ;;
    '') printf '\n' ;;
    *) printf '%s\n' Custom ;;
  esac
}

# L1 NPC Config 在构造库启用后去掉了冗余的 `Npc` 前缀。构造目录是可长期
# 保留的用户资产，不能因为一次源码重命名而失去引用；这里仅迁移已知的旧终端
# Config 名称。未知名称仍由 catalog 严格拒绝，避免把拼写错误当作历史构造。
canonical_config_fqcn() {
  case "$1" in
    npc.NpcStandaloneConfig) printf '%s\n' npc.StandaloneConfig ;;
    npc.NpcDpiConfig|npc.DpiConfig) printf '%s\n' npc.SimulationConfig ;;
    npc.NpcPipelineDpiConfig|npc.PipelineDpiConfig) printf '%s\n' npc.PipelineSimulationConfig ;;
    npc.NpcFullIsa64NoPipelineDpiConfig|npc.FullIsa64NoPipelineDpiConfig) printf '%s\n' npc.FullIsa64NoPipelineSimulationConfig ;;
    npc.NpcFullIsa64PipelineNoForwardingDpiConfig|npc.FullIsa64PipelineNoForwardingDpiConfig) printf '%s\n' npc.FullIsa64PipelineNoForwardingSimulationConfig ;;
    npc.NpcFullIsa64PipelineDualForwardingDpiConfig|npc.FullIsa64PipelineDualForwardingDpiConfig) printf '%s\n' npc.FullIsa64PipelineDualForwardingSimulationConfig ;;
    npc.NpcFpgaConfig) printf '%s\n' npc.FpgaConfig ;;
    npc.NpcExternalAxiConfig) printf '%s\n' npc.ExternalAxiConfig ;;
    npc.NpcPipelineCheckConfig) printf '%s\n' npc.PipelineCheckConfig ;;
    npc.NpcFloatingCheckConfig) printf '%s\n' npc.FloatingCheckConfig ;;
    npc.NpcMulDivCheckConfig) printf '%s\n' npc.MulDivCheckConfig ;;
    *) printf '%s\n' "$1" ;;
  esac
}

replace_config_metadata() {
  local file=$1 expected_fqcn=$2 canonical_fqcn=$3 canonical_short temporary saved
  [[ -f $file ]] || { echo "构造缺少 Config 元数据：$file" >&2; exit 1; }
  saved=$(value "$file" CONFIG_FQCN)
  [[ $saved == "$expected_fqcn" || $saved == "$canonical_fqcn" ]] || {
    echo "构造 Config 元数据不一致：$file 记录 $saved，预期 $expected_fqcn" >&2
    exit 1
  }
  canonical_short=${canonical_fqcn##*.}
  temporary=$(mktemp "$file.config-migration.XXXXXX")
  awk -v fqcn="$canonical_fqcn" -v short="$canonical_short" '
    /^CONFIG_FQCN=/ { print "CONFIG_FQCN=" fqcn; next }
    /^CONFIG_SHORT_NAME=/ { print "CONFIG_SHORT_NAME=" short; next }
    { print }
  ' "$file" > "$temporary"
  mv "$temporary" "$file"
}

# 此函数必须持有 $root/.lock。目录名、profile 和 construction.env 是同一个
# 不可分割的引用单元，三者一起迁移才能让 version= 和 rebuild 始终命中同一构造。
migrate_config_names_locked() {
  local construction directory profile saved_fqcn profile_fqcn canonical_fqcn target
  while IFS= read -r construction; do
    directory=$(dirname "$construction")
    profile="$directory/profile.env"
    [[ -f $profile ]] || { echo "构造缺少 profile.env：$directory" >&2; exit 1; }
    saved_fqcn=$(value "$construction" CONFIG_FQCN)
    canonical_fqcn=$(canonical_config_fqcn "$saved_fqcn")
    [[ $canonical_fqcn != "$saved_fqcn" ]] || continue
    profile_fqcn=$(value "$profile" CONFIG_FQCN)
    [[ $profile_fqcn == "$saved_fqcn" || $profile_fqcn == "$canonical_fqcn" ]] || {
      echo "构造与 profile 的 Config FQCN 不一致：$directory" >&2
      exit 1
    }
    target="$root/$canonical_fqcn"
    [[ ! -e $target || $target == "$directory" ]] || {
      echo "无法迁移旧 Config $saved_fqcn：目标构造已存在 $target" >&2
      exit 1
    }
    replace_config_metadata "$construction" "$saved_fqcn" "$canonical_fqcn"
    replace_config_metadata "$profile" "$saved_fqcn" "$canonical_fqcn"
    mv "$directory" "$target"
    echo "已迁移保存构造：$saved_fqcn -> $canonical_fqcn" >&2
  done < <(construction_environments)
}

migrate_profile_mode() {
  local file=$1 capability scope board replacement normalized_scope temporary inferred_preset host_backend host_devices
  local saved_host_config saved_preset preset pipeline_html performance_html integer_execute_stages serial_execute_stages register_initial_fetch_request separate_serial_integer_alu serial_execute_result_forwarding divider_non_blocking needs_divider_non_blocking
  [[ -f $file ]] || return 0
  capability=$(value "$file" CAPABILITY)
  scope=$(value "$file" SCOPE)
  board=$(value "$file" FPGA_BOARD)
  replacement=$(normalize_capability "$capability")
  normalized_scope=$(normalize_scope "$scope")
  case "$replacement:$normalized_scope:$board" in
    run:npc:*|run:soc:*) inferred_preset=LocalBase; host_backend=local; host_devices=1 ;;
    run:fpga:u55c) inferred_preset=U55cBase; host_backend=u55c; host_devices=0 ;;
    run:fpga:zcu102) inferred_preset=Zcu102Base; host_backend=zcu102; host_devices=0 ;;
    generate-only:*:*|check-only:*:*) inferred_preset=none; host_backend=none; host_devices=0 ;;
    *) echo "无法为旧 profile 推断 NEMU host：$file（$replacement/$normalized_scope/$board）" >&2; exit 1 ;;
  esac
  saved_host_config=$(value "$file" NEMU_CONFIG_FQCN)
  saved_preset=$(value "$file" NEMU_PRESET)
  if [[ -n $saved_preset ]]; then preset=$(canonical_nemu_preset "$saved_preset")
  elif [[ -n $saved_host_config ]]; then preset=$(canonical_nemu_preset "$saved_host_config")
  else preset=$inferred_preset
  fi
  pipeline_html=$(value "$file" NEMU_PIPELINE_HTML)
  performance_html=$(value "$file" NEMU_PERFORMANCE_HTML)
  integer_execute_stages=$(value "$file" INTEGER_EXECUTE_STAGES)
  [[ -n $integer_execute_stages ]] || integer_execute_stages=1
  [[ $integer_execute_stages == 1 || $integer_execute_stages == 2 ]] || {
    echo "保存 profile 的 INTEGER_EXECUTE_STAGES 非法：$file（$integer_execute_stages）" >&2
    exit 1
  }
  serial_execute_stages=$(value "$file" SERIAL_EXECUTE_STAGES)
  [[ -n $serial_execute_stages ]] || serial_execute_stages=1
  [[ $serial_execute_stages == 1 || $serial_execute_stages == 2 || $serial_execute_stages == 3 ]] || {
    echo "保存 profile 的 SERIAL_EXECUTE_STAGES 非法：$file（$serial_execute_stages）" >&2
    exit 1
  }
  register_initial_fetch_request=$(value "$file" REGISTER_INITIAL_FETCH_REQUEST)
  [[ -n $register_initial_fetch_request ]] || register_initial_fetch_request=0
  [[ $register_initial_fetch_request == 0 || $register_initial_fetch_request == 1 ]] || {
    echo "保存 profile 的 REGISTER_INITIAL_FETCH_REQUEST 非法：$file（$register_initial_fetch_request）" >&2
    exit 1
  }
  separate_serial_integer_alu=$(value "$file" SEPARATE_SERIAL_INTEGER_ALU)
  [[ -n $separate_serial_integer_alu ]] || separate_serial_integer_alu=0
  [[ $separate_serial_integer_alu == 0 || $separate_serial_integer_alu == 1 ]] || {
    echo "保存 profile 的 SEPARATE_SERIAL_INTEGER_ALU 非法：$file（$separate_serial_integer_alu）" >&2
    exit 1
  }
  serial_execute_result_forwarding=$(value "$file" SERIAL_EXECUTE_RESULT_FORWARDING)
  [[ -n $serial_execute_result_forwarding ]] || serial_execute_result_forwarding=1
  [[ $serial_execute_result_forwarding == 0 || $serial_execute_result_forwarding == 1 ]] || {
    echo "保存 profile 的 SERIAL_EXECUTE_RESULT_FORWARDING 非法：$file（$serial_execute_result_forwarding）" >&2
    exit 1
  }
  needs_divider_non_blocking=0
  [[ $normalized_scope == fpga ]] && needs_divider_non_blocking=1
  divider_non_blocking=$(value "$file" FPGA_DIVIDER_NON_BLOCKING)
  [[ -n $divider_non_blocking ]] || divider_non_blocking=0
  [[ $divider_non_blocking == 0 || $divider_non_blocking == 1 ]] || {
    echo "保存 profile 的 FPGA_DIVIDER_NON_BLOCKING 非法：$file（$divider_non_blocking）" >&2
    exit 1
  }
  [[ $pipeline_html == 1 ]] && performance_html=1
  [[ $performance_html == 0 || $performance_html == 1 ]] || performance_html=0
  [[ $replacement != "$capability" || $normalized_scope != "$scope" ||
    $(value "$file" PROFILE_FORMAT) != "$profile_format" || -z $(value "$file" NEMU_PERFORMANCE_HTML) ||
    -z $saved_preset || -n $saved_host_config || -z $(value "$file" INTEGER_EXECUTE_STAGES) ||
    -z $(value "$file" SERIAL_EXECUTE_STAGES) ||
    -z $(value "$file" REGISTER_INITIAL_FETCH_REQUEST) ||
    -z $(value "$file" SEPARATE_SERIAL_INTEGER_ALU) ||
    -z $(value "$file" SERIAL_EXECUTE_RESULT_FORWARDING) ]] ||
    [[ $needs_divider_non_blocking == 0 || -n $(value "$file" FPGA_DIVIDER_NON_BLOCKING) ]] || return 0

  # 已经带完整 NEMU host 字段的保存 profile 只升级格式并补新字段，不能根据
  # backend 重新推断 preset，否则流水线 preset 会被错误降级为普通 local preset。
  if [[ -n $saved_preset || -n $saved_host_config ]]; then
    temporary=$(mktemp "$file.profile-migration.XXXXXX")
    awk -v capability="$replacement" -v scope="$normalized_scope" \
      -v profile_format="$profile_format" -v performance_html="$performance_html" -v preset="$preset" \
      -v integer_execute_stages="$integer_execute_stages" \
      -v serial_execute_stages="$serial_execute_stages" \
      -v register_initial_fetch_request="$register_initial_fetch_request" \
      -v separate_serial_integer_alu="$separate_serial_integer_alu" \
      -v serial_execute_result_forwarding="$serial_execute_result_forwarding" \
      -v divider_non_blocking="$divider_non_blocking" -v needs_divider_non_blocking="$needs_divider_non_blocking" '
      /^PROFILE_FORMAT=/ { print "PROFILE_FORMAT=" profile_format; next }
      /^CAPABILITY=/ { print "CAPABILITY=" capability; next }
      /^SCOPE=/ { print "SCOPE=" scope; next }
      /^NEMU_CONFIG_FQCN=/ { next }
      /^NEMU_PRESET=/ { if (!preset_seen++) print "NEMU_PRESET=" preset; next }
      /^NEMU_PERFORMANCE_HTML=/ { next }
      /^INTEGER_EXECUTE_STAGES=/ { if (!integer_execute_stages_seen++) print "INTEGER_EXECUTE_STAGES=" integer_execute_stages; next }
      /^SERIAL_EXECUTE_STAGES=/ { if (!serial_execute_stages_seen++) print "SERIAL_EXECUTE_STAGES=" serial_execute_stages; next }
      /^REGISTER_INITIAL_FETCH_REQUEST=/ { if (!register_initial_fetch_request_seen++) print "REGISTER_INITIAL_FETCH_REQUEST=" register_initial_fetch_request; next }
      /^SEPARATE_SERIAL_INTEGER_ALU=/ { if (!separate_serial_integer_alu_seen++) print "SEPARATE_SERIAL_INTEGER_ALU=" separate_serial_integer_alu; next }
      /^SERIAL_EXECUTE_RESULT_FORWARDING=/ { if (!serial_execute_result_forwarding_seen++) print "SERIAL_EXECUTE_RESULT_FORWARDING=" serial_execute_result_forwarding; next }
      /^FPGA_DIVIDER_NON_BLOCKING=/ { if (needs_divider_non_blocking && !divider_non_blocking_seen++) print "FPGA_DIVIDER_NON_BLOCKING=" divider_non_blocking; next }
      { print }
      END {
        if (!preset_seen) print "NEMU_PRESET=" preset
        if (!integer_execute_stages_seen) print "INTEGER_EXECUTE_STAGES=" integer_execute_stages
        if (!serial_execute_stages_seen) print "SERIAL_EXECUTE_STAGES=" serial_execute_stages
        if (!register_initial_fetch_request_seen) print "REGISTER_INITIAL_FETCH_REQUEST=" register_initial_fetch_request
        if (!separate_serial_integer_alu_seen) print "SEPARATE_SERIAL_INTEGER_ALU=" separate_serial_integer_alu
        if (!serial_execute_result_forwarding_seen) print "SERIAL_EXECUTE_RESULT_FORWARDING=" serial_execute_result_forwarding
        if (needs_divider_non_blocking && !divider_non_blocking_seen) print "FPGA_DIVIDER_NON_BLOCKING=" divider_non_blocking
        print "NEMU_PERFORMANCE_HTML=" performance_html
      }
    ' "$file" > "$temporary"
    mv "$temporary" "$file"
    return 0
  fi

  temporary=$(mktemp "$file.profile-migration.XXXXXX")
  awk -v capability="$replacement" -v scope="$normalized_scope" -v profile_format="$profile_format" '
    /^PROFILE_FORMAT=/ { print "PROFILE_FORMAT=" profile_format; next }
    /^CAPABILITY=/ { print "CAPABILITY=" capability; next }
    /^SCOPE=/ { print "SCOPE=" scope; next }
    /^INTEGER_EXECUTE_STAGES=/ { next }
    /^SERIAL_EXECUTE_STAGES=/ { next }
    /^REGISTER_INITIAL_FETCH_REQUEST=/ { next }
    /^SEPARATE_SERIAL_INTEGER_ALU=/ { next }
    /^SERIAL_EXECUTE_RESULT_FORWARDING=/ { next }
    /^FPGA_DIVIDER_NON_BLOCKING=/ { next }
    /^NEMU_(CONFIG_FQCN|PRESET|BACKEND|TRACE|WATCHPOINT|VCD|PERFORMANCE_HTML|PIPELINE_HTML|NPC_DIFFTEST|DEVICES|OPTIMIZATION|DEBUG|LTO|ASAN)=/ { next }
    { print }
  ' "$file" > "$temporary"
  {
    echo "NEMU_PRESET=$preset"
    echo "NEMU_BACKEND=$host_backend"
    echo 'NEMU_TRACE=0'
    echo 'NEMU_WATCHPOINT=1'
    echo 'NEMU_VCD=0'
    echo 'NEMU_PERFORMANCE_HTML=0'
    echo 'NEMU_PIPELINE_HTML=0'
    echo 'NEMU_NPC_DIFFTEST=0'
    echo "NEMU_DEVICES=$host_devices"
    echo 'NEMU_OPTIMIZATION=O2'
    echo 'NEMU_DEBUG=0'
    echo 'NEMU_LTO=0'
    echo 'NEMU_ASAN=0'
    echo "INTEGER_EXECUTE_STAGES=$integer_execute_stages"
    echo "SERIAL_EXECUTE_STAGES=$serial_execute_stages"
    echo "REGISTER_INITIAL_FETCH_REQUEST=$register_initial_fetch_request"
    echo "SEPARATE_SERIAL_INTEGER_ALU=$separate_serial_integer_alu"
    echo "SERIAL_EXECUTE_RESULT_FORWARDING=$serial_execute_result_forwarding"
    if [[ $needs_divider_non_blocking == 1 ]]; then echo "FPGA_DIVIDER_NON_BLOCKING=$divider_non_blocking"; fi
  } >> "$temporary"
  mv "$temporary" "$file"
}

migrate_construction_mode() {
  local file=$1 capability replacement nemu_config preset canonical_preset profile_preset temporary
  [[ -f $file ]] || return 0
  capability=$(value "$file" CAPABILITY)
  replacement=$(normalize_capability "$capability")
  nemu_config=$(value "$file" NEMU_CONFIG_FQCN)
  preset=$(value "$file" NEMU_PRESET)
  profile_preset=$(value "$(dirname "$file")/profile.env" NEMU_PRESET)
  if [[ -n $preset ]]; then canonical_preset=$(canonical_nemu_preset "$preset")
  elif [[ -n $nemu_config ]]; then canonical_preset=$(canonical_nemu_preset "$nemu_config")
  else canonical_preset=$profile_preset
  fi
  [[ $replacement == "$capability" && -z $nemu_config && $canonical_preset == "$preset" ]] && return 0
  temporary=$(mktemp "$file.mode-migration.XXXXXX")
  awk -v capability="$replacement" -v preset="$canonical_preset" '
    /^CAPABILITY=/ { print "CAPABILITY=" capability; next }
    /^NEMU_CONFIG_FQCN=/ { next }
    /^NEMU_PRESET=/ { if (!preset_seen++) print "NEMU_PRESET=" preset; next }
    { print }
    END { if (!preset_seen && preset != "") print "NEMU_PRESET=" preset }
  ' "$file" > "$temporary"
  mv "$temporary" "$file"
}

migrate_host_metadata() {
  local construction=$1 host current preset canonical format pipeline_html performance_html temporary
  host="$(dirname "$construction")/abi/nemu/host.env"
  [[ -f $host ]] || return 0
  current=$(value "$host" NEMU_CONFIG_FQCN)
  preset=$(value "$host" NEMU_PRESET)
  if [[ -n $preset ]]; then canonical=$(canonical_nemu_preset "$preset")
  elif [[ -n $current ]]; then canonical=$(canonical_nemu_preset "$current")
  else canonical=$(value "$(dirname "$(dirname "$(dirname "$host")")")/profile.env" NEMU_PRESET)
  fi
  format=$(value "$host" HOST_FORMAT)
  pipeline_html=$(value "$host" NEMU_PIPELINE_HTML)
  performance_html=$(value "$host" NEMU_PERFORMANCE_HTML)
  [[ $pipeline_html == 1 ]] && performance_html=1
  [[ $performance_html == 0 || $performance_html == 1 ]] || performance_html=0
  [[ -n $current || $canonical != "$preset" || $format != 5 || -z $pipeline_html ||
    -z $(value "$host" NEMU_PERFORMANCE_HTML) ]] || return 0
  temporary=$(mktemp "$host.config-migration.XXXXXX")
  awk -v preset="$canonical" -v performance_html="$performance_html" '
    /^HOST_FORMAT=/ { print "HOST_FORMAT=5"; next }
    /^NEMU_CONFIG_FQCN=/ { next }
    /^NEMU_PRESET=/ { if (!preset_seen++) print "NEMU_PRESET=" preset; next }
    /^NEMU_PERFORMANCE_HTML=/ { next }
    { print }
    END {
      if (!preset_seen) print "NEMU_PRESET=" preset
      print "NEMU_PERFORMANCE_HTML=" performance_html
    }
  ' "$host" > "$temporary"
  mv "$temporary" "$host"
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

copy_failure_file() {
  local stage=$1 failed_dir=$2 source=$3 relative
  [[ -f $source ]] || return 0
  relative=${source#"$stage/"}
  mkdir -p "$failed_dir/$(dirname "$relative")"
  cp -a "$source" "$failed_dir/$relative"
}

preserve_fpga_failure_evidence() {
  local stage=$1 failed_dir=$2 directory source report_dir
  [[ -d $stage/fpga ]] || return 0
  copy_failure_file "$stage" "$failed_dir" "$stage/profile.env"

  for directory in "$stage/fpga/ip-generated/logs" "$stage/fpga/vitis-logs" "$stage/fpga/vitis-reports"; do
    [[ -d $directory ]] || continue
    mkdir -p "$failed_dir/${directory#"$stage/"}"
    cp -a "$directory/." "$failed_dir/${directory#"$stage/"}/"
  done

  for directory in "$stage/fpga/synth" "$stage/fpga/vitis-temp"; do
    [[ -d $directory ]] || continue
    while IFS= read -r -d '' report_dir; do
      mkdir -p "$failed_dir/${report_dir#"$stage/"}"
      cp -a "$report_dir/." "$failed_dir/${report_dir#"$stage/"}/"
    done < <(find "$directory" -type d -name npc-implementation-reports -print0)
    while IFS= read -r -d '' source; do
      copy_failure_file "$stage" "$failed_dir" "$source"
    done < <(find "$directory" -type f \( -name runme.log -o -name vivado.log -o -name '*timing_summary*.rpt' \) -print0)
  done
}

require_version_index() {
  [[ ${1:-} =~ ^[1-9][0-9]*$ ]] || { echo "非法版本序号 '${1:-}'：应为从 1 开始的正整数" >&2; exit 2; }
}

construction_environments() {
  local directory
  while IFS= read -r file; do
    directory=$(dirname "$file")
    construction_is_complete "$directory" || continue
    printf '%s\t%s\n' "$(value "$file" CONSTRUCTION_ID)" "$file"
  done < <(find "$root" -mindepth 2 -maxdepth 2 -name construction.env -print) |
    LC_ALL=C sort -t $'\t' -k1,1 -k2,2 |
    cut -f2-
}

# 版本索引文件是 make version 的唯一数据源。这里保留 construction.env 的扫描，
# 仅用于把旧保存构造迁移到该格式；它不参与正常的列表和选择。
final_construction_environments() {
  local directory
  while IFS= read -r file; do
    directory=$(dirname "$file")
    case "$(basename "$directory")" in
      .*|staging-*|.previous-*) continue ;;
    esac
    [[ -f $directory/profile.env ]] || continue
    printf '%s\t%s\n' "$(value "$file" CONSTRUCTION_ID)" "$file"
  done < <(find "$root" -mindepth 2 -maxdepth 2 -name construction.env -print) |
    LC_ALL=C sort -t $'\t' -k1,1 -k2,2 |
    cut -f2-
}

construction_is_complete() {
  local directory=$1 profile scope board platform artifact host_abi
  case "$(basename "$directory")" in
    .staging-*|staging-*|.previous-*) return 1 ;;
  esac
  [[ -f $directory/construction.env && -f $directory/profile.env && ! -e $directory/.incomplete ]] || return 1
  if [[ -f $directory/.complete ]]; then
    [[ $(value "$directory/.complete" CONSTRUCTION_COMPLETE) == 1 ]] || return 1
  fi

  profile="$directory/profile.env"
  host_abi=$(value "$profile" HOST_ABI)
  [[ $host_abi == none || -x $directory/abi/nemu/nemu-exec ]] || return 1
  scope=$(value "$profile" SCOPE)
  [[ $scope == fpga ]] || return 0
  board=$(value "$profile" FPGA_BOARD)
  platform=$(value "$profile" FPGA_PLATFORM)
  case "$board" in
    u55c) artifact="$directory/fpga/artifacts/npc-$platform.xclbin" ;;
    zcu102) artifact="$directory/fpga/artifacts/npc.bit" ;;
    *) return 1 ;;
  esac
  [[ -s $artifact ]]
}

mark_construction_complete() {
  local directory=$1 profile scope board platform artifact='-'
  profile="$directory/profile.env"
  scope=$(value "$profile" SCOPE)
  if [[ $scope == fpga ]]; then
    board=$(value "$profile" FPGA_BOARD)
    platform=$(value "$profile" FPGA_PLATFORM)
    case "$board" in
      u55c) artifact="fpga/artifacts/npc-$platform.xclbin" ;;
      zcu102) artifact='fpga/artifacts/npc.bit' ;;
      *) echo "无法标记未知 FPGA 板卡构造完成：$board" >&2; return 1 ;;
    esac
    [[ -s $directory/$artifact ]] || {
      echo "FPGA 构造缺少最终比特流：$directory/$artifact" >&2
      return 1
    }
  fi
  {
    echo 'CONSTRUCTION_COMPLETE=1'
    echo "CONFIG_FQCN=$(value "$profile" CONFIG_FQCN)"
    echo "SCOPE=$scope"
    echo "FPGA_ARTIFACT=$artifact"
    echo "COMPLETED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$directory/.complete"
  rm -f "$directory/.incomplete"
}

version_tag_file() {
  printf '%s/version.tag\n' "$1"
}

version_info_file() {
  printf '%s/version.info\n' "$1"
}

version_index_from_tag() {
  local index
  index=$(value "$(version_tag_file "$1")" VERSION_INDEX)
  require_version_index "$index"
  printf '%s\n' "$index"
}

version_state_from_tag() {
  local state
  state=$(value "$(version_tag_file "$1")" STATE)
  [[ $state == building || $state == complete || $state == failed || $state == interrupted ]] || {
    echo "版本标签状态非法：$1（$state）" >&2
    exit 1
  }
  printf '%s\n' "$state"
}

write_version_tag() {
  local directory=$1 version_index=$2 state=$3 file temporary
  require_version_index "$version_index"
  [[ $state == building || $state == complete || $state == failed || $state == interrupted ]] || {
    echo "无法写入未知版本标签状态：$state" >&2
    exit 2
  }
  file=$(version_tag_file "$directory")
  temporary=$(mktemp "$directory/.version.tag.XXXXXX")
  {
    echo 'VERSION_TAG_FORMAT=1'
    echo "VERSION_INDEX=$version_index"
    echo "STATE=$state"
  } > "$temporary"
  mv "$temporary" "$file"
}

write_version_info() {
  local directory=$1 profile=$2 version_index=$3 temporary fqcn short target scope arch running_time xlen rv32=0 rv64=0
  require_version_index "$version_index"
  fqcn=$(value "$profile" CONFIG_FQCN)
  [[ -n $fqcn ]] || { echo "构造缺少 CONFIG_FQCN：$profile" >&2; exit 1; }
  short=$(value "$profile" CONFIG_SHORT_NAME)
  [[ -n $short ]] || short=${fqcn##*.}
  target=$(value "$profile" TARGET)
  scope=$(value "$profile" SCOPE)
  case "$target" in
    NPC) arch=NPC ;;
    SOC) arch=SoC ;;
    *) echo "构造 TARGET 非法：$profile（$target）" >&2; exit 1 ;;
  esac
  case "$scope" in
    fpga) running_time=FPGA ;;
    npc|soc) running_time=SIM ;;
    *) echo "构造 SCOPE 非法：$profile（$scope）" >&2; exit 1 ;;
  esac
  xlen=$(value "$profile" XLEN)
  [[ $xlen == 32 ]] && rv32=1
  [[ $xlen == 64 ]] && rv64=1
  temporary=$(mktemp "$directory/.version.info.XXXXXX")
  {
    echo 'VERSION_INFO_FORMAT=1'
    echo "VERSION_INDEX=$version_index"
    echo "CONFIG_FQCN=$fqcn"
    echo "CONFIG_SHORT_NAME=$short"
    echo "RV32=$rv32"
    echo "RV64=$rv64"
    echo "M=$(value "$profile" M)"
    echo "F=$(value "$profile" F)"
    echo "ZICSR=$(value "$profile" ZICSR)"
    echo "PIPE=$(value "$profile" PIPELINE)"
    echo "ID=$(value "$profile" ID_FWD)"
    echo "EX=$(value "$profile" EX_FWD)"
    echo "ARCH=$arch"
    echo "RUNNING_TIME=$running_time"
  } > "$temporary"
  mv "$temporary" "$(version_info_file "$directory")"
}

write_pending_version_info() {
  local directory=$1 fqcn=$2 scope=$3 target=$4 version_index=$5 temporary short arch running_time
  require_version_index "$version_index"
  short=${fqcn##*.}
  case "$target" in
    NPC) arch=NPC ;;
    SOC) arch=SoC ;;
    *) echo "构造 TARGET 非法：$fqcn（$target）" >&2; exit 1 ;;
  esac
  case "$scope" in
    fpga) running_time=FPGA ;;
    npc|soc) running_time=SIM ;;
    *) echo "构造 SCOPE 非法：$fqcn（$scope）" >&2; exit 1 ;;
  esac
  temporary=$(mktemp "$directory/.version.info.XXXXXX")
  {
    echo 'VERSION_INFO_FORMAT=1'
    echo "VERSION_INDEX=$version_index"
    echo "CONFIG_FQCN=$fqcn"
    echo "CONFIG_SHORT_NAME=$short"
    echo 'RV32=0'
    echo 'RV64=0'
    echo 'M=0'
    echo 'F=0'
    echo 'ZICSR=0'
    echo 'PIPE=0'
    echo 'ID=0'
    echo 'EX=0'
    echo "ARCH=$arch"
    echo "RUNNING_TIME=$running_time"
  } > "$temporary"
  mv "$temporary" "$(version_info_file "$directory")"
}

prepare_stable_construction_directory() {
  local directory=$1 fqcn=$2
  rm -rf "$directory/abi" "$directory/fpga" "$directory/logs" "$directory/runtime"
  rm -f "$directory/profile.env" "$directory/construction.env" "$directory/.complete"
  mkdir -p "$directory/logs/build"
  {
    echo 'CONSTRUCTION_INCOMPLETE=1'
    echo "CONFIG_FQCN=$fqcn"
    echo "STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$directory/.incomplete"
}

migrate_legacy_staging_locked() {
  local directory profile fqcn target index
  while IFS= read -r directory; do
    profile="$directory/profile.env"
    [[ -f $profile ]] || continue
    fqcn=$(value "$profile" CONFIG_FQCN)
    target="$root/$fqcn"
    [[ -n $fqcn && ! -e $target ]] || continue
    index=$(next_version_index)
    mv "$directory" "$target"
    [[ -f $target/.incomplete ]] || {
      {
        echo 'CONSTRUCTION_INCOMPLETE=1'
        echo "CONFIG_FQCN=$fqcn"
        echo "STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      } > "$target/.incomplete"
    }
    write_version_info "$target" "$target/profile.env" "$index"
    write_version_tag "$target" "$index" interrupted
    echo "已迁移中断构造到稳定目录：$fqcn" >&2
  done < <(find "$root" -mindepth 1 -maxdepth 1 -type d \( -name '.staging-*' -o -name 'staging-*' \) -print | LC_ALL=C sort)
}

write_version_info_index() {
  local directory=$1 version_index=$2 file temporary
  require_version_index "$version_index"
  file=$(version_info_file "$directory")
  temporary=$(mktemp "$directory/.version.info-index.XXXXXX")
  awk -v version_index="$version_index" '
    /^VERSION_INDEX=/ { print "VERSION_INDEX=" version_index; seen = 1; next }
    { print }
    END { if (!seen) print "VERSION_INDEX=" version_index }
  ' "$file" > "$temporary"
  mv "$temporary" "$file"
}

version_directory_is_final() {
  local directory=$1
  [[ $(dirname "$directory") == "$root" ]] || return 1
  case "$(basename "$directory")" in
    .*|staging-*|.previous-*) return 1 ;;
  esac
  return 0
}

version_metadata_directories() {
  local tag directory
  while IFS= read -r tag; do
    directory=$(dirname "$tag")
    [[ -f $(version_info_file "$directory") ]] || {
      echo "构造版本标签缺少信息文件：$directory" >&2
      exit 1
    }
    if version_directory_is_final "$directory"; then
      printf '%s\n' "$directory"
    fi
  done < <(find "$root" -mindepth 2 -maxdepth 2 -name version.tag -type f -print | LC_ALL=C sort)
}

version_directory_is_valid() {
  local directory=$1
  version_directory_is_final "$directory" || return 1
  [[ $(version_state_from_tag "$directory") == complete ]] || return 1
  construction_is_complete "$directory"
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

# VERSION_INDEX 是用户可见的紧凑序号。重构保持原序号；删除后会按当前版本顺序
# 压缩后续序号。旧构造没有标签时，首次访问构造库会按内部时间 ID 的顺序补齐。
migrate_version_indexes_locked() {
  local file index next=1
  local -A used=()

  migrate_config_names_locked

  while IFS= read -r file; do
    index=$(value "$file" VERSION_INDEX)
    [[ -z $index ]] && continue
    require_version_index "$index"
    [[ -z ${used[$index]+present} ]] || { echo "版本序号 $index 重复，构造库已损坏" >&2; exit 1; }
    used[$index]=present
  done < <(final_construction_environments)

  while IFS= read -r file; do
    index=$(value "$file" VERSION_INDEX)
    [[ -n $index ]] && continue
    while [[ -n ${used[$next]+present} ]]; do ((next++)); done
    write_version_index "$file" "$next"
    used[$next]=present
    ((next++))
  done < <(final_construction_environments)

  # 已保存构造仅保留可读的来源记录；旧版的摘要和工具链探测结果不再参与
  # 任何构造决策，迁移时一并移除，避免误导为仍会自动重构。
  while IFS= read -r file; do
    strip_legacy_metadata "$file"
    migrate_profile_mode "$(dirname "$file")/profile.env"
    migrate_construction_mode "$file"
    migrate_host_metadata "$file"
  done < <(final_construction_environments)

  # version.tag/version.info 是版本浏览的完整快照；从旧 metadata 迁移时绝不
  # 查询 Scala catalog。无 .incomplete 的历史正式目录按完成状态导入，当前资产
  # 缺失会由 valid? 的轻量校验显示为无效，而不会从列表消失。
  while IFS= read -r file; do
    local directory state
    directory=$(dirname "$file")
    index=$(value "$file" VERSION_INDEX)
    state=complete
    [[ -e $directory/.incomplete ]] && state=building
    write_version_info "$directory" "$directory/profile.env" "$index"
    write_version_tag "$directory" "$index" "$state"
  done < <(final_construction_environments)

  migrate_legacy_staging_locked
}

next_version_index() {
  local directory index maximum=0
  while IFS= read -r directory; do
    index=$(version_index_from_tag "$directory")
    require_version_index "$index"
    (( index > maximum )) && maximum=$index
  done < <(version_metadata_directories)
  printf '%s\n' "$((maximum + 1))"
}

versioned_final_directories() {
  local directory index
  while IFS= read -r directory; do
    version_directory_is_final "$directory" || continue
    index=$(version_index_from_tag "$directory")
    require_version_index "$index"
    printf '%020d\t%s\n' "$index" "$directory"
  done < <(version_metadata_directories) |
    LC_ALL=C sort -t $'\t' -k1,1n -k2,2 |
    cut -f2-
}

reindex_version_indexes_locked() {
  local directory index state next=1
  while IFS= read -r directory; do
    index=$(version_index_from_tag "$directory")
    if [[ $index != "$next" ]]; then
      state=$(version_state_from_tag "$directory")
      write_version_info_index "$directory" "$next"
      write_version_tag "$directory" "$next" "$state"
      [[ ! -f $directory/construction.env ]] || write_version_index "$directory/construction.env" "$next"
    fi
    ((next++))
  done < <(versioned_final_directories)
}

ensure_version_indexes() {
  exec 9>"$root/.lock"
  flock 9
  migrate_version_indexes_locked
}

# `build` 持有全局锁的时间覆盖完整 Chisel/Verilator/Vivado 流程。version 只读取
# 最终 FQCN 目录的 tag/info，因此构造从创建到结束始终使用同一个可见目录名。
# 只有旧正式构造还没有索引文件时才等待锁并执行一次文件迁移。
published_indexes_complete() {
  local file directory index tag_index
  local -A seen=()
  while IFS= read -r file; do
    directory=$(dirname "$file")
    [[ -f $(version_tag_file "$directory") && -f $(version_info_file "$directory") ]] || return 1
    tag_index=$(value "$(version_tag_file "$directory")" VERSION_INDEX)
    index=$(value "$file" VERSION_INDEX)
    [[ $index =~ ^[1-9][0-9]*$ && $index == "$tag_index" ]] || return 1
    [[ -z ${seen[$index]+present} ]] || return 1
    seen[$index]=1
  done < <(final_construction_environments)
  return 0
}

ensure_version_indexes_for_read() {
  local lock_fd
  exec {lock_fd}>"$root/.lock"
  if flock -n "$lock_fd"; then
    migrate_version_indexes_locked
  elif ! published_indexes_complete; then
    flock "$lock_fd"
    migrate_version_indexes_locked
  fi
  exec {lock_fd}>&-
}

refresh_catalog_if_stale() {
  [[ $catalog_ready == 1 ]] && return 0
  if [[ ! -f $catalog ]] || find "$npc_root/chisel/configs" -name '*.scala' -newer "$catalog" -print -quit | grep -q .; then
    "$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
  fi
  catalog_ready=1
}

resolve_catalog() {
  local request=$1 resolved
  if [[ $catalog_ready == 0 ]]; then
    "$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
    catalog_ready=1
  fi
  resolved=$("$npc_root/scripts/resolve-config.sh" "$catalog" "$request" 'npc,soc,fpga')
  [[ $resolved != !* ]] || { echo "${resolved#!}" >&2; exit 2; }
  printf '%s\n' "$resolved"
}

# FQCN 是构造目录和 metadata 的稳定内部标识；公开 Make 命令始终使用 catalog
# 注册的短名，避免把包路径泄漏为用户需要复制的参数。
config_short_name() {
  local fqcn=$1 short_name class_name scope board target extra matched=''
  while IFS=$'\t' read -r short_name class_name scope board target extra; do
    [[ -z ${short_name:-} || $short_name == \#* ]] && continue
    [[ -z ${extra:-} ]] || {
      echo "配置目录格式错误：$catalog" >&2
      return 1
    }
    [[ $class_name == "$fqcn" ]] || continue
    [[ -z $matched ]] || {
      echo "配置目录中存在重复完整类名：$fqcn" >&2
      return 1
    }
    matched=$short_name
  done < "$catalog"
  [[ -n $matched ]] || {
    echo "配置目录中缺少完整类名：$fqcn" >&2
    return 1
  }
  printf '%s\n' "$matched"
}

# profile 是当前源码 Config 的可再生描述缓存，不是已保存构造的一部分。缓存命中
# 必须同时绑定生成它的 Scala 输入；否则 Config 已调频而 `resolve` 仍返回旧频率，
# 直到下一次 build 才会暴露漂移。一个 manager 进程内可复用同一指纹。
profile_inputs_fingerprint() {
  if [[ -n $profile_inputs_fingerprint_cache ]]; then
    return
  fi

  profile_inputs_fingerprint_cache=$(
    cd "$npc_root"
    {
      for input in scripts/generate-config-profile.sh build.sbt chisel/ysyxSoC/build.sc; do
        [[ -f $input ]] && printf '%s\0' "$input"
      done
      find chisel/configs fpga/common/scala fpga/u55c/scala fpga/zcu102/scala \
        chisel/rv-core/main/scala chisel/ysyxSoC/src \
        -type f -name '*.scala' -print0
    } | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}'
  )
  [[ -n $profile_inputs_fingerprint_cache ]] || {
    echo '无法计算 Config profile 输入指纹' >&2
    exit 1
  }
}

write_profile_inputs_fingerprint() {
  local file=$1 fingerprint=$2 temporary
  temporary=$(mktemp "$file.tmp.XXXXXX")
  printf '%s\n' "$fingerprint" > "$temporary"
  mv "$temporary" "$file"
}

profile_for() {
  local request=$1 refresh=${2:-0} resolved fqcn scope board target output protocol_abi inputs_fingerprint inputs_file cached_fingerprint
  resolved=$(resolve_catalog "$request")
  IFS='|' read -r fqcn scope board target <<< "$resolved"
  output="$root/.profiles/$fqcn.env"
  inputs_file="$output.inputs.sha256"
  profile_inputs_fingerprint
  inputs_fingerprint=$profile_inputs_fingerprint_cache
  cached_fingerprint=$(cat "$inputs_file" 2>/dev/null || true)
  protocol_abi=$(expected_protocol_abi "$scope")
  migrate_profile_mode "$output"
  # 缓存只避免运行已保存构造时反复启动 SBT/Mill，不参与硬件失效判断。实际
  # 硬件 ABI 仍只能通过公开的 make rebuild 更新。
  # 自动目录只包含挂载一个 terminal 层 trait 的完整终端，这些 trait 已组合 NEMU
  # 运行行为、scope 和 target。旧缓存里的 generate-only/check-only profile
  # 不能继续代表同名终端，必须从当前 Scala Config 重建。
  if [[ $refresh == 1 || ! -f $output || $cached_fingerprint != "$inputs_fingerprint" || $(value "$output" CONFIG_FQCN) != "$fqcn" || $(value "$output" PROFILE_FORMAT) != "$profile_format" || $(value "$output" PROTOCOL_ABI) != "$protocol_abi" || $(value "$output" CAPABILITY) != run ]]; then
    NPC_CONFIG_CATALOG_READY=1 "$profile_tool" "$npc_root" "$fqcn" "$output"
    write_profile_inputs_fingerprint "$inputs_file" "$inputs_fingerprint"
  fi
  printf '%s\n' "$output"
}

construction_by_version() {
  local directory
  directory=$(final_construction_by_version "$1")
  version_directory_is_valid "$directory" || {
    echo "版本序号 $1 当前不可运行" >&2
    exit 1
  }
  printf '%s\n' "$directory"
}

final_construction_by_version() {
  local index=$1 directory matches=()
  require_version_index "$index"
  while IFS= read -r directory; do
    version_directory_is_final "$directory" || continue
    [[ $(version_index_from_tag "$directory") == "$index" ]] && matches+=("$directory")
  done < <(version_metadata_directories)
  [[ ${#matches[@]} == 1 ]] || {
    if [[ ${#matches[@]} == 0 ]]; then echo "版本序号 $index 不存在" >&2; else echo "版本序号 $index 重复，构造库已损坏" >&2; fi
    exit 1
  }
  printf '%s\n' "${matches[0]}"
}

verify_assets() {
  local directory=$1 profile construction scope host_abi board platform artifacts manifest host
  profile="$directory/profile.env"
  construction="$directory/construction.env"
  [[ -f $profile && -f $construction ]] || {
    echo "构造缺少 profile.env 或 construction.env：$directory" >&2; return 1;
  }
  [[ $(value "$profile" CONFIG_FQCN) == "$(value "$construction" CONFIG_FQCN)" ]] || {
    echo "构造的 Config FQCN 记录不一致：$directory" >&2; return 1;
  }
  scope=$(value "$profile" SCOPE)
  host_abi=$(value "$profile" HOST_ABI)
  if [[ $host_abi != none ]]; then
    [[ -x $directory/abi/nemu/nemu-exec ]] || {
      echo "构造缺少可执行 NEMU host：$directory/abi/nemu/nemu-exec" >&2; return 1;
    }
    host="$directory/abi/nemu/host.env"
    if [[ -f $host ]]; then
      for key in CONFIG_FQCN NEMU_PRESET NEMU_BACKEND NEMU_TRACE NEMU_WATCHPOINT NEMU_VCD NEMU_PERFORMANCE_HTML NEMU_PIPELINE_HTML \
        NEMU_NPC_DIFFTEST NEMU_DEVICES NEMU_OPTIMIZATION NEMU_DEBUG NEMU_LTO NEMU_ASAN; do
        [[ $(value "$host" "$key") == $(value "$profile" "$key") ]] || {
          echo "构造 NEMU host 元数据与 profile 不匹配：$directory（$key）" >&2; return 1;
        }
      done
    fi
  fi
  [[ $scope == fpga ]] || return 0
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

# 使用当前终端重新生成的 profile 只替换保存 profile 的 NEMU 段。硬件、FPGA
# 工具链与协议字段继续冻结，必须通过公开的 make rebuild 才能更新。
write_host_refreshed_profile() {
  local current=$1 saved=$2 output=$3
  [[ $(value "$current" CONFIG_FQCN) == $(value "$saved" CONFIG_FQCN) ]] || {
    echo "host-build 当前 Config 与保存 profile 不一致" >&2
    return 1
  }
  awk -F= '
    NR == FNR {
      if ($1 ~ /^NEMU_/) current[++count] = $0
      next
    }
    $1 ~ /^NEMU_/ {
      if (!emitted++) for (i = 1; i <= count; i++) print current[i]
      next
    }
    { print }
    END {
      if (!emitted) for (i = 1; i <= count; i++) print current[i]
    }
  ' "$current" "$saved" > "$output"
}

write_host_refreshed_construction() {
  local saved=$1 profile=$2 output=$3
  awk -v preset="$(value "$profile" NEMU_PRESET)" -v backend="$(value "$profile" NEMU_BACKEND)" '
    /^NEMU_CONFIG_FQCN=/ { next }
    /^NEMU_PRESET=/ { if (!preset_seen++) print "NEMU_PRESET=" preset; next }
    /^NEMU_BACKEND=/ { if (!backend_seen++) print "NEMU_BACKEND=" backend; next }
    { print }
    END {
      if (!preset_seen) print "NEMU_PRESET=" preset
      if (!backend_seen) print "NEMU_BACKEND=" backend
    }
  ' "$saved" > "$output"
}

next_id() {
  local prefix sequence candidate
  prefix=${CONSTRUCTION_ID_PREFIX:-$(date +%Y%m%d%H%M%S)}
  [[ $prefix =~ ^[0-9]{14}$ ]] || { echo "内部构造编号时间前缀必须是 14 位数字" >&2; exit 2; }
  for sequence in $(seq -w 0 99); do
    candidate="$prefix$sequence"
    if ! grep -R -q --include=construction.env "^CONSTRUCTION_ID=$candidate$" "$root" 2>/dev/null; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  echo "同一秒内已分配 100 个构造编号" >&2
  exit 1
}

do_build_locked() {
  local request=$1 force=$2 resolved scope board target profile fqcn retry_config capability final stage old_id version_index created rebuild_count now log failed_dir
  migrate_version_indexes_locked
  resolved=$(resolve_catalog "$request")
  IFS='|' read -r fqcn scope board target <<< "$resolved"
  final="$root/$fqcn"
  if [[ -d $final && $force != 1 ]] && version_directory_is_valid "$final"; then
    if [[ $scope == fpga ]]; then
      verify_assets "$final"
    fi
    echo "构造已有效：$fqcn；make build 只允许创建或修复无效构造。需要重新生成请执行 make -C $npc_root rebuild config=$(config_short_name "$fqcn")。" >&2
    return 1
  fi
  old_id=''
  version_index=''
  created=''
  rebuild_count=0
  if [[ -f $final/construction.env ]]; then
    old_id=$(value "$final/construction.env" CONSTRUCTION_ID)
    created=$(value "$final/construction.env" CREATED_AT)
    rebuild_count=$(value "$final/construction.env" REBUILD_COUNT)
  fi
  if [[ -f $(version_tag_file "$final") ]]; then
    version_index=$(version_index_from_tag "$final")
  fi
  [[ -n $old_id ]] || old_id=$(next_id)
  [[ -n $version_index ]] || version_index=$(next_version_index)
  [[ -n $created ]] || created=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  rebuild_count=$(( ${rebuild_count:-0} + 1 ))
  [[ -f $final/construction.env ]] || rebuild_count=0

  # FQCN 目录是可视化工具的稳定入口。build 与 rebuild 都直接在这里工作，
  # 状态只由 version.tag 表示；不会创建、隐藏或重命名 staging 目录。
  stage="$final"
  prepare_stable_construction_directory "$stage" "$fqcn"
  write_pending_version_info "$stage" "$fqcn" "$scope" "$target" "$version_index"
  write_version_tag "$stage" "$version_index" building
  trap 'write_version_tag "$stage" "$version_index" interrupted || true; exit 130' INT TERM
  trap 'write_version_tag "$stage" "$version_index" failed || true; exit 1' ERR
  profile=$(profile_for "$fqcn" 1)
  capability=$(value "$profile" CAPABILITY)
  if [[ $capability == check-only ]]; then
    write_version_tag "$stage" "$version_index" failed
    echo "$fqcn 是检查 Config，不能构造" >&2
    exit 2
  fi
  cp "$profile" "$stage/profile.env"
  write_version_info "$stage" "$stage/profile.env" "$version_index"
  log="$stage/logs/build/all.log"
  : > "$log"
  echo "开始构造 $fqcn"
  if ! "$build_tool" "$workspace" "$stage" "$stage/profile.env"; then
    failed_dir="$root/.failed/$fqcn/build"
    rm -rf "$failed_dir"
    mkdir -p "$failed_dir"
    cp -a "$stage/logs/build/." "$failed_dir/" 2>/dev/null || true
    preserve_fpga_failure_evidence "$stage" "$failed_dir"
    write_version_tag "$stage" "$version_index" failed
    echo '构造失败；无效构造目录已保留。' >&2
    echo '失败原因（关键日志）：' >&2
    failure_excerpt "$failed_dir/all.log"
    echo "完整日志目录：$failed_dir" >&2
    retry_config=$(config_short_name "$fqcn")
    echo "需要重试，请执行：make -C $npc_root rebuild config=$retry_config" >&2
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
    echo "NEMU_PRESET=$(value "$stage/profile.env" NEMU_PRESET)"
    echo "NEMU_BACKEND=$(value "$stage/profile.env" NEMU_BACKEND)"
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
  mark_construction_complete "$stage"
  write_version_tag "$stage" "$version_index" complete
  trap - ERR INT TERM
  echo "已保存构造版本 $version_index：$final"
}

do_build() {
  local request=$1 force=$2
  exec 9>"$root/.lock"
  flock 9
  do_build_locked "$request" "$force"
}

do_delete() {
  local d_version=${1:-} delete_version=${2:-} version_index directory
  [[ -n $d_version || -n $delete_version ]] || usage
  if [[ -n $d_version && -n $delete_version && $d_version != "$delete_version" ]]; then
    echo "D=$d_version 与 delete=$delete_version 不一致" >&2
    exit 2
  fi
  version_index=${d_version:-$delete_version}
  require_version_index "$version_index"
  exec 9>"$root/.lock"
  flock 9
  migrate_version_indexes_locked
  directory=$(final_construction_by_version "$version_index")
  rm -rf -- "$directory"
  reindex_version_indexes_locked
  echo "已删除构造版本 $version_index，并重新映射后续版本序号"
}

do_host_build_directory() {
  local directory=$1 profile capability fqcn logs stage previous failed_dir current_profile
  local profile_stage construction_stage host_stage profile_backup construction_backup host_backup host_lock_fd profile_lock_fd
  local profile_backed_up=0 construction_backed_up=0 host_backed_up=0 logs_backed_up=0
  local profile_published=0 construction_published=0 host_published=0 logs_published=0
  local publish_failed=0 rollback_failed=0 cleanup_failed=0
  profile="$directory/profile.env"
  [[ -f $profile ]] || { echo "构造缺少 profile.env：$directory" >&2; return 1; }
  capability=$(value "$profile" CAPABILITY)
  [[ $capability == run ]] || { echo "$(value "$profile" CONFIG_FQCN) 不是 run Config，不能构造 host" >&2; return 2; }
  fqcn=$(value "$profile" CONFIG_FQCN)
  exec {host_lock_fd}>"$directory/abi/.host-refresh.lock"
  flock "$host_lock_fd"
  # host-build all 可并行编译不同 host，但 SBT/Mill profile 生成需要串行准备，避免
  # 多个 JVM 同时争用启动 socket。生成完成后立即释放，不限制 C/C++ host 并行度。
  exec {profile_lock_fd}>"$root/.profile-generation.lock"
  flock "$profile_lock_fd"
  current_profile=$(profile_for "$fqcn" 1)
  flock -u "$profile_lock_fd"
  exec {profile_lock_fd}>&-
  profile_stage=$(mktemp "$directory/.profile-host-staging.XXXXXX")
  construction_stage=$(mktemp "$directory/.construction-host-staging.XXXXXX")
  host_stage="$directory/abi/.nemu-host-staging.$$"
  write_host_refreshed_profile "$current_profile" "$profile" "$profile_stage"
  write_host_refreshed_construction "$directory/construction.env" "$profile_stage" "$construction_stage"
  logs="$directory/logs"
  stage="$logs/.host-staging-$$"
  previous="$logs/.host-previous-$$"
  rm -rf "$stage"
  mkdir -p "$stage"
  if ! "$phase_log_tool" run "$stage" nemu-host 1 1 -- \
    "$refresh_simulation_host_tool" "$workspace" "$directory" "$profile_stage" "$host_stage"; then
    failed_dir="$root/.failed/$fqcn/host"
    rm -rf "$failed_dir"
    mkdir -p "$failed_dir"
    cp -a "$stage/." "$failed_dir/" 2>/dev/null || true
    rm -rf "$stage" "$host_stage"
    rm -f "$profile_stage" "$construction_stage"
    echo "NEMU host 重新生成失败：$fqcn" >&2
    failure_excerpt "$failed_dir/all.log"
    echo "完整日志目录：$failed_dir" >&2
    return 1
  fi
  for key in CONFIG_FQCN NEMU_PRESET NEMU_BACKEND NEMU_TRACE NEMU_WATCHPOINT NEMU_VCD NEMU_PERFORMANCE_HTML NEMU_PIPELINE_HTML \
    NEMU_NPC_DIFFTEST NEMU_DEVICES NEMU_OPTIMIZATION NEMU_DEBUG NEMU_LTO NEMU_ASAN; do
    [[ $(value "$host_stage/host.env" "$key") == $(value "$profile_stage" "$key") ]] || {
      rm -rf "$stage" "$host_stage"
      rm -f "$profile_stage" "$construction_stage"
      echo "新 NEMU host 元数据与待发布 profile 不匹配：$fqcn（$key）" >&2
      return 1
    }
  done

  profile_backup="$directory/.profile-host-previous.$$"
  construction_backup="$directory/.construction-host-previous.$$"
  host_backup="$directory/abi/.nemu-host-previous.$$"
  rm -rf "$previous"

  host_publish_move() {
    local phase=$1 source=$2 destination=$3
    if [[ ${CONSTRUCTION_TEST_HOST_PUBLISH_FAIL:-} == "$phase" ]]; then
      echo "按测试请求模拟 host 发布失败：$phase" >&2
      return 1
    fi
    mv "$source" "$destination"
  }

  if host_publish_move backup-profile "$profile" "$profile_backup"; then
    profile_backed_up=1
  else
    publish_failed=1
  fi
  if (( publish_failed == 0 )); then
    if host_publish_move backup-construction "$directory/construction.env" "$construction_backup"; then
      construction_backed_up=1
    else
      publish_failed=1
    fi
  fi
  if (( publish_failed == 0 )); then
    if host_publish_move backup-host "$directory/abi/nemu" "$host_backup"; then
      host_backed_up=1
    else
      publish_failed=1
    fi
  fi
  if (( publish_failed == 0 )) && [[ -d $logs/host ]]; then
    if host_publish_move backup-logs "$logs/host" "$previous"; then
      logs_backed_up=1
    else
      publish_failed=1
    fi
  fi
  if (( publish_failed == 0 )); then
    if host_publish_move publish-profile "$profile_stage" "$profile"; then
      profile_published=1
    else
      publish_failed=1
    fi
  fi
  if (( publish_failed == 0 )); then
    if host_publish_move publish-construction "$construction_stage" "$directory/construction.env"; then
      construction_published=1
    else
      publish_failed=1
    fi
  fi
  if (( publish_failed == 0 )); then
    if host_publish_move publish-host "$host_stage" "$directory/abi/nemu"; then
      host_published=1
    else
      publish_failed=1
    fi
  fi
  if (( publish_failed == 0 )); then
    if host_publish_move publish-logs "$stage" "$logs/host"; then
      logs_published=1
    else
      publish_failed=1
    fi
  fi

  if (( publish_failed == 0 )); then
    cleanup_failed=0
    rm -f "$profile_backup" "$construction_backup" || cleanup_failed=1
    rm -rf "$host_backup" "$previous" || cleanup_failed=1
    if (( cleanup_failed == 1 )); then
      echo "NEMU host/profile 已发布，但旧备份清理失败：$fqcn" >&2
    fi
    return 0
  fi

  if (( profile_published == 1 )) && ! rm -f "$profile"; then rollback_failed=1; fi
  if (( construction_published == 1 )) && ! rm -f "$directory/construction.env"; then rollback_failed=1; fi
  if (( host_published == 1 )) && ! rm -rf "$directory/abi/nemu"; then rollback_failed=1; fi
  if (( logs_published == 1 )) && ! rm -rf "$logs/host"; then rollback_failed=1; fi
  if (( profile_backed_up == 1 )); then
    if [[ -e $profile ]] || ! mv "$profile_backup" "$profile"; then rollback_failed=1; fi
  fi
  if (( construction_backed_up == 1 )); then
    if [[ -e $directory/construction.env ]] || ! mv "$construction_backup" "$directory/construction.env"; then rollback_failed=1; fi
  fi
  if (( host_backed_up == 1 )); then
    if [[ -e $directory/abi/nemu ]] || ! mv "$host_backup" "$directory/abi/nemu"; then rollback_failed=1; fi
  fi
  if (( logs_backed_up == 1 )); then
    if [[ -e $logs/host ]] || ! mv "$previous" "$logs/host"; then rollback_failed=1; fi
  fi
  rm -rf "$stage" "$host_stage" || rollback_failed=1
  rm -f "$profile_stage" "$construction_stage" || rollback_failed=1
  if (( rollback_failed == 0 )); then
    echo "发布 NEMU host/profile 失败，已恢复原构造：$fqcn" >&2
  else
    echo "发布 NEMU host/profile 失败且回滚不完整；备份保留在构造目录中：$fqcn" >&2
  fi
  return 1
}

do_host_build() {
  local request=$1 resolved fqcn short_config directory
  resolved=$(resolve_catalog "$request")
  IFS='|' read -r fqcn _ <<< "$resolved"
  directory="$root/$fqcn"
  [[ -d $directory ]] || {
    short_config=$(config_short_name "$fqcn")
    echo "构造不存在：$fqcn；host-build 不会隐式生成硬件，请先执行 make -C $npc_root build config=$short_config" >&2
    return 1
  }
  do_host_build_directory "$directory"
}

do_host_build_all() {
  local jobs=$1 directory env capability active=0 status=0
  [[ $jobs == -1 || $jobs =~ ^[1-9][0-9]*$ ]] || {
    echo "jobs 只能为正整数或 -1" >&2; return 2;
  }
  ensure_version_indexes
  while IFS= read -r env; do
    directory=$(dirname "$env")
    capability=$(value "$directory/profile.env" CAPABILITY)
    [[ $capability == run ]] || continue
    (
      do_host_build_directory "$directory"
    ) &
    active=$((active + 1))
    if [[ $jobs != -1 && $active -ge $jobs ]]; then
      wait -n || status=1
      active=$((active - 1))
    fi
  done < <(construction_environments)
  while (( active > 0 )); do
    wait -n || status=1
    active=$((active - 1))
  done
  return "$status"
}

do_resolve() {
  local request=${1:-} version_index=${2:-} directory profile info resolved fqcn scope board target saved_fqcn saved_short saved_version
  ensure_version_indexes_for_read
  if [[ -z $request && -z $version_index ]]; then
    echo "必须提供 config=<Config> 或 version=<版本序号>。可用 Config：" >&2
    "$0" catalog "$npc_root" >&2
    exit 2
  fi
  if [[ -n $version_index ]]; then
    directory=$(construction_by_version "$version_index")
    profile="$directory/profile.env"
    info=$(version_info_file "$directory")
    saved_fqcn=$(value "$info" CONFIG_FQCN)
    saved_short=$(value "$info" CONFIG_SHORT_NAME)
    saved_version=$(version_index_from_tag "$directory")
    if [[ -n $request ]]; then
      [[ $request == "$saved_fqcn" || $request == "$saved_short" ]] || {
        echo "config=$request 与 version=$version_index（$saved_fqcn）不一致" >&2
        exit 2
      }
    fi
  else
    resolved=$(resolve_catalog "$request")
    IFS='|' read -r fqcn scope board target <<< "$resolved"
    directory="$root/$fqcn"
    profile=$(profile_for "$fqcn")
    saved_version='-'
    if [[ -f $(version_tag_file "$directory") && -f $(version_info_file "$directory") ]]; then
      saved_version=$(version_index_from_tag "$directory")
      profile="$directory/profile.env"
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
    printf '%-34s %-8s %-5s %s\n' Config Scope XLEN Board
    # profile_for 会启动 SBT/Mill；不能让它们继承目录 TSV 作为 stdin，否则
    # 工具可能读走后续行并使 config-list 只显示第一项。
    exec 8< "$catalog"
    while IFS=$'\t' read -r short fqcn scope board target <&8; do
      [[ -z ${short:-} || $short == \#* ]] && continue
      profile=$(profile_for "$fqcn")
      printf '%-34s %-8s %-5s %s\n' "$short" "$scope" "$(value "$profile" XLEN)" "$board"
    done
    exec 8<&-
    ;;
  host-catalog)
    [[ $# == 0 ]] || usage
    host_catalog=$(mktemp "$root/.nemu-config-catalog.XXXXXX")
    trap 'rm -f "$host_catalog"' EXIT
    "$npc_root/scripts/generate-nemu-config-catalog.sh" "$npc_root" "$host_catalog"
    printf '%-22s %-8s %s\n' Preset Backend '默认受控策略'
    while IFS=$'\t' read -r preset backend policy; do
      [[ -z ${preset:-} || $preset == \#* ]] && continue
      printf '%-22s %-8s %s\n' "$preset" "$backend" "$policy"
    done < "$host_catalog"
    ;;
  resolve)
    [[ $# == 2 ]] || usage
    do_resolve "$1" "$2"
    ;;
  build)
    [[ $# == 1 ]] || usage
    do_build "$1" 0
    ;;
  rebuild)
    [[ $# == 1 ]] || usage
    do_build "$1" 1
    ;;
  host-build)
    [[ $# == 3 ]] || usage
    request=$1 build_all=$2 jobs=$3
    if [[ $build_all == 1 ]]; then
      [[ -z $request ]] || { echo 'host-build all=1 不能同时提供 config=' >&2; exit 2; }
      do_host_build_all "$jobs"
    else
      [[ $build_all == 0 ]] || { echo 'host-build all 只能为 0 或 1' >&2; exit 2; }
      [[ -n $request ]] || { echo 'host-build 必须提供 config=<硬件 Config> 或 all=1' >&2; exit 2; }
      do_host_build "$request"
    fi
    ;;
  ensure)
    [[ $# == 3 ]] || usage
    request=$1 allow_build=$2 host_rebuild=$3
    ensure_version_indexes
    profile=$(profile_for "$request")
    fqcn=$(value "$profile" CONFIG_FQCN)
    scope=$(value "$profile" SCOPE)
    directory="$root/$fqcn"
    if [[ ! -d $directory ]]; then
      if [[ $scope == fpga && $allow_build != 1 ]]; then
        echo "FPGA 构造不存在：$fqcn；首次运行请添加 build=1" >&2; exit 1
      fi
      do_build "$fqcn" 0
    elif [[ $scope == fpga ]]; then
      verify_assets "$directory"
    else
      verify_assets "$directory"
    fi
    if [[ $host_rebuild == 1 ]]; then do_host_build "$fqcn"; fi
    ;;
  list)
    [[ $# -le 1 ]] || usage
    selector=${1:-}
    selector_is_version=0
    if [[ -n $selector && $selector =~ ^[0-9]+$ ]]; then
      require_version_index "$selector"
      selector_is_version=1
    fi
    ensure_version_indexes_for_read
    declare -A final_any=() final_valid=() indexes=()
    while IFS= read -r directory; do
      version_index=$(version_index_from_tag "$directory")
      info=$(version_info_file "$directory")
      indexes[$version_index]=1
      if version_directory_is_final "$directory"; then
        [[ -z ${final_any[$version_index]:-} || ${final_any[$version_index]} == "$directory" ]] || {
          echo "版本序号 $version_index 重复，构造库已损坏" >&2
          exit 1
        }
        final_any[$version_index]=$directory
        if version_directory_is_valid "$directory"; then
          final_valid[$version_index]=$directory
        fi
      fi
    done < <(version_metadata_directories)

    found=0
    echo '=== 构造属性位图（+ 表示启用）==='
    printf '%-8s %-4s %-4s %-2s %-2s %-5s %-4s %-3s %-3s %-6s %-5s %-12s %s\n' \
      Version RV32 RV64 M F Zicsr Pipe ID EX 'valid?' Arch RunningTime Config
    while IFS= read -r version_index; do
      directory=${final_valid[$version_index]:-${final_any[$version_index]:-}}
      [[ -n $directory ]] || continue
      info=$(version_info_file "$directory")
      fqcn=$(value "$info" CONFIG_FQCN)
      short=$(value "$info" CONFIG_SHORT_NAME)
      [[ -z $selector || $selector == "$version_index" || $selector == "$fqcn" || $selector == "$short" ]] || continue
      found=$((found + 1))
      valid=''
      version_directory_is_valid "$directory" && valid=+
      printf '%-8s %-4s %-4s %-2s %-2s %-5s %-4s %-3s %-3s %-6s %-5s %-12s %s\n' \
        "$version_index" \
        "$(feature_mark "$(value "$info" RV32)")" \
        "$(feature_mark "$(value "$info" RV64)")" \
        "$(feature_mark "$(value "$info" M)")" \
        "$(feature_mark "$(value "$info" F)")" \
        "$(feature_mark "$(value "$info" ZICSR)")" \
        "$(feature_mark "$(value "$info" PIPE)")" \
        "$(feature_mark "$(value "$info" ID)")" \
        "$(feature_mark "$(value "$info" EX)")" \
        "$valid" \
        "$(value "$info" ARCH)" \
        "$(value "$info" RUNNING_TIME)" \
        "$short"
    done < <(printf '%s\n' "${!indexes[@]}" | LC_ALL=C sort -n)
    if [[ $selector_is_version == 1 && $found != 1 ]]; then
      echo "版本序号 $selector 不存在" >&2
      exit 1
    fi
    ;;
  delete)
    [[ $# -ge 1 && $# -le 2 ]] || usage
    do_delete "$@"
    ;;
  *) usage ;;
esac
