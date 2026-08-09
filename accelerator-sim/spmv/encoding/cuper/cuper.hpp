#pragma once

#include "../../golden.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace accelerator_sim::spmv::encoding::cuper {

constexpr std::size_t kLanesPerBeat = 8;
constexpr std::uint32_t kColumnBits = 14;
constexpr std::uint32_t kRowBits = 18;
constexpr std::uint32_t kPaddingRow = (1U << kRowBits) - 1U;
constexpr std::uint32_t kMaximumValidRow = (1U << (kRowBits - 1U)) - 1U;
constexpr std::uint64_t kPaddingSlot = static_cast<std::uint64_t>(kPaddingRow) << 32U;

struct CuperConfig {
  std::size_t hbmChannelCount = 16;
  std::size_t sliceSize = 64;
  std::size_t columnSlicesPerBatch = 128;
  // U55C c_latency=4，加上 wrapper 输入级和 SRAM 读写边界，安全 issue 间隔为 7。
  std::size_t reorderWindow = 7;
};

using CuperBeat = std::array<std::uint64_t, kLanesPerBeat>;

struct CuperEncodingStats {
  std::size_t batchCount = 0;
  std::size_t minimumMatrixBeatsPerChannel = 0;
  std::size_t maximumMatrixBeatsPerChannel = 0;
  std::uint64_t totalMatrixBeats = 0;
  std::uint64_t validSlots = 0;
  std::uint64_t paddingSlots = 0;
  std::uint64_t packedBytes = 0;

  double slotUtilization() const;
};

struct CuperPackage {
  CuperConfig config;
  std::size_t rows = 0;
  std::size_t columns = 0;
  std::uint64_t nonzeros = 0;

  // 每个 HBM channel 独立的累计 batch 边界，单位是该 channel 的 512-bit beat。
  std::vector<std::vector<std::uint32_t>> channelBatchPointers;
  // lane 0 对应 512-bit beat 的 [63:0]，lane 7 对应 [511:448]。
  // 各 channel 只补齐自身 8 lanes，因此长度可以不同。
  std::vector<std::vector<CuperBeat>> matrixChannels;
  CuperEncodingStats stats;
};

struct DecodedCuperSlot {
  bool padding = false;
  std::uint32_t localColumn = 0;
  std::uint32_t encodedRow = 0;
  float value = 0.0F;
};

std::size_t columnsPerBatch(const CuperConfig& config);
std::size_t totalPeCount(const CuperConfig& config);
std::size_t peForRow(std::size_t row, const CuperConfig& config);
std::size_t decodeOriginalRow(std::uint32_t encodedRow, std::size_t pe,
                              const CuperConfig& config);
DecodedCuperSlot decodeSlot(std::uint64_t slot);
CuperPackage encode(const CsrMatrix& matrix, const CuperConfig& config = {});

}  // namespace accelerator_sim::spmv::encoding::cuper
