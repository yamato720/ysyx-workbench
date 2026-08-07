#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include <vector>

#include "VSpmvHbmDpiReadSlave.h"
#include "svdpi.h"
#include "verilated.h"

namespace {

constexpr std::uint64_t kHbmBase = 0x80000000ULL;
constexpr std::uint64_t kHbmBytes = 128ULL * 1024ULL * 1024ULL;
std::vector<std::uint64_t> readAddresses;

bool noJitterEnabled() {
  const char* configured = std::getenv("SPMV_HBM_NO_JITTER");
  return configured != nullptr && *configured != '\0' && std::string(configured) != "0";
}

void require(bool condition, const std::string& message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

struct Simulation {
  VerilatedContext context;
  VSpmvHbmDpiReadSlave dut{&context};
  std::uint64_t cycles = 0;

  void tick() {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
    context.timeInc(1);
    ++cycles;
  }

  void issue(std::uint64_t address, std::uint8_t length, std::uint8_t id = 5) {
    dut.arAddr = address;
    dut.arLen = length;
    dut.arId = id;
    dut.arSize = 6;
    dut.arBurst = 1;
    dut.arValid = 1;
    dut.clock = 0;
    dut.eval();
    require(dut.arReady, "HBM slave did not have a free AR queue entry");
    tick();
    dut.arValid = 0;
  }

  void waitForResponse(unsigned limit = 100) {
    unsigned waited = 0;
    while (!dut.rValid && waited < limit) {
      tick();
      ++waited;
    }
    require(dut.rValid, "HBM slave response timed out");
  }
};

}  // namespace

extern "C" void spmv_hbm_read512(std::uint64_t address, svBitVecVal* data, svBit* error) {
  std::fill(data, data + 16, 0U);
  *error = 0;
  if ((address & 63ULL) != 0 || address < kHbmBase ||
      address > kHbmBase + kHbmBytes - 64ULL) {
    *error = 1;
    return;
  }
  readAddresses.push_back(address);
  for (std::uint32_t word = 0; word < 16; ++word) {
    data[word] = static_cast<std::uint32_t>(address - kHbmBase) + word;
  }
}

extern "C" svBit spmv_hbm_no_jitter() {
  return noJitterEnabled() ? 1 : 0;
}

int main(int argc, char** argv) {
  try {
    Simulation simulation;
    simulation.context.commandArgs(argc, argv);
    simulation.dut.arValid = 0;
    simulation.dut.rReady = 0;
    simulation.dut.reset = 1;
    simulation.tick();
    simulation.tick();
    simulation.dut.reset = 0;
    simulation.tick();

    simulation.issue(kHbmBase, 2);
    const std::uint64_t acceptedAt = simulation.cycles;
    simulation.issue(kHbmBase + 0x1000, 1, 6);
    require(!simulation.dut.arReady,
        "HBM slave accepted more than two outstanding AR requests");
    const std::uint64_t expectedLatency = noJitterEnabled()
        ? (73 + 81) / 2
        : 73 + (0x13579bdfU % 9U);
    simulation.waitForResponse();
    require(simulation.cycles - acceptedAt == expectedLatency,
        "first HBM beat latency is outside the fixed-seed contract");
    require(readAddresses.size() == 1 && readAddresses[0] == kHbmBase,
        "first response did not perform exactly one 512-bit DPI read");
    require(simulation.dut.rId == 5 && simulation.dut.rResp == 0 && !simulation.dut.rLast,
        "first response sideband mismatch");
    std::array<std::uint32_t, 16> held{};
    for (std::size_t word = 0; word < held.size(); ++word) {
      held[word] = simulation.dut.rData[word];
      require(held[word] == word, "512-bit DPI word order mismatch");
    }

    for (unsigned cycle = 0; cycle < 5; ++cycle) {
      simulation.tick();
      require(simulation.dut.rValid && readAddresses.size() == 1,
          "backpressure caused an extra DPI read or dropped RVALID");
      for (std::size_t word = 0; word < held.size(); ++word) {
        require(simulation.dut.rData[word] == held[word],
            "RDATA changed while RVALID was stalled");
      }
    }

    simulation.dut.rReady = 1;
    simulation.tick();
    require(simulation.dut.rValid && readAddresses.size() == 2 &&
        readAddresses[1] == kHbmBase + 64 && !simulation.dut.rLast,
        "burst did not sustain one complete beat per cycle");
    simulation.tick();
    require(simulation.dut.rValid && readAddresses.size() == 3 &&
        readAddresses[2] == kHbmBase + 128 && simulation.dut.rLast,
        "burst final beat or RLAST mismatch");
    simulation.tick();
    require(!simulation.dut.rValid && simulation.dut.arReady,
        "HBM slave did not release one credit after the first burst");
    simulation.tick();
    require(simulation.dut.rValid && simulation.dut.rId == 6 &&
        readAddresses.size() == 4 && readAddresses.back() == kHbmBase + 0x1000,
        "queued burst latency did not overlap or response order was interleaved");
    simulation.tick();
    require(simulation.dut.rValid && simulation.dut.rId == 6 && simulation.dut.rLast &&
        readAddresses.size() == 5 && readAddresses.back() == kHbmBase + 0x1040,
        "queued burst did not sustain one complete beat per cycle");
    simulation.tick();
    require(!simulation.dut.rValid && simulation.dut.arReady,
        "HBM slave did not return to idle after both bursts");

    const std::size_t validCalls = readAddresses.size();
    simulation.dut.rReady = 1;
    simulation.issue(kHbmBase + 1, 0);
    simulation.waitForResponse();
    require(simulation.dut.rResp == 2 && readAddresses.size() == validCalls,
        "misaligned request reached DPI or missed SLVERR");
    simulation.tick();

    simulation.issue(kHbmBase + 0xfc0, 1);
    simulation.waitForResponse();
    for (unsigned beat = 0; beat < 2; ++beat) {
      require(simulation.dut.rValid && simulation.dut.rResp == 2 &&
          static_cast<bool>(simulation.dut.rLast) == (beat == 1),
          "4 KiB crossing request did not return a complete SLVERR burst");
      simulation.tick();
    }
    require(readAddresses.size() == validCalls,
        "4 KiB crossing request unexpectedly called DPI");
    simulation.dut.final();
    return 0;
  } catch (const std::exception& error) {
    VL_PRINTF("hbm-dpi-protocol-test: %s\n", error.what());
    return 2;
  }
}
