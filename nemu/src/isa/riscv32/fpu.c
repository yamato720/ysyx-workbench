/***************************************************************************************
* Scalar RISC-V F extension, implemented through Berkeley SoftFloat.
***************************************************************************************/

#include <isa.h>
#include <cpu/cpu.h>
#include <cpu/decode.h>
#include <memory/vaddr.h>

#include "csr.h"
#include "fpu.h"

#ifdef CONFIG_RISCV_F

#include "softfloat.h"
#include "internals.h"

enum {
  FFLAGS_MASK = 0x1f,
  FFLAG_NV = 0x10,
  CANONICAL_NAN = 0x7fc00000u,
};

static inline uint32_t rd_of(uint32_t inst) { return BITS(inst, 11, 7); }
static inline uint32_t rs1_of(uint32_t inst) { return BITS(inst, 19, 15); }
static inline uint32_t rs2_of(uint32_t inst) { return BITS(inst, 24, 20); }
static inline uint32_t rs3_of(uint32_t inst) { return BITS(inst, 31, 27); }
static inline uint32_t rm_of(uint32_t inst) { return BITS(inst, 14, 12); }
static inline word_t imm_i(uint32_t inst) { return SEXT(BITS(inst, 31, 20), 12); }
static inline word_t imm_s(uint32_t inst) {
  return (SEXT(BITS(inst, 31, 25), 7) << 5) | BITS(inst, 11, 7);
}

static inline bool fpr_boxed(unsigned index) {
#ifdef CONFIG_RV64
  return (cpu.fpr[index] >> 32) == 0xffffffffu;
#else
  (void)index;
  return true;
#endif
}

static inline uint32_t fpr_raw(unsigned index) { return (uint32_t)cpu.fpr[index]; }
static inline uint32_t fpr_f32(unsigned index) {
  return fpr_boxed(index) ? fpr_raw(index) : CANONICAL_NAN;
}
static inline void write_fpr(unsigned index, uint32_t value) {
#ifdef CONFIG_RV64
  cpu.fpr[index] = UINT64_C(0xffffffff00000000) | value;
#else
  cpu.fpr[index] = value;
#endif
  riscv_csr_mark_f_dirty();
}

static inline void fp_begin(unsigned rm) {
  softfloat_roundingMode = rm;
  softfloat_detectTininess = softfloat_tininess_afterRounding;
  softfloat_exceptionFlags = 0;
}
static inline void fp_end(void) {
  riscv_csr_or_fflags(softfloat_exceptionFlags & FFLAGS_MASK);
  riscv_csr_mark_f_dirty();
}

static bool resolve_rm(unsigned encoded, unsigned *rm) {
  unsigned resolved = encoded == 7 ? riscv_csr_frm() : encoded;
  if (encoded == 5 || encoded == 6 || resolved > 4) return false;
  *rm = resolved;
  return true;
}

static inline bool is_nan(uint32_t value) { return (value & 0x7fffffffU) > 0x7f800000U; }
static inline bool is_snan(uint32_t value) { return is_nan(value) && !(value & 0x00400000U); }

static uint32_t fminmax(uint32_t a, uint32_t b, bool maximum) {
  if (is_nan(a) || is_nan(b)) {
    if (is_snan(a) || is_snan(b)) softfloat_raiseFlags(FFLAG_NV);
    if (is_nan(a) && is_nan(b)) return CANONICAL_NAN;
    return is_nan(a) ? b : a;
  }
  if (((a | b) << 1) == 0) return maximum ? (a & b) : (a | b);
  const bool a_lt_b = f32_lt((float32_t){ .v = a }, (float32_t){ .v = b });
  return maximum ? (a_lt_b ? b : a) : (a_lt_b ? a : b);
}

static uint32_t fclass(uint32_t value) {
  const unsigned sign = value >> 31;
  const unsigned exponent = (value >> 23) & 0xff;
  const unsigned fraction = value & 0x7fffff;
  if (exponent == 0xff) {
    if (!fraction) return 1u << (sign ? 0 : 7);
    return 1u << (is_snan(value) ? 8 : 9);
  }
  if (!exponent) {
    if (!fraction) return 1u << (sign ? 3 : 4);
    return 1u << (sign ? 2 : 5);
  }
  return 1u << (sign ? 1 : 6);
}

static inline void write_x(unsigned rd, uint64_t value, bool word_result) {
#ifdef CONFIG_RV64
  cpu.gpr[rd] = word_result ? (word_t)(int64_t)(int32_t)value : (word_t)value;
#else
  (void)word_result;
  cpu.gpr[rd] = (word_t)value;
#endif
}

static bool execute_fma(uint32_t inst) {
  unsigned rm;
  if (!resolve_rm(rm_of(inst), &rm)) return false;
  unsigned operation = 0;
  uint32_t c = fpr_f32(rs3_of(inst));
  switch (BITS(inst, 6, 0)) {
    case 0x47: operation = softfloat_mulAdd_subC; break;
    case 0x4b: operation = softfloat_mulAdd_subProd; break;
    case 0x4f:
      operation = softfloat_mulAdd_subProd;
      c ^= 0x80000000u;
      break;
    default: break;
  }
  fp_begin(rm);
  float32_t result = softfloat_mulAddF32(fpr_f32(rs1_of(inst)), fpr_f32(rs2_of(inst)),
                                          c, operation);
  write_fpr(rd_of(inst), result.v);
  fp_end();
  return true;
}

static bool execute_opfp(uint32_t inst) {
  const unsigned rd = rd_of(inst), rs1 = rs1_of(inst), rs2 = rs2_of(inst);
  const unsigned funct7 = BITS(inst, 31, 25), funct3 = rm_of(inst);
  const uint32_t a_bits = fpr_f32(rs1), b_bits = fpr_f32(rs2);
  const float32_t a = { .v = a_bits }, b = { .v = b_bits };
  unsigned rm;
  float32_t result;

  switch (funct7) {
    case 0x00: case 0x04: case 0x08: case 0x0c:
      if (!resolve_rm(funct3, &rm)) return false;
      fp_begin(rm);
      if (funct7 == 0x00) result = f32_add(a, b);
      else if (funct7 == 0x04) result = f32_sub(a, b);
      else if (funct7 == 0x08) result = f32_mul(a, b);
      else result = f32_div(a, b);
      write_fpr(rd, result.v); fp_end(); return true;
    case 0x2c:
      if (rs2 != 0 || !resolve_rm(funct3, &rm)) return false;
      fp_begin(rm); write_fpr(rd, f32_sqrt(a).v); fp_end(); return true;
    case 0x10: {
      uint32_t sign;
      if (funct3 == 0) sign = b_bits & 0x80000000u;
      else if (funct3 == 1) sign = (~b_bits) & 0x80000000u;
      else if (funct3 == 2) sign = (a_bits ^ b_bits) & 0x80000000u;
      else return false;
      write_fpr(rd, (a_bits & 0x7fffffffU) | sign); return true;
    }
    case 0x14:
      if (funct3 > 1) return false;
      fp_begin(softfloat_round_near_even); write_fpr(rd, fminmax(a_bits, b_bits, funct3 == 1));
      fp_end(); return true;
    case 0x50:
      if (funct3 > 2) return false;
      fp_begin(softfloat_round_near_even);
      cpu.gpr[rd] = funct3 == 0 ? f32_le(a, b) : funct3 == 1 ? f32_lt(a, b) : f32_eq(a, b);
      fp_end(); return true;
    case 0x60:
      if (!resolve_rm(funct3, &rm)) return false;
      fp_begin(rm);
      switch (rs2) {
        case 0: write_x(rd, (uint32_t)f32_to_i32(a, rm, true), true); break;
        case 1: write_x(rd, (uint32_t)f32_to_ui32(a, rm, true), true); break;
#ifdef CONFIG_RV64
        case 2: write_x(rd, (uint64_t)f32_to_i64(a, rm, true), false); break;
        case 3: write_x(rd, f32_to_ui64(a, rm, true), false); break;
#endif
        default: return false;
      }
      fp_end(); return true;
    case 0x68:
      if (!resolve_rm(funct3, &rm)) return false;
      fp_begin(rm);
      switch (rs2) {
        case 0: result = i32_to_f32((int32_t)cpu.gpr[rs1]); break;
        case 1: result = ui32_to_f32((uint32_t)cpu.gpr[rs1]); break;
#ifdef CONFIG_RV64
        case 2: result = i64_to_f32((int64_t)cpu.gpr[rs1]); break;
        case 3: result = ui64_to_f32((uint64_t)cpu.gpr[rs1]); break;
#endif
        default: return false;
      }
      write_fpr(rd, result.v); fp_end(); return true;
    case 0x70:
      if (rs2 || funct3 > 1) return false;
      if (!funct3) write_x(rd, fpr_raw(rs1), true);
      else cpu.gpr[rd] = fclass(a_bits);
      return true;
    case 0x78:
      if (rs2 || funct3) return false;
      write_fpr(rd, (uint32_t)cpu.gpr[rs1]); return true;
    default: return false;
  }
}

bool riscv_f_exec(struct Decode *s) {
  const uint32_t inst = s->isa.inst, opcode = BITS(s->isa.inst, 6, 0);
  const bool is_f = opcode == 0x07 || opcode == 0x27 || opcode == 0x43 || opcode == 0x47 ||
    opcode == 0x4b || opcode == 0x4f || opcode == 0x53;
  if (!is_f) return false;
  if (!riscv_csr_f_enabled()) { INV(s->pc); return true; }
  switch (opcode) {
    case 0x07:
      if (rm_of(inst) == 2) {
        write_fpr(rd_of(inst), vaddr_read(cpu.gpr[rs1_of(inst)] + imm_i(inst), 4)); return true;
      }
      break;
    case 0x27:
      if (rm_of(inst) == 2) {
        vaddr_write(cpu.gpr[rs1_of(inst)] + imm_s(inst), 4, fpr_raw(rs2_of(inst))); return true;
      }
      break;
    case 0x43: case 0x47: case 0x4b: case 0x4f:
      if (BITS(inst, 26, 25) == 0 && execute_fma(inst)) return true;
      break;
    case 0x53:
      if (execute_opfp(inst)) return true;
      break;
    default: break;
  }
  INV(s->pc);
  return true;
}

void riscv_f_reset(void) { memset(cpu.fpr, 0, sizeof(cpu.fpr)); }

#else

bool riscv_f_exec(struct Decode *s) {
  const uint32_t opcode = BITS(s->isa.inst, 6, 0);
  if (opcode == 0x07 || opcode == 0x27 || opcode == 0x43 || opcode == 0x47 || opcode == 0x4b ||
      opcode == 0x4f || opcode == 0x53) { INV(s->pc); return true; }
  return false;
}
void riscv_f_reset(void) {}

#endif
