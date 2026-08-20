#!/usr/bin/env bash
# 从 Cuperflow profile 的 PC 数生成 wrapper 使用的 AXI 端口宏。
set -euo pipefail

[[ $# == 2 ]] || {
  echo "usage: $0 <hbm-pc-count> <output.svh>" >&2
  exit 2
}

pc_count=$1
output=$2

[[ $pc_count =~ ^[0-9]+$ ]] && (( pc_count >= 1 && pc_count <= 16 )) || {
  echo "Cuperflow HBM PC 数量必须位于 1..16，实际为 $pc_count" >&2
  exit 2
}

mkdir -p "$(dirname "$output")"
temporary="${output}.tmp.$$"
trap 'rm -f "$temporary"' EXIT

{
  printf '`define SPMV_CUPERFLOW_HBM_PC_COUNT %s\n\n' "$pc_count"

  printf '`define SPMV_CUPERFLOW_AXI_PORTS \\\n'
  for ((pc = 0; pc < pc_count; pc++)); do
    if (( pc + 1 < pc_count )); then
      printf '  `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc%02d), \\\n' "$pc"
    else
      printf '  `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc%02d),\n\n' "$pc"
    fi
  done

  printf '`define SPMV_CUPERFLOW_AXI_WIRE_DECLARATIONS \\\n'
  for ((pc = 0; pc < pc_count; pc++)); do
    if (( pc + 1 < pc_count )); then
      printf '  `SPMV_CUPERFLOW_AXI_WIRES(%d) \\\n' "$pc"
    else
      printf '  `SPMV_CUPERFLOW_AXI_WIRES(%d)\n\n' "$pc"
    fi
  done

  printf '`define SPMV_CUPERFLOW_AXI_BINDINGS \\\n'
  for ((pc = 0; pc < pc_count; pc++)); do
    if (( pc + 1 < pc_count )); then
      printf '  `SPMV_CUPERFLOW_AXI_BIND(pc%02d, %d) \\\n' "$pc" "$pc"
    else
      printf '  `SPMV_CUPERFLOW_AXI_BIND(pc%02d, %d)\n\n' "$pc" "$pc"
    fi
  done

  printf '`define SPMV_CUPERFLOW_CORE_BINDS \\\n'
  for ((pc = 0; pc < pc_count; pc++)); do
    if (( pc + 1 < pc_count )); then
      printf '  `SPMV_CUPERFLOW_CORE_BIND(%d), \\\n' "$pc"
    else
      printf '  `SPMV_CUPERFLOW_CORE_BIND(%d),\n' "$pc"
    fi
  done

  printf '\n`define SPMV_CUPERFLOW_PRODUCT_READY_BINDS \\\n'
  for ((pc = 0; pc < pc_count; pc++)); do
    if (( pc + 1 < pc_count )); then
      printf '  .io_product_%d_ready(1'"'"'b1), \\\n' "$pc"
    else
      printf '  .io_product_%d_ready(1'"'"'b1),\n' "$pc"
    fi
  done
} > "$temporary"

mv "$temporary" "$output"
