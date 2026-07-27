# 供各 Make 入口共享的 Scala Config 目录辅助函数。
NPC_CONFIG_ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
NPC_CONFIG_CATALOG := $(NPC_CONFIG_ROOT)/chisel/configs/resources/npc-config-catalog.tsv
NPC_CONFIG_RESOLVER := $(NPC_CONFIG_ROOT)/scripts/resolve-config.sh
NPC_CONFIG_GENERATOR := $(NPC_CONFIG_ROOT)/scripts/generate-config-catalog.sh
npc_config_quote = '$(subst ','"'"'',$(1))'

# TSV 是 Scala 源码的派生快照。构造树中第一个 Make 负责生成一次；递归 Make 和
# AM/NEMU 子构造继承此标记，避免在同一次构造中重复启动 SBT。`version` 的已保存
# 构造表只读取原子发布的元数据，但可构造 Config 表由管理器按源码时间戳按需刷新。
NPC_CONFIG_READ_ONLY_GOALS := $(if $(strip $(MAKECMDGOALS)),$(if $(strip $(filter-out version,$(MAKECMDGOALS))),,1),)
ifeq ($(NPC_CONFIG_READ_ONLY_GOALS),1)
  # 不在 Make 解析阶段启动 SBT；让 construction-manager 在 catalog 过期时刷新，
  # 这样 `make version` 能立即反映原地重命名或新增的 FPGA Config。
  override NPC_CONFIG_CATALOG_READY := 0
  export NPC_CONFIG_CATALOG_READY
else
  ifeq ($(strip $(NPC_CONFIG_CATALOG_READY)),)
    NPC_CONFIG_GENERATION := $(shell $(NPC_CONFIG_GENERATOR) $(call npc_config_quote,$(NPC_CONFIG_ROOT)) 2>&1)
    ifneq ($(filter !%,$(NPC_CONFIG_GENERATION)),)
      $(error $(patsubst !%,%,$(NPC_CONFIG_GENERATION)))
    endif
    export NPC_CONFIG_CATALOG_READY := 1
  endif
endif

npc_config_resolve = $(strip $(shell $(NPC_CONFIG_RESOLVER) $(call npc_config_quote,$(NPC_CONFIG_CATALOG)) $(call npc_config_quote,$(1)) $(call npc_config_quote,$(2))))
npc_config_field = $(word $(1),$(subst |, ,$(2)))
npc_config_error = $(if $(filter !%,$(firstword $(1))),$(patsubst !%,%,$(1)))
comma := ,
