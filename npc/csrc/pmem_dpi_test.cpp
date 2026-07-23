#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>

extern "C" {
void pmem_read_word(int addr, int word_bytes, uint64_t *data);
void pmem_write_word(int addr, int word_bytes, uint64_t data, uint8_t strb);
void mmio_read_word(int addr, int len, int word_bytes, uint64_t *word_data);
void mmio_write_word(int addr, int len, int word_bytes, uint64_t word_data, uint8_t strb);
}

namespace {

constexpr uint32_t kPmemBase = 0x80000000u;
constexpr uint32_t kPmemSize = 0x08000000u;
uint8_t pmem[kPmemSize] = {};
int fault_count = 0;
uint64_t last_mmio_addr = 0;
int last_mmio_len = 0;
uint64_t last_mmio_write = 0;
int mmio_read_count = 0;
int mmio_write_count = 0;

uint64_t lane_mask(int bytes) {
  return bytes == 8 ? UINT64_MAX : ((UINT64_C(1) << (bytes * 8)) - 1);
}

}  // namespace

extern "C" uint8_t *guest_to_host(uint64_t paddr) {
  if (paddr < kPmemBase || paddr >= static_cast<uint64_t>(kPmemBase) + kPmemSize) return nullptr;
  return pmem + (paddr - kPmemBase);
}

extern "C" uint64_t mmio_read(uint64_t addr, int len) {
  last_mmio_addr = addr;
  last_mmio_len = len;
  mmio_read_count++;
  return UINT64_C(0x44332211);
}

extern "C" void mmio_write(uint64_t addr, int len, uint64_t data) {
  last_mmio_addr = addr;
  last_mmio_len = len;
  last_mmio_write = data;
  mmio_write_count++;
}

extern "C" void assert_fail_msg() {
  fault_count++;
}

int main() {
  for (unsigned mask = 0; mask < 16; mask++) {
    uint64_t value = UINT64_C(0x0123456789abcdef);
    pmem_write_word(kPmemBase, 4, UINT64_C(0xaaaaaaaa), 0xf);
    pmem_write_word(kPmemBase, 4, value, static_cast<uint8_t>(mask));
    uint64_t read = 0;
    pmem_read_word(kPmemBase, 4, &read);
    uint64_t expected = UINT64_C(0xaaaaaaaa);
    for (int lane = 0; lane < 4; lane++) {
      if ((mask & (1u << lane)) != 0) {
        expected = (expected & ~(UINT64_C(0xff) << (lane * 8))) |
          (value & (UINT64_C(0xff) << (lane * 8)));
      }
    }
    assert(read == expected);
  }

  for (unsigned mask = 0; mask < 256; mask++) {
    const uint64_t value = UINT64_C(0xfedcba9876543210);
    pmem_write_word(kPmemBase + 8, 8, UINT64_C(0x1122334455667788), 0xff);
    pmem_write_word(kPmemBase + 8, 8, value, static_cast<uint8_t>(mask));
    uint64_t read = 0;
    pmem_read_word(kPmemBase + 8, 8, &read);
    uint64_t expected = UINT64_C(0x1122334455667788);
    for (int lane = 0; lane < 8; lane++) {
      if ((mask & (1u << lane)) != 0) {
        expected = (expected & ~(UINT64_C(0xff) << (lane * 8))) |
          (value & (UINT64_C(0xff) << (lane * 8)));
      }
    }
    assert(read == expected);
  }

  const int faults_before = fault_count;
  uint64_t rejected = UINT64_MAX;
  pmem_read_word(kPmemBase + kPmemSize, 8, &rejected);
  assert(rejected == 0);
  pmem_write_word(kPmemBase + 2, 4, 0, 0xf);
  assert(fault_count == faults_before + 2);

  // 串口 sb：word 内的第 0 条 byte lane 只传递一个字节。
  mmio_write_word(0xa00003f8, 1, 8, UINT64_C(0x000000000000005a), 0x01);
  assert(mmio_write_count == 1);
  assert(last_mmio_addr == 0xa00003f8u && last_mmio_len == 1 && last_mmio_write == 0x5a);

  // RTC lw：NEMU 只收到一次 4 字节读取，返回值位于对应总线 lane。
  uint64_t rtc_word = 0;
  mmio_read_word(0xa0000048, 4, 8, &rtc_word);
  assert(mmio_read_count == 1);
  assert(last_mmio_addr == 0xa0000048u && last_mmio_len == 4);
  assert(rtc_word == (UINT64_C(0x44332211) & lane_mask(4)));

  puts("pmem DPI ABI tests passed");
  return 0;
}
