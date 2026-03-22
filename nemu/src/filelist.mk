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
NPC_OBJ_DIR = $(NPC_HOME)/intermediate/chisel-cpu-lib

# Find Verilator installation
VERILATOR_ROOT ?= $(shell dirname $$(dirname $$(which verilator)))/share/verilator

# All NPC objects (Verilator generated + custom + Verilator runtime)
NPC_ALL_OBJS = $(wildcard $(NPC_OBJ_DIR)/*.o)

# Add NPC objects to linker flags (they will be appended directly to the link command)
LDFLAGS += $(NPC_ALL_OBJS)

# Add include paths for NPC
INC_PATH += $(NPC_HOME)/csrc $(NPC_OBJ_DIR) \
            $(VERILATOR_ROOT)/include $(VERILATOR_ROOT)/include/vltstd

# Add required libraries
LIBS += -lpthread -latomic
endif

SHARE = $(if $(CONFIG_TARGET_SHARE),1,0)
LIBS += $(if $(CONFIG_TARGET_NATIVE_ELF),-lreadline -ldl -pie,)

ifdef mainargs
ASFLAGS += -DBIN_PATH=\"$(mainargs)\"
endif
SRCS-$(CONFIG_TARGET_AM) += src/am-bin.S
.PHONY: src/am-bin.S
