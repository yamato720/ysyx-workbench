#ifndef NPC_FP_DPI_H
#define NPC_FP_DPI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void npc_f32_execute(
    uint64_t operand_a,
    uint64_t operand_b,
    uint64_t operand_c,
    uint32_t operation,
    uint32_t rounding_mode,
    uint32_t xlen,
    uint64_t* result,
    uint32_t* exception_flags);

#ifdef __cplusplus
}
#endif

#endif
