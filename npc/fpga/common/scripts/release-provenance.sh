#!/usr/bin/env bash
# Captures the exact Git topology behind a tracked release construction file.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage:
  release-provenance.sh write <constructions.env> <source-root> <output> [--allow-dirty] [--require-tag]
  release-provenance.sh verify <constructions.env> <source-root> <manifest> [--allow-dirty] [--require-tag]
EOF
  exit 2
}

fail() {
  echo "release provenance: $*" >&2
  exit 1
}

value() {
  local file=$1 key=$2 values
  values=$(sed -n "s/^${key}=//p" "$file")
  [[ $(printf '%s\n' "$values" | sed '/^$/d' | wc -l) == 1 ]] || return 1
  printf '%s\n' "$values" | tail -n 1
}

source_dirty() {
  local root=$1
  [[ -z $(git -C "$root" status --porcelain --untracked-files=all) ]]
}

emit_revisions() {
  local root=$1 line sha rest path key
  printf 'ROOT_COMMIT=%s\n' "$(git -C "$root" rev-parse HEAD)"
  while IFS= read -r line; do
    [[ -n $line ]] || continue
    line=${line#?}
    line=${line# }
    sha=${line%% *}
    rest=${line#* }
    path=${rest%% *}
    [[ $sha =~ ^[0-9a-fA-F]{40}$ ]] || fail "invalid submodule status: $line"
    key=$(printf '%s' "$path" | tr '[:lower:]/.-' '[:upper:]___')
    printf 'SUBMODULE_%s_SHA=%s\n' "$key" "$sha"
  done < <(git -C "$root" submodule status --recursive)
}

emit_manifest() {
  local constructions=$1 source_root=$2 release_tool=$3
  local tag version configs config
  tag=$(value "$constructions" RELEASE_TAG) || fail 'construction manifest has no RELEASE_TAG'
  version=$(value "$constructions" RELEASE_VERSION) || fail 'construction manifest has no RELEASE_VERSION'
  configs=$(value "$constructions" CONFIG_FQCNS) || fail 'construction manifest has no CONFIG_FQCNS'
  [[ -n $tag && -n $version && -n $configs ]] || fail 'construction manifest contains an empty release identity'
  for config in $configs; do
    "$release_tool" verify "$constructions" "$source_root/npc" "$config" >/dev/null
  done
  {
    printf '%s\n' \
      'RELEASE_PROVENANCE_FORMAT=2' \
      "RELEASE_TAG=$tag" \
      "RELEASE_VERSION=$version" \
      "CONFIG_FQCNS=$configs" \
      "CONSTRUCTIONS_SHA256=$(sha256sum "$constructions" | cut -d' ' -f1)"
    emit_revisions "$source_root"
    grep -E '^RELEASE_CONSTRUCTIONS_FORMAT=' "$constructions"
  } | LC_ALL=C sort
}

check_tag() {
  local root=$1 tag=$2 expected_commit=$3
  [[ $(git -C "$root" cat-file -t "$tag" 2>/dev/null || true) == tag ]] ||
    fail "$tag is not an annotated tag"
  [[ $(git -C "$root" rev-list -n 1 "$tag") == "$expected_commit" ]] ||
    fail "$tag does not point to ROOT_COMMIT"
}

run() {
  local action=$1 constructions=$2 source_root=$3 output=$4
  shift 4
  local allow_dirty=0 require_tag=0 option tag root_commit release_tool generated
  while [[ $# -gt 0 ]]; do
    case $1 in
      --allow-dirty) allow_dirty=1 ;;
      --require-tag) require_tag=1 ;;
      *) usage ;;
    esac
    shift
  done
  [[ -f $constructions && -d $source_root ]] || fail 'construction manifest or source root is missing'
  git -C "$source_root" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail 'source root is not a Git worktree'
  [[ $allow_dirty == 1 ]] || source_dirty "$source_root" || fail 'release provenance requires a clean recursive worktree'
  release_tool=$source_root/npc/fpga/common/scripts/release-construction.sh
  [[ -x $release_tool ]] || fail 'release construction tool is missing'
  tag=$(value "$constructions" RELEASE_TAG) || fail 'construction manifest has no RELEASE_TAG'
  root_commit=$(git -C "$source_root" rev-parse HEAD)
  if [[ $require_tag == 1 ]]; then
    check_tag "$source_root" "$tag" "$root_commit"
  fi

  case $action in
    write)
      mkdir -p "$(dirname "$output")"
      generated=$(mktemp "$(dirname "$output")/.release-provenance.XXXXXX")
      trap 'rm -f "$generated"' RETURN
      emit_manifest "$constructions" "$source_root" "$release_tool" > "$generated"
      mv "$generated" "$output"
      trap - RETURN
      ;;
    verify)
      [[ -f $output ]] || fail "provenance manifest is missing: $output"
      generated=$(mktemp)
      trap 'rm -f "$generated"' RETURN
      emit_manifest "$constructions" "$source_root" "$release_tool" > "$generated"
      cmp -s "$generated" "$output" || fail 'provenance manifest disagrees with this checkout or construction file'
      trap - RETURN
      ;;
    *) usage ;;
  esac
}

[[ $# -ge 4 ]] || usage
run "$@"
