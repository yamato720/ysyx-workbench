SPMV_CUPERFLOW_FPGA_BUILD_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
SPMV_CUPERFLOW_WORK_DIR ?= $(CURDIR)/fpga/common/build/spmv-cuperflow/manual
SPMV_CUPERFLOW_RTL_DIR := $(SPMV_CUPERFLOW_WORK_DIR)/rtl
SPMV_CUPERFLOW_IP_DIR := $(SPMV_CUPERFLOW_WORK_DIR)/ip-generated
SPMV_CUPERFLOW_ARTIFACT_DIR := $(SPMV_CUPERFLOW_WORK_DIR)/artifacts
SPMV_CUPERFLOW_SOURCE_MANIFEST := $(SPMV_CUPERFLOW_WORK_DIR)/synthesis-sources.manifest
SPMV_CUPERFLOW_PARAMETER_MANIFEST := $(SPMV_CUPERFLOW_RTL_DIR)/spmv-cuperflow-parameters.env
SPMV_CUPERFLOW_ELAB_DONE := $(SPMV_CUPERFLOW_WORK_DIR)/.elaboration.complete
SPMV_CUPERFLOW_IP_DONE := $(SPMV_CUPERFLOW_WORK_DIR)/.ip.complete
SPMV_CUPERFLOW_PACKAGE_DONE := $(SPMV_CUPERFLOW_WORK_DIR)/.package.complete
SPMV_CUPERFLOW_LINK_DONE := $(SPMV_CUPERFLOW_WORK_DIR)/.link.complete
SPMV_CUPERFLOW_TOP := SpmvCuperflowKernel
SPMV_CUPERFLOW_CHISEL_TOP := SpmvCuperflowInputTop
SPMV_CUPERFLOW_WRAPPER := $(CURDIR)/fpga/u55c/rtl/spmv/spmv-cuperflow-kernel.sv
SPMV_CUPERFLOW_COMMON_RTL := $(CURDIR)/fpga/u55c/rtl/common/u55c-clocked-core.sv
SPMV_CUPERFLOW_IP_TCL := $(CURDIR)/fpga/u55c/tcl/spmv/create-input-fp64-ip.tcl
SPMV_CUPERFLOW_PACKAGE_TCL := $(CURDIR)/fpga/u55c/tcl/spmv/package-cuperflow-xo.tcl
SPMV_CUPERFLOW_MANIFEST_TOOL := $(CURDIR)/fpga/common/scripts/manifest.sh
SPMV_CUPERFLOW_SOURCE_MANIFEST_TOOL := $(CURDIR)/scripts/ip-source-manifest.sh
SPMV_CUPERFLOW_ARTIFACT_TOOL := $(CURDIR)/fpga/common/scripts/artifact-manifest.sh
SPMV_CUPERFLOW_XCI := $(SPMV_CUPERFLOW_IP_DIR)/SpmvFp64MulXilinxCore.xci
SPMV_CUPERFLOW_XO := $(SPMV_CUPERFLOW_ARTIFACT_DIR)/spmv-cuperflow.xo
SPMV_CUPERFLOW_XCLBIN := $(SPMV_CUPERFLOW_ARTIFACT_DIR)/spmv-cuperflow.xclbin
SPMV_CUPERFLOW_VITIS_TEMP_DIR := $(SPMV_CUPERFLOW_WORK_DIR)/vitis-temp
SPMV_CUPERFLOW_VITIS_LOG_DIR := $(SPMV_CUPERFLOW_WORK_DIR)/vitis-logs
SPMV_CUPERFLOW_VITIS_REPORT_DIR := $(SPMV_CUPERFLOW_WORK_DIR)/vitis-reports
SPMV_CUPERFLOW_VITIS_LINK_CONFIG := $(SPMV_CUPERFLOW_WORK_DIR)/vitis-link.cfg
SPMV_CUPERFLOW_WNS := $(SPMV_CUPERFLOW_ARTIFACT_DIR)/spmv-cuperflow.wns
SPMV_CUPERFLOW_TIMING_WNS_TOOL := $(CURDIR)/fpga/common/scripts/extract-timing-wns.sh
SPMV_CUPERFLOW_PACKAGE_ASSETS := spmv-cuperflow.xo SpmvFp64MulXilinxCore.xci
SPMV_CUPERFLOW_FINAL_ASSETS = $(if $(filter bitstream-only,$(CAPABILITY)),spmv-cuperflow.xclbin $(SPMV_CUPERFLOW_PACKAGE_ASSETS),$(SPMV_CUPERFLOW_PACKAGE_ASSETS))

spmv-cuperflow-fpga-check:
	@test "$(CAPABILITY)" = synthesize-only || test "$(CAPABILITY)" = bitstream-only
	@test "$(TARGET)" = SPMV && test "$(FPGA_BOARD)" = u55c
	@test "$(HOST_ABI)" = none && test "$(ACCELERATOR_HOST_ABI)" = spmv-cuperflow-u55c-v3
	@test "$(PROTOCOL_ABI)" = spmv-cuperflow-u55c-v3 && test "$(SPMV_CUPERFLOW_XRT_KERNEL)" = SpmvCuperflowKernel
	@test "$(SPMV_CUPERFLOW_HBM_PC_COUNT)" = 16 && test "$(SPMV_CUPERFLOW_AXI_ADDR_WIDTH)" = 64
	@test "$(SPMV_CUPERFLOW_AXI_DATA_WIDTH)" = 512 && test "$(SPMV_CUPERFLOW_AXI_ID_WIDTH)" = 4
	@test "$(SPMV_CUPERFLOW_HBM_BASE)" = 0x0 && test "$(SPMV_CUPERFLOW_X_REGION_BYTES)" = 67108864
	@test "$(SPMV_CUPERFLOW_X_WINDOW_SIZE)" = 8192 && test "$(SPMV_CUPERFLOW_X_REPLICA_COUNT)" = 4
	@test "$(SPMV_CUPERFLOW_X_ELEMENT_WIDTH)" = 64 && test "$(SPMV_CUPERFLOW_X_STORAGE)" = ultra
	@test "$(SPMV_CUPERFLOW_X_MEMORY_DATA_WIDTH)" = 256 && test "$(SPMV_CUPERFLOW_X_LOAD_LANES)" = 8
	@test "$(SPMV_CUPERFLOW_MAP_ABI)" = cuperflow-map-multisegment-v3
	@test "$(SPMV_FP64_MUL_PROVIDER)" = xilinx-floating-point-v7.1
	@test "$(SPMV_FP64_MUL_LATENCY)" = 12 && test "$(SPMV_FP64_MUL_II)" = 1
	@test "$(FPGA_PLATFORM_CLOCK_MHZ)" = 300 && test "$(FPGA_CLOCK_MHZ)" = 250
	@test "$(FPGA_PART)" = xcu55c-fsvh2892-2L-e && test "$(FPGA_VITIS_TARGET)" = hw
	@test -f "$(SPMV_CUPERFLOW_WRAPPER)" && test -f "$(SPMV_CUPERFLOW_COMMON_RTL)"
	@test -f "$(SPMV_CUPERFLOW_IP_TCL)" && test -f "$(SPMV_CUPERFLOW_PACKAGE_TCL)"

$(SPMV_CUPERFLOW_ELAB_DONE): FORCE spmv-cuperflow-fpga-check
	@set -e; \
	mkdir -p "$(SPMV_CUPERFLOW_WORK_DIR)"; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then echo 'SPMV Cuperflow FPGA elaboration dry run'; touch "$@"; exit 0; fi; \
	rm -rf "$(SPMV_CUPERFLOW_RTL_DIR)"; mkdir -p "$(SPMV_CUPERFLOW_RTL_DIR)"; \
	cd "$(CURDIR)/chisel/ysyxSoC" && NPC_SCALA_CONFIG="$(CONFIG_FQCN)" \
		mill -i ysyxsoc.runMain accelerators.spmv.fpga.ElaborateSpmvCuperflowFpga --target-dir "$(SPMV_CUPERFLOW_RTL_DIR)"; \
	test -f "$(SPMV_CUPERFLOW_RTL_DIR)/$(SPMV_CUPERFLOW_CHISEL_TOP).sv" && test -f "$(SPMV_CUPERFLOW_PARAMETER_MANIFEST)"; \
	{ printf '%s\n' '`define SPMV_CUPERFLOW_PLATFORM_CLOCK_MHZ $(FPGA_PLATFORM_CLOCK_MHZ)'; \
	  printf '%s\n' '`define SPMV_CUPERFLOW_CORE_CLOCK_MHZ $(FPGA_CLOCK_MHZ)'; \
	  cat "$(SPMV_CUPERFLOW_COMMON_RTL)" "$(SPMV_CUPERFLOW_WRAPPER)"; \
	} > "$(SPMV_CUPERFLOW_RTL_DIR)/spmv-cuperflow-kernel.sv"; \
	"$(SPMV_CUPERFLOW_MANIFEST_TOOL)" verify "$(SPMV_CUPERFLOW_PARAMETER_MANIFEST)" \
		CONFIG_FQCN=$(CONFIG_FQCN) SPMV_CUPERFLOW_HBM_PC_COUNT=$(SPMV_CUPERFLOW_HBM_PC_COUNT) \
		SPMV_CUPERFLOW_HBM_BASE=$(SPMV_CUPERFLOW_HBM_BASE) SPMV_CUPERFLOW_HBM_BYTES=$(SPMV_CUPERFLOW_HBM_BYTES) \
		SPMV_CUPERFLOW_X_REGION_BYTES=$(SPMV_CUPERFLOW_X_REGION_BYTES) \
		SPMV_CUPERFLOW_AXI_ADDR_WIDTH=$(SPMV_CUPERFLOW_AXI_ADDR_WIDTH) \
		SPMV_CUPERFLOW_AXI_DATA_WIDTH=$(SPMV_CUPERFLOW_AXI_DATA_WIDTH) \
		SPMV_CUPERFLOW_AXI_ID_WIDTH=$(SPMV_CUPERFLOW_AXI_ID_WIDTH) \
		SPMV_CUPERFLOW_MAX_OUTSTANDING_BURSTS=$(SPMV_CUPERFLOW_MAX_OUTSTANDING_BURSTS) \
		SPMV_CUPERFLOW_X_WINDOW_SIZE=$(SPMV_CUPERFLOW_X_WINDOW_SIZE) \
		SPMV_CUPERFLOW_X_REPLICA_COUNT=$(SPMV_CUPERFLOW_X_REPLICA_COUNT) \
		SPMV_CUPERFLOW_X_PINGPONG=$(SPMV_CUPERFLOW_X_PINGPONG) \
		SPMV_CUPERFLOW_X_BANK_COUNT=$(SPMV_CUPERFLOW_X_BANK_COUNT) \
		SPMV_CUPERFLOW_X_ELEMENT_WIDTH=$(SPMV_CUPERFLOW_X_ELEMENT_WIDTH) \
		SPMV_CUPERFLOW_X_STORAGE=$(SPMV_CUPERFLOW_X_STORAGE) \
		SPMV_CUPERFLOW_X_MEMORY_DATA_WIDTH=$(SPMV_CUPERFLOW_X_MEMORY_DATA_WIDTH) \
		SPMV_CUPERFLOW_X_LOAD_LANES=$(SPMV_CUPERFLOW_X_LOAD_LANES) \
		SPMV_CUPERFLOW_MAP_ABI=$(SPMV_CUPERFLOW_MAP_ABI) \
		SPMV_FP64_MUL_PROVIDER=$(SPMV_FP64_MUL_PROVIDER) SPMV_FP64_MUL_LATENCY=$(SPMV_FP64_MUL_LATENCY) \
		SPMV_FP64_MUL_II=$(SPMV_FP64_MUL_II); \
	touch "$@"

spmv-cuperflow-fpga-elaborate: $(SPMV_CUPERFLOW_ELAB_DONE)

$(SPMV_CUPERFLOW_IP_DONE): FORCE $(if $(filter 1,$(SPMV_CUPERFLOW_PHASE_PREREQUISITES)),$(SPMV_CUPERFLOW_ELAB_DONE)) spmv-cuperflow-fpga-check
	@set -e; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then mkdir -p "$(SPMV_CUPERFLOW_IP_DIR)"; printf 'dry-run FP64 XCI\n' > "$(SPMV_CUPERFLOW_XCI)"; touch "$@"; exit 0; fi; \
	rm -rf "$(SPMV_CUPERFLOW_IP_DIR)"; mkdir -p "$(SPMV_CUPERFLOW_IP_DIR)"; \
	vivado -mode batch -nojournal -nolog -source "$(SPMV_CUPERFLOW_IP_TCL)" -tclargs \
		"$(SPMV_CUPERFLOW_IP_DIR)/project" "$(FPGA_PART)" "$(SPMV_CUPERFLOW_XCI)" "$(FPGA_VIVADO_SYNTH_JOBS)" "$(CONSTRUCTION_PROFILE)"; \
	test -s "$(SPMV_CUPERFLOW_XCI)"; touch "$@"

spmv-cuperflow-fpga-ip: $(SPMV_CUPERFLOW_IP_DONE)

$(SPMV_CUPERFLOW_PACKAGE_DONE): FORCE $(if $(filter 1,$(SPMV_CUPERFLOW_PHASE_PREREQUISITES)),$(SPMV_CUPERFLOW_IP_DONE)) spmv-cuperflow-fpga-check
	@set -e; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then \
		mkdir -p "$(SPMV_CUPERFLOW_ARTIFACT_DIR)"; \
		printf 'dry-run XO\n' > "$(SPMV_CUPERFLOW_XO)"; \
		cp "$(SPMV_CUPERFLOW_XCI)" "$(SPMV_CUPERFLOW_ARTIFACT_DIR)/SpmvFp64MulXilinxCore.xci"; \
		"$(SPMV_CUPERFLOW_ARTIFACT_TOOL)" write --directory "$(SPMV_CUPERFLOW_ARTIFACT_DIR)" --source-root "$(abspath $(CURDIR)/..)" \
			--release-tag UNRELEASED --board u55c --variant "$(CONFIG_FQCN)" --type alveo --platform "$(FPGA_PLATFORM)" \
			--config-fqcn "$(CONFIG_FQCN)" --host-abi none --protocol-abi "$(PROTOCOL_ABI)" \
			$(foreach asset,$(SPMV_CUPERFLOW_PACKAGE_ASSETS),--asset "$(asset)"); \
		touch "$@"; exit 0; \
	fi; \
	"$(SPMV_CUPERFLOW_SOURCE_MANIFEST_TOOL)" write synthesis "$(SPMV_CUPERFLOW_SOURCE_MANIFEST)" "$(CURDIR)" --absolute \
		--rtl-dir "$(SPMV_CUPERFLOW_RTL_DIR)" \
		--xci-dir "$(SPMV_CUPERFLOW_IP_DIR)/SpmvFp64MulXilinxCore"; \
	"$(SPMV_CUPERFLOW_SOURCE_MANIFEST_TOOL)" verify "$(SPMV_CUPERFLOW_SOURCE_MANIFEST)" "$(CURDIR)" synthesis; \
	rm -rf "$(SPMV_CUPERFLOW_ARTIFACT_DIR)"; mkdir -p "$(SPMV_CUPERFLOW_ARTIFACT_DIR)"; \
	vivado -mode batch -nojournal -nolog -source "$(SPMV_CUPERFLOW_PACKAGE_TCL)" -tclargs \
		"$(SPMV_CUPERFLOW_WORK_DIR)/package" "$(FPGA_PART)" "$(SPMV_CUPERFLOW_TOP)" "$(SPMV_CUPERFLOW_SOURCE_MANIFEST)" \
		"$(SPMV_CUPERFLOW_XO)" "$(FPGA_VIVADO_SYNTH_JOBS)" "$(FPGA_PLATFORM_CLOCK_MHZ)" "$(FPGA_CLOCK_MHZ)" \
		"$(SPMV_CUPERFLOW_HBM_PC_COUNT)"; \
	cp "$(SPMV_CUPERFLOW_XCI)" "$(SPMV_CUPERFLOW_ARTIFACT_DIR)/SpmvFp64MulXilinxCore.xci"; \
	for asset in $(SPMV_CUPERFLOW_PACKAGE_ASSETS); do test -s "$(SPMV_CUPERFLOW_ARTIFACT_DIR)/$$asset"; done; \
	"$(SPMV_CUPERFLOW_ARTIFACT_TOOL)" write --directory "$(SPMV_CUPERFLOW_ARTIFACT_DIR)" --source-root "$(abspath $(CURDIR)/..)" \
		--release-tag UNRELEASED --board u55c --variant "$(CONFIG_FQCN)" --type alveo --platform "$(FPGA_PLATFORM)" \
		--config-fqcn "$(CONFIG_FQCN)" --host-abi none --protocol-abi "$(PROTOCOL_ABI)" \
		$(foreach asset,$(SPMV_CUPERFLOW_PACKAGE_ASSETS),--asset "$(asset)"); touch "$@"

spmv-cuperflow-fpga-package: $(SPMV_CUPERFLOW_PACKAGE_DONE)

$(SPMV_CUPERFLOW_LINK_DONE): FORCE $(if $(filter 1,$(SPMV_CUPERFLOW_PHASE_PREREQUISITES)),$(SPMV_CUPERFLOW_PACKAGE_DONE)) spmv-cuperflow-fpga-check
	@set -e; \
	if test "$(CAPABILITY)" != bitstream-only; then echo 'Cuperflow FPGA link 只属于 bitstream-only Config' >&2; exit 2; fi; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then \
		mkdir -p "$(SPMV_CUPERFLOW_ARTIFACT_DIR)"; printf 'dry-run xclbin\n' > "$(SPMV_CUPERFLOW_XCLBIN)"; printf '0.000\n' > "$(SPMV_CUPERFLOW_WNS)"; \
		"$(SPMV_CUPERFLOW_ARTIFACT_TOOL)" write --directory "$(SPMV_CUPERFLOW_ARTIFACT_DIR)" --source-root "$(abspath $(CURDIR)/..)" \
			--release-tag UNRELEASED --board u55c --variant "$(CONFIG_FQCN)" --type alveo --platform "$(FPGA_PLATFORM)" \
			--config-fqcn "$(CONFIG_FQCN)" --host-abi none --protocol-abi "$(PROTOCOL_ABI)" --timing-wns 0.000 \
			$(foreach asset,$(SPMV_CUPERFLOW_FINAL_ASSETS),--asset "$(asset)"); touch "$@"; exit 0; \
	fi; \
	"$(SPMV_CUPERFLOW_SOURCE_MANIFEST_TOOL)" verify "$(SPMV_CUPERFLOW_SOURCE_MANIFEST)" "$(CURDIR)" synthesis; \
	rm -rf "$(SPMV_CUPERFLOW_VITIS_TEMP_DIR)" "$(SPMV_CUPERFLOW_VITIS_LOG_DIR)" "$(SPMV_CUPERFLOW_VITIS_REPORT_DIR)"; \
	mkdir -p "$(SPMV_CUPERFLOW_VITIS_TEMP_DIR)" "$(SPMV_CUPERFLOW_VITIS_LOG_DIR)" "$(SPMV_CUPERFLOW_VITIS_REPORT_DIR)"; \
	{ printf '%s\n' '[connectivity]'; for pc in $$(seq 0 15); do printf 'sp=SpmvCuperflowKernel_1.m_axi_pc%02d:HBM[%d]\n' "$$pc" "$$pc"; done; \
		printf '%s\n' '' '[clock]'; printf 'freqHz=%s000000:SpmvCuperflowKernel_1.ap_clk\n' "$(FPGA_PLATFORM_CLOCK_MHZ)"; \
		printf '%s\n' '' '[vivado]' 'synth.jobs=$(FPGA_VIVADO_SYNTH_JOBS)' 'impl.jobs=$(FPGA_VIVADO_IMPL_JOBS)'; \
	} > "$(SPMV_CUPERFLOW_VITIS_LINK_CONFIG)"; \
	$(if $(filter unset,$(FPGA_VITIS_XRT_MODE)),env -u XILINX_XRT,) v++ --link --target "$(FPGA_VITIS_TARGET)" \
		--platform "$(FPGA_PLATFORM)" --config "$(SPMV_CUPERFLOW_VITIS_LINK_CONFIG)" \
		--temp_dir "$(SPMV_CUPERFLOW_VITIS_TEMP_DIR)" --log_dir "$(SPMV_CUPERFLOW_VITIS_LOG_DIR)" \
		--report_dir "$(SPMV_CUPERFLOW_VITIS_REPORT_DIR)" --output "$(SPMV_CUPERFLOW_XCLBIN)" "$(SPMV_CUPERFLOW_XO)"; \
	"$(SPMV_CUPERFLOW_TIMING_WNS_TOOL)" "$(SPMV_CUPERFLOW_VITIS_REPORT_DIR)" > "$(SPMV_CUPERFLOW_WNS)"; \
	wns=$$(cat "$(SPMV_CUPERFLOW_WNS)"); awk -v wns="$$wns" -v min="$(FPGA_TIMING_WNS_MIN_NS)" 'BEGIN { exit !(wns >= min) }'; \
	for asset in $(SPMV_CUPERFLOW_FINAL_ASSETS); do test -s "$(SPMV_CUPERFLOW_ARTIFACT_DIR)/$$asset"; done; \
	"$(SPMV_CUPERFLOW_ARTIFACT_TOOL)" write --directory "$(SPMV_CUPERFLOW_ARTIFACT_DIR)" --source-root "$(abspath $(CURDIR)/.. )" \
		--release-tag UNRELEASED --board u55c --variant "$(CONFIG_FQCN)" --type alveo --platform "$(FPGA_PLATFORM)" \
		--config-fqcn "$(CONFIG_FQCN)" --host-abi none --protocol-abi "$(PROTOCOL_ABI)" --timing-wns "$$wns" \
		$(foreach asset,$(SPMV_CUPERFLOW_FINAL_ASSETS),--asset "$(asset)"); touch "$@"

spmv-cuperflow-fpga-link: $(SPMV_CUPERFLOW_LINK_DONE)

.PHONY: spmv-cuperflow-fpga-check spmv-cuperflow-fpga-elaborate spmv-cuperflow-fpga-ip \
	spmv-cuperflow-fpga-package spmv-cuperflow-fpga-link
