SPMV_INPUT_FPGA_BUILD_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
SPMV_INPUT_WORK_DIR ?= $(CURDIR)/fpga/common/build/spmv-input/manual
SPMV_INPUT_RTL_DIR := $(SPMV_INPUT_WORK_DIR)/rtl
SPMV_INPUT_IP_DIR := $(SPMV_INPUT_WORK_DIR)/ip-generated
SPMV_INPUT_ARTIFACT_DIR := $(SPMV_INPUT_WORK_DIR)/artifacts
SPMV_INPUT_SOURCE_MANIFEST := $(SPMV_INPUT_WORK_DIR)/synthesis-sources.manifest
SPMV_INPUT_PARAMETER_MANIFEST := $(SPMV_INPUT_RTL_DIR)/spmv-input-parameters.env
SPMV_INPUT_ELAB_DONE := $(SPMV_INPUT_WORK_DIR)/.elaboration.complete
SPMV_INPUT_IP_DONE := $(SPMV_INPUT_WORK_DIR)/.ip.complete
SPMV_INPUT_PACKAGE_DONE := $(SPMV_INPUT_WORK_DIR)/.package.complete
SPMV_INPUT_LINK_DONE := $(SPMV_INPUT_WORK_DIR)/.link.complete
SPMV_INPUT_TOP := SpmvInputKernel
SPMV_INPUT_CHISEL_TOP := SpmvInputTop
SPMV_INPUT_WRAPPER := $(CURDIR)/fpga/u55c/rtl/spmv/spmv-input-kernel.sv
SPMV_INPUT_COMMON_RTL := $(CURDIR)/fpga/u55c/rtl/common/u55c-clocked-core.sv
SPMV_INPUT_IP_TCL := $(CURDIR)/fpga/u55c/tcl/spmv/create-input-fp64-ip.tcl
SPMV_INPUT_PACKAGE_TCL := $(CURDIR)/fpga/u55c/tcl/spmv/package-input-xo.tcl
SPMV_INPUT_MANIFEST_TOOL := $(CURDIR)/fpga/common/scripts/manifest.sh
SPMV_INPUT_SOURCE_MANIFEST_TOOL := $(CURDIR)/scripts/ip-source-manifest.sh
SPMV_INPUT_ARTIFACT_TOOL := $(CURDIR)/fpga/common/scripts/artifact-manifest.sh
SPMV_INPUT_XCI := $(SPMV_INPUT_IP_DIR)/SpmvFp64MulXilinxCore.xci
SPMV_INPUT_XO := $(SPMV_INPUT_ARTIFACT_DIR)/spmv-input.xo
SPMV_INPUT_XCLBIN := $(SPMV_INPUT_ARTIFACT_DIR)/spmv-input.xclbin
SPMV_INPUT_VITIS_TEMP_DIR := $(SPMV_INPUT_WORK_DIR)/vitis-temp
SPMV_INPUT_VITIS_LOG_DIR := $(SPMV_INPUT_WORK_DIR)/vitis-logs
SPMV_INPUT_VITIS_REPORT_DIR := $(SPMV_INPUT_WORK_DIR)/vitis-reports
SPMV_INPUT_VITIS_LINK_CONFIG := $(SPMV_INPUT_WORK_DIR)/vitis-link.cfg
SPMV_INPUT_WNS := $(SPMV_INPUT_ARTIFACT_DIR)/spmv-input.wns
SPMV_INPUT_TIMING_WNS_TOOL := $(CURDIR)/fpga/common/scripts/extract-timing-wns.sh
SPMV_INPUT_PACKAGE_ASSETS := spmv-input.xo SpmvFp64MulXilinxCore.xci
SPMV_INPUT_FINAL_ASSETS := spmv-input.xclbin $(SPMV_INPUT_PACKAGE_ASSETS)

spmv-input-fpga-check:
	@test "$(CAPABILITY)" = run && test "$(TARGET)" = SPMV && test "$(FPGA_BOARD)" = u55c
	@test "$(HOST_ABI)" = none && test "$(ACCELERATOR_HOST_ABI)" = spmv-input-u55c-runtime-v1
	@test "$(PROTOCOL_ABI)" = spmv-input-u55c-windowed-v1 && test "$(SPMV_XRT_KERNEL)" = SpmvInputKernel
	@test "$(SPMV_INPUT_HBM_MASTER_COUNT)" = 19 && test "$(SPMV_INPUT_A_READER_COUNT)" = 16
	@test "$(SPMV_INPUT_X_READER_COUNT)" = 2 && test "$(SPMV_INPUT_CTRL_READER_COUNT)" = 1
	@test "$(SPMV_INPUT_AXI_ADDR_WIDTH)" = 64 && test "$(SPMV_INPUT_AXI_DATA_WIDTH)" = 512
	@test "$(SPMV_INPUT_AXI_ID_WIDTH)" = 4 && test "$(SPMV_INPUT_MAX_OUTSTANDING_BURSTS)" = 2
	@test "$(SPMV_INPUT_X_PORT_SCHEDULE)" = pingpong && test "$(SPMV_INPUT_X_WRITE_LANES)" = 8
	@test "$(SPMV_INPUT_X_OVERLAP_LANES)" = 4 && test "$(SPMV_INPUT_X_WINDOW_SIZE)" = 8192
	@test "$(SPMV_FP64_MUL_PROVIDER)" = xilinx-floating-point-v7.1
	@test "$(SPMV_FP64_MUL_LATENCY)" = 12 && test "$(SPMV_FP64_MUL_II)" = 1
	@test "$(FPGA_PLATFORM_CLOCK_MHZ)" = 300
	@case "$(FPGA_CLOCK_MHZ)" in 100|125|150|200|225|250|300) ;; *) echo 'SPMV core clock must be a supported U55C frequency' >&2; exit 2;; esac
	@test "$(FPGA_CLOCK_MHZ)" -le "$(FPGA_PLATFORM_CLOCK_MHZ)"
	@test "$(FPGA_PART)" = xcu55c-fsvh2892-2L-e && test "$(FPGA_VITIS_TARGET)" = hw
	@test -f "$(SPMV_INPUT_WRAPPER)" && test -f "$(SPMV_INPUT_COMMON_RTL)" && test -f "$(SPMV_INPUT_IP_TCL)" && test -f "$(SPMV_INPUT_PACKAGE_TCL)"

$(SPMV_INPUT_ELAB_DONE): FORCE spmv-input-fpga-check
	@set -e; \
	mkdir -p "$(SPMV_INPUT_WORK_DIR)"; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then echo 'SPMV input FPGA elaboration dry run'; touch "$@"; exit 0; fi; \
	rm -rf "$(SPMV_INPUT_RTL_DIR)"; mkdir -p "$(SPMV_INPUT_RTL_DIR)"; \
	cd "$(CURDIR)/chisel/ysyxSoC" && NPC_SCALA_CONFIG="$(CONFIG_FQCN)" \
		mill -i ysyxsoc.runMain accelerators.spmv.fpga.ElaborateSpmvInputFpga --target-dir "$(SPMV_INPUT_RTL_DIR)"; \
	test -f "$(SPMV_INPUT_RTL_DIR)/$(SPMV_INPUT_CHISEL_TOP).sv" && test -f "$(SPMV_INPUT_PARAMETER_MANIFEST)"; \
	{ \
		test "$(SPMV_INPUT_X_PORT_SCHEDULE)" = pingpong && printf '%s\n' '`define SPMV_INPUT_PINGPONG 1'; \
		printf '%s\n' '`define SPMV_INPUT_PLATFORM_CLOCK_MHZ $(FPGA_PLATFORM_CLOCK_MHZ)'; \
		printf '%s\n' '`define SPMV_INPUT_CORE_CLOCK_MHZ $(FPGA_CLOCK_MHZ)'; \
		cat "$(SPMV_INPUT_COMMON_RTL)" "$(SPMV_INPUT_WRAPPER)"; \
	} > "$(SPMV_INPUT_RTL_DIR)/spmv-input-kernel.sv"; \
	"$(SPMV_INPUT_MANIFEST_TOOL)" verify "$(SPMV_INPUT_PARAMETER_MANIFEST)" \
		CONFIG_FQCN=$(CONFIG_FQCN) SPMV_INPUT_HBM_MASTER_COUNT=$(SPMV_INPUT_HBM_MASTER_COUNT) \
		SPMV_INPUT_A_READER_COUNT=$(SPMV_INPUT_A_READER_COUNT) SPMV_INPUT_X_READER_COUNT=$(SPMV_INPUT_X_READER_COUNT) \
		SPMV_INPUT_CTRL_READER_COUNT=$(SPMV_INPUT_CTRL_READER_COUNT) SPMV_INPUT_AXI_ADDR_WIDTH=$(SPMV_INPUT_AXI_ADDR_WIDTH) \
		SPMV_INPUT_AXI_DATA_WIDTH=$(SPMV_INPUT_AXI_DATA_WIDTH) SPMV_INPUT_AXI_ID_WIDTH=$(SPMV_INPUT_AXI_ID_WIDTH) \
		SPMV_INPUT_X_PORT_SCHEDULE=$(SPMV_INPUT_X_PORT_SCHEDULE) SPMV_FP64_MUL_PROVIDER=$(SPMV_FP64_MUL_PROVIDER) \
		SPMV_FP64_MUL_LATENCY=$(SPMV_FP64_MUL_LATENCY) SPMV_FP64_MUL_II=$(SPMV_FP64_MUL_II); \
	touch "$@"

spmv-input-fpga-elaborate: $(SPMV_INPUT_ELAB_DONE)

$(SPMV_INPUT_IP_DONE): FORCE $(if $(filter 1,$(SPMV_INPUT_PHASE_PREREQUISITES)),$(SPMV_INPUT_ELAB_DONE)) spmv-input-fpga-check
	@set -e; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then mkdir -p "$(SPMV_INPUT_IP_DIR)"; printf 'dry-run FP64 XCI\n' > "$(SPMV_INPUT_XCI)"; touch "$@"; exit 0; fi; \
	rm -rf "$(SPMV_INPUT_IP_DIR)"; mkdir -p "$(SPMV_INPUT_IP_DIR)"; \
	vivado -mode batch -nojournal -nolog -source "$(SPMV_INPUT_IP_TCL)" -tclargs \
		"$(SPMV_INPUT_IP_DIR)/project" "$(FPGA_PART)" "$(SPMV_INPUT_XCI)" "$(FPGA_VIVADO_SYNTH_JOBS)" "$(CONSTRUCTION_PROFILE)"; \
	test -s "$(SPMV_INPUT_XCI)"; touch "$@"

spmv-input-fpga-ip: $(SPMV_INPUT_IP_DONE)

$(SPMV_INPUT_PACKAGE_DONE): FORCE $(if $(filter 1,$(SPMV_INPUT_PHASE_PREREQUISITES)),$(SPMV_INPUT_IP_DONE)) spmv-input-fpga-check
	@set -e; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then mkdir -p "$(SPMV_INPUT_ARTIFACT_DIR)"; printf 'dry-run XO\n' > "$(SPMV_INPUT_XO)"; cp "$(SPMV_INPUT_XCI)" "$(SPMV_INPUT_ARTIFACT_DIR)/SpmvFp64MulXilinxCore.xci"; touch "$@"; exit 0; fi; \
	"$(SPMV_INPUT_SOURCE_MANIFEST_TOOL)" write synthesis "$(SPMV_INPUT_SOURCE_MANIFEST)" "$(CURDIR)" --absolute \
		--rtl-dir "$(SPMV_INPUT_RTL_DIR)" --xci-dir "$(SPMV_INPUT_IP_DIR)"; \
	"$(SPMV_INPUT_SOURCE_MANIFEST_TOOL)" verify "$(SPMV_INPUT_SOURCE_MANIFEST)" "$(CURDIR)" synthesis; \
	rm -rf "$(SPMV_INPUT_ARTIFACT_DIR)"; mkdir -p "$(SPMV_INPUT_ARTIFACT_DIR)"; \
	vivado -mode batch -nojournal -nolog -source "$(SPMV_INPUT_PACKAGE_TCL)" -tclargs \
		"$(SPMV_INPUT_WORK_DIR)/package" "$(FPGA_PART)" "$(SPMV_INPUT_TOP)" "$(SPMV_INPUT_SOURCE_MANIFEST)" \
		"$(SPMV_INPUT_XO)" "$(FPGA_VIVADO_SYNTH_JOBS)" "$(FPGA_PLATFORM_CLOCK_MHZ)" "$(FPGA_CLOCK_MHZ)" "$(SPMV_INPUT_HBM_MASTER_COUNT)"; \
	cp "$(SPMV_INPUT_XCI)" "$(SPMV_INPUT_ARTIFACT_DIR)/SpmvFp64MulXilinxCore.xci"; \
	for asset in $(SPMV_INPUT_PACKAGE_ASSETS); do test -s "$(SPMV_INPUT_ARTIFACT_DIR)/$$asset"; done; touch "$@"

spmv-input-fpga-package: $(SPMV_INPUT_PACKAGE_DONE)

$(SPMV_INPUT_LINK_DONE): FORCE $(if $(filter 1,$(SPMV_INPUT_PHASE_PREREQUISITES)),$(SPMV_INPUT_PACKAGE_DONE)) spmv-input-fpga-check
	@set -e; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then \
		mkdir -p "$(SPMV_INPUT_ARTIFACT_DIR)"; printf 'dry-run xclbin\n' > "$(SPMV_INPUT_XCLBIN)"; printf '0.000\n' > "$(SPMV_INPUT_WNS)"; \
		"$(SPMV_INPUT_ARTIFACT_TOOL)" write --directory "$(SPMV_INPUT_ARTIFACT_DIR)" --source-root "$(abspath $(CURDIR)/..)" \
			--release-tag UNRELEASED --board u55c --variant "$(CONFIG_FQCN)" --type alveo --platform "$(FPGA_PLATFORM)" \
			--config-fqcn "$(CONFIG_FQCN)" --host-abi none --protocol-abi "$(PROTOCOL_ABI)" --timing-wns 0.000 \
			$(foreach asset,$(SPMV_INPUT_FINAL_ASSETS),--asset "$(asset)"); touch "$@"; exit 0; \
	fi; \
	"$(SPMV_INPUT_SOURCE_MANIFEST_TOOL)" verify "$(SPMV_INPUT_SOURCE_MANIFEST)" "$(CURDIR)" synthesis; \
	rm -rf "$(SPMV_INPUT_VITIS_TEMP_DIR)" "$(SPMV_INPUT_VITIS_LOG_DIR)" "$(SPMV_INPUT_VITIS_REPORT_DIR)"; \
	mkdir -p "$(SPMV_INPUT_VITIS_TEMP_DIR)" "$(SPMV_INPUT_VITIS_LOG_DIR)" "$(SPMV_INPUT_VITIS_REPORT_DIR)"; \
	{ printf '%s\n' '[connectivity]'; for pc in $$(seq 0 18); do printf 'sp=SpmvInputKernel_1.m_axi_pc%02d:HBM[%d]\n' "$$pc" "$$pc"; done; \
		printf '%s\n' '' '[clock]'; printf 'freqHz=%s000000:SpmvInputKernel_1.ap_clk\n' "$(FPGA_PLATFORM_CLOCK_MHZ)"; \
		printf '%s\n' '' '[vivado]' 'synth.jobs=$(FPGA_VIVADO_SYNTH_JOBS)' 'impl.jobs=$(FPGA_VIVADO_IMPL_JOBS)'; \
	} > "$(SPMV_INPUT_VITIS_LINK_CONFIG)"; \
	$(if $(filter unset,$(FPGA_VITIS_XRT_MODE)),env -u XILINX_XRT,) v++ --link --target "$(FPGA_VITIS_TARGET)" --platform "$(FPGA_PLATFORM)" \
		--config "$(SPMV_INPUT_VITIS_LINK_CONFIG)" --temp_dir "$(SPMV_INPUT_VITIS_TEMP_DIR)" --log_dir "$(SPMV_INPUT_VITIS_LOG_DIR)" \
		--report_dir "$(SPMV_INPUT_VITIS_REPORT_DIR)" --output "$(SPMV_INPUT_XCLBIN)" "$(SPMV_INPUT_XO)"; \
	"$(SPMV_INPUT_TIMING_WNS_TOOL)" "$(SPMV_INPUT_VITIS_REPORT_DIR)" > "$(SPMV_INPUT_WNS)"; \
	wns=$$(cat "$(SPMV_INPUT_WNS)"); awk -v wns="$$wns" -v min="$(FPGA_TIMING_WNS_MIN_NS)" 'BEGIN { exit !(wns >= min) }'; \
	for asset in $(SPMV_INPUT_FINAL_ASSETS); do test -s "$(SPMV_INPUT_ARTIFACT_DIR)/$$asset"; done; \
	"$(SPMV_INPUT_ARTIFACT_TOOL)" write --directory "$(SPMV_INPUT_ARTIFACT_DIR)" --source-root "$(abspath $(CURDIR)/..)" \
		--release-tag UNRELEASED --board u55c --variant "$(CONFIG_FQCN)" --type alveo --platform "$(FPGA_PLATFORM)" \
		--config-fqcn "$(CONFIG_FQCN)" --host-abi none --protocol-abi "$(PROTOCOL_ABI)" --timing-wns "$$wns" \
		$(foreach asset,$(SPMV_INPUT_FINAL_ASSETS),--asset "$(asset)"); touch "$@"

spmv-input-fpga-link: $(SPMV_INPUT_LINK_DONE)

.PHONY: spmv-input-fpga-check spmv-input-fpga-elaborate spmv-input-fpga-ip spmv-input-fpga-package spmv-input-fpga-link
