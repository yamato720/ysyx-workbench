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

COLOR_RED := $(shell echo "\033[1;31m")
COLOR_END := $(shell echo "\033[0m")

NEMU_CONFIG_BOOTSTRAP_GOALS := menuconfig savedefconfig defconfig-file $(filter %defconfig,$(MAKECMDGOALS))
ifeq ($(wildcard $(NEMU_CONFIG_FILE)),)
ifeq ($(strip $(NEMU_CONFIG_BOOTSTRAP_GOALS)),)
$(warning $(COLOR_RED)Warning: .config does not exist!$(COLOR_END))
$(warning $(COLOR_RED)To build the project, first run 'make menuconfig'.$(COLOR_END))
endif
endif

Q            := @
KCONFIG_PATH := $(NEMU_HOME)/tools/kconfig
FIXDEP_PATH  := $(NEMU_HOME)/tools/fixdep
Kconfig      := $(NEMU_HOME)/Kconfig
rm-distclean += $(NEMU_CONFIG_ROOT)/include/generated $(NEMU_CONFIG_ROOT)/include/config \
	$(NEMU_CONFIG_FILE) $(NEMU_CONFIG_FILE).old
silent := -s

CONF   := $(KCONFIG_PATH)/build/conf
MCONF  := $(KCONFIG_PATH)/build/mconf
FIXDEP := $(FIXDEP_PATH)/build/fixdep
KCONFIG_LOCK := $(KCONFIG_PATH)/build/.conf.lock

$(CONF):
	$(Q)$(MAKE) $(silent) -C $(KCONFIG_PATH) NAME=conf

$(MCONF):
	$(Q)$(MAKE) $(silent) -C $(KCONFIG_PATH) NAME=mconf

$(FIXDEP):
	$(Q)$(MAKE) $(silent) -C $(FIXDEP_PATH)

menuconfig: $(MCONF) $(CONF) $(FIXDEP)
	@mkdir -p "$(NEMU_CONFIG_ROOT)/include/config" "$(NEMU_CONFIG_ROOT)/include/generated"
	$(Q)flock "$(KCONFIG_LOCK)" $(MCONF) $(Kconfig)
	$(Q)flock "$(KCONFIG_LOCK)" $(CONF) $(silent) --syncconfig $(Kconfig)

savedefconfig: $(CONF)
	$(Q)flock "$(KCONFIG_LOCK)" $< $(silent) --$@=configs/defconfig $(Kconfig)

%defconfig: $(CONF) $(FIXDEP)
	@mkdir -p "$(NEMU_CONFIG_ROOT)/include/config" "$(NEMU_CONFIG_ROOT)/include/generated"
	$(Q)flock "$(KCONFIG_LOCK)" $< $(silent) --defconfig=configs/$@ $(Kconfig)
	$(Q)flock "$(KCONFIG_LOCK)" $< $(silent) --syncconfig $(Kconfig)

# 保存构造会由 Scala host 预设渲染一个专属 defconfig。它不是仓库内的命名预设，
# 因此不能走上面的 `%defconfig` 规则；显式入口也避免 Make 把绝对路径解析为目标名。
defconfig-file: $(CONF) $(FIXDEP)
	@test -n "$(NEMU_DEFCONFIG_FILE)" && test -f "$(NEMU_DEFCONFIG_FILE)" || { \
		echo 'defconfig-file 需要 NEMU_DEFCONFIG_FILE=<生成的 defconfig>' >&2; exit 2; \
	}
	@mkdir -p "$(NEMU_CONFIG_ROOT)/include/config" "$(NEMU_CONFIG_ROOT)/include/generated"
	$(Q)flock "$(KCONFIG_LOCK)" $< $(silent) --defconfig="$(NEMU_DEFCONFIG_FILE)" $(Kconfig)
	$(Q)flock "$(KCONFIG_LOCK)" $< $(silent) --syncconfig $(Kconfig)

.PHONY: menuconfig savedefconfig defconfig defconfig-file

# Help text used by make help
help:
	@echo  '  menuconfig	  - Update current config utilising a menu based program'
	@echo  '  savedefconfig   - Save current config as configs/defconfig (minimal config)'

distclean: clean
	-@rm -rf $(rm-distclean)

.PHONY: help distclean

define call_fixdep
	@$(FIXDEP) $(1) $(2) unused > $(1).tmp
	@mv $(1).tmp $(1)
endef
