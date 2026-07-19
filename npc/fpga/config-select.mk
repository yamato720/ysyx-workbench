# FPGA 底层 recipe 的 Config 选择。公开入口只接受 config=<终端Config>；本文件
# 不提供 fpga=、soc=、VERSION、SIM_FPGA_CONFIG 或结构参数兼容。
FPGA_ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
FPGA_BOARDS_DIR := $(FPGA_ROOT)/boards
FPGA_BUILD_REQUESTED := $(if $(filter fpga-check fpga-config fpga-plan fpga-elaborate fpga-ip fpga-synth fpga-link,$(MAKECMDGOALS)),1,)
fpga_normalize_number = $(shell printf '%d' '$(strip $(1))' 2>/dev/null)

ifeq ($(FPGA_BUILD_REQUESTED),1)
  ifeq ($(strip $(config)),)
    $(error FPGA 内部构造缺少终端 config=<Config>)
  endif
  FPGA_CATALOG_RESOLVED := $(call scpu_config_resolve,$(strip $(config)),fpga-npc$(comma)fpga-soc)
  ifneq ($(call scpu_config_error,$(FPGA_CATALOG_RESOLVED)),)
    $(error $(patsubst !%,%,$(call scpu_config_error,$(FPGA_CATALOG_RESOLVED))))
  endif
  FPGA_SCALA_CONFIG := $(call scpu_config_field,1,$(FPGA_CATALOG_RESOLVED))
  FPGA_CONFIG_SCOPE := $(call scpu_config_field,2,$(FPGA_CATALOG_RESOLVED))
  FPGA_CONFIG_NAME := $(call scpu_config_field,3,$(FPGA_CATALOG_RESOLVED))
  FPGA_CONFIG_TARGET := $(call scpu_config_field,4,$(FPGA_CATALOG_RESOLVED))
  FPGA_CONFIG_RESOLVED := $(FPGA_BOARDS_DIR)/$(FPGA_CONFIG_NAME)/config.mk
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

    # config.mk 只提供 Tcl/IP 文件布局的板卡基线。硬件 ABI 必须来自所选
    # Scala Config 的 profile；两者重叠的板卡参数不一致时立即拒绝构造。
    BOARD_CONFIG_FPGA_TYPE := $(FPGA_TYPE)
    BOARD_CONFIG_FPGA_PART := $(FPGA_PART)
    BOARD_CONFIG_FPGA_PLATFORM := $(FPGA_PLATFORM)
    BOARD_CONFIG_FPGA_BOARD_PART := $(FPGA_BOARD_PART)
    BOARD_CONFIG_FPGA_CLOCK_MHZ := $(FPGA_CLOCK_MHZ)
    BOARD_CONFIG_FPGA_VIVADO_VERSION := $(FPGA_VIVADO_VERSION)
    BOARD_CONFIG_FPGA_VITIS_VERSION := $(FPGA_VITIS_VERSION)
    BOARD_CONFIG_FPGA_VITIS_TARGET := $(FPGA_VITIS_TARGET)
    BOARD_CONFIG_FPGA_TIMING_WNS_MIN_NS := $(FPGA_TIMING_WNS_MIN_NS)
    BOARD_CONFIG_FPGA_VIVADO_SYNTH_JOBS := $(FPGA_VIVADO_SYNTH_JOBS)
    BOARD_CONFIG_FPGA_VIVADO_IMPL_JOBS := $(FPGA_VIVADO_IMPL_JOBS)
    BOARD_CONFIG_FPGA_VIVADO_IMPL_STRATEGY := $(FPGA_VIVADO_IMPL_STRATEGY)
    BOARD_CONFIG_FPGA_VIVADO_IMPL_STRATEGY_SEARCH := $(FPGA_VIVADO_IMPL_STRATEGY_SEARCH)
    BOARD_CONFIG_FPGA_MEMORY_KIND := $(FPGA_MEMORY_KIND)
    BOARD_CONFIG_FPGA_FLOATING_FALLBACK := $(FPGA_FLOATING_FALLBACK)
    BOARD_CONFIG_FPGA_MEMORY_BASE := $(FPGA_MEMORY_BASE)
    BOARD_CONFIG_FPGA_MEMORY_HOST_BASE := $(FPGA_MEMORY_HOST_BASE)
    BOARD_CONFIG_FPGA_MEMORY_SIZE := $(FPGA_MEMORY_SIZE)
    BOARD_CONFIG_FPGA_CONTROL_BASE := $(FPGA_CONTROL_BASE)
    BOARD_CONFIG_FPGA_MAILBOX_BASE := $(FPGA_MAILBOX_BASE)
    BOARD_CONFIG_FPGA_DIV_IP_CYCLES := $(FPGA_DIV_IP_CYCLES)
    BOARD_CONFIG_FPGA_DIV_ADAPTER_CYCLES := $(FPGA_DIV_ADAPTER_CYCLES)
    BOARD_CONFIG_FPGA_PL_GIC_SPI := $(or $(FPGA_PL_GIC_SPI),0)

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
    ifneq ($(strip $(BOARD_CONFIG_FPGA_TYPE)),$(strip $(FPGA_TYPE)))
      $(error Scala FPGA_TYPE=$(FPGA_TYPE) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_TYPE) 不一致)
    endif
    ifneq ($(strip $(BOARD_CONFIG_FPGA_PART)),$(strip $(FPGA_PART)))
      $(error Scala FPGA_PART=$(FPGA_PART) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_PART) 不一致)
    endif
    ifneq ($(strip $(BOARD_CONFIG_FPGA_PLATFORM)),$(strip $(FPGA_PLATFORM)))
      $(error Scala FPGA_PLATFORM=$(FPGA_PLATFORM) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_PLATFORM) 不一致)
    endif
    ifneq ($(strip $(BOARD_CONFIG_FPGA_BOARD_PART)),$(strip $(FPGA_BOARD_PART)))
      $(error Scala FPGA_BOARD_PART=$(FPGA_BOARD_PART) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_BOARD_PART) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_CLOCK_MHZ),$(FPGA_CLOCK_MHZ))
      $(error Scala FPGA_CLOCK_MHZ=$(FPGA_CLOCK_MHZ) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_CLOCK_MHZ) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_VIVADO_VERSION),$(FPGA_VIVADO_VERSION))
      $(error Scala Vivado 版本 $(FPGA_VIVADO_VERSION) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_VIVADO_VERSION) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_VITIS_VERSION),$(FPGA_VITIS_VERSION))
      $(error Scala Vitis 版本 $(FPGA_VITIS_VERSION) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_VITIS_VERSION) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_VITIS_TARGET),$(FPGA_VITIS_TARGET))
      $(error Scala Vitis target $(FPGA_VITIS_TARGET) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_VITIS_TARGET) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_TIMING_WNS_MIN_NS),$(FPGA_TIMING_WNS_MIN_NS))
      $(error Scala WNS 下限 $(FPGA_TIMING_WNS_MIN_NS) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_TIMING_WNS_MIN_NS) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_VIVADO_SYNTH_JOBS),$(FPGA_VIVADO_SYNTH_JOBS))
      $(error Scala synth jobs $(FPGA_VIVADO_SYNTH_JOBS) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_VIVADO_SYNTH_JOBS) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_VIVADO_IMPL_JOBS),$(FPGA_VIVADO_IMPL_JOBS))
      $(error Scala impl jobs $(FPGA_VIVADO_IMPL_JOBS) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_VIVADO_IMPL_JOBS) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_VIVADO_IMPL_STRATEGY),$(FPGA_VIVADO_IMPL_STRATEGY))
      $(error Scala impl strategy $(FPGA_VIVADO_IMPL_STRATEGY) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_VIVADO_IMPL_STRATEGY) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_VIVADO_IMPL_STRATEGY_SEARCH),$(FPGA_VIVADO_IMPL_STRATEGY_SEARCH))
      $(error Scala impl strategy search $(FPGA_VIVADO_IMPL_STRATEGY_SEARCH) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_VIVADO_IMPL_STRATEGY_SEARCH) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_MEMORY_KIND),$(FPGA_MEMORY_KIND))
      $(error Scala memory kind $(FPGA_MEMORY_KIND) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_MEMORY_KIND) 不一致)
    endif
    ifneq ($(BOARD_CONFIG_FPGA_FLOATING_FALLBACK),$(FPGA_FLOATING_FALLBACK))
      $(error Scala 浮点回退策略 $(FPGA_FLOATING_FALLBACK) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_FLOATING_FALLBACK) 不一致)
    endif
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
    ifneq ($(BOARD_CONFIG_FPGA_PL_GIC_SPI),$(FPGA_PL_GIC_SPI))
      $(error Scala GIC SPI $(FPGA_PL_GIC_SPI) 与板卡 config.mk 的 $(BOARD_CONFIG_FPGA_PL_GIC_SPI) 不一致)
    endif

    override NPC_XLEN := $(XLEN)
    override NPC_F := $(F)
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
    override NPC_FADD_CYCLES := $(FADD_CYCLES)
    override NPC_FADD_II := $(FADD_II)
    override NPC_FMUL_CYCLES := $(FMUL_CYCLES)
    override NPC_FMUL_II := $(FMUL_II)
    override NPC_FDIV_CYCLES := $(FDIV_CYCLES)
    override NPC_FDIV_II := $(FDIV_II)
    override NPC_FFMA_CYCLES := $(FFMA_CYCLES)
    override NPC_FFMA_II := $(FFMA_II)
    override NPC_FSQRT_CYCLES := $(FSQRT_CYCLES)
    override NPC_FSQRT_II := $(FSQRT_II)
    override NPC_FCVT_CYCLES := $(FCVT_CYCLES)
    override NPC_FCVT_II := $(FCVT_II)
    override NPC_FCMP_CYCLES := $(FCMP_CYCLES)
    override NPC_FCMP_II := $(FCMP_II)
    override FPGA_M := $(M)
    override FPGA_F := $(F)
    override FPGA_MEMORY_BASE := $(MEMORY_BASE)
    override FPGA_MEMORY_SIZE := $(MEMORY_SIZE)
  endif
  FPGA_BOARD_DIR := $(FPGA_BOARDS_DIR)/$(FPGA_CONFIG_NAME)

  override FPGA_SOC := $(if $(filter fpga-soc,$(FPGA_CONFIG_SCOPE)),ysyx,)
  override NPC_TARGET := $(FPGA_CONFIG_TARGET)
  override NPC_ARITH_BACKEND := fpga
  FPGA_SOC_LABEL := $(if $(FPGA_SOC),ysyx,no-soc)
  FPGA_ISA_LABEL := $(if $(filter 1,$(NPC_F)),imf,im)
  FPGA_VARIANT := $(subst .,_,$(FPGA_SCALA_CONFIG))
  FPGA_VARIANT_DIR := $(FPGA_ROOT)/build/$(FPGA_CONFIG_NAME)/$(FPGA_VARIANT)
  FPGA_WORK_DIR ?= $(FPGA_VARIANT_DIR)/work
endif
