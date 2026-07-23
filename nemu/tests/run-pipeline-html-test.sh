#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT

${CC:-cc} -std=c11 -D_GNU_SOURCE -Wall -Wextra -Werror \
  -I"$root/include" \
  "$root/src/cpu/pipeline-html.c" "$root/tests/pipeline-html-test.c" \
  -o "$build/pipeline-html-test"
"$build/pipeline-html-test"
