# ZCU102 runtime Vivado project skeleton.
#
# This file is intentionally conservative. It records the expected project
# shape, but does not try to guess board-specific pins or generated IP paths.
# Fill in tool version, board part, source lists, and block design details
# after the first manual Vivado bring-up.

set project_name zcu102_runtime
set project_dir  ./build/vivado

create_project $project_name $project_dir -force

# Prefer setting the board part from installed ZCU102 board files.
# Example only; verify with: get_board_parts *zcu102*
# set_property board_part xilinx.com:zcu102:part0:3.4 [current_project]

# Device fallback for common ZCU102 kits. Verify against the actual board.
# set_property part xczu9eg-ffvb1156-2-i [current_project]

# Add generated RTL and handwritten runtime sources here.
# add_files ../../ysyxSoC/build/ysyxSoCFull.v
# add_files ../rtl/ZCU102BoardTop.sv

# Add constraints derived from the official ZCU102 master XDC.
# add_files -fileset constrs_1 ../constraints/zcu102_runtime.xdc

# Recommended next steps:
# 1. create_bd_design "zcu102_runtime_bd"
# 2. instantiate Zynq UltraScale+ MPSoC PS
# 3. enable PL clock/reset and AXI control port
# 4. add AXI BRAM Controller + Block Memory Generator
# 5. connect ZCU102RuntimeTop
# 6. add ILA on CPU AXI and runtime status
# 7. validate_bd_design
# 8. generate_target all
