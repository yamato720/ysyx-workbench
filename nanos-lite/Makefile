HAS_NAVY = 1
RAMDISK_FILE = build/ramdisk.img

NAME = nanos-lite
SRCS = $(shell find -L ./src/ -name "*.c" -o -name "*.cpp" -o -name "*.S")
include $(AM_HOME)/Makefile

# Optional: specify program to load, e.g. make ARCH=riscv64-nemu run PROG=hello
# If unset, proc.c defaults to the first user file listed in files.h (ramdisk).
ifdef PROG
  CFLAGS += -DPROGRAM_NAME='"/bin/$(PROG)"'
endif

# Detect PROG changes between invocations and force-recompile proc.c if needed.
# Without this, incremental builds reuse the old proc.o even when PROG changes.
$(shell mkdir -p build; \
  prev=$$(cat build/.prog-stamp 2>/dev/null); \
  curr="$(PROG)"; \
  [ "$$prev" = "$$curr" ] || (printf '%s' "$$curr" > build/.prog-stamp; touch src/proc.c))

ifeq ($(ARCH),native)
ISA = am_native
else
INC_PATH += include $(NAVY_HOME)/libs/libc/include
endif

./src/resources.S: $(RAMDISK_FILE)
	@touch $@

ifeq ($(HAS_NAVY),)
files = $(RAMDISK_FILE) src/files.h src/syscall.h
# create an empty file if it does not exist
$(foreach f,$(files),$(if $(wildcard $f),, $(shell touch $f)))
else

ifeq ($(wildcard $(NAVY_HOME)/libs/libos/src/syscall.h),)
  $(error $$NAVY_HOME must be a Navy-apps repo)
endif

update:
	$(MAKE) -s -C $(NAVY_HOME) ISA=$(ISA) ramdisk
	@ln -sf $(NAVY_HOME)/build/ramdisk.img $(RAMDISK_FILE)
	@ln -sf $(NAVY_HOME)/build/ramdisk.h src/files.h
	@ln -sf $(NAVY_HOME)/libs/libos/src/syscall.h src/syscall.h

# run-update: rebuild ramdisk then run (like NEMU's always-rebuild behavior)
# run:        use existing ramdisk as-is
run-update: update
	$(MAKE) $(MFLAGS) ARCH=$(ARCH) $(if $(PROG),PROG=$(PROG),) run

# list: show all files currently packed in the ramdisk
# /bin/ entries are executable programs; /share/ entries are data resources.
# If PROG is set, highlight that entry with ">>"
list:
	@if [ ! -f src/files.h ] || [ ! -s src/files.h ]; then \
	  echo "Ramdisk is empty or not loaded. Run: make ARCH=$(ARCH) update"; \
	else \
	  echo "Files in ramdisk ($(shell test -f $(RAMDISK_FILE) && wc -c < $(RAMDISK_FILE) || echo 0) bytes total):"; \
	  grep -E '^\{' src/files.h | \
	    awk -v prog="/bin/$(PROG)" \
	      '{gsub(/[{"},]/,""); marker=($$1==prog && prog!="/bin/") ? ">>" : "  "; \
	       printf "%s %2d) %-40s %7d bytes  @ offset %d\n", marker, NR, $$1, $$2, $$3}'; \
	fi

# save: build a navy-apps program, install it, and rebuild the ramdisk
# Usage: make ARCH=riscv64-nemu save PROG=hello
# Avoids running `make` in navy-apps root (which errors due to missing NAME/SRCS).
save:
	@test -n "$(PROG)" || (echo "Usage: make ARCH=$(ARCH) save PROG=<program-name>"; exit 1)
	@if [ -d "$(NAVY_HOME)/tests/$(PROG)" ]; then \
	  echo "[save] Building tests/$(PROG)..."; \
	  $(MAKE) -C "$(NAVY_HOME)/tests/$(PROG)" ISA=$(ISA) install; \
	elif [ -d "$(NAVY_HOME)/apps/$(PROG)" ]; then \
	  echo "[save] Building apps/$(PROG)..."; \
	  $(MAKE) -C "$(NAVY_HOME)/apps/$(PROG)" ISA=$(ISA) install; \
	else \
	  echo "Error: '$(PROG)' not found in navy-apps/tests/ or navy-apps/apps/"; exit 1; \
	fi
	@$(MAKE) -s -C $(NAVY_HOME) ISA=$(ISA) ramdisk
	@ln -sf $(NAVY_HOME)/build/ramdisk.img $(RAMDISK_FILE)
	@ln -sf $(NAVY_HOME)/build/ramdisk.h src/files.h
	@echo "[save] $(PROG) is now in the ramdisk. Use 'make list' to verify."

help:
	@echo ""
	@echo "nanos-lite Makefile targets"
	@echo "==========================="
	@echo ""
	@echo "  查看 / 调试:"
	@echo "    make ARCH=riscv64-nemu list [PROG=<name>]"
	@echo "        列出 ramdisk 中所有文件；指定 PROG 时用 >> 高亮对应条目"
	@echo "        /bin/   = 可执行用户程序"
	@echo "        /share/ = 共享数据资源（字体/音乐/图片等）"
	@echo ""
	@echo "  运行 (使用现有 ramdisk):"
	@echo "    make ARCH=riscv64-nemu run [PROG=<name>]"
	@echo "        不重建 ramdisk；切换 PROG 时自动重编 proc.c"
	@echo ""
	@echo "  运行 + 重建 ramdisk (navy-apps 源码有改动时用):"
	@echo "    make ARCH=riscv64-nemu run-update [PROG=<name>]"
	@echo ""
	@echo "  编译并载入单个程序:"
	@echo "    make ARCH=riscv64-nemu save PROG=<name>"
	@echo "        在 navy-apps/tests/<name>/ 或 apps/<name>/ 下编译"
	@echo "        并重打包 ramdisk，然后用 'run' 运行"
	@echo ""
	@echo "  只重建 ramdisk (不运行):"
	@echo "    make ARCH=riscv64-nemu update"
	@echo ""
	@echo "  清理:"
	@echo "    make ARCH=riscv64-nemu clean"
	@echo ""
	@echo "  示例工作流:"
	@echo "    make ARCH=riscv64-nemu save PROG=hello"
	@echo "    make ARCH=riscv64-nemu list PROG=hello"
	@echo "    make ARCH=riscv64-nemu run  PROG=hello"
	@echo ""

.PHONY: update run-update list save help
endif
