include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/npc.mk

CFLAGS  += -DISA_H=\"riscv/riscv.h\"
# RV64I for Chisel CPU (with Zicsr for CSR instructions, soft-float ABI)
COMMON_CFLAGS := -fno-pic -march=rv64i_zicsr -mabi=lp64 -mcmodel=medany -mstrict-align
