// DiffTest support for NPC - Compare with NEMU reference
// This file provides integration between NPC (DUT) and NEMU (REF)

#ifndef __NPC_DIFFTEST_H__
#define __NPC_DIFFTEST_H__

#include <cstdint>
#include <cstddef>



typedef struct {
    uint64_t gpr[NR_GPR];
    uint64_t pc;
} CPU_state;

// DiffTest API
class DiffTest {
public:
    // Initialize DiffTest with NEMU .so file
    static bool init(const char *ref_so_file, long img_size);
    
    // Clean up
    static void cleanup();
    
    // Copy memory between DUT and REF
    static void memcpy(uint32_t addr, void *buf, size_t n, bool direction);
    
    // Copy registers between DUT and REF
    static void regcpy(CPU_state *dut, bool direction);
    
    // Execute n instructions on REF
    static void exec(uint64_t n);
    
    // Raise interrupt on REF
    static void raise_intr(uint64_t NO);
    
    // Check if DUT state matches REF state
    static bool check(CPU_state *dut);
    
    // Single step and check
    static bool step(CPU_state *dut, uint64_t pc);
    
    // Skip reference for special instructions
    static void skip_ref();
    
    // Get reference state
    static void get_ref_state(CPU_state *ref);
    
    // Is DiffTest enabled?
    static bool enabled();
    
private:
    static void *handle;
    static bool is_enabled;
    
    // Function pointers loaded from NEMU .so
    static void (*ref_difftest_memcpy)(uint32_t addr, void *buf, size_t n, bool direction);
    static void (*ref_difftest_regcpy)(void *dut, bool direction);
    static void (*ref_difftest_exec)(uint64_t n);
    static void (*ref_difftest_raise_intr)(uint64_t NO);
    static void (*ref_difftest_init)(int port);
};

#endif // __NPC_DIFFTEST_H__
