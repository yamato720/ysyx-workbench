#include <cstdint>

#include "fp_dpi.h"

extern "C" {
#include "softfloat.h"
#include "internals.h"
}

namespace {

// Keep these values aligned with scpu.FloatingOperation. They are an
// operator-local interface, not a frontend/backend ALU control encoding.
enum FpOp : std::uint32_t {
  kFadd = 0, kFsub, kFmul, kFdiv, kFsqrt, kFmadd, kFmsub, kFnmsub, kFnmadd,
  kFsgnj, kFsgnjn, kFsgnjx, kFmin, kFmax, kFeq, kFlt, kFle,
  kFcvtW, kFcvtWu, kFcvtL, kFcvtLu, kFcvtSW, kFcvtSWu, kFcvtSL, kFcvtSLu,
  kFmvXW, kFclass, kFmvWX,
};

constexpr std::uint32_t kCanonicalNan = 0x7fc00000u;

std::uint_fast8_t to_softfloat_rounding_mode(std::uint32_t rounding_mode) {
  switch (rounding_mode) {
    case 0: return softfloat_round_near_even;
    case 1: return softfloat_round_minMag;
    case 2: return softfloat_round_min;
    case 3: return softfloat_round_max;
    case 4: return softfloat_round_near_maxMag;
    default: return softfloat_round_near_even;
  }
}

bool is_nan(std::uint32_t value) { return (value & 0x7fffffffu) > 0x7f800000u; }
bool is_snan(std::uint32_t value) { return is_nan(value) && !(value & 0x00400000u); }

std::uint32_t fminmax(std::uint32_t a_bits, std::uint32_t b_bits, bool maximum) {
  if (is_nan(a_bits) || is_nan(b_bits)) {
    if (is_snan(a_bits) || is_snan(b_bits)) softfloat_raiseFlags(softfloat_flag_invalid);
    if (is_nan(a_bits) && is_nan(b_bits)) return kCanonicalNan;
    return is_nan(a_bits) ? b_bits : a_bits;
  }
  if (((a_bits | b_bits) << 1) == 0) return maximum ? (a_bits & b_bits) : (a_bits | b_bits);
  const bool a_lt_b = f32_lt(float32_t{a_bits}, float32_t{b_bits});
  return maximum ? (a_lt_b ? b_bits : a_bits) : (a_lt_b ? a_bits : b_bits);
}

std::uint64_t word_result(std::uint32_t value, std::uint32_t xlen) {
  return xlen == 64 ? static_cast<std::uint64_t>(static_cast<std::int64_t>(
                         static_cast<std::int32_t>(value)))
                    : value;
}

std::uint32_t fclass(std::uint32_t value) {
  const unsigned sign = value >> 31;
  const unsigned exponent = (value >> 23) & 0xff;
  const unsigned fraction = value & 0x7fffff;
  if (exponent == 0xff) {
    if (!fraction) return 1u << (sign ? 0 : 7); // -inf / +inf
    return 1u << (is_snan(value) ? 8 : 9);
  }
  if (!exponent) {
    if (!fraction) return 1u << (sign ? 3 : 4); // -0 / +0
    return 1u << (sign ? 2 : 5);                // subnormal
  }
  return 1u << (sign ? 1 : 6);                  // normal
}

}  // namespace

extern "C" void npc_f32_execute(uint64_t operand_a, uint64_t operand_b, uint64_t operand_c,
    uint32_t operation, uint32_t rounding_mode, uint32_t xlen, uint64_t* result,
    uint32_t* exception_flags) {
  const std::uint32_t a_bits = static_cast<std::uint32_t>(operand_a);
  const std::uint32_t b_bits = static_cast<std::uint32_t>(operand_b);
  std::uint32_t c_bits = static_cast<std::uint32_t>(operand_c);
  const float32_t a{a_bits};
  const float32_t b{b_bits};

  softfloat_roundingMode = to_softfloat_rounding_mode(rounding_mode);
  softfloat_detectTininess = softfloat_tininess_afterRounding;
  softfloat_exceptionFlags = 0;

  std::uint64_t computed = 0;
  float32_t fresult{};
  switch (operation) {
    case kFadd: fresult = f32_add(a, b); computed = fresult.v; break;
    case kFsub: fresult = f32_sub(a, b); computed = fresult.v; break;
    case kFmul: fresult = f32_mul(a, b); computed = fresult.v; break;
    case kFdiv: fresult = f32_div(a, b); computed = fresult.v; break;
    case kFsqrt: fresult = f32_sqrt(a); computed = fresult.v; break;
    case kFmadd: fresult = softfloat_mulAddF32(a_bits, b_bits, c_bits, 0); computed = fresult.v; break;
    case kFmsub: fresult = softfloat_mulAddF32(a_bits, b_bits, c_bits, softfloat_mulAdd_subC); computed = fresult.v; break;
    case kFnmsub:
      fresult = softfloat_mulAddF32(a_bits, b_bits, c_bits, softfloat_mulAdd_subProd);
      computed = fresult.v;
      break;
    case kFnmadd:
      c_bits ^= 0x80000000u;
      fresult = softfloat_mulAddF32(a_bits, b_bits, c_bits, softfloat_mulAdd_subProd);
      computed = fresult.v;
      break;
    case kFsgnj: computed = (a_bits & 0x7fffffffu) | (b_bits & 0x80000000u); break;
    case kFsgnjn: computed = (a_bits & 0x7fffffffu) | ((~b_bits) & 0x80000000u); break;
    case kFsgnjx: computed = (a_bits & 0x7fffffffu) | ((a_bits ^ b_bits) & 0x80000000u); break;
    case kFmin: computed = fminmax(a_bits, b_bits, false); break;
    case kFmax: computed = fminmax(a_bits, b_bits, true); break;
    case kFeq: computed = f32_eq(a, b); break;
    case kFlt: computed = f32_lt(a, b); break;
    case kFle: computed = f32_le(a, b); break;
    case kFcvtW: computed = word_result(static_cast<std::uint32_t>(f32_to_i32(a, softfloat_roundingMode, true)), xlen); break;
    case kFcvtWu: computed = word_result(static_cast<std::uint32_t>(f32_to_ui32(a, softfloat_roundingMode, true)), xlen); break;
    case kFcvtL: computed = static_cast<std::uint64_t>(f32_to_i64(a, softfloat_roundingMode, true)); break;
    case kFcvtLu: computed = f32_to_ui64(a, softfloat_roundingMode, true); break;
    case kFcvtSW: fresult = i32_to_f32(static_cast<std::int32_t>(operand_a)); computed = fresult.v; break;
    case kFcvtSWu: fresult = ui32_to_f32(static_cast<std::uint32_t>(operand_a)); computed = fresult.v; break;
    case kFcvtSL: fresult = i64_to_f32(static_cast<std::int64_t>(operand_a)); computed = fresult.v; break;
    case kFcvtSLu: fresult = ui64_to_f32(operand_a); computed = fresult.v; break;
    case kFmvXW: computed = word_result(a_bits, xlen); break;
    case kFclass: computed = fclass(a_bits); break;
    case kFmvWX: computed = static_cast<std::uint32_t>(operand_a); break;
    default: break;
  }

  *result = computed;
  *exception_flags = softfloat_exceptionFlags & 0x1f;
}
