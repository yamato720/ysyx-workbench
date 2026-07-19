# NPC FPGA 板卡配置的公共格式。
#
# 每块板卡都显式给出全部配置值；本文件只定义格式版本和公共常量。
FPGA_CONFIG_SCHEMA := 3

FPGA_SUPPORTED_SOCS := ysyx
FPGA_SUPPORTED_XLENS := 32 64
FPGA_SUPPORTED_TYPES := zynqmp alveo
