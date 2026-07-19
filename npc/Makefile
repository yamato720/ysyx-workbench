# NPC - RISC-V CPU Simulator with Verilator
.DEFAULT_GOAL := config-list
obj_dir := ./intermediate
CHISEL_OUT := ./generated
CHISEL_DPI_OUT := ./generated-dpi
include ./config-catalog.mk
include ./fpga/config-select.mk

# 统一构造器把 Scala 生成的 profile 作为底层 recipe 的唯一结构输入。变量名
# `NPC_*` 仅保留为本 Makefile 内部适配层，不再接受公开命令行覆盖。
ifeq ($(INTERNAL_CONSTRUCTION),1)
ifneq ($(strip $(CONSTRUCTION_PROFILE)),)
  ifeq ($(wildcard $(CONSTRUCTION_PROFILE)),)
    $(error 构造 profile 不存在：$(CONSTRUCTION_PROFILE))
  endif
  include $(CONSTRUCTION_PROFILE)
  override NPC_XLEN := $(XLEN)
  override NPC_TARGET := $(TARGET)
  override NPC_PIPELINE := $(PIPELINE)
  override NPC_INTERLOCK := $(INTERLOCK)
  override NPC_ID_FWD := $(ID_FWD)
  override NPC_EX_FWD := $(EX_FWD)
  override NPC_F := $(F)
  override NPC_ARITH_BACKEND := $(ARITH_BACKEND)
  override NPC_ARITH_OUTPUT_FIFO := $(ARITH_OUTPUT_FIFO)
  override NPC_MUL_CYCLES := $(MUL_CYCLES)
  override NPC_MUL_II := $(MUL_II)
  override NPC_DIV_CYCLES := $(DIV_CYCLES)
  override NPC_DIV_II := $(DIV_II)
  override NPC_FADD_CYCLES := $(FADD_CYCLES)
  override NPC_FADD_II := $(FADD_II)
  override NPC_FMUL_CYCLES := $(FMUL_CYCLES)
  override NPC_FMUL_II := $(FMUL_II)
  override NPC_FDIV_CYCLES := $(FDIV_CYCLES)
  override NPC_FDIV_II := $(FDIV_II)
  override NPC_FFMA_CYCLES := $(FFMA_CYCLES)
  override NPC_FFMA_II := $(FFMA_II)
  override NPC_FSQRT_CYCLES := $(FSQRT_CYCLES)
  override NPC_FSQRT_II := $(FSQRT_II)
  override NPC_FCVT_CYCLES := $(FCVT_CYCLES)
  override NPC_FCVT_II := $(FCVT_II)
  override NPC_FCMP_CYCLES := $(FCMP_CYCLES)
  override NPC_FCMP_II := $(FCMP_II)
endif
endif

# 旧目标只作为本文件内的底层构造步骤存在，不能再由用户直接选择。统一构造器通过
# INTERNAL_CONSTRUCTION=1 调用它们，并在成功后原子发布到 constructions/<FQCN>。
LEGACY_PUBLIC_GOALS := cpu run-cpu all check sim create gtk chisel chisel-dpi chisel-module \
	chisel-cpu chisel-cpu-lib run-chisel run-difftest soc-sim-verilog soc-nemu-lib \
	fpga-build fpga-check fpga-config fpga-plan fpga-elaborate fpga-ip fpga-synth fpga-link
LEGACY_STRUCTURE_VARS := S D VERSION fpga soc fpga-build SIM_FPGA_CONFIG FPGA_SCALA_PROPS \
	NPC_XLEN NPC_TARGET NPC_PIPELINE NPC_INTERLOCK NPC_ID_FWD NPC_EX_FWD NPC_F \
	NPC_ARITH_BACKEND NPC_ARITH_OUTPUT_FIFO NPC_MUL_CYCLES NPC_MUL_II NPC_DIV_CYCLES NPC_DIV_II \
	NPC_FADD_CYCLES NPC_FADD_II NPC_FMUL_CYCLES NPC_FMUL_II NPC_FDIV_CYCLES NPC_FDIV_II \
	NPC_FFMA_CYCLES NPC_FFMA_II NPC_FSQRT_CYCLES NPC_FSQRT_II NPC_FCVT_CYCLES NPC_FCVT_II \
	NPC_FCMP_CYCLES NPC_FCMP_II
ifeq ($(INTERNAL_CONSTRUCTION),1)
else
  ifneq ($(strip $(filter $(LEGACY_PUBLIC_GOALS),$(MAKECMDGOALS))),)
    $(error 旧构造目标已删除；请使用 make -C npc build config=<Config>)
  endif
  LEGACY_STRUCTURE_OVERRIDES := $(foreach variable,$(LEGACY_STRUCTURE_VARS),$(if $(filter command line,$(origin $(variable))),$(variable),))
  ifneq ($(strip $(LEGACY_STRUCTURE_OVERRIDES)),)
    $(error 结构覆盖变量 $(LEGACY_STRUCTURE_OVERRIDES) 已删除；请在 Scala Config 中固定硬件 ABI)
  endif
endif

NPC_PIPELINE_ORIGIN := $(origin NPC_PIPELINE)
NPC_ID_FWD_ORIGIN := $(origin NPC_ID_FWD)
NPC_EX_FWD_ORIGIN := $(origin NPC_EX_FWD)
NPC_XLEN ?= $(if $(filter soc-nemu-lib soc-sim-verilog,$(MAKECMDGOALS)),32,64)
NPC_TARGET ?= $(if $(filter soc-nemu-lib soc-sim-verilog,$(MAKECMDGOALS)),SOC,NPC)
NPC_PIPELINE ?= 0
NPC_INTERLOCK ?= 1
NPC_ID_FWD ?= $(NPC_PIPELINE)
NPC_EX_FWD ?= $(NPC_PIPELINE)
NPC_F ?= 0
NPC_ARITH_BACKEND ?= model
NPC_ARITH_OUTPUT_FIFO ?= 4
NPC_MUL_CYCLES ?= 3
NPC_MUL_II ?= 1
NPC_DIV_CYCLES ?= 37
NPC_DIV_II ?= 1
NPC_FADD_CYCLES ?= 3
NPC_FADD_II ?= 1
NPC_FMUL_CYCLES ?= 4
NPC_FMUL_II ?= 1
NPC_FDIV_CYCLES ?= 29
NPC_FDIV_II ?= 1
NPC_FFMA_CYCLES ?= 4
NPC_FFMA_II ?= 1
NPC_FSQRT_CYCLES ?= 29
NPC_FSQRT_II ?= 1
NPC_FCVT_CYCLES ?= 7
NPC_FCVT_II ?= 1
NPC_FCMP_CYCLES ?= 3
NPC_FCMP_II ?= 1

# Non-FPGA Make targets select a registered complete NPC or SoC Config.  FPGA
# targets resolve their terminal Config earlier in fpga/config-select.mk.
ifeq ($(INTERNAL_CONSTRUCTION),1)
ifneq ($(FPGA_BUILD_REQUESTED),1)
ifneq ($(strip $(config)),)
  NPC_CONFIG_SCOPE := $(if $(filter SOC,$(NPC_TARGET)),soc,npc)
  NPC_CONFIG_RESOLVED := $(call scpu_config_resolve,$(config),$(NPC_CONFIG_SCOPE))
  ifneq ($(call scpu_config_error,$(NPC_CONFIG_RESOLVED)),)
    $(error $(patsubst !%,%,$(call scpu_config_error,$(NPC_CONFIG_RESOLVED))))
  endif
  NPC_SCALA_CONFIG := $(call scpu_config_field,1,$(NPC_CONFIG_RESOLVED))
  NPC_CONFIG_TARGET := $(call scpu_config_field,4,$(NPC_CONFIG_RESOLVED))
  ifneq ($(NPC_CONFIG_TARGET),$(NPC_TARGET))
    $(error config=$(NPC_SCALA_CONFIG) requires NPC_TARGET=$(NPC_CONFIG_TARGET), got $(NPC_TARGET))
  endif
endif
endif
endif

NPC_STANDALONE_SCALA_CONFIG := $(if $(NPC_SCALA_CONFIG),$(NPC_SCALA_CONFIG),scpu.NpcStandaloneConfig)
NPC_DPI_SCALA_CONFIG := $(if $(NPC_SCALA_CONFIG),$(NPC_SCALA_CONFIG),scpu.NpcDpiConfig)
SOC_STANDALONE_SCALA_CONFIG := $(if $(NPC_SCALA_CONFIG),$(NPC_SCALA_CONFIG),ysyx.YsyxStandaloneConfig)
SOC_SIMULATION_SCALA_CONFIG := $(if $(NPC_SCALA_CONFIG),$(NPC_SCALA_CONFIG),ysyx.YsyxSimulationConfig)
NPC_BUILD_PROPS_WITH_CONFIG = -Dnpc.config=$(1)

ifneq ($(filter 1 true yes on,$(NPC_F)),)
ifeq ($(NPC_ARITH_BACKEND),ip)
$(error NPC_ARITH_BACKEND=ip cannot be used with NPC_F=1: Vivado 2022.2 FPO lacks dynamic RISC-V rounding/RMM, NX reporting, and unsigned float-to-integer conversion; use NPC_ARITH_BACKEND=model)
endif
endif

NPC_IP_RTL_DIR ?= $(CURDIR)/generated-ip
NPC_IP_RTL := $(wildcard $(NPC_IP_RTL_DIR)/*.sv) $(wildcard $(NPC_IP_RTL_DIR)/*.v)
SOFTFLOAT_HOME ?= $(CURDIR)/chisel/ysyxSoC/rocket-chip/dependencies/hardfloat/berkeley-softfloat-3
SOFTFLOAT_SOURCE ?= $(SOFTFLOAT_HOME)/source
SOFTFLOAT_TEMPLATE ?= $(SOFTFLOAT_HOME)/build/Linux-x86_64-GCC
SOFTFLOAT_BUILD_DIR ?= $(obj_dir)/softfloat
SOFTFLOAT_LIB ?= $(SOFTFLOAT_BUILD_DIR)/softfloat.a
SOFTFLOAT_LIB_ABS := $(abspath $(SOFTFLOAT_LIB))
SOFTFLOAT_CPPFLAGS = -I$(SOFTFLOAT_SOURCE)/include
SOFTFLOAT_INPUTS := $(shell find "$(SOFTFLOAT_SOURCE)" "$(SOFTFLOAT_TEMPLATE)" -type f 2>/dev/null)
NPC_ISA := $(if $(filter 32,$(NPC_XLEN)),riscv32,riscv64)
SOC_NPC_PIPELINE := $(if $(filter undefined,$(NPC_PIPELINE_ORIGIN)),1,$(NPC_PIPELINE))
SOC_NPC_ID_FWD := $(if $(filter undefined,$(NPC_ID_FWD_ORIGIN)),$(SOC_NPC_PIPELINE),$(NPC_ID_FWD))
SOC_NPC_EX_FWD := $(if $(filter undefined,$(NPC_EX_FWD_ORIGIN)),$(SOC_NPC_PIPELINE),$(NPC_EX_FWD))
# NEMU passes this from CONFIG_NPC_VCD_TRACE. Standalone simulator targets
# deliberately keep their own unconditional --trace behavior below.
NPC_VCD_TRACE ?= 0
NPC_VCD_TRACE_ENABLED := $(if $(filter 1 y yes true on,$(NPC_VCD_TRACE)),1,0)
NPC_VCD_VERILATOR_FLAGS := $(if $(filter 1,$(NPC_VCD_TRACE_ENABLED)),--trace,)
NPC_VCD_CFLAGS := $(if $(filter 1,$(NPC_VCD_TRACE_ENABLED)),-DNPC_VCD_TRACE,)

CHISEL_STAMP := $(obj_dir)/chisel/.complete
CHISEL_DPI_STAMP := $(obj_dir)/chisel-dpi/.complete
NPC_NEMU_STAMP := $(obj_dir)/chisel-cpu-lib.stamp

FORCE:

SOC_SIM_DIR := ./chisel/ysyxSoC/build-sim
SOC_SIM_VERILOG := $(SOC_SIM_DIR)/ysyxSoCFull.v
SOC_PERIP_ROOT := ./chisel/ysyxSoC/perip
SOC_PERIP_V := $(shell find $(SOC_PERIP_ROOT) -name "*.v")
SOC_NEMU_STAMP := $(obj_dir)/soc-nemu-lib/.build-stamp
SOC_NEMU_INPUTS := $(SOC_SIM_VERILOG) $(SOC_PERIP_V) \
	./csrc/npc_core.cpp ./csrc/npc_core.h ./csrc/npc_debug.h ./csrc/pmem.cpp ./csrc/soc_dpi.cpp ./csrc/fp_dpi.cpp $(SOFTFLOAT_LIB) Makefile

.PHONY: softfloat-lib fp-dpi-test FORCE

$(SOFTFLOAT_LIB): $(SOFTFLOAT_INPUTS)
	@set -e; \
	echo '=== Building SoftFloat model ==='; \
	test -f "$(SOFTFLOAT_TEMPLATE)/Makefile" || { echo "SoftFloat build template not found: $(SOFTFLOAT_TEMPLATE)" >&2; exit 2; }; \
	rm -rf "$(SOFTFLOAT_BUILD_DIR)"; \
	mkdir -p "$(SOFTFLOAT_BUILD_DIR)"; \
	cp "$(SOFTFLOAT_TEMPLATE)/platform.h" "$(SOFTFLOAT_BUILD_DIR)/platform.h"; \
	$(MAKE) -C "$(SOFTFLOAT_BUILD_DIR)" -f "$(SOFTFLOAT_TEMPLATE)/Makefile" \
		SOURCE_DIR="$(SOFTFLOAT_SOURCE)" SPECIALIZE_TYPE=RISCV

softfloat-lib: $(SOFTFLOAT_LIB)

fp-dpi-test: softfloat-lib
	@mkdir -p "$(obj_dir)/fp-dpi-test"
	@g++ -std=c++14 -I$(shell pwd)/csrc $(SOFTFLOAT_CPPFLAGS) \
		./csrc/fp_dpi.cpp ./csrc/fp_dpi_test.cpp "$(SOFTFLOAT_LIB_ABS)" \
		-o "$(obj_dir)/fp-dpi-test/fp-dpi-test"
	@"$(obj_dir)/fp-dpi-test/fp-dpi-test"

# ============== Chisel Build ==============

# A Chisel configuration is an explicit hardware ABI.  Construction management
# calls these recipes only for a first build or rebuild=1; no source/tool
# 摘要比对不得悄悄替换已保存构造。
$(CHISEL_STAMP): FORCE
	@set -e; \
	mkdir -p "$(dir $@)"; \
	echo '=== Generating Verilog from Chisel ==='; \
	rm -rf "$(CHISEL_OUT)"; \
	cd "$(CURDIR)" && NPC_SCALA_CONFIG="$(NPC_STANDALONE_SCALA_CONFIG)" sbt $(call NPC_BUILD_PROPS_WITH_CONFIG,$(NPC_STANDALONE_SCALA_CONFIG)) "root/runMain scpu.Elaborate"; \
	test -f "$(CHISEL_OUT)/CPU.sv"; touch "$@"

$(CHISEL_DPI_STAMP): FORCE
	@set -e; \
	mkdir -p "$(dir $@)"; \
	echo '=== Generating NEMU Verilog from Chisel ==='; \
	rm -rf "$(CHISEL_DPI_OUT)"; \
	cd "$(CURDIR)" && NPC_SCALA_CONFIG="$(NPC_DPI_SCALA_CONFIG)" sbt $(call NPC_BUILD_PROPS_WITH_CONFIG,$(NPC_DPI_SCALA_CONFIG)) "root/runMain scpu.ElaborateDPI"; \
	test -f "$(CHISEL_DPI_OUT)/CPU.sv"; touch "$@"

chisel: $(CHISEL_STAMP)
	@echo "Generated Verilog files in $(CHISEL_OUT)"

chisel-dpi: $(CHISEL_DPI_STAMP)
	@echo "Generated DPI-C Verilog files in $(CHISEL_DPI_OUT)"

# Generate specific Chisel module
chisel-module:
	@if [ -z "$(MODULE)" ]; then \
		echo "Usage: make chisel-module MODULE=<ModuleName>"; \
		exit 1; \
	fi
	@echo "=== Generating $(MODULE) from Chisel ==="
	cd $(shell pwd) && NPC_SCALA_CONFIG="$(NPC_STANDALONE_SCALA_CONFIG)" sbt $(call NPC_BUILD_PROPS_WITH_CONFIG,$(NPC_STANDALONE_SCALA_CONFIG)) "root/runMain scpu.Elaborate$(MODULE)"

# NEMU reference for DiffTest
NEMU_HOME ?= $(shell pwd)/../nemu
NEMU_REF_SO = $(NEMU_HOME)/build/$(NPC_ISA)-nemu-interpreter-so

# Build Chisel CPU with DPI-C support for Verilator simulation (standalone executable)
chisel-cpu: chisel-dpi softfloat-lib
	@echo "=== Building Chisel CPU with DPI-C (standalone) ==="
	@rm -rf $(obj_dir)/chisel-cpu
	@mkdir -p $(obj_dir)/chisel-cpu
	@mkdir -p ./out/chisel-cpu
	verilator -Wall --sv --cc --exe --build -j $(shell nproc) \
		--trace \
		--top-module CPU \
		-Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM -Wno-CASEINCOMPLETE -Wno-WIDTH \
		-Wno-UNOPTFLAT -Wno-LATCH -Wno-DECLFILENAME -Wno-PINCONNECTEMPTY \
		-CFLAGS "-I$(shell pwd)/csrc $(SOFTFLOAT_CPPFLAGS) -DNPC_STANDALONE -DNPC_XLEN=$(NPC_XLEN)" \
		-LDFLAGS "-ldl $(SOFTFLOAT_LIB_ABS)" \
		-Mdir $(obj_dir)/chisel-cpu \
		-I$(CHISEL_DPI_OUT) \
		$(CHISEL_DPI_OUT)/CPU.sv $(NPC_IP_RTL) ./csrc/npc_main.cpp ./csrc/npc_core.cpp ./csrc/pmem.cpp ./csrc/fp_dpi.cpp
	@mv $(obj_dir)/chisel-cpu/VCPU ./out/chisel-cpu/npc-exec
	@echo "Built: ./out/chisel-cpu/npc-exec"

# Build NPC core as object files for NEMU integration
$(NPC_NEMU_STAMP): $(CHISEL_DPI_STAMP) $(SOFTFLOAT_LIB) FORCE
	@set -e; \
	mkdir -p "$(dir $@)"; \
	echo "=== Building Chisel CPU library for NEMU (VCD=$(NPC_VCD_TRACE_ENABLED)) ==="; \
		rm -rf "$(obj_dir)/chisel-cpu-lib"; mkdir -p "$(obj_dir)/chisel-cpu-lib" ./out/chisel-cpu-lib; \
		verilator -Wall --sv --cc -j $(shell nproc) $(NPC_VCD_VERILATOR_FLAGS) \
			--top-module CPU \
			-Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM -Wno-CASEINCOMPLETE -Wno-WIDTH \
			-Wno-UNOPTFLAT -Wno-LATCH -Wno-DECLFILENAME -Wno-PINCONNECTEMPTY \
			-CFLAGS "-I$(CURDIR)/csrc -fPIC $(NPC_VCD_CFLAGS) -DNPC_XLEN=$(NPC_XLEN)" \
			-Mdir "$(obj_dir)/chisel-cpu-lib" -I"$(CHISEL_DPI_OUT)" \
			"$(CHISEL_DPI_OUT)/CPU.sv" $(NPC_IP_RTL); \
		if $(MAKE) -C "$(obj_dir)/chisel-cpu-lib" -f VCPU.mk -n VCPU__ALL.o >/dev/null 2>&1; then \
			$(MAKE) -C "$(obj_dir)/chisel-cpu-lib" -f VCPU.mk VCPU__ALL.o; \
		else \
			$(MAKE) -C "$(obj_dir)/chisel-cpu-lib" -f VCPU.mk libVCPU.a VCPU__ALL.a; \
		fi; \
		VERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator; \
		g++ -c -fPIC -I"$(CURDIR)/csrc" -I"$(obj_dir)/chisel-cpu-lib" -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
			-std=c++14 $(NPC_VCD_CFLAGS) -DNPC_XLEN=$(NPC_XLEN) ./csrc/npc_core.cpp -o "$(obj_dir)/chisel-cpu-lib/npc_core.o" & core_pid=$$!; \
		g++ -c -fPIC -I"$(CURDIR)/csrc" -I"$(obj_dir)/chisel-cpu-lib" -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
			-std=c++14 -DNPC_XLEN=$(NPC_XLEN) ./csrc/pmem.cpp -o "$(obj_dir)/chisel-cpu-lib/pmem.o" & pmem_pid=$$!; \
		g++ -c -fPIC -I"$(CURDIR)/csrc" -I"$(obj_dir)/chisel-cpu-lib" $(SOFTFLOAT_CPPFLAGS) -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
			-std=c++14 -DNPC_XLEN=$(NPC_XLEN) ./csrc/fp_dpi.cpp -o "$(obj_dir)/chisel-cpu-lib/fp_dpi.o" & fp_pid=$$!; \
		wait $$core_pid; wait $$pmem_pid; wait $$fp_pid; \
		g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd -std=c++14 -faligned-new \
			$$VERILATOR_ROOT/include/verilated.cpp -o "$(obj_dir)/chisel-cpu-lib/verilated.o" & verilated_pid=$$!; \
		g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd -std=c++14 -faligned-new \
			$$VERILATOR_ROOT/include/verilated_dpi.cpp -o "$(obj_dir)/chisel-cpu-lib/verilated_dpi.o" & dpi_pid=$$!; \
		g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd -std=c++14 -faligned-new \
			$$VERILATOR_ROOT/include/verilated_threads.cpp -o "$(obj_dir)/chisel-cpu-lib/verilated_threads.o" & threads_pid=$$!; \
		if test "$(NPC_VCD_TRACE_ENABLED)" = 1; then \
			g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd -std=c++14 -faligned-new \
				$$VERILATOR_ROOT/include/verilated_vcd_c.cpp -o "$(obj_dir)/chisel-cpu-lib/verilated_vcd_c.o" & vcd_pid=$$!; \
		fi; \
		wait $$verilated_pid; wait $$dpi_pid; wait $$threads_pid; \
		if test "$(NPC_VCD_TRACE_ENABLED)" = 1; then wait $$vcd_pid; fi; \
		touch "$@"

chisel-cpu-lib: $(NPC_NEMU_STAMP)
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
		echo "Please build NEMU with: make -C $(NEMU_HOME) ISA=$(NPC_ISA) SHARE=1"; \
		exit 1; \
	fi
	@echo "=== Running NPC with DiffTest (REF: NEMU) ==="
	cd ./out/chisel-cpu && ./npc-exec -d $(NEMU_REF_SO) $(IMG)

.PHONY: chisel chisel-dpi chisel-module chisel-cpu chisel-cpu-lib run-chisel run-difftest

# Build ysyxSoCTop as the DUT for NEMU-driven SoC simulation.  The generated
# top includes the full AXI/APB fabric; simulation mode replaces only PSRAM
# with an APB DPI memory backed by NEMU's physical memory.
soc-sim-verilog: check-soc-xlen
	@$(MAKE) -C ./chisel/ysyxSoC sim-verilog INTERNAL_CONSTRUCTION=1 config=$(SOC_SIMULATION_SCALA_CONFIG) NPC_TARGET=SOC NPC_XLEN=$(NPC_XLEN) \
		NPC_PIPELINE=$(SOC_NPC_PIPELINE) NPC_INTERLOCK=$(NPC_INTERLOCK) NPC_ID_FWD=$(SOC_NPC_ID_FWD) \
		NPC_EX_FWD=$(SOC_NPC_EX_FWD) NPC_F=$(NPC_F) NPC_ARITH_BACKEND=$(NPC_ARITH_BACKEND) \
		NPC_ARITH_OUTPUT_FIFO=$(NPC_ARITH_OUTPUT_FIFO) NPC_MUL_CYCLES=$(NPC_MUL_CYCLES) NPC_MUL_II=$(NPC_MUL_II) \
		NPC_DIV_CYCLES=$(NPC_DIV_CYCLES) NPC_DIV_II=$(NPC_DIV_II) NPC_FADD_CYCLES=$(NPC_FADD_CYCLES) \
		NPC_FADD_II=$(NPC_FADD_II) NPC_FMUL_CYCLES=$(NPC_FMUL_CYCLES) NPC_FMUL_II=$(NPC_FMUL_II) \
		NPC_FDIV_CYCLES=$(NPC_FDIV_CYCLES) NPC_FDIV_II=$(NPC_FDIV_II) \
		NPC_FFMA_CYCLES=$(NPC_FFMA_CYCLES) NPC_FFMA_II=$(NPC_FFMA_II) \
		NPC_FSQRT_CYCLES=$(NPC_FSQRT_CYCLES) NPC_FSQRT_II=$(NPC_FSQRT_II) \
		NPC_FCVT_CYCLES=$(NPC_FCVT_CYCLES) NPC_FCVT_II=$(NPC_FCVT_II) \
		NPC_FCMP_CYCLES=$(NPC_FCMP_CYCLES) NPC_FCMP_II=$(NPC_FCMP_II)

soc-nemu-lib: soc-sim-verilog check-soc-xlen softfloat-lib
	@$(MAKE) $(SOC_NEMU_STAMP)

check-soc-xlen:
	@if [ "$(NPC_XLEN)" != "32" ]; then \
		echo "ysyxSoC requires NPC_XLEN=32"; \
		exit 1; \
	fi

$(SOC_NEMU_STAMP): FORCE $(SOC_NEMU_INPUTS)
	@echo "=== Building ysyxSoC Verilator library for NEMU ==="
	@rm -rf $(obj_dir)/soc-nemu-lib
	@mkdir -p $(obj_dir)/soc-nemu-lib
	@verilator -Wall -Wno-fatal --quiet --quiet-stats --sv --cc --no-timing -j $(shell nproc) $(NPC_VCD_VERILATOR_FLAGS) \
		--top-module ysyxSoCTop \
		-I$(SOC_PERIP_ROOT)/uart16550/rtl \
		-I$(SOC_PERIP_ROOT)/spi/rtl \
		-Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM -Wno-CASEINCOMPLETE -Wno-WIDTH \
		-Wno-UNOPTFLAT -Wno-LATCH -Wno-DECLFILENAME -Wno-PINCONNECTEMPTY \
		-Wno-TIMESCALEMOD -Wno-ASSIGNDLY -Wno-UNDRIVEN -Wno-PROCASSINIT -Wno-SYNCASYNCNET -Wno-DEFPARAM \
		-CFLAGS "-I$(shell pwd)/csrc -fPIC $(NPC_VCD_CFLAGS) -DNPC_SOC -DNPC_XLEN=32" \
		-Mdir $(obj_dir)/soc-nemu-lib \
		$(SOC_SIM_VERILOG) $(SOC_PERIP_V)
	@$(MAKE) -s -C $(obj_dir)/soc-nemu-lib -f VysyxSoCTop.mk libVysyxSoCTop
	@echo "=== Compiling SoC NEMU glue ==="
	@VERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator && \
	set -e; \
	g++ -c -fPIC -I$(shell pwd)/csrc -I$(obj_dir)/soc-nemu-lib \
		-I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 $(NPC_VCD_CFLAGS) -DNPC_SOC -DNPC_XLEN=32 \
		./csrc/npc_core.cpp -o $(obj_dir)/soc-nemu-lib/npc_core.o; \
	g++ -c -fPIC -I$(shell pwd)/csrc -I$(obj_dir)/soc-nemu-lib \
		-I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 -DNPC_SOC -DNPC_XLEN=32 \
		./csrc/pmem.cpp -o $(obj_dir)/soc-nemu-lib/pmem.o; \
	g++ -c -fPIC -I$(shell pwd)/csrc -I$(obj_dir)/soc-nemu-lib $(SOFTFLOAT_CPPFLAGS) \
		-I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 -DNPC_SOC -DNPC_XLEN=32 \
		./csrc/fp_dpi.cpp -o $(obj_dir)/soc-nemu-lib/fp_dpi.o; \
	g++ -c -fPIC -I$(shell pwd)/csrc -I$(obj_dir)/soc-nemu-lib \
		-I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 -DNPC_SOC -DNPC_XLEN=32 \
		./csrc/soc_dpi.cpp -o $(obj_dir)/soc-nemu-lib/soc_dpi.o
	@echo "=== Compiling Verilator runtime objects ==="
	@VERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator; \
	set -e; \
	g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 -faligned-new $$VERILATOR_ROOT/include/verilated.cpp \
		-o $(obj_dir)/soc-nemu-lib/verilated.o; \
	g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 -faligned-new $$VERILATOR_ROOT/include/verilated_dpi.cpp \
		-o $(obj_dir)/soc-nemu-lib/verilated_dpi.o; \
	if test "$(NPC_VCD_TRACE_ENABLED)" = 1; then \
		g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
			-std=c++14 -faligned-new $$VERILATOR_ROOT/include/verilated_vcd_c.cpp \
			-o $(obj_dir)/soc-nemu-lib/verilated_vcd_c.o; \
	fi; \
	g++ -c -fPIC -I$$VERILATOR_ROOT/include -I$$VERILATOR_ROOT/include/vltstd \
		-std=c++14 -faligned-new $$VERILATOR_ROOT/include/verilated_threads.cpp \
		-o $(obj_dir)/soc-nemu-lib/verilated_threads.o
	@touch $@
	@echo "Built SoC NEMU library objects in $(obj_dir)/soc-nemu-lib/"

.PHONY: soc-sim-verilog soc-nemu-lib check-soc-xlen

include ./fpga/build.mk

# ============== Config 驱动的公开接口 ==============

CONSTRUCTION_MANAGER := $(CURDIR)/scripts/construction-manager.sh
REBUILD_REQUEST := $(if $(filter 1,$(strip $(rebuild))),1,0)
DELETE_CONFIRM := $(if $(filter 1,$(strip $(yes))),1,0)

config-list:
	@$(CONSTRUCTION_MANAGER) catalog "$(CURDIR)"

build:
	@test -n "$(strip $(config))" || { echo 'build 必须提供 config=<Config短名或FQCN>' >&2; exit 2; }
	@$(CONSTRUCTION_MANAGER) build "$(CURDIR)" "$(strip $(config))" "$(REBUILD_REQUEST)"

version:
	@if test -n "$(strip $(delete))"; then \
		$(CONSTRUCTION_MANAGER) delete "$(CURDIR)" "$(strip $(delete))" "$(DELETE_CONFIRM)"; \
	elif test -n "$(strip $(version))"; then \
		$(CONSTRUCTION_MANAGER) list "$(CURDIR)" "$(strip $(version))"; \
	elif test -n "$(strip $(config))"; then \
		resolved=$$($(CONSTRUCTION_MANAGER) resolve "$(CURDIR)" "$(strip $(config))" ''); \
		fqcn=$${resolved%%|*}; $(CONSTRUCTION_MANAGER) list "$(CURDIR)" "$$fqcn"; \
	else \
		$(CONSTRUCTION_MANAGER) list "$(CURDIR)"; \
	fi

.PHONY: config-list build version



include ../Makefile
