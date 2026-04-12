// NPC core functions for both standalone and NEMU integration
#include "npc_core.h"
#include "VCPU___024root.h"
#include <cstdio>
#include <cstring>
#include <verilated.h>
#include <verilated_vcd_c.h>
#ifndef NPC_STANDALONE
enum { NEMU_RUNNING, NEMU_STOP, NEMU_END, NEMU_ABORT, NEMU_QUIT };
typedef struct {
    int state;
    uint64_t halt_pc;
    uint32_t halt_ret;
} NEMUState;
extern "C" { extern NEMUState nemu_state; }
#endif

// Global CPU instance and trace (renamed to avoid conflict with NEMU's cpu)
TopModule *npc_cpu = nullptr;
VerilatedVcdC *npc_trace = nullptr;

// Simulation state
uint64_t sim_time = 5;
uint64_t cycle_count = 0;
bool difftest_fail = false;
uint64_t last_halt_pc = 0;
int halt_count = 0;
const int HALT_THRESHOLD = 100;
int inst_count = 0;
static uint64_t last_pc = 0;

bool trace_enabled = false;

// mepc change monitor (enable for debugging CSR issues)
static uint64_t last_mepc = 0;
static bool mepc_monitor_enabled = false;

#define MAX_SIM_TIME 1000000 * 29 * 2

// RISC-V register names
static const char* reg_name[] = {
    "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
    "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
    "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
    "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"
};

// Simple RISC-V disassembler
static void disassemble(uint32_t inst, char* buf, size_t buf_size) {
    uint32_t opcode = inst & 0x7F;
    uint32_t rd = (inst >> 7) & 0x1F;
    uint32_t funct3 = (inst >> 12) & 0x7;
    uint32_t rs1 = (inst >> 15) & 0x1F;
    uint32_t rs2 = (inst >> 20) & 0x1F;
    uint32_t funct7 = (inst >> 25) & 0x7F;
    
    switch(opcode) {
        case 0x33: { // R-type
            if (funct7 == 0x00) {
                if (funct3 == 0x0) snprintf(buf, buf_size, "add %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x4) snprintf(buf, buf_size, "xor %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x6) snprintf(buf, buf_size, "or %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x7) snprintf(buf, buf_size, "and %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x1) snprintf(buf, buf_size, "sll %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x5) snprintf(buf, buf_size, "srl %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x2) snprintf(buf, buf_size, "slt %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x3) snprintf(buf, buf_size, "sltu %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else snprintf(buf, buf_size, "unknown");
            } else if (funct7 == 0x20) {
                if (funct3 == 0x0) snprintf(buf, buf_size, "sub %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x5) snprintf(buf, buf_size, "sra %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else snprintf(buf, buf_size, "unknown");
            } else if (funct7 == 0x01) { // RV32M
                if (funct3 == 0x0) snprintf(buf, buf_size, "mul %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x4) snprintf(buf, buf_size, "div %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x6) snprintf(buf, buf_size, "rem %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else snprintf(buf, buf_size, "unknown");
            } else snprintf(buf, buf_size, "unknown");
            break;
        }
        case 0x3B: { // R-type 64-bit
            if (funct7 == 0x00) {
                if (funct3 == 0x0) snprintf(buf, buf_size, "addw %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x1) snprintf(buf, buf_size, "sllw %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x5) snprintf(buf, buf_size, "srlw %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else snprintf(buf, buf_size, "unknown");
            } else if (funct7 == 0x20) {
                if (funct3 == 0x0) snprintf(buf, buf_size, "subw %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else if (funct3 == 0x5) snprintf(buf, buf_size, "sraw %s,%s,%s", reg_name[rd], reg_name[rs1], reg_name[rs2]);
                else snprintf(buf, buf_size, "unknown");
            } else snprintf(buf, buf_size, "unknown");
            break;
        }
        case 0x13: { // I-type
            int32_t imm = (int32_t)inst >> 20;
            if (funct3 == 0x0) snprintf(buf, buf_size, "addi %s,%s,%d", reg_name[rd], reg_name[rs1], imm);
            else if (funct3 == 0x4) snprintf(buf, buf_size, "xori %s,%s,%d", reg_name[rd], reg_name[rs1], imm);
            else if (funct3 == 0x6) snprintf(buf, buf_size, "ori %s,%s,%d", reg_name[rd], reg_name[rs1], imm);
            else if (funct3 == 0x7) snprintf(buf, buf_size, "andi %s,%s,%d", reg_name[rd], reg_name[rs1], imm);
            else if (funct3 == 0x1) snprintf(buf, buf_size, "slli %s,%s,%d", reg_name[rd], reg_name[rs1], (inst >> 20) & 0x3F);
            else if (funct3 == 0x5) {
                if ((inst >> 30) & 0x1) snprintf(buf, buf_size, "srai %s,%s,%d", reg_name[rd], reg_name[rs1], (inst >> 20) & 0x3F);
                else snprintf(buf, buf_size, "srli %s,%s,%d", reg_name[rd], reg_name[rs1], (inst >> 20) & 0x3F);
            }
            else if (funct3 == 0x2) snprintf(buf, buf_size, "slti %s,%s,%d", reg_name[rd], reg_name[rs1], imm);
            else if (funct3 == 0x3) snprintf(buf, buf_size, "sltiu %s,%s,%d", reg_name[rd], reg_name[rs1], imm);
            else snprintf(buf, buf_size, "unknown");
            break;
        }
        case 0x1B: { // I-type 64-bit
            int32_t imm = (int32_t)inst >> 20;
            if (funct3 == 0x0) snprintf(buf, buf_size, "addiw %s,%s,%d", reg_name[rd], reg_name[rs1], imm);
            else if (funct3 == 0x1) snprintf(buf, buf_size, "slliw %s,%s,%d", reg_name[rd], reg_name[rs1], (inst >> 20) & 0x1F);
            else if (funct3 == 0x5) {
                if ((inst >> 30) & 0x1) snprintf(buf, buf_size, "sraiw %s,%s,%d", reg_name[rd], reg_name[rs1], (inst >> 20) & 0x1F);
                else snprintf(buf, buf_size, "srliw %s,%s,%d", reg_name[rd], reg_name[rs1], (inst >> 20) & 0x1F);
            }
            else snprintf(buf, buf_size, "unknown");
            break;
        }
        case 0x03: { // Load
            int32_t imm = (int32_t)inst >> 20;
            if (funct3 == 0x0) snprintf(buf, buf_size, "lb %s,%d(%s)", reg_name[rd], imm, reg_name[rs1]);
            else if (funct3 == 0x1) snprintf(buf, buf_size, "lh %s,%d(%s)", reg_name[rd], imm, reg_name[rs1]);
            else if (funct3 == 0x2) snprintf(buf, buf_size, "lw %s,%d(%s)", reg_name[rd], imm, reg_name[rs1]);
            else if (funct3 == 0x3) snprintf(buf, buf_size, "ld %s,%d(%s)", reg_name[rd], imm, reg_name[rs1]);
            else if (funct3 == 0x4) snprintf(buf, buf_size, "lbu %s,%d(%s)", reg_name[rd], imm, reg_name[rs1]);
            else if (funct3 == 0x5) snprintf(buf, buf_size, "lhu %s,%d(%s)", reg_name[rd], imm, reg_name[rs1]);
            else if (funct3 == 0x6) snprintf(buf, buf_size, "lwu %s,%d(%s)", reg_name[rd], imm, reg_name[rs1]);
            else snprintf(buf, buf_size, "unknown");
            break;
        }
        case 0x23: { // Store
            int32_t imm = ((int32_t)(inst & 0xFE000000) >> 20) | ((inst >> 7) & 0x1F);
            if (funct3 == 0x0) snprintf(buf, buf_size, "sb %s,%d(%s)", reg_name[rs2], imm, reg_name[rs1]);
            else if (funct3 == 0x1) snprintf(buf, buf_size, "sh %s,%d(%s)", reg_name[rs2], imm, reg_name[rs1]);
            else if (funct3 == 0x2) snprintf(buf, buf_size, "sw %s,%d(%s)", reg_name[rs2], imm, reg_name[rs1]);
            else if (funct3 == 0x3) snprintf(buf, buf_size, "sd %s,%d(%s)", reg_name[rs2], imm, reg_name[rs1]);
            else snprintf(buf, buf_size, "unknown");
            break;
        }
        case 0x63: { // Branch
            int32_t imm = ((int32_t)(inst & 0x80000000) >> 19) | ((inst & 0x80) << 4) | 
                         ((inst >> 20) & 0x7E0) | ((inst >> 7) & 0x1E);
            if (funct3 == 0x0) snprintf(buf, buf_size, "beq %s,%s,%d", reg_name[rs1], reg_name[rs2], imm);
            else if (funct3 == 0x1) snprintf(buf, buf_size, "bne %s,%s,%d", reg_name[rs1], reg_name[rs2], imm);
            else if (funct3 == 0x4) snprintf(buf, buf_size, "blt %s,%s,%d", reg_name[rs1], reg_name[rs2], imm);
            else if (funct3 == 0x5) snprintf(buf, buf_size, "bge %s,%s,%d", reg_name[rs1], reg_name[rs2], imm);
            else if (funct3 == 0x6) snprintf(buf, buf_size, "bltu %s,%s,%d", reg_name[rs1], reg_name[rs2], imm);
            else if (funct3 == 0x7) snprintf(buf, buf_size, "bgeu %s,%s,%d", reg_name[rs1], reg_name[rs2], imm);
            else snprintf(buf, buf_size, "unknown");
            break;
        }
        case 0x37: { // LUI
            uint32_t imm = inst & 0xFFFFF000;
            snprintf(buf, buf_size, "lui %s,0x%x", reg_name[rd], imm >> 12);
            break;
        }
        case 0x17: { // AUIPC
            uint32_t imm = inst & 0xFFFFF000;
            snprintf(buf, buf_size, "auipc %s,0x%x", reg_name[rd], imm >> 12);
            break;
        }
        case 0x6F: { // JAL
            int32_t imm = ((int32_t)(inst & 0x80000000) >> 11) | (inst & 0xFF000) | 
                         ((inst >> 9) & 0x800) | ((inst >> 20) & 0x7FE);
            snprintf(buf, buf_size, "jal %s,%d", reg_name[rd], imm);
            break;
        }
        case 0x67: { // JALR
            int32_t imm = (int32_t)inst >> 20;
            snprintf(buf, buf_size, "jalr %s,%d(%s)", reg_name[rd], imm, reg_name[rs1]);
            break;
        }
        case 0x73: { // System
            if (inst == 0x00100073) snprintf(buf, buf_size, "ebreak");
            else if (inst == 0x00000073) snprintf(buf, buf_size, "ecall");
            else snprintf(buf, buf_size, "unknown");
            break;
        }
        default:
            snprintf(buf, buf_size, "unknown");
            break;
    }
}

// typedef struct cpu_state {
//     uint64_t pc;
//     uint32_t instruction;
//     uint64_t gpr[32];
// } cpu_state_t;

// cpu_state_t npc_cpu_state;

void record_mem_access();
// DPI-C functions
extern "C" {
    int load_image(const char *filename);
    void pmem_dump(uint32_t addr, int len);
}

void init_npc() {
    if (!npc_cpu) {
        npc_cpu = new TopModule;
    }
#if defined(NPC_STANDALONE) || defined(TRACE_NEMU)
    // In NEMU integration mode, do not print here
    if (!npc_trace) {
        npc_trace = new VerilatedVcdC;
        npc_cpu->trace(npc_trace, 99);
        // Close and reopen VCD to ensure clean state
        npc_trace->open("wave.vcd");
    } else {
        // If trace already exists, close and reopen it
        npc_trace->close();
        npc_trace->open("wave.vcd");
    }
    #endif
    
    // Reset sequence
    npc_cpu->clock = 0;
    npc_cpu->reset = 1;
    npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(TRACE_NEMU)
    if(trace_enabled)
    npc_trace->dump(0);
#endif
    
    npc_cpu->clock = 1;
    npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(TRACE_NEMU)
    if(trace_enabled)
    npc_trace->dump(1);
#endif
    
    npc_cpu->clock = 0;
    npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(TRACE_NEMU)
    if(trace_enabled)
    npc_trace->dump(2);
#endif
    
    npc_cpu->clock = 1;
    npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(TRACE_NEMU)
    if(trace_enabled)
    npc_trace->dump(3);
#endif
    
    // Release reset
    npc_cpu->clock = 0;
    npc_cpu->reset = 0;
    npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(TRACE_NEMU)
    if(trace_enabled)
    npc_trace->dump(4);
#endif

    // npc_cpu_state.pc = npc_cpu->io_pc;
    // npc_cpu_state.instruction = npc_cpu->io_instruction;
    // for (int i = 0; i < 32; i++) {
    //     npc_cpu_state.gpr[i] = get_npc_reg(i);
    // }
}

void single_run() {
    bool finished = false;
    last_pc = npc_cpu->io_pc;
    while(finished == false){
        // Clock low
        npc_cpu->clock = 0;
        npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(TRACE_NEMU)
        if(trace_enabled){
            npc_trace->dump(sim_time++);
        }
#endif
        
        // Clock high
        npc_cpu->clock = 1;
        npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(TRACE_NEMU)
        if(trace_enabled)
        npc_trace->dump(sim_time++);
#endif

        // Per-clock mepc change monitor
        if (mepc_monitor_enabled) {
            uint64_t cur_mepc = npc_cpu->rootp->CPU__DOT__CSRs_inst__DOT__mepc;
            if (cur_mepc != last_mepc) {
                uint64_t r_pc = npc_cpu->rootp->CPU__DOT__Priv_Exec_inst__DOT__r_pc;
                uint8_t r_trapEn = npc_cpu->rootp->CPU__DOT__Priv_Exec_inst__DOT__r_trapEn;
                uint8_t io_trap_en = npc_cpu->rootp->CPU__DOT__CSRs_inst__DOT__io_trap_en;
                uint8_t io_we = npc_cpu->rootp->CPU__DOT__CSRs_inst__DOT__io_we;
                uint8_t r_csrEn = npc_cpu->rootp->CPU__DOT__Priv_Exec_inst__DOT__r_csrEn;
                uint8_t io_allow = npc_cpu->rootp->CPU__DOT__Priv_Exec_inst__DOT__io_csr_allow;
                uint16_t r_addr = npc_cpu->rootp->CPU__DOT__Priv_Exec_inst__DOT__r_addr;
                uint64_t pc_current = npc_cpu->rootp->CPU__DOT__PC_Ctrl_inst__DOT__pc_current;
                uint64_t trap_epc = npc_cpu->rootp->CPU__DOT__Priv_Exec_inst__DOT__r_pc;
                uint8_t tick_memwb = npc_cpu->rootp->CPU__DOT__Metronome_inst__DOT__tick_memwb_reg;
                printf("[mepc-CLK] inst#%d pc=0x%lx last_pc=0x%lx: mepc 0x%lx -> 0x%lx\n",
                       inst_count, npc_cpu->io_pc, last_pc, last_mepc, cur_mepc);
                printf("  r_trapEn=%d io_trap_en=%d tick_memwb=%d io_we=%d io_allow=%d r_csrEn=%d r_addr=0x%x\n",
                       r_trapEn, io_trap_en, tick_memwb, io_we, io_allow, r_csrEn, r_addr);
                printf("  r_pc(trap_epc)=0x%lx pc_current=0x%lx\n", trap_epc, pc_current);
                last_mepc = cur_mepc;
            }
        }
        
        // Halt detection: if PC doesn't change for HALT_THRESHOLD cycles, assume halt
        if (npc_cpu->io_pc == last_halt_pc) {
            halt_count++;
            if (halt_count >= HALT_THRESHOLD) {
                printf("Program halted at PC=0x%016lx after %lu cycles\n", npc_cpu->io_pc, cycle_count);
                Verilated::gotFinish(true);
                break;
            }
        } else {
            last_halt_pc = npc_cpu->io_pc;
            halt_count = 0;
        }
        
        if (npc_cpu->io_pc != last_pc) {
            inst_count++;
            finished = true;
            #ifndef NPC_STANDALONE
            record_mem_access(); // Record memory access for this instruction
            #endif
        }
    }
    // npc_cpu_state.pc = npc_cpu->io_pc;
    // npc_cpu_state.instruction = npc_cpu->io_instruction;
    // for (int i = 0; i < 32; i++) {
    //     npc_cpu_state.gpr[i] = get_npc_reg(i);
    // }
    last_pc = npc_cpu->io_pc;

    #if defined(NPC_STANDALONE) || defined(TRACE_NEMU)
    if(trace_enabled)printf("Trace dumped up to cycle %lu\n", sim_time);
    #endif
}

#ifdef NPC_STANDALONE
void getvalue() {
    // Note: In NEMU integration mode, we don't print here
    // NEMU will handle the logging itself
    printf("[%8d] PC=0x%016lx INST=0x%08x\n", 
           inst_count, npc_cpu->io_pc, npc_cpu->io_instruction);
    for(int i = 0; i < 32; i++) {
        printf("reg[%2d]=0x%016lx ", i, get_npc_reg(i));
        if (i % 4 == 3) printf("\n");
    }
}
#else

#define MAX_MEM_ACCESS_STR_LEN 128

static char mem_access_str[MAX_MEM_ACCESS_STR_LEN][256]; // Circular buffer for memory access logs
static int mem_access_str_idx = 0;



void getvalue() {
    // record mem access info for NEMU's watchpoint checking
    
    
}


#endif

#ifndef NPC_STANDALONE
// NEMU integration functions

void record_mem_access() {
    if(npc_cpu->io_opcode_out == 0x03 || npc_cpu->io_opcode_out == 0x23) { // Load or Store
        const char* type = (npc_cpu->io_opcode_out == 0x03) ? "LOAD" : "STORE";
        uint64_t addr = npc_cpu->io_alu_result_out; // Address calculated by NPC
        uint64_t data = npc_cpu->io_mem_result_out; // Data read/written (for store, this is the data to be written)
        // funct3 bit2 is the "unsigned" flag for loads (lbu=4, lhu=5, lwu=6); mask it out to get width
        // 0→1B, 1→2B, 2→4B, 3→8B
        int len;
        switch (npc_cpu->io_func3_out & 0x3) {
            case 0: len = 1; break;
            case 1: len = 2; break;
            case 2: len = 4; break;
            default: len = 8; break; // ld / sd
        }
        uint64_t masked_data = (len == 8) ? data : (data & ((1ULL << (len * 8)) - 1));
        uint32_t ins = npc_cpu->io_instruction; // Current instruction for context
        
        // Disassemble instruction
        char disasm[128];
        disassemble(ins, disasm, sizeof(disasm));
        
        snprintf(mem_access_str[mem_access_str_idx], sizeof(mem_access_str[mem_access_str_idx]),
                 "ins:0x%08x (%s)\n%s: addr=0x%016lx data=0x%016lx(%ld) len=%d", 
                 ins, disasm, type, addr, masked_data, masked_data, len);
    }
    mem_access_str_idx = (mem_access_str_idx + 1) % 16;
}

void display_mem_access() {
    printf("Recent Memory Accesses:\n");
    for (int i = 0; i < 16; i++) {
        int idx = (mem_access_str_idx + i) % 16;
        if (mem_access_str[idx][0] != '\0') {
            printf("%s\n", mem_access_str[idx]);
        }
    }
}

void start_trace() {
    if (npc_trace && !trace_enabled) {
        // Close any existing trace and reopen to start a fresh VCD from current state
        npc_trace->close();
        npc_trace->open("clip_wave.vcd");
    }
    trace_enabled = true;
}

void stop_trace() {
    trace_enabled = false;
    // Properly close/flush the VCD so wave viewers (GTKWave etc.) can parse it
    if (npc_trace) {
        npc_trace->close();
    }
}


#endif

uint64_t get_npc_pc() {
    return npc_cpu ? npc_cpu->io_pc : 0;
}

uint32_t get_npc_inst() {
    return npc_cpu ? npc_cpu->io_instruction : 0;
}

uint64_t get_npc_reg(int idx) {
    if (!npc_cpu || idx < 0 || idx > 31) return 0;
    
    // Access registers through io_regs_debug_X ports (register file outputs)
    switch(idx) {
        case 0: return npc_cpu->io_regs_debug_0;
        case 1: return npc_cpu->io_regs_debug_1;
        case 2: return npc_cpu->io_regs_debug_2;
        case 3: return npc_cpu->io_regs_debug_3;
        case 4: return npc_cpu->io_regs_debug_4;
        case 5: return npc_cpu->io_regs_debug_5;
        case 6: return npc_cpu->io_regs_debug_6;
        case 7: return npc_cpu->io_regs_debug_7;
        case 8: return npc_cpu->io_regs_debug_8;
        case 9: return npc_cpu->io_regs_debug_9;
        case 10: return npc_cpu->io_regs_debug_10;
        case 11: return npc_cpu->io_regs_debug_11;
        case 12: return npc_cpu->io_regs_debug_12;
        case 13: return npc_cpu->io_regs_debug_13;
        case 14: return npc_cpu->io_regs_debug_14;
        case 15: return npc_cpu->io_regs_debug_15;
        case 16: return npc_cpu->io_regs_debug_16;
        case 17: return npc_cpu->io_regs_debug_17;
        case 18: return npc_cpu->io_regs_debug_18;
        case 19: return npc_cpu->io_regs_debug_19;    
        case 20: return npc_cpu->io_regs_debug_20;
        case 21: return npc_cpu->io_regs_debug_21;
        case 22: return npc_cpu->io_regs_debug_22;
        case 23: return npc_cpu->io_regs_debug_23;
        case 24: return npc_cpu->io_regs_debug_24;
        case 25: return npc_cpu->io_regs_debug_25;
        case 26: return npc_cpu->io_regs_debug_26;
        case 27: return npc_cpu->io_regs_debug_27;
        case 28: return npc_cpu->io_regs_debug_28;
        case 29: return npc_cpu->io_regs_debug_29;
        case 30: return npc_cpu->io_regs_debug_30;
        case 31: return npc_cpu->io_regs_debug_31;
        // Add more cases as needed for all 32 registers
        default: return 0;
    }
}

void cleanup_npc() {
#if defined(NPC_STANDALONE) || defined(TRACE_NEMU)
    if (npc_trace) {
        npc_trace->close();
        delete npc_trace;
        npc_trace = nullptr;
    }
#endif
    if (npc_cpu) {
        delete npc_cpu;
        npc_cpu = nullptr;
    }
}

bool is_npc_finished() {
#ifndef NPC_STANDALONE
    if (nemu_state.state == NEMU_ABORT || nemu_state.state == NEMU_END ||
        nemu_state.state == NEMU_QUIT) {
        return true;
    }
#endif
    return Verilated::gotFinish() || difftest_fail || sim_time >= MAX_SIM_TIME;
}

// C-compatible interface for NEMU
extern "C" {
    void npc_init() {
        Verilated::traceEverOn(true);
        init_npc();
#if defined(NPC_STANDALONE) 
        trace_enabled = true;  // auto-enable VCD in batch/NEMU mode
#endif
    }
    
    void npc_single_run() {
        single_run();
        cycle_count++;
    }
    
    void npc_getvalue() {
        getvalue();
    }
    
    uint64_t npc_get_pc() {
        return get_npc_pc();
    }
    
    uint32_t npc_get_inst() {
        return get_npc_inst();
    }
    
    uint64_t npc_get_reg(int idx) {
        return get_npc_reg(idx);
    }
    
    void npc_cleanup() {
        cleanup_npc();
    }
    
    int npc_is_finished() {
        return is_npc_finished() ? 1 : 0;
    }
    #ifndef NPC_STANDALONE

    void npc_record_mem_access() {
        record_mem_access();
    }

    void npc_display_mem_access() {
        display_mem_access();
    }

    void npc_start_trace() {
        start_trace();
    }

    void npc_stop_trace() {
        stop_trace();
    }


    #endif
}
