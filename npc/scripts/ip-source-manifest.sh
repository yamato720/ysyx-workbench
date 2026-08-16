#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'IP source manifest: %s\n' "$*" >&2
  exit 2
}

usage() {
  cat >&2 <<'EOF'
用法：
  ip-source-manifest.sh write MODE MANIFEST ROOT [--absolute] [--rtl FILE|--rtl-list FILE|--rtl-dir DIR|--model-list FILE|--xci-dir DIR]...
  ip-source-manifest.sh verify MANIFEST ROOT [MODE]
  ip-source-manifest.sh list MANIFEST ROOT {rtl|model|xci|all}
  ip-source-manifest.sh copy MANIFEST ROOT DEST
EOF
  exit 2
}

normalize_file() {
  local root=$1 path=$2 absolute=$3 resolved
  if [[ $path == /* ]]; then resolved=$(realpath -m "$path")
  else resolved=$(realpath -m "$root/$path")
  fi
  [[ -f $resolved ]] || fail "source does not exist: $path"
  if [[ $absolute == 1 ]]; then
    printf '%s\n' "$resolved"
  elif [[ $resolved == "$root/"* ]]; then
    printf '%s\n' "${resolved#"$root/"}"
  else
    printf '%s\n' "$resolved"
  fi
}

validate_synthesis_source() {
  local path=$1 name
  name=${path##*/}
  case "$name" in
    DPIMem.v|MMIOCore.v|MemoryFaultDpi.v|SimAPBDpiRam.v|SimAPBDpiMmio.v|SimPutchSink.v)
      fail "synthesis manifest rejects simulation-only source: $path" ;;
  esac
  if grep -Eq 'import[[:space:]]+"DPI-C"|pmem_(read|write)|mmio_(read|write)|memory_fault|flash_read|mrom_read' "$path"; then
    fail "synthesis manifest rejects DPI content: $path"
  fi
}

resolve_entry() {
  local root=$1 path=$2
  if [[ $path == /* ]]; then printf '%s\n' "$path"
  else printf '%s\n' "$root/$path"
  fi
}

write_manifest() {
  [[ $# -ge 3 ]] || usage
  local mode=$1 manifest=$2 root=$3 absolute=0 option value resolved line
  shift 3
  [[ $mode == simulation || $mode == synthesis ]] || fail "mode must be simulation or synthesis"
  root=$(realpath "$root")
  mkdir -p "$(dirname "$manifest")"
  local entries
  entries=$(mktemp)
  trap 'rm -f "$entries"' RETURN

  while [[ $# -gt 0 ]]; do
    option=$1
    shift
    case "$option" in
      --absolute) absolute=1; continue ;;
      --rtl|--rtl-list|--rtl-dir|--model-list|--xci-dir) ;;
      *) fail "unknown write option: $option" ;;
    esac
    [[ $# -gt 0 ]] || fail "$option requires a value"
    value=$1
    shift
    case "$option" in
      --rtl)
        resolved=$(normalize_file "$root" "$value" "$absolute")
        printf 'RTL=%s\n' "$resolved" >> "$entries"
        ;;
      --rtl-list|--model-list)
        local kind
        [[ -f $value ]] || value="$root/$value"
        [[ -f $value ]] || fail "source list does not exist: $value"
        kind=RTL
        [[ $option == --model-list ]] && kind=MODEL
        while IFS= read -r line || [[ -n $line ]]; do
          [[ -n $line && $line != \#* ]] || continue
          resolved=$(normalize_file "$root" "$line" "$absolute")
          printf '%s=%s\n' "$kind" "$resolved" >> "$entries"
        done < "$value"
        ;;
      --rtl-dir|--xci-dir)
        local kind pattern
        [[ -d $value ]] || value="$root/$value"
        [[ -d $value ]] || fail "source directory does not exist: $value"
        kind=RTL
        pattern='*.v|*.sv'
        [[ $option == --xci-dir ]] && { kind=XCI; pattern='*.xci'; }
        while IFS= read -r -d '' line; do
          resolved=$(normalize_file "$root" "$line" "$absolute")
          printf '%s=%s\n' "$kind" "$resolved" >> "$entries"
        done < <(find "$value" -type f \( -name "${pattern%%|*}" -o -name "${pattern#*|}" \) -print0 | sort -z)
        ;;
    esac
  done

  [[ -s $entries ]] || fail 'manifest has no sources'
  grep -q '^RTL=' "$entries" || fail 'manifest has no RTL sources'
  if [[ $mode == synthesis ]]; then
    if grep -q '^MODEL=' "$entries"; then fail 'synthesis manifest cannot contain MODEL entries'; fi
    while IFS='=' read -r option value; do
      [[ $option == RTL ]] || continue
      validate_synthesis_source "$(resolve_entry "$root" "$value")"
    done < "$entries"
  elif grep -q '^XCI=' "$entries"; then
    fail 'simulation manifest cannot contain XCI entries'
  fi

  local tmp
  tmp=$(mktemp "$(dirname "$manifest")/.ip-sources.XXXXXX")
  {
    printf 'FORMAT=npc-ip-source-manifest-v1\nMODE=%s\n' "$mode"
    LC_ALL=C sort -u "$entries"
  } > "$tmp"
  mv "$tmp" "$manifest"
}

verify_manifest() {
  [[ $# -ge 2 && $# -le 3 ]] || usage
  local manifest=$1 root=$2 expected_mode=${3:-} mode kind path
  root=$(realpath "$root")
  [[ -f $manifest ]] || fail "manifest does not exist: $manifest"
  [[ $(sed -n 's/^FORMAT=//p' "$manifest") == npc-ip-source-manifest-v1 ]] || fail 'unsupported format'
  mode=$(sed -n 's/^MODE=//p' "$manifest")
  [[ $mode == simulation || $mode == synthesis ]] || fail 'invalid mode'
  [[ -z $expected_mode || $mode == "$expected_mode" ]] || fail "expected $expected_mode manifest, got $mode"
  grep -q '^RTL=' "$manifest" || fail 'manifest has no RTL sources'
  while IFS='=' read -r kind path; do
    case "$kind" in FORMAT|MODE) continue ;; RTL|MODEL|XCI) ;; *) fail "unknown entry: $kind" ;; esac
    path=$(resolve_entry "$root" "$path")
    [[ -f $path ]] || fail "manifest source is missing: $path"
    if [[ $mode == synthesis ]]; then
      [[ $kind != MODEL ]] || fail 'synthesis manifest contains a MODEL entry'
      [[ $kind != RTL ]] || validate_synthesis_source "$path"
    else
      [[ $kind != XCI ]] || fail 'simulation manifest contains an XCI entry'
    fi
  done < "$manifest"
}

list_manifest() {
  [[ $# == 3 ]] || usage
  local manifest=$1 root=$2 selected=$3 kind path
  [[ $selected == rtl || $selected == model || $selected == xci || $selected == all ]] || usage
  verify_manifest "$manifest" "$root"
  root=$(realpath "$root")
  while IFS='=' read -r kind path; do
    [[ $kind == RTL || $kind == MODEL || $kind == XCI ]] || continue
    if [[ $selected == all || ${kind,,} == "$selected" ]]; then resolve_entry "$root" "$path"; fi
  done < "$manifest"
}

copy_manifest() {
  [[ $# == 3 ]] || usage
  local manifest=$1 root=$2 destination=$3 source relative
  root=$(realpath "$root")
  verify_manifest "$manifest" "$root"
  mkdir -p "$destination"
  while IFS= read -r source; do
    if [[ $source == "$root/"* ]]; then relative=${source#"$root/"}
    else relative="external/${source##*/}"
    fi
    mkdir -p "$destination/$(dirname "$relative")"
    cp "$source" "$destination/$relative"
  done < <(list_manifest "$manifest" "$root" all)
  cp "$manifest" "$destination/ip-sources.manifest"
}

[[ $# -gt 0 ]] || usage
command=$1
shift
case "$command" in
  write) write_manifest "$@" ;;
  verify) verify_manifest "$@" ;;
  list) list_manifest "$@" ;;
  copy) copy_manifest "$@" ;;
  *) usage ;;
esac
