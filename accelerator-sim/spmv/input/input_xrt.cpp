#include "input_simulation.hpp"

#ifdef SPMV_INPUT_XRT

#include <experimental/xrt_ip.h>
#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace accelerator_sim::spmv {
namespace {

constexpr std::size_t kAReaderCount = 16;
constexpr std::size_t kXReaderCount = 2;
constexpr std::size_t kHbmPortCount = kAReaderCount + kXReaderCount + 1;
constexpr std::size_t kBeatBytes = sizeof(encoding::cuper::CuperBeat);
constexpr std::uint32_t kCtrl = 0x000;
constexpr std::uint32_t kCtrlBeats = 0x0b0;
constexpr std::uint32_t kABatch = 0x100;
constexpr std::uint32_t kXBatch = 0x200;
constexpr std::uint32_t kBatchIndex = 0x220;
constexpr std::uint32_t kProductChecksum = 0x230;
constexpr std::uint32_t kStatus = 0x238;
constexpr std::uint32_t kHbmBase = 0x010;
constexpr std::uint32_t kApDone = 1U << 1;
constexpr std::uint32_t kMulError = 1U << 5;

struct XrtOptions {
  std::string dataset = "n512";
  std::string xclbin;
  unsigned deviceIndex = 0;
  bool list = false;
};

void writeU64(xrt::ip& ip, std::uint32_t offset, std::uint64_t value) {
  ip.write_register(offset, static_cast<std::uint32_t>(value));
  ip.write_register(offset + 4, static_cast<std::uint32_t>(value >> 32));
}

std::uint64_t readU64(xrt::ip& ip, std::uint32_t offset) {
  return static_cast<std::uint64_t>(ip.read_register(offset)) |
      (static_cast<std::uint64_t>(ip.read_register(offset + 4)) << 32);
}

unsigned parseUnsigned(const std::string& text, const char* option) {
  std::size_t consumed = 0;
  const unsigned long value = std::stoul(text, &consumed, 0);
  if (consumed != text.size() || value > std::numeric_limits<unsigned>::max()) {
    throw std::invalid_argument(std::string(option) + " 必须是非负整数");
  }
  return static_cast<unsigned>(value);
}

XrtOptions parseOptions(int argc, char** argv) {
  XrtOptions options;
  if (const char* configured = std::getenv("SPMV_XRT_XCLBIN")) {
    options.xclbin = configured;
  }
  for (int index = 1; index < argc; ++index) {
    const std::string argument = argv[index];
    if (argument == "--xclbin") {
      if (++index >= argc) throw std::invalid_argument("--xclbin 缺少路径");
      options.xclbin = argv[index];
    } else if (argument == "--device") {
      if (++index >= argc) throw std::invalid_argument("--device 缺少序号");
      options.deviceIndex = parseUnsigned(argv[index], "--device");
    } else if (argument == "--list") {
      options.list = true;
    } else if (!argument.empty() && argument.front() == '-') {
      throw std::invalid_argument("未知 U55C XRT host 参数：" + argument);
    } else if (options.dataset == "n512") {
      options.dataset = argument;
    } else {
      throw std::invalid_argument("只允许指定一个数据集");
    }
  }
  if (!options.list && options.xclbin.empty()) {
    throw std::invalid_argument("缺少 xclbin；传入 --xclbin <path> 或设置 SPMV_XRT_XCLBIN");
  }
  return options;
}

xrt::ip openKernel(const xrt::device& device, const xrt::uuid& uuid) {
  const std::array<std::string, 3> names = {
      "SpmvInputKernel:SpmvInputKernel_1", "SpmvInputKernel_1", "SpmvInputKernel"};
  std::string failures;
  for (const std::string& name : names) {
    try {
      return xrt::ip(device, uuid, name);
    } catch (const std::exception& error) {
      failures += " [" + name + ": " + error.what() + "]";
    }
  }
  throw std::runtime_error("无法打开 SpmvInputKernel XRT IP:" + failures);
}

xrt::bo makeHbmBo(xrt::device& device, unsigned bank,
                  const std::vector<encoding::cuper::CuperBeat>& beats) {
  const std::size_t bytes = std::max(kBeatBytes, beats.size() * kBeatBytes);
  xrt::bo bo(device, bytes, xrt::bo::flags::normal, bank);
  auto* mapped = bo.map<std::uint8_t*>();
  std::memset(mapped, 0, bytes);
  if (!beats.empty()) std::memcpy(mapped, beats.data(), beats.size() * kBeatBytes);
  bo.sync(XCL_BO_SYNC_BO_TO_DEVICE, bytes, 0);
  return bo;
}

void validateInput(const InputSimulationData& input) {
  if (input.aChannels.size() != kAReaderCount || input.aAddresses.size() != kAReaderCount ||
      input.xChannels.size() != kXReaderCount || input.xAddresses.size() != kXReaderCount ||
      input.batches.empty() || input.ctrlChannel.empty()) {
    throw std::runtime_error("XRT host 收到不完整的 16A/2X/1Ctrl 输入布局");
  }
}

void waitForBatch(xrt::ip& ip, std::size_t batch,
                  const InputSimulationBatch& expected) {
  for (;;) {
    const std::uint32_t control = ip.read_register(kCtrl);
    if ((control & kApDone) == 0) {
      std::this_thread::yield();
      continue;
    }
    const std::uint32_t status = ip.read_register(kStatus);
    const std::uint64_t checksum = readU64(ip, kProductChecksum);
    if ((status & kMulError) != 0) {
      throw std::runtime_error("batch " + std::to_string(batch) + " 的乘法器报告错误，status=0x" +
          std::to_string(status));
    }
    if (checksum != expected.expectedProductChecksum) {
      throw std::runtime_error("batch " + std::to_string(batch) + " product checksum 不匹配：期望 0x" +
          std::to_string(expected.expectedProductChecksum) + "，实际 0x" + std::to_string(checksum));
    }
    std::cout << "[spmv-input-u55c] batch=" << batch
              << " fp64_mul=" << expected.expectedMultiplyCount
              << " product_checksum=0x" << std::hex << checksum << std::dec << " PASS\n";
    return;
  }
}

}  // namespace

int runInputXrt(int argc, char** argv) {
  const XrtOptions options = parseOptions(argc, argv);
  if (options.list) {
    throw std::invalid_argument("U55C XRT host 的 --list 请使用本地 SpMV 仿真 host");
  }
  const InputSimulationData input = buildInputSimulationData(options.dataset);
  validateInput(input);

  xrt::device device(options.deviceIndex);
  const xrt::uuid uuid = device.load_xclbin(options.xclbin);
  xrt::ip ip = openKernel(device, uuid);

  std::vector<xrt::bo> aBos;
  std::vector<xrt::bo> xBos;
  aBos.reserve(kAReaderCount);
  xBos.reserve(kXReaderCount);
  for (std::size_t channel = 0; channel < kAReaderCount; ++channel) {
    aBos.push_back(makeHbmBo(device, static_cast<unsigned>(channel), input.aChannels[channel]));
    writeU64(ip, kHbmBase + static_cast<std::uint32_t>(channel * 8), aBos.back().address());
  }
  for (std::size_t channel = 0; channel < kXReaderCount; ++channel) {
    xBos.push_back(makeHbmBo(device, static_cast<unsigned>(kAReaderCount + channel),
                             input.xChannels[channel]));
    writeU64(ip, kHbmBase + static_cast<std::uint32_t>((kAReaderCount + channel) * 8),
             xBos.back().address());
  }
  xrt::bo ctrlBo = makeHbmBo(device, static_cast<unsigned>(kAReaderCount + kXReaderCount),
                             input.ctrlChannel);
  writeU64(ip, kHbmBase + static_cast<std::uint32_t>((kHbmPortCount - 1) * 8), ctrlBo.address());
  ip.write_register(kCtrlBeats, static_cast<std::uint32_t>(input.ctrlChannel.size()));

  for (std::size_t batch = 0; batch < input.batches.size(); ++batch) {
    const InputSimulationBatch& window = input.batches[batch];
    for (std::size_t channel = 0; channel < kAReaderCount; ++channel) {
      if (window.aAddresses[channel] < input.aAddresses[channel]) {
        throw std::runtime_error("A batch 地址低于本 HBM BO 基地址");
      }
      writeU64(ip, kABatch + static_cast<std::uint32_t>(channel * 16),
               window.aAddresses[channel] - input.aAddresses[channel]);
      ip.write_register(kABatch + static_cast<std::uint32_t>(channel * 16 + 8),
                        static_cast<std::uint32_t>(window.aChannels[channel].size()));
    }
    for (std::size_t channel = 0; channel < kXReaderCount; ++channel) {
      if (window.xAddresses[channel] < input.xAddresses[channel]) {
        throw std::runtime_error("X batch 地址低于本 HBM BO 基地址");
      }
      writeU64(ip, kXBatch + static_cast<std::uint32_t>(channel * 16),
               window.xAddresses[channel] - input.xAddresses[channel]);
      ip.write_register(kXBatch + static_cast<std::uint32_t>(channel * 16 + 8),
                        static_cast<std::uint32_t>(window.xChannels[channel].size()));
    }
    ip.write_register(kBatchIndex, static_cast<std::uint32_t>(batch));
    ip.write_register(kCtrl, 1U);
    waitForBatch(ip, batch, window);
  }

  std::cout << "[spmv-input-u55c] dataset=" << input.dataset
            << " batches=" << input.batches.size()
            << " expected_product_checksum=0x" << std::hex << input.expectedProductChecksum
            << std::dec << " PASS\n";
  return 0;
}

}  // namespace accelerator_sim::spmv

#endif
