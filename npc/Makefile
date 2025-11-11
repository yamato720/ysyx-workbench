v_files := $(wildcard ./vsrc/*.v)
c_files := $(wildcard ./csrc/*.cpp)

SIM := ALU

obj_dir := ./intermediate

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
	@set -e; \
	if [ -z "$(NAME)" ]; then \
		echo "usage: make gtk NAME=<module_name>"; \
		echo "example: make gtk NAME=new_module"; \
		exit 1; \
	fi; 
	@if [ -f ./out/$(NAME)/wave.vcd ]; then \
		echo "=== gtkwave : ./out/$(NAME)/wave.vcd ==="; \
		gtkwave ./out/$(NAME)/wave.vcd; \
	else \
		echo "error: ./out/$(NAME)/wave.vcd not found. Build with 'make sim'."; \
		exit 1; \
	fi


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

