// DiffTest implementation - Load NEMU as reference
#include "difftest.h"
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <cassert>

// Static members initialization
void *DiffTest::handle = nullptr;
bool DiffTest::is_enabled = false;
void (*DiffTest::ref_difftest_memcpy)(uint32_t, void*, size_t, bool) = nullptr;
void (*DiffTest::ref_difftest_regcpy)(void*, bool) = nullptr;
void (*DiffTest::ref_difftest_exec)(uint64_t) = nullptr;
void (*DiffTest::ref_difftest_raise_intr)(uint64_t) = nullptr;
void (*DiffTest::ref_difftest_init)(int) = nullptr;

bool DiffTest::init(const char *ref_so_file, long img_size) {
    if (ref_so_file == nullptr) {
        printf("[DiffTest] No reference .so file provided, DiffTest disabled\n");
        is_enabled = false;
        return false;
    }
    
    // Open the shared library
    handle = dlopen(ref_so_file, RTLD_LAZY);
    if (!handle) {
        printf("[DiffTest] Failed to open %s: %s\n", ref_so_file, dlerror());
        is_enabled = false;
        return false;
    }
    
    // Load function pointers
    ref_difftest_memcpy = (void (*)(uint32_t, void*, size_t, bool))dlsym(handle, "difftest_memcpy");
    ref_difftest_regcpy = (void (*)(void*, bool))dlsym(handle, "difftest_regcpy");
    ref_difftest_exec = (void (*)(uint64_t))dlsym(handle, "difftest_exec");
    ref_difftest_raise_intr = (void (*)(uint64_t))dlsym(handle, "difftest_raise_intr");
    ref_difftest_init = (void (*)(int))dlsym(handle, "difftest_init");
    
    if (!ref_difftest_memcpy || !ref_difftest_regcpy || 
        !ref_difftest_exec || !ref_difftest_init) {
        printf("[DiffTest] Failed to load DiffTest functions from %s\n", ref_so_file);
        dlclose(handle);
        handle = nullptr;
        is_enabled = false;
        return false;
    }
    
    // Initialize NEMU reference
    ref_difftest_init(0);
    
    printf("[DiffTest] Initialized with reference: %s\n", ref_so_file);
    is_enabled = true;
    return true;
}

void DiffTest::cleanup() {
    if (handle) {
        dlclose(handle);
        handle = nullptr;
    }
    is_enabled = false;
}

void DiffTest::memcpy(uint32_t addr, void *buf, size_t n, bool direction) {
    if (is_enabled && ref_difftest_memcpy) {
        ref_difftest_memcpy(addr, buf, n, direction);
    }
}

void DiffTest::regcpy(CPU_state *dut, bool direction) {
    if (is_enabled && ref_difftest_regcpy) {
        ref_difftest_regcpy(dut, direction);
    }
}

void DiffTest::exec(uint64_t n) {
    if (is_enabled && ref_difftest_exec) {
        ref_difftest_exec(n);
    }
}

void DiffTest::raise_intr(uint64_t NO) {
    if (is_enabled && ref_difftest_raise_intr) {
        ref_difftest_raise_intr(NO);
    }
}

void DiffTest::get_ref_state(CPU_state *ref) {
    if (is_enabled && ref_difftest_regcpy) {
        ref_difftest_regcpy(ref, DIFFTEST_TO_DUT);
    }
}

bool DiffTest::check(CPU_state *dut) {
    if (!is_enabled) return true;
    
    CPU_state ref;
    get_ref_state(&ref);
    
    bool pass = true;
    
    // Check PC
    if (dut->pc != ref.pc) {
        printf("[DiffTest] PC mismatch: DUT=0x%lx, REF=0x%lx\n", dut->pc, ref.pc);
        pass = false;
    }
    
    // Check GPRs
    for (int i = 0; i < NR_GPR; i++) {
        if (dut->gpr[i] != ref.gpr[i]) {
            printf("[DiffTest] GPR[%d] mismatch: DUT=0x%lx, REF=0x%lx\n", 
                   i, dut->gpr[i], ref.gpr[i]);
            pass = false;
        }
    }
    
    return pass;
}

bool DiffTest::step(CPU_state *dut, uint64_t pc) {
    if (!is_enabled) return true;
    
    // Execute one instruction on reference
    exec(1);
    
    // Check if states match
    return check(dut);
}

void DiffTest::skip_ref() {
    // Skip reference for I/O or other special instructions
    // Reference will copy state from DUT
    if (is_enabled && ref_difftest_regcpy) {
        CPU_state dut_state;
        // This should be called with actual DUT state
        // For now, just skip one instruction on ref
        exec(1);
    }
}

bool DiffTest::enabled() {
    return is_enabled;
}
