SHELL := /bin/bash

PYTHON ?= python3
SIZE ?= 512
ASPECT_RATIO ?= 1.6
DATASETS ?= thermal2_n1024
FORCE_DOWNLOAD ?= 0

DATA_ROOT := $(abspath $(CURDIR))
SCRIPT_DIR := $(DATA_ROOT)/scripts
GENERATED_OUTPUT := $(DATA_ROOT)/generated/cgsolver/n$(SIZE)
FORCE_DOWNLOAD_ARG := $(if $(filter 1 yes true,$(FORCE_DOWNLOAD)),--force-download,)

.DEFAULT_GOAL := fetch

.PHONY: fetch generate download download-suitesparse-data list

fetch: generate download-suitesparse-data

generate:
	$(PYTHON) "$(SCRIPT_DIR)/generate_cg_dataset.py" \
		--size "$(SIZE)" --aspect-ratio "$(ASPECT_RATIO)" \
		--output-dir "$(GENERATED_OUTPUT)"

download: download-suitesparse-data

download-suitesparse-data:
	$(PYTHON) "$(SCRIPT_DIR)/download_suitesparse_data.py" \
		--data-root "$(DATA_ROOT)/suitesparse" \
		--datasets $(DATASETS) $(FORCE_DOWNLOAD_ARG)

list:
	@echo "n<SIZE>: deterministic generated SPD matrix (default SIZE=$(SIZE))"
	@$(PYTHON) "$(SCRIPT_DIR)/download_suitesparse_data.py" --list
