#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT

${CC:-cc} -std=c11 -D_GNU_SOURCE -Wall -Wextra -Werror \
  -I"$root/include" -I"$root/src/fpga" \
  "$root/src/fpga/fpga-runtime.c" \
  "$root/src/fpga/fpga-zcu102-uio.c" \
  "$root/tests/fpga-runtime-test.c" \
  -o "$build/fpga-runtime-test"
"$build/fpga-runtime-test"
