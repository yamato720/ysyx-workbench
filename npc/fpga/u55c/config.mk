U55C_CONFIG_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
include $(U55C_CONFIG_DIR)/../common/config.mk

FPGA_CONFIG_FORMAT := 4
FPGA_NAME := u55c
# 器件、平台与工具链均来自终端 FpgaToolchainConfig；本文件只保留独立的板卡
# 硬件约束和 Tcl/IP 目录策略。
# The stock xilinx_u55c_gen3x16_xdma_3_202210_1 platform exposes a 300 MHz
# DATA_CLK to HBM-connected RTL kernels.  The profile clock is the core clock:
# lower entries are generated inside the kernel and cross back to DATA_CLK
# through async AXI-channel FIFOs.  The fixed 100 MHz freerun clock is not used.
FPGA_PLATFORM_CLOCK_MHZ := 300
FPGA_ALLOWED_CLOCK_MHZ := 100 125 150 200 250 300
FPGA_PLATFORM_CLOCK_VERIFIER := $(U55C_CONFIG_DIR)/scripts/verify-data-clock.sh

FPGA_MEMORY_BASE := 0x80000000
FPGA_MEMORY_HOST_BASE := 0x00000000
FPGA_MEMORY_SIZE := 0x08000000
FPGA_CONTROL_BASE := 0xa0000000
FPGA_MAILBOX_BASE := 0xa0010000

FPGA_DIV_IP_CYCLES := 34
FPGA_DIV_ADAPTER_CYCLES := 3
