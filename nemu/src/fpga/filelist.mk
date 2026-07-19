SRCS-$(CONFIG_FPGA_BACKEND_ZCU102) += src/fpga/fpga-fallback.c
SRCS-$(CONFIG_FPGA_BACKEND_ZCU102) += src/fpga/fpga-zcu102-uio.c
SRCS-$(CONFIG_FPGA_BACKEND_ZCU102) += src/fpga/fpga-remote-npc.c
SRCS-$(CONFIG_FPGA_BACKEND_U55C) += src/fpga/fpga-fallback.c
SRCS-$(CONFIG_FPGA_BACKEND_U55C) += src/fpga/fpga-remote-npc.c
ifeq ($(CONFIG_FPGA_BACKEND_U55C),y)
CXXSRC += src/fpga/fpga-u55c-xrt.cc
endif
