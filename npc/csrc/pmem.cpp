// Physical memory simulation with DPI-C interface
// Dual-port byte-addressable memory for Chisel CPU
// When integrated with NEMU, this uses NEMU's pmem directly
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <verilated.h>
#include "svdpi.h"

#ifdef NPC_STANDALONE
// Standalone NPC mode: use own memory
#define PMEM_SIZE (128 * 1024 * 1024)  // 128MB
#define PMEM_BASE 0x80000000

static uint8_t pmem[PMEM_SIZE] = {};

// Guest to host address translation
static inline uint8_t* guest_to_host_impl(uint32_t paddr) {
    return pmem + (paddr - PMEM_BASE);
}

// Host to guest address translation
static inline uint32_t host_to_guest_impl(uint8_t *haddr) {
    return (uint32_t)(haddr - pmem) + PMEM_BASE;
}
#else
// NEMU integration mode: use NEMU's memory interface
extern "C" {
    uint8_t* guest_to_host(uint64_t paddr);
    uint64_t host_to_guest(uint8_t *haddr);
    uint64_t mmio_read(uint64_t addr, int len);
    void mmio_write(uint64_t addr, int len, uint64_t data);
    void assert_fail_msg();
}

// Wrapper to handle 32-bit address from Verilog
static inline uint8_t* guest_to_host_impl(uint32_t paddr) {
    uint8_t* result = guest_to_host((uint64_t)paddr);
    if (result == nullptr) {
        fprintf(stderr, "[NPC-PMEM] ERROR: guest_to_host returned NULL for paddr=0x%08x\n", paddr);
        fflush(stderr);
    }
    return result;
}

static inline uint32_t host_to_guest_impl(uint8_t *haddr) {
    return (uint32_t)host_to_guest(haddr);
}

static inline bool is_pmem_addr(uint32_t addr) {
    return addr >= 0x80000000u && addr < 0x88000000u;
}

static inline bool is_mmio_addr(uint32_t addr) {
    return addr >= 0xa0000000u && addr < 0xa2000000u;
}
#endif

// DPI-C functions for Verilog/Chisel to call
// Note: DPI-C "byte" type maps to C "char" (signed 8-bit)
//       DPI-C "int" type maps to C "int" (32-bit)
extern "C" {


void mmio_read_impl(uint32_t addr, int len, uint64_t *result) {
    if (is_mmio_addr(addr)) {
        *result = mmio_read(addr, len);
        return;
    }
    *result = 0;
    assert_fail_msg();  // Invalid MMIO address
}

void mmio_write_impl(uint32_t addr, int len, uint64_t data) {
    if (is_mmio_addr(addr)) {
        mmio_write(addr, len, data);
        return;
    }
    assert_fail_msg();  // Invalid MMIO address
    return;
}

// ============== Port A: Byte Read/Write ==============

// Read 1 byte from port A
// Verilog signature: import "DPI-C" function byte pmem_read_a(input int addr);
char pmem_read_a(int addr) {
    uint32_t uaddr = (uint32_t)addr;
    // if(uaddr == 0) {
    //     // printf("ERROR: pmem_read_a called with addr=0\n");
    //     return 0;  // Ignore reads from address 0
    // }
#ifdef NPC_STANDALONE
    if (uaddr < PMEM_BASE || uaddr >= PMEM_BASE + PMEM_SIZE) {
        // printf("ERROR: pmem_read_a out of bounds: 0x%08x\n", uaddr);
        return 0;
    }
    return (char)*guest_to_host_impl(uaddr);
#else
    // printf("read A from address 0x%08x\n", uaddr);
    // // In NEMU integration mode, check if address is valid
    // if (uaddr < 0x80000000 || uaddr >= 0x88000000) {
    //     // Invalid address during NPC initialization, return NOP instruction byte
    //     return 0x13;  // Part of RISC-V NOP (addi x0, x0, 0 = 0x00000013)
    // }
    if (is_pmem_addr(uaddr)) {
        uint8_t *p = guest_to_host_impl(uaddr);
        if (p == nullptr) return 0;
        return (char)*p;
    } 
    assert_fail_msg();  // Out of bounds access
    // assert(0 && "pmem_read_a out of bounds");
    return 0;

#endif
}

// Write 1 byte to port A
// Verilog signature: import "DPI-C" function void pmem_write_a(input int addr, input byte data);
void pmem_write_a(int addr, char data) {
    uint32_t uaddr = (uint32_t)addr;
    // if(uaddr == 0) {
    //     // printf("ERROR: pmem_write_a called with addr=0\n");
    //     return;  // Ignore writes to address 0
    // }
#ifdef NPC_STANDALONE
    if (uaddr < PMEM_BASE || uaddr >= PMEM_BASE + PMEM_SIZE) {
        // printf("ERROR: pmem_write_a out of bounds: 0x%08x\n", uaddr);
        return;
    }
    *guest_to_host_impl(uaddr) = (uint8_t)data;
#else
    if (is_pmem_addr(uaddr)) {
        *guest_to_host_impl(uaddr) = (uint8_t)data;
        return;
    }
    assert_fail_msg();  // Out of bounds access
#endif
}

// ============== Port B: Byte Read/Write ==============

// Read 1 byte from port B
// Verilog signature: import "DPI-C" function byte pmem_read_b(input int addr);
char pmem_read_b(int addr) {
    uint32_t uaddr = (uint32_t)addr;
    // if(uaddr == 0) {
    //     // printf("ERROR: pmem_read_b called with addr=0\n");
    //     return 0;  // Ignore reads from address 0
    // }
#ifdef NPC_STANDALONE
    if (uaddr < PMEM_BASE || uaddr >= PMEM_BASE + PMEM_SIZE) {
        // printf("ERROR: pmem_read_b out of bounds: 0x%08x\n", uaddr);
        return 0;
    }
    return (char)*guest_to_host_impl(uaddr);
#else
    // printf("read B from address 0x%08x\n", uaddr);
    // if (uaddr < 0x80000000 || uaddr >= 0x88000000) {
    //     return 0x13;  // Part of RISC-V NOP
    // }
    if (is_pmem_addr(uaddr)) {
        uint8_t *p = guest_to_host_impl(uaddr);
        if (p == nullptr) return 0;
        return (char)*p;
    }
    assert_fail_msg();  // Out of bounds access
    return 0;
#endif
}

// Write 1 byte to port B
// Verilog signature: import "DPI-C" function void pmem_write_b(input int addr, input byte data);
void pmem_write_b(int addr, char data) {
    uint32_t uaddr = (uint32_t)addr;
    // if(uaddr == 0) {
    //     // printf("ERROR: pmem_write_b called with addr=0\n");
    //     return;  // Ignore writes to address 0
    // }
#ifdef NPC_STANDALONE
    if (uaddr < PMEM_BASE || uaddr >= PMEM_BASE + PMEM_SIZE) {
        // printf("ERROR: pmem_write_b out of bounds: 0x%08x\n", uaddr);
        return;
    }
    *guest_to_host_impl(uaddr) = (uint8_t)data;
#else
    if (is_pmem_addr(uaddr)) {
        *guest_to_host_impl(uaddr) = (uint8_t)data;
        return;
    }
    assert_fail_msg();  // Out of bounds access
#endif
}

// ============== Legacy 32-bit interface (for compatibility) ==============

// Read 32-bit word (for instruction fetch)
// Verilog signature: import "DPI-C" function int pmem_read(input int addr);
int pmem_read(int addr) {
    uint32_t uaddr = (uint32_t)addr;
#ifdef NPC_STANDALONE
    if (uaddr < PMEM_BASE || uaddr >= PMEM_BASE + PMEM_SIZE) {
        printf("ERROR: pmem_read out of bounds: 0x%08x\n", uaddr);
        return 0;
    }
    uint32_t *p = (uint32_t *)guest_to_host_impl(uaddr);
    return (int)*p;
#else
    if (uaddr < 0x80000000 || uaddr >= 0x88000000) {
        return 0x00000013;  // RISC-V NOP instruction
    }
    uint32_t *p = (uint32_t *)guest_to_host_impl(uaddr);
    return (int)*p;
#endif
}

// Write with byte mask
// Verilog signature: import "DPI-C" function void pmem_write(input int addr, input int data, input byte wmask);
void pmem_write(int addr, int data, char wmask) {
    uint32_t uaddr = (uint32_t)addr;
#ifdef NPC_STANDALONE
    if (uaddr < PMEM_BASE || uaddr >= PMEM_BASE + PMEM_SIZE) {
        printf("ERROR: pmem_write out of bounds: 0x%08x\n", uaddr);
        return;
    }
    uint8_t *p = guest_to_host_impl(uaddr);
#else
    if (uaddr < 0x80000000 || uaddr >= 0x88000000) {
        return;  // Ignore invalid writes
    }
    uint8_t *p = guest_to_host_impl(uaddr);
#endif
    for (int i = 0; i < 4; i++) {
        if (wmask & (1 << i)) {
            p[i] = (data >> (i * 8)) & 0xFF;
        }
    }
}

// ============== Image Loading ==============

// Load binary image into memory
int load_image(const char *filename) {
#ifdef NPC_STANDALONE
    FILE *fp = fopen(filename, "rb");
    if (!fp) {
        printf("ERROR: Cannot open image file: %s\n", filename);
        return -1;
    }
    
    fseek(fp, 0, SEEK_END);
    long size = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    
    if (size > PMEM_SIZE) {
        printf("ERROR: Image too large: %ld bytes (max %d)\n", size, PMEM_SIZE);
        fclose(fp);
        return -1;
    }
    
    // Load at PMEM_BASE (0x80000000)
    size_t ret = fread(guest_to_host_impl(PMEM_BASE), 1, size, fp);
    fclose(fp);
    
    printf("Loaded image: %s (%ld bytes)\n", filename, size);
    return (int)ret;
#else
    // NEMU integration mode: image is already loaded by NEMU
    printf("NPC: Using NEMU's memory (image loaded by NEMU)\n");
    return 0;
#endif
}

// ============== Debug: Memory Dump ==============

void pmem_dump(uint32_t addr, int len) {
    printf("Memory dump at 0x%08x:\n", addr);
    for (int i = 0; i < len; i++) {
        if (i % 16 == 0) printf("0x%08x: ", addr + i);
        printf("%02x ", pmem_read_a(addr + i));
        if (i % 16 == 15) printf("\n");
    }
    if (len % 16 != 0) printf("\n");
}


// DPI-C functions with output-argument form (required by Verilator for >32-bit returns)
void mul_unit_32  (uint64_t a, uint64_t b, uint64_t *result) { *result = (uint64_t)(int64_t)((int32_t)a * (int32_t)b); }
void mul_unit_64  (uint64_t a, uint64_t b, uint64_t *result) { *result = (uint64_t)((int64_t)a * (int64_t)b); }

void mulh_unit_32 (uint64_t a, uint64_t b, uint64_t *result) {
    __int128 r = (__int128)(int32_t)a * (int32_t)b;
    *result = (uint64_t)(r >> 32) & 0xFFFFFFFF;
}
void mulh_unit_64 (uint64_t a, uint64_t b, uint64_t *result) {
    __int128 r = (__int128)(int64_t)a * (int64_t)b;
    *result = (uint64_t)(r >> 64);
}

void mulhsu_unit_32(uint64_t a, uint64_t b, uint64_t *result) {
    __int128 r = (__int128)(int32_t)a * (uint32_t)b;
    *result = (uint64_t)(r >> 32) & 0xFFFFFFFF;
}
void mulhsu_unit_64(uint64_t a, uint64_t b, uint64_t *result) {
    __int128 r = (__int128)(int64_t)a * (uint64_t)b;
    *result = (uint64_t)(r >> 64);
}

void mulhu_unit_32 (uint64_t a, uint64_t b, uint64_t *result) {
    uint64_t r = (uint64_t)(uint32_t)a * (uint32_t)b;
    *result = (uint64_t)(r >> 32) & 0xFFFFFFFF;
}
void mulhu_unit_64 (uint64_t a, uint64_t b, uint64_t *result) {
    unsigned __int128 r = (unsigned __int128)(uint64_t)a * (uint64_t)b;
    *result = (uint64_t)(r >> 64);
}

void div_unit_32  (uint64_t a, uint64_t b, uint64_t *result) {
    if (b == 0) { *result = (uint64_t)(int64_t)-1; return; }
    if ((int32_t)a == INT32_MIN && (int32_t)b == -1) { *result = (uint64_t)(int64_t)INT32_MIN; return; }
    *result = (uint64_t)(int64_t)((int32_t)a / (int32_t)b);
}
void div_unit_64  (uint64_t a, uint64_t b, uint64_t *result) {
    if (b == 0) { *result = (uint64_t)-1; return; }
    if ((int64_t)a == INT64_MIN && (int64_t)b == -1) { *result = (uint64_t)INT64_MIN; return; }
    *result = (uint64_t)((int64_t)a / (int64_t)b);
}

void divu_unit_32 (uint64_t a, uint64_t b, uint64_t *result) {
    if (b == 0) { *result = (uint64_t)(uint32_t)-1; return; }
    *result = (uint64_t)((uint32_t)a / (uint32_t)b);
}
void divu_unit_64 (uint64_t a, uint64_t b, uint64_t *result) {
    if (b == 0) { *result = (uint64_t)-1; return; }
    *result = a / b;
}

void rem_unit_32  (uint64_t a, uint64_t b, uint64_t *result) {
    if (b == 0) { *result = (uint64_t)(int64_t)(int32_t)a; return; }
    if ((int32_t)a == INT32_MIN && (int32_t)b == -1) { *result = 0; return; }
    *result = (uint64_t)(int64_t)((int32_t)a % (int32_t)b);
}
void rem_unit_64  (uint64_t a, uint64_t b, uint64_t *result) {
    if (b == 0) { *result = a; return; }
    if ((int64_t)a == INT64_MIN && (int64_t)b == -1) { *result = 0; return; }
    *result = (uint64_t)((int64_t)a % (int64_t)b);
}

void remu_unit_32 (uint64_t a, uint64_t b, uint64_t *result) {
    if (b == 0) { *result = (uint64_t)(uint32_t)a; return; }
    *result = (uint64_t)((uint32_t)a % (uint32_t)b);
}
void remu_unit_64 (uint64_t a, uint64_t b, uint64_t *result) {
    if (b == 0) { *result = a; return; }
    *result = a % b;
}

// rv64m only instructions
void mulw_unit    (uint64_t a, uint64_t b, uint64_t *result) { 
    *result = (uint64_t)(int64_t)((int32_t)a * (int32_t)b);
}

void divw_unit    (uint64_t a, uint64_t b, uint64_t *result) {
    if ((uint32_t)b == 0) { *result = (uint64_t)(int64_t)-1; return; }
    if ((int32_t)a == INT32_MIN && (int32_t)b == -1) { *result = (uint64_t)(int64_t)INT32_MIN; return; }
    *result = (uint64_t)(int64_t)((int32_t)a / (int32_t)b);
}
void divuw_unit   (uint64_t a, uint64_t b, uint64_t *result) {
    if ((uint32_t)b == 0) { *result = (uint64_t)-1; return; }
    *result = (uint64_t)(int64_t)(int32_t)((uint32_t)a / (uint32_t)b);
}
void remw_unit    (uint64_t a, uint64_t b, uint64_t *result) {
    if ((uint32_t)b == 0) { *result = (uint64_t)(int64_t)(int32_t)a; return; }
    if ((int32_t)a == INT32_MIN && (int32_t)b == -1) { *result = 0; return; }
    *result = (uint64_t)(int64_t)((int32_t)a % (int32_t)b);
}
void remuw_unit   (uint64_t a, uint64_t b, uint64_t *result) {
    if ((uint32_t)b == 0) { *result = (uint64_t)(int64_t)(int32_t)(uint32_t)a; return; }
    *result = (uint64_t)(int64_t)(int32_t)((uint32_t)a % (uint32_t)b);
}





} // extern "C"
