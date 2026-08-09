SHELL := /bin/bash

CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -ffp-contract=off
CPPFLAGS ?=
LDLIBS ?=

BUILD_DIR ?= $(CURDIR)/build
HOST_BIN := $(BUILD_DIR)/spmv-host
ENCODING_TEST_BIN := $(BUILD_DIR)/encoding-test
ENCODING_SOURCES := encoding/encoder.cpp encoding/cuper/cuper.cpp encoding/cuper/report.cpp
HOST_SOURCES := host.cpp golden.cpp $(ENCODING_SOURCES)
HOST_OBJECTS := $(addprefix $(BUILD_DIR)/,$(HOST_SOURCES:.cpp=.o))
ENCODING_OBJECTS := $(addprefix $(BUILD_DIR)/,$(ENCODING_SOURCES:.cpp=.o))
ENCODING_TEST_OBJECT := $(BUILD_DIR)/encoding/encoding_test.o
DEPFILES := $(sort $(HOST_OBJECTS:.o=.d) $(ENCODING_TEST_OBJECT:.o=.d))
ENCODING ?= cuper
ENCODING_TEST_DATASET ?= n512
ACCELERATOR_DATA_ROOT ?= $(abspath $(CURDIR)/../data)
SPMV_GOLDEN_DIR := $(BUILD_DIR)/golden
SPMV_ENCODING_REPORT_DIR ?= $(BUILD_DIR)/encoding
CPPFLAGS += -DACCELERATOR_SIM_DEFAULT_DATA_ROOT=\"$(ACCELERATOR_DATA_ROOT)\"

SPMV_PROFILE_FILE ?= $(if $(strip $(CONSTRUCTION_PROFILE)),$(CONSTRUCTION_PROFILE),$(if $(strip $(CONSTRUCTION_DIR)),$(CONSTRUCTION_DIR)/profile.env,))
spmv_profile_value = $(shell test -n "$(SPMV_PROFILE_FILE)" && test -f "$(SPMV_PROFILE_FILE)" && sed -n 's/^$(1)=//p' "$(SPMV_PROFILE_FILE)" | tail -n 1)

SPMV_INPUT_A_READER_COUNT ?= $(call spmv_profile_value,SPMV_INPUT_A_READER_COUNT)
SPMV_INPUT_X_READER_COUNT ?= $(call spmv_profile_value,SPMV_INPUT_X_READER_COUNT)
SPMV_INPUT_HBM_CHANNEL_COUNT ?= $(call spmv_profile_value,SPMV_INPUT_HBM_CHANNEL_COUNT)
SPMV_INPUT_HBM_BASE ?= $(call spmv_profile_value,SPMV_INPUT_HBM_BASE)
SPMV_INPUT_HBM_BYTES ?= $(call spmv_profile_value,SPMV_INPUT_HBM_BYTES)
SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES ?= $(call spmv_profile_value,SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES)
SPMV_INPUT_AXI_ADDR_WIDTH ?= $(call spmv_profile_value,SPMV_INPUT_AXI_ADDR_WIDTH)
SPMV_INPUT_AXI_DATA_WIDTH ?= $(call spmv_profile_value,SPMV_INPUT_AXI_DATA_WIDTH)
SPMV_INPUT_AXI_ID_WIDTH ?= $(call spmv_profile_value,SPMV_INPUT_AXI_ID_WIDTH)

# 未绑定 construction 时保留独立编码 smoke test 的默认输入布局；一旦绑定 profile，
# 上面的 ?= 会优先采用 Config 导出的值。
SPMV_INPUT_A_READER_COUNT ?= 16
SPMV_INPUT_X_READER_COUNT ?= 1
SPMV_INPUT_HBM_CHANNEL_COUNT ?= 16
SPMV_INPUT_HBM_BASE ?= 0x80000000
SPMV_INPUT_HBM_BYTES ?= 134217728
SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES ?= 4096
SPMV_INPUT_AXI_ADDR_WIDTH ?= 64
SPMV_INPUT_AXI_DATA_WIDTH ?= 512
SPMV_INPUT_AXI_ID_WIDTH ?= 4

export ACCELERATOR_DATA_ROOT SPMV_GOLDEN_DIR SPMV_ENCODING_REPORT_DIR mainargs \
	SPMV_INPUT_A_READER_COUNT SPMV_INPUT_X_READER_COUNT SPMV_INPUT_HBM_CHANNEL_COUNT \
	SPMV_INPUT_HBM_BASE SPMV_INPUT_HBM_BYTES SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES \
	SPMV_INPUT_AXI_ADDR_WIDTH SPMV_INPUT_AXI_DATA_WIDTH SPMV_INPUT_AXI_ID_WIDTH

ifneq ($(strip $(CONSTRUCTION_DIR)),)
VERILATOR_ROOT := $(shell dirname $$(dirname $$(command -v verilator)))/share/verilator
MODEL_DIR := $(CONSTRUCTION_DIR)/abi/verilator
CPPFLAGS += -DSPMV_INPUT_SMOKE_VERILATOR -I$(MODEL_DIR) -isystem $(VERILATOR_ROOT)/include \
	-isystem $(VERILATOR_ROOT)/include/vltstd
LDLIBS += $(MODEL_DIR)/libVSpmvInputTop.a \
	$(MODEL_DIR)/libverilated.a -pthread -latomic
endif

.PHONY: build-host build-simulation-host encode cuper-a-test encoding-html-test encoding-test run

build-host: $(HOST_BIN)

build-simulation-host:
	@test -n "$(strip $(CONSTRUCTION_DIR))" || { echo 'build-simulation-host 缺少 CONSTRUCTION_DIR' >&2; exit 2; }
	@test -s "$(MODEL_DIR)/VSpmvInputTop.h"
	@test -s "$(MODEL_DIR)/libVSpmvInputTop.a"
	@test -s "$(MODEL_DIR)/libverilated.a"
	@$(MAKE) --no-print-directory "$(HOST_BIN)" CONSTRUCTION_DIR="$(CONSTRUCTION_DIR)" BUILD_DIR="$(BUILD_DIR)"
	@printf 'HOST_FORMAT=1\nCONFIG_FQCN=%s\nACCELERATOR_HOST_KIND=spmv\nACCELERATOR_HOST_ABI=spmv-input-smoke-v1\nPROTOCOL_ABI=spmv-input-v1\nSPMV_INPUT_A_READER_COUNT=%s\nSPMV_INPUT_X_READER_COUNT=%s\nSPMV_INPUT_HBM_CHANNEL_COUNT=%s\nSPMV_INPUT_HBM_BASE=%s\nSPMV_INPUT_HBM_BYTES=%s\nSPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES=%s\nSPMV_INPUT_AXI_ADDR_WIDTH=%s\nSPMV_INPUT_AXI_DATA_WIDTH=%s\nSPMV_INPUT_AXI_ID_WIDTH=%s\n' \
		"$$(sed -n 's/^CONFIG_FQCN=//p' "$(SPMV_PROFILE_FILE)")" \
		"$(SPMV_INPUT_A_READER_COUNT)" "$(SPMV_INPUT_X_READER_COUNT)" \
		"$(SPMV_INPUT_HBM_CHANNEL_COUNT)" "$(SPMV_INPUT_HBM_BASE)" "$(SPMV_INPUT_HBM_BYTES)" \
		"$(SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES)" "$(SPMV_INPUT_AXI_ADDR_WIDTH)" \
		"$(SPMV_INPUT_AXI_DATA_WIDTH)" "$(SPMV_INPUT_AXI_ID_WIDTH)" > "$(BUILD_DIR)/host.env"

encode: build-host
	@"$(HOST_BIN)" --encode "$(ENCODING)" "$${mainargs:-}"

cuper-a-test: build-host
	@"$(HOST_BIN)" --check-cuper-a "$${mainargs:-$(ENCODING_TEST_DATASET)}"

encoding-test: $(ENCODING_TEST_BIN)
	@"$(ENCODING_TEST_BIN)"

encoding-html-test: build-host
	@command -v node >/dev/null || { echo 'encoding-html-test 需要 node' >&2; exit 2; }
	@"$(HOST_BIN)" --encode "$(ENCODING)" "$${mainargs:-$(ENCODING_TEST_DATASET)}"
	@node "$(CURDIR)/encoding/html_report_test.js" \
		"$(SPMV_ENCODING_REPORT_DIR)/$(ENCODING)/$${mainargs:-$(ENCODING_TEST_DATASET)}.html"

run: build-host
	@"$(HOST_BIN)" "$${mainargs:-}"

$(HOST_BIN): $(HOST_OBJECTS)
	$(CXX) $(CXXFLAGS) $(HOST_OBJECTS) $(LDLIBS) -o "$@"

$(ENCODING_TEST_BIN): $(ENCODING_TEST_OBJECT) $(ENCODING_OBJECTS)
	$(CXX) $(CXXFLAGS) $^ -o "$@"

$(BUILD_DIR)/%.o: %.cpp golden.hpp | $(BUILD_DIR)
	@mkdir -p "$(dir $@)"
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) -MMD -MP -c "$<" -o "$@"

$(BUILD_DIR):
	mkdir -p "$@"

-include $(DEPFILES)
