# Placeholder XDC for ZCU102 runtime.
#
# Do not use this file as a pin-complete constraint set. Populate it from the
# official AMD/Xilinx ZCU102 master XDC or installed Vivado board files.
#
# MVP designs that use PS-generated PL clocks and PS AXI control may need very
# few manual pin constraints. Add only verified board-level ports here.

# Example shape only:
# set_property PACKAGE_PIN <verified_pin> [get_ports uart_tx]
# set_property IOSTANDARD LVCMOS18 [get_ports uart_tx]
