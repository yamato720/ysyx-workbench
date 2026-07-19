// NPC core functions header
// This file provides the interface for both standalone NPC and NEMU integration
#ifndef NPC_CORE_H
#define NPC_CORE_H

#include <cstdint>
#include "npc_debug.h"
#ifdef NPC_SOC
#include "VysyxSoCTop.h"
typedef VysyxSoCTop TopModule;
#else
#include "VCPU.h"
typedef VCPU TopModule;
#endif

// Global CPU instance (accessible for state reading) - renamed to avoid conflict
extern TopModule *npc_cpu;

// Core simulation functions
void init_npc();
void single_run();
void getvalue();
void cleanup_npc();

// State query functions
uint64_t get_npc_pc();
uint32_t get_npc_inst();
uint64_t get_npc_reg(int idx);
uint64_t get_npc_freg(int idx);
uint32_t get_npc_fcsr();
uint64_t get_npc_mstatus();
bool is_npc_finished();

// C-compatible interface for NEMU
#ifdef __cplusplus
extern "C" {
#endif

void npc_init();
void npc_single_run();
// Advance exactly one clock and return nonzero only when an instruction commits.
int npc_step_cycle();
void npc_getvalue();
uint64_t npc_get_pc();
uint32_t npc_get_inst();
uint64_t npc_get_current_pc();
uint32_t npc_get_frontend_instruction();
uint64_t npc_get_reg(int idx);
uint64_t npc_get_freg(int idx);
uint32_t npc_get_fcsr();
uint64_t npc_get_mstatus();
uint64_t npc_get_cycle_count();
uint64_t npc_get_commit_count();
uint32_t npc_get_backpressure_reasons();
uint32_t npc_get_pipeline_features();
uint64_t npc_get_pipeline_stall_count(uint32_t counter);
uint64_t npc_get_timing_sample_count(uint32_t timing_class);
uint64_t npc_get_timing_total_cycles(uint32_t timing_class, uint32_t stage);
uint64_t npc_get_timing_max_total_cycles(uint32_t timing_class);
uint64_t npc_get_timing_last_pc(uint32_t timing_class);
uint32_t npc_get_timing_last_instruction(uint32_t timing_class);
uint64_t npc_get_timing_last_stage_cycles(uint32_t timing_class, uint32_t stage);
uint32_t npc_get_last_timing_class();
uint64_t npc_get_last_timing_stage_cycles(uint32_t stage);
uint64_t npc_get_last_timing_total_cycles();
void npc_cleanup();
int npc_is_finished();
void npc_record_mem_access();
void npc_display_mem_access();

#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
void npc_start_trace();
void npc_stop_trace();
#endif

#ifdef __cplusplus
}
#endif

#endif // NPC_CORE_H
