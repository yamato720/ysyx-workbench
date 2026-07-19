#ifndef NPC_DEBUG_H
#define NPC_DEBUG_H

// Stable instantaneous backpressure-reason bits exported by the hardware.
// Keep existing assignments fixed; future pipeline or OoO structures may use
// higher currently-unassigned bits without changing NEMU's decoding of these.
enum {
  NPC_BACKPRESSURE_IF_AXI = 1u << 0,
  NPC_BACKPRESSURE_IF_ID = 1u << 1,
  NPC_BACKPRESSURE_ID_EX = 1u << 2,
  NPC_BACKPRESSURE_EX_MEM = 1u << 3,
  NPC_BACKPRESSURE_MEM_LSU_WAIT = 1u << 4,
  NPC_BACKPRESSURE_LSU_AXI = 1u << 5,
  NPC_BACKPRESSURE_SERIAL_EX = 1u << 6,
  NPC_BACKPRESSURE_REDIRECT_FLUSH = 1u << 7,
  NPC_BACKPRESSURE_UNCLASSIFIED_BUSY = 1u << 8,
};

#endif // NPC_DEBUG_H
