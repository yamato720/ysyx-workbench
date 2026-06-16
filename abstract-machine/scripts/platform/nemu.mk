AM_SRCS := platform/nemu/trm.c \
           platform/nemu/ioe/ioe.c \
           platform/nemu/ioe/timer.c \
           platform/nemu/ioe/input.c \
           platform/nemu/ioe/uart.c \
           platform/nemu/ioe/gpu.c \
           platform/nemu/ioe/audio.c \
           platform/nemu/ioe/disk.c \
           platform/nemu/mpe.c

CFLAGS    += -fdata-sections -ffunction-sections
CFLAGS    += -I$(AM_HOME)/am/src/platform/nemu/include
LDSCRIPTS += $(AM_HOME)/scripts/linker.ld
LDFLAGS   += --defsym=_pmem_start=0x80000000 --defsym=_entry_offset=0x0
LDFLAGS   += --gc-sections -e _start
NEMUFLAGS += -l $(shell dirname $(IMAGE).elf)/nemu-log.txt

MAINARGS_MAX_LEN = 64
MAINARGS_PLACEHOLDER = the_insert-arg_rule_in_Makefile_will_insert_mainargs_here
CFLAGS += -DMAINARGS_MAX_LEN=$(MAINARGS_MAX_LEN) -DMAINARGS_PLACEHOLDER=$(MAINARGS_PLACEHOLDER)
USENPC = 0
TRACE_NEMU = 0

NPC_HOME ?= $(AM_HOME)/../npc
NPC_XLEN ?= $(if $(filter riscv32%,$(ISA)),32,64)
NEMU_DEFCONFIG ?= $(if $(filter riscv32,$(ISA)),riscv32-nemu-interpreter-defconfig,$(if $(filter riscv64,$(ISA)),riscv64-nemu-interpreter-defconfig,))

npc-lib:
	$(MAKE) -C $(NPC_HOME) chisel-cpu-lib NPC_XLEN=$(NPC_XLEN)

nemu-config:
	@if [ -n "$(NEMU_DEFCONFIG)" ]; then \
		$(MAKE) -C $(NEMU_HOME) $(NEMU_DEFCONFIG); \
	fi

insert-arg: image
	@python $(AM_HOME)/tools/insert-arg.py $(IMAGE).bin $(MAINARGS_MAX_LEN) $(MAINARGS_PLACEHOLDER) "$(mainargs)"

image: image-dep
	@$(OBJDUMP) -d $(IMAGE).elf > $(IMAGE).txt
	@echo + OBJCOPY "->" $(IMAGE_REL).bin
	@$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $(IMAGE).elf $(IMAGE).bin

run: nemu-config insert-arg
	$(MAKE) -C $(NEMU_HOME) clean
	$(MAKE) -C $(NEMU_HOME) ISA=$(ISA) run ARGS="$(NEMUFLAGS) -e $(IMAGE).elf" IMG=$(IMAGE).bin

run-npc: nemu-config npc-lib insert-arg
	$(MAKE) -C $(NEMU_HOME) clean
	$(MAKE) -C $(NEMU_HOME) ISA=$(ISA) run USENPC=1 ARGS="$(NEMUFLAGS) -e $(IMAGE).elf" IMG=$(IMAGE).bin

run-npc-bat: nemu-config npc-lib insert-arg
	$(MAKE) -C $(NEMU_HOME) clean
	env -i HOME='$(HOME)' PATH='$(PATH)' NEMU_HOME='$(NEMU_HOME)' NPC_HOME='$(NPC_HOME)' AM_HOME='$(AM_HOME)' \
		$(MAKE) -j1 -C $(NEMU_HOME) ISA=$(ISA) run-npc-bat USENPC=1 ARGS="$(NEMUFLAGS) -b" IMG=$(IMAGE).bin

run-bat: nemu-config insert-arg
	$(MAKE) -C $(NEMU_HOME) clean
	$(MAKE) -C $(NEMU_HOME) ISA=$(ISA) run-bat ARGS="$(NEMUFLAGS) -b" IMG=$(IMAGE).bin


run-npc-update: nemu-config insert-arg
	$(MAKE) -C $(NPC_HOME) chisel-cpu-lib NPC_XLEN=$(NPC_XLEN) TRACE_NEMU=1
	rm -f $(NEMU_HOME)/build/$(ISA)-nemu-interpreter
	$(MAKE) -C $(NEMU_HOME) ISA=$(ISA) USENPC=1

run-npc-update-trace: insert-arg
	$(MAKE) -C $(NPC_HOME) chisel-cpu-lib NPC_XLEN=$(NPC_XLEN) TRACE_NEMU=1

gdb: nemu-config insert-arg
	$(MAKE) -C $(NEMU_HOME) clean
	$(MAKE) -C $(NEMU_HOME) ISA=$(ISA) gdb ARGS="$(NEMUFLAGS)" IMG=$(IMAGE).bin

gdb-npc: nemu-config insert-arg
	$(MAKE) -C $(NEMU_HOME) clean
	$(MAKE) -C $(NEMU_HOME) ISA=$(ISA) gdb USENPC=1 ARGS="$(NEMUFLAGS) -e $(IMAGE).elf" IMG=$(IMAGE).bin




.PHONY: insert-arg npc-lib nemu-config
