#ifndef NEMU_NPC_FPGA_TRACE_H
#define NEMU_NPC_FPGA_TRACE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define NPC_FPGA_TRACE_STAGE_COUNT 5u
#define NPC_FPGA_TRACE_RECORD_BYTES_V1 72u

/* Little-endian HBM trace ABI emitted by the U55C v12 debug xclbin. */
struct npc_fpga_trace_record {
  uint64_t sequence;
  uint64_t pc;
  uint32_t instruction;
  uint32_t status;
  uint64_t commit_cycle;
  uint64_t stage[NPC_FPGA_TRACE_STAGE_COUNT];
} __attribute__((packed));

#ifdef __cplusplus
static_assert(sizeof(struct npc_fpga_trace_record) == NPC_FPGA_TRACE_RECORD_BYTES_V1,
              "FPGA trace record ABI must stay 72 bytes");
extern "C" {
#else
_Static_assert(sizeof(struct npc_fpga_trace_record) == NPC_FPGA_TRACE_RECORD_BYTES_V1,
               "FPGA trace record ABI must stay 72 bytes");
#endif

typedef int (*npc_fpga_trace_visitor)(const struct npc_fpga_trace_record *record,
                                      void *opaque);

bool npc_hardware_monitoring_available(void);
int npc_runtime_visit_trace(npc_fpga_trace_visitor visitor, void *opaque);
uint64_t npc_runtime_trace_dropped(void);

int nemu_fpga_runtime_validate_trace_records(
    const struct npc_fpga_trace_record *records, size_t count);

#ifdef __cplusplus
}
#endif

#endif
