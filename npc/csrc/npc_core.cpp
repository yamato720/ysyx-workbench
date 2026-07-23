// NPC core functions for both standalone and NEMU integration
#include "npc_core.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <verilated.h>
#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
#include <verilated_vcd_c.h>
#endif
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
#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
VerilatedVcdC *npc_trace = nullptr;
#endif

// These names mirror the nested NpcCoreDebugBundle fields emitted by Verilator.
#define NPC_DEBUG_FRONTEND(field) (npc_cpu->io_debug_frontend_##field)
#define NPC_DEBUG_BACKEND(field) (npc_cpu->io_debug_backend_##field)
#define NPC_DEBUG_CORE(field) (npc_cpu->io_debug_##field)
#define NPC_DEBUG_MASTER(field) (npc_cpu->io_debug_master_##field)
#define NPC_DEBUG_REGISTER(index) (npc_cpu->io_debug_backend_registers_##index)
#define NPC_DEBUG_FREGISTER(index) (npc_cpu->io_debug_backend_floatingRegisters_##index)

// Simulation state
uint64_t sim_time = 5;
uint64_t cycle_count = 0; // Legacy host-side instruction-step counter.
bool difftest_fail = false;
uint64_t last_halt_pc = 0;
int halt_count = 0;
const int HALT_THRESHOLD = 100;
int inst_count = 0;
uint64_t committed_instruction_count = 0;
static uint64_t last_pc = 0;
static uint64_t last_commit_pc = 0;
static uint64_t last_commit_next_pc = 0;
static uint32_t last_commit_inst = 0;
#if defined(NPC_VCD_TRACE)
static unsigned int trace_file_sequence = 0;
#endif

enum NPCPipelineTimingClass {
    NPC_TIMING_NORMAL = 0,
    NPC_TIMING_LOAD,
    NPC_TIMING_STORE,
    NPC_TIMING_MULDIV,

    // Load width/sign variants.
    NPC_TIMING_LOAD_LB,
    NPC_TIMING_LOAD_LH,
    NPC_TIMING_LOAD_LW,
    NPC_TIMING_LOAD_LD,
    NPC_TIMING_LOAD_LBU,
    NPC_TIMING_LOAD_LHU,
    NPC_TIMING_LOAD_LWU,

    // Store width variants.
    NPC_TIMING_STORE_SB,
    NPC_TIMING_STORE_SH,
    NPC_TIMING_STORE_SW,
    NPC_TIMING_STORE_SD,

    // RV32M plus RV64M word-operation variants.
    NPC_TIMING_M_MUL,
    NPC_TIMING_M_MULH,
    NPC_TIMING_M_MULHSU,
    NPC_TIMING_M_MULHU,
    NPC_TIMING_M_DIV,
    NPC_TIMING_M_DIVU,
    NPC_TIMING_M_REM,
    NPC_TIMING_M_REMU,
    NPC_TIMING_M_MULW,
    NPC_TIMING_M_DIVW,
    NPC_TIMING_M_DIVUW,
    NPC_TIMING_M_REMW,
    NPC_TIMING_M_REMUW,

    NPC_TIMING_ALL,
    NPC_TIMING_CLASS_COUNT,
};

enum NPCPipelineTimingStage {
    NPC_TIMING_IF = 0,
    NPC_TIMING_ID,
    NPC_TIMING_EX,
    NPC_TIMING_MEM,
    NPC_TIMING_WB,
    NPC_TIMING_STAGE_COUNT,
};

enum NPCPipelineStallCounter {
    NPC_PIPELINE_STALL_FETCH_AXI = 0,
    NPC_PIPELINE_STALL_ID,
    NPC_PIPELINE_STALL_EXECUTE,
    NPC_PIPELINE_STALL_MEMORY,
    NPC_PIPELINE_STALL_REDIRECT,
    NPC_PIPELINE_STALL_COUNTER_COUNT,
};

struct NPCPipelineTiming {
    uint64_t stage[NPC_TIMING_STAGE_COUNT] = {};
};

struct NPCPipelineTimingAggregate {
    uint64_t sample_count = 0;
    uint64_t total_stage_cycles[NPC_TIMING_STAGE_COUNT] = {};
    uint64_t max_total_cycles = 0;
};

struct NPCPipelineTimingSample {
    uint64_t pc = 0;
    uint32_t instruction = 0;
    NPCPipelineTiming timing = {};
    bool valid = false;
};

static NPCPipelineTiming last_commit_timing = {};
static uint32_t last_commit_timing_class = NPC_TIMING_NORMAL;
static NPCPipelineTimingAggregate pipeline_timing_aggregates[NPC_TIMING_CLASS_COUNT] = {};
static NPCPipelineTimingSample pipeline_timing_latest_samples[NPC_TIMING_CLASS_COUNT] = {};

static uint64_t pipeline_timing_total(const NPCPipelineTiming &timing) {
    uint64_t total = 0;
    for (int stage = 0; stage < NPC_TIMING_STAGE_COUNT; stage++) {
        total += timing.stage[stage];
    }
    return total;
}

static uint32_t classify_pipeline_timing(uint32_t instruction) {
    const uint32_t opcode = instruction & 0x7f;
    const uint32_t funct3 = (instruction >> 12) & 0x7;
    const uint32_t funct7 = (instruction >> 25) & 0x7f;

    if (opcode == 0x03) {
        switch (funct3) {
            case 0x0: return NPC_TIMING_LOAD_LB;
            case 0x1: return NPC_TIMING_LOAD_LH;
            case 0x2: return NPC_TIMING_LOAD_LW;
            case 0x3: return NPC_TIMING_LOAD_LD;
            case 0x4: return NPC_TIMING_LOAD_LBU;
            case 0x5: return NPC_TIMING_LOAD_LHU;
            case 0x6: return NPC_TIMING_LOAD_LWU;
            default: return NPC_TIMING_LOAD;
        }
    }
    if (opcode == 0x23) {
        switch (funct3) {
            case 0x0: return NPC_TIMING_STORE_SB;
            case 0x1: return NPC_TIMING_STORE_SH;
            case 0x2: return NPC_TIMING_STORE_SW;
            case 0x3: return NPC_TIMING_STORE_SD;
            default: return NPC_TIMING_STORE;
        }
    }
    if ((opcode == 0x33 || opcode == 0x3b) && funct7 == 0x01) {
        if (opcode == 0x3b) {
            switch (funct3) {
                case 0x0: return NPC_TIMING_M_MULW;
                case 0x4: return NPC_TIMING_M_DIVW;
                case 0x5: return NPC_TIMING_M_DIVUW;
                case 0x6: return NPC_TIMING_M_REMW;
                case 0x7: return NPC_TIMING_M_REMUW;
                default: return NPC_TIMING_MULDIV;
            }
        }
        switch (funct3) {
            case 0x0: return NPC_TIMING_M_MUL;
            case 0x1: return NPC_TIMING_M_MULH;
            case 0x2: return NPC_TIMING_M_MULHSU;
            case 0x3: return NPC_TIMING_M_MULHU;
            case 0x4: return NPC_TIMING_M_DIV;
            case 0x5: return NPC_TIMING_M_DIVU;
            case 0x6: return NPC_TIMING_M_REM;
            case 0x7: return NPC_TIMING_M_REMU;
            default: return NPC_TIMING_MULDIV;
        }
    }
    return NPC_TIMING_NORMAL;
}

static uint32_t summarize_pipeline_timing_class(uint32_t timing_class) {
    if (timing_class >= NPC_TIMING_LOAD_LB && timing_class <= NPC_TIMING_LOAD_LWU) {
        return NPC_TIMING_LOAD;
    }
    if (timing_class >= NPC_TIMING_STORE_SB && timing_class <= NPC_TIMING_STORE_SD) {
        return NPC_TIMING_STORE;
    }
    if (timing_class >= NPC_TIMING_M_MUL && timing_class <= NPC_TIMING_M_REMUW) {
        return NPC_TIMING_MULDIV;
    }
    return timing_class;
}

static void update_pipeline_timing_aggregate(
    uint32_t timing_class,
    uint64_t pc,
    uint32_t instruction,
    const NPCPipelineTiming &timing
) {
    NPCPipelineTimingAggregate &aggregate = pipeline_timing_aggregates[timing_class];
    const uint64_t total = pipeline_timing_total(timing);
    aggregate.sample_count++;
    for (int stage = 0; stage < NPC_TIMING_STAGE_COUNT; stage++) {
        aggregate.total_stage_cycles[stage] += timing.stage[stage];
    }
    if (total > aggregate.max_total_cycles) {
        aggregate.max_total_cycles = total;
    }

    NPCPipelineTimingSample &latest = pipeline_timing_latest_samples[timing_class];
    latest.pc = pc;
    latest.instruction = instruction;
    latest.timing = timing;
    latest.valid = true;
}

static void record_committed_pipeline_timing(uint64_t pc, uint32_t instruction) {
    last_commit_timing.stage[NPC_TIMING_IF] = NPC_DEBUG_BACKEND(commitFetchCycles);
    last_commit_timing.stage[NPC_TIMING_ID] = NPC_DEBUG_BACKEND(commitDecodeCycles);
    last_commit_timing.stage[NPC_TIMING_EX] = NPC_DEBUG_BACKEND(commitExecuteCycles);
    last_commit_timing.stage[NPC_TIMING_MEM] = NPC_DEBUG_BACKEND(commitMemoryCycles);
    last_commit_timing.stage[NPC_TIMING_WB] = NPC_DEBUG_BACKEND(commitWritebackCycles);
    last_commit_timing_class = classify_pipeline_timing(instruction);
    const uint32_t summary_class = summarize_pipeline_timing_class(last_commit_timing_class);

    update_pipeline_timing_aggregate(summary_class, pc, instruction, last_commit_timing);
    if (last_commit_timing_class != summary_class) {
        update_pipeline_timing_aggregate(last_commit_timing_class, pc, instruction, last_commit_timing);
    }
    update_pipeline_timing_aggregate(NPC_TIMING_ALL, pc, instruction, last_commit_timing);
}

#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
bool trace_enabled = false;
#endif

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
    cycle_count = 0;
    inst_count = 0;
    committed_instruction_count = 0;
    last_pc = 0;
    last_commit_pc = 0;
    last_commit_next_pc = 0;
    last_commit_inst = 0;
    last_commit_timing = {};
    last_commit_timing_class = NPC_TIMING_NORMAL;
    std::memset(pipeline_timing_aggregates, 0, sizeof(pipeline_timing_aggregates));
    std::memset(pipeline_timing_latest_samples, 0, sizeof(pipeline_timing_latest_samples));
#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
    // In NEMU integration mode, do not print here
    if (!npc_trace) {
        npc_trace = new VerilatedVcdC;
        npc_cpu->trace(npc_trace, 99);
    }
#ifdef NPC_STANDALONE
    if (!npc_trace->isOpen()) {
        npc_trace->open("wave.vcd");
    }
#endif
#endif
    
    // The standalone core settles with two reset clocks.  The SoC keeps the
    // CPU behind a 10-stage reset synchronizer, so it needs a longer reset
    // assertion before the first AXI request is allowed onto the fabric.
#ifdef NPC_SOC
    constexpr int reset_cycles = 12;
#else
    constexpr int reset_cycles = 2;
#endif
    npc_cpu->reset = 1;
    for (int cycle = 0; cycle < reset_cycles; cycle++) {
        npc_cpu->clock = 0;
        npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
        if (trace_enabled) npc_trace->dump(sim_time++);
#endif

        npc_cpu->clock = 1;
        npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
        if (trace_enabled) npc_trace->dump(sim_time++);
#endif
    }
    
    // Release reset
    npc_cpu->clock = 0;
    npc_cpu->reset = 0;
    npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
    if(trace_enabled)
    npc_trace->dump(sim_time++);
#endif

    // npc_cpu_state.pc = NPC_DEBUG_FRONTEND(currentPc);
    // npc_cpu_state.instruction = NPC_DEBUG_FRONTEND(frontendInstruction);
    // for (int i = 0; i < 32; i++) {
    //     npc_cpu_state.gpr[i] = get_npc_reg(i);
    // }
}

// This is deliberately the only place that advances the Verilated clock.  It
// keeps commit observation and profiling identical for instruction-level
// callers and for NEMU's cycle-level deadlock watchdog.
static bool run_one_cycle() {
    npc_cpu->clock = 0;
    npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
    if (trace_enabled) {
        npc_trace->dump(sim_time++);
    }
#endif

    npc_cpu->clock = 1;
    npc_cpu->eval();
#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
    if (trace_enabled) {
        npc_trace->dump(sim_time++);
    }
#endif

    if (mepc_monitor_enabled) {
        uint64_t cur_mepc = NPC_DEBUG_BACKEND(mepc);
        if (cur_mepc != last_mepc) {
            uint8_t execute_trap_enabled = NPC_DEBUG_BACKEND(executeTrapEnable);
            uint8_t commit_trap_enabled = NPC_DEBUG_BACKEND(commitTrapEnable);
            uint8_t execute_csr_enabled = NPC_DEBUG_BACKEND(executeCsrEnable);
            uint8_t csr_access_allowed = NPC_DEBUG_BACKEND(commitCsrAllow);
            uint16_t csr_address = NPC_DEBUG_BACKEND(commitCsrAddress);
            uint64_t pc_current = NPC_DEBUG_FRONTEND(currentPc);
            uint64_t trap_exception_pc = NPC_DEBUG_BACKEND(commitTrapEpc);
            uint8_t commit_valid = NPC_DEBUG_BACKEND(commitValid);
            printf("[mepc-CLK] inst#%d pc=0x%lx last_pc=0x%lx: mepc 0x%lx -> 0x%lx\n",
                   inst_count, (unsigned long)NPC_DEBUG_FRONTEND(currentPc), last_pc, last_mepc, cur_mepc);
            printf("  execute_trap_enabled=%d commit_trap_enabled=%d commit_valid=%d csr_access_allowed=%d execute_csr_enabled=%d csr_address=0x%x\n",
                   execute_trap_enabled, commit_trap_enabled, commit_valid, csr_access_allowed,
                   execute_csr_enabled, csr_address);
            printf("  trap_exception_pc=0x%lx pc_current=0x%lx\n", trap_exception_pc, pc_current);
            last_mepc = cur_mepc;
        }
    }

    const bool committed = NPC_DEBUG_BACKEND(commitValid);
    if (committed) {
        last_commit_pc = NPC_DEBUG_BACKEND(commitPc);
        last_commit_next_pc = NPC_DEBUG_BACKEND(commitNextPc);
        last_commit_inst = NPC_DEBUG_BACKEND(commitInstruction);
        record_committed_pipeline_timing(last_commit_pc, last_commit_inst);
        inst_count++;
        committed_instruction_count++;
#ifndef NPC_STANDALONE
        record_mem_access();
#endif
    }

    // Halt detection remains independent of the watchdog: a genuinely idle
    // core has no backpressure reasons and must not be diagnosed as deadlock.
    if (!committed && !NPC_DEBUG_CORE(coreBusy) && NPC_DEBUG_FRONTEND(currentPc) == last_halt_pc) {
        halt_count++;
        if (halt_count >= HALT_THRESHOLD) {
            printf("Program halted at PC=0x%016lx after %lu cycles\n",
                   (unsigned long)NPC_DEBUG_FRONTEND(currentPc), cycle_count);
            Verilated::gotFinish(true);
        }
    } else {
        last_halt_pc = NPC_DEBUG_FRONTEND(currentPc);
        halt_count = 0;
    }
    last_pc = NPC_DEBUG_FRONTEND(currentPc);
    return committed;
}

void single_run() {
    bool finished = false;
#ifdef NPC_SOC
    // Preserve the standalone SoC early-warning message. NEMU itself now owns
    // the authoritative five-second deadlock timeout.
    int soc_wait_cycles = 0;
    bool soc_stall_reported = false;
#endif
    last_pc = NPC_DEBUG_FRONTEND(currentPc);
    while (!finished && !Verilated::gotFinish()) {
        finished = run_one_cycle();

#ifdef NPC_SOC
        soc_wait_cycles++;
        if (!finished && !soc_stall_reported && soc_wait_cycles == 1024) {
            fprintf(stderr,
                    "[NPC-SoC] no commit after %d cycles: pc=0x%08lx inst=0x%08x busy=%u "
                    "AR(v=%u r=%u addr=0x%08x) R(v=%u r=%u data=0x%08x)\n",
                    soc_wait_cycles,
                    (unsigned long)NPC_DEBUG_FRONTEND(currentPc),
                    NPC_DEBUG_FRONTEND(frontendInstruction),
                    NPC_DEBUG_CORE(coreBusy),
                    NPC_DEBUG_MASTER(arValid),
                    NPC_DEBUG_MASTER(arReady),
                    NPC_DEBUG_MASTER(arAddress),
                    NPC_DEBUG_MASTER(rValid),
                    NPC_DEBUG_MASTER(rReady),
                    NPC_DEBUG_MASTER(rData));
            soc_stall_reported = true;
        }
#endif
    }
    // npc_cpu_state.pc = NPC_DEBUG_FRONTEND(currentPc);
    // npc_cpu_state.instruction = NPC_DEBUG_FRONTEND(frontendInstruction);
    // for (int i = 0; i < 32; i++) {
    //     npc_cpu_state.gpr[i] = get_npc_reg(i);
    // }
    last_pc = NPC_DEBUG_FRONTEND(currentPc);

    #if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
    if(trace_enabled)printf("Trace dumped up to cycle %lu\n", sim_time);
    #endif
}

#ifdef NPC_STANDALONE
void getvalue() {
    // Note: In NEMU integration mode, we don't print here
    // NEMU will handle the logging itself
    printf("[%8d] PC=0x%016lx INST=0x%08x\n",
           inst_count, (unsigned long)NPC_DEBUG_FRONTEND(currentPc), NPC_DEBUG_FRONTEND(frontendInstruction));
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
    uint32_t ins = last_commit_inst; // Committed instruction for context
    uint32_t opcode = ins & 0x7f;
    uint32_t funct3 = (ins >> 12) & 0x7;

    if(opcode == 0x03 || opcode == 0x23) { // Load or Store
        const char* type = (opcode == 0x03) ? "LOAD" : "STORE";
        uint64_t addr = NPC_DEBUG_BACKEND(executeAluResult); // Address calculated by NPC
        uint64_t data = NPC_DEBUG_BACKEND(memoryResult); // Data read/written (for store, this is the data to be written)
        // funct3 bit2 is the "unsigned" flag for loads (lbu=4, lhu=5, lwu=6); mask it out to get width
        // 0→1B, 1→2B, 2→4B, 3→8B
        int len;
        switch (funct3 & 0x3) {
            case 0: len = 1; break;
            case 1: len = 2; break;
            case 2: len = 4; break;
            default: len = 8; break; // ld / sd
        }
        uint64_t masked_data = (len == 8) ? data : (data & ((1ULL << (len * 8)) - 1));

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

#if defined(NPC_VCD_TRACE)
void start_trace() {
    if (npc_trace && !trace_enabled) {
        // Close any existing trace and reopen to start a fresh VCD from current state
        npc_trace->close();
        const char *runtime_directory = std::getenv("NEMU_RUNTIME_OUTPUT_DIR");
        char trace_path[4096];
        trace_file_sequence++;
        if (runtime_directory != nullptr && runtime_directory[0] != '\0') {
            std::snprintf(trace_path, sizeof(trace_path), "%s/wave-%03u.vcd",
                          runtime_directory, trace_file_sequence);
        } else {
            std::snprintf(trace_path, sizeof(trace_path), "wave-%03u.vcd", trace_file_sequence);
        }
        npc_trace->open(trace_path);
        std::printf("NPC VCD：%s\n", trace_path);
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


#endif

uint64_t get_npc_pc() {
    return npc_cpu ? last_commit_next_pc : 0;
}

uint32_t get_npc_inst() {
    return npc_cpu ? last_commit_inst : 0;
}

uint64_t get_npc_current_pc() {
    return npc_cpu ? NPC_DEBUG_FRONTEND(currentPc) : 0;
}

uint32_t get_npc_frontend_instruction() {
    return npc_cpu ? NPC_DEBUG_FRONTEND(frontendInstruction) : 0;
}

uint64_t get_npc_reg(int idx) {
    if (!npc_cpu || idx < 0 || idx > 31) return 0;
    
    // Access the backend portion of the nested debug bundle.
    switch(idx) {
        case 0: return NPC_DEBUG_REGISTER(0);
        case 1: return NPC_DEBUG_REGISTER(1);
        case 2: return NPC_DEBUG_REGISTER(2);
        case 3: return NPC_DEBUG_REGISTER(3);
        case 4: return NPC_DEBUG_REGISTER(4);
        case 5: return NPC_DEBUG_REGISTER(5);
        case 6: return NPC_DEBUG_REGISTER(6);
        case 7: return NPC_DEBUG_REGISTER(7);
        case 8: return NPC_DEBUG_REGISTER(8);
        case 9: return NPC_DEBUG_REGISTER(9);
        case 10: return NPC_DEBUG_REGISTER(10);
        case 11: return NPC_DEBUG_REGISTER(11);
        case 12: return NPC_DEBUG_REGISTER(12);
        case 13: return NPC_DEBUG_REGISTER(13);
        case 14: return NPC_DEBUG_REGISTER(14);
        case 15: return NPC_DEBUG_REGISTER(15);
        case 16: return NPC_DEBUG_REGISTER(16);
        case 17: return NPC_DEBUG_REGISTER(17);
        case 18: return NPC_DEBUG_REGISTER(18);
        case 19: return NPC_DEBUG_REGISTER(19);
        case 20: return NPC_DEBUG_REGISTER(20);
        case 21: return NPC_DEBUG_REGISTER(21);
        case 22: return NPC_DEBUG_REGISTER(22);
        case 23: return NPC_DEBUG_REGISTER(23);
        case 24: return NPC_DEBUG_REGISTER(24);
        case 25: return NPC_DEBUG_REGISTER(25);
        case 26: return NPC_DEBUG_REGISTER(26);
        case 27: return NPC_DEBUG_REGISTER(27);
        case 28: return NPC_DEBUG_REGISTER(28);
        case 29: return NPC_DEBUG_REGISTER(29);
        case 30: return NPC_DEBUG_REGISTER(30);
        case 31: return NPC_DEBUG_REGISTER(31);
        default: return 0;
    }
}

uint64_t get_npc_freg(int idx) {
    if (!npc_cpu || idx < 0 || idx > 31) return 0;
    switch(idx) {
        case 0: return NPC_DEBUG_FREGISTER(0); case 1: return NPC_DEBUG_FREGISTER(1);
        case 2: return NPC_DEBUG_FREGISTER(2); case 3: return NPC_DEBUG_FREGISTER(3);
        case 4: return NPC_DEBUG_FREGISTER(4); case 5: return NPC_DEBUG_FREGISTER(5);
        case 6: return NPC_DEBUG_FREGISTER(6); case 7: return NPC_DEBUG_FREGISTER(7);
        case 8: return NPC_DEBUG_FREGISTER(8); case 9: return NPC_DEBUG_FREGISTER(9);
        case 10: return NPC_DEBUG_FREGISTER(10); case 11: return NPC_DEBUG_FREGISTER(11);
        case 12: return NPC_DEBUG_FREGISTER(12); case 13: return NPC_DEBUG_FREGISTER(13);
        case 14: return NPC_DEBUG_FREGISTER(14); case 15: return NPC_DEBUG_FREGISTER(15);
        case 16: return NPC_DEBUG_FREGISTER(16); case 17: return NPC_DEBUG_FREGISTER(17);
        case 18: return NPC_DEBUG_FREGISTER(18); case 19: return NPC_DEBUG_FREGISTER(19);
        case 20: return NPC_DEBUG_FREGISTER(20); case 21: return NPC_DEBUG_FREGISTER(21);
        case 22: return NPC_DEBUG_FREGISTER(22); case 23: return NPC_DEBUG_FREGISTER(23);
        case 24: return NPC_DEBUG_FREGISTER(24); case 25: return NPC_DEBUG_FREGISTER(25);
        case 26: return NPC_DEBUG_FREGISTER(26); case 27: return NPC_DEBUG_FREGISTER(27);
        case 28: return NPC_DEBUG_FREGISTER(28); case 29: return NPC_DEBUG_FREGISTER(29);
        case 30: return NPC_DEBUG_FREGISTER(30); case 31: return NPC_DEBUG_FREGISTER(31);
        default: return 0;
    }
}

uint32_t get_npc_fcsr() {
    return npc_cpu ? NPC_DEBUG_BACKEND(fcsr) : 0;
}

uint64_t get_npc_mstatus() {
    return npc_cpu ? NPC_DEBUG_BACKEND(mstatus) : 0;
}

uint64_t get_npc_cycle_count() {
    // The hardware counter starts after reset deassertion and counts every
    // core clock. Do not use cycle_count here: it only counts NEMU calls.
    return npc_cpu ? NPC_DEBUG_BACKEND(cycleCount) : 0;
}

uint64_t get_npc_commit_count() {
    return committed_instruction_count;
}

uint32_t get_npc_backpressure_reasons() {
    return npc_cpu ? NPC_DEBUG_CORE(backpressureReasons) : 0;
}

uint32_t get_npc_pipeline_features() {
    return npc_cpu ? NPC_DEBUG_BACKEND(pipelineFeatures) : 0;
}

uint64_t get_npc_pipeline_stall_count(uint32_t counter) {
    if (!npc_cpu) return 0;
    switch (counter) {
        case NPC_PIPELINE_STALL_FETCH_AXI: return NPC_DEBUG_FRONTEND(fetchAxiWaitCycles);
        case NPC_PIPELINE_STALL_ID: return NPC_DEBUG_BACKEND(idStallCycles);
        case NPC_PIPELINE_STALL_EXECUTE: return NPC_DEBUG_BACKEND(executeStallCycles);
        case NPC_PIPELINE_STALL_MEMORY: return NPC_DEBUG_BACKEND(memoryStallCycles);
        case NPC_PIPELINE_STALL_REDIRECT: return NPC_DEBUG_FRONTEND(redirectFlushCount);
        default: return 0;
    }
}

uint64_t get_npc_timing_sample_count(uint32_t timing_class) {
    if (timing_class >= NPC_TIMING_CLASS_COUNT) return 0;
    return pipeline_timing_aggregates[timing_class].sample_count;
}

uint64_t get_npc_timing_total_cycles(uint32_t timing_class, uint32_t stage) {
    if (timing_class >= NPC_TIMING_CLASS_COUNT || stage >= NPC_TIMING_STAGE_COUNT) return 0;
    return pipeline_timing_aggregates[timing_class].total_stage_cycles[stage];
}

uint64_t get_npc_timing_max_total_cycles(uint32_t timing_class) {
    if (timing_class >= NPC_TIMING_CLASS_COUNT) return 0;
    return pipeline_timing_aggregates[timing_class].max_total_cycles;
}

uint64_t get_npc_timing_last_pc(uint32_t timing_class) {
    if (timing_class >= NPC_TIMING_CLASS_COUNT) return 0;
    return pipeline_timing_latest_samples[timing_class].pc;
}

uint32_t get_npc_timing_last_instruction(uint32_t timing_class) {
    if (timing_class >= NPC_TIMING_CLASS_COUNT) return 0;
    return pipeline_timing_latest_samples[timing_class].instruction;
}

uint64_t get_npc_timing_last_stage_cycles(uint32_t timing_class, uint32_t stage) {
    if (timing_class >= NPC_TIMING_CLASS_COUNT || stage >= NPC_TIMING_STAGE_COUNT) return 0;
    return pipeline_timing_latest_samples[timing_class].timing.stage[stage];
}

uint32_t get_npc_last_timing_class() {
    return last_commit_timing_class;
}

uint64_t get_npc_last_timing_stage_cycles(uint32_t stage) {
    if (stage >= NPC_TIMING_STAGE_COUNT) return 0;
    return last_commit_timing.stage[stage];
}

uint64_t get_npc_last_timing_total_cycles() {
    return pipeline_timing_total(last_commit_timing);
}

void cleanup_npc() {
#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
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
#if defined(NPC_STANDALONE) || defined(NPC_VCD_TRACE)
        Verilated::traceEverOn(true);
#endif
        init_npc();
#if defined(NPC_STANDALONE) 
        trace_enabled = true;  // auto-enable VCD in batch/NEMU mode
#endif
    }
    
    void npc_single_run() {
        single_run();
        cycle_count++;
    }

    int npc_step_cycle() {
        return run_one_cycle() ? 1 : 0;
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

    uint64_t npc_get_current_pc() {
        return get_npc_current_pc();
    }

    uint32_t npc_get_frontend_instruction() {
        return get_npc_frontend_instruction();
    }
    
    uint64_t npc_get_reg(int idx) {
        return get_npc_reg(idx);
    }

    uint64_t npc_get_freg(int idx) {
        return get_npc_freg(idx);
    }

    uint32_t npc_get_fcsr() {
        return get_npc_fcsr();
    }

    uint64_t npc_get_mstatus() {
        return get_npc_mstatus();
    }

    uint64_t npc_get_cycle_count() {
        return get_npc_cycle_count();
    }

    uint64_t npc_get_commit_count() {
        return get_npc_commit_count();
    }

    uint32_t npc_get_backpressure_reasons() {
        return get_npc_backpressure_reasons();
    }

    uint32_t npc_get_pipeline_features() {
        return get_npc_pipeline_features();
    }

    uint64_t npc_get_pipeline_stall_count(uint32_t counter) {
        return get_npc_pipeline_stall_count(counter);
    }

    uint64_t npc_get_timing_sample_count(uint32_t timing_class) {
        return get_npc_timing_sample_count(timing_class);
    }

    uint64_t npc_get_timing_total_cycles(uint32_t timing_class, uint32_t stage) {
        return get_npc_timing_total_cycles(timing_class, stage);
    }

    uint64_t npc_get_timing_max_total_cycles(uint32_t timing_class) {
        return get_npc_timing_max_total_cycles(timing_class);
    }

    uint64_t npc_get_timing_last_pc(uint32_t timing_class) {
        return get_npc_timing_last_pc(timing_class);
    }

    uint32_t npc_get_timing_last_instruction(uint32_t timing_class) {
        return get_npc_timing_last_instruction(timing_class);
    }

    uint64_t npc_get_timing_last_stage_cycles(uint32_t timing_class, uint32_t stage) {
        return get_npc_timing_last_stage_cycles(timing_class, stage);
    }

    uint32_t npc_get_last_timing_class() {
        return get_npc_last_timing_class();
    }

    uint64_t npc_get_last_timing_stage_cycles(uint32_t stage) {
        return get_npc_last_timing_stage_cycles(stage);
    }

    uint64_t npc_get_last_timing_total_cycles() {
        return get_npc_last_timing_total_cycles();
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

#if defined(NPC_VCD_TRACE)
    void npc_start_trace() {
        start_trace();
    }

    void npc_stop_trace() {
        stop_trace();
    }
#endif


    #endif
}
