# 供各 Make 入口共享的 Scala Config 目录辅助函数。
SCPU_CONFIG_ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
SCPU_CONFIG_CATALOG := $(SCPU_CONFIG_ROOT)/chisel/configs/resources/scpu-config-catalog.tsv
SCPU_CONFIG_RESOLVER := $(SCPU_CONFIG_ROOT)/scripts/resolve-config.sh
SCPU_CONFIG_GENERATOR := $(SCPU_CONFIG_ROOT)/scripts/generate-config-catalog.sh
scpu_config_quote = '$(subst ','"'"'',$(1))'

# TSV 是 Scala 源码的派生快照。构造树中第一个 Make 负责生成一次；递归 Make 和
# AM/NEMU 子构造继承此标记，避免在同一次构造中重复启动 SBT。`version` 只读取已
# 原子发布的 construction 元数据，不解析当前 Config，因此不能为查询启动或等待 SBT。
SCPU_CONFIG_READ_ONLY_GOALS := $(if $(strip $(MAKECMDGOALS)),$(if $(strip $(filter-out version,$(MAKECMDGOALS))),,1),)
ifeq ($(strip $(SCPU_CONFIG_CATALOG_READY)),)
  ifeq ($(SCPU_CONFIG_READ_ONLY_GOALS),1)
    export SCPU_CONFIG_CATALOG_READY := 1
  else
    SCPU_CONFIG_GENERATION := $(shell $(SCPU_CONFIG_GENERATOR) $(call scpu_config_quote,$(SCPU_CONFIG_ROOT)) 2>&1)
    ifneq ($(filter !%,$(SCPU_CONFIG_GENERATION)),)
      $(error $(patsubst !%,%,$(SCPU_CONFIG_GENERATION)))
    endif
    export SCPU_CONFIG_CATALOG_READY := 1
  endif
endif

scpu_config_resolve = $(strip $(shell $(SCPU_CONFIG_RESOLVER) $(call scpu_config_quote,$(SCPU_CONFIG_CATALOG)) $(call scpu_config_quote,$(1)) $(call scpu_config_quote,$(2))))
scpu_config_field = $(word $(1),$(subst |, ,$(2)))
scpu_config_error = $(if $(filter !%,$(firstword $(1))),$(patsubst !%,%,$(1)))
comma := ,
