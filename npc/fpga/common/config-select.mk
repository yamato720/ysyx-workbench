# FPGA 底层 recipe 的 Config 选择。公开入口只接受 config=<终端Config>；本文件
# 不提供 fpga=、soc=、VERSION、SIM_FPGA_CONFIG 或结构参数兼容。
FPGA_COMMON_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
FPGA_ROOT := $(abspath $(FPGA_COMMON_DIR)/..)
FPGA_BUILD_REQUESTED := $(if $(filter fpga-check fpga-config fpga-plan fpga-elaborate fpga-ip fpga-synth fpga-link spmv-check spmv-elaborate spmv-ooc-synth spmv-link spmv-input-fpga-check spmv-input-fpga-elaborate spmv-input-fpga-ip spmv-input-fpga-package spmv-input-fpga-link,$(MAKECMDGOALS)),1,)
fpga_normalize_number = $(shell printf '%d' '$(strip $(1))' 2>/dev/null)

ifeq ($(FPGA_BUILD_REQUESTED),1)
  ifeq ($(strip $(config)),)
    $(error FPGA 内部构造缺少终端 config=<Config>)
  endif
  FPGA_CATALOG_RESOLVED := $(call npc_config_resolve,$(strip $(config)),fpga)
  ifneq ($(call npc_config_error,$(FPGA_CATALOG_RESOLVED)),)
    $(error $(patsubst !%,%,$(call npc_config_error,$(FPGA_CATALOG_RESOLVED))))
  endif
  FPGA_SCALA_CONFIG := $(call npc_config_field,1,$(FPGA_CATALOG_RESOLVED))
  FPGA_CONFIG_NAME := $(call npc_config_field,3,$(FPGA_CATALOG_RESOLVED))
  FPGA_CONFIG_TARGET := $(call npc_config_field,4,$(FPGA_CATALOG_RESOLVED))
  FPGA_CONFIG_RESOLVED := $(FPGA_ROOT)/$(FPGA_CONFIG_NAME)/config.mk
  ifeq ($(wildcard $(FPGA_CONFIG_RESOLVED)),)
    $(error Config $(FPGA_SCALA_CONFIG) 对应的板卡文件不存在：$(FPGA_CONFIG_RESOLVED))
  endif
  include $(FPGA_CONFIG_RESOLVED)
  ifneq ($(FPGA_CONFIG_FORMAT),$(FPGA_CONFIG_SCHEMA))
    $(error 不支持的 FPGA 板卡配置格式 $(FPGA_CONFIG_FORMAT))
  endif
  ifneq ($(FPGA_NAME),$(FPGA_CONFIG_NAME))
    $(error Scala Config 板卡 $(FPGA_CONFIG_NAME) 与板卡配置 $(FPGA_NAME) 不一致)
  endif
  ifeq ($(INTERNAL_CONSTRUCTION),1)
    ifeq ($(strip $(CONSTRUCTION_PROFILE)),)
      $(error FPGA 内部构造缺少 Scala 生成的 CONSTRUCTION_PROFILE)
    endif
    ifeq ($(wildcard $(CONSTRUCTION_PROFILE)),)
      $(error FPGA 构造 profile 不存在：$(CONSTRUCTION_PROFILE))
    endif

    # config.mk 只提供 Tcl/IP 文件布局以及频率、地址和 IP 时序等独立硬件约束。
    # device/flow/reports/runtime 完整来自终端 FpgaToolchainConfig 渲染的 profile。
    BOARD_CONFIG_FPGA_ALLOWED_CLOCK_MHZ := $(FPGA_ALLOWED_CLOCK_MHZ)
    BOARD_CONFIG_FPGA_PLATFORM_CLOCK_MHZ := $(FPGA_PLATFORM_CLOCK_MHZ)
    BOARD_CONFIG_FPGA_MEMORY_BASE := $(FPGA_MEMORY_BASE)
    BOARD_CONFIG_FPGA_MEMORY_HOST_BASE := $(FPGA_MEMORY_HOST_BASE)
    BOARD_CONFIG_FPGA_MEMORY_SIZE := $(FPGA_MEMORY_SIZE)
    BOARD_CONFIG_FPGA_CONTROL_BASE := $(FPGA_CONTROL_BASE)
    BOARD_CONFIG_FPGA_MAILBOX_BASE := $(FPGA_MAILBOX_BASE)
    BOARD_CONFIG_FPGA_DIV_IP_CYCLES := $(FPGA_DIV_IP_CYCLES)
    BOARD_CONFIG_FPGA_DIV_ADAPTER_CYCLES := $(FPGA_DIV_ADAPTER_CYCLES)

    include $(CONSTRUCTION_PROFILE)

    ifneq ($(CONFIG_FQCN),$(FPGA_SCALA_CONFIG))
      $(error profile Config $(CONFIG_FQCN) 与目录选择 $(FPGA_SCALA_CONFIG) 不一致)
    endif
    ifneq ($(FPGA_BOARD),$(FPGA_CONFIG_NAME))
      $(error profile 板卡 $(FPGA_BOARD) 与目录板卡 $(FPGA_CONFIG_NAME) 不一致)
    endif
    ifneq ($(TARGET),$(FPGA_CONFIG_TARGET))
      $(error profile 目标 $(TARGET) 与目录目标 $(FPGA_CONFIG_TARGET) 不一致)
    endif
    ifeq ($(strip $(BOARD_CONFIG_FPGA_ALLOWED_CLOCK_MHZ)),)
      $(error 板卡 config.mk 必须定义 FPGA_ALLOWED_CLOCK_MHZ)
    endif
    ifeq ($(filter $(FPGA_CLOCK_MHZ),$(BOARD_CONFIG_FPGA_ALLOWED_CLOCK_MHZ)),)
      $(error Scala FPGA_CLOCK_MHZ=$(FPGA_CLOCK_MHZ) 不在板卡允许频率 $(BOARD_CONFIG_FPGA_ALLOWED_CLOCK_MHZ) 中)
    endif
    ifneq ($(FPGA_PLATFORM_CLOCK_MHZ),$(BOARD_CONFIG_FPGA_PLATFORM_CLOCK_MHZ))
      $(error Scala FPGA_PLATFORM_CLOCK_MHZ=$(FPGA_PLATFORM_CLOCK_MHZ) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_PLATFORM_CLOCK_MHZ) 不一致)
    endif
    ifeq ($(shell test "$(FPGA_CLOCK_MHZ)" -le "$(FPGA_PLATFORM_CLOCK_MHZ)" && echo yes),)
      $(error Scala FPGA_CLOCK_MHZ=$(FPGA_CLOCK_MHZ) 超过 platform clock $(FPGA_PLATFORM_CLOCK_MHZ))
    endif
    ifneq ($(TARGET),SPMV)
    ifneq ($(call fpga_normalize_number,$(BOARD_CONFIG_FPGA_MEMORY_BASE)),$(call fpga_normalize_number,$(MEMORY_BASE)))
      $(error Scala memory base $(MEMORY_BASE) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_MEMORY_BASE) 不一致)
    endif
    ifneq ($(call fpga_normalize_number,$(BOARD_CONFIG_FPGA_MEMORY_HOST_BASE)),$(call fpga_normalize_number,$(FPGA_MEMORY_HOST_BASE)))
      $(error Scala host memory base $(FPGA_MEMORY_HOST_BASE) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_MEMORY_HOST_BASE) 不一致)
    endif
    ifneq ($(call fpga_normalize_number,$(BOARD_CONFIG_FPGA_MEMORY_SIZE)),$(call fpga_normalize_number,$(MEMORY_SIZE)))
      $(error Scala memory size $(MEMORY_SIZE) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_MEMORY_SIZE) 不一致)
    endif
    ifneq ($(call fpga_normalize_number,$(BOARD_CONFIG_FPGA_CONTROL_BASE)),$(call fpga_normalize_number,$(FPGA_CONTROL_BASE)))
      $(error Scala control base $(FPGA_CONTROL_BASE) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_CONTROL_BASE) 不一致)
    endif
    ifneq ($(call fpga_normalize_number,$(BOARD_CONFIG_FPGA_MAILBOX_BASE)),$(call fpga_normalize_number,$(FPGA_MAILBOX_BASE)))
      $(error Scala mailbox base $(FPGA_MAILBOX_BASE) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_MAILBOX_BASE) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_DIV_IP_CYCLES),$(FPGA_DIV_IP_CYCLES))
      $(error Scala divider IP latency $(FPGA_DIV_IP_CYCLES) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_DIV_IP_CYCLES) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_DIV_ADAPTER_CYCLES),$(FPGA_DIV_ADAPTER_CYCLES))
      $(error Scala divider adapter latency $(FPGA_DIV_ADAPTER_CYCLES) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_DIV_ADAPTER_CYCLES) 不一致)
    endif
    ifneq ($(filter 0 1,$(FPGA_RUNTIME_SDB)),$(FPGA_RUNTIME_SDB))
      $(error FPGA_RUNTIME_SDB 必须是 0 或 1)
    endif
    ifneq ($(filter 0 1,$(FPGA_RUNTIME_TRACE)),$(FPGA_RUNTIME_TRACE))
      $(error FPGA_RUNTIME_TRACE 必须是 0 或 1)
    endif
    ifeq ($(FPGA_RUNTIME_SDB):$(FPGA_RUNTIME_TRACE),1:1)
      $(error FPGA SDB 与批处理性能监测必须互斥)
    endif
    ifeq ($(FPGA_RUNTIME_TRACE),1)
      ifneq ($(FPGA_CONFIG_NAME),u55c)
        $(error runtime trace 仅支持 U55C)
      endif
      ifneq ($(FPGA_CONFIG_TARGET),NPC)
        $(error runtime trace 仅支持裸 NPC)
      endif
      ifneq ($(FPGA_TRACE_HBM_BANK),1)
        $(error runtime trace 必须固定使用 HBM[1])
      endif
      ifneq ($(FPGA_TRACE_BUFFER_BYTES),8388608)
        $(error performance-monitor 缓冲区必须为 8 MiB)
      endif
      ifneq ($(FPGA_TRACE_MAX_RECORDS),200000)
        $(error performance-monitor 记录上限必须为 200000)
      endif
      ifneq ($(FPGA_TRACE_CACHE_RECORDS),2048)
        $(error performance-monitor FIFO 必须为 2048 records)
      endif
      ifneq ($(FPGA_TRACE_FORMAT),2)
        $(error performance-monitor trace format 必须为 v2)
      endif
      ifneq ($(FPGA_TRACE_RECORD_BYTES),32)
        $(error performance-monitor trace record 必须为 32 bytes)
      endif
      ifneq ($(FPGA_TRACE_DATA_WIDTH),256)
        $(error performance-monitor trace AXI 必须为 256 bits)
      endif
      ifneq ($(FPGA_TRACE_BURST_RECORDS),16)
        $(error performance-monitor trace burst 必须为 16 records)
      endif
    endif
    override NPC_XLEN := $(XLEN)
    override NPC_M := $(M)
    override NPC_PIPELINE := $(PIPELINE)
    override NPC_INTERLOCK := $(INTERLOCK)
    override NPC_ID_FWD := $(ID_FWD)
    override NPC_EX_FWD := $(EX_FWD)
    override NPC_ARITH_BACKEND := $(ARITH_BACKEND)
    override NPC_ARITH_OUTPUT_FIFO := $(ARITH_OUTPUT_FIFO)
    override NPC_MUL_CYCLES := $(MUL_CYCLES)
    override NPC_MUL_II := $(MUL_II)
    override NPC_DIV_CYCLES := $(DIV_CYCLES)
    override NPC_DIV_II := $(DIV_II)
    override FPGA_MEMORY_BASE := $(MEMORY_BASE)
    override FPGA_MEMORY_SIZE := $(MEMORY_SIZE)
    endif
  endif
  FPGA_BOARD_DIR := $(FPGA_ROOT)/$(FPGA_CONFIG_NAME)

  override FPGA_SOC := $(if $(filter SOC,$(FPGA_CONFIG_TARGET)),ysyx,)
  override NPC_TARGET := $(FPGA_CONFIG_TARGET)
  override NPC_ARITH_BACKEND := fpga
  FPGA_SOC_LABEL := $(if $(FPGA_SOC),ysyx,no-soc)
  FPGA_ISA_LABEL := $(if $(filter 1,$(NPC_M)),im,i)
  FPGA_VARIANT := $(subst .,_,$(FPGA_SCALA_CONFIG))
  FPGA_VARIANT_DIR := $(FPGA_COMMON_DIR)/build/$(FPGA_CONFIG_NAME)/$(FPGA_VARIANT)
  FPGA_WORK_DIR ?= $(FPGA_VARIANT_DIR)/work
endif
