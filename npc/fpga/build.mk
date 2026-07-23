FPGA_MANIFEST_TOOL := $(FPGA_ROOT)/common/scripts/manifest.sh
FPGA_ARTIFACT_MANIFEST_TOOL := $(FPGA_ROOT)/common/scripts/artifact-manifest.sh
FPGA_RELEASE_CONSTRUCTION_TOOL := $(FPGA_ROOT)/common/scripts/release-construction.sh
FPGA_TIMING_WNS_TOOL := $(FPGA_ROOT)/common/scripts/extract-timing-wns.sh
FPGA_RELEASE_CONSTRUCTIONS ?= $(FPGA_ROOT)/releases/v0.2.0-fpga-sdb/constructions.env
RELEASE_CONSTRUCTION ?=
FPGA_BOARD_RTL_DIR := $(FPGA_BOARD_DIR)/rtl
FPGA_IP_ADAPTER_DIR := $(FPGA_ROOT)/ip/adapters/xilinx
FPGA_IP_GENERATOR := $(FPGA_ROOT)/ip/generators/xilinx/create-arithmetic-ip.tcl
FPGA_RTL_DIR := $(FPGA_WORK_DIR)/rtl
FPGA_IP_DIR := $(FPGA_WORK_DIR)/ip
FPGA_IP_LOG_DIR := $(FPGA_IP_DIR)/logs
FPGA_SYNTH_DIR := $(FPGA_WORK_DIR)/synth
FPGA_ARTIFACT_DIR := $(FPGA_WORK_DIR)/artifacts
FPGA_VITIS_TEMP_DIR := $(FPGA_WORK_DIR)/vitis-temp
FPGA_VITIS_LOG_DIR := $(FPGA_WORK_DIR)/vitis-logs
FPGA_VITIS_REPORT_DIR := $(FPGA_WORK_DIR)/vitis-reports
FPGA_VITIS_LINK_CONFIG := $(FPGA_WORK_DIR)/vitis-link.cfg
FPGA_IMPLEMENTATION_REPORT_TCL := $(FPGA_ROOT)/common/tcl/implementation-reports.tcl
FPGA_U55C_REPORT_HOOK := $(FPGA_WORK_DIR)/u55c-implementation-reports.tcl
FPGA_ELAB_RTL := $(FPGA_RTL_DIR)/NpcFpgaTop.sv
FPGA_ELAB_PARAMETERS := $(FPGA_RTL_DIR)/fpga-parameters.env
FPGA_IP_MANIFEST := $(FPGA_IP_DIR)/manifest.env
FPGA_IP_ACTUAL_MANIFEST := $(FPGA_IP_DIR)/actual.env
FPGA_ELAB_DONE := $(FPGA_WORK_DIR)/.elaboration.complete
FPGA_IP_DONE := $(FPGA_WORK_DIR)/.ip.complete
FPGA_SYNTH_DONE := $(FPGA_WORK_DIR)/.synthesis.complete
FPGA_LINK_DONE := $(FPGA_WORK_DIR)/.link.complete
FPGA_TOP := NpcFpgaTop
FPGA_SYNTH_TOP := $(if $(filter zynqmp,$(FPGA_TYPE)),$(FPGA_TOP),NpcFpgaKernel)
FPGA_DCP := $(FPGA_SYNTH_DIR)/$(FPGA_TOP).dcp
FPGA_XO := $(FPGA_SYNTH_DIR)/$(FPGA_TOP).xo
FPGA_BIT := $(FPGA_ARTIFACT_DIR)/npc.bit
FPGA_XSA := $(FPGA_ARTIFACT_DIR)/npc.xsa
FPGA_XCLBIN := $(FPGA_ARTIFACT_DIR)/npc-$(FPGA_PLATFORM).xclbin
FPGA_ARTIFACT_MANIFEST := $(FPGA_ARTIFACT_DIR)/artifact-manifest.env
FPGA_ARTIFACT_CHECKSUMS := $(FPGA_ARTIFACT_DIR)/SHA256SUMS
FPGA_TIMING_WNS_FILE := $(FPGA_ARTIFACT_DIR)/npc.wns
FPGA_FINAL_ARTIFACT := $(if $(filter zynqmp,$(FPGA_TYPE)),$(FPGA_BIT),$(FPGA_XCLBIN))
FPGA_ZCU102_RUNTIME_GENERATOR := $(FPGA_BOARD_DIR)/scripts/generate-runtime.sh
FPGA_RUNTIME_OUTPUTS := $(if $(filter zynqmp,$(FPGA_TYPE)),$(FPGA_ARTIFACT_DIR)/system-user.dtsi $(FPGA_ARTIFACT_DIR)/npc-zcu102.env,)
FPGA_PACKAGE_ASSETS := $(if $(filter zynqmp,$(FPGA_TYPE)),$(FPGA_BIT) $(FPGA_XSA) $(FPGA_RUNTIME_OUTPUTS),$(FPGA_XCLBIN))
FPGA_LINK_OUTPUTS := $(FPGA_PACKAGE_ASSETS) $(FPGA_TIMING_WNS_FILE)
FPGA_SYNTH_TCL := $(if $(filter zynqmp,$(FPGA_TYPE)),$(FPGA_BOARD_DIR)/tcl/synth.tcl,$(FPGA_BOARD_DIR)/tcl/package-xo.tcl)
FPGA_LINK_TCL := $(FPGA_BOARD_DIR)/tcl/link.tcl
FPGA_LINK_CONFIG := $(FPGA_BOARD_DIR)/link.cfg
FPGA_LINK_INPUTS := $(FPGA_BOARD_DIR) $(FPGA_TIMING_WNS_TOOL)
FPGA_TOOL_DRY_RUN ?= 0
FPGA_SKIP_TOOL_VERSION_CHECK ?= 0
FPGA_RELEASE_TAG ?= UNRELEASED
FPGA_TIMING_WNS ?=
FPGA_FORMAL_RELEASE ?= 0
FPGA_PHASE_PREREQUISITES ?= 1
FPGA_FIRTOOL_VERSION := 1.105.0
FPGA_VIVADO_ROOT ?= $(abspath $(dir $(shell command -v vivado))/..)
FPGA_BOARD_REPO ?= $(FPGA_VIVADO_ROOT)/data/xhub/boards/XilinxBoardStore/boards/Xilinx
# `unset` 只作用于 Vitis 子进程。它让 Vitis 2022.2 选择自带 xclbinutil，避免本机
# 新版 XRT 覆盖后要求不兼容的 Boost 运行库；不会影响 FPGA 运行宿主的 XRT 环境。
FPGA_VITIS_ENV := $(if $(filter unset,$(FPGA_VITIS_XRT_MODE)),env -u XILINX_XRT,)

fpga_hex = $(shell printf '0x%x' $(1))

FPGA_MANIFEST_VALUES = \
		CONFIG_SCHEMA=$(FPGA_CONFIG_SCHEMA) CONFIG_NAME=$(FPGA_CONFIG_NAME) \
		FPGA_SCALA_CONFIG=$(FPGA_SCALA_CONFIG) \
		FPGA_NAME=$(FPGA_NAME) FPGA_TYPE=$(FPGA_TYPE) FPGA_PART=$(FPGA_PART) FPGA_PLATFORM=$(FPGA_PLATFORM) \
		BOARD_PART=$(FPGA_BOARD_PART) CLOCK_MHZ=$(FPGA_CLOCK_MHZ) VIVADO_VERSION=$(FPGA_VIVADO_VERSION) \
		VITIS_VERSION=$(FPGA_VITIS_VERSION) VITIS_TARGET=$(FPGA_VITIS_TARGET) \
		VITIS_XRT_MODE=$(FPGA_VITIS_XRT_MODE) \
		TIMING_WNS_MIN_NS=$(FPGA_TIMING_WNS_MIN_NS) VIVADO_SYNTH_JOBS=$(FPGA_VIVADO_SYNTH_JOBS) \
		VIVADO_IMPL_JOBS=$(FPGA_VIVADO_IMPL_JOBS) VIVADO_IMPL_STRATEGY=$(FPGA_VIVADO_IMPL_STRATEGY) \
		VIVADO_IMPL_STRATEGY_SEARCH=$(FPGA_VIVADO_IMPL_STRATEGY_SEARCH) \
		REPORT_TIMING_MAX_PATHS=$(FPGA_REPORT_TIMING_MAX_PATHS) \
		REPORT_TIMING_PATHS_PER_CLOCK=$(FPGA_REPORT_TIMING_PATHS_PER_CLOCK) \
		REPORT_CONGESTION=$(FPGA_REPORT_CONGESTION) REPORT_CLOCK_UTILIZATION=$(FPGA_REPORT_CLOCK_UTILIZATION) \
		REPORT_CONTROL_SETS=$(FPGA_REPORT_CONTROL_SETS) REPORT_HIGH_FANOUT_NETS=$(FPGA_REPORT_HIGH_FANOUT_NETS) \
		REPORT_METHODOLOGY=$(FPGA_REPORT_METHODOLOGY) REPORT_QOR_SUGGESTIONS=$(FPGA_REPORT_QOR_SUGGESTIONS) \
		XLEN=$(NPC_XLEN) M=$(FPGA_M) F=$(NPC_F) ZICSR=$(ZICSR) FLOATING_FALLBACK=$(FPGA_FLOATING_FALLBACK) \
		MEMORY_BASE=$(FPGA_MEMORY_BASE) MEMORY_HOST_BASE=$(FPGA_MEMORY_HOST_BASE) MEMORY_SIZE=$(FPGA_MEMORY_SIZE) \
	MUL_LATENCY=$(NPC_MUL_CYCLES) MUL_II=$(NPC_MUL_II) DIV_LATENCY=$(NPC_DIV_CYCLES) DIV_II=$(NPC_DIV_II) \
	DIV_IP_LATENCY=$(FPGA_DIV_IP_CYCLES) DIV_ADAPTER_LATENCY=$(FPGA_DIV_ADAPTER_CYCLES) \
	FADD_LATENCY=$(NPC_FADD_CYCLES) FADD_II=$(NPC_FADD_II) FMUL_LATENCY=$(NPC_FMUL_CYCLES) FMUL_II=$(NPC_FMUL_II) \
	FDIV_LATENCY=$(NPC_FDIV_CYCLES) FDIV_II=$(NPC_FDIV_II) FMA_LATENCY=$(NPC_FFMA_CYCLES) FMA_II=$(NPC_FFMA_II) \
	FSQRT_LATENCY=$(NPC_FSQRT_CYCLES) FSQRT_II=$(NPC_FSQRT_II) FCVT_LATENCY=$(NPC_FCVT_CYCLES) FCVT_II=$(NPC_FCVT_II) \
	FCMP_LATENCY=$(NPC_FCMP_CYCLES) FCMP_II=$(NPC_FCMP_II)

FPGA_SOC_ELAB_ARGS = FPGA_SCALA_CONFIG=$(FPGA_SCALA_CONFIG) FPGA_OUTPUT=$(FPGA_RTL_DIR)

FPGA_ELAB_MANIFEST_VALUES = \
	CONFIG_FQCN=$(FPGA_SCALA_CONFIG) FPGA_BOARD=$(FPGA_CONFIG_NAME) FPGA_CLOCK_MHZ=$(FPGA_CLOCK_MHZ) \
	FPGA_MEMORY_BASE=$(call fpga_hex,$(FPGA_MEMORY_BASE)) \
	FPGA_MEMORY_SIZE=$(call fpga_hex,$(FPGA_MEMORY_SIZE)) \
	FPGA_MEMORY_HOST_BASE=$(call fpga_hex,$(FPGA_MEMORY_HOST_BASE)) \
	FPGA_CONTROL_BASE=$(call fpga_hex,$(FPGA_CONTROL_BASE)) \
	FPGA_MAILBOX_BASE=$(call fpga_hex,$(FPGA_MAILBOX_BASE)) \
	FPGA_DIV_IP_CYCLES=$(FPGA_DIV_IP_CYCLES) FPGA_DIV_ADAPTER_CYCLES=$(FPGA_DIV_ADAPTER_CYCLES) \
	NPC_TARGET=$(NPC_TARGET) NPC_XLEN=$(NPC_XLEN) NPC_PIPELINE=$(NPC_PIPELINE) NPC_ZICSR=$(ZICSR) \
	NPC_INTERLOCK=$(NPC_INTERLOCK) NPC_ID_FWD=$(NPC_ID_FWD) NPC_EX_FWD=$(NPC_EX_FWD) \
	NPC_F=$(NPC_F) FPGA_FLOATING_FALLBACK=$(FPGA_FLOATING_FALLBACK) NPC_ARITH_BACKEND=$(NPC_ARITH_BACKEND) NPC_ARITH_OUTPUT_FIFO=$(NPC_ARITH_OUTPUT_FIFO) \
	NPC_MUL_CYCLES=$(NPC_MUL_CYCLES) NPC_MUL_II=$(NPC_MUL_II) NPC_DIV_CYCLES=$(NPC_DIV_CYCLES) NPC_DIV_II=$(NPC_DIV_II) \
	NPC_FADD_CYCLES=$(NPC_FADD_CYCLES) NPC_FADD_II=$(NPC_FADD_II) NPC_FMUL_CYCLES=$(NPC_FMUL_CYCLES) NPC_FMUL_II=$(NPC_FMUL_II) \
	NPC_FDIV_CYCLES=$(NPC_FDIV_CYCLES) NPC_FDIV_II=$(NPC_FDIV_II) NPC_FFMA_CYCLES=$(NPC_FFMA_CYCLES) NPC_FFMA_II=$(NPC_FFMA_II) \
	NPC_FSQRT_CYCLES=$(NPC_FSQRT_CYCLES) NPC_FSQRT_II=$(NPC_FSQRT_II) NPC_FCVT_CYCLES=$(NPC_FCVT_CYCLES) NPC_FCVT_II=$(NPC_FCVT_II) \
	NPC_FCMP_CYCLES=$(NPC_FCMP_CYCLES) NPC_FCMP_II=$(NPC_FCMP_II) \
	FPGA_NOTIFICATION_MODE=$(FPGA_NOTIFICATION_MODE) $(FPGA_OPERATOR_ROUTE_VALUES)

# `profile.env` 是路由表的唯一文本来源；将全部固定 OPERATOR_ROUTE_* 字段一并
# 纳入 elaboration 清单，避免生成 RTL 与保存构造的单条路由漂移。
FPGA_OPERATOR_ROUTE_VALUES = $(foreach route,$(sort $(filter OPERATOR_ROUTE_%,$(.VARIABLES))),$(route)=$($(route)))

FPGA_IP_ACTUAL_VALUES = \
	MUL_LATENCY=$(NPC_MUL_CYCLES) MUL_II=$(NPC_MUL_II) \
	DIV_IP_LATENCY=$(FPGA_DIV_IP_CYCLES) DIV_II=$(NPC_DIV_II)

fpga-config:
	@printf '%s\n' \
		'config=$(FPGA_CONFIG_NAME)' 'config_file=$(FPGA_CONFIG_RESOLVED)' \
		'scala_config=$(FPGA_SCALA_CONFIG)' \
		'board=$(FPGA_NAME)' 'type=$(FPGA_TYPE)' 'part=$(FPGA_PART)' 'platform=$(FPGA_PLATFORM)' \
		'clock_mhz=$(FPGA_CLOCK_MHZ)' 'timing_wns_min_ns=$(FPGA_TIMING_WNS_MIN_NS)' \
		'floating_fallback=$(FPGA_FLOATING_FALLBACK)' 'notification_mode=$(FPGA_NOTIFICATION_MODE)' \
		'vivado_version=$(FPGA_VIVADO_VERSION)' 'vitis_version=$(FPGA_VITIS_VERSION)' \
		'vitis_target=$(FPGA_VITIS_TARGET)' 'vitis_xrt_mode=$(FPGA_VITIS_XRT_MODE)' \
		'vivado_synth_jobs=$(FPGA_VIVADO_SYNTH_JOBS)' \
		'vivado_impl_jobs=$(FPGA_VIVADO_IMPL_JOBS)' \
		'vivado_impl_strategy_candidate=$(FPGA_VIVADO_IMPL_STRATEGY)' \
		'vivado_impl_strategy_search=$(FPGA_VIVADO_IMPL_STRATEGY_SEARCH)' \
		'vivado_impl_strategy_mode=$(if $(filter 1,$(FPGA_VIVADO_IMPL_STRATEGY_SEARCH)),default-plus-candidate,platform-default)' \
		'vivado_report_timing_max_paths=$(FPGA_REPORT_TIMING_MAX_PATHS)' \
		'vivado_report_timing_paths_per_clock=$(FPGA_REPORT_TIMING_PATHS_PER_CLOCK)' \
		'vivado_report_congestion=$(FPGA_REPORT_CONGESTION)' \
		'vivado_report_clock_utilization=$(FPGA_REPORT_CLOCK_UTILIZATION)' \
		'vivado_report_control_sets=$(FPGA_REPORT_CONTROL_SETS)' \
		'vivado_report_high_fanout_nets=$(FPGA_REPORT_HIGH_FANOUT_NETS)' \
		'vivado_report_methodology=$(FPGA_REPORT_METHODOLOGY)' \
		'vivado_report_qor_suggestions=$(FPGA_REPORT_QOR_SUGGESTIONS)' \
		'soc=$(FPGA_SOC)' 'target=$(NPC_TARGET)' 'xlen=$(NPC_XLEN)' \
		'isa=$(FPGA_ISA_LABEL)' 'backend=$(NPC_ARITH_BACKEND)' \
		'output=$(FPGA_WORK_DIR)'

fpga-check:
	@case "$(FPGA_SKIP_TOOL_VERSION_CHECK)" in 0|1) ;; *) echo 'FPGA_SKIP_TOOL_VERSION_CHECK must be 0 or 1' >&2; exit 2;; esac
	@test "$(FPGA_CLOCK_MHZ)" -gt 0
	@case "$(FPGA_VIVADO_VERSION)" in [0-9]*.[0-9]*) ;; *) echo 'FPGA_VIVADO_VERSION must be a Vivado major.minor version' >&2; exit 2;; esac
	@case "$(FPGA_VITIS_VERSION)" in none|[0-9]*.[0-9]*) ;; *) echo 'FPGA_VITIS_VERSION must be none or a Vitis major.minor version' >&2; exit 2;; esac
	@case "$(FPGA_VITIS_XRT_MODE)" in inherit|unset) ;; *) echo 'FPGA_VITIS_XRT_MODE must be inherit or unset' >&2; exit 2;; esac
	@case "$(FPGA_VIVADO_SYNTH_JOBS)" in ''|0|*[!0-9]*) echo 'FPGA_VIVADO_SYNTH_JOBS must be a positive integer' >&2; exit 2;; esac
	@case "$(FPGA_VIVADO_IMPL_JOBS)" in ''|0|*[!0-9]*) echo 'FPGA_VIVADO_IMPL_JOBS must be a positive integer' >&2; exit 2;; esac
	@case "$(FPGA_VIVADO_IMPL_STRATEGY)" in ''|*[!A-Za-z0-9_]*) echo 'FPGA_VIVADO_IMPL_STRATEGY contains unsafe characters' >&2; exit 2;; esac
	@case "$(FPGA_VIVADO_IMPL_STRATEGY_SEARCH)" in 0|1) ;; *) echo 'FPGA_VIVADO_IMPL_STRATEGY_SEARCH must be 0 or 1' >&2; exit 2;; esac
	@case "$(FPGA_REPORT_TIMING_MAX_PATHS)" in ''|0|*[!0-9]*) echo 'FPGA_REPORT_TIMING_MAX_PATHS must be a positive integer' >&2; exit 2;; esac
	@case "$(FPGA_REPORT_TIMING_PATHS_PER_CLOCK)" in ''|0|*[!0-9]*) echo 'FPGA_REPORT_TIMING_PATHS_PER_CLOCK must be a positive integer' >&2; exit 2;; esac
	@for setting in \
		FPGA_REPORT_CONGESTION=$(FPGA_REPORT_CONGESTION) \
		FPGA_REPORT_CLOCK_UTILIZATION=$(FPGA_REPORT_CLOCK_UTILIZATION) \
		FPGA_REPORT_CONTROL_SETS=$(FPGA_REPORT_CONTROL_SETS) \
		FPGA_REPORT_HIGH_FANOUT_NETS=$(FPGA_REPORT_HIGH_FANOUT_NETS) \
		FPGA_REPORT_METHODOLOGY=$(FPGA_REPORT_METHODOLOGY) \
		FPGA_REPORT_QOR_SUGGESTIONS=$(FPGA_REPORT_QOR_SUGGESTIONS); do \
		key=$${setting%%=*}; value=$${setting#*=}; \
		case "$$value" in 0|1) ;; *) echo "$$key must be 0 or 1" >&2; exit 2;; esac; \
	done
	@awk -v min="$(FPGA_TIMING_WNS_MIN_NS)" 'BEGIN { exit !(min >= 0) }' || { echo 'FPGA_TIMING_WNS_MIN_NS must be nonnegative' >&2; exit 2; }
	@if test "$(FPGA_TYPE)" = alveo && test "$(FPGA_VITIS_TARGET)" != hw; then echo 'Alveo builds require FPGA_VITIS_TARGET=hw' >&2; exit 2; fi
	@if test "$(FPGA_TYPE)" != alveo && test "$(FPGA_VITIS_TARGET)" != none; then echo 'non-Alveo builds require FPGA_VITIS_TARGET=none' >&2; exit 2; fi
	@if test "$(FPGA_TYPE)" != alveo && test "$(FPGA_VITIS_XRT_MODE)" != inherit; then echo 'non-Alveo builds require FPGA_VITIS_XRT_MODE=inherit' >&2; exit 2; fi
	@if test "$(FPGA_TOOL_DRY_RUN)" != 1 && test "$(FPGA_SKIP_TOOL_VERSION_CHECK)" != 1; then \
		vivado_version=$$(vivado -version 2>&1 | sed -n '/[^[:space:]]/{p;q;}'); \
		case "$$vivado_version" in *v$(FPGA_VIVADO_VERSION)* ) ;; *) echo "Vivado version mismatch: expected $(FPGA_VIVADO_VERSION), got $$vivado_version" >&2; exit 2;; esac; \
		if test "$(FPGA_TYPE)" = alveo; then \
			vitis_version=$$($(FPGA_VITIS_ENV) v++ --version 2>&1 | sed -n '/[^[:space:]]/{p;q;}'); \
			case "$$vitis_version" in *v$(FPGA_VITIS_VERSION)* ) ;; *) echo "Vitis version mismatch: expected $(FPGA_VITIS_VERSION), got $$vitis_version" >&2; exit 2;; esac; \
		fi; \
	fi
	@test "$(NPC_MUL_CYCLES)" -gt 0 && test "$(NPC_DIV_CYCLES)" -gt 0
	@test "$$(( $(FPGA_DIV_IP_CYCLES) + $(FPGA_DIV_ADAPTER_CYCLES) ))" -eq "$(NPC_DIV_CYCLES)" || { \
		echo 'DIV latency must equal FPGA_DIV_IP_CYCLES + FPGA_DIV_ADAPTER_CYCLES' >&2; exit 2; }
	@test "$(NPC_FADD_CYCLES)" -gt 0 && test "$(NPC_FMUL_CYCLES)" -gt 0
	@test "$(NPC_FDIV_CYCLES)" -gt 0 && test "$(NPC_FFMA_CYCLES)" -gt 0
	@test "$(NPC_FSQRT_CYCLES)" -gt 0 && test "$(NPC_FCVT_CYCLES)" -gt 0 && test "$(NPC_FCMP_CYCLES)" -gt 0
	@echo "FPGA config $(FPGA_CONFIG_NAME) is valid"

fpga-plan: fpga-config
	@printf '%s\n' \
		'elaboration -> $(FPGA_ELAB_RTL)' \
		'IP generation -> $(FPGA_IP_DIR)' \
		'synthesis -> $(if $(filter zynqmp,$(FPGA_TYPE)),$(FPGA_DCP),$(FPGA_XO))' \
		'link -> $(FPGA_FINAL_ARTIFACT)'

$(FPGA_ELAB_DONE): FORCE fpga-check
	@set -e; \
	mkdir -p "$(FPGA_WORK_DIR)"; \
	echo '=== Elaborating synthesizable FPGA top ==='; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then \
		if test -n "$(FPGA_SOC)"; then \
			echo "make -C chisel/ysyxSoC fpga-verilog FPGA_OUTPUT=$(FPGA_RTL_DIR) ..."; \
		else echo "cd chisel/ysyxSoC && mill -i ysyxsoc.runMain scpu.ElaborateFPGA --target-dir $(FPGA_RTL_DIR)"; fi; \
		touch "$@"; exit 0; \
	fi; \
	rm -rf "$(FPGA_RTL_DIR)"; mkdir -p "$(FPGA_RTL_DIR)"; \
	if test -n "$(FPGA_SOC)"; then \
		$(MAKE) -C ./chisel/ysyxSoC fpga-verilog INTERNAL_CONSTRUCTION=1 config= FPGA_OUTPUT="$(FPGA_RTL_DIR)" $(FPGA_SOC_ELAB_ARGS); \
	else cd ./chisel/ysyxSoC && ./patch/update-firtool.sh $(FPGA_FIRTOOL_VERSION) ./patch/firtool && \
		NPC_SCALA_CONFIG="$(FPGA_SCALA_CONFIG)" \
		CHISEL_FIRTOOL_PATH="$(CURDIR)/chisel/ysyxSoC/patch/firtool/firtool-$(FPGA_FIRTOOL_VERSION)/bin" \
		mill -i ysyxsoc.runMain scpu.ElaborateFPGA --target-dir "$(FPGA_RTL_DIR)"; fi; \
	test -f "$(FPGA_ELAB_RTL)" && test -f "$(FPGA_ELAB_PARAMETERS)"; \
	"$(FPGA_MANIFEST_TOOL)" verify "$(FPGA_ELAB_PARAMETERS)" $(FPGA_ELAB_MANIFEST_VALUES); \
	touch "$@"

fpga-elaborate: $(FPGA_ELAB_DONE)

$(FPGA_IP_DONE): FORCE fpga-check
	@set -e; \
	mkdir -p "$(FPGA_WORK_DIR)"; \
	echo '=== Generating FPGA integer IP ==='; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then \
		echo "vivado -mode batch -source $(FPGA_IP_GENERATOR) -tclargs ..."; touch "$@"; exit 0; \
	fi; \
	rm -rf "$(FPGA_IP_DIR)"; mkdir -p "$(FPGA_IP_DIR)"; \
	vivado -mode batch -nojournal -nolog -source "$(FPGA_IP_GENERATOR)" -tclargs \
		"$(FPGA_IP_DIR)" "$(FPGA_IP_ACTUAL_MANIFEST)" "$(FPGA_IP_LOG_DIR)" "$(FPGA_PART)" "$(NPC_XLEN)" \
		"$(NPC_MUL_CYCLES)" "$(NPC_MUL_II)" "$(FPGA_DIV_IP_CYCLES)" "$(NPC_DIV_II)"; \
	"$(FPGA_MANIFEST_TOOL)" write "$(FPGA_IP_MANIFEST)" $(FPGA_MANIFEST_VALUES); \
	touch "$@"

fpga-ip: $(FPGA_IP_DONE)
	@if test "$(FPGA_TOOL_DRY_RUN)" != 1; then \
		"$(FPGA_MANIFEST_TOOL)" verify "$(FPGA_IP_MANIFEST)" $(FPGA_MANIFEST_VALUES); \
		"$(FPGA_MANIFEST_TOOL)" verify "$(FPGA_IP_ACTUAL_MANIFEST)" $(FPGA_IP_ACTUAL_VALUES); \
	fi

FPGA_SYNTH_PREREQUISITES := $(if $(filter 1,$(FPGA_PHASE_PREREQUISITES)),$(FPGA_ELAB_DONE) $(FPGA_IP_DONE))
$(FPGA_SYNTH_DONE): FORCE $(FPGA_SYNTH_PREREQUISITES)
	@set -e; \
	mkdir -p "$(FPGA_WORK_DIR)"; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then echo 'FPGA synthesis dry run'; touch "$@"; exit 0; fi; \
	"$(FPGA_MANIFEST_TOOL)" verify "$(FPGA_IP_MANIFEST)" $(FPGA_MANIFEST_VALUES); \
	echo '=== Synthesizing FPGA design ==='; \
	rm -rf "$(FPGA_SYNTH_DIR)"; mkdir -p "$(FPGA_SYNTH_DIR)"; \
	if test "$(FPGA_TYPE)" = zynqmp; then \
		vivado -mode batch -nojournal -nolog -source "$(FPGA_SYNTH_TCL)" -tclargs \
			"$(FPGA_SYNTH_DIR)/project" "$(FPGA_PART)" "$(FPGA_TOP)" "$(FPGA_RTL_DIR)" \
			"$(FPGA_BOARD_RTL_DIR)" "$(FPGA_IP_ADAPTER_DIR)" "$(FPGA_IP_DIR)" "$(FPGA_DCP)" \
			"$(FPGA_VIVADO_SYNTH_JOBS)"; \
	else \
		vivado -mode batch -nojournal -nolog -source "$(FPGA_SYNTH_TCL)" -tclargs \
			"$(FPGA_SYNTH_DIR)/project" "$(FPGA_PART)" "$(FPGA_SYNTH_TOP)" "$(FPGA_RTL_DIR)" \
			"$(FPGA_BOARD_RTL_DIR)" "$(FPGA_IP_ADAPTER_DIR)" "$(FPGA_IP_DIR)" "$(FPGA_XO)" "$(NPC_XLEN)" \
			"$(FPGA_VIVADO_SYNTH_JOBS)" "$(FPGA_CLOCK_MHZ)"; \
	fi; \
	test -f "$(if $(filter zynqmp,$(FPGA_TYPE)),$(FPGA_DCP),$(FPGA_XO))"; touch "$@"

fpga-synth: $(FPGA_SYNTH_DONE)

FPGA_LINK_PREREQUISITES := $(if $(filter 1,$(FPGA_PHASE_PREREQUISITES)),$(FPGA_SYNTH_DONE))
$(FPGA_LINK_DONE): FORCE $(FPGA_LINK_PREREQUISITES)
	@set -e; \
	mkdir -p "$(FPGA_WORK_DIR)"; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then echo 'FPGA link dry run'; touch "$@"; exit 0; fi; \
	mkdir -p "$(FPGA_ARTIFACT_DIR)"; \
	if test "$(FPGA_TYPE)" = alveo; then \
		{ \
			printf '%s\n' \
				'# 由构造 Config 生成；先保留 Vitis 默认 post-route hook，再追加 NPC 报告。' \
				'set npc_report_timing_max_paths $(FPGA_REPORT_TIMING_MAX_PATHS)' \
				'set npc_report_timing_paths_per_clock $(FPGA_REPORT_TIMING_PATHS_PER_CLOCK)' \
				'set npc_report_congestion $(FPGA_REPORT_CONGESTION)' \
				'set npc_report_clock_utilization $(FPGA_REPORT_CLOCK_UTILIZATION)' \
				'set npc_report_control_sets $(FPGA_REPORT_CONTROL_SETS)' \
				'set npc_report_high_fanout_nets $(FPGA_REPORT_HIGH_FANOUT_NETS)' \
				'set npc_report_methodology $(FPGA_REPORT_METHODOLOGY)' \
				'set npc_report_qor_suggestions $(FPGA_REPORT_QOR_SUGGESTIONS)' \
				'set npc_vitis_run [current_run]' \
				'set npc_vitis_default_post_route [file normalize [file join [get_property DIRECTORY $$npc_vitis_run] .. .. .. scripts [get_property NAME $$npc_vitis_run] _full_route_post.tcl]]' \
				'if {[file exists $$npc_vitis_default_post_route]} {' \
				'  source $$npc_vitis_default_post_route' \
				'} else {' \
				'  puts stderr "WARNING: Vitis default post-route hook not found: $$npc_vitis_default_post_route"' \
				'}'; \
			printf 'source [list {%s}]\n' "$(FPGA_IMPLEMENTATION_REPORT_TCL)"; \
		} > "$(FPGA_U55C_REPORT_HOOK)"; \
		{ cat "$(FPGA_LINK_CONFIG)"; \
			printf '\n[clock]\nfreqHz=%s000000:NpcFpgaKernel_1.ap_clk\n' "$(FPGA_CLOCK_MHZ)"; \
			printf '\n[vivado]\nsynth.jobs=%s\nimpl.jobs=%s\n' \
				"$(FPGA_VIVADO_SYNTH_JOBS)" "$(FPGA_VIVADO_IMPL_JOBS)"; \
			printf 'prop=run.impl_1.STEPS.ROUTE_DESIGN.TCL.POST=%s\n' "$(abspath $(FPGA_U55C_REPORT_HOOK))"; \
			if test "$(FPGA_VIVADO_IMPL_STRATEGY_SEARCH)" = 1; then \
				printf 'impl.strategies=%s\n' "$(FPGA_VIVADO_IMPL_STRATEGY)"; \
				printf 'prop=run.impl_%s.STEPS.ROUTE_DESIGN.TCL.POST=%s\n' \
					"$(FPGA_VIVADO_IMPL_STRATEGY)" "$(abspath $(FPGA_U55C_REPORT_HOOK))"; \
			fi; \
		} > "$(FPGA_VITIS_LINK_CONFIG)"; \
	fi; \
	echo '=== Linking FPGA image ==='; \
	rm -rf "$(FPGA_ARTIFACT_DIR)"; mkdir -p "$(FPGA_ARTIFACT_DIR)"; \
	if test "$(FPGA_TYPE)" = zynqmp; then \
		vivado -mode batch -nojournal -nolog -source "$(FPGA_LINK_TCL)" -tclargs \
			"$(FPGA_SYNTH_DIR)/platform" "$(FPGA_PART)" "$(FPGA_BOARD_REPO)" "$(FPGA_BOARD_PART)" \
			"$(FPGA_RTL_DIR)" "$(FPGA_BOARD_RTL_DIR)" "$(FPGA_IP_ADAPTER_DIR)" "$(FPGA_IP_DIR)" "$(NPC_XLEN)" \
			"$(FPGA_CLOCK_MHZ)" "$(FPGA_MEMORY_BASE)" "$(FPGA_MEMORY_HOST_BASE)" \
			"$(FPGA_MAILBOX_BASE)" "$(FPGA_BIT)" "$(FPGA_XSA)" "$(FPGA_VIVADO_IMPL_JOBS)" \
			"$(FPGA_VIVADO_IMPL_STRATEGY)" "$(FPGA_TIMING_WNS_MIN_NS)" "$(FPGA_IMPLEMENTATION_REPORT_TCL)" \
			"$(FPGA_REPORT_TIMING_MAX_PATHS)" "$(FPGA_REPORT_TIMING_PATHS_PER_CLOCK)" \
			"$(FPGA_REPORT_CONGESTION)" "$(FPGA_REPORT_CLOCK_UTILIZATION)" "$(FPGA_REPORT_CONTROL_SETS)" \
			"$(FPGA_REPORT_HIGH_FANOUT_NETS)" "$(FPGA_REPORT_METHODOLOGY)" "$(FPGA_REPORT_QOR_SUGGESTIONS)"; \
		"$(FPGA_ZCU102_RUNTIME_GENERATOR)" "$(FPGA_ARTIFACT_DIR)" \
			"$(FPGA_MEMORY_HOST_BASE)" "$(FPGA_MEMORY_SIZE)" "$(FPGA_MAILBOX_BASE)" "$(FPGA_PL_GIC_SPI)"; \
	else \
		rm -rf "$(FPGA_VITIS_TEMP_DIR)" "$(FPGA_VITIS_LOG_DIR)" "$(FPGA_VITIS_REPORT_DIR)"; \
		$(FPGA_VITIS_ENV) v++ --link --target "$(FPGA_VITIS_TARGET)" --platform "$(FPGA_PLATFORM)" --config "$(FPGA_VITIS_LINK_CONFIG)" \
			--temp_dir "$(FPGA_VITIS_TEMP_DIR)" --log_dir "$(FPGA_VITIS_LOG_DIR)" \
			--report_dir "$(FPGA_VITIS_REPORT_DIR)" --output "$(FPGA_XCLBIN)" "$(FPGA_XO)"; \
		"$(FPGA_TIMING_WNS_TOOL)" "$(FPGA_VITIS_REPORT_DIR)" > "$(FPGA_TIMING_WNS_FILE)"; \
	fi; \
	wns=$$(cat "$(FPGA_TIMING_WNS_FILE)"); \
	awk -v wns="$$wns" -v min="$(FPGA_TIMING_WNS_MIN_NS)" 'BEGIN { exit !(wns >= min) }' || { echo "timing WNS $$wns ns is below configured minimum $(FPGA_TIMING_WNS_MIN_NS) ns" >&2; exit 1; }; \
	for output in $(FPGA_LINK_OUTPUTS); do test -f "$$output"; done; \
	touch "$@"

fpga-artifact-manifest: $(FPGA_LINK_DONE) FORCE
	@set -e; \
	if test "$(FPGA_TOOL_DRY_RUN)" = 1; then echo 'FPGA artifact manifest dry run'; exit 0; fi; \
	timing_arg=''; \
	if test -n "$(FPGA_TIMING_WNS_FILE)"; then \
		test -f "$(FPGA_TIMING_WNS_FILE)"; timing_arg="--timing-wns $$(cat "$(FPGA_TIMING_WNS_FILE)")"; \
	elif test -n "$(FPGA_TIMING_WNS)"; then timing_arg="--timing-wns $(FPGA_TIMING_WNS)"; fi; \
	$(FPGA_ARTIFACT_MANIFEST_TOOL) write \
		--directory "$(FPGA_ARTIFACT_DIR)" --source-root "$(CURDIR)/.." \
		--release-tag "$(FPGA_RELEASE_TAG)" --board "$(FPGA_NAME)" --variant "$(FPGA_VARIANT)" \
		--type "$(FPGA_TYPE)" --platform "$(if $(FPGA_PLATFORM),$(FPGA_PLATFORM),none)" \
		--config-fqcn "$(FPGA_SCALA_CONFIG)" \
		--host-abi "$(HOST_ABI)" --protocol-abi "$(PROTOCOL_ABI)" \
		$$timing_arg \
		$(foreach asset,$(FPGA_PACKAGE_ASSETS),--asset "$(notdir $(asset))")

fpga-verify-artifact: fpga-artifact-manifest
	@$(FPGA_ARTIFACT_MANIFEST_TOOL) verify --directory "$(FPGA_ARTIFACT_DIR)" \
		--board "$(FPGA_NAME)" --platform "$(if $(FPGA_PLATFORM),$(FPGA_PLATFORM),none)" \
		--config-fqcn "$(FPGA_SCALA_CONFIG)" --host-abi "$(HOST_ABI)" --protocol-abi "$(PROTOCOL_ABI)" \
		--release-tag "$(FPGA_RELEASE_TAG)" \
		$(if $(filter 1 y yes true on,$(FPGA_FORMAL_RELEASE)),--formal --require-timing,)

fpga-release-verify: FPGA_FORMAL_RELEASE := 1
fpga-release-verify: fpga-verify-artifact

fpga-link: $(FPGA_LINK_DONE) fpga-artifact-manifest

fpga-config-test:
	@"$(FPGA_ROOT)/tests/config-regression.sh" "$(CURDIR)"

fpga-rtl-test:
	@"$(FPGA_ROOT)/tests/run-fpga-rtl-test.sh" "$(CURDIR)"

fpga-release-test:
	@"$(FPGA_ROOT)/tests/release-regression.sh" "$(CURDIR)"

release-construction-check:
	@test -n "$(RELEASE_CONSTRUCTION)" || { echo 'set RELEASE_CONSTRUCTION=<stable release construction name>' >&2; exit 2; }
	@"$(FPGA_RELEASE_CONSTRUCTION_TOOL)" verify "$(FPGA_RELEASE_CONSTRUCTIONS)" "$(CURDIR)" "$(RELEASE_CONSTRUCTION)"

.PHONY: fpga-config fpga-check fpga-plan fpga-elaborate fpga-ip fpga-synth fpga-link fpga-config-test fpga-rtl-test fpga-release-test fpga-artifact-manifest fpga-verify-artifact fpga-release-verify release-construction-check
