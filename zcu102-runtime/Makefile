NPC_DIR ?= ../npc
NEMU_SRC_DIR := nemu-src/nemu
AM_SRC_DIR := nemu-src/abstract-machine
AM_KERNELS_SRC_DIR := nemu-src/am-kernels
NAVY_APPS_SRC_DIR := nemu-src/navy-apps
NANOS_LITE_SRC_DIR := nemu-src/nanos-lite
GENERATED_DIR := generated
TEST_ARCH ?= riscv64-nemu
PS_NEMU_BIN := build/bin/zcu102-ps-nemu-sdb
PS_NEMU_BIN_RV64 := build/bin/zcu102-ps-nemu-sdb-rv64
PS_NEMU_BIN_RV32 := build/bin/zcu102-ps-nemu-sdb-rv32
BUSY_BOX_NANO_BIN := build/bin/busy-box-nano
NEMU_REF_SO := $(abspath $(NEMU_SRC_DIR)/build/riscv64-nemu-interpreter-so)
NEMU_REF_OBJ_DIR := $(NEMU_SRC_DIR)/build/obj-riscv64-nemu-interpreter-so
ZCU102_RUNTIME_DEFS := $(abspath include/zcu102_runtime_overrides.h)
ZCU102_NEMU_CFLAGS := -DZCU102_RUNTIME_PS_NEMU=1 -DZCU102_RUNTIME_NEMU_REF=1 -include $(ZCU102_RUNTIME_DEFS)
ZCU102_NEMU_REF_CC := gcc -DZCU102_RUNTIME_NEMU_REF=1 -include $(ZCU102_RUNTIME_DEFS)
NEMU_REF_STAMP := build/.nemu-ref.stamp
NEMU_REF_LOCK := build/.nemu-ref.lock
NEMU_INC_COMMON := -I$(NEMU_SRC_DIR)/include \
                   -I$(NEMU_SRC_DIR)/src/monitor/sdb \
                   -I$(NEMU_SRC_DIR)/src/engine/interpreter
NEMU_INC_RV64 := -Iinclude/riscv64 $(NEMU_INC_COMMON) -I$(NEMU_SRC_DIR)/src/isa/riscv64/include
NEMU_INC_RV32 := -Iinclude/riscv32 $(NEMU_INC_COMMON) -I$(NEMU_SRC_DIR)/src/isa/riscv32/include
NEMU_CFLAGS_COMMON := -std=gnu11 -O2 -MMD -Wall -Werror \
                      -DITRACE_COND=true \
                      $(ZCU102_NEMU_CFLAGS)
NEMU_CFLAGS_RV64 := $(NEMU_CFLAGS_COMMON) $(NEMU_INC_RV64) -D__GUEST_ISA__=riscv64
NEMU_CFLAGS_RV32 := $(NEMU_CFLAGS_COMMON) $(NEMU_INC_RV32) -D__GUEST_ISA__=riscv32
NEMU_LDLIBS := -lreadline -ldl $(shell sdl2-config --libs 2>/dev/null)
NEMU_SRCS_COMMON := \
  sw/src/ps_nemu_sdb.c \
  $(NEMU_SRC_DIR)/src/device/device.c \
  $(NEMU_SRC_DIR)/src/device/alarm.c \
  $(NEMU_SRC_DIR)/src/device/intr.c \
  $(NEMU_SRC_DIR)/src/device/serial.c \
  $(NEMU_SRC_DIR)/src/device/timer.c \
  $(NEMU_SRC_DIR)/src/device/keyboard.c \
  $(NEMU_SRC_DIR)/src/device/vga.c \
  $(NEMU_SRC_DIR)/src/device/audio.c \
  $(NEMU_SRC_DIR)/src/device/disk.c \
  $(NEMU_SRC_DIR)/src/engine/interpreter/init.c \
  $(NEMU_SRC_DIR)/src/engine/interpreter/hostcall.c \
  $(NEMU_SRC_DIR)/src/device/io/map.c \
  $(NEMU_SRC_DIR)/src/device/io/mmio.c \
  $(NEMU_SRC_DIR)/src/device/io/port-io.c \
  $(NEMU_SRC_DIR)/src/cpu/difftest/ref.c \
  $(NEMU_SRC_DIR)/src/cpu/difftest/dut.c \
  $(NEMU_SRC_DIR)/src/cpu/cpu-exec.c \
  $(NEMU_SRC_DIR)/src/monitor/monitor.c \
  $(NEMU_SRC_DIR)/src/monitor/sdb/expr.c \
  $(NEMU_SRC_DIR)/src/monitor/sdb/sdb.c \
  sw/src/ps_elfreader.c \
  $(NEMU_SRC_DIR)/src/monitor/sdb/watchpoint.c \
  $(NEMU_SRC_DIR)/src/utils/state.c \
  $(NEMU_SRC_DIR)/src/utils/log.c \
  $(NEMU_SRC_DIR)/src/utils/timer.c \
  $(NEMU_SRC_DIR)/src/memory/vaddr.c \
  $(NEMU_SRC_DIR)/src/memory/paddr.c
NEMU_SRCS_RV64 := \
  $(NEMU_SRCS_COMMON) \
  $(NEMU_SRC_DIR)/src/isa/riscv64/system/intr.c \
  $(NEMU_SRC_DIR)/src/isa/riscv64/system/mmu.c \
  $(NEMU_SRC_DIR)/src/isa/riscv64/difftest/dut.c \
  $(NEMU_SRC_DIR)/src/isa/riscv64/inst.c \
  $(NEMU_SRC_DIR)/src/isa/riscv64/reg.c \
  $(NEMU_SRC_DIR)/src/isa/riscv64/init.c \
  $(NEMU_SRC_DIR)/src/isa/riscv64/logo.c
NEMU_SRCS_RV32 := \
  $(NEMU_SRCS_COMMON) \
  $(NEMU_SRC_DIR)/src/isa/riscv32/system/intr.c \
  $(NEMU_SRC_DIR)/src/isa/riscv32/system/mmu.c \
  $(NEMU_SRC_DIR)/src/isa/riscv32/difftest/dut.c \
  $(NEMU_SRC_DIR)/src/isa/riscv32/inst.c \
  $(NEMU_SRC_DIR)/src/isa/riscv32/reg.c \
  $(NEMU_SRC_DIR)/src/isa/riscv32/init.c \
  $(NEMU_SRC_DIR)/src/isa/riscv32/logo.c

.PHONY: chisel selftest launcher launch clean-generated build-ps build-ps-rv64 build-ps-rv32 run-ps build-busy-box-nano busy-box-nano list-cpu-tests build-cpu-test run-cpu-test build-cpp nemu-src-check nemu-ref force-nemu-ref build-so build-all lint-sw lint-rtl lint-selftest check check-selftest

launcher:
	@./tools/run.py --interactive

launch:
	@./tools/run.py $(if $(TASK),$(TASK),--interactive)

chisel:
	@echo "=== Generating ZCU102 NPC debugger from Chisel ==="
	@rm -rf $(GENERATED_DIR)
	@cd $(NPC_DIR) && sbt "root/runMain zcu102.ElaborateZCU102NPCDebugger"
	@echo "Generated Verilog files in $(GENERATED_DIR)"

selftest:
	@echo "=== Generating ZCU102 NPC self-test from Chisel ==="
	@rm -rf $(GENERATED_DIR)
	@cd $(NPC_DIR) && sbt "root/runMain zcu102.ElaborateZCU102NPCSelfTest"
	@echo "Generated Verilog files in $(GENERATED_DIR)"

build-ps: build-ps-rv64 build-ps-rv32

build-ps-rv64: nemu-src-check
	@mkdir -p $(dir $(PS_NEMU_BIN_RV64))
	@gcc $(NEMU_CFLAGS_RV64) $(NEMU_SRCS_RV64) $(NEMU_LDLIBS) -o $(PS_NEMU_BIN_RV64)
	@ln -sfn $(notdir $(PS_NEMU_BIN_RV64)) $(PS_NEMU_BIN)

build-ps-rv32: nemu-src-check
	@mkdir -p $(dir $(PS_NEMU_BIN_RV32))
	@gcc $(NEMU_CFLAGS_RV32) $(NEMU_SRCS_RV32) $(NEMU_LDLIBS) -o $(PS_NEMU_BIN_RV32)

run-ps: build-ps
	@$(PS_NEMU_BIN) $(ARGS)

build-busy-box-nano:
	@mkdir -p $(dir $(BUSY_BOX_NANO_BIN))
	@gcc -std=gnu11 -O2 -Wall -Wextra -DZCU102_ROOT=\"$(abspath .)\" \
		sw/src/busy_box_nano.c -o $(BUSY_BOX_NANO_BIN)

busy-box-nano: build-ps build-busy-box-nano
	@$(BUSY_BOX_NANO_BIN)

list-cpu-tests:
	@$(MAKE) -s build-busy-box-nano
	@$(BUSY_BOX_NANO_BIN) --list

build-cpu-test: build-busy-box-nano
	@test -n "$(TEST)" || { echo "usage: make build-cpu-test TEST=cpu-tests/add|nanos-lite/tests/hello [TEST_ARCH=riscv64-nemu|riscv64-npc|riscv32-nemu|riscv32-npc] [REBUILD=1] [MAINARGS='...']"; exit 2; }
	@$(BUSY_BOX_NANO_BIN) --build "$(TEST)" --arch "$(TEST_ARCH)" $(if $(REBUILD),--rebuild,) $(if $(MAINARGS),--mainargs "$(MAINARGS)",)

run-cpu-test: build-ps build-busy-box-nano
	@test -n "$(TEST)" || { echo "usage: make run-cpu-test TEST=cpu-tests/add|nanos-lite/tests/hello [TEST_ARCH=riscv64-nemu|riscv64-npc|riscv32-nemu|riscv32-npc] [BATCH=1] [REBUILD=1] [MAINARGS='...']"; exit 2; }
	@$(BUSY_BOX_NANO_BIN) --run "$(TEST)" --arch "$(TEST_ARCH)" $(if $(BATCH),--batch,) $(if $(REBUILD),--rebuild,) $(if $(MAINARGS),--mainargs "$(MAINARGS)",)

nemu-src-check:
	@test -L $(NEMU_SRC_DIR)
	@test -L $(AM_SRC_DIR)
	@test -L $(AM_KERNELS_SRC_DIR)
	@test -L $(NAVY_APPS_SRC_DIR)
	@test -L $(NANOS_LITE_SRC_DIR)

$(NEMU_REF_STAMP): $(ZCU102_RUNTIME_DEFS) $(NEMU_SRC_DIR)/Makefile $(NEMU_SRC_DIR)/src/cpu/difftest/ref.c | nemu-src-check
	@mkdir -p $(dir $@)
	@flock $(NEMU_REF_LOCK) sh -c 'rm -rf "$(NEMU_REF_OBJ_DIR)" "$(NEMU_REF_SO)" && $(MAKE) -C "$(NEMU_SRC_DIR)" ISA=riscv64 SHARE=1 ZCU102_RUNTIME_NEMU_REF=1 CC="$(ZCU102_NEMU_REF_CC)" && touch "$(NEMU_REF_STAMP)"'

nemu-ref: $(NEMU_REF_STAMP)

force-nemu-ref: nemu-src-check
	@mkdir -p $(dir $(NEMU_REF_STAMP))
	@flock $(NEMU_REF_LOCK) sh -c 'rm -rf "$(NEMU_REF_OBJ_DIR)" "$(NEMU_REF_SO)" "$(NEMU_REF_STAMP)"'
	@$(MAKE) nemu-ref

build-cpp: build-ps build-busy-box-nano

build-so: nemu-ref

build-all: build-cpp build-so

lint-sw: build-cpp

lint-rtl: chisel
	@verilator --lint-only --sv $(GENERATED_DIR)/ZCU102NPCDebugger.sv

lint-selftest: selftest
	@verilator --lint-only --sv $(GENERATED_DIR)/ZCU102NPCSelfTest.sv

check: lint-sw lint-rtl

check-selftest: lint-sw lint-selftest

clean-generated:
	@rm -rf $(GENERATED_DIR)
