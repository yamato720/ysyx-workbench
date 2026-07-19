.DEFAULT_GOAL = app

# Add necessary options if the target is a shared library
ifeq ($(SHARE),1)
SO = -so
CFLAGS  += -fPIC -fvisibility=hidden
LDFLAGS += -shared -fPIC
endif

WORK_DIR  = $(shell pwd)
BUILD_DIR ?= $(NEMU_BUILD_ROOT)

INC_PATH := $(NEMU_CONFIG_ROOT)/include $(WORK_DIR)/include $(WORK_DIR)/src/monitor/sdb $(INC_PATH)
ifneq ($(strip $(NEMU_OBJ_DIR)),)
OBJ_DIR  := $(NEMU_OBJ_DIR)
else
OBJ_DIR  ?= $(BUILD_DIR)/obj-$(NAME)$(SO)
endif
BINARY   = $(BUILD_DIR)/$(NAME)$(SO)

# Compilation flags
ifeq ($(CC),clang)
CXX := clang++
else
CXX := g++
endif
LD := $(CXX)
INCLUDES = $(addprefix -I, $(INC_PATH))
CFLAGS  := -O2 -MMD -Wall -Werror $(INCLUDES) $(CFLAGS)
LDFLAGS := -O2 $(LDFLAGS)

OBJS = $(SRCS:%.c=$(OBJ_DIR)/%.o) $(CXXSRC:%.cc=$(OBJ_DIR)/%.o)

# `.config` is the menuconfig ABI.  C/C++ source and header changes are tracked
# by Make and fixdep; only a menuconfig change must invalidate every object.
# This deliberately compares small generated config text instead of hashing
# the source tree or probing the toolchain on every invocation.
NEMU_MENUCONFIG_STATE := $(OBJ_DIR)/.menuconfig-state
NEMU_MENUCONFIG_INPUTS := $(NEMU_CONFIG_FILE) $(NEMU_AUTO_CONF) $(NEMU_AUTO_CONF_CMD) $(NEMU_AUTO_HEADER)

.PHONY: FORCE
FORCE:

$(NEMU_MENUCONFIG_STATE): FORCE
	@set -e; \
	mkdir -p "$(OBJ_DIR)"; tmp="$@.tmp"; \
	{ for file in $(NEMU_MENUCONFIG_INPUTS); do \
		printf '%s\n' "--- $$file"; test ! -f "$$file" || cat "$$file"; \
	done; } > "$$tmp"; \
	if test -f "$@" && cmp -s "$$tmp" "$@"; then rm -f "$$tmp"; else mv "$$tmp" "$@"; fi

$(OBJ_DIR)/%.o: %.c $(NEMU_MENUCONFIG_STATE)
	@echo + CC $<
	@mkdir -p $(dir $@)
	@$(CC) $(CFLAGS) -c -o $@ $<
	$(call call_fixdep, $(@:.o=.d), $@)

$(OBJ_DIR)/%.o: %.cc $(NEMU_MENUCONFIG_STATE)
	@echo + CXX $<
	@mkdir -p $(dir $@)
	@$(CXX) $(CFLAGS) $(CXXFLAGS) -c -o $@ $<
	$(call call_fixdep, $(@:.o=.d), $@)

# Depencies
-include $(OBJS:.o=.d)

# Some convenient rules

.PHONY: app clean

app: $(BINARY)

$(BINARY):: $(OBJS) $(ARCHIVES)
	@echo + LD $@
	@mkdir -p $(dir $@)
	@$(LD) -o $@ $(OBJS) $(LDFLAGS) $(ARCHIVES) $(LIBS)

clean:
	-rm -rf $(BUILD_DIR)
