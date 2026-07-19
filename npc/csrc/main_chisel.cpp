// Main simulation driver for Chisel NPC (RISC-V CPU)
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <getopt.h>
#include <verilated.h>
#include <verilated_vcd_c.h>

// Include generated CPU header
#include "VCPU.h"
typedef VCPU TopModule;


TopModule *cpu = new TopModule;
VerilatedVcdC *trace = new VerilatedVcdC;

#define CPU_DEBUG_FRONTEND(model, field) ((model)->io_debug_frontend_##field)
#define CPU_DEBUG_BACKEND(model, field) ((model)->io_debug_backend_##field)
#define CPU_DEBUG_REGISTER(model, index) ((model)->io_debug_backend_registers_##index)

// Main simulation loop
uint64_t sim_time = 5;
uint64_t cycle_count = 0;
bool difftest_fail = false;
uint64_t last_halt_pc = 0;
int halt_count = 0;
const int HALT_THRESHOLD = 100;  // PC不变超过100次认为halt
int inst_count = 0;


// DPI-C functions
extern "C" {
    int load_image(const char *filename);
    void pmem_dump(uint32_t addr, int len);
}

#define MAX_SIM_TIME 1000000 * 29 * 2// Max simulation time in half-cycles


// Print instruction trace
// Output when the frontend accepts a new PC.
static uint64_t last_pc = 0;
static void print_itrace(TopModule *cpu, uint64_t cycle, int* inst_count) {
    if (CPU_DEBUG_FRONTEND(cpu, pcWriteEnable) && CPU_DEBUG_FRONTEND(cpu, currentPc) != last_pc) {
        printf("[%8d] PC=0x%016lx INST=0x%08x\n", 
               *inst_count, CPU_DEBUG_FRONTEND(cpu, currentPc), CPU_DEBUG_FRONTEND(cpu, frontendInstruction));
        last_pc = CPU_DEBUG_FRONTEND(cpu, currentPc);
        (*inst_count)++;
    }
}

static uint64_t read_debug_register(TopModule *cpu, int idx) {
#define READ_DEBUG_REGISTER(n) case n: return CPU_DEBUG_REGISTER(cpu, n)
    switch (idx) {
        READ_DEBUG_REGISTER(0);
        READ_DEBUG_REGISTER(1);
        READ_DEBUG_REGISTER(2);
        READ_DEBUG_REGISTER(3);
        READ_DEBUG_REGISTER(4);
        READ_DEBUG_REGISTER(5);
        READ_DEBUG_REGISTER(6);
        READ_DEBUG_REGISTER(7);
        READ_DEBUG_REGISTER(8);
        READ_DEBUG_REGISTER(9);
        READ_DEBUG_REGISTER(10);
        READ_DEBUG_REGISTER(11);
        READ_DEBUG_REGISTER(12);
        READ_DEBUG_REGISTER(13);
        READ_DEBUG_REGISTER(14);
        READ_DEBUG_REGISTER(15);
        READ_DEBUG_REGISTER(16);
        READ_DEBUG_REGISTER(17);
        READ_DEBUG_REGISTER(18);
        READ_DEBUG_REGISTER(19);
        READ_DEBUG_REGISTER(20);
        READ_DEBUG_REGISTER(21);
        READ_DEBUG_REGISTER(22);
        READ_DEBUG_REGISTER(23);
        READ_DEBUG_REGISTER(24);
        READ_DEBUG_REGISTER(25);
        READ_DEBUG_REGISTER(26);
        READ_DEBUG_REGISTER(27);
        READ_DEBUG_REGISTER(28);
        READ_DEBUG_REGISTER(29);
        READ_DEBUG_REGISTER(30);
        READ_DEBUG_REGISTER(31);
        default: return 0;
    }
#undef READ_DEBUG_REGISTER
}


void init_npc() {
    // Reset sequence
    cpu->clock = 0;
    cpu->reset = 1;
    cpu->eval();
    trace->dump(0);
    
    cpu->clock = 1;
    cpu->eval();
    trace->dump(1);
    
    cpu->clock = 0;
    cpu->eval();
    trace->dump(2);
    
    cpu->clock = 1;
    cpu->eval();
    trace->dump(3);
    
    // Release reset
    cpu->clock = 0;
    cpu->reset = 0;
    cpu->eval();
    trace->dump(4);
}


void single_run() {
    bool finished = false;
    while(finished == false){
        // Clock low
        cpu->clock = 0;
        cpu->eval();
        trace->dump(sim_time++);
        
        // Clock high
        cpu->clock = 1;
        cpu->eval();
        trace->dump(sim_time++);
        
        
        // Halt detection: if PC doesn't change for HALT_THRESHOLD cycles, assume halt
        if (CPU_DEBUG_FRONTEND(cpu, currentPc) == last_halt_pc) {
            halt_count++;
            if (halt_count >= HALT_THRESHOLD) {
                printf("Program halted at PC=0x%016lx after %lu cycles\n", CPU_DEBUG_FRONTEND(cpu, currentPc), cycle_count);
                Verilated::gotFinish(true);  // 设置finish标志，正常退出
                break;
            }
        } else {
            last_halt_pc = CPU_DEBUG_FRONTEND(cpu, currentPc);
            halt_count = 0;
        }
        if (CPU_DEBUG_FRONTEND(cpu, pcWriteEnable) && CPU_DEBUG_FRONTEND(cpu, currentPc) != last_pc) {
            inst_count++;
            finished = true;
        }
    }
    
}

void getvalue() {
    printf("[%8d] PC=0x%016lx INST=0x%08x\n", 
               inst_count, CPU_DEBUG_FRONTEND(cpu, currentPc), CPU_DEBUG_FRONTEND(cpu, frontendInstruction));
        last_pc = CPU_DEBUG_FRONTEND(cpu, currentPc);
    for(int i = 0; i < 32; i++) {
        printf("reg[%2d]=0x%016lx ", i, read_debug_register(cpu, i));
        if (i % 4 == 3) printf("\n");
    }
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
    
    // Create CPU instance
    
    // Create VCD trace

    cpu->trace(trace, 99);
    trace->open("wave.vcd");
    
    

    init_npc();
    

    printf("Starting Chisel CPU simulation...\n");
    
    
    
    while (sim_time < MAX_SIM_TIME && !Verilated::gotFinish() && !difftest_fail) {
        
        
        // Print instruction trace (unless in batch mode)
        single_run();
        cycle_count++;
        getvalue();
        
        
        // DiffTest check (on valid instruction commit)
        
        
        // Print progress (in batch mode, only every 100000 cycles)
        
        
    }
    
    bool timeout = !Verilated::gotFinish() && !difftest_fail;
    
    if (Verilated::gotFinish()) {
        printf("Simulation ended by $finish at cycle %lu\n", cycle_count);
    } else {
        printf("Simulation TIMEOUT at MAX_SIM_TIME, cycle %lu\n", cycle_count);
    }
    
    // Cleanup
    trace->close();
    delete trace;
    delete cpu;
    
    
    
    // Return failure if difftest failed or timeout
    return timeout ? 1 : 0;
}
