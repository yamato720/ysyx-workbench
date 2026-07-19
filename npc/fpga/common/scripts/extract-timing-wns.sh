#!/usr/bin/env bash
# Extract the routed-design setup WNS from a Vitis/Vivado timing report.
set -euo pipefail

if [[ $# != 1 || ! -d $1 ]]; then
  echo "usage: $0 REPORT_DIRECTORY" >&2
  exit 2
fi

report_dir=$1
report=$(find "$report_dir" -type f -name '*_hw_bb_locked_timing_summary_routed.rpt' -print -quit)
if [[ -z $report ]]; then
  report=$(find "$report_dir" -type f -name '*timing_summary*routed.rpt' -print -quit)
fi
[[ -n $report ]] || {
  echo "no routed timing summary found under $report_dir" >&2
  exit 1
}

wns=$(awk '
  /^[[:space:]]*WNS\(ns\)/ { saw_header = 1; next }
  saw_header && /^[[:space:]-]+$/ { want_value = 1; next }
  want_value && $1 ~ /^-?[0-9]+([.][0-9]+)?$/ { print $1; exit }
' "$report")
[[ $wns =~ ^-?[0-9]+([.][0-9]+)?$ ]] || {
  echo "could not extract setup WNS from $report" >&2
  exit 1
}
printf '%s\n' "$wns"
