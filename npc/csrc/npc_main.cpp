// Standalone main for NPC
// This file provides the original standalone functionality
#include "npc_core.h"
#include <cstdio>
#include <cstdlib>
#include <verilated.h>

// DPI-C functions
extern "C" {
    int load_image(const char *filename);
    void pmem_dump(uint32_t addr, int len);
}

int main(int argc, char **argv) {
    // Load binary image
    long img_size = load_image(argv[1]);
    if (img_size < 0) {
        return 1;
    }

    printf("Loaded image: %s (%ld bytes)\n", argv[1], img_size);

    // Initialize Verilator
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);
    
    init_npc();
    
    printf("Starting Chisel CPU simulation...\n");
    
    // Main simulation loop
    while (!is_npc_finished()) {
        single_run();
        getvalue();
    }
    
    bool timeout = !Verilated::gotFinish();
    
    if (Verilated::gotFinish()) {
        printf("Simulation ended by $finish\n");
    } else {
        printf("Simulation TIMEOUT at MAX_SIM_TIME\n");
    }
    
    // Cleanup
    cleanup_npc();
    
    return timeout ? 1 : 0;
}
