SHELL := /bin/bash

CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -ffp-contract=off
CPPFLAGS ?=
LDLIBS ?=

BUILD_DIR ?= $(CURDIR)/build
HOST_BIN := $(BUILD_DIR)/spmv-host
SOURCES := host.cpp golden.cpp
OBJECTS := $(addprefix $(BUILD_DIR)/,$(SOURCES:.cpp=.o))
DEPFILES := $(OBJECTS:.o=.d)
ACCELERATOR_DATA_ROOT ?= $(abspath $(CURDIR)/../data)
SPMV_GOLDEN_DIR := $(BUILD_DIR)/golden
CPPFLAGS += -DACCELERATOR_SIM_DEFAULT_DATA_ROOT=\"$(ACCELERATOR_DATA_ROOT)\"

export ACCELERATOR_DATA_ROOT SPMV_GOLDEN_DIR mainargs

ifneq ($(strip $(CONSTRUCTION_DIR)),)
VERILATOR_ROOT := $(shell dirname $$(dirname $$(command -v verilator)))/share/verilator
MODEL_DIR := $(CONSTRUCTION_DIR)/abi/verilator
SOFTFLOAT_DIR := $(CONSTRUCTION_DIR)/abi/softfloat
SPMV_X_MODE ?= $(shell sed -n 's/^SPMV_X_MODE=//p' "$(CONSTRUCTION_DIR)/profile.env")
SPMV_PERFORMANCE_HTML ?= $(shell sed -n 's/^SPMV_PERFORMANCE_HTML=//p' "$(CONSTRUCTION_DIR)/profile.env")
SPMV_PIPELINE_HTML ?= $(shell sed -n 's/^SPMV_PIPELINE_HTML=//p' "$(CONSTRUCTION_DIR)/profile.env")
ifeq ($(SPMV_X_MODE),cached)
CPPFLAGS += -DSPMV_CACHED_X
else ifneq ($(SPMV_X_MODE),paired)
$(error 保存构造的 SPMV_X_MODE 必须是 paired 或 cached)
endif
SPMV_PERFORMANCE_HTML ?= 0
SPMV_PIPELINE_HTML ?= 0
ifeq ($(SPMV_PIPELINE_HTML),1)
CPPFLAGS += -DSPMV_PIPELINE_HTML=1
else
CPPFLAGS += -DSPMV_PIPELINE_HTML=0
endif
ifeq ($(SPMV_PERFORMANCE_HTML),1)
CPPFLAGS += -DSPMV_PERFORMANCE_HTML=1
else
CPPFLAGS += -DSPMV_PERFORMANCE_HTML=0
endif
CPPFLAGS += -DSPMV_CSR5_VERILATOR -I$(MODEL_DIR) -isystem $(VERILATOR_ROOT)/include \
	-isystem $(VERILATOR_ROOT)/include/vltstd -isystem $(SOFTFLOAT_DIR)/include
LDLIBS += $(MODEL_DIR)/libVSpmvOneHbmCsr5MulSimulationTop.a \
	$(MODEL_DIR)/libverilated.a $(SOFTFLOAT_DIR)/softfloat.a -pthread -latomic
endif

.PHONY: build-host build-simulation-host hbm-dpi-test hbm-protocol-test run

build-host: $(HOST_BIN)

build-simulation-host:
	@test -n "$(strip $(CONSTRUCTION_DIR))" || { echo 'build-simulation-host 缺少 CONSTRUCTION_DIR' >&2; exit 2; }
	@test -s "$(MODEL_DIR)/VSpmvOneHbmCsr5MulSimulationTop.h"
	@test -s "$(MODEL_DIR)/libVSpmvOneHbmCsr5MulSimulationTop.a"
	@test -s "$(MODEL_DIR)/libverilated.a"
	@test -s "$(SOFTFLOAT_DIR)/softfloat.a"
	@$(MAKE) --no-print-directory "$(HOST_BIN)" CONSTRUCTION_DIR="$(CONSTRUCTION_DIR)" BUILD_DIR="$(BUILD_DIR)"
	@printf 'HOST_FORMAT=1\nCONFIG_FQCN=%s\nACCELERATOR_HOST_KIND=spmv\nACCELERATOR_HOST_ABI=spmv-csr5-verilator-v3\nPROTOCOL_ABI=spmv-one-hbm-csr5-mul-v3\nSPMV_X_MODE=%s\nSPMV_PERFORMANCE_HTML=%s\nSPMV_PIPELINE_HTML=%s\nSPMV_PERFORMANCE_FIRST_SUBCONFIG=%s\n' \
		"$$(sed -n 's/^CONFIG_FQCN=//p' "$(CONSTRUCTION_DIR)/profile.env")" \
		"$(SPMV_X_MODE)" "$(SPMV_PERFORMANCE_HTML)" "$(SPMV_PIPELINE_HTML)" \
		"$$(sed -n 's/^SPMV_PERFORMANCE_FIRST_SUBCONFIG=//p' "$(CONSTRUCTION_DIR)/profile.env")" > "$(BUILD_DIR)/host.env"

hbm-dpi-test: build-simulation-host
	@"$(HOST_BIN)" --self-test-hbm-dpi

hbm-protocol-test:
	@test -n "$(strip $(CONSTRUCTION_DIR))" || { echo 'hbm-protocol-test 缺少 CONSTRUCTION_DIR' >&2; exit 2; }
	@mkdir -p "$(BUILD_DIR)"
	@rm -rf "$(BUILD_DIR)/hbm-protocol-test"
	@verilator -Wall -Wno-fatal --quiet --quiet-stats --sv --cc --exe --build --no-timing \
		-j 0 --top-module SpmvHbmDpiReadSlave \
		-Mdir "$(BUILD_DIR)/hbm-protocol-test" \
		"$(CONSTRUCTION_DIR)/abi/rtl/SpmvHbmDpiReadSlave.sv" \
		"$(CURDIR)/hbm_dpi_protocol_test.cpp"
	@"$(BUILD_DIR)/hbm-protocol-test/VSpmvHbmDpiReadSlave"

run: build-host
	@"$(HOST_BIN)" "$${mainargs:-}"

$(HOST_BIN): $(OBJECTS)
	$(CXX) $(CXXFLAGS) $(OBJECTS) $(LDLIBS) -o "$@"

$(BUILD_DIR)/%.o: %.cpp golden.hpp | $(BUILD_DIR)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) -MMD -MP -c "$<" -o "$@"

$(BUILD_DIR):
	mkdir -p "$@"

-include $(DEPFILES)
