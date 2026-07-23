#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT

${CC:-cc} -std=c11 -D_GNU_SOURCE -Wall -Wextra -Werror \
  -I"$root/include" \
  "$root/src/cpu/performance-html.c" "$root/tests/performance-html-test.c" \
  -o "$build/performance-html-test"
"$build/performance-html-test"
