AM_SRCS := platform/nemu/trm.c \
           platform/nemu/ioe/ioe.c \
           platform/nemu/ioe/timer.c \
           platform/nemu/ioe/input.c \
           platform/nemu/ioe/uart.c \
           platform/nemu/ioe/gpu.c \
           platform/nemu/ioe/audio.c \
           platform/nemu/ioe/disk.c \
           platform/nemu/mpe.c

CFLAGS += -fdata-sections -ffunction-sections
CFLAGS += -I$(AM_HOME)/am/src/platform/nemu/include
LDSCRIPTS += $(AM_HOME)/scripts/linker.ld
LDFLAGS += --defsym=_pmem_start=0x80000000 --defsym=_entry_offset=0x0
LDFLAGS += --gc-sections -e _start
NEMUFLAGS += -l $(shell dirname $(IMAGE).elf)/nemu-log.txt

MAINARGS_MAX_LEN := 64
MAINARGS_PLACEHOLDER := the_insert-arg_rule_in_Makefile_will_insert_mainargs_here
CFLAGS += -DMAINARGS_MAX_LEN=$(MAINARGS_MAX_LEN) -DMAINARGS_PLACEHOLDER=$(MAINARGS_PLACEHOLDER)

NEMU_HOME ?= $(AM_HOME)/../nemu
NPC_HOME ?= $(AM_HOME)/../npc

ifneq ($(origin host_rebuild),undefined)
  $(error host_rebuild 已删除，请使用 host-rebuild=1)
endif

insert-arg: image
	@python $(AM_HOME)/tools/insert-arg.py $(IMAGE).bin $(MAINARGS_MAX_LEN) $(MAINARGS_PLACEHOLDER) "$(mainargs)"

image: image-dep
	@$(OBJDUMP) -d $(IMAGE).elf > $(IMAGE).txt
	@echo + OBJCOPY "->" $(IMAGE_REL).bin
	@$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $(IMAGE).elf $(IMAGE).bin

ifeq ($(CONFIG_CONSTRUCTION_MANAGED),1)
  # 通用 AM 入口已根据 Config 推导 ARCH，但此时构造可能尚不存在。先完成原子
  # ensure，再递归进入下方保存构造分支以加载 profile、host ABI 与 FPGA 资产。
  .PHONY: npc-construction
  npc-construction:
	@case "$(build)" in ''|0|1) ;; *) echo 'build 只能是 0 或 1' >&2; exit 2;; esac
	@case "$(rebuild)" in ''|0|1) ;; *) echo 'rebuild 只能是 0 或 1' >&2; exit 2;; esac
	@case "$(host-rebuild)" in ''|0|1) ;; *) echo 'host-rebuild 只能是 0 或 1' >&2; exit 2;; esac
	@test "$(strip $(rebuild))" != 1 || test "$(strip $(host-rebuild))" != 1 || { echo 'rebuild=1 已包含 host 重构，不能同时提供 host-rebuild=1' >&2; exit 2; }
	@$(CONSTRUCTION_MANAGER) ensure "$(NPC_HOME)" "$(NPC_CONFIG_FQCN)" \
		"$(if $(filter 1,$(build)),1,0)" "$(if $(filter 1,$(rebuild)),1,0)" "$(if $(filter 1,$(host-rebuild)),1,0)"

  run run-bat gdb: npc-construction
	@$(MAKE) --no-print-directory "$@" ARCH="$(ARCH)" \
		NPC_CONSTRUCTION_DIR="$(NPC_CONSTRUCTION_DIR)" NPC_CONFIG_FQCN="$(NPC_CONFIG_FQCN)" \
		config= version= build= rebuild= host-rebuild= CONFIG_CONSTRUCTION_MANAGED=
else ifneq ($(strip $(NPC_CONSTRUCTION_DIR)),)
  CONSTRUCTION_ENV := $(NPC_CONSTRUCTION_DIR)/construction.env
  CONSTRUCTION_PROFILE := $(NPC_CONSTRUCTION_DIR)/profile.env
  ifeq ($(wildcard $(CONSTRUCTION_ENV)),)
    $(error 构造目录缺少 construction.env：$(NPC_CONSTRUCTION_DIR))
  endif
  ifeq ($(wildcard $(CONSTRUCTION_PROFILE)),)
    $(error 构造目录缺少 profile.env：$(NPC_CONSTRUCTION_DIR))
  endif
  include $(CONSTRUCTION_PROFILE)
  ifneq ($(CONFIG_FQCN),$(NPC_CONFIG_FQCN))
    $(error 构造 profile 为 $(CONFIG_FQCN)，调用方要求 $(NPC_CONFIG_FQCN))
  endif
  EXPECTED_XLEN := $(if $(filter riscv32-nemu,$(ARCH)),32,$(if $(filter riscv64-nemu,$(ARCH)),64,unknown))
  ifneq ($(XLEN),$(EXPECTED_XLEN))
    $(error ARCH/ISA 的 XLEN=$(EXPECTED_XLEN) 与 Config 固定的 XLEN=$(XLEN) 冲突)
  endif
  NEMU_CONSTRUCTION_EXEC := $(NPC_CONSTRUCTION_DIR)/abi/nemu/nemu-exec
  ifeq ($(wildcard $(NEMU_CONSTRUCTION_EXEC)),)
    $(error 构造缺少 NEMU host：$(NEMU_CONSTRUCTION_EXEC))
  endif

  define run_construction
	@set -e; \
	raw_label='$(if $(strip $(NEMU_RUNTIME_LABEL)),$(NEMU_RUNTIME_LABEL),$(NAME))'; \
	runtime_label=$$(printf '%s' "$$raw_label" | sed 's/[^A-Za-z0-9._-]/_/g'); \
	test -n "$$runtime_label" || runtime_label=run; \
	runtime_root="$(NPC_CONSTRUCTION_DIR)/runtime/$$runtime_label"; \
	run_id="$$(date +%s%N)-$$$$"; run_dir="$$runtime_root/$$run_id"; \
	mkdir -p "$$run_dir"; \
	export NEMU_RUNTIME_OUTPUT_DIR="$$run_dir" NEMU_RUNTIME_LABEL="$$runtime_label"; \
	if test "$(NEMU_PERFORMANCE_HTML)" = 1; then \
		export NEMU_CAPSTONE_SO="$(NPC_CONSTRUCTION_DIR)/abi/nemu/lib/libcapstone.so.5"; \
	fi; \
	if test "$(SCOPE)" = fpga; then \
		artifacts="$(NPC_CONSTRUCTION_DIR)/fpga/artifacts"; \
		if test "$(FPGA_BOARD)" = u55c; then \
			export NEMU_FPGA_XCLBIN="$$artifacts/npc-$(FPGA_PLATFORM).xclbin"; \
		elif test "$(FPGA_BOARD)" = zcu102; then \
			set -a; . "$$artifacts/npc-zcu102.env"; set +a; \
		else echo "未知 FPGA 板卡：$(FPGA_BOARD)" >&2; exit 1; fi; \
	fi; \
	status=0; "$(NEMU_CONSTRUCTION_EXEC)" $(NEMUFLAGS) $(1) -e "$(IMAGE).elf" "$(IMAGE).bin" || status=$$?; \
	temporary_link="$$runtime_root/.latest-$$run_id"; \
	ln -s "$$run_id" "$$temporary_link"; mv -Tf "$$temporary_link" "$$runtime_root/latest"; \
	if find "$$run_dir" -maxdepth 1 -type f \( -name 'performance.html' -o -name 'instructions.html' -o -name 'pipeline.html' -o -name 'wave-*.vcd' \) -print -quit | grep -q .; then \
		printf '运行时输出：%s\n' "$$run_dir"; \
	fi; \
	exit $$status
  endef

run: insert-arg
	$(call run_construction,)

run-bat: insert-arg
	$(call run_construction,-b)

gdb: insert-arg
	@gdb -s "$(NEMU_CONSTRUCTION_EXEC)" --args "$(NEMU_CONSTRUCTION_EXEC)" $(NEMUFLAGS) -e "$(IMAGE).elf" "$(IMAGE).bin"
else
  # 未选择 Config 的 AM 工程继续使用普通 NEMU；提供 config=/version= 的通用
  # AM run 会先经上方托管分支转换为保存构造运行。
  NEMU_CONFIG_ROOT ?= $(NEMU_HOME)
  NEMU_BUILD_ROOT ?= $(NEMU_HOME)/build
  NEMU_DEFCONFIG ?= $(if $(filter riscv32,$(ISA)),riscv32-nemu-interpreter-defconfig,$(if $(filter riscv64,$(ISA)),riscv64-nemu-interpreter-defconfig,))

nemu-config:
	@if test ! -f "$(NEMU_CONFIG_ROOT)/.config"; then \
		$(MAKE) -C "$(NEMU_HOME)" "$(NEMU_DEFCONFIG)" NEMU_CONFIG_ROOT="$(NEMU_CONFIG_ROOT)" NEMU_BUILD_ROOT="$(NEMU_BUILD_ROOT)"; \
	fi

run: nemu-config insert-arg
	@$(MAKE) -C "$(NEMU_HOME)" ISA="$(ISA)" run ARGS="$(NEMUFLAGS) -e $(IMAGE).elf" IMG="$(IMAGE).bin" \
		NEMU_CONFIG_ROOT="$(NEMU_CONFIG_ROOT)" NEMU_BUILD_ROOT="$(NEMU_BUILD_ROOT)"

run-bat: nemu-config insert-arg
	@$(MAKE) -C "$(NEMU_HOME)" ISA="$(ISA)" run-bat ARGS="$(NEMUFLAGS) -e $(IMAGE).elf" IMG="$(IMAGE).bin" \
		NEMU_CONFIG_ROOT="$(NEMU_CONFIG_ROOT)" NEMU_BUILD_ROOT="$(NEMU_BUILD_ROOT)"
endif

.PHONY: insert-arg nemu-config run run-bat gdb
