# NPC - RISC-V CPU Simulator with Verilator
v_files := $(wildcard ./vsrc/*.v)
c_files := $(wildcard ./csrc/*.cpp)

SIM := ALU
obj_dir := ./intermediate
CHISEL_OUT := ./generated
CHISEL_DPI_OUT := ./generated-dpi

# ============== Chisel Build ==============

# Generate Verilog from Chisel (without DPI-C, for testing)
chisel:
	@echo "=== Generating Verilog from Chisel ==="
	cd $(shell pwd) && sbt "root/runMain scpu.Elaborate"
	@echo "Generated Verilog files in $(CHISEL_OUT)"

# Generate Verilog from Chisel with DPI-C support (for Verilator simulation)
chisel-dpi:
	@echo "=== Generating DPI-C mode Verilog from Chisel ==="
	cd $(shell pwd) && sbt "root/runMain scpu.ElaborateDPI"
	@echo "Generated DPI-C Verilog files in $(CHISEL_DPI_OUT)"
	@cp ./chisel/src/main/resources/rtl/shell/*.v $(CHISEL_DPI_OUT)

# Generate specific Chisel module
chisel-module:
	@if [ -z "$(MODULE)" ]; then \
		echo "Usage: make chisel-module MODULE=<ModuleName>"; \
		exit 1; \
	fi
	@echo "=== Generating $(MODULE) from Chisel ==="
	cd $(shell pwd) && sbt "root/runMain scpu.Elaborate$(MODULE)"

# NEMU reference for DiffTest
NEMU_HOME ?= $(shell pwd)/../nemu
NEMU_REF_SO = $(NEMU_HOME)/build/riscv64-nemu-interpreter-so

# Build Chisel CPU with DPI-C support for Verilator simulation (standalone executable)
chisel-cpu: chisel-dpi
	@echo "=== Building Chisel CPU with DPI-C (standalone) ==="
	@mkdir -p $(obj_dir)/chisel-cpu
	@mkdir -p ./out/chisel-cpu
	verilator -Wall --cc --exe --build -j $(shell nproc) \
		--trace \
		--top-module CPU \
		-Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM -Wno-CASEINCOMPLETE -Wno-WIDTH \
		-Wno-UNOPTFLAT -Wno-LATCH -Wno-DECLFILENAME \
		-CFLAGS "-I$(shell pwd)/csrc -DNPC_STANDALONE" \
		-LDFLAGS "-ldl" \
		-Mdir $(obj_dir)/chisel-cpu \
		-I$(CHISEL_DPI_OUT) \
		$(CHISEL_DPI_OUT)/*.v ./csrc/npc_main.cpp ./csrc/npc_core.cpp ./csrc/pmem.cpp \
	@mv $(obj_dir)/chisel-cpu/VCPU_64_DPI ./out/chisel-cpu/npc-exec 2>/dev/null || true
	@echo "Built: ./out/chisel-cpu/npc-exec"

# Build NPC core as object files for NEMU integration
chisel-cpu-lib: chisel-dpi
	@echo "=== Building Chisel CPU library for NEMU ==="
	@mkdir -p $(obj_dir)/chisel-cpu-lib
	@mkdir -p ./out/chisel-cpu-lib
	@if [ "$(TRACE_NEMU)" = "0" ] || [ -z "$(TRACE_NEMU)" ]; then \
		echo "Building without NEMU trace support"; \
		verilator -Wall --cc -j $(shell nproc) \
			--trace \
			--top-module CPU \
			-Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM -Wno-CASEINCOMPLETE -Wno-WIDTH \
			-Wno-UNOPTFLAT -Wno-LATCH -Wno-DECLFILENAME \
			-CFLAGS "-I$(shell pwd)/csrc -fPIC" \
			-Mdir $(obj_dir)/chisel-cpu-lib \
			-I$(CHISEL_DPI_OUT) \
			$(CHISEL_DPI_OUT)/*.v; \
	else \
		echo "Building with NEMU trace support"; \
		verilator -Wall --cc -j $(shell nproc) \
			--trace \
			--top-module CPU \
			-Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM -Wno-CASEINCOMPLETE -Wno-WIDTH \
			-Wno-UNOPTFLAT -Wno-LATCH -Wno-DECLFILENAME \
			-CFLAGS "-I$(shell pwd)/csrc -fPIC -DTRACE_NEMU" \
			-Mdir $(obj_dir)/chisel-cpu-lib \
			-I$(CHISEL_DPI_OUT) \
			$(CHISEL_DPI_OUT)/*.v; \
	fi
	@echo "=== Compiling Verilator generated CPU sources ==="
	@cd $(obj_dir)/chisel-cpu-lib && \
	VERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator && \
	for f in VCPU*.cpp; do \
		g++ -c -fPIC -I. -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
			-std=c++14 -faligned-new "$$f" & \
	done; \
	wait
	@echo "=== Compiling NPC core objects ==="
	@VERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator && \
	if [ "$(TRACE_NEMU)" = "0" ] || [ -z "$(TRACE_NEMU)" ]; then \
		g++ -c -fPIC -I$(shell pwd)/csrc -I$(obj_dir)/chisel-cpu-lib \
			-I$$VERILATOR_ROOT/include \
			-I$$VERILATOR_ROOT/include/vltstd \
			-std=c++14 \
			./csrc/npc_core.cpp -o $(obj_dir)/chisel-cpu-lib/npc_core.o & \
		g++ -c -fPIC -I$(shell pwd)/csrc -I$(obj_dir)/chisel-cpu-lib \
			-I$$VERILATOR_ROOT/include \
			-I$$VERILATOR_ROOT/include/vltstd \
			-std=c++14 \
			./csrc/pmem.cpp -o $(obj_dir)/chisel-cpu-lib/pmem.o & \
		wait; \
	else \
		g++ -c -fPIC -I$(shell pwd)/csrc -I$(obj_dir)/chisel-cpu-lib \
			-I$$VERILATOR_ROOT/include \
			-I$$VERILATOR_ROOT/include/vltstd \
			-std=c++14 -DTRACE_NEMU \
			./csrc/npc_core.cpp -o $(obj_dir)/chisel-cpu-lib/npc_core.o & \
		g++ -c -fPIC -I$(shell pwd)/csrc -I$(obj_dir)/chisel-cpu-lib \
			-I$$VERILATOR_ROOT/include \
			-I$$VERILATOR_ROOT/include/vltstd \
			-std=c++14 -DTRACE_NEMU \
			./csrc/pmem.cpp -o $(obj_dir)/chisel-cpu-lib/pmem.o & \
		wait; \
	fi
	@echo "=== Compiling Verilator runtime objects ==="
	@VERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator; \
	g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 -faligned-new \
		$$VERILATOR_ROOT/include/verilated.cpp -o $(obj_dir)/chisel-cpu-lib/verilated.o & \
	g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 -faligned-new \
		$$VERILATOR_ROOT/include/verilated_dpi.cpp -o $(obj_dir)/chisel-cpu-lib/verilated_dpi.o & \
	g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 -faligned-new \
		$$VERILATOR_ROOT/include/verilated_vcd_c.cpp -o $(obj_dir)/chisel-cpu-lib/verilated_vcd_c.o & \
	g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 -faligned-new \
		$$VERILATOR_ROOT/include/verilated_threads.cpp -o $(obj_dir)/chisel-cpu-lib/verilated_threads.o & \
	wait
	@echo "Built NPC library objects in $(obj_dir)/chisel-cpu-lib/"

# Run Chisel CPU simulation with image
run-chisel: chisel-cpu
	@if [ -z "$(IMG)" ]; then \
		echo "Usage: make run-chisel IMG=<path/to/image.bin>"; \
		exit 1; \
	fi
	@echo "=== Running Chisel NPC with image: $(IMG) ==="
	cd ./out/chisel-cpu && ./npc-exec $(IMG)

# Run with DiffTest (compare with NEMU)
run-difftest: chisel-cpu
	@if [ -z "$(IMG)" ]; then \
		echo "Usage: make run-difftest IMG=<path/to/image.bin>"; \
		exit 1; \
	fi
	@if [ ! -f "$(NEMU_REF_SO)" ]; then \
		echo "Error: NEMU reference not found at $(NEMU_REF_SO)"; \
		echo "Please build NEMU with: make -C $(NEMU_HOME) ISA=riscv64 SHARE=1"; \
		exit 1; \
	fi
	@echo "=== Running NPC with DiffTest (REF: NEMU) ==="
	cd ./out/chisel-cpu && ./npc-exec -d $(NEMU_REF_SO) $(IMG)

.PHONY: chisel chisel-dpi chisel-module chisel-cpu chisel-cpu-lib run-chisel run-difftest

# ============== Original Verilog CPU ==============

# Build CPU with DPI-C support
cpu: ./vsrc/cpu.v ./csrc/main.cpp ./csrc/pmem.cpp
	@echo "=== Building NPC CPU with DPI-C ==="
	@mkdir -p $(obj_dir)/cpu
	@mkdir -p ./out/cpu
	verilator -Wall --cc --exe --build -j $(shell nproc) \
		--trace \
		-Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM -Wno-CASEINCOMPLETE \
		-CFLAGS "-I$(shell pwd)/csrc" \
		-Mdir $(obj_dir)/cpu \
		./vsrc/cpu.v ./csrc/main.cpp ./csrc/pmem.cpp
	@mv $(obj_dir)/cpu/Vcpu ./out/cpu/npc-exec
	@echo "Built: ./out/cpu/npc-exec"

# Run CPU simulation with image
run-cpu: cpu
	@if [ -z "$(IMG)" ]; then \
		echo "Usage: make run-cpu IMG=<path/to/image.bin>"; \
		exit 1; \
	fi
	@echo "=== Running NPC with image: $(IMG) ==="
	cd ./out/cpu && ./npc-exec $(IMG)

.PHONY: cpu run-cpu

all: $(v_files) $(c_files)
	@mkdir -p $(obj_dir)
	@mkdir -p ./out
	@for v in $(v_files); do \
		base=$$(basename $$v .v); \
		c=./csrc/$$base.cpp; \
		if [ -f $$c ]; then \
			echo "=== build: $$base ==="; \
			mkdir -p $(obj_dir)/$$base; \
			verilator -Wall --cc $$v --exe $$c -Mdir $(obj_dir)/$$base --trace ; \
			$(MAKE) -C $(obj_dir)/$$base -f V$$base.mk -j$$(nproc) || exit 1; \
			mkdir -p ./out/$$base; \
			mv $(obj_dir)/$$base/V$$base ./out/$$base/exec; \
		else \
			echo "warning: csrc for $$base not found ($$c)"; \
		fi; \
	done

build:
	@set -e; \
	if [ -z "$(NAME)" ]; then \
		echo "usage: make build NAME=<module_name>"; \
		echo "example: make build NAME=new_module"; \
		exit 1; \
	fi; \
	base="$(NAME)"; \
	v="./vsrc/$${base}.v"; \
	c="./csrc/$${base}.cpp"; \
	if [ ! -f "$$v" ]; then \
		echo "error: vsrc file $$v not found"; \
		exit 1; \
	fi; \
	if [ ! -f "$$c" ]; then \
		echo "error: csrc file $$c not found"; \
		exit 1; \
	fi; \
	echo "=== build: $$base ==="; \
	mkdir -p $(obj_dir)/$$base; \
	verilator -Wall --cc $$v --exe $$c -Mdir $(obj_dir)/$$base --trace ; \
	$(MAKE) -C $(obj_dir)/$$base -f V$$base.mk -j$$(nproc) || exit 1; \
	mkdir -p ./out/$$base; \
	mv $(obj_dir)/$$base/V$$base ./out/$$base/exec;

check:
	@mkdir -p $(obj_dir)
	@mkdir -p ./out
	@for v in $(v_files); do \
		base=$$(basename $$v .v); \
		c=./csrc/$$base.cpp; \
		if [ -f $$c ]; then \
			echo "=== check: $$base ==="; \
			mkdir -p $(obj_dir)/$$base; \
			verilator -Wall --cc $$v --exe $$c -Mdir $(obj_dir)/$$base -lint-only --trace ; \
			$(MAKE) -C $(obj_dir)/$$base -f V$$base.mk -j$$(nproc) || exit 1; \
			mkdir -p ./out/$$base; \
			mv $(obj_dir)/$$base/V$$base ./out/$$base/exec; \
		else \
			echo "warning: csrc for $$base not found ($$c)"; \
		fi; \
	done
sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@set -e; \
	if [ -z "$(NAME)" ]; then \
		echo "usage: make sim NAME=<module_name>"; \
		echo "example: make sim NAME=new_module"; \
		exit 1; \
	fi; 
	@if [ -x ./out/$(NAME)/exec ]; then \
		echo "=== running: ./out/$(NAME)/exec ==="; \
		./out/$(NAME)/exec; \
	elif [ -f ./out/$(NAME)/exec ]; then \
		echo "=== ./out/$(NAME)/exec exists but is not executable ==="; \
		exit 1; \
	else \
		echo "error: ./out/$(NAME)/exec not found. Start build it."; \
		make build; \
		echo "=== running: ./out/$(NAME)/exec ==="; \
		./out/$(NAME)/exec; \
	fi

gtk:
	gtkwave ./out/chisel-cpu/wave.vcd 


clean:
	rm -rf ./out/*
	rm -rf ./intermediate/*

.PHONY: all sim clean gtk check build


# Create a new pair of files: vsrc/<NAME>.v and csrc/<NAME>.cpp with boilerplate
# Usage: make create NAME=<module_name>

create:
	@set -e; \
	if [ -z "$(NAME)" ]; then \
		echo "usage: make create NAME=<module_name>"; \
		echo "example: make create NAME=new_module"; \
		exit 1; \
	fi; \
	base="$(NAME)"; \
	vfile="./vsrc/$${base}.v"; \
	cfile="./csrc/$${base}.cpp"; \
	if [ -e "$$vfile" ]; then \
		echo "skip: $$vfile already exists"; \
	else \
		touch $$vfile; \
		touch $$cfile; \
	fi; \
	echo "created: $$vfile and $$cfile";

.PHONY: create

help:
	@echo "Makefile targets:"
	@echo "  all    - Build all Verilog modules and their C++ testbenches"
	@echo "  check  - Lint all Verilog modules and their C++ testbenches"
	@echo "  sim    - Run the simulation for the module specified by SIM variable (default: xor_switch)"
	@echo "  gtk    - Open GTKWave for the waveform of the module specified by SIM variable (default: xor_switch)"
	@echo "  clean  - Clean all build artifacts"
	@echo "  create - Create new Verilog and C++ files with boilerplate (usage: make create NAME=<module_name>)"
	@echo "  list   - List all available Verilog modules and their C++ testbenches"

list:
	@echo "Available Verilog modules and their C++ testbenches:"
	@for v in $(v_files); do \
		base=$$(basename $$v .v); \
		c=./csrc/$$base.cpp; \
		if [ -f $$c ]; then \
			echo "  $$base"; \
		fi; \
	done

.PHONY: help list



include ../Makefile
