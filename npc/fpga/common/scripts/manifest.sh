#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 {write|verify} <manifest> KEY=VALUE ..." >&2
  exit 2
}

[[ $# -ge 2 ]] || usage
command=$1
manifest=$2
shift 2

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
printf '%s\n' "$@" | LC_ALL=C sort > "$tmp"

case "$command" in
  write)
    mkdir -p "$(dirname "$manifest")"
    mv "$tmp" "$manifest"
    trap - EXIT
    ;;
  verify)
    [[ -f $manifest ]] || {
      echo "FPGA IP manifest is missing: $manifest" >&2
      exit 1
    }
    actual=$(mktemp)
    trap 'rm -f "$tmp" "$actual"' EXIT
    LC_ALL=C sort "$manifest" > "$actual"
    if ! cmp -s "$tmp" "$actual"; then
      echo "FPGA config and generated IP manifest disagree: $manifest" >&2
      diff -u "$actual" "$tmp" >&2 || true
      exit 1
    fi
    ;;
  *) usage ;;
esac
