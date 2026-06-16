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

#define MAX_SIM_TIME 1000000 * 29 * 2// Max simulation time in ticks


// Print instruction trace
// Output when tick_pc is about to trigger (before next instruction fetch)
static uint64_t last_pc = 0;
static void print_itrace(TopModule *cpu, uint64_t cycle, int* inst_count) {
    if (cpu->io_tick_pc_debug && cpu->io_pc != last_pc) {
        printf("[%8d] PC=0x%016lx INST=0x%08x\n", 
               *inst_count, cpu->io_pc, cpu->io_instruction);
        last_pc = cpu->io_pc;
        (*inst_count)++;
        // printf("ins %4d\n", *inst_count);
    }
    // printf("ins_high: 0x%02x ins_low: 0x%02x\n", cpu->io_ins_high, cpu->io_ins_low);
    // printf("ins 0: 0x%08lx ", cpu->io_regs_out_0);
    // printf("ins 1: 0x%08lx ", cpu->io_regs_out_1);
    // printf("ins 2: 0x%08lx ", cpu->io_regs_out_2);
    // printf("ins 3: 0x%08lx\n", cpu->io_regs_out_3);
    // printf("ins 4: 0x%08lx ", cpu->io_regs_out_4);
    // printf("ins 5: 0x%08lx ", cpu->io_regs_out_5);
    // printf("ins 6: 0x%08lx ", cpu->io_regs_out_6);
    // printf("ins 7: 0x%08lx\n", cpu->io_regs_out_7);
    // printf("ins 8: 0x%08lx ", cpu->io_regs_out_8);
    // printf("ins 9: 0x%08lx ", cpu->io_regs_out_9);
    // printf("ins10: 0x%08lx ", cpu->io_regs_out_10);
    // printf("ins11: 0x%08lx\n", cpu->io_regs_out_11);
    // printf("wc: %u\n", cpu->io_waitcycles);


    // printf("address low: 0x%016lx high: 0x%016lx\n", cpu->io_addr_low, cpu->io_addr_high);
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
        if (cpu->io_pc == last_halt_pc) {
            halt_count++;
            if (halt_count >= HALT_THRESHOLD) {
                printf("Program halted at PC=0x%016lx after %lu cycles\n", cpu->io_pc, cycle_count);
                Verilated::gotFinish(true);  // 设置finish标志，正常退出
                break;
            }
        } else {
            last_halt_pc = cpu->io_pc;
            halt_count = 0;
        }
        if (cpu->io_tick_pc_debug && cpu->io_pc != last_pc) {
            inst_count++;
            finished = true;
        }
    }
    
}

void getvalue() {
    printf("[%8d] PC=0x%016lx INST=0x%08x\n", 
               inst_count, cpu->io_pc, cpu->io_instruction);
        last_pc = cpu->io_pc;
    for(int i = 0; i < 32; i++) {
        printf("reg[%2d]=0x%016lx ", i, cpu->io_regs_out_0 + i);
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
