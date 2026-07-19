#***************************************************************************************
# Copyright (c) 2014-2024 Zihao Yu, Nanjing University
#
# NEMU is licensed under Mulan PSL v2.
# You can use this software according to the terms and conditions of the Mulan PSL v2.
# You may obtain a copy of Mulan PSL v2 at:
#          http://license.coscl.org.cn/MulanPSL2
#
# THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
# EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
# MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
#
# See the Mulan PSL v2 for more details.
#**************************************************************************************/

SRCS-y += src/nemu-main.c
DIRS-y += src/cpu src/monitor src/utils
DIRS-$(CONFIG_MODE_SYSTEM) += src/memory
DIRS-BLACKLIST-$(CONFIG_TARGET_AM) += src/monitor/sdb

# NPC integration: add NPC core objects when USENPC=1
ifeq ($(USENPC), 1)
NPC_HOME ?= $(NEMU_HOME)/../npc
ifeq ($(NPC_SOC), 1)
NPC_OBJ_DIR ?= $(NPC_HOME)/intermediate/soc-nemu-lib
NPC_TOP = VysyxSoCTop
NPC_VERILATED_OBJS = \
  $(NPC_OBJ_DIR)/libVysyxSoCTop.a \
  $(NPC_OBJ_DIR)/libverilated.a
else
NPC_OBJ_DIR ?= $(NPC_HOME)/intermediate/chisel-cpu-lib
NPC_TOP = VCPU
# Flat designs produce a single __ALL.o.  Verilator switches to split static
# archives when a large hierarchy (for example the pipelined HardFloat
# divider) crosses its compilation threshold; link both archives in that case.
ifeq ($(wildcard $(NPC_OBJ_DIR)/$(NPC_TOP)__ALL.o),)
NPC_VERILATED_OBJS = \
  $(NPC_OBJ_DIR)/lib$(NPC_TOP).a \
  $(NPC_OBJ_DIR)/$(NPC_TOP)__ALL.a
else
NPC_VERILATED_OBJS = $(NPC_OBJ_DIR)/$(NPC_TOP)__ALL.o
endif
endif
NPC_GLUE_DIR ?= $(NPC_HOME)/csrc
NPC_SOFTFLOAT_LIB ?= $(abspath $(NPC_OBJ_DIR)/../softfloat/softfloat.a)

# Find Verilator installation
VERILATOR_ROOT ?= $(shell dirname $$(dirname $$(which verilator)))/share/verilator

# All NPC objects (custom glue + Verilator generated + Verilator runtime).
# Keep this list explicit: during a clean AM run, NEMU parses this file before
# npc-lib has necessarily populated the directory, so wildcard can expand empty.
NPC_ALL_OBJS = \
  $(NPC_OBJ_DIR)/npc_core.o \
  $(NPC_OBJ_DIR)/fp_dpi.o \
  $(NPC_OBJ_DIR)/pmem.o \
  $(NPC_OBJ_DIR)/verilated.o \
  $(NPC_OBJ_DIR)/verilated_dpi.o \
  $(NPC_OBJ_DIR)/verilated_threads.o

ifeq ($(CONFIG_NPC_VCD_TRACE),y)
NPC_ALL_OBJS += $(NPC_OBJ_DIR)/verilated_vcd_c.o
endif

ifeq ($(NPC_SOC), 1)
NPC_ALL_OBJS += $(NPC_OBJ_DIR)/soc_dpi.o
endif

# The ysyxSoC model is emitted as a static archive by current Verilator.  It
# must follow npc_core.o, which constructs the top-level model and therefore
# creates the symbol that causes the linker to extract it from the archive.
NPC_ALL_OBJS += $(NPC_VERILATED_OBJS)

# Add NPC objects to linker flags (they will be appended directly to the link command)
LDFLAGS += $(NPC_ALL_OBJS)

# Add include paths for NPC
INC_PATH += $(NPC_GLUE_DIR) $(NPC_OBJ_DIR) \
            $(VERILATOR_ROOT)/include $(VERILATOR_ROOT)/include/vltstd

# Add required libraries
LIBS += -lpthread -latomic $(NPC_SOFTFLOAT_LIB)
endif

SHARE = $(if $(CONFIG_TARGET_SHARE),1,0)
ifneq ($(filter y,$(CONFIG_FPGA_BACKEND_ZCU102) $(CONFIG_FPGA_BACKEND_U55C)),)
LIBS += $(if $(CONFIG_TARGET_NATIVE_ELF),-ldl -pie,)
else ifeq ($(ZCU102_RUNTIME_NEMU_REF),1)
  ifeq ($(SHARE),1)
    LIBS += $(if $(CONFIG_TARGET_NATIVE_ELF),-lreadline -ldl,)
  else
    LIBS += $(if $(CONFIG_TARGET_NATIVE_ELF),-lreadline -ldl -pie,)
  endif
else
LIBS += $(if $(CONFIG_TARGET_NATIVE_ELF),-lreadline -ldl -pie,)
endif

ifdef mainargs
ASFLAGS += -DBIN_PATH=\"$(mainargs)\"
endif
SRCS-$(CONFIG_TARGET_AM) += src/am-bin.S
.PHONY: src/am-bin.S
