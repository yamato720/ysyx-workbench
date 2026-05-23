#ifndef ZCU102_NPC_DEBUGGER_H
#define ZCU102_NPC_DEBUGGER_H

#include <stdint.h>
#include <stddef.h>

#define ZNPC_REG_CONTROL                 0x000u
#define ZNPC_REG_STATUS                  0x004u
#define ZNPC_REG_BOOT_PC                 0x008u
#define ZNPC_REG_EXIT_CODE               0x00cu
#define ZNPC_REG_CYCLE_LOW               0x010u
#define ZNPC_REG_CYCLE_HIGH              0x014u
#define ZNPC_REG_INSTRET_LOW             0x018u
#define ZNPC_REG_INSTRET_HIGH            0x01cu
#define ZNPC_REG_LAST_COMMIT_PC_LOW      0x020u
#define ZNPC_REG_LAST_COMMIT_PC_HIGH     0x024u
#define ZNPC_REG_LAST_COMMIT_INST        0x028u
#define ZNPC_REG_LAST_COMMIT_RD          0x02cu
#define ZNPC_REG_LAST_COMMIT_WDATA_LOW   0x030u
#define ZNPC_REG_LAST_COMMIT_WDATA_HIGH  0x034u
#define ZNPC_REG_TRAP_CAUSE              0x038u
#define ZNPC_REG_TRACE_HEAD              0x040u
#define ZNPC_REG_TRACE_TAIL              0x044u
#define ZNPC_REG_TRACE_COUNT             0x048u
#define ZNPC_REG_TRACE_BASE              0x04cu
#define ZNPC_REG_TRACE_SIZE              0x050u
#define ZNPC_REG_PUTCH_DATA              0x060u
#define ZNPC_REG_PUTCH_STATUS            0x064u

#define ZNPC_CONTROL_RUN          (1u << 0)
#define ZNPC_CONTROL_RESET_CPU    (1u << 1)
#define ZNPC_CONTROL_HALT_REQ     (1u << 2)
#define ZNPC_CONTROL_SINGLE_STEP  (1u << 3)
#define ZNPC_CONTROL_TRACE_ENABLE (1u << 4)
#define ZNPC_CONTROL_CLEAR_TRACE  (1u << 5)
#define ZNPC_CONTROL_CLEAR_PUTCH  (1u << 6)
#define ZNPC_CONTROL_CLEAR_STATUS (1u << 7)

#define ZNPC_STATUS_RUNNING     (1u << 0)
#define ZNPC_STATUS_HALTED      (1u << 1)
#define ZNPC_STATUS_BUSY        (1u << 2)
#define ZNPC_STATUS_TRAP_VALID  (1u << 3)
#define ZNPC_STATUS_TRACE_FULL  (1u << 4)
#define ZNPC_STATUS_PUTCH_VALID (1u << 5)

typedef struct {
  uintptr_t regs_base;
  uintptr_t guest_base;
  size_t guest_size;
} znpc_runtime_t;

static inline volatile uint32_t *znpc_reg_ptr(const znpc_runtime_t *rt, uint32_t off) {
  return (volatile uint32_t *)(rt->regs_base + off);
}

static inline uint32_t znpc_read_reg(const znpc_runtime_t *rt, uint32_t off) {
  return *znpc_reg_ptr(rt, off);
}

static inline void znpc_write_reg(const znpc_runtime_t *rt, uint32_t off, uint32_t value) {
  *znpc_reg_ptr(rt, off) = value;
}

void znpc_reset(const znpc_runtime_t *rt);
void znpc_load_image(const znpc_runtime_t *rt, const void *image, size_t size);
void znpc_start(const znpc_runtime_t *rt, uint32_t boot_pc, int trace_enable);
uint32_t znpc_poll(const znpc_runtime_t *rt);
void znpc_drain_putch(const znpc_runtime_t *rt);
uint32_t znpc_exit_code(const znpc_runtime_t *rt);

#endif
