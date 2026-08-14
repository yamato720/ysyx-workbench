#pragma once

#include "../../golden.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace accelerator_sim::spmv::encoding::cuper {

constexpr std::size_t kLanesPerBeat = 8;
constexpr std::size_t kVectorLanesPerBeat = 16;
constexpr std::size_t kVectorStorageAlignmentElements = 1024;
constexpr std::size_t kVectorReplicaCount = 4;
constexpr std::size_t kVectorPartitionFactor = 8;
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
using CuperVectorBeat = std::array<std::uint32_t, kVectorLanesPerBeat>;

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

struct CuperVectorStats {
  std::size_t batchCount = 0;
  std::size_t payloadBeats = 0;
  std::size_t allocatedBeats = 0;
  std::size_t validElements = 0;
  std::size_t lanePaddingElements = 0;
  std::size_t allocationPaddingElements = 0;
  std::uint64_t packedBytes = 0;
  std::uint64_t allocatedBytes = 0;
};

/** Cuper X 的 HBM 布局及其 Core 本地存储映射。
  *
  * HBM 中的元素保持原列顺序，FP64 输入先转换成 FP32，再按 float_v16 打包。
  * Core 按 8192 列 batch 接收数据，并把每个 batch 复制到 4 份、8 路 cyclic partition
  * 的 local_X 存储中。
  */
struct CuperVectorPackage {
  CuperConfig config;
  std::size_t columns = 0;
  std::vector<double> sourceValues;
  // 累计 batch 边界，单位是 512-bit float_v16 beat；不包含 HBM 分配尾部 padding。
  std::vector<std::uint32_t> batchPointers;
  // 包含 host 的 1024-element 对齐尾部，未被 kernel 读取的 beat 保持全零。
  std::vector<CuperVectorBeat> hbmBeats;
  CuperVectorStats stats;
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
CuperVectorPackage encodeVector(const std::vector<double>& input,
                                const CuperConfig& config = {});

}  // namespace accelerator_sim::spmv::encoding::cuper
