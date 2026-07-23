// NEMU-backed physical memory and MMIO DPI implementation.
#include <cstdint>
#include <cstdio>
#include <cstring>

#ifdef NPC_STANDALONE
#define PMEM_SIZE (128 * 1024 * 1024)
#define PMEM_BASE 0x80000000u

static uint8_t pmem[PMEM_SIZE] = {};

static inline uint8_t *guest_to_host_impl(uint32_t paddr) {
    return pmem + (paddr - PMEM_BASE);
}

static inline uint64_t mmio_read(uint64_t, int) { return 0; }
static inline void mmio_write(uint64_t, int, uint64_t) {}
static inline void assert_fail_msg() {
    fprintf(stderr, "[NPC-PMEM] invalid memory access\n");
}
#else
extern "C" {
uint8_t *guest_to_host(uint64_t paddr);
uint64_t mmio_read(uint64_t addr, int len);
void mmio_write(uint64_t addr, int len, uint64_t data);
void assert_fail_msg();
}

static inline uint8_t *guest_to_host_impl(uint32_t paddr) {
    uint8_t *result = guest_to_host(static_cast<uint64_t>(paddr));
    if (result == nullptr) {
        fprintf(stderr, "[NPC-PMEM] guest_to_host returned NULL for paddr=0x%08x\n", paddr);
        fflush(stderr);
    }
    return result;
}
#endif

namespace {

constexpr uint32_t kPmemBase = 0x80000000u;
constexpr uint32_t kPmemSize = 0x08000000u;
constexpr uint32_t kMmioBase = 0xa0000000u;
constexpr uint32_t kMmioEnd = 0xa2000000u;

bool valid_word_bytes(int word_bytes) {
    return word_bytes == 4 || word_bytes == 8;
}

bool valid_access_len(int len) {
    return len == 1 || len == 2 || len == 4 || len == 8;
}

bool is_pmem_addr(uint32_t addr) {
    return addr >= kPmemBase && addr < kPmemBase + kPmemSize;
}

bool is_pmem_range(uint32_t addr, int len) {
    return len > 0 && addr >= kPmemBase &&
        static_cast<uint64_t>(addr) + static_cast<uint64_t>(len) <=
            static_cast<uint64_t>(kPmemBase) + kPmemSize;
}

bool is_mmio_addr(uint32_t addr) {
    return addr >= kMmioBase && addr < kMmioEnd;
}

uint64_t byte_mask(int len) {
    return len == 8 ? UINT64_MAX : ((UINT64_C(1) << (len * 8)) - 1);
}

uint8_t byte_strobe_mask(int len) {
    return static_cast<uint8_t>((UINT32_C(1) << len) - 1);
}

const char *fault_reason(int reason) {
    switch (reason) {
        case 0: return "misaligned";
        case 1: return "crosses XLEN beat";
        case 2: return "read response error";
        case 3: return "write response error";
        case 4: return "address out of range";
        case 5: return "invalid DPI request";
        default: return "unknown";
    }
}

uint64_t last_store_sequence = 0;
uint32_t last_store_address = 0;
uint64_t last_store_data = 0;
uint8_t last_store_strobe = 0;
int last_store_word_bytes = 0;

void report_memory_fault(uint32_t addr, bool write, int len, int reason) {
    fprintf(stderr, "[NPC-MEM-FAULT] addr=0x%08x %s len=%d reason=%s\n",
        addr, write ? "write" : "read", len, fault_reason(reason));
    fflush(stderr);
    assert_fail_msg();
}

}  // namespace

extern "C" {

void memory_fault(int addr, unsigned char write, int len, int reason) {
    report_memory_fault(static_cast<uint32_t>(addr), write != 0, len, reason);
}

// 主存 ABI：仅接受已经对齐的完整 XLEN beat，绝不在此处修正地址。
void pmem_read_word(int addr, int word_bytes, uint64_t *data) {
    const uint32_t uaddr = static_cast<uint32_t>(addr);
    *data = 0;
    if (!valid_word_bytes(word_bytes) || (uaddr & static_cast<uint32_t>(word_bytes - 1)) != 0) {
        report_memory_fault(uaddr, false, word_bytes, 5);
        return;
    }
    if (!is_pmem_range(uaddr, word_bytes)) {
        report_memory_fault(uaddr, false, word_bytes, 4);
        return;
    }
    uint8_t *p = guest_to_host_impl(uaddr);
    if (p == nullptr) {
        report_memory_fault(uaddr, false, word_bytes, 4);
        return;
    }
    memcpy(data, p, static_cast<size_t>(word_bytes));
}

void pmem_write_word(int addr, int word_bytes, uint64_t data, uint8_t strb) {
    const uint32_t uaddr = static_cast<uint32_t>(addr);
    if (!valid_word_bytes(word_bytes) || (uaddr & static_cast<uint32_t>(word_bytes - 1)) != 0) {
        report_memory_fault(uaddr, true, word_bytes, 5);
        return;
    }
    if (!is_pmem_range(uaddr, word_bytes)) {
        report_memory_fault(uaddr, true, word_bytes, 4);
        return;
    }
    uint8_t *p = guest_to_host_impl(uaddr);
    if (p == nullptr) {
      report_memory_fault(uaddr, true, word_bytes, 4);
      return;
    }
    last_store_sequence++;
    last_store_address = uaddr;
    last_store_data = data;
    last_store_strobe = strb;
    last_store_word_bytes = word_bytes;
    for (int lane = 0; lane < word_bytes; lane++) {
        if ((strb & (UINT8_C(1) << lane)) != 0) {
            p[lane] = static_cast<uint8_t>(data >> (lane * 8));
        }
    }
}

uint64_t npc_get_last_store_sequence() { return last_store_sequence; }
uint64_t npc_get_last_store_address() { return last_store_address; }
uint64_t npc_get_last_store_data() { return last_store_data; }
uint32_t npc_get_last_store_strobe() { return last_store_strobe; }
uint32_t npc_get_last_store_word_bytes() { return static_cast<uint32_t>(last_store_word_bytes); }

// MMIO ABI：以指令的原始地址和长度恰好调用一次设备，数据在总线字内按 lane 放置。
void mmio_read_word(int addr, int len, int word_bytes, uint64_t *word_data) {
    const uint32_t uaddr = static_cast<uint32_t>(addr);
    *word_data = 0;
    if (!valid_word_bytes(word_bytes) || !valid_access_len(len)) {
        report_memory_fault(uaddr, false, len, 5);
        return;
    }
    const int lane = uaddr & static_cast<uint32_t>(word_bytes - 1);
    if (lane + len > word_bytes) {
        report_memory_fault(uaddr, false, len, 5);
        return;
    }
    if (!is_mmio_addr(uaddr)) {
        report_memory_fault(uaddr, false, len, 4);
        return;
    }
    *word_data = (mmio_read(uaddr, len) & byte_mask(len)) << (lane * 8);
}

void mmio_write_word(int addr, int len, int word_bytes, uint64_t word_data, uint8_t strb) {
    const uint32_t uaddr = static_cast<uint32_t>(addr);
    if (!valid_word_bytes(word_bytes) || !valid_access_len(len)) {
        report_memory_fault(uaddr, true, len, 5);
        return;
    }
    const int lane = uaddr & static_cast<uint32_t>(word_bytes - 1);
    if (lane + len > word_bytes) {
        report_memory_fault(uaddr, true, len, 5);
        return;
    }
    const uint8_t expected_strb = static_cast<uint8_t>(byte_strobe_mask(len) << lane);
    if (strb != expected_strb) {
        report_memory_fault(uaddr, true, len, 5);
        return;
    }
    if (!is_mmio_addr(uaddr)) {
        report_memory_fault(uaddr, true, len, 4);
        return;
    }
    mmio_write(uaddr, len, (word_data >> (lane * 8)) & byte_mask(len));
}

// 保留旧版字节/32 位接口，供未迁移的独立 Verilog 实验使用；当前 Chisel 仿真不再导入它们。
char pmem_read_a(int addr) {
    const uint32_t uaddr = static_cast<uint32_t>(addr);
    if (!is_pmem_addr(uaddr)) {
        report_memory_fault(uaddr, false, 1, 4);
        return 0;
    }
    uint8_t *p = guest_to_host_impl(uaddr);
    return p == nullptr ? 0 : static_cast<char>(*p);
}

void pmem_write_a(int addr, char data) {
    const uint32_t uaddr = static_cast<uint32_t>(addr);
    if (!is_pmem_addr(uaddr)) {
        report_memory_fault(uaddr, true, 1, 4);
        return;
    }
    uint8_t *p = guest_to_host_impl(uaddr);
    if (p != nullptr) *p = static_cast<uint8_t>(data);
}

char pmem_read_b(int addr) { return pmem_read_a(addr); }
void pmem_write_b(int addr, char data) { pmem_write_a(addr, data); }

int pmem_read(int addr) {
    uint64_t data = 0;
    pmem_read_word(addr, 4, &data);
    return static_cast<int>(data);
}

void pmem_write(int addr, int data, char wmask) {
    pmem_write_word(addr, 4, static_cast<uint32_t>(data), static_cast<uint8_t>(wmask));
}

int load_image(const char *filename) {
#ifdef NPC_STANDALONE
    FILE *fp = fopen(filename, "rb");
    if (fp == nullptr) {
        fprintf(stderr, "ERROR: Cannot open image file: %s\n", filename);
        return -1;
    }
    fseek(fp, 0, SEEK_END);
    const long size = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    if (size < 0 || size > PMEM_SIZE) {
        fclose(fp);
        fprintf(stderr, "ERROR: Image is too large: %ld bytes\n", size);
        return -1;
    }
    const size_t read = fread(guest_to_host_impl(PMEM_BASE), 1, static_cast<size_t>(size), fp);
    fclose(fp);
    return static_cast<int>(read);
#else
    (void)filename;
    return 0;
#endif
}

void pmem_dump(uint32_t addr, int len) {
    for (int index = 0; index < len; index++) {
        if ((index % 16) == 0) printf("0x%08x: ", addr + index);
        printf("%02x ", static_cast<unsigned char>(pmem_read_a(addr + index)));
        if ((index % 16) == 15) printf("\n");
    }
    if ((len % 16) != 0) printf("\n");
}

void mul_unit_32(uint64_t a, uint64_t b, uint64_t *result) { *result = static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(a) * static_cast<int32_t>(b))); }
void mul_unit_64(uint64_t a, uint64_t b, uint64_t *result) { *result = static_cast<uint64_t>(static_cast<int64_t>(a) * static_cast<int64_t>(b)); }
void mulh_unit_32(uint64_t a, uint64_t b, uint64_t *result) { *result = static_cast<uint64_t>((static_cast<__int128>(static_cast<int32_t>(a)) * static_cast<int32_t>(b)) >> 32) & UINT32_MAX; }
void mulh_unit_64(uint64_t a, uint64_t b, uint64_t *result) { *result = static_cast<uint64_t>((static_cast<__int128>(static_cast<int64_t>(a)) * static_cast<int64_t>(b)) >> 64); }
void mulhsu_unit_32(uint64_t a, uint64_t b, uint64_t *result) { *result = static_cast<uint64_t>((static_cast<__int128>(static_cast<int32_t>(a)) * static_cast<uint32_t>(b)) >> 32) & UINT32_MAX; }
void mulhsu_unit_64(uint64_t a, uint64_t b, uint64_t *result) { *result = static_cast<uint64_t>((static_cast<__int128>(static_cast<int64_t>(a)) * static_cast<uint64_t>(b)) >> 64); }
void mulhu_unit_32(uint64_t a, uint64_t b, uint64_t *result) { *result = (static_cast<uint64_t>(static_cast<uint32_t>(a)) * static_cast<uint32_t>(b)) >> 32; }
void mulhu_unit_64(uint64_t a, uint64_t b, uint64_t *result) { *result = static_cast<uint64_t>((static_cast<unsigned __int128>(a) * b) >> 64); }

void div_unit_32(uint64_t a, uint64_t b, uint64_t *result) { if (b == 0) *result = static_cast<uint64_t>(static_cast<int64_t>(-1)); else if (static_cast<int32_t>(a) == INT32_MIN && static_cast<int32_t>(b) == -1) *result = static_cast<uint64_t>(static_cast<int64_t>(INT32_MIN)); else *result = static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(a) / static_cast<int32_t>(b))); }
void div_unit_64(uint64_t a, uint64_t b, uint64_t *result) { if (b == 0) *result = UINT64_MAX; else if (static_cast<int64_t>(a) == INT64_MIN && static_cast<int64_t>(b) == -1) *result = static_cast<uint64_t>(INT64_MIN); else *result = static_cast<uint64_t>(static_cast<int64_t>(a) / static_cast<int64_t>(b)); }
void divu_unit_32(uint64_t a, uint64_t b, uint64_t *result) { *result = b == 0 ? UINT32_MAX : static_cast<uint32_t>(a) / static_cast<uint32_t>(b); }
void divu_unit_64(uint64_t a, uint64_t b, uint64_t *result) { *result = b == 0 ? UINT64_MAX : a / b; }
void rem_unit_32(uint64_t a, uint64_t b, uint64_t *result) { if (b == 0) *result = static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(a))); else if (static_cast<int32_t>(a) == INT32_MIN && static_cast<int32_t>(b) == -1) *result = 0; else *result = static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(a) % static_cast<int32_t>(b))); }
void rem_unit_64(uint64_t a, uint64_t b, uint64_t *result) { if (b == 0) *result = a; else if (static_cast<int64_t>(a) == INT64_MIN && static_cast<int64_t>(b) == -1) *result = 0; else *result = static_cast<uint64_t>(static_cast<int64_t>(a) % static_cast<int64_t>(b)); }
void remu_unit_32(uint64_t a, uint64_t b, uint64_t *result) { *result = b == 0 ? static_cast<uint32_t>(a) : static_cast<uint32_t>(a) % static_cast<uint32_t>(b); }
void remu_unit_64(uint64_t a, uint64_t b, uint64_t *result) { *result = b == 0 ? a : a % b; }
void mulw_unit(uint64_t a, uint64_t b, uint64_t *result) { *result = static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(a) * static_cast<int32_t>(b))); }
void divw_unit(uint64_t a, uint64_t b, uint64_t *result) { if (static_cast<uint32_t>(b) == 0) *result = static_cast<uint64_t>(static_cast<int64_t>(-1)); else if (static_cast<int32_t>(a) == INT32_MIN && static_cast<int32_t>(b) == -1) *result = static_cast<uint64_t>(static_cast<int64_t>(INT32_MIN)); else *result = static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(a) / static_cast<int32_t>(b))); }
void divuw_unit(uint64_t a, uint64_t b, uint64_t *result) { *result = static_cast<uint32_t>(b) == 0 ? UINT64_MAX : static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(static_cast<uint32_t>(a) / static_cast<uint32_t>(b)))); }
void remw_unit(uint64_t a, uint64_t b, uint64_t *result) { if (static_cast<uint32_t>(b) == 0) *result = static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(a))); else if (static_cast<int32_t>(a) == INT32_MIN && static_cast<int32_t>(b) == -1) *result = 0; else *result = static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(a) % static_cast<int32_t>(b))); }
void remuw_unit(uint64_t a, uint64_t b, uint64_t *result) { *result = static_cast<uint32_t>(b) == 0 ? static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(a))) : static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(static_cast<uint32_t>(a) % static_cast<uint32_t>(b)))); }

}  // extern "C"
