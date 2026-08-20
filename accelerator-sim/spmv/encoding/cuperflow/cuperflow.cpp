#include "cuperflow.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <iterator>
#include <limits>
#include <numeric>
#include <stdexcept>
#include <unordered_map>
#include <utility>

namespace accelerator_sim::spmv::encoding::cuperflow {

std::uint64_t makeXAddressMarker(std::uint32_t address) {
  if (static_cast<std::uint64_t>(address) > kXAddressMarkerAddressMask) {
    throw std::out_of_range("Cuperflow X 地址 marker 超过 13-bit BRAM 地址范围");
  }
  return kXAddressMarkerBase | static_cast<std::uint64_t>(address);
}

bool isXAddressMarker(std::uint64_t word) {
  const std::uint64_t fixedMask = ~kXAddressMarkerAddressMask;
  return (word & fixedMask) == kXAddressMarkerBase;
}

std::uint32_t decodeXAddressMarker(std::uint64_t word) {
  if (!isXAddressMarker(word)) {
    throw std::invalid_argument("Cuperflow X word 不是地址 marker");
  }
  return static_cast<std::uint32_t>(word & kXAddressMarkerAddressMask);
}

std::uint64_t makeXMapMarker(bool last) {
  return kXMapMarkerBase |
      (static_cast<std::uint64_t>(kCuperflowMapVersion) << kXMapMarkerVersionShift) |
      (last ? kXMapMarkerLastMask : 0U);
}

bool isXMapMarker(std::uint64_t word) {
  constexpr std::uint64_t kVariableMask = (std::uint64_t{1} << 13U) - 1U;
  constexpr std::uint64_t kVersionMask = (std::uint64_t{1} << kXMapMarkerVersionBits) - 1U;
  return (word & ~kVariableMask) == kXMapMarkerBase &&
      ((word >> kXMapMarkerVersionShift) & kVersionMask) == kCuperflowMapVersion;
}

CuperflowVectorBeat packMapBeat(const CuperflowMapBeat& map) {
  CuperflowVectorBeat beat{};
  beat[0] = makeXMapMarker(map.last);
  beat[1] = static_cast<std::uint64_t>(map.xBeats) |
      (static_cast<std::uint64_t>(map.xWords) << 32U);
  beat[2] = map.batchDescriptorCount;
  beat[3] = static_cast<std::uint64_t>(map.sliceGroup) |
      (static_cast<std::uint64_t>(map.xElements) << 16U);
  for (std::size_t segment = 0; segment < kMaxXSegments; ++segment) {
    const CuperflowXSegment& item = map.xSegments[segment];
    if (item.start > kXAddressMarkerAddressMask || item.count > kMaxXRangeElements) {
      throw std::out_of_range("Cuperflow map X 段 descriptor 超出 13/14-bit 范围");
    }
    const std::uint32_t descriptor = static_cast<std::uint32_t>(item.start) |
        (static_cast<std::uint32_t>(item.count) << kColumnBits);
    beat[4U + segment / 2U] |= static_cast<std::uint64_t>(descriptor) <<
        (32U * (segment % 2U));
  }
  return beat;
}

CuperflowMapBeat unpackMapBeat(const CuperflowVectorBeat& beat) {
  if (!isXMapMarker(beat[0])) {
    throw std::invalid_argument("Cuperflow X beat 不是 map");
  }
  CuperflowMapBeat map;
  map.xBeats = static_cast<std::uint32_t>(beat[1]);
  map.xWords = static_cast<std::uint32_t>(beat[1] >> 32U);
  if ((beat[2] >> 32U) != 0U || (beat[3] >> 32U) != 0U) {
    throw std::invalid_argument("Cuperflow map 的 reserved 位非零");
  }
  map.batchDescriptorCount = static_cast<std::uint32_t>(beat[2]);
  map.sliceGroup = static_cast<std::uint16_t>(beat[3]);
  map.xElements = static_cast<std::uint16_t>(beat[3] >> 16U);
  for (std::size_t segment = 0; segment < kMaxXSegments; ++segment) {
    const std::uint32_t descriptor = static_cast<std::uint32_t>(
        beat[4U + segment / 2U] >> (32U * (segment % 2U)));
    if ((descriptor >> (kColumnBits + kXSegmentCountBits)) != 0U) {
      throw std::invalid_argument("Cuperflow map X 段 descriptor 的 reserved 位非零");
    }
    map.xSegments[segment] = CuperflowXSegment{
        static_cast<std::uint16_t>(descriptor & kXAddressMarkerAddressMask),
        static_cast<std::uint16_t>((descriptor >> kColumnBits) &
                                   ((std::uint32_t{1} << kXSegmentCountBits) - 1U))};
  }
  map.last = (beat[0] & kXMapMarkerLastMask) != 0U;
  return map;
}

std::uint64_t makeBatchDescriptorMarker(bool lastBatchInGroup) {
  return kBatchDescriptorMarkerBase |
      (static_cast<std::uint64_t>(kCuperflowBatchDescriptorVersion)
       << kBatchDescriptorMarkerVersionShift) |
      (lastBatchInGroup ? kBatchDescriptorMarkerLastMask : 0U);
}

bool isBatchDescriptorMarker(std::uint64_t word) {
  constexpr std::uint64_t kVariableMask = (std::uint64_t{1} << 13U) - 1U;
  constexpr std::uint64_t kVersionMask =
      (std::uint64_t{1} << kBatchDescriptorMarkerVersionBits) - 1U;
  return (word & ~kVariableMask) == kBatchDescriptorMarkerBase &&
      ((word >> kBatchDescriptorMarkerVersionShift) & kVersionMask) ==
          kCuperflowBatchDescriptorVersion;
}

void validateBatchDescriptor(const CuperflowBatchDescriptor& descriptor) {
  if (descriptor.activeRowCount > kMaxXRangeElements) {
    throw std::invalid_argument("Cuperflow BATCH_DESC 的 activeRowCount 超过 8192");
  }
  const std::uint32_t expectedWords = static_cast<std::uint32_t>(
      (static_cast<std::uint64_t>(descriptor.activeRowCount) + 63U) / 64U);
  if (descriptor.contributorWordCount != expectedWords) {
    throw std::invalid_argument("Cuperflow BATCH_DESC 的 contributor bitmap 长度错误");
  }
}

CuperflowVectorBeat packBatchDescriptor(const CuperflowBatchDescriptor& descriptor) {
  validateBatchDescriptor(descriptor);
  CuperflowVectorBeat beat{};
  beat[0] = makeBatchDescriptorMarker(descriptor.lastBatchInGroup);
  beat[1] = static_cast<std::uint64_t>(descriptor.batchId) |
      (static_cast<std::uint64_t>(descriptor.aOffsetBeats) << 32U);
  beat[2] = static_cast<std::uint64_t>(descriptor.aBeats) |
      (static_cast<std::uint64_t>(descriptor.contributorOffsetWords) << 32U);
  beat[3] = static_cast<std::uint64_t>(descriptor.contributorWordCount) |
      (static_cast<std::uint64_t>(descriptor.activeRowCount) << 32U);
  return beat;
}

CuperflowBatchDescriptor unpackBatchDescriptor(const CuperflowVectorBeat& beat) {
  if (!isBatchDescriptorMarker(beat[0])) {
    throw std::invalid_argument("Cuperflow X beat 不是 BATCH_DESC");
  }
  for (std::size_t lane = 4; lane < beat.size(); ++lane) {
    if (beat[lane] != 0U) {
      throw std::invalid_argument("Cuperflow BATCH_DESC 的 reserved 位非零");
    }
  }
  CuperflowBatchDescriptor descriptor;
  descriptor.batchId = static_cast<std::uint32_t>(beat[1]);
  descriptor.aOffsetBeats = static_cast<std::uint32_t>(beat[1] >> 32U);
  descriptor.aBeats = static_cast<std::uint32_t>(beat[2]);
  descriptor.contributorOffsetWords = static_cast<std::uint32_t>(beat[2] >> 32U);
  descriptor.contributorWordCount = static_cast<std::uint32_t>(beat[3]);
  descriptor.activeRowCount = static_cast<std::uint32_t>(beat[3] >> 32U);
  descriptor.lastBatchInGroup = (beat[0] & kBatchDescriptorMarkerLastMask) != 0U;
  validateBatchDescriptor(descriptor);
  return descriptor;
}

std::size_t slotsPerChunk(CuperflowChunkMode mode) {
  switch (mode) {
    case CuperflowChunkMode::Full8: return 8;
    case CuperflowChunkMode::Two4: return 4;
    case CuperflowChunkMode::Four2: return 2;
  }
  throw std::invalid_argument("Cuperflow slot 的 chunkMode=11 非法");
}

namespace {

constexpr std::size_t kCheckerCount = 8;
constexpr std::uint64_t kColumnMask = (1ULL << kColumnBits) - 1ULL;
constexpr std::uint64_t kTagMask = (1ULL << kTagBits) - 1ULL;
constexpr std::uint64_t kRowMask = (1ULL << kRowBits) - 1ULL;
constexpr std::uint64_t kRowLastMask = std::uint64_t{1} << 47U;
constexpr std::uint64_t kChunkModeMask = std::uint64_t{0x3} << 45U;

struct RawElement {
  // slot.localRow 编码该 physicalRow 在当前 row batch 内的偏移。
  std::size_t physicalRow = 0;
  std::uint32_t column = 0;
  float value = 0.0F;
};

struct ScheduledSlot {
  bool occupied = false;
  RawElement element;
};

bool isCanonicalZero(double value) {
  // 仅 canonicalize CSR 中显式写入的 +0/-0。非零 double 即使转换为 FP32 后下溢，
  // 仍是数学输入的一部分，不能被误当作 padding 删除。
  return value == 0.0;
}

std::size_t divideRoundedUp(std::size_t value, std::size_t divisor) {
  return value / divisor + static_cast<std::size_t>(value % divisor != 0);
}

void validateConfig(const CuperflowConfig& config) {
  if (config.hbmChannelCount == 0 || config.hbmChannelCount > 16) {
    throw std::invalid_argument("Cuperflow V0 HBM channel 数必须位于 1..16");
  }
  if (config.sliceSize == 0 || config.rowBatchSize == 0 || config.xSlicesPerBatch == 0) {
    throw std::invalid_argument(
        "Cuperflow sliceSize、rowBatchSize 和 xSlicesPerBatch 必须大于 0");
  }
  if (config.rowBatchSize > kRowMask + 1U) {
    throw std::invalid_argument(
        "Cuperflow rowBatchSize 必须能由 slot v6 的 13-bit batch-local 行标表示");
  }
  if (config.sliceSize > kMaxXRangeElements / config.xSlicesPerBatch) {
    throw std::invalid_argument("Cuperflow X range 最大长度不能超过 8192 个元素");
  }
  if (config.sliceGroupSize != 0 && config.sliceGroupSize > config.xSlicesPerBatch) {
    throw std::invalid_argument("Cuperflow sliceGroupSize 不能超过 X range 的最大 slice 数");
  }
  if (config.reorderWindow != 0) {
    throw std::invalid_argument("Cuperflow 不支持插入 reorder padding，reorderWindow 必须为 0");
  }
  if (config.aPacking == CuperflowAPacking::RowRoundRobin &&
      config.tailPacking == CuperflowTailPacking::Compact421) {
    throw std::invalid_argument(
        "Cuperflow slot v6 不支持 Compact421；row-round-robin 必须使用 8/4/2 chunk");
  }
  if (config.sliceSize > std::numeric_limits<std::size_t>::max() /
          config.xSlicesPerBatch) {
    throw std::overflow_error("Cuperflow X batch 宽度溢出");
  }
  if (config.sliceSize > (1ULL << kColumnBits)) {
    throw std::invalid_argument("Cuperflow column slice 宽度超过 13-bit 局部列号范围");
  }
  if (config.hbmChannelCount > std::numeric_limits<std::size_t>::max() /
          kLanesPerBeat) {
    throw std::overflow_error("Cuperflow PE 数量溢出");
  }
}

std::size_t rowGroupSpan(const CuperflowConfig& config) {
  const std::size_t totalPes = totalPeCount(config);
  if (totalPes > std::numeric_limits<std::size_t>::max() / 2U) {
    throw std::overflow_error("Cuperflow row group 跨度溢出");
  }
  return 2U * totalPes;
}

std::size_t localRowForRowUnchecked(std::size_t row, const CuperflowConfig& config) {
  const std::size_t rowGroup = row / rowGroupSpan(config);
  if (rowGroup > std::numeric_limits<std::size_t>::max() / 2U) {
    throw std::overflow_error("Cuperflow PE-local 行标溢出");
  }
  return 2U * rowGroup + row % 2U;
}

std::size_t peForRowUnchecked(std::size_t row, const CuperflowConfig& config);

/** 保持 8/16 PC 的历史 checker 顺序，并为中间几何覆盖每一个 PC。
  *
  * 8 PC 是 0..7；16 PC 仍是 0,2,...,14,1,3,...,15。9..15 PC 使用同一偶/奇
  * permutation 的截断，避免旧的 `channelCount / 8 == 0` 除零和无法覆盖尾 PC。
  */
std::size_t channelForPacket(std::size_t packet, const CuperflowConfig& config) {
  const std::size_t channelCount = config.hbmChannelCount;
  const std::size_t position = packet % channelCount;
  if (channelCount <= kCheckerCount) {
    return position;
  }
  const std::size_t evenCount = (channelCount + 1U) / 2U;
  return position < evenCount ? position * 2U : (position - evenCount) * 2U + 1U;
}

std::size_t packetPositionForChannel(std::size_t channel, const CuperflowConfig& config) {
  if (config.hbmChannelCount <= kCheckerCount) {
    return channel;
  }
  const std::size_t evenCount = (config.hbmChannelCount + 1U) / 2U;
  return (channel & 1U) == 0U ? channel / 2U : evenCount + channel / 2U;
}

void validateMatrix(const CsrMatrix& matrix) {
  if (matrix.rows == std::numeric_limits<std::size_t>::max() ||
      matrix.rowPointers.size() != matrix.rows + 1U) {
    throw std::invalid_argument("CSR row pointer 数量必须等于 rows + 1");
  }
  if (matrix.rowPointers.empty() || matrix.rowPointers.front() != 0) {
    throw std::invalid_argument("CSR rowPointers[0] 必须为 0");
  }
  if (matrix.columnIndices.size() != matrix.values.size()) {
    throw std::invalid_argument("CSR column/value 数量不一致");
  }
  if (matrix.rowPointers.back() != matrix.columnIndices.size()) {
    throw std::invalid_argument("CSR 最后一个 row pointer 必须等于 nnz");
  }
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    if (matrix.rowPointers[row] > matrix.rowPointers[row + 1U]) {
      throw std::invalid_argument("CSR rowPointers 必须单调不减");
    }
  }
  for (std::uint32_t column : matrix.columnIndices) {
    if (column >= matrix.columns) {
      throw std::invalid_argument("CSR column index 超出矩阵列范围");
    }
  }
}

std::vector<std::size_t> buildRowPermutation(const CsrMatrix& matrix,
                                             const CuperflowConfig& config) {
  std::vector<std::size_t> physicalToOriginalRows(matrix.rows);
  std::iota(physicalToOriginalRows.begin(), physicalToOriginalRows.end(), 0);
  if (!config.rowReorder || matrix.rows == 0) {
    return physicalToOriginalRows;
  }

  const std::size_t batchCount = divideRoundedUp(matrix.rows, config.rowBatchSize);
  const std::size_t totalPes = totalPeCount(config);
  for (std::size_t batch = 0; batch < batchCount; ++batch) {
    const std::size_t firstRow = batch * config.rowBatchSize;
    const std::size_t lastRow = std::min(firstRow + config.rowBatchSize, matrix.rows);

    std::vector<std::vector<std::size_t>> physicalRowsByPe(totalPes);
    for (std::size_t physicalRow = firstRow; physicalRow < lastRow; ++physicalRow) {
      physicalRowsByPe[peForRowUnchecked(physicalRow, config)].push_back(physicalRow);
    }

    std::vector<std::size_t> originalRows(lastRow - firstRow);
    std::iota(originalRows.begin(), originalRows.end(), firstRow);
    std::stable_sort(originalRows.begin(), originalRows.end(),
                     [&matrix](std::size_t lhs, std::size_t rhs) {
                       const std::uint64_t lhsNnz = matrix.rowPointers[lhs + 1U] -
                           matrix.rowPointers[lhs];
                       const std::uint64_t rhsNnz = matrix.rowPointers[rhs + 1U] -
                           matrix.rowPointers[rhs];
                       return lhsNnz > rhsNnz;
                     });

    std::vector<std::uint64_t> peLoads(totalPes, 0);
    std::vector<std::size_t> nextPhysicalRow(totalPes, 0);
    for (const std::size_t originalRow : originalRows) {
      std::size_t selectedPe = totalPes;
      for (std::size_t pe = 0; pe < totalPes; ++pe) {
        if (nextPhysicalRow[pe] == physicalRowsByPe[pe].size()) {
          continue;
        }
        if (selectedPe == totalPes || peLoads[pe] < peLoads[selectedPe] ||
            (peLoads[pe] == peLoads[selectedPe] && pe < selectedPe)) {
          selectedPe = pe;
        }
      }
      if (selectedPe == totalPes) {
        throw std::logic_error("Cuperflow row 重排没有可用的物理 row 槽");
      }
      const std::size_t physicalRow =
          physicalRowsByPe[selectedPe][nextPhysicalRow[selectedPe]++];
      physicalToOriginalRows[physicalRow] = originalRow;
      const std::uint64_t rowNnz = matrix.rowPointers[originalRow + 1U] -
          matrix.rowPointers[originalRow];
      if (peLoads[selectedPe] > std::numeric_limits<std::uint64_t>::max() - rowNnz) {
        throw std::overflow_error("Cuperflow row 重排的 PE 负载溢出");
      }
      peLoads[selectedPe] += rowNnz;
    }
  }
  return physicalToOriginalRows;
}

std::vector<CuperflowXSegment> buildXSegments(
    const std::vector<std::uint32_t>& usedColumns, std::size_t groupFirstColumn,
    std::size_t groupElements) {
  if (usedColumns.empty()) {
    return {};
  }
  if (!kFlexibleXEncodingEnabled) {
    return {CuperflowXSegment{0, static_cast<std::uint16_t>(groupElements)}};
  }

  std::vector<CuperflowXSegment> segments;
  std::size_t runFirst = usedColumns.front();
  std::size_t previous = runFirst;
  const auto appendRun = [&segments, groupFirstColumn](std::size_t first, std::size_t last) {
    const std::size_t start = first - groupFirstColumn;
    const std::size_t count = last - first + 1U;
    if (start > kXAddressMarkerAddressMask || count > kMaxXRangeElements) {
      throw std::overflow_error("Cuperflow X 连续段超出 map descriptor 范围");
    }
    segments.push_back(CuperflowXSegment{static_cast<std::uint16_t>(start),
                                         static_cast<std::uint16_t>(count)});
  };
  for (std::size_t index = 1; index < usedColumns.size(); ++index) {
    const std::size_t column = usedColumns[index];
    if (column != previous + 1U) {
      appendRun(runFirst, previous);
      runFirst = column;
    }
    previous = column;
  }
  appendRun(runFirst, previous);
  if (segments.size() <= kMaxXSegments) {
    return segments;
  }

  const std::size_t start = static_cast<std::size_t>(usedColumns.front()) - groupFirstColumn;
  const std::size_t count = static_cast<std::size_t>(usedColumns.back()) -
      static_cast<std::size_t>(usedColumns.front()) + 1U;
  if (start > kXAddressMarkerAddressMask || count > groupElements) {
    throw std::overflow_error("Cuperflow X 连续 span 回退超出 group 范围");
  }
  return {CuperflowXSegment{static_cast<std::uint16_t>(start),
                             static_cast<std::uint16_t>(count)}};
}

std::uint32_t segmentIdForColumn(const std::vector<CuperflowXSegment>& segments,
                                 std::size_t localColumn) {
  for (std::size_t index = 0; index < segments.size(); ++index) {
    const CuperflowXSegment& segment = segments[index];
    if (localColumn >= segment.start && localColumn - segment.start < segment.count) {
      return static_cast<std::uint32_t>(index);
    }
  }
  throw std::logic_error("Cuperflow A 列未落入已编码的 X 段");
}

std::uint64_t packSlot(const RawElement& element, std::uint32_t segmentId,
                       std::size_t groupFirstColumn, std::size_t batchFirstRow) {
  if (element.physicalRow < batchFirstRow) {
    throw std::logic_error("Cuperflow slot 的 physical row 早于当前 row batch");
  }
  const std::size_t localRow = element.physicalRow - batchFirstRow;
  if (localRow > kRowMask) {
    throw std::overflow_error("Cuperflow slot v6 的 batch-local 行标超过 13 bit");
  }
  if (segmentId >= kMaxXSegments) {
    throw std::overflow_error("Cuperflow X 段号超过 3 bit");
  }

  if (element.column < groupFirstColumn) {
    throw std::logic_error("Cuperflow slot 的列号早于所属 slice group");
  }
  const std::size_t localColumn = static_cast<std::size_t>(element.column) - groupFirstColumn;
  if (localColumn > kColumnMask) {
    throw std::overflow_error("Cuperflow 局部列号超过 13 bit");
  }

  std::uint32_t valueBits = 0;
  static_assert(sizeof(valueBits) == sizeof(element.value));
  std::memcpy(&valueBits, &element.value, sizeof(valueBits));
  return (static_cast<std::uint64_t>(localColumn) << 51U) |
      (static_cast<std::uint64_t>(segmentId) << 48U) |
      (static_cast<std::uint64_t>(localRow) << 32U) | valueBits;
}

std::uint64_t withChunkMetadata(std::uint64_t slot, CuperflowChunkMode mode, bool rowLast) {
  const std::uint64_t encodedMode = static_cast<std::uint64_t>(mode);
  if (encodedMode > static_cast<std::uint64_t>(CuperflowChunkMode::Four2)) {
    throw std::invalid_argument("Cuperflow slot 的 chunkMode=11 非法");
  }
  return (slot & ~(kRowLastMask | kChunkModeMask)) |
      (rowLast ? kRowLastMask : 0U) | (encodedMode << 45U);
}

std::size_t peForRowUnchecked(std::size_t row, const CuperflowConfig& config) {
  const std::size_t packet = row / 2U;
  const std::size_t channel = channelForPacket(packet, config);
  const std::size_t peInAccumulator = (packet / config.hbmChannelCount) % kLanesPerBeat;
  return channel * kLanesPerBeat + peInAccumulator;
}

}  // namespace

double CuperflowEncodingStats::matrixSlotUtilization() const {
  const std::uint64_t slots = matrixSlots + zeroFillSlots;
  return slots == 0 ? 0.0 : static_cast<double>(matrixSlots) / static_cast<double>(slots);
}

std::size_t columnsPerBatch(const CuperflowConfig& config) {
  return config.sliceSize * config.xSlicesPerBatch;
}

std::size_t rowBatchCount(std::size_t rows, const CuperflowConfig& config) {
  validateConfig(config);
  return divideRoundedUp(rows, config.rowBatchSize);
}

std::size_t columnSliceCount(std::size_t columns, const CuperflowConfig& config) {
  validateConfig(config);
  return divideRoundedUp(columns, config.sliceSize);
}

std::size_t effectiveSliceGroupSize(const CuperflowConfig& config) {
  validateConfig(config);
  return config.sliceGroupSize == 0 ? config.hbmChannelCount : config.sliceGroupSize;
}

std::size_t effectiveSliceGroupSize(std::size_t columnSliceCount,
                                    const CuperflowConfig& config) {
  validateConfig(config);
  if (config.sliceGroupSize != 0 || columnSliceCount == 0) {
    return config.sliceGroupSize == 0 ? 1U : config.sliceGroupSize;
  }
  const std::size_t balancedSize = columnSliceCount / config.hbmChannelCount;
  return std::min(config.xSlicesPerBatch, std::max<std::size_t>(1U, balancedSize));
}

std::size_t sliceGroupCount(std::size_t columns, const CuperflowConfig& config) {
  const std::size_t slices = columnSliceCount(columns, config);
  return divideRoundedUp(slices, effectiveSliceGroupSize(slices, config));
}

std::size_t totalPeCount(const CuperflowConfig& config) {
  return config.hbmChannelCount * kLanesPerBeat;
}

std::size_t peForRow(std::size_t row, const CuperflowConfig& config) {
  validateConfig(config);
  return peForRowUnchecked(row, config);
}

std::size_t localRowForRow(std::size_t row, const CuperflowConfig& config) {
  validateConfig(config);
  const std::size_t localRow = localRowForRowUnchecked(row, config);
  if (localRow > kRowMask) {
    throw std::out_of_range("Cuperflow slot v6 的 PE-local 行标超过 13 bit");
  }
  return localRow;
}

std::size_t rowForPeLocal(std::size_t physicalPe, std::size_t localRow,
                          const CuperflowConfig& config) {
  validateConfig(config);
  const std::size_t totalPes = totalPeCount(config);
  if (physicalPe >= totalPes) {
    throw std::out_of_range("Cuperflow 物理 PE 超出配置范围");
  }
  if (localRow > kRowMask) {
    throw std::out_of_range("Cuperflow PE-local 行标超过 13 bit");
  }

  const std::size_t channel = physicalPe / kLanesPerBeat;
  const std::size_t peInAccumulator = physicalPe % kLanesPerBeat;
  const std::size_t logicalPacket = packetPositionForChannel(channel, config) +
      config.hbmChannelCount * peInAccumulator;
  const std::size_t rowGroup = localRow / 2U;
  if (rowGroup > (std::numeric_limits<std::size_t>::max() - logicalPacket) / totalPes) {
    throw std::overflow_error("Cuperflow 全局行标反解溢出");
  }
  const std::size_t packet = rowGroup * totalPes + logicalPacket;
  if (packet > (std::numeric_limits<std::size_t>::max() - localRow % 2U) / 2U) {
    throw std::overflow_error("Cuperflow 全局行标反解溢出");
  }
  return 2U * packet + localRow % 2U;
}

std::size_t physicalRowForBatchLocal(std::size_t batch, std::size_t localRow,
                                     const CuperflowConfig& config) {
  validateConfig(config);
  if (localRow >= config.rowBatchSize ||
      batch > (std::numeric_limits<std::size_t>::max() - localRow) / config.rowBatchSize) {
    throw std::out_of_range("Cuperflow batch-local 行标超出配置范围");
  }
  return batch * config.rowBatchSize + localRow;
}

DecodedCuperflowSlot decodeSlot(std::uint64_t slot) {
  DecodedCuperflowSlot decoded;
  decoded.localColumn = static_cast<std::uint32_t>((slot >> 51U) & kColumnMask);
  decoded.segmentId = static_cast<std::uint32_t>((slot >> 48U) & kTagMask);
  decoded.rowLast = (slot & kRowLastMask) != 0U;
  const std::uint32_t encodedMode = static_cast<std::uint32_t>((slot >> 45U) & 0x3U);
  if (encodedMode == 0b11U) {
    throw std::invalid_argument("Cuperflow slot 的 chunkMode=11 非法");
  }
  decoded.chunkMode = static_cast<CuperflowChunkMode>(encodedMode);
  decoded.localRow = static_cast<std::uint32_t>((slot >> 32U) & kRowMask);
  const std::uint32_t valueBits = static_cast<std::uint32_t>(slot);
  static_assert(sizeof(valueBits) == sizeof(decoded.value));
  std::memcpy(&decoded.value, &valueBits, sizeof(valueBits));
  return decoded;
}

namespace {

struct CuperflowChunkEvent {
  std::size_t wave = 0;
  std::size_t batch = 0;
  std::uint32_t localRow = 0;
  bool rowLast = false;
};

struct CuperflowL1Analysis {
  std::vector<CuperflowPcL1Stats> pcStats;
  std::vector<CuperflowWaveBatchL1Stats> waveBatchStats;
  std::array<std::uint64_t, kLanesPerBeat * 2U + 1U> contributorPopcountHistogram{};
  std::uint64_t chunkInterBeatDistanceCount = 0;
  std::uint64_t chunkInterBeatDistanceTotal = 0;
  std::uint64_t chunkInterBeatDistanceMinimum = 0;
  std::uint64_t chunkInterBeatDistanceMaximum = 0;
  std::uint64_t chunkInterBeatDistanceBelowFaddLatency = 0;
  std::uint64_t completionRobPeak = 0;
  std::uint64_t xPayloadLoadCount = 0;
  std::uint64_t expectedXPayloadLoadCount = 0;
};

std::uint64_t analysisRowKey(std::size_t batch, std::uint32_t localRow) {
  if (batch > (std::numeric_limits<std::uint64_t>::max() >> kRowBits)) {
    throw std::overflow_error("Cuperflow L1 分析 batch/row key 溢出");
  }
  return (static_cast<std::uint64_t>(batch) << kRowBits) | localRow;
}

std::uint64_t analysisCompletionKey(std::size_t wave, std::size_t batch,
                                    std::uint32_t localRow) {
  constexpr std::uint32_t kWaveShift = 48;
  if (wave > ((std::uint64_t{1} << (64U - kWaveShift)) - 1U) ||
      batch > ((std::uint64_t{1} << (kWaveShift - kRowBits)) - 1U)) {
    throw std::overflow_error("Cuperflow L1 completion key 溢出");
  }
  return (static_cast<std::uint64_t>(wave) << kWaveShift) |
      (static_cast<std::uint64_t>(batch) << kRowBits) | localRow;
}

std::size_t activeRowsInBitmap(const std::vector<std::uint64_t>& words,
                               std::size_t activeRows) {
  std::size_t count = 0;
  for (std::size_t row = 0; row < activeRows; ++row) {
    count += static_cast<std::size_t>((words[row / 64U] >> (row % 64U)) & 1U);
  }
  return count;
}

std::vector<bool> groupsWithAPayload(const CuperflowPackage& package) {
  if (package.channelGroupARanges.size() != package.config.hbmChannelCount ||
      package.sliceGroupChannels.size() != package.sliceGroupCount) {
    throw std::invalid_argument("Cuperflow L1 分析缺少 per-PC group A 区间");
  }
  std::vector<bool> groups(package.sliceGroupCount, false);
  for (std::size_t channel = 0; channel < package.channelGroupARanges.size(); ++channel) {
    for (const CuperflowGroupARange& range : package.channelGroupARanges[channel]) {
      if (range.sliceGroup >= groups.size() || groups[range.sliceGroup] || range.aBeats == 0U ||
          package.sliceGroupChannels[range.sliceGroup] != channel) {
        throw std::invalid_argument("Cuperflow L1 分析发现非法或重复的非空 A group");
      }
      groups[range.sliceGroup] = true;
    }
  }
  return groups;
}

CuperflowL1Analysis analyzeL1(const CuperflowPackage& package) {
  const std::size_t channelCount = package.config.hbmChannelCount;
  const std::size_t batchCount = package.stats.batchCount;
  const std::size_t waveCount = package.contributorWaveCount;
  CuperflowL1Analysis analysis;
  analysis.pcStats.resize(channelCount);
  analysis.waveBatchStats.resize(batchCount * waveCount);
  std::vector<std::vector<std::vector<CuperflowChunkEvent>>> events(channelCount);

  for (std::size_t channel = 0; channel < channelCount; ++channel) {
    CuperflowPcL1Stats& pc = analysis.pcStats[channel];
    events[channel].resize(package.matrixChannels[channel].size());
    for (const std::uint8_t mask : package.matrixEntryMasks[channel]) {
      pc.effectiveSlots += static_cast<std::uint64_t>(__builtin_popcount(mask));
    }
    for (const std::size_t group : package.channelSliceGroups[channel]) {
      const std::uint32_t descriptorOffset =
          package.channelGroupDescriptorOffsets[channel][group];
      if (descriptorOffset == std::numeric_limits<std::uint32_t>::max()) {
        throw std::invalid_argument("Cuperflow L1 分析发现 owner group 缺少 BATCH_DESC");
      }
      for (std::size_t batch = 0; batch < batchCount; ++batch) {
        const CuperflowBatchDescriptor& descriptor =
            package.channelBatchDescriptors[channel][descriptorOffset + batch];
        pc.aBeats += descriptor.aBeats;
        pc.emptyBatches += descriptor.aBeats == 0U ? 1U : 0U;
        const std::vector<std::uint64_t> bitmap(
            package.channelContributorWords[channel].begin() + descriptor.contributorOffsetWords,
            package.channelContributorWords[channel].begin() + descriptor.contributorOffsetWords +
                descriptor.contributorWordCount);
        pc.activeRows += activeRowsInBitmap(bitmap, descriptor.activeRowCount);

        const std::size_t groupSegment = batch * package.sliceGroupCount + group;
        const auto range = package.channelLaneSliceGroupRanges[channel][groupSegment][0];
        const std::size_t chunkWave = group / channelCount;
        for (std::size_t beat = range.first; beat < range.second; ++beat) {
          const std::uint8_t entryMask = package.matrixEntryMasks[channel][beat];
          if (entryMask == 0U) {
            continue;
          }
          const DecodedCuperflowSlot first = decodeSlot(
              package.matrixChannels[channel][beat][static_cast<std::size_t>(__builtin_ctz(entryMask))]);
          const std::size_t chunkWidth = slotsPerChunk(first.chunkMode);
          for (std::size_t chunkStart = 0; chunkStart < kLanesPerBeat;
               chunkStart += chunkWidth) {
            const std::uint8_t chunkMask = static_cast<std::uint8_t>(
                ((std::uint32_t{1} << chunkWidth) - 1U) << chunkStart);
            const std::uint8_t valid = entryMask & chunkMask;
            if (valid == 0U) {
              continue;
            }
            const std::size_t firstLane = static_cast<std::size_t>(__builtin_ctz(valid));
            const DecodedCuperflowSlot slot = decodeSlot(package.matrixChannels[channel][beat][firstLane]);
            for (std::size_t lane = chunkStart; lane < chunkStart + chunkWidth; ++lane) {
              if ((valid & (std::uint8_t{1} << lane)) == 0U) {
                continue;
              }
              const DecodedCuperflowSlot peer = decodeSlot(package.matrixChannels[channel][beat][lane]);
              if (peer.localRow != slot.localRow || peer.rowLast != slot.rowLast ||
                  peer.chunkMode != slot.chunkMode) {
                throw std::invalid_argument("Cuperflow L1 分析发现同一 chunk 的控制字段不一致");
              }
            }
            events[channel][beat].push_back(CuperflowChunkEvent{
                chunkWave, batch, slot.localRow, slot.rowLast});
          }
        }
      }
    }
  }

  for (std::size_t batch = 0; batch < batchCount; ++batch) {
    for (std::size_t wave = 0; wave < waveCount; ++wave) {
      CuperflowWaveBatchL1Stats& waveStats =
          analysis.waveBatchStats[batch * waveCount + wave];
      const std::vector<std::uint16_t>& masks =
          package.contributorMasksByWaveBatch[batch * waveCount + wave];
      std::uint64_t minimumProgress = std::numeric_limits<std::uint64_t>::max();
      std::uint64_t maximumProgress = 0;
      for (std::size_t channel = 0; channel < channelCount; ++channel) {
        const std::size_t group = wave * channelCount + channel;
        std::uint64_t progress = 0;
        if (group < package.sliceGroupCount) {
          const std::uint32_t descriptorOffset =
              package.channelGroupDescriptorOffsets[channel][group];
          if (descriptorOffset == std::numeric_limits<std::uint32_t>::max()) {
            throw std::invalid_argument("Cuperflow L1 分析发现 wave group 缺少 descriptor");
          }
          progress = package.channelBatchDescriptors[channel][descriptorOffset + batch].aBeats;
        }
        minimumProgress = std::min(minimumProgress, progress);
        maximumProgress = std::max(maximumProgress, progress);
      }
      waveStats.maxPcProgressGap = maximumProgress - minimumProgress;
      for (const std::uint16_t mask : masks) {
        const std::size_t popcount = static_cast<std::size_t>(__builtin_popcount(mask));
        if (popcount >= analysis.contributorPopcountHistogram.size()) {
          throw std::invalid_argument("Cuperflow contributor mask 超过 16 个 PC");
        }
        ++analysis.contributorPopcountHistogram[popcount];
        waveStats.activeRows += mask != 0U ? 1U : 0U;
      }
    }
  }

  for (std::size_t channel = 0; channel < channelCount; ++channel) {
    std::unordered_map<std::uint64_t, std::size_t> lastBeat;
    for (std::size_t beat = 0; beat < events[channel].size(); ++beat) {
      for (const CuperflowChunkEvent& event : events[channel][beat]) {
        const std::uint64_t key = analysisRowKey(event.batch, event.localRow);
        const auto previous = lastBeat.find(key);
        if (previous != lastBeat.end()) {
          const std::uint64_t distance = beat - previous->second;
          ++analysis.chunkInterBeatDistanceCount;
          analysis.chunkInterBeatDistanceTotal += distance;
          analysis.chunkInterBeatDistanceMinimum =
              analysis.chunkInterBeatDistanceCount == 1U ? distance :
              std::min(analysis.chunkInterBeatDistanceMinimum, distance);
          analysis.chunkInterBeatDistanceMaximum =
              std::max(analysis.chunkInterBeatDistanceMaximum, distance);
          analysis.chunkInterBeatDistanceBelowFaddLatency +=
              distance < kAnalysisCandidateFaddLatency ? 1U : 0U;
        }
        lastBeat[key] = beat;
      }
    }
  }

  std::size_t maximumBeats = 0;
  for (const auto& pcEvents : events) {
    maximumBeats = std::max(maximumBeats, pcEvents.size());
  }
  std::unordered_map<std::uint64_t, std::uint16_t> completionMasks;
  for (std::size_t beat = 0; beat < maximumBeats; ++beat) {
    for (std::size_t channel = 0; channel < channelCount; ++channel) {
      if (beat >= events[channel].size()) {
        continue;
      }
      for (const CuperflowChunkEvent& event : events[channel][beat]) {
        if (!event.rowLast) {
          continue;
        }
        const std::vector<std::uint16_t>& expectedRows = package.contributorMasksByWaveBatch[
            event.batch * waveCount + event.wave];
        if (event.localRow >= expectedRows.size()) {
          throw std::invalid_argument("Cuperflow completion ROB 回放遇到越界 localRow");
        }
        const std::uint16_t expected = expectedRows[event.localRow];
        const std::uint16_t channelBit = static_cast<std::uint16_t>(std::uint16_t{1} << channel);
        if ((expected & channelBit) == 0U) {
          throw std::invalid_argument("Cuperflow completion ROB 回放发现无 contributor 的 rowLast");
        }
        const std::uint64_t key = analysisCompletionKey(event.wave, event.batch, event.localRow);
        auto [entry, inserted] = completionMasks.try_emplace(key, 0U);
        if (!inserted && (entry->second & channelBit) != 0U) {
          throw std::invalid_argument("Cuperflow completion ROB 回放发现重复 rowLast");
        }
        entry->second |= channelBit;
        analysis.completionRobPeak = std::max<std::uint64_t>(
            analysis.completionRobPeak, completionMasks.size());
        if (entry->second == expected) {
          completionMasks.erase(entry);
        }
      }
    }
  }
  if (!completionMasks.empty()) {
    throw std::invalid_argument("Cuperflow completion ROB 回放结束时仍有未完成行");
  }

  const std::vector<bool> payloadGroups = groupsWithAPayload(package);
  analysis.xPayloadLoadCount = static_cast<std::uint64_t>(
      std::count(payloadGroups.begin(), payloadGroups.end(), true));
  analysis.expectedXPayloadLoadCount = analysis.xPayloadLoadCount;
  return analysis;
}

void storeL1Analysis(CuperflowPackage& package, const CuperflowL1Analysis& analysis) {
  package.pcL1Stats = analysis.pcStats;
  package.waveBatchL1Stats = analysis.waveBatchStats;
  package.stats.contributorPopcountHistogram = analysis.contributorPopcountHistogram;
  package.stats.chunkInterBeatDistanceCount = analysis.chunkInterBeatDistanceCount;
  package.stats.chunkInterBeatDistanceTotal = analysis.chunkInterBeatDistanceTotal;
  package.stats.chunkInterBeatDistanceMinimum = analysis.chunkInterBeatDistanceMinimum;
  package.stats.chunkInterBeatDistanceMaximum = analysis.chunkInterBeatDistanceMaximum;
  package.stats.chunkInterBeatDistanceBelowFaddLatency =
      analysis.chunkInterBeatDistanceBelowFaddLatency;
  package.stats.candidateFaddLatency = kAnalysisCandidateFaddLatency;
  package.stats.completionRobPeak = analysis.completionRobPeak;
  package.stats.xPayloadLoadCount = analysis.xPayloadLoadCount;
  package.stats.expectedXPayloadLoadCount = analysis.expectedXPayloadLoadCount;
}

}  // namespace

CuperflowPackage encode(const CsrMatrix& matrix, const CuperflowConfig& config) {
  validateConfig(config);
  validateMatrix(matrix);

  const std::size_t batchCount = rowBatchCount(matrix.rows, config);
  const std::size_t sliceCount = columnSliceCount(matrix.columns, config);
  const std::size_t groupSize = effectiveSliceGroupSize(sliceCount, config);
  const std::size_t groupCount = divideRoundedUp(sliceCount, groupSize);
  if (sliceCount >= config.hbmChannelCount && groupCount < config.hbmChannelCount) {
    throw std::invalid_argument(
        "Cuperflow sliceGroup 必须为每个 HBM 保留不同的 X range");
  }
  const std::size_t totalPes = totalPeCount(config);
  const std::size_t rowGroupSpanValue = rowGroupSpan(config);

  if (groupCount != 0 && batchCount >
      std::numeric_limits<std::size_t>::max() / groupCount) {
    throw std::overflow_error("Cuperflow row batch-slice group 数量溢出");
  }
  const std::size_t groupSegmentCount = batchCount * groupCount;
  const std::size_t contributorWaveCount =
      divideRoundedUp(groupCount, config.hbmChannelCount);

  std::vector<std::size_t> sliceGroupChannels(groupCount);
  std::vector<std::vector<std::size_t>> channelSliceGroups(config.hbmChannelCount);
  std::vector<std::vector<std::uint32_t>> xUsedColumnsByGroup(groupCount);
  for (std::size_t group = 0; group < groupCount; ++group) {
    const std::size_t channel = group % config.hbmChannelCount;
    sliceGroupChannels[group] = channel;
    channelSliceGroups[channel].push_back(group);
  }
  // X 段计划必须在 A slot 写入前完成，因为 slot[50:48] 现在是 segmentId。它只
  // 依赖 CSR 列集合，和后续 row 重排、lane 调度无关。
  for (std::size_t index = 0; index < matrix.columnIndices.size(); ++index) {
    if (isCanonicalZero(matrix.values[index])) {
      continue;
    }
    const std::uint32_t column = matrix.columnIndices[index];
    const std::size_t slice = static_cast<std::size_t>(column) / config.sliceSize;
    xUsedColumnsByGroup[slice / groupSize].push_back(column);
  }
  std::vector<std::vector<CuperflowXSegment>> xSegmentsByGroup(groupCount);
  for (std::size_t group = 0; group < groupCount; ++group) {
    std::vector<std::uint32_t>& columns = xUsedColumnsByGroup[group];
    std::sort(columns.begin(), columns.end());
    columns.erase(std::unique(columns.begin(), columns.end()), columns.end());
    const std::size_t groupFirstColumn = group * groupSize * config.sliceSize;
    const std::size_t groupElements = std::min(
        groupSize * config.sliceSize, matrix.columns - groupFirstColumn);
    xSegmentsByGroup[group] = buildXSegments(columns, groupFirstColumn, groupElements);
  }

  CuperflowBeat zeroFillBeat{};
  zeroFillBeat.fill(kZeroFillSlot);
  std::vector<std::vector<CuperflowBeat>> channels(config.hbmChannelCount);
  std::vector<std::vector<std::uint8_t>> entryMasks(config.hbmChannelCount);
  std::vector<std::vector<std::uint32_t>> channelBatchPointers(
      config.hbmChannelCount, std::vector<std::uint32_t>(batchCount + 1U, 0));
  std::vector<std::vector<std::array<std::pair<std::uint32_t, std::uint32_t>, kLanesPerBeat>>>
      channelLaneSliceGroupRanges(
          config.hbmChannelCount,
          std::vector<std::array<std::pair<std::uint32_t, std::uint32_t>, kLanesPerBeat>>(
              groupSegmentCount));
  using GroupLaneWords = std::array<std::vector<std::uint64_t>, kLanesPerBeat>;
  std::vector<std::vector<GroupLaneWords>> batchGroupLanes(
      batchCount, std::vector<GroupLaneWords>(groupCount));
  using GroupBeatList = std::vector<CuperflowBeat>;
  std::vector<std::vector<GroupBeatList>> batchGroupBeats(
      batchCount, std::vector<GroupBeatList>(groupCount));
  std::vector<std::vector<std::vector<std::uint8_t>>> batchGroupEntryMasks(
      batchCount, std::vector<std::vector<std::uint8_t>>(groupCount));
  using GroupChunkModes = std::vector<CuperflowChunkMode>;
  std::vector<std::vector<GroupChunkModes>> batchGroupChunkModes(
      batchCount, std::vector<GroupChunkModes>(groupCount));
  std::vector<std::vector<std::vector<std::uint64_t>>> batchGroupActiveRows(
      batchCount, std::vector<std::vector<std::uint64_t>>(groupCount));
  std::uint64_t matrixSlots = 0;
  std::uint64_t droppedExplicitZeros = 0;
  for (double value : matrix.values) {
    droppedExplicitZeros += isCanonicalZero(value) ? 1U : 0U;
  }
  std::uint64_t full8ChunkCount = 0;
  std::uint64_t two4ChunkCount = 0;
  std::uint64_t four2ChunkCount = 0;
  std::uint64_t rowPartial1BeatCount = 0;
  std::uint64_t rowPartial2BeatCount = 0;
  std::uint64_t rowPartial4BeatCount = 0;
  const std::vector<std::size_t> physicalToOriginalRows =
      buildRowPermutation(matrix, config);
  for (std::size_t batch = 0; batch < batchCount; ++batch) {
    const std::size_t firstRow = batch * config.rowBatchSize;
    const std::size_t lastRow = std::min(firstRow + config.rowBatchSize, matrix.rows);

    if (config.aPacking == CuperflowAPacking::RowRoundRobin) {
      // 不按 PE/lane 拆流。完整行块按 round -> row 的顺序构造；短行尾部再按
      // 配置决定短行尾部如何切为不跨行的子块，并在同宽桶内依次拼满 8-slot beat。
      // 默认 pad3-1 给后续行归约保留 8/4/2 的确定边界；候选 compact 则用于衡量
      // 不在子块内部填充时的吞吐上限。
      using GroupRows = std::unordered_map<std::size_t, std::vector<RawElement>>;
      std::vector<GroupRows> rowsByGroup(groupCount);
      for (std::size_t physicalRow = firstRow; physicalRow < lastRow; ++physicalRow) {
        const std::size_t originalRow = physicalToOriginalRows[physicalRow];
        const std::size_t begin = static_cast<std::size_t>(matrix.rowPointers[originalRow]);
        const std::size_t end = static_cast<std::size_t>(matrix.rowPointers[originalRow + 1U]);
        const std::size_t localRow = physicalRow - firstRow;
        for (std::size_t index = begin; index < end; ++index) {
          if (isCanonicalZero(matrix.values[index])) {
            continue;
          }
          const std::uint32_t column = matrix.columnIndices[index];
          const std::size_t slice = static_cast<std::size_t>(column) / config.sliceSize;
          const std::size_t group = slice / groupSize;
          rowsByGroup[group][localRow].push_back(RawElement{
              physicalRow, column, static_cast<float>(matrix.values[index])});
        }
      }

      for (std::size_t group = 0; group < groupCount; ++group) {
        GroupRows& rows = rowsByGroup[group];
        std::size_t fullRounds = 0;
        for (auto& entry : rows) {
          std::vector<RawElement>& elements = entry.second;
          std::stable_sort(elements.begin(), elements.end(),
              [](const RawElement& lhs, const RawElement& rhs) {
                return lhs.column < rhs.column;
              });
          fullRounds = std::max(fullRounds, elements.size() / kLanesPerBeat);
        }

        const std::size_t groupFirstColumn = group * groupSize * config.sliceSize;
        GroupBeatList& beats = batchGroupBeats[batch][group];
        std::vector<std::uint8_t>& masks = batchGroupEntryMasks[batch][group];
        GroupChunkModes& chunkModes = batchGroupChunkModes[batch][group];
        const auto appendRowBlock = [&](CuperflowBeat& beat, std::uint8_t& mask,
                                        std::size_t laneStart,
                                        const std::vector<RawElement>& elements,
                                        std::size_t elementStart, std::size_t count) {
          if (laneStart > kLanesPerBeat || count > kLanesPerBeat - laneStart ||
              elementStart > elements.size() || count > elements.size() - elementStart) {
            throw std::logic_error("Cuperflow row tail 子块越界");
          }
          for (std::size_t offset = 0; offset < count; ++offset) {
            const RawElement& element = elements[elementStart + offset];
            const std::uint32_t segmentId = segmentIdForColumn(xSegmentsByGroup[group],
                static_cast<std::size_t>(element.column) - groupFirstColumn);
            const std::size_t lane = laneStart + offset;
            beat[lane] = packSlot(element, segmentId, groupFirstColumn, firstRow);
            mask |= static_cast<std::uint8_t>(1U << lane);
          }
        };

        for (std::size_t round = 0; round < fullRounds; ++round) {
          for (std::size_t localRow = 0; localRow < lastRow - firstRow; ++localRow) {
            const auto row = rows.find(localRow);
            if (row == rows.end()) {
              continue;
            }
            const std::size_t begin = round * kLanesPerBeat;
            if (begin + kLanesPerBeat > row->second.size()) {
              continue;
            }
            CuperflowBeat beat{};
            std::uint8_t mask = 0;
            appendRowBlock(beat, mask, 0, row->second, begin, kLanesPerBeat);
            beats.push_back(beat);
            masks.push_back(mask);
            chunkModes.push_back(CuperflowChunkMode::Full8);
          }
        }

        struct TailBlock {
          std::size_t elementBegin = 0;
          std::size_t validElements = 0;
          std::size_t width = 0;
        };
        const auto tailBlocksForRow = [&](const std::vector<RawElement>& elements) {
          std::array<TailBlock, 3> blocks{};
          std::size_t blockCount = 0;
          std::size_t tailRemaining = elements.size() % kLanesPerBeat;
          std::size_t tailBegin = elements.size() - tailRemaining;
          const auto appendTailBlock = [&](std::size_t validElements, std::size_t width) {
            if (validElements == 0 || validElements > width || width > kLanesPerBeat ||
                blockCount == blocks.size()) {
              throw std::logic_error("Cuperflow row tail 子块非法");
            }
            blocks[blockCount++] = TailBlock{tailBegin, validElements, width};
            tailBegin += validElements;
            tailRemaining -= validElements;
          };

          switch (config.tailPacking) {
            case CuperflowTailPacking::Compact421:
              for (const std::size_t width :
                   {std::size_t{4}, std::size_t{2}, std::size_t{1}}) {
                if (tailRemaining >= width) {
                  appendTailBlock(width, width);
                }
              }
              break;
            case CuperflowTailPacking::Pad3To4And1To2:
              if (tailRemaining >= 4) {
                appendTailBlock(4, 4);
              }
              if (tailRemaining == 3) {
                appendTailBlock(3, 4);
              } else if (tailRemaining == 2) {
                appendTailBlock(2, 2);
              } else if (tailRemaining == 1) {
                appendTailBlock(1, 2);
              }
              break;
            case CuperflowTailPacking::PadAllTo4:
              if (tailRemaining >= 4) {
                appendTailBlock(4, 4);
              }
              if (tailRemaining != 0) {
                appendTailBlock(tailRemaining, 4);
              }
              break;
            default:
              throw std::invalid_argument("Cuperflow 未知 row tail 打包策略");
          }
          return std::pair{blocks, blockCount};
        };

        for (const std::size_t width : {std::size_t{4}, std::size_t{2}, std::size_t{1}}) {
          CuperflowBeat tailBeat{};
          std::uint8_t tailMask = 0;
          std::size_t tailBeatFill = 0;
          for (std::size_t localRow = 0; localRow < lastRow - firstRow; ++localRow) {
            const auto row = rows.find(localRow);
            if (row == rows.end()) {
              continue;
            }
            const std::vector<RawElement>& elements = row->second;
            const auto [tailBlocks, blockCount] = tailBlocksForRow(elements);
            for (std::size_t block = 0; block < blockCount; ++block) {
              const TailBlock& tailBlock = tailBlocks[block];
              if (tailBlock.width != width) {
                continue;
              }
              appendRowBlock(tailBeat, tailMask, tailBeatFill, elements,
                             tailBlock.elementBegin, tailBlock.validElements);
              tailBeatFill += tailBlock.width;
              if (tailBeatFill == kLanesPerBeat) {
                beats.push_back(tailBeat);
                masks.push_back(tailMask);
                chunkModes.push_back(width == 4U ? CuperflowChunkMode::Two4 :
                                     CuperflowChunkMode::Four2);
                tailBeat = CuperflowBeat{};
                tailMask = 0;
                tailBeatFill = 0;
              }
            }
          }
          if (tailBeatFill != 0) {
            beats.push_back(tailBeat);
            masks.push_back(tailMask);
            chunkModes.push_back(width == 4U ? CuperflowChunkMode::Two4 :
                                 CuperflowChunkMode::Four2);
          }
        }

        // V0 的 2-slot beat 只允许产生 1、2 或 4 个 RowPartial。最后恰有三段
        // 时拆成 2 + 1 两拍，避免将一个不存在的第四段误报为有效 partial。
        GroupBeatList normalizedBeats;
        std::vector<std::uint8_t> normalizedMasks;
        GroupChunkModes normalizedModes;
        normalizedBeats.reserve(beats.size() + 1U);
        normalizedMasks.reserve(masks.size() + 1U);
        normalizedModes.reserve(chunkModes.size() + 1U);
        for (std::size_t beatIndex = 0; beatIndex < beats.size(); ++beatIndex) {
          const bool isTwoSlotBeat = chunkModes[beatIndex] == CuperflowChunkMode::Four2;
          const std::size_t partials = isTwoSlotBeat ?
              static_cast<std::size_t>(((masks[beatIndex] & 0x03U) != 0U) +
                  ((masks[beatIndex] & 0x0cU) != 0U) +
                  ((masks[beatIndex] & 0x30U) != 0U) +
                  ((masks[beatIndex] & 0xc0U) != 0U)) : 0U;
          if (!isTwoSlotBeat || partials != 3U) {
            normalizedBeats.push_back(beats[beatIndex]);
            normalizedMasks.push_back(masks[beatIndex]);
            normalizedModes.push_back(chunkModes[beatIndex]);
            continue;
          }
          CuperflowBeat firstBeat{};
          CuperflowBeat secondBeat{};
          const std::uint8_t firstMask = masks[beatIndex] & 0x0fU;
          const std::uint8_t secondMask = static_cast<std::uint8_t>(masks[beatIndex] >> 4U);
          for (std::size_t lane = 0; lane < 4; ++lane) {
            firstBeat[lane] = beats[beatIndex][lane];
            secondBeat[lane] = beats[beatIndex][lane + 4U];
          }
          normalizedBeats.push_back(firstBeat);
          normalizedMasks.push_back(firstMask);
          normalizedModes.push_back(CuperflowChunkMode::Four2);
          normalizedBeats.push_back(secondBeat);
          normalizedMasks.push_back(secondMask);
          normalizedModes.push_back(CuperflowChunkMode::Four2);
        }
        beats = std::move(normalizedBeats);
        masks = std::move(normalizedMasks);
        chunkModes = std::move(normalizedModes);

        if (beats.size() != masks.size() || beats.size() != chunkModes.size()) {
          throw std::logic_error("Cuperflow row-round-robin 的 beat、mask、chunkMode 长度不一致");
        }
        std::vector<std::uint64_t>& activeRows = batchGroupActiveRows[batch][group];
        activeRows.assign(divideRoundedUp(lastRow - firstRow, std::size_t{64}), 0U);
        std::vector<std::uint32_t> remainingRows(lastRow - firstRow, 0U);
        for (std::size_t beatIndex = 0; beatIndex < beats.size(); ++beatIndex) {
          const std::uint8_t mask = masks[beatIndex];
          for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
            if ((mask & (std::uint8_t{1} << lane)) != 0U) {
              ++remainingRows[decodeSlot(beats[beatIndex][lane]).localRow];
            }
          }
        }
        for (std::size_t beatIndex = 0; beatIndex < beats.size(); ++beatIndex) {
          const std::size_t chunkWidth = slotsPerChunk(chunkModes[beatIndex]);
          std::size_t partials = 0;
          for (std::size_t chunkStart = 0; chunkStart < kLanesPerBeat;
               chunkStart += chunkWidth) {
            const std::uint8_t chunkMask = static_cast<std::uint8_t>(
                ((std::uint32_t{1} << chunkWidth) - 1U) << chunkStart);
            const std::uint8_t valid = masks[beatIndex] & chunkMask;
            if (valid == 0U) {
              continue;
            }
            ++partials;
            const std::size_t firstLane = static_cast<std::size_t>(__builtin_ctz(valid));
            const std::uint32_t localRow = decodeSlot(beats[beatIndex][firstLane]).localRow;
            std::size_t validSlots = 0;
            for (std::size_t lane = chunkStart; lane < chunkStart + chunkWidth; ++lane) {
              if ((valid & (std::uint8_t{1} << lane)) == 0U) {
                continue;
              }
              if (decodeSlot(beats[beatIndex][lane]).localRow != localRow) {
                throw std::logic_error("Cuperflow 同一 chunk 的 localRow 不一致");
              }
              ++validSlots;
            }
            if (localRow >= remainingRows.size() || validSlots > remainingRows[localRow]) {
              throw std::logic_error("Cuperflow rowLast 计数越界");
            }
            const bool rowLast = validSlots == remainingRows[localRow];
            for (std::size_t lane = chunkStart; lane < chunkStart + chunkWidth; ++lane) {
              if ((valid & (std::uint8_t{1} << lane)) != 0U) {
                beats[beatIndex][lane] = withChunkMetadata(
                    beats[beatIndex][lane], chunkModes[beatIndex], rowLast);
              }
            }
            remainingRows[localRow] -= static_cast<std::uint32_t>(validSlots);
            if (rowLast) {
              activeRows[localRow / 64U] |= std::uint64_t{1} << (localRow % 64U);
            }
            switch (chunkModes[beatIndex]) {
              case CuperflowChunkMode::Full8: ++full8ChunkCount; break;
              case CuperflowChunkMode::Two4: ++two4ChunkCount; break;
              case CuperflowChunkMode::Four2: ++four2ChunkCount; break;
            }
          }
          switch (partials) {
            case 1: ++rowPartial1BeatCount; break;
            case 2: ++rowPartial2BeatCount; break;
            case 4: ++rowPartial4BeatCount; break;
            default:
              throw std::logic_error("Cuperflow 一个 beat 的 row partial 数不属于 1/2/4");
          }
        }
        if (std::any_of(remainingRows.begin(), remainingRows.end(),
                        [](std::uint32_t value) { return value != 0U; })) {
          throw std::logic_error("Cuperflow rowLast 未覆盖所有有效 slot");
        }
      }
      continue;
    }

    // 旧 X-page 局部列重排实验使用的 lane-striped 编码。百万级矩阵的空组合数量
    // 远大于非零元数量，因此只为实际出现的 (slice, PE) 建 bucket。
    const std::size_t firstNnz = static_cast<std::size_t>(matrix.rowPointers[firstRow]);
    const std::size_t lastNnz = static_cast<std::size_t>(matrix.rowPointers[lastRow]);
    const std::size_t batchNnz = lastNnz - firstNnz;
    using SlicePeBucket = std::unordered_map<std::size_t, std::vector<RawElement>>;
    SlicePeBucket buckets;
    const std::size_t bucketCapacity = std::min(
        batchNnz, sliceCount > std::numeric_limits<std::size_t>::max() / totalPes
            ? std::numeric_limits<std::size_t>::max()
            : sliceCount * totalPes);
    buckets.reserve(bucketCapacity);
    std::vector<std::size_t> activeKeys;
    activeKeys.reserve(bucketCapacity);
    for (std::size_t physicalRow = firstRow; physicalRow < lastRow; ++physicalRow) {
      const std::size_t originalRow = physicalToOriginalRows[physicalRow];
      const std::size_t begin = static_cast<std::size_t>(matrix.rowPointers[originalRow]);
      const std::size_t end = static_cast<std::size_t>(matrix.rowPointers[originalRow + 1U]);
      const std::size_t pe = peForRowUnchecked(physicalRow, config);
      for (std::size_t index = begin; index < end; ++index) {
        if (isCanonicalZero(matrix.values[index])) {
          continue;
        }
        const std::uint32_t column = matrix.columnIndices[index];
        const std::size_t slice = static_cast<std::size_t>(column) / config.sliceSize;
        const std::size_t group = slice / groupSize;
        const std::size_t channel = sliceGroupChannels[group];
        const std::size_t lane = pe % kLanesPerBeat;
        // HBM 只由列 group 决定；lane 仍沿用 row scheduler 的低三位以均衡 8 条流。
        const std::size_t stream = channel * kLanesPerBeat + lane;
        const std::size_t key = slice * totalPes + stream;
        auto [bucket, inserted] = buckets.try_emplace(key);
        if (inserted) {
          activeKeys.push_back(key);
        }
        bucket->second.push_back(RawElement{physicalRow, column,
            static_cast<float>(matrix.values[index])});
      }
    }
    std::sort(activeKeys.begin(), activeKeys.end());

    std::vector<std::vector<ScheduledSlot>> batchLaneStreams(totalPes);
    using LaneSliceLengths = std::array<std::uint32_t, kLanesPerBeat>;
    std::vector<LaneSliceLengths> laneSliceLengths(
        config.hbmChannelCount * sliceCount, LaneSliceLengths{});

    const std::size_t firstRowGroup = firstRow / rowGroupSpanValue;
    const std::size_t lastRowGroup = (lastRow - 1U) / rowGroupSpanValue;
    const std::size_t localRowGroupCount = lastRow == firstRow
        ? 0U : lastRowGroup - firstRowGroup + 1U;
    if (localRowGroupCount > std::numeric_limits<std::size_t>::max() / 2U) {
      throw std::overflow_error("Cuperflow batch-local row group 数溢出");
    }
    std::vector<std::size_t> nextPosition(localRowGroupCount * 2U, 0);

    // activeKeys 按 slice、PE 排序，等价于旧实现的双重循环，但只处理非空 bucket。
    for (const std::size_t key : activeKeys) {
      auto bucket = buckets.find(key);
      if (bucket == buckets.end()) {
        throw std::logic_error("Cuperflow 找不到已登记的 slice/PE bucket");
      }
      const std::size_t slice = key / totalPes;
      const std::size_t stream = key % totalPes;
      std::vector<RawElement>& elements = bucket->second;
      if (elements.size() > 1U) {
        std::stable_sort(elements.begin(), elements.end(),
                         [](const RawElement& lhs, const RawElement& rhs) {
                           return lhs.column != rhs.column ? lhs.column < rhs.column :
                               lhs.physicalRow < rhs.physicalRow;
                         });
      }

      std::fill(nextPosition.begin(), nextPosition.end(), 0U);
      std::vector<ScheduledSlot> scheduled;
      for (const RawElement& element : elements) {
        const std::size_t rowGroup = element.physicalRow / rowGroupSpanValue;
        if (rowGroup < firstRowGroup || rowGroup > lastRowGroup) {
          throw std::logic_error("Cuperflow bucket 中出现 batch 外的 physical row");
        }
        const std::size_t accumulatorTarget =
            (rowGroup - firstRowGroup) * 2U + element.physicalRow % 2U;
        std::size_t position = nextPosition[accumulatorTarget];
        while (position < scheduled.size() && scheduled[position].occupied) {
          ++position;
        }
        if (position == std::numeric_limits<std::size_t>::max()) {
          throw std::overflow_error("Cuperflow reorder 位置溢出");
        }
        if (position >= scheduled.size()) {
          scheduled.resize(position + 1U);
        }
        scheduled[position] = ScheduledSlot{true, element};
        nextPosition[accumulatorTarget] = position;
      }

      if (batchLaneStreams[stream].size() >
          std::numeric_limits<std::size_t>::max() - scheduled.size()) {
        throw std::overflow_error("Cuperflow stream 位置溢出");
      }
      batchLaneStreams[stream].insert(batchLaneStreams[stream].end(),
                                      scheduled.begin(), scheduled.end());

      const std::size_t channel = stream / kLanesPerBeat;
      const std::size_t lane = stream % kLanesPerBeat;
      if (scheduled.size() > std::numeric_limits<std::uint32_t>::max()) {
        throw std::overflow_error("Cuperflow lane slice 长度超过 uint32_t");
      }
      laneSliceLengths[channel * sliceCount + slice][lane] =
          static_cast<std::uint32_t>(scheduled.size());
    }
    for (std::size_t channel = 0; channel < config.hbmChannelCount; ++channel) {
      std::array<std::size_t, kLanesPerBeat> cursors{};
      for (std::size_t slice = 0; slice < sliceCount; ++slice) {
        const std::size_t group = slice / groupSize;
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          const std::size_t pe = channel * kLanesPerBeat + lane;
          const std::size_t length = laneSliceLengths[channel * sliceCount + slice][lane];
          const std::vector<ScheduledSlot>& stream = batchLaneStreams[pe];
          if (cursors[lane] > stream.size() || length > stream.size() - cursors[lane]) {
            throw std::logic_error("Cuperflow lane stream 短于 slice 长度表");
          }
          const std::size_t groupFirstColumn = (group * groupSize) * config.sliceSize;
          auto& dest = batchGroupLanes[batch][group][lane];
          dest.reserve(dest.size() + length);
          for (std::size_t index = 0; index < length; ++index) {
            const ScheduledSlot& scheduled = stream[cursors[lane] + index];
            if (!scheduled.occupied) {
              throw std::logic_error("Cuperflow 无 padding 流包含空 slot");
            }
            const std::uint32_t segmentId = segmentIdForColumn(xSegmentsByGroup[group],
                static_cast<std::size_t>(scheduled.element.column) - groupFirstColumn);
            dest.push_back(packSlot(scheduled.element, segmentId, groupFirstColumn, firstRow));
          }
          cursors[lane] += length;
        }
      }
      for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
        if (cursors[lane] != batchLaneStreams[channel * kLanesPerBeat + lane].size()) {
          throw std::logic_error("Cuperflow lane stream 未按 slice 长度表耗尽");
        }
      }
    }
  }

  std::vector<std::vector<CuperflowGroupARange>> channelGroupARanges(config.hbmChannelCount);
  for (std::size_t channel = 0; channel < config.hbmChannelCount; ++channel) {
    for (const std::size_t group : channelSliceGroups[channel]) {
      const std::size_t groupStart = channels[channel].size();
      bool any = false;
      for (std::size_t batch = 0; batch < batchCount; ++batch) {
        const bool rowRoundRobin = config.aPacking == CuperflowAPacking::RowRoundRobin;
        const GroupLaneWords& words = batchGroupLanes[batch][group];
        const GroupBeatList& rowBeats = batchGroupBeats[batch][group];
        const std::vector<std::uint8_t>& rowMasks = batchGroupEntryMasks[batch][group];
        std::size_t unionBeats = rowBeats.size();
        if (!rowRoundRobin) {
          for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
            unionBeats = std::max(unionBeats, words[lane].size());
          }
        }
        const std::size_t dest = channels[channel].size();
        const std::size_t groupSegment = batch * groupCount + group;
        if (unionBeats == 0) {
          continue;
        }
        any = true;
        if (dest > std::numeric_limits<std::uint32_t>::max() ||
            unionBeats > std::numeric_limits<std::uint32_t>::max() - dest) {
          throw std::overflow_error("Cuperflow group-major A 指针超过 uint32_t 范围");
        }
        channels[channel].insert(channels[channel].end(), unionBeats, zeroFillBeat);
        entryMasks[channel].insert(entryMasks[channel].end(), unionBeats, 0U);
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          channelLaneSliceGroupRanges[channel][groupSegment][lane] = {
              static_cast<std::uint32_t>(dest),
              static_cast<std::uint32_t>(dest + (rowRoundRobin ? unionBeats : words[lane].size()))};
          if (rowRoundRobin) {
            continue;
          }
          for (std::size_t index = 0; index < words[lane].size(); ++index) {
            channels[channel][dest + index][lane] = words[lane][index];
            entryMasks[channel][dest + index] |= static_cast<std::uint8_t>(1U << lane);
            ++matrixSlots;
          }
        }
        if (rowRoundRobin) {
          if (rowMasks.size() != rowBeats.size()) {
            throw std::logic_error("Cuperflow row-round-robin beat 与掩码长度不一致");
          }
          for (std::size_t index = 0; index < rowBeats.size(); ++index) {
            channels[channel][dest + index] = rowBeats[index];
            entryMasks[channel][dest + index] = rowMasks[index];
            for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
              matrixSlots += (rowMasks[index] >> lane) & 1U;
            }
          }
        }
      }
      if (!any) {
        continue;
      }
      if (groupStart > std::numeric_limits<std::uint32_t>::max() ||
          channels[channel].size() - groupStart >
              std::numeric_limits<std::uint32_t>::max()) {
        throw std::overflow_error("Cuperflow group A 区间超过 uint32_t 范围");
      }
      channelGroupARanges[channel].push_back(CuperflowGroupARange{
          group,
          static_cast<std::uint32_t>(groupStart),
          static_cast<std::uint32_t>(channels[channel].size() - groupStart)});
    }
    if (channels[channel].size() > std::numeric_limits<std::uint32_t>::max()) {
      throw std::overflow_error("Cuperflow per-HBM A 长度超过 uint32_t 范围");
    }
    channelBatchPointers[channel][batchCount] =
        static_cast<std::uint32_t>(channels[channel].size());
  }
  if (matrixSlots + droppedExplicitZeros != matrix.values.size()) {
    throw std::logic_error("Cuperflow 编码后的矩阵 slot 数与 canonical CSR 不一致");
  }

  std::vector<std::vector<std::uint32_t>> channelGroupDescriptorOffsets(
      config.hbmChannelCount,
      std::vector<std::uint32_t>(groupCount, std::numeric_limits<std::uint32_t>::max()));
  std::vector<std::vector<CuperflowBatchDescriptor>> channelBatchDescriptors(
      config.hbmChannelCount);
  std::vector<std::vector<std::uint64_t>> channelContributorWords(config.hbmChannelCount);
  std::vector<std::vector<std::uint16_t>> contributorMasksByWaveBatch(
      batchCount * contributorWaveCount);
  for (std::size_t batch = 0; batch < batchCount; ++batch) {
    const std::size_t activeRows = std::min(config.rowBatchSize,
        matrix.rows - batch * config.rowBatchSize);
    for (std::size_t wave = 0; wave < contributorWaveCount; ++wave) {
      contributorMasksByWaveBatch[batch * contributorWaveCount + wave].assign(activeRows, 0U);
    }
  }
  std::uint64_t batchDescriptorCount = 0;
  std::uint64_t emptyBatchCount = 0;
  if (config.aPacking == CuperflowAPacking::RowRoundRobin) {
    for (std::size_t channel = 0; channel < config.hbmChannelCount; ++channel) {
      for (const std::size_t group : channelSliceGroups[channel]) {
        if (channelBatchDescriptors[channel].size() >
            std::numeric_limits<std::uint32_t>::max()) {
          throw std::overflow_error("Cuperflow BATCH_DESC 下标超过 uint32_t 范围");
        }
        channelGroupDescriptorOffsets[channel][group] =
            static_cast<std::uint32_t>(channelBatchDescriptors[channel].size());
        for (std::size_t batch = 0; batch < batchCount; ++batch) {
          const std::size_t activeRows = std::min(config.rowBatchSize,
              matrix.rows - batch * config.rowBatchSize);
          const std::size_t groupSegment = batch * groupCount + group;
          const auto& aRange = channelLaneSliceGroupRanges[channel][groupSegment][0];
          const std::vector<std::uint64_t>& bitmap = batchGroupActiveRows[batch][group];
          const std::size_t expectedWords = divideRoundedUp(activeRows, std::size_t{64});
          if (bitmap.size() != expectedWords ||
              channelContributorWords[channel].size() >
                  std::numeric_limits<std::uint32_t>::max() ||
              expectedWords > std::numeric_limits<std::uint32_t>::max()) {
            throw std::overflow_error("Cuperflow contributor bitmap 超过 descriptor 位宽");
          }
          CuperflowBatchDescriptor descriptor;
          descriptor.batchId = static_cast<std::uint32_t>(batch);
          descriptor.aOffsetBeats = aRange.first;
          descriptor.aBeats = aRange.second - aRange.first;
          descriptor.contributorOffsetWords =
              static_cast<std::uint32_t>(channelContributorWords[channel].size());
          descriptor.contributorWordCount = static_cast<std::uint32_t>(expectedWords);
          descriptor.activeRowCount = static_cast<std::uint32_t>(activeRows);
          descriptor.lastBatchInGroup = batch + 1U == batchCount;
          validateBatchDescriptor(descriptor);
          channelContributorWords[channel].insert(channelContributorWords[channel].end(),
              bitmap.begin(), bitmap.end());
          channelBatchDescriptors[channel].push_back(descriptor);
          std::vector<std::uint16_t>& rowMasks = contributorMasksByWaveBatch[
              batch * contributorWaveCount + group / config.hbmChannelCount];
          for (std::size_t row = 0; row < activeRows; ++row) {
            if (((bitmap[row / 64U] >> (row % 64U)) & 1U) != 0U) {
              rowMasks[row] |= static_cast<std::uint16_t>(std::uint16_t{1} << channel);
            }
          }
          ++batchDescriptorCount;
          emptyBatchCount += descriptor.aBeats == 0U ? 1U : 0U;
        }
      }
    }
  }
  std::size_t minimumBeatsPerChannel = channels.empty() ? 0 : channels.front().size();
  std::size_t maximumBeatsPerChannel = 0;
  std::uint64_t totalBeats = 0;
  for (const std::vector<CuperflowBeat>& channel : channels) {
    minimumBeatsPerChannel = std::min(minimumBeatsPerChannel, channel.size());
    maximumBeatsPerChannel = std::max(maximumBeatsPerChannel, channel.size());
    if (channel.size() > std::numeric_limits<std::uint64_t>::max() - totalBeats) {
      throw std::overflow_error("Cuperflow 总 beat 数溢出");
    }
    totalBeats += channel.size();
  }
  if (totalBeats > std::numeric_limits<std::uint64_t>::max() / kLanesPerBeat) {
    throw std::overflow_error("Cuperflow 总 slot 数溢出");
  }
  const std::uint64_t totalSlots = totalBeats * kLanesPerBeat;
  if (totalBeats > std::numeric_limits<std::uint64_t>::max() / 64U) {
    throw std::overflow_error("Cuperflow package 字节数溢出");
  }

  CuperflowPackage package;
  package.config = config;
  package.rows = matrix.rows;
  package.columns = matrix.columns;
  package.nonzeros = matrixSlots;
  package.physicalToOriginalRows = std::move(physicalToOriginalRows);
  package.columnSliceCount = sliceCount;
  package.sliceGroupSize = groupSize;
  package.sliceGroupCount = groupCount;
  package.contributorWaveCount = contributorWaveCount;
  package.sliceGroupChannels = std::move(sliceGroupChannels);
  package.channelSliceGroups = std::move(channelSliceGroups);
  package.xUsedColumnsByGroup = std::move(xUsedColumnsByGroup);
  package.xSegmentsByGroup = std::move(xSegmentsByGroup);
  package.channelBatchPointers = std::move(channelBatchPointers);
  package.channelLaneSliceGroupRanges = std::move(channelLaneSliceGroupRanges);
  package.channelGroupARanges = std::move(channelGroupARanges);
  package.matrixChannels = std::move(channels);
  package.matrixEntryMasks = std::move(entryMasks);
  package.channelGroupDescriptorOffsets = std::move(channelGroupDescriptorOffsets);
  package.channelBatchDescriptors = std::move(channelBatchDescriptors);
  package.channelContributorWords = std::move(channelContributorWords);
  package.contributorMasksByWaveBatch = std::move(contributorMasksByWaveBatch);
  package.stats.batchCount = batchCount;
  package.stats.minimumMatrixBeatsPerChannel = minimumBeatsPerChannel;
  package.stats.maximumMatrixBeatsPerChannel = maximumBeatsPerChannel;
  package.stats.totalMatrixBeats = totalBeats;
  package.stats.matrixSlots = matrixSlots;
  package.stats.zeroFillSlots = totalSlots - matrixSlots;
  package.stats.droppedExplicitZeros = droppedExplicitZeros;
  package.stats.full8ChunkCount = full8ChunkCount;
  package.stats.two4ChunkCount = two4ChunkCount;
  package.stats.four2ChunkCount = four2ChunkCount;
  package.stats.rowPartial1BeatCount = rowPartial1BeatCount;
  package.stats.rowPartial2BeatCount = rowPartial2BeatCount;
  package.stats.rowPartial4BeatCount = rowPartial4BeatCount;
  package.stats.batchDescriptorCount = batchDescriptorCount;
  package.stats.emptyBatchCount = emptyBatchCount;
  package.stats.packedBytes = totalBeats * 64U;
  if (config.aPacking == CuperflowAPacking::RowRoundRobin) {
    storeL1Analysis(package, analyzeL1(package));
    validatePackage(package);
  }
  return package;
}

void validatePackage(const CuperflowPackage& package) {
  validateConfig(package.config);
  if (package.config.aPacking != CuperflowAPacking::RowRoundRobin) {
    throw std::invalid_argument("Cuperflow V0 package validator 只接受 row-round-robin A 布局");
  }
  const std::size_t channelCount = package.config.hbmChannelCount;
  const std::size_t expectedWaveCount =
      divideRoundedUp(package.sliceGroupCount, channelCount);
  if (package.matrixChannels.size() != channelCount ||
      package.matrixEntryMasks.size() != channelCount ||
      package.channelLaneSliceGroupRanges.size() != channelCount ||
      package.channelGroupDescriptorOffsets.size() != channelCount ||
      package.channelBatchDescriptors.size() != channelCount ||
      package.channelContributorWords.size() != channelCount ||
      package.sliceGroupChannels.size() != package.sliceGroupCount ||
      package.contributorWaveCount != expectedWaveCount ||
      package.contributorMasksByWaveBatch.size() !=
          package.stats.batchCount * expectedWaveCount) {
    throw std::invalid_argument("Cuperflow V0 package 的顶层表尺寸不一致");
  }

  std::vector<std::vector<std::uint16_t>> expectedContributorMasks(
      package.contributorMasksByWaveBatch.size());
  for (std::size_t index = 0; index < expectedContributorMasks.size(); ++index) {
    expectedContributorMasks[index].assign(package.contributorMasksByWaveBatch[index].size(), 0U);
  }

  std::uint64_t observedSlots = 0;
  std::uint64_t observedDescriptors = 0;
  for (std::size_t channel = 0; channel < channelCount; ++channel) {
    if (package.matrixChannels[channel].size() != package.matrixEntryMasks[channel].size() ||
        package.channelLaneSliceGroupRanges[channel].size() !=
            package.stats.batchCount * package.sliceGroupCount ||
        package.channelGroupDescriptorOffsets[channel].size() != package.sliceGroupCount) {
      throw std::invalid_argument("Cuperflow V0 package 的 channel 表尺寸不一致");
    }
    for (std::size_t group = 0; group < package.sliceGroupCount; ++group) {
      const bool ownsGroup = package.sliceGroupChannels[group] == channel;
      const std::uint32_t descriptorOffset = package.channelGroupDescriptorOffsets[channel][group];
      if (!ownsGroup) {
        if (descriptorOffset != std::numeric_limits<std::uint32_t>::max()) {
          throw std::invalid_argument("Cuperflow 非 owner channel 持有 BATCH_DESC");
        }
        continue;
      }
      if (descriptorOffset == std::numeric_limits<std::uint32_t>::max() ||
          descriptorOffset > package.channelBatchDescriptors[channel].size() ||
          package.stats.batchCount >
              package.channelBatchDescriptors[channel].size() - descriptorOffset) {
        throw std::invalid_argument("Cuperflow owner channel 的 BATCH_DESC 范围非法");
      }
      const std::size_t groupFirstColumn =
          group * package.sliceGroupSize * package.config.sliceSize;
      const std::size_t groupColumns = std::min(
          package.sliceGroupSize * package.config.sliceSize,
          package.columns - groupFirstColumn);
      for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
        const std::size_t groupSegment = batch * package.sliceGroupCount + group;
        const CuperflowBatchDescriptor& descriptor =
            package.channelBatchDescriptors[channel][descriptorOffset + batch];
        validateBatchDescriptor(descriptor);
        const std::size_t activeRows = std::min(package.config.rowBatchSize,
            package.rows - batch * package.config.rowBatchSize);
        if (descriptor.batchId != batch || descriptor.activeRowCount != activeRows ||
            descriptor.lastBatchInGroup != (batch + 1U == package.stats.batchCount) ||
            descriptor.contributorOffsetWords > package.channelContributorWords[channel].size() ||
            descriptor.contributorWordCount >
                package.channelContributorWords[channel].size() - descriptor.contributorOffsetWords) {
          throw std::invalid_argument("Cuperflow BATCH_DESC 字段或 contributor 范围非法");
        }
        const auto& laneRanges = package.channelLaneSliceGroupRanges[channel][groupSegment];
        const auto range = laneRanges[0];
        if (range.first > range.second || range.second > package.matrixChannels[channel].size() ||
            descriptor.aOffsetBeats != range.first || descriptor.aBeats != range.second - range.first) {
          throw std::invalid_argument("Cuperflow BATCH_DESC 的 A range 与 package 不一致");
        }
        for (std::size_t lane = 1; lane < kLanesPerBeat; ++lane) {
          if (laneRanges[lane] != range) {
            throw std::invalid_argument("Cuperflow row-round-robin 的 lane A range 不一致");
          }
        }
        const std::size_t waveSegment =
            batch * expectedWaveCount + group / channelCount;
        const std::vector<std::uint16_t>& rowMasks =
            package.contributorMasksByWaveBatch[waveSegment];
        if (rowMasks.size() != activeRows) {
          throw std::invalid_argument("Cuperflow contributor row-major mask 长度错误");
        }
        std::vector<std::uint8_t> rowLastCount(activeRows, 0U);
        for (std::size_t beatIndex = range.first; beatIndex < range.second; ++beatIndex) {
          const std::uint8_t entryMask = package.matrixEntryMasks[channel][beatIndex];
          std::size_t firstValidLane = kLanesPerBeat;
          for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
            if ((entryMask & (std::uint8_t{1} << lane)) == 0U) {
              continue;
            }
            if (package.matrixChannels[channel][beatIndex][lane] == kZeroFillSlot) {
              throw std::invalid_argument("Cuperflow 有效 lane 与全零 padding 冲突");
            }
            firstValidLane = std::min(firstValidLane, lane);
            ++observedSlots;
          }
          if (firstValidLane == kLanesPerBeat) {
            throw std::invalid_argument("Cuperflow A range 含不属于任何 chunk 的空 beat");
          }
          const CuperflowChunkMode mode =
              decodeSlot(package.matrixChannels[channel][beatIndex][firstValidLane]).chunkMode;
          const std::size_t chunkWidth = slotsPerChunk(mode);
          for (std::size_t chunkStart = 0; chunkStart < kLanesPerBeat;
               chunkStart += chunkWidth) {
            const std::uint8_t chunkMask = static_cast<std::uint8_t>(
                ((std::uint32_t{1} << chunkWidth) - 1U) << chunkStart);
            const std::uint8_t valid = entryMask & chunkMask;
            if (valid == 0U) {
              continue;
            }
            const std::size_t firstLane = static_cast<std::size_t>(__builtin_ctz(valid));
            const DecodedCuperflowSlot first =
                decodeSlot(package.matrixChannels[channel][beatIndex][firstLane]);
            if (first.localRow >= activeRows || first.segmentId >= package.xSegmentsByGroup[group].size() ||
                first.localColumn >= groupColumns) {
              throw std::invalid_argument("Cuperflow slot 的 row/segment/column 超出 group 范围");
            }
            const CuperflowXSegment& segment = package.xSegmentsByGroup[group][first.segmentId];
            if (first.localColumn < segment.start ||
                first.localColumn - segment.start >= segment.count) {
              throw std::invalid_argument("Cuperflow slot 列未落入所选 X segment");
            }
            for (std::size_t lane = chunkStart; lane < chunkStart + chunkWidth; ++lane) {
              if ((valid & (std::uint8_t{1} << lane)) == 0U) {
                continue;
              }
              const DecodedCuperflowSlot slot =
                  decodeSlot(package.matrixChannels[channel][beatIndex][lane]);
              if (slot.chunkMode != mode || slot.localRow != first.localRow ||
                  slot.rowLast != first.rowLast) {
                throw std::invalid_argument("Cuperflow 同一 chunk 的 mode/row/rowLast 不一致");
              }
              if (slot.localRow >= activeRows ||
                  slot.segmentId >= package.xSegmentsByGroup[group].size() ||
                  slot.localColumn >= groupColumns) {
                throw std::invalid_argument("Cuperflow slot 超出 row/segment/column 范围");
              }
              const CuperflowXSegment& laneSegment = package.xSegmentsByGroup[group][slot.segmentId];
              if (slot.localColumn < laneSegment.start ||
                  slot.localColumn - laneSegment.start >= laneSegment.count) {
                throw std::invalid_argument("Cuperflow slot 列未落入所选 X segment");
              }
            }
            if (first.rowLast && ++rowLastCount[first.localRow] != 1U) {
              throw std::invalid_argument("Cuperflow 一行产生了多个 rowLast partial");
            }
          }
        }
        for (std::size_t row = 0; row < activeRows; ++row) {
          const bool active = ((package.channelContributorWords[channel]
              [descriptor.contributorOffsetWords + row / 64U] >> (row % 64U)) & 1U) != 0U;
          const std::uint16_t channelBit = static_cast<std::uint16_t>(std::uint16_t{1} << channel);
          if ((rowLastCount[row] == 1U) != active ||
              ((rowMasks[row] & channelBit) != 0U) != active) {
            throw std::invalid_argument("Cuperflow contributor bitmap 与 rowLast 不一致");
          }
          if (active) {
            expectedContributorMasks[waveSegment][row] |= channelBit;
          }
        }
        ++observedDescriptors;
      }
    }
  }
  if (observedSlots != package.stats.matrixSlots ||
      observedDescriptors != package.stats.batchDescriptorCount) {
    throw std::invalid_argument("Cuperflow V0 package 的统计与实际内容不一致");
  }
  if (expectedContributorMasks != package.contributorMasksByWaveBatch) {
    throw std::invalid_argument("Cuperflow row-major contributor mask 与 per-PC bitmap 不一致");
  }
  const CuperflowL1Analysis analysis = analyzeL1(package);
  if (package.pcL1Stats != analysis.pcStats ||
      package.waveBatchL1Stats != analysis.waveBatchStats ||
      package.stats.contributorPopcountHistogram != analysis.contributorPopcountHistogram ||
      package.stats.chunkInterBeatDistanceCount != analysis.chunkInterBeatDistanceCount ||
      package.stats.chunkInterBeatDistanceTotal != analysis.chunkInterBeatDistanceTotal ||
      package.stats.chunkInterBeatDistanceMinimum != analysis.chunkInterBeatDistanceMinimum ||
      package.stats.chunkInterBeatDistanceMaximum != analysis.chunkInterBeatDistanceMaximum ||
      package.stats.chunkInterBeatDistanceBelowFaddLatency !=
          analysis.chunkInterBeatDistanceBelowFaddLatency ||
      package.stats.candidateFaddLatency != kAnalysisCandidateFaddLatency ||
      package.stats.completionRobPeak != analysis.completionRobPeak ||
      package.stats.xPayloadLoadCount != analysis.xPayloadLoadCount ||
      package.stats.expectedXPayloadLoadCount != analysis.expectedXPayloadLoadCount) {
    throw std::invalid_argument("Cuperflow V0 L1 分析统计与 package 不一致");
  }
}

namespace {

std::uint64_t doubleBits(double value) {
  std::uint64_t bits = 0;
  static_assert(sizeof(bits) == sizeof(value));
  std::memcpy(&bits, &value, sizeof(bits));
  return bits;
}

void validateVectorInput(const std::vector<double>& input) {
  if (input.empty()) {
    throw std::invalid_argument("Cuperflow X 输入不能为空");
  }
  for (double value : input) {
    if (!std::isfinite(value)) {
      throw std::invalid_argument(
          "Cuperflow X 输入必须是有限 FP64；NaN/Inf 保留给未来多段 X marker 协议");
    }
  }
}

void addEncodedStat(std::uint64_t& total, std::size_t value, const char* message) {
  if (value > std::numeric_limits<std::uint64_t>::max() - total) {
    throw std::overflow_error(message);
  }
  total += static_cast<std::uint64_t>(value);
}

CuperflowVectorPackage encodeVectorImpl(
    const std::vector<double>& input, const CuperflowConfig& config,
    const CuperflowPackage* matrixPackage) {
  validateConfig(config);
  validateVectorInput(input);

  const bool flexible = matrixPackage != nullptr && kFlexibleXEncodingEnabled;

  const std::size_t batchWidth = columnsPerBatch(config);
  if (batchWidth % kVectorLanesPerBeat != 0) {
    throw std::invalid_argument("Cuperflow X column batch 宽度必须按 FP64 beat 对齐");
  }
  const std::size_t batchCount = divideRoundedUp(input.size(), batchWidth);
  const std::size_t payloadBeats = divideRoundedUp(input.size(), kVectorLanesPerBeat);
  const std::size_t allocatedElements = divideRoundedUp(
      input.size(), kVectorStorageAlignmentElements) * kVectorStorageAlignmentElements;
  const std::size_t allocatedBeats = allocatedElements / kVectorLanesPerBeat;
  if (payloadBeats > std::numeric_limits<std::uint32_t>::max()) {
    throw std::overflow_error("Cuperflow X batch pointer 超过 uint32_t 范围");
  }
  if (allocatedBeats > std::numeric_limits<std::uint64_t>::max() / 64U) {
    throw std::overflow_error("Cuperflow X HBM 分配字节数溢出");
  }

  CuperflowVectorPackage package;
  package.config = config;
  package.columns = input.size();
  package.sourceValues = input;
  package.batchPointers.resize(batchCount + 1U, 0);
  package.hbmBeats.resize(allocatedBeats);
  package.channelHbmBeats.resize(config.hbmChannelCount);
  package.channelXRanges.resize(config.hbmChannelCount);
  package.flexibleXEncoding = flexible;

  for (std::size_t batch = 0; batch < batchCount; ++batch) {
    const std::size_t endColumn = std::min((batch + 1U) * batchWidth, input.size());
    package.batchPointers[batch + 1U] = static_cast<std::uint32_t>(
        divideRoundedUp(endColumn, kVectorLanesPerBeat));
  }
  for (std::size_t column = 0; column < input.size(); ++column) {
    package.hbmBeats[column / kVectorLanesPerBeat][column % kVectorLanesPerBeat] =
        doubleBits(input[column]);
  }

  const std::size_t sliceCount = columnSliceCount(input.size(), config);
  const std::size_t groupSize = effectiveSliceGroupSize(sliceCount, config);
  const std::size_t groupCount = divideRoundedUp(sliceCount, groupSize);
  if (sliceCount >= config.hbmChannelCount && groupCount < config.hbmChannelCount) {
    throw std::invalid_argument(
        "Cuperflow X range 必须为每个 HBM 保留不同的 slice group");
  }
  if (flexible &&
      (matrixPackage->columns != input.size() ||
       matrixPackage->sliceGroupCount != groupCount ||
       matrixPackage->sliceGroupSize != groupSize ||
       matrixPackage->xUsedColumnsByGroup.size() != groupCount ||
       matrixPackage->xSegmentsByGroup.size() != groupCount ||
       matrixPackage->channelGroupARanges.size() != config.hbmChannelCount)) {
    throw std::invalid_argument("Cuperflow A/X package 的 sliceGroup 几何不一致");
  }

  struct PendingGroup {
    std::size_t group = 0;
    std::size_t groupFirstColumn = 0;
    std::vector<CuperflowXSegment> segments;
    std::size_t payloadElements = 0;
    std::size_t usedElements = 0;
    std::uint32_t aOffsetBeats = 0;
    std::uint32_t aBeats = 0;
  };
  std::vector<std::vector<PendingGroup>> pending(config.hbmChannelCount);
  for (std::size_t group = 0; group < groupCount; ++group) {
    const std::size_t firstSlice = group * groupSize;
    const std::size_t groupFirstColumn = firstSlice * config.sliceSize;
    const std::size_t groupElements = std::min(
        groupSize * config.sliceSize, input.size() - groupFirstColumn);
    const std::size_t channel = group % config.hbmChannelCount;
    std::vector<CuperflowXSegment> segments{
        CuperflowXSegment{0, static_cast<std::uint16_t>(groupElements)}};
    std::size_t usedElements = groupElements;
    std::uint32_t aOffsetBeats = 0;
    std::uint32_t aBeats = 0;
    if (matrixPackage != nullptr) {
      for (const CuperflowGroupARange& range : matrixPackage->channelGroupARanges[channel]) {
        if (range.sliceGroup == group) {
          aOffsetBeats = range.aOffsetBeats;
          aBeats = range.aBeats;
          break;
        }
      }
      if (aBeats == 0) {
        continue;
      }
      const std::vector<std::uint32_t>& usedColumns =
          matrixPackage->xUsedColumnsByGroup[group];
      if (usedColumns.empty()) {
        throw std::logic_error("Cuperflow 非空 A group 没有对应的 X 列集合");
      }
      usedElements = usedColumns.size();
      if (flexible) {
        segments = matrixPackage->xSegmentsByGroup[group];
        if (segments.empty() || segments.size() > kMaxXSegments) {
          throw std::logic_error("Cuperflow 非空 A group 没有合法的 X 段计划");
        }
      }
    }
    std::size_t payloadElements = 0;
    for (const CuperflowXSegment& segment : segments) {
      if (segment.count == 0 || segment.start + segment.count > groupElements) {
        throw std::logic_error("Cuperflow X 段超出所属 sliceGroup 范围");
      }
      payloadElements += segment.count;
    }
    pending[channel].push_back(PendingGroup{
        group, groupFirstColumn, std::move(segments), payloadElements, usedElements,
        aOffsetBeats, aBeats});
  }

  std::size_t maximumRangeElements = 0;
  for (std::size_t channel = 0; channel < config.hbmChannelCount; ++channel) {
    std::vector<CuperflowVectorBeat>& channelBeats = package.channelHbmBeats[channel];
    if (matrixPackage != nullptr && pending[channel].empty()) {
      CuperflowMapBeat emptyMap;
      emptyMap.last = true;
      channelBeats.push_back(packMapBeat(emptyMap));
    }
    for (std::size_t index = 0; index < pending[channel].size(); ++index) {
      const PendingGroup& item = pending[channel][index];
      const bool last = index + 1U == pending[channel].size();
      std::vector<std::uint64_t> tokens;
      tokens.reserve(item.payloadElements);
      for (const CuperflowXSegment& segment : item.segments) {
        for (std::size_t offset = 0; offset < segment.count; ++offset) {
          tokens.push_back(doubleBits(input[item.groupFirstColumn + segment.start + offset]));
        }
      }
      const std::size_t valueCount = tokens.size();
      constexpr std::size_t markerCount = 0;
      if (channelBeats.size() > std::numeric_limits<std::uint32_t>::max()) {
        throw std::overflow_error("Cuperflow per-HBM X range pointer 超过 uint32_t 范围");
      }
      const bool emitMap = matrixPackage != nullptr;
      const std::size_t mapBeat = channelBeats.size();
      if (emitMap) {
        if (tokens.size() > std::numeric_limits<std::uint32_t>::max() ||
            item.payloadElements > std::numeric_limits<std::uint16_t>::max() ||
            item.group > std::numeric_limits<std::uint16_t>::max()) {
          throw std::overflow_error("Cuperflow map 字段超出 1-beat 位宽");
        }
        const std::uint32_t xBeats = static_cast<std::uint32_t>(
            divideRoundedUp(tokens.size(), kVectorLanesPerBeat));
        CuperflowMapBeat map;
        map.xBeats = xBeats;
        map.xWords = static_cast<std::uint32_t>(tokens.size());
        if (matrixPackage->channelGroupDescriptorOffsets.size() != config.hbmChannelCount ||
            matrixPackage->channelBatchDescriptors.size() != config.hbmChannelCount ||
            channel >= matrixPackage->channelGroupDescriptorOffsets.size() ||
            item.group >= matrixPackage->channelGroupDescriptorOffsets[channel].size()) {
          throw std::invalid_argument("Cuperflow V0 matrix package 缺少 BATCH_DESC 索引");
        }
        const std::uint32_t descriptorOffset =
            matrixPackage->channelGroupDescriptorOffsets[channel][item.group];
        if (descriptorOffset == std::numeric_limits<std::uint32_t>::max() ||
            descriptorOffset > matrixPackage->channelBatchDescriptors[channel].size() ||
            matrixPackage->stats.batchCount >
                matrixPackage->channelBatchDescriptors[channel].size() - descriptorOffset) {
          throw std::invalid_argument("Cuperflow V0 GROUP_MAP 的 BATCH_DESC 范围非法");
        }
        map.batchDescriptorCount = static_cast<std::uint32_t>(matrixPackage->stats.batchCount);
        map.sliceGroup = static_cast<std::uint16_t>(item.group);
        map.xElements = static_cast<std::uint16_t>(item.payloadElements);
        std::copy(item.segments.begin(), item.segments.end(), map.xSegments.begin());
        map.last = last;
        channelBeats.push_back(packMapBeat(map));
      }
      const std::size_t beatBegin = channelBeats.size();
      for (std::size_t word = 0; word < tokens.size(); ++word) {
        if (word % kVectorLanesPerBeat == 0U) {
          channelBeats.emplace_back();
        }
        channelBeats.back()[word % kVectorLanesPerBeat] = tokens[word];
      }
      const std::size_t beatEnd = channelBeats.size();
      if (beatEnd > std::numeric_limits<std::uint32_t>::max()) {
        throw std::overflow_error("Cuperflow per-HBM X range pointer 超过 uint32_t 范围");
      }
      CuperflowXRange range;
      range.sliceGroup = item.group;
      range.segments = item.segments;
      range.elementCount = item.payloadElements;
      range.usedElementCount = item.usedElements;
      range.encodedWordCount = tokens.size();
      range.valueCount = valueCount;
      range.markerCount = markerCount;
      range.mapBeat = emitMap ? static_cast<std::uint32_t>(mapBeat) :
          std::numeric_limits<std::uint32_t>::max();
      range.beatBegin = static_cast<std::uint32_t>(beatBegin);
      range.beatEnd = static_cast<std::uint32_t>(beatEnd);
      range.aOffsetBeats = item.aOffsetBeats;
      range.aBeats = item.aBeats;
      if (matrixPackage != nullptr) {
        const std::uint32_t descriptorOffset =
            matrixPackage->channelGroupDescriptorOffsets[channel][item.group];
        range.batchDescriptorCount = static_cast<std::uint32_t>(matrixPackage->stats.batchCount);
        if (channelBeats.size() > std::numeric_limits<std::uint32_t>::max()) {
          throw std::overflow_error("Cuperflow BATCH_DESC beat 指针超过 uint32_t 范围");
        }
        range.descriptorBeatBegin = static_cast<std::uint32_t>(channelBeats.size());
        for (std::size_t batch = 0; batch < matrixPackage->stats.batchCount; ++batch) {
          const CuperflowBatchDescriptor& descriptor =
              matrixPackage->channelBatchDescriptors[channel][descriptorOffset + batch];
          channelBeats.push_back(packBatchDescriptor(descriptor));
          if (descriptor.contributorOffsetWords >
                  matrixPackage->channelContributorWords[channel].size() ||
              descriptor.contributorWordCount >
                  matrixPackage->channelContributorWords[channel].size() -
                      descriptor.contributorOffsetWords) {
            throw std::invalid_argument("Cuperflow BATCH_DESC 的 contributor bitmap 越界");
          }
          for (std::size_t word = 0; word < descriptor.contributorWordCount; ++word) {
            if (word % kVectorLanesPerBeat == 0U) {
              channelBeats.emplace_back();
            }
            channelBeats.back()[word % kVectorLanesPerBeat] =
                matrixPackage->channelContributorWords[channel]
                    [descriptor.contributorOffsetWords + word];
          }
        }
        if (channelBeats.size() > std::numeric_limits<std::uint32_t>::max()) {
          throw std::overflow_error("Cuperflow BATCH_DESC beat 指针超过 uint32_t 范围");
        }
        range.descriptorBeatEnd = static_cast<std::uint32_t>(channelBeats.size());
      }
      range.last = last;
      package.channelXRanges[channel].push_back(std::move(range));
      maximumRangeElements = std::max(maximumRangeElements, item.payloadElements);
      addEncodedStat(package.stats.encodedWordCount, tokens.size(),
                     "Cuperflow X token 数量溢出");
      addEncodedStat(package.stats.encodedValueCount, valueCount,
                     "Cuperflow X value 数量溢出");
      addEncodedStat(package.stats.demandedElements, item.usedElements,
                     "Cuperflow X 实际需求列数量溢出");
      addEncodedStat(package.stats.markerCount, markerCount,
                     "Cuperflow X marker 数量溢出");
      addEncodedStat(package.stats.segmentCount, item.segments.size(),
                     "Cuperflow X 段数量溢出");
    }
  }

  for (const auto& ranges : package.channelXRanges) {
    for (const CuperflowXRange& range : ranges) {
      addEncodedStat(package.stats.encodedPayloadBeats,
                     static_cast<std::size_t>(range.beatEnd - range.beatBegin),
                     "Cuperflow X encoded beat 数量溢出");
    }
  }
  if (package.stats.encodedPayloadBeats >
      std::numeric_limits<std::uint64_t>::max() / kVectorLanesPerBeat) {
    throw std::overflow_error("Cuperflow X encoded 尾部 slot 数量溢出");
  }
  const std::uint64_t encodedSlots =
      package.stats.encodedPayloadBeats * kVectorLanesPerBeat;
  if (encodedSlots < package.stats.encodedWordCount) {
    throw std::logic_error("Cuperflow X encoded beat 不能容纳全部 token");
  }
  package.stats.encodedLanePaddingWords = encodedSlots - package.stats.encodedWordCount;

  package.stats.batchCount = batchCount;
  package.stats.payloadBeats = payloadBeats;
  package.stats.allocatedBeats = allocatedBeats;
  package.stats.validElements = input.size();
  package.stats.lanePaddingElements = payloadBeats * kVectorLanesPerBeat - input.size();
  package.stats.allocationPaddingElements = allocatedElements -
      payloadBeats * kVectorLanesPerBeat;
  package.stats.packedBytes = payloadBeats * 64U;
  package.stats.allocatedBytes = allocatedBeats * 64U;
  package.stats.rangeCount = 0;
  for (const auto& ranges : package.channelXRanges) {
    package.stats.rangeCount += ranges.size();
  }
  package.stats.maximumRangeElements = maximumRangeElements;
  return package;
}

}  // namespace

CuperflowVectorPackage encodeVector(const std::vector<double>& input,
                                    const CuperflowConfig& config) {
  // 没有 A package 时不能推导稀疏列集合，因此明确使用连续 X 回退路径。
  return encodeVectorImpl(input, config, nullptr);
}

CuperflowVectorPackage encodeVector(const std::vector<double>& input,
                                    const CuperflowPackage& matrixPackage) {
  if (matrixPackage.columns != input.size()) {
    throw std::invalid_argument("Cuperflow A/X 输入列数不一致");
  }
  return encodeVectorImpl(input, matrixPackage.config, &matrixPackage);
}

void validateXPayloadLoads(const CuperflowPackage& matrixPackage,
                           const CuperflowVectorPackage& vectorPackage) {
  const std::vector<bool> expectedGroups = groupsWithAPayload(matrixPackage);
  const std::uint64_t expectedLoads = static_cast<std::uint64_t>(
      std::count(expectedGroups.begin(), expectedGroups.end(), true));
  if (matrixPackage.columns != vectorPackage.columns ||
      matrixPackage.config.hbmChannelCount != vectorPackage.config.hbmChannelCount ||
      vectorPackage.stats.rangeCount != expectedLoads ||
      matrixPackage.stats.xPayloadLoadCount != expectedLoads ||
      matrixPackage.stats.expectedXPayloadLoadCount != expectedLoads) {
    throw std::invalid_argument("Cuperflow X payload 装载统计与 A package 不一致");
  }
  std::vector<bool> seen(matrixPackage.sliceGroupCount, false);
  std::size_t observedRanges = 0;
  for (std::size_t channel = 0; channel < vectorPackage.channelXRanges.size(); ++channel) {
    for (const CuperflowXRange& range : vectorPackage.channelXRanges[channel]) {
      if (range.sliceGroup >= seen.size() || !expectedGroups[range.sliceGroup] ||
          seen[range.sliceGroup] ||
          channel >= matrixPackage.sliceGroupChannels.size() ||
          matrixPackage.sliceGroupChannels[range.sliceGroup] != channel) {
        throw std::invalid_argument("Cuperflow X payload 发生重复装载或 HBM owner 错配");
      }
      seen[range.sliceGroup] = true;
      ++observedRanges;
    }
  }
  if (observedRanges != expectedLoads || seen != expectedGroups) {
    throw std::invalid_argument("Cuperflow BATCH_DESC 改变了 X payload 装载次数");
  }
}

}  // namespace accelerator_sim::spmv::encoding::cuperflow
