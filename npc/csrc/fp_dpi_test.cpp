#include <cstdint>
#include <cstdio>

#include "fp_dpi.h"

namespace {

constexpr std::uint32_t kFadd = 0;
constexpr std::uint32_t kFsub = 1;
constexpr std::uint32_t kFmul = 2;
constexpr std::uint32_t kFdiv = 3;
constexpr std::uint32_t kFmadd = 5;
constexpr std::uint32_t kFmsub = 6;
constexpr std::uint32_t kFnmsub = 7;
constexpr std::uint32_t kFnmadd = 8;

struct TestCase {
  const char* name;
  std::uint32_t operandA;
  std::uint32_t operandB;
  std::uint32_t operandC;
  std::uint32_t op;
  std::uint32_t result;
  std::uint32_t exceptionFlags;
};

}  // namespace

int main() {
  const TestCase tests[] = {
    {"fadd", 0x3f800000U, 0x40000000U, 0, kFadd, 0x40400000U, 0x00U},
    {"fsub", 0x40400000U, 0x3f800000U, 0, kFsub, 0x40000000U, 0x00U},
    {"fmul", 0x3fc00000U, 0x40000000U, 0, kFmul, 0x40400000U, 0x00U},
    {"fdiv", 0x40400000U, 0x40000000U, 0, kFdiv, 0x3fc00000U, 0x00U},
    {"fmadd", 0x3f800000U, 0x40000000U, 0x40400000U, kFmadd, 0x40a00000U, 0x00U},
    {"fmsub", 0x3f800000U, 0x40000000U, 0x40400000U, kFmsub, 0xbf800000U, 0x00U},
    {"fnmsub", 0x3f800000U, 0x40000000U, 0x40400000U, kFnmsub, 0x3f800000U, 0x00U},
    {"fnmadd", 0x3f800000U, 0x40000000U, 0x40400000U, kFnmadd, 0xc0a00000U, 0x00U},
    {"divide-by-zero", 0x3f800000U, 0x00000000U, 0, kFdiv, 0x7f800000U, 0x08U},
    {"overflow", 0x7f7fffffU, 0x7f7fffffU, 0, kFadd, 0x7f800000U, 0x05U},
    {"signaling-nan", 0x7fa00001U, 0x3f800000U, 0, kFadd, 0x7fc00000U, 0x10U},
  };

  for (const auto& test : tests) {
    std::uint64_t result = 0;
    std::uint32_t exceptionFlags = 0;
    npc_f32_execute(test.operandA, test.operandB, test.operandC, test.op, 0, 32, &result, &exceptionFlags);
    if (result != test.result || exceptionFlags != test.exceptionFlags) {
      std::fprintf(stderr, "%s: result=%08x flags=%02x, expected result=%08x flags=%02x\n",
        test.name, static_cast<std::uint32_t>(result), exceptionFlags, test.result, test.exceptionFlags);
      return 1;
    }
  }

  return 0;
}
