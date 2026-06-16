REPO_PATH = repo
CONFIG_FILE = config/nanos-lite.config
PATCH_FILE = $(abspath patch/diff.patch)
ifeq ($(wildcard repo/include/busybox.h),)
  $(shell git clone --depth=1 -b 1_32_stable git://git.busybox.net/busybox $(REPO_PATH))
  $(shell cd $(REPO_PATH) && git apply $(PATCH_FILE))
  $(shell cp $(CONFIG_FILE) $(REPO_PATH)/.config)
endif

NAME = busybox
include $(NAVY_HOME)/Makefile

ifeq ($(ISA), native)
CFLAGS += -I$(WORK_DIR)/include/native/
else
CFLAGS += -I$(WORK_DIR)/include/navy/ -D__GLIBC__ -fdata-sections -ffunction-sections -nostdlib
comma = ,
LDFLAGS := $(addprefix -Wl$(comma), $(LDFLAGS))
export LDFLAGS
endif

export CFLAGS
export CROSS_COMPILE

$(DST_DIR)/navy_argv.o: navy_argv.c
	@mkdir -p $(dir $@) && echo + CC $<
	@$(CC) -std=gnu11 $(CFLAGS) -c -o $@ $<

# The busybox Makefile can not correctly handle the dependency when
# $(LINKAGE) are updated. If it is the case, perform a fresh build.
$(APP): $(DST_DIR)/navy_argv.o force;
	cp $(CONFIG_FILE) $(REPO_PATH)/.config
	rm -f $(REPO_PATH)/busybox $(REPO_PATH)/busybox_unstripped
	$(MAKE) -C $(REPO_PATH) silentoldconfig
	$(MAKE) -C $(REPO_PATH) LINKAGE="$(LINKAGE) $(DST_DIR)/navy_argv.o"
	cp $(REPO_PATH)/busybox $@

force: ;

menuconfig:
	$(MAKE) -C $(REPO_PATH) $@

install: $(APP)
	@echo + INSTALL "->" $(NAME)
	@mkdir -p $(NAVY_HOME)/fsimg/bin
	@cp $(APP) $(NAVY_HOME)/fsimg/bin/$(NAME)

clean: clean-busybox

clean-busybox:
	$(MAKE) -C $(REPO_PATH) clean

.PHONY: force menuconfig clean-busybox
