#include "fpga-mailbox.h"

#include <stdatomic.h>
#include <stddef.h>

#if defined(__has_include)
#if __has_include(<generated/autoconf.h>)
#include <generated/autoconf.h>
#endif
#endif

#ifdef CONFIG_RISCV_F
#include "internals.h"
#include "softfloat.h"
#endif

enum {
  FFLAGS_MASK = 0x1f,
  CANONICAL_NAN = 0x7fc00000u,
};

static uint64_t join_u64(uint32_t low, uint32_t high) {
  return (uint64_t)low | ((uint64_t)high << 32);
}

static uint64_t nan_box(uint32_t value, unsigned xlen) {
  return xlen == 64 ? UINT64_C(0xffffffff00000000) | value : value;
}

static uint64_t word_result(uint32_t value, unsigned xlen) {
  return xlen == 64 ? (uint64_t)(int64_t)(int32_t)value : value;
}

#ifdef CONFIG_RISCV_F
static uint32_t f32_operand(uint64_t value, unsigned xlen) {
  if (xlen == 64 && (value >> 32) != UINT32_MAX) return CANONICAL_NAN;
  return (uint32_t)value;
}

static bool resolve_rounding_mode(const struct nemu_fpga_fallback_request *request,
                                  uint_fast8_t *rounding_mode) {
  const unsigned encoded = (request->instruction >> 12) & 7;
  const unsigned resolved = encoded == 7 ? (request->fcsr >> 5) & 7 : encoded;
  if (encoded == 5 || encoded == 6 || resolved > 4 ||
      request->rounding_mode != resolved) return false;
  switch (resolved) {
    case 0: *rounding_mode = softfloat_round_near_even; break;
    case 1: *rounding_mode = softfloat_round_minMag; break;
    case 2: *rounding_mode = softfloat_round_min; break;
    case 3: *rounding_mode = softfloat_round_max; break;
    case 4: *rounding_mode = softfloat_round_near_maxMag; break;
    default: return false;
  }
  return true;
}
#endif

void nemu_fpga_fallback_execute(const struct nemu_fpga_fallback_request *request,
                                unsigned xlen,
                                struct nemu_fpga_fallback_response *response) {
  *response = (struct nemu_fpga_fallback_response) {
    .sequence = request != NULL ? request->sequence : 0,
    .illegal = true,
  };
  if (request == NULL || (xlen != 32 && xlen != 64)) return;

#ifndef CONFIG_RISCV_F
  (void)xlen;
  return;
#else
  uint_fast8_t rounding_mode;
  if (!resolve_rounding_mode(request, &rounding_mode)) return;

  const uint32_t a_bits = f32_operand(request->operand_a, xlen);
  const uint32_t b_bits = f32_operand(request->operand_b, xlen);
  uint32_t c_bits = f32_operand(request->operand_c, xlen);
  const float32_t a = { .v = a_bits };
  const float32_t b = { .v = b_bits };
  float32_t fp_result = { .v = 0 };
  uint64_t result = 0;
  bool supported = true;

  const uint_fast8_t saved_rounding_mode = softfloat_roundingMode;
  const uint_fast8_t saved_tininess = softfloat_detectTininess;
  const uint_fast8_t saved_exception_flags = softfloat_exceptionFlags;
  softfloat_roundingMode = rounding_mode;
  softfloat_detectTininess = softfloat_tininess_afterRounding;
  softfloat_exceptionFlags = 0;

  switch (request->operation) {
    case NEMU_FPGA_FADD: fp_result = f32_add(a, b); result = nan_box(fp_result.v, xlen); break;
    case NEMU_FPGA_FSUB: fp_result = f32_sub(a, b); result = nan_box(fp_result.v, xlen); break;
    case NEMU_FPGA_FMUL: fp_result = f32_mul(a, b); result = nan_box(fp_result.v, xlen); break;
    case NEMU_FPGA_FDIV: fp_result = f32_div(a, b); result = nan_box(fp_result.v, xlen); break;
    case NEMU_FPGA_FSQRT: fp_result = f32_sqrt(a); result = nan_box(fp_result.v, xlen); break;
    case NEMU_FPGA_FMADD:
      fp_result = softfloat_mulAddF32(a_bits, b_bits, c_bits, 0);
      result = nan_box(fp_result.v, xlen);
      break;
    case NEMU_FPGA_FMSUB:
      fp_result = softfloat_mulAddF32(a_bits, b_bits, c_bits, softfloat_mulAdd_subC);
      result = nan_box(fp_result.v, xlen);
      break;
    case NEMU_FPGA_FNMSUB:
      fp_result = softfloat_mulAddF32(a_bits, b_bits, c_bits, softfloat_mulAdd_subProd);
      result = nan_box(fp_result.v, xlen);
      break;
    case NEMU_FPGA_FNMADD:
      c_bits ^= 0x80000000u;
      fp_result = softfloat_mulAddF32(a_bits, b_bits, c_bits, softfloat_mulAdd_subProd);
      result = nan_box(fp_result.v, xlen);
      break;
    case NEMU_FPGA_FCVT_W:
      result = word_result((uint32_t)f32_to_i32(a, rounding_mode, true), xlen);
      break;
    case NEMU_FPGA_FCVT_WU:
      result = word_result((uint32_t)f32_to_ui32(a, rounding_mode, true), xlen);
      break;
    case NEMU_FPGA_FCVT_L:
      if (xlen == 64) result = (uint64_t)f32_to_i64(a, rounding_mode, true);
      else supported = false;
      break;
    case NEMU_FPGA_FCVT_LU:
      if (xlen == 64) result = f32_to_ui64(a, rounding_mode, true);
      else supported = false;
      break;
    case NEMU_FPGA_FCVT_S_W:
      fp_result = i32_to_f32((int32_t)request->operand_a);
      result = nan_box(fp_result.v, xlen);
      break;
    case NEMU_FPGA_FCVT_S_WU:
      fp_result = ui32_to_f32((uint32_t)request->operand_a);
      result = nan_box(fp_result.v, xlen);
      break;
    case NEMU_FPGA_FCVT_S_L:
      if (xlen == 64) {
        fp_result = i64_to_f32((int64_t)request->operand_a);
        result = nan_box(fp_result.v, xlen);
      } else supported = false;
      break;
    case NEMU_FPGA_FCVT_S_LU:
      if (xlen == 64) {
        fp_result = ui64_to_f32(request->operand_a);
        result = nan_box(fp_result.v, xlen);
      } else supported = false;
      break;
    default: supported = false; break;
  }

  const uint_fast8_t result_flags = softfloat_exceptionFlags;
  softfloat_roundingMode = saved_rounding_mode;
  softfloat_detectTininess = saved_tininess;
  softfloat_exceptionFlags = saved_exception_flags;
  if (supported) {
    response->result = result;
    response->exception_flags = result_flags & FFLAGS_MASK;
    response->illegal = false;
  }
#endif
}

enum nemu_fpga_mailbox_service_result
nemu_fpga_mailbox_service_once(const struct nemu_fpga_mailbox_io *io, unsigned xlen) {
  if (io == NULL || io->read32 == NULL || io->write32 == NULL ||
      (xlen != 32 && xlen != 64)) return NEMU_FPGA_MB_IO_ERROR;

  const uint32_t status = io->read32(io->opaque, NEMU_FPGA_MB_STATUS);
  if (!(status & NEMU_FPGA_MB_REQUEST_PENDING) ||
      (status & NEMU_FPGA_MB_RESPONSE_PENDING)) return NEMU_FPGA_MB_IDLE;
  if (io->max_request_cycles != 0 &&
      io->read32(io->opaque, NEMU_FPGA_MB_TIMEOUT_CYCLES) > io->max_request_cycles) {
    return NEMU_FPGA_MB_TIMEOUT;
  }

  struct nemu_fpga_fallback_request request = {0};
  request.sequence = io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_SEQUENCE);
  request.pc = join_u64(io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_PC_LOW),
                        io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_PC_HIGH));
  request.instruction = io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_INSTRUCTION);
  const uint32_t operation_rm = io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_OPERATION_RM);
  request.operation = operation_rm & 0x1f;
  request.rounding_mode = (operation_rm >> 5) & 7;
  request.fcsr = io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_FCSR) & 0xff;
  request.operand_a = join_u64(io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_A_LOW),
                               io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_A_HIGH));
  request.operand_b = join_u64(io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_B_LOW),
                               io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_B_HIGH));
  request.operand_c = join_u64(io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_C_LOW),
                               io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_C_HIGH));

  struct nemu_fpga_fallback_response response;
  nemu_fpga_fallback_execute(&request, xlen, &response);

  const uint32_t current_status = io->read32(io->opaque, NEMU_FPGA_MB_STATUS);
  const uint32_t current_sequence = io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_SEQUENCE);
  if (!(current_status & NEMU_FPGA_MB_REQUEST_PENDING) ||
      (current_status & NEMU_FPGA_MB_RESPONSE_PENDING) ||
      current_sequence != request.sequence) return NEMU_FPGA_MB_STALE;

  io->write32(io->opaque, NEMU_FPGA_MB_RESPONSE_SEQUENCE, response.sequence);
  io->write32(io->opaque, NEMU_FPGA_MB_RESPONSE_RESULT_LOW, (uint32_t)response.result);
  io->write32(io->opaque, NEMU_FPGA_MB_RESPONSE_RESULT_HIGH, (uint32_t)(response.result >> 32));
  io->write32(io->opaque, NEMU_FPGA_MB_RESPONSE_FLAGS,
              response.exception_flags | ((uint32_t)response.illegal << 8));
  atomic_thread_fence(memory_order_release);

  const uint32_t commit_status = io->read32(io->opaque, NEMU_FPGA_MB_STATUS);
  const uint32_t commit_sequence = io->read32(io->opaque, NEMU_FPGA_MB_REQUEST_SEQUENCE);
  if (!(commit_status & NEMU_FPGA_MB_REQUEST_PENDING) ||
      (commit_status & NEMU_FPGA_MB_RESPONSE_PENDING) ||
      commit_sequence != request.sequence) return NEMU_FPGA_MB_STALE;
  io->write32(io->opaque, NEMU_FPGA_MB_RESPONSE_COMMIT, 1);
  return NEMU_FPGA_MB_RESPONDED;
}

int nemu_fpga_runtime_hold_reset(const struct nemu_fpga_mailbox_io *io) {
  if (io == NULL || io->write32 == NULL) return -1;
  io->write32(io->opaque, NEMU_FPGA_RT_CONTROL,
              NEMU_FPGA_RT_CORE_RESET | NEMU_FPGA_RT_CLEAR_HALT);
  atomic_thread_fence(memory_order_seq_cst);
  return 0;
}

int nemu_fpga_runtime_start(const struct nemu_fpga_mailbox_io *io) {
  if (io == NULL || io->write32 == NULL) return -1;
  atomic_thread_fence(memory_order_release);
  io->write32(io->opaque, NEMU_FPGA_RT_CONTROL, NEMU_FPGA_RT_CLEAR_HALT);
  return 0;
}

int nemu_fpga_runtime_start_halted(const struct nemu_fpga_mailbox_io *io) {
  if (io == NULL || io->write32 == NULL) return -1;
  atomic_thread_fence(memory_order_release);
  io->write32(io->opaque, NEMU_FPGA_RT_CONTROL, 0);
  return 0;
}

uint64_t nemu_fpga_runtime_read_counter(const struct nemu_fpga_mailbox_io *io,
                                        uint32_t low_offset, uint32_t high_offset) {
  if (io == NULL || io->read32 == NULL) return 0;
  uint32_t high_before;
  uint32_t high_after;
  uint32_t low;
  do {
    high_before = io->read32(io->opaque, high_offset);
    low = io->read32(io->opaque, low_offset);
    high_after = io->read32(io->opaque, high_offset);
  } while (high_before != high_after);
  return join_u64(low, high_after);
}

struct nemu_fpga_runtime_event
nemu_fpga_runtime_service_once(const struct nemu_fpga_mailbox_io *io, unsigned xlen) {
  struct nemu_fpga_runtime_event event = { .type = NEMU_FPGA_RT_EVENT_NONE };
  const enum nemu_fpga_mailbox_service_result mailbox =
      nemu_fpga_mailbox_service_once(io, xlen);
  if (mailbox == NEMU_FPGA_MB_TIMEOUT || mailbox == NEMU_FPGA_MB_IO_ERROR) {
    event.type = NEMU_FPGA_RT_EVENT_ERROR;
    event.error = mailbox;
    return event;
  }

  const uint32_t status = io->read32(io->opaque, NEMU_FPGA_RT_STATUS);
  if (status & NEMU_FPGA_RT_PROTOCOL_ERROR) {
    event.type = NEMU_FPGA_RT_EVENT_ERROR;
    event.error = NEMU_FPGA_MB_STALE;
  } else if (status & NEMU_FPGA_RT_PUTCH_PENDING) {
    event.type = NEMU_FPGA_RT_EVENT_PUTCH;
    event.value = io->read32(io->opaque, NEMU_FPGA_RT_PUTCH_DATA) & 0xff;
    const uint32_t control = io->read32(io->opaque, NEMU_FPGA_RT_CONTROL);
    io->write32(io->opaque, NEMU_FPGA_RT_CONTROL,
                (control & NEMU_FPGA_RT_CORE_RESET) | NEMU_FPGA_RT_ACK_PUTCH);
  } else if ((status & NEMU_FPGA_RT_HALTED) &&
             (io->read32(io->opaque, NEMU_FPGA_DEBUG_PROTOCOL) != NEMU_FPGA_DEBUG_PROTOCOL_V2 ||
              (io->read32(io->opaque, NEMU_FPGA_DEBUG_STOP_REASON) & 0xf) ==
                  NEMU_FPGA_STOP_EBREAK)) {
    event.type = NEMU_FPGA_RT_EVENT_HALT;
    event.value = nemu_fpga_runtime_read_counter(io, NEMU_FPGA_RT_HALT_CODE_LOW,
                                                 NEMU_FPGA_RT_HALT_CODE_HIGH);
  }
  return event;
}
