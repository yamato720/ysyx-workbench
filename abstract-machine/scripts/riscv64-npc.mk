include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/npc.mk

CFLAGS  += -DISA_H=\"riscv/riscv.h\"
# RV64I for Chisel CPU (with Zicsr for CSR instructions, soft-float ABI)
COMMON_CFLAGS := -fno-pic -march=rv64i_zicsr -mabi=lp64 -mcmodel=medany -mstrict-align
NPC_XLEN      := 64

AM_SRCS += riscv/npc/libgcc/div.S \
           riscv/npc/libgcc/muldi3.S \
           riscv/npc/libgcc/multi3.c \
           riscv/npc/libgcc/ashldi3.c \
           riscv/npc/libgcc/unused.c
