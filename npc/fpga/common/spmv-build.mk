SPMV_BUILD_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
SPMV_WORK_DIR ?= $(CURDIR)/fpga/common/build/spmv/manual
SPMV_RTL_DIR := $(SPMV_WORK_DIR)/rtl
SPMV_SYNTH_DIR := $(SPMV_WORK_DIR)/synth
SPMV_ARTIFACT_DIR := $(SPMV_WORK_DIR)/artifacts
SPMV_SOURCE_MANIFEST := $(SPMV_WORK_DIR)/synthesis-sources.manifest
SPMV_PARAMETER_MANIFEST := $(SPMV_RTL_DIR)/spmv-parameters.env
SPMV_ELAB_DONE := $(SPMV_WORK_DIR)/.elaboration.complete
SPMV_SYNTH_DONE := $(SPMV_WORK_DIR)/.synthesis.complete
SPMV_LINK_DONE := $(SPMV_WORK_DIR)/.link.complete
SPMV_TOP := SpmvResourceProbeKernel
SPMV_CHISEL_TOP := SpmvResourceProbeTop
SPMV_WRAPPER := $(CURDIR)/fpga/u55c/rtl/spmv/spmv-resource-probe-kernel.sv
SPMV_SYNTH_TCL := $(CURDIR)/fpga/u55c/tcl/spmv/package-ooc-xo.tcl
SPMV_MANIFEST_TOOL := $(CURDIR)/fpga/common/scripts/manifest.sh
SPMV_SOURCE_MANIFEST_TOOL := $(CURDIR)/scripts/ip-source-manifest.sh
SPMV_ARTIFACT_TOOL := $(CURDIR)/fpga/common/scripts/artifact-manifest.sh
SPMV_XO := $(SPMV_ARTIFACT_DIR)/spmv-resource-probe.xo
SPMV_DCP := $(SPMV_ARTIFACT_DIR)/spmv-resource-probe.dcp
SPMV_UTILIZATION := $(SPMV_ARTIFACT_DIR)/spmv-utilization.rpt
SPMV_HIERARCHICAL_UTILIZATION := $(SPMV_ARTIFACT_DIR)/spmv-utilization-hierarchical.rpt
SPMV_TIMING := $(SPMV_ARTIFACT_DIR)/spmv-timing-summary.rpt
SPMV_XCLBIN := $(SPMV_ARTIFACT_DIR)/spmv-resource-probe.xclbin
SPMV_WNS := $(SPMV_ARTIFACT_DIR)/spmv-resource-probe.wns
SPMV_VITIS_TEMP_DIR := $(SPMV_WORK_DIR)/vitis-temp
SPMV_VITIS_LOG_DIR := $(SPMV_WORK_DIR)/vitis-logs
SPMV_VITIS_REPORT_DIR := $(SPMV_WORK_DIR)/vitis-reports
SPMV_VITIS_LINK_CONFIG := $(SPMV_WORK_DIR)/vitis-link.cfg
SPMV_TIMING_WNS_TOOL := $(CURDIR)/fpga/common/scripts/extract-timing-wns.sh
SPMV_REPORT_HOOK := $(SPMV_WORK_DIR)/spmv-implementation-reports.tcl
SPMV_SYNTH_ASSETS := spmv-resource-probe.xo spmv-resource-probe.dcp spmv-utilization.rpt \
	spmv-utilization-hierarchical.rpt spmv-timing-summary.rpt
SPMV_FINAL_ASSETS = $(if $(filter bitstream-only,$(CAPABILITY)),spmv-resource-probe.xclbin $(SPMV_SYNTH_ASSETS),$(SPMV_SYNTH_ASSETS))
SPMV_CLOCK_PERIOD_NS = $(shell awk -v mhz="$(SPMV_CLOCK_MHZ)" 'BEGIN { if (mhz > 0) printf "%.3f", 1000 / mhz }')

spmv-check:
	@test "$(CAPABILITY)" = synthesize-only || test "$(CAPABILITY)" = bitstream-only || { echo 'SPMV 构造必须使用 synthesize-only 或 bitstream-only capability' >&2; exit 2; }
	@test "$(TARGET)" = SPMV || { echo 'SPMV 构造必须使用 TARGET=SPMV' >&2; exit 2; }
	@test "$(FPGA_BOARD)" = u55c || { echo 'SPMV 资源探针只支持 U55C' >&2; exit 2; }
	@test "$(HOST_ABI)" = none && { test "$(PROTOCOL_ABI)" = spmv-resource-probe-v1 || test "$(PROTOCOL_ABI)" = spmv-resource-probe-v2; }
	@test "$(SPMV_HBM_PC_COUNT)" = 32 && test "$(SPMV_AXI_ADDR_WIDTH)" = 64
	@test "$(SPMV_AXI_DATA_WIDTH)" = 512 && test "$(SPMV_AXI_ID_WIDTH)" = 4
	@test "$(SPMV_X_ELEMENTS_PER_PC)" = 8192 && test "$(SPMV_X_STORAGE)" = uram
	@test "$(SPMV_X_READ_ELEMENTS_PER_CYCLE)" = "$(SPMV_PARALLEL_READ_LANES)"
	@test "$(SPMV_X_WRITE_ELEMENTS_PER_CYCLE)" = "$(SPMV_PARALLEL_WRITE_LANES)"
	@test "$(SPMV_BURST_BEATS)" = 64 && test "$(SPMV_OUTSTANDING_BURSTS_PER_PC)" = 1
	@test "$(SPMV_BASE_ALIGNMENT_BYTES)" = 4096
	@test "$(SPMV_CLOCK_MHZ)" = "$(FPGA_CLOCK_MHZ)"
	@test "$(FPGA_PLATFORM_CLOCK_MHZ)" = 300
	@test "$(FPGA_PART)" = xcu55c-fsvh2892-2L-e
	@if test "$(CAPABILITY)" = synthesize-only; then \
		test "$(PROTOCOL_ABI)" = spmv-resource-probe-v1 && test "$(SPMV_ELEMENT_WIDTH)" = 32 && \
		test "$(SPMV_URAM_BANKS_PER_PC)" = 1 && test "$(SPMV_X_READ_ELEMENTS_PER_CYCLE)" = 1 && \
		test "$(SPMV_X_WRITE_ELEMENTS_PER_CYCLE)" = 1 && test "$(SPMV_CLOCK_MHZ)" = 300; \
	else \
		test "$(PROTOCOL_ABI)" = spmv-resource-probe-v2 && test "$(SPMV_ELEMENT_WIDTH)" = 64 && \
		test "$(SPMV_URAM_BANKS_PER_PC)" = 4 && test "$(SPMV_URAM_BANK_DEPTH)" = 2048 && \
		test "$(SPMV_X_READ_ELEMENTS_PER_CYCLE)" = 8 && test "$(SPMV_X_WRITE_ELEMENTS_PER_CYCLE)" = 8 && \
		test "$(SPMV_CLOCK_MHZ)" = 225 && test "$(FPGA_VITIS_TARGET)" = hw; \
	fi

$(SPMV_ELAB_DONE): FORCE spmv-check
	@set -e; \
	mkdir -p "$(SPMV_WORK_DIR)"; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then echo 'SPMV elaboration dry run'; touch "$@"; exit 0; fi; \
	rm -rf "$(SPMV_RTL_DIR)"; mkdir -p "$(SPMV_RTL_DIR)"; \
	cd "$(CURDIR)/chisel/ysyxSoC" && NPC_SCALA_CONFIG="$(CONFIG_FQCN)" \
		mill -i ysyxsoc.runMain spmv.ElaborateSpmvResourceProbe --target-dir "$(SPMV_RTL_DIR)"; \
	test -f "$(SPMV_RTL_DIR)/$(SPMV_CHISEL_TOP).sv" && test -f "$(SPMV_PARAMETER_MANIFEST)"; \
	cp "$(SPMV_WRAPPER)" "$(SPMV_RTL_DIR)/"; \
	"$(SPMV_SOURCE_MANIFEST_TOOL)" write synthesis "$(SPMV_SOURCE_MANIFEST)" "$(CURDIR)" \
		--absolute --rtl-dir "$(SPMV_RTL_DIR)"; \
	"$(SPMV_SOURCE_MANIFEST_TOOL)" verify "$(SPMV_SOURCE_MANIFEST)" "$(CURDIR)" synthesis; \
	"$(SPMV_MANIFEST_TOOL)" verify "$(SPMV_PARAMETER_MANIFEST)" \
		CONFIG_FQCN=$(CONFIG_FQCN) SPMV_HBM_PC_COUNT=$(SPMV_HBM_PC_COUNT) \
		SPMV_AXI_ADDR_WIDTH=$(SPMV_AXI_ADDR_WIDTH) SPMV_AXI_DATA_WIDTH=$(SPMV_AXI_DATA_WIDTH) \
		SPMV_AXI_ID_WIDTH=$(SPMV_AXI_ID_WIDTH) SPMV_ELEMENT_WIDTH=$(SPMV_ELEMENT_WIDTH) \
		SPMV_X_ELEMENTS_PER_PC=$(SPMV_X_ELEMENTS_PER_PC) \
		SPMV_X_READ_ELEMENTS_PER_CYCLE=$(SPMV_X_READ_ELEMENTS_PER_CYCLE) \
		SPMV_X_WRITE_ELEMENTS_PER_CYCLE=$(SPMV_X_WRITE_ELEMENTS_PER_CYCLE) \
		SPMV_URAM_BANKS_PER_PC=$(SPMV_URAM_BANKS_PER_PC) SPMV_URAM_BANK_DEPTH=$(SPMV_URAM_BANK_DEPTH) \
		SPMV_PARALLEL_READ_LANES=$(SPMV_PARALLEL_READ_LANES) SPMV_PARALLEL_WRITE_LANES=$(SPMV_PARALLEL_WRITE_LANES) \
		SPMV_X_STORAGE=$(SPMV_X_STORAGE) SPMV_BURST_BEATS=$(SPMV_BURST_BEATS) \
			SPMV_BASE_ALIGNMENT_BYTES=$(SPMV_BASE_ALIGNMENT_BYTES) \
		SPMV_OUTSTANDING_BURSTS_PER_PC=$(SPMV_OUTSTANDING_BURSTS_PER_PC) \
		SPMV_CLOCK_MHZ=$(SPMV_CLOCK_MHZ); \
	touch "$@"

spmv-elaborate: $(SPMV_ELAB_DONE)

SPMV_SYNTH_PREREQUISITES := $(if $(filter 1,$(SPMV_PHASE_PREREQUISITES)),$(SPMV_ELAB_DONE))
$(SPMV_SYNTH_DONE): FORCE $(SPMV_SYNTH_PREREQUISITES) spmv-check
	@set -e; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then echo 'SPMV OOC synthesis dry run'; touch "$@"; exit 0; fi; \
	"$(SPMV_SOURCE_MANIFEST_TOOL)" verify "$(SPMV_SOURCE_MANIFEST)" "$(CURDIR)" synthesis; \
	rm -rf "$(SPMV_SYNTH_DIR)" "$(SPMV_ARTIFACT_DIR)"; \
	mkdir -p "$(SPMV_SYNTH_DIR)" "$(SPMV_ARTIFACT_DIR)"; \
	vivado -mode batch -nojournal -nolog -source "$(SPMV_SYNTH_TCL)" -tclargs \
		"$(SPMV_SYNTH_DIR)/project" "$(FPGA_PART)" "$(SPMV_TOP)" "$(SPMV_SOURCE_MANIFEST)" \
		"$(SPMV_XO)" "$(SPMV_DCP)" "$(SPMV_ARTIFACT_DIR)" "$(FPGA_VIVADO_SYNTH_JOBS)" \
		"$(SPMV_CLOCK_PERIOD_NS)" "$(SPMV_HBM_PC_COUNT)" "$(SPMV_CLOCK_MHZ)"; \
	for asset in $(SPMV_SYNTH_ASSETS); do test -s "$(SPMV_ARTIFACT_DIR)/$$asset"; done; \
	if test "$(CAPABILITY)" = synthesize-only; then \
		"$(SPMV_ARTIFACT_TOOL)" write --directory "$(SPMV_ARTIFACT_DIR)" --source-root "$(abspath $(CURDIR)/..)" \
			--release-tag UNRELEASED --board u55c --variant "$(CONFIG_FQCN)" --type alveo \
			--platform "$(FPGA_PLATFORM)" --config-fqcn "$(CONFIG_FQCN)" --host-abi none \
			--protocol-abi "$(PROTOCOL_ABI)" \
			$(foreach asset,$(SPMV_SYNTH_ASSETS),--asset "$(asset)"); \
	fi; \
	touch "$@"

spmv-ooc-synth: $(SPMV_SYNTH_DONE)

SPMV_LINK_PREREQUISITES := $(if $(filter 1,$(SPMV_PHASE_PREREQUISITES)),$(SPMV_SYNTH_DONE))
$(SPMV_LINK_DONE): FORCE $(SPMV_LINK_PREREQUISITES) spmv-check
	@set -e; \
	if test "$(CAPABILITY)" != bitstream-only; then echo 'SPMV link 只属于 bitstream-only Config' >&2; exit 2; fi; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then \
		mkdir -p "$(SPMV_ARTIFACT_DIR)"; printf 'dry-run xclbin\n' > "$(SPMV_XCLBIN)"; printf '0.000\n' > "$(SPMV_WNS)"; \
		"$(SPMV_ARTIFACT_TOOL)" write --directory "$(SPMV_ARTIFACT_DIR)" --source-root "$(abspath $(CURDIR)/..)" \
			--release-tag UNRELEASED --board u55c --variant "$(CONFIG_FQCN)" --type alveo \
			--platform "$(FPGA_PLATFORM)" --config-fqcn "$(CONFIG_FQCN)" --host-abi none \
			--protocol-abi "$(PROTOCOL_ABI)" --timing-wns 0.000 --asset spmv-resource-probe.xclbin; \
		touch "$@"; exit 0; \
	fi; \
	"$(SPMV_SOURCE_MANIFEST_TOOL)" verify "$(SPMV_SOURCE_MANIFEST)" "$(CURDIR)" synthesis; \
	rm -rf "$(SPMV_VITIS_TEMP_DIR)" "$(SPMV_VITIS_LOG_DIR)" "$(SPMV_VITIS_REPORT_DIR)"; \
	mkdir -p "$(SPMV_VITIS_TEMP_DIR)" "$(SPMV_VITIS_LOG_DIR)" "$(SPMV_VITIS_REPORT_DIR)" "$(SPMV_ARTIFACT_DIR)"; \
	{ printf '%s\n' '[connectivity]'; \
		for pc in $$(seq 0 31); do printf 'sp=SpmvResourceProbeKernel_1.m_axi_pc%02d:HBM[%d]\n' "$$pc" "$$pc"; done; \
		printf '%s\n' '' '[clock]'; printf 'freqHz=%s000000:SpmvResourceProbeKernel_1.ap_clk\n' "$(SPMV_CLOCK_MHZ)"; \
		printf '%s\n' '' '[vivado]' 'synth.jobs=$(FPGA_VIVADO_SYNTH_JOBS)' 'impl.jobs=$(FPGA_VIVADO_IMPL_JOBS)'; \
	} > "$(SPMV_VITIS_LINK_CONFIG)"; \
	$(if $(filter unset,$(FPGA_VITIS_XRT_MODE)),env -u XILINX_XRT,) v++ --link --target "$(FPGA_VITIS_TARGET)" \
		--platform "$(FPGA_PLATFORM)" --config "$(SPMV_VITIS_LINK_CONFIG)" \
		--temp_dir "$(SPMV_VITIS_TEMP_DIR)" --log_dir "$(SPMV_VITIS_LOG_DIR)" \
		--report_dir "$(SPMV_VITIS_REPORT_DIR)" --output "$(SPMV_XCLBIN)" \
		"$(SPMV_XO)"; \
	"$(SPMV_TIMING_WNS_TOOL)" "$(SPMV_VITIS_REPORT_DIR)" > "$(SPMV_WNS)"; \
	wns=$$(cat "$(SPMV_WNS)"); awk -v wns="$$wns" -v min="$(FPGA_TIMING_WNS_MIN_NS)" 'BEGIN { exit !(wns >= min) }'; \
	for asset in $(SPMV_FINAL_ASSETS); do test -s "$(SPMV_ARTIFACT_DIR)/$$asset"; done; \
	"$(SPMV_ARTIFACT_TOOL)" write --directory "$(SPMV_ARTIFACT_DIR)" --source-root "$(abspath $(CURDIR)/..)" \
		--release-tag UNRELEASED --board u55c --variant "$(CONFIG_FQCN)" --type alveo \
		--platform "$(FPGA_PLATFORM)" --config-fqcn "$(CONFIG_FQCN)" --host-abi none \
		--protocol-abi "$(PROTOCOL_ABI)" --timing-wns "$$wns" \
		$(foreach asset,$(SPMV_FINAL_ASSETS),--asset "$(asset)"); \
	touch "$@"

spmv-link: $(SPMV_LINK_DONE)

.PHONY: spmv-check spmv-elaborate spmv-ooc-synth spmv-link
