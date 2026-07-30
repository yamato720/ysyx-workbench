#!/usr/bin/env bash
# Verify the clock Vitis actually bound to an HBM-connected U55C RTL kernel.
set -euo pipefail

[[ $# == 2 ]] || {
  echo "usage: verify-data-clock.sh <xclbin> <expected-mhz>" >&2
  exit 2
}

xclbin=$1
expected_mhz=$2
xclbinutil=${XCLBINUTIL:-xclbinutil}

[[ -f $xclbin ]] || {
  echo "U55C clock verification cannot find xclbin: $xclbin" >&2
  exit 2
}
[[ $expected_mhz =~ ^[1-9][0-9]*$ ]] || {
  echo "U55C expected clock must be a positive integer MHz value: $expected_mhz" >&2
  exit 2
}

actual_mhz=$("$xclbinutil" --info --input "$xclbin" 2>/dev/null | awk '
  /^Scalable Clocks$/ { in_scalable_clocks = 1; next }
  /^System Clocks$/ { in_scalable_clocks = 0 }
  in_scalable_clocks && /^[[:space:]]*Name:/ { name = $2; next }
  in_scalable_clocks && name == "DATA_CLK" && /^[[:space:]]*Frequency:/ {
    if (!found++) print $2
  }
')

[[ $actual_mhz =~ ^[1-9][0-9]*$ ]] || {
  echo "U55C xclbin does not expose a DATA_CLK frequency: $xclbin" >&2
  exit 1
}
[[ $actual_mhz == "$expected_mhz" ]] || {
  echo "U55C DATA_CLK is ${actual_mhz} MHz, expected ${expected_mhz} MHz; refusing a misleading clock profile" >&2
  exit 1
}
