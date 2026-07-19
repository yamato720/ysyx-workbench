#!/usr/bin/env bash
# Creates and verifies portable FPGA release-asset manifests.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage:
  artifact-manifest.sh write --directory DIR --source-root DIR --release-tag TAG \
    --board BOARD --variant VARIANT --type TYPE --platform PLATFORM \
    --config-fqcn FQCN --host-abi ABI --protocol-abi ABI [--timing-wns VALUE] \
    --asset FILE [--asset FILE ...]
  artifact-manifest.sh verify --directory DIR --board BOARD --platform PLATFORM \
    [--config-fqcn FQCN] [--host-abi ABI] [--protocol-abi ABI] \
    [--release-tag TAG] [--formal] [--require-timing]
EOF
  exit 2
}

fail() {
  echo "artifact manifest: $*" >&2
  exit 1
}

require_value() {
  [[ -n ${2:-} ]] || { echo "missing value for $1" >&2; usage; }
}

env_value() {
  local file=$1 key=$2 matches value
  matches=$(sed -n "s/^${key}=//p" "$file")
  [[ $(printf '%s\n' "$matches" | sed '/^$/d' | wc -l) == 1 ]] || return 1
  value=$(printf '%s\n' "$matches" | tail -n 1)
  [[ -n $value ]] || return 1
  printf '%s\n' "$value"
}

safe_token() {
  [[ $1 =~ ^[A-Za-z0-9._:-]+$ ]]
}

safe_asset_name() {
  [[ $1 =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]
}

asset_key() {
  printf '%s' "$1" | tr '[:lower:].-' '[:upper:]__'
}

tool_version() {
  local tool=$1
  if command -v "$tool" >/dev/null 2>&1; then
    case "$tool" in
      vivado) "$tool" -version 2>&1 | sed -n '/[^[:space:]]/{p;q;}' || true ;;
      v++) "$tool" --version 2>&1 | sed -n '/[^[:space:]]/{p;q;}' || true ;;
      *) "$tool" --version 2>&1 | sed -n '/[^[:space:]]/{p;q;}' || true ;;
    esac
  else
    printf 'unavailable\n'
  fi
}

git_dirty() {
  local root=$1
  if [[ -n $(git -C "$root" status --porcelain --untracked-files=all 2>/dev/null) ]]; then
    printf '1\n'
  else
    printf '0\n'
  fi
}

emit_source_revisions() {
  local root=$1 line rest sha path key
  printf 'ROOT_COMMIT=%s\n' "$(git -C "$root" rev-parse HEAD)"
  printf 'SOURCE_DIRTY=%s\n' "$(git_dirty "$root")"
  while IFS= read -r line; do
    [[ -n $line ]] || continue
    line=${line#?}
    line=${line# }
    sha=${line%% *}
    rest=${line#* }
    path=${rest%% *}
    [[ $sha =~ ^[0-9a-fA-F]{40}$ ]] || fail "invalid submodule revision: $line"
    key=$(printf '%s' "$path" | tr '[:lower:]/.-' '[:upper:]___')
    [[ $key =~ ^[A-Z0-9_]+$ ]] || fail "unsafe submodule path: $path"
    printf 'SUBMODULE_%s_SHA=%s\n' "$key" "$sha"
  done < <(git -C "$root" submodule status --recursive)
}

write_manifest() {
  local directory= source_root= release_tag= board= variant= type= platform=
  local config_fqcn= host_abi= protocol_abi=
  local timing_wns=UNAVAILABLE
  local -a assets=()

  while [[ $# -gt 0 ]]; do
    case $1 in
      --directory) require_value "$1" "${2:-}"; directory=$2; shift 2 ;;
      --source-root) require_value "$1" "${2:-}"; source_root=$2; shift 2 ;;
      --release-tag) require_value "$1" "${2:-}"; release_tag=$2; shift 2 ;;
      --board) require_value "$1" "${2:-}"; board=$2; shift 2 ;;
      --variant) require_value "$1" "${2:-}"; variant=$2; shift 2 ;;
      --type) require_value "$1" "${2:-}"; type=$2; shift 2 ;;
      --platform) platform=${2:-}; shift 2 ;;
      --config-fqcn) require_value "$1" "${2:-}"; config_fqcn=$2; shift 2 ;;
      --host-abi) require_value "$1" "${2:-}"; host_abi=$2; shift 2 ;;
      --protocol-abi) require_value "$1" "${2:-}"; protocol_abi=$2; shift 2 ;;
      --timing-wns) require_value "$1" "${2:-}"; timing_wns=$2; shift 2 ;;
      --asset) require_value "$1" "${2:-}"; assets+=("$2"); shift 2 ;;
      *) usage ;;
    esac
  done

  [[ -d $directory && -d $source_root ]] || fail 'directory and source root must exist'
  [[ ${#assets[@]} -gt 0 ]] || fail 'at least one --asset is required'
  for value in "$release_tag" "$board" "$variant" "$type" "$config_fqcn" "$host_abi" "$protocol_abi"; do
    safe_token "$value" || fail "unsafe manifest value: $value"
  done
  platform=${platform:-none}
  safe_token "$platform" || fail "unsafe platform value: $platform"
  [[ $timing_wns == UNAVAILABLE || $timing_wns =~ ^-?[0-9]+([.][0-9]+)?$ ]] ||
    fail "invalid timing WNS value: $timing_wns"
  git -C "$source_root" rev-parse --is-inside-work-tree >/dev/null 2>&1 ||
    fail "source root is not a Git worktree: $source_root"

  local asset manifest sums tmp key asset_names
  manifest=$directory/artifact-manifest.env
  sums=$directory/SHA256SUMS
  tmp=$(mktemp "$directory/.artifact-manifest.XXXXXX")
  trap 'rm -f "$tmp"' RETURN
  {
    printf '%s\n' \
      'ARTIFACT_MANIFEST_FORMAT=2' \
      "RELEASE_TAG=$release_tag" \
      "BOARD=$board" \
      "VARIANT=$variant" \
      "FPGA_TYPE=$type" \
      "FPGA_PLATFORM=$platform" \
      "XRT_PLATFORM=$platform" \
      "CONFIG_FQCN=$config_fqcn" \
      "HOST_ABI=$host_abi" \
      "PROTOCOL_ABI=$protocol_abi" \
      "VIVADO_VERSION=$(tool_version vivado)" \
      "VITIS_VERSION=$(tool_version v++)" \
      "XRT_VERSION=$(tool_version xbutil)" \
      "TIMING_WNS_NS=$timing_wns"
    emit_source_revisions "$source_root"
    asset_names=$(IFS=' '; printf '%s' "${assets[*]}")
    printf 'ASSET_NAMES=%s\n' "$asset_names"
    for asset in "${assets[@]}"; do
      safe_asset_name "$asset" || fail "unsafe asset name: $asset"
      [[ -f $directory/$asset ]] || fail "asset is missing: $directory/$asset"
      key=$(asset_key "$asset")
      printf 'ASSET_%s_SHA256=%s\n' "$key" "$(sha256sum -- "$directory/$asset" | cut -d' ' -f1)"
    done
  } | LC_ALL=C sort > "$tmp"
  mv "$tmp" "$manifest"
  trap - RETURN

  tmp=$(mktemp "$directory/.sha256sums.XXXXXX")
  trap 'rm -f "$tmp"' RETURN
  for asset in "${assets[@]}"; do
    (cd "$directory" && sha256sum -- "$asset")
  done | LC_ALL=C sort > "$tmp"
  mv "$tmp" "$sums"
  trap - RETURN
}

verify_manifest() {
  local directory= board= platform= config_fqcn= host_abi= protocol_abi= release_tag= formal=0 require_timing=0
  while [[ $# -gt 0 ]]; do
    case $1 in
      --directory) require_value "$1" "${2:-}"; directory=$2; shift 2 ;;
      --board) require_value "$1" "${2:-}"; board=$2; shift 2 ;;
      --platform) platform=${2:-}; shift 2 ;;
      --config-fqcn) require_value "$1" "${2:-}"; config_fqcn=$2; shift 2 ;;
      --host-abi) require_value "$1" "${2:-}"; host_abi=$2; shift 2 ;;
      --protocol-abi) require_value "$1" "${2:-}"; protocol_abi=$2; shift 2 ;;
      --release-tag) require_value "$1" "${2:-}"; release_tag=$2; shift 2 ;;
      --formal) formal=1; shift ;;
      --require-timing) require_timing=1; shift ;;
      *) usage ;;
    esac
  done
  [[ -d $directory ]] || fail "artifact directory does not exist: $directory"
  local manifest=$directory/artifact-manifest.env sums=$directory/SHA256SUMS
  [[ -f $manifest && -f $sums ]] || fail 'artifact-manifest.env and SHA256SUMS are required'

  local field actual expected asset_name key expected_sums actual_sums wns asset_names
  for field in ARTIFACT_MANIFEST_FORMAT BOARD FPGA_TYPE FPGA_PLATFORM XRT_PLATFORM CONFIG_FQCN \
    HOST_ABI PROTOCOL_ABI \
    VIVADO_VERSION VITIS_VERSION XRT_VERSION \
    TIMING_WNS_NS ROOT_COMMIT SOURCE_DIRTY ASSET_NAMES; do
    env_value "$manifest" "$field" >/dev/null || fail "missing or duplicate $field"
  done
  case "$(env_value "$manifest" ARTIFACT_MANIFEST_FORMAT)" in
    1|2) ;;
    *) fail 'unsupported manifest format' ;;
  esac
  [[ $(env_value "$manifest" BOARD) == "$board" ]] || fail "wrong board: expected $board"
  [[ $(env_value "$manifest" FPGA_PLATFORM) == "$platform" ]] || fail 'wrong FPGA platform'
  [[ $(env_value "$manifest" XRT_PLATFORM) == "$platform" ]] || fail 'wrong XRT platform'
  if [[ -n $config_fqcn ]]; then
    [[ $(env_value "$manifest" CONFIG_FQCN) == "$config_fqcn" ]] || fail 'wrong terminal Config FQCN'
  fi
  if [[ -n $host_abi ]]; then
    [[ $(env_value "$manifest" HOST_ABI) == "$host_abi" ]] || fail 'incompatible host ABI'
  fi
  if [[ -n $protocol_abi ]]; then
    [[ $(env_value "$manifest" PROTOCOL_ABI) == "$protocol_abi" ]] || fail 'incompatible FPGA protocol ABI'
  fi
  if [[ -n $release_tag ]]; then
    [[ $(env_value "$manifest" RELEASE_TAG) == "$release_tag" ]] || fail 'wrong release tag'
  fi
  if [[ $formal == 1 ]]; then
    [[ $(env_value "$manifest" SOURCE_DIRTY) == 0 ]] || fail 'formal assets require a clean source worktree'
    [[ $(env_value "$manifest" RELEASE_TAG) != UNRELEASED ]] || fail 'formal assets require a release tag'
  fi

  wns=$(env_value "$manifest" TIMING_WNS_NS)
  if [[ $require_timing == 1 || $formal == 1 ]]; then
    [[ $wns =~ ^-?[0-9]+([.][0-9]+)?$ ]] || fail 'timing WNS is unavailable'
    awk -v wns="$wns" 'BEGIN { exit !(wns >= 0) }' || fail "timing WNS is negative: $wns ns"
  fi

  asset_names=$(env_value "$manifest" ASSET_NAMES)
  case "$board" in
    zcu102)
      [[ $asset_names == 'npc.bit npc.xsa system-user.dtsi npc-zcu102.env' ]] ||
        fail 'ZCU102 package has an unexpected required asset set'
      ;;
    u55c)
      [[ $platform != none && $asset_names == "npc-$platform.xclbin" ]] ||
        fail 'U55C package must contain one platform-qualified xclbin'
      ;;
    *) fail "unsupported board: $board" ;;
  esac
  expected_sums=$(mktemp)
  actual_sums=$(mktemp)
  trap 'rm -f "$expected_sums" "$actual_sums"' RETURN
  for asset_name in $asset_names; do
    safe_asset_name "$asset_name" || fail "unsafe asset name: $asset_name"
    [[ -f $directory/$asset_name ]] || fail "checksummed asset is missing: $asset_name"
    key=$(asset_key "$asset_name")
    expected=$(env_value "$manifest" "ASSET_${key}_SHA256") ||
      fail "missing checksum for $asset_name"
    [[ $expected =~ ^[0-9a-f]{64}$ ]] || fail "invalid manifest checksum for $asset_name"
    actual=$(sha256sum -- "$directory/$asset_name" | cut -d' ' -f1)
    [[ $actual == "$expected" ]] || fail "checksum mismatch: $asset_name"
    printf '%s  %s\n' "$expected" "$asset_name" >> "$expected_sums"
  done
  [[ -s $expected_sums ]] || fail 'manifest has no assets'
  LC_ALL=C sort "$expected_sums" -o "$expected_sums"
  while IFS= read -r line; do
    [[ $line =~ ^([0-9a-f]{64})[[:space:]][[:space:]]([A-Za-z0-9][A-Za-z0-9._-]*)$ ]] ||
      fail "unsafe SHA256SUMS entry: $line"
    printf '%s\n' "$line" >> "$actual_sums"
  done < "$sums"
  [[ -s $actual_sums ]] || fail 'SHA256SUMS has no assets'
  LC_ALL=C sort "$actual_sums" -o "$actual_sums"
  cmp -s "$expected_sums" "$actual_sums" || fail 'SHA256SUMS disagrees with manifest assets'
  trap - RETURN
}

[[ $# -ge 1 ]] || usage
command=$1
shift
case "$command" in
  write) write_manifest "$@" ;;
  verify) verify_manifest "$@" ;;
  *) usage ;;
esac
