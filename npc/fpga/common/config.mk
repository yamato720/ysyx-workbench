# NPC FPGA 板卡配置的公共格式。
#
# 板卡文件只给出独立硬件约束；终端工具链由 Scala profile 完整提供。
FPGA_CONFIG_SCHEMA := 4

FPGA_SUPPORTED_SOCS := ysyx
FPGA_SUPPORTED_XLENS := 32 64
FPGA_SUPPORTED_TYPES := zynqmp alveo
