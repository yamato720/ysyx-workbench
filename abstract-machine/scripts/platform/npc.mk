AM_SRCS := riscv/npc/start.S \
           riscv/npc/trm.c \
           riscv/npc/ioe.c \
           riscv/npc/timer.c \
           riscv/npc/input.c \
           riscv/npc/cte.c \
           riscv/npc/trap.S \
           platform/dummy/vme.c \
           platform/dummy/mpe.c

NPC_HOME ?= $(AM_HOME)/../npc

CFLAGS    += -fdata-sections -ffunction-sections
LDSCRIPTS += $(AM_HOME)/scripts/linker.ld
LDFLAGS   += --defsym=_pmem_start=0x80000000 --defsym=_entry_offset=0x0
LDFLAGS   += --gc-sections -e _start

MAINARGS_MAX_LEN = 64
MAINARGS_PLACEHOLDER = the_insert-arg_rule_in_Makefile_will_insert_mainargs_here
CFLAGS += -DMAINARGS_MAX_LEN=$(MAINARGS_MAX_LEN) -DMAINARGS_PLACEHOLDER=$(MAINARGS_PLACEHOLDER)

insert-arg: image
	@python $(AM_HOME)/tools/insert-arg.py $(IMAGE).bin $(MAINARGS_MAX_LEN) $(MAINARGS_PLACEHOLDER) "$(mainargs)"

image: image-dep
	@$(OBJDUMP) -d $(IMAGE).elf > $(IMAGE).txt
	@echo + OBJCOPY "->" $(IMAGE_REL).bin
	@$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $(IMAGE).elf $(IMAGE).bin

# Default: use Chisel CPU
run: insert-arg
	$(MAKE) -C $(NPC_HOME) run-chisel IMG=$(IMAGE).bin

# Alternative: use original Verilog CPU
run-verilog: insert-arg
	$(MAKE) -C $(NPC_HOME) run-cpu IMG=$(IMAGE).bin

# Run on NEMU (for comparison/debugging)
NEMU_HOME ?= $(AM_HOME)/../nemu
NEMUFLAGS += -l $(shell dirname $(IMAGE).elf)/nemu-log.txt

run-nemu: insert-arg
	$(MAKE) -C $(NEMU_HOME) ISA=riscv64 run ARGS="$(NEMUFLAGS)" IMG=$(IMAGE).bin

run-nemu-bat: insert-arg
	$(MAKE) -C $(NEMU_HOME) ISA=riscv64 run-bat ARGS="$(NEMUFLAGS)" IMG=$(IMAGE).bin

gdb-nemu: insert-arg
	$(MAKE) -C $(NEMU_HOME) ISA=riscv64 gdb ARGS="$(NEMUFLAGS)" IMG=$(IMAGE).bin

.PHONY: insert-arg run-verilog run-nemu run-nemu-bat gdb-nemu
