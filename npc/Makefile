v_files := $(wildcard ./vsrc/*.v)
c_files := $(wildcard ./csrc/*.cpp)

SIM := xor_switch

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
sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@if [ -x ./out/$(SIM)/exec ]; then \
		echo "=== running: ./out/$(SIM)/exec ==="; \
		./out/$(SIM)/exec; \
	elif [ -f ./out/$(SIM)/exec ]; then \
		echo "=== ./out/$(SIM)/exec exists but is not executable ==="; \
		exit 1; \
	else \
		echo "error: ./out/$(SIM)/exec not found. Build with 'make'."; \
		exit 1; \
	fi

gtk:
	@if [ -f ./out/$(SIM)/wave.vcd ]; then \
		echo "=== gtkwave : ./out/$(SIM)/wave.vcd ==="; \
		gtkwave ./out/$(SIM)/wave.vcd; \
	else \
		echo "error: ./out/$(SIM)/wave.vcd not found. Build with 'make sim'."; \
		exit 1; \
	fi


clean:
	rm -rf ./out/*
	rm -rf ./intermediate/*

.PHONY: all sim clean gtk


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




include ../Makefile

