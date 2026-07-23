# Config 驱动的 AM/NEMU 运行选择。
#
# 该文件由 AbstractMachine 主 Makefile 在 ARCH 校验前包含。它只处理直接传入
# `config=` 或单个 `version=` 的 run/run-bat/gdb 调用；第二阶段递归 Make 会带着
# NPC_CONSTRUCTION_DIR 进入 platform/nemu.mk 的既有保存构造运行路径。

NPC_HOME ?= $(abspath $(AM_HOME)/../npc)
CONSTRUCTION_MANAGER ?= $(NPC_HOME)/scripts/construction-manager.sh

comma := ,
CONFIG_VERSION_IDS := $(strip $(subst $(comma), ,$(version)))
ifneq ($(strip $(version)),)
  ifneq ($(words $(CONFIG_VERSION_IDS)),1)
    $(error 通用 AM run/run-bat/gdb 只接受一个 version 编号；多版本矩阵请使用 am-kernels/tests/cpu-tests)
  endif
endif

CONFIG_CONSTRUCTION_RESOLUTION := $(strip $(shell $(CONSTRUCTION_MANAGER) resolve "$(NPC_HOME)" "$(strip $(config))" "$(strip $(version))"))
ifeq ($(CONFIG_CONSTRUCTION_RESOLUTION),)
  $(error 无法解析 config/version；请执行 make -C $(NPC_HOME) config-list 查看可用 Config)
endif

CONFIG_CONSTRUCTION_WORDS := $(subst |, ,$(CONFIG_CONSTRUCTION_RESOLUTION))
NPC_CONFIG_FQCN := $(word 1,$(CONFIG_CONSTRUCTION_WORDS))
NPC_CONFIG_SHORT := $(word 2,$(CONFIG_CONSTRUCTION_WORDS))
NPC_CONFIG_CAPABILITY := $(word 3,$(CONFIG_CONSTRUCTION_WORDS))
NPC_CONFIG_TARGET := $(word 4,$(CONFIG_CONSTRUCTION_WORDS))
NPC_CONFIG_XLEN := $(word 5,$(CONFIG_CONSTRUCTION_WORDS))
NPC_CONFIG_SCOPE := $(word 6,$(CONFIG_CONSTRUCTION_WORDS))
NPC_CONSTRUCTION_DIR := $(word 9,$(CONFIG_CONSTRUCTION_WORDS))
NPC_CONFIG_ARCH := riscv$(NPC_CONFIG_XLEN)-nemu

ifneq ($(NPC_CONFIG_CAPABILITY),run)
  $(error $(NPC_CONFIG_FQCN) 不是可运行 Config，不能执行 run/run-bat/gdb)
endif

ifneq ($(strip $(ARCH)),)
  ifneq ($(ARCH),$(NPC_CONFIG_ARCH))
    $(error ARCH=$(ARCH) 与 $(NPC_CONFIG_FQCN) 固定的 XLEN=$(NPC_CONFIG_XLEN) 冲突；应为 $(NPC_CONFIG_ARCH))
  endif
endif
override ARCH := $(NPC_CONFIG_ARCH)
override CONFIG_CONSTRUCTION_MANAGED := 1
