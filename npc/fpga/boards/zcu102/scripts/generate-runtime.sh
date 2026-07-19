#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "usage: $0 OUTPUT_DIR DDR_PHYS DDR_SIZE MAILBOX_PHYS GIC_SPI" >&2
  exit 2
fi

output_dir=$1
ddr_phys=$2
ddr_size=$3
mailbox_phys=$4
gic_spi=$5

for value in "$ddr_phys" "$ddr_size" "$mailbox_phys" "$gic_spi"; do
  if [[ ! $value =~ ^(0[xX][0-9a-fA-F]+|[0-9]+)$ ]]; then
    echo "invalid numeric runtime value: $value" >&2
    exit 2
  fi
done

ddr_phys_hex=$(printf '0x%08x' "$((ddr_phys))")
ddr_size_hex=$(printf '0x%08x' "$((ddr_size))")
mailbox_phys_hex=$(printf '0x%08x' "$((mailbox_phys))")
mailbox_unit=$(printf '%x' "$((mailbox_phys))")

mkdir -p "$output_dir"
printf '%s\n' \
  '/ {' \
  '  reserved-memory {' \
  '    #address-cells = <2>;' \
  '    #size-cells = <2>;' \
  '    ranges;' \
  '' \
  "    npc_reserved_ddr: npc@${ddr_phys_hex#0x} {" \
  '      no-map;' \
  "      reg = <0x0 $ddr_phys_hex 0x0 $ddr_size_hex>;" \
  '    };' \
  '  };' \
  '};' \
  '' \
  '&amba_pl {' \
  "  npc_mailbox: mailbox@${mailbox_unit} {" \
  '    compatible = "generic-uio";' \
  "    reg = <0x0 $mailbox_phys_hex 0x0 0x00010000>;" \
  '    interrupt-parent = <&gic>;' \
  "    interrupts = <0 $gic_spi 4>;" \
  '  };' \
  '};' > "$output_dir/system-user.dtsi"

printf '%s\n' \
  '# Source this file on ZCU102 PS Linux before starting the FPGA NEMU backend.' \
  'export NEMU_FPGA_UIO="${NEMU_FPGA_UIO:-/dev/uio0}"' \
  'export NEMU_FPGA_DDR_DEVICE="${NEMU_FPGA_DDR_DEVICE:-/dev/mem}"' \
  'export NEMU_FPGA_UIO_SIZE="${NEMU_FPGA_UIO_SIZE:-0x00010000}"' \
  "export NEMU_FPGA_DDR_PHYS=\"\${NEMU_FPGA_DDR_PHYS:-$ddr_phys_hex}\"" \
  "export NEMU_FPGA_DDR_SIZE=\"\${NEMU_FPGA_DDR_SIZE:-$ddr_size_hex}\"" \
  'export NEMU_FPGA_MAILBOX_MAX_CYCLES="${NEMU_FPGA_MAILBOX_MAX_CYCLES:-300000000}"' \
  > "$output_dir/npc-zcu102.env"
