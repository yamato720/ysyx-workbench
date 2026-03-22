// NPC core functions header
// This file provides the interface for both standalone NPC and NEMU integration
#ifndef NPC_CORE_H
#define NPC_CORE_H

#include <cstdint>
#include "VCPU.h"

typedef VCPU TopModule;

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
bool is_npc_finished();

// C-compatible interface for NEMU
#ifdef __cplusplus
extern "C" {
#endif

void npc_init();
void npc_single_run();
void npc_getvalue();
uint64_t npc_get_pc();
uint32_t npc_get_inst();
uint64_t npc_get_reg(int idx);
void npc_cleanup();
int npc_is_finished();
void npc_record_mem_access();
void npc_display_mem_access();

void npc_start_trace();
void npc_stop_trace();

#ifdef __cplusplus
}
#endif

#endif // NPC_CORE_H
