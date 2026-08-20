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
  return kXMapMarkerBase | (last ? kXMapMarkerLastMask : 0U);
}

bool isXMapMarker(std::uint64_t word) {
  return (word & ~kXMapMarkerLastMask) == kXMapMarkerBase;
}

CuperflowVectorBeat packMapBeat(const CuperflowMapBeat& map) {
  CuperflowVectorBeat beat{};
  beat[0] = makeXMapMarker(map.last);
  beat[1] = static_cast<std::uint64_t>(map.xBeats) |
      (static_cast<std::uint64_t>(map.xWords) << 32U);
  beat[2] = static_cast<std::uint64_t>(map.aOffsetBeats) |
      (static_cast<std::uint64_t>(map.aBeats) << 32U);
  beat[3] = static_cast<std::uint64_t>(map.firstBatch) |
      (static_cast<std::uint64_t>(map.sliceGroup) << 16U) |
      (static_cast<std::uint64_t>(map.xElements) << 32U) |
      (map.last ? (std::uint64_t{1} << 48U) : 0U);
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
  map.aOffsetBeats = static_cast<std::uint32_t>(beat[2]);
  map.aBeats = static_cast<std::uint32_t>(beat[2] >> 32U);
  map.firstBatch = static_cast<std::uint16_t>(beat[3]);
  map.sliceGroup = static_cast<std::uint16_t>(beat[3] >> 16U);
  map.xElements = static_cast<std::uint16_t>(beat[3] >> 32U);
  for (std::size_t segment = 0; segment < kMaxXSegments; ++segment) {
    const std::uint32_t descriptor = static_cast<std::uint32_t>(
        beat[4U + segment / 2U] >> (32U * (segment % 2U)));
    map.xSegments[segment] = CuperflowXSegment{
        static_cast<std::uint16_t>(descriptor & kXAddressMarkerAddressMask),
        static_cast<std::uint16_t>((descriptor >> kColumnBits) &
                                   ((std::uint32_t{1} << kXSegmentCountBits) - 1U))};
  }
  map.last = ((beat[0] & kXMapMarkerLastMask) != 0U) ||
      (((beat[3] >> 48U) & 1U) != 0U);
  return map;
}

namespace {

constexpr std::size_t kCheckerCount = 8;
constexpr std::uint64_t kColumnMask = (1ULL << kColumnBits) - 1ULL;
constexpr std::uint64_t kTagMask = (1ULL << kTagBits) - 1ULL;
constexpr std::uint64_t kRowMask = (1ULL << kRowBits) - 1ULL;

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

std::size_t divideRoundedUp(std::size_t value, std::size_t divisor) {
  return value / divisor + static_cast<std::size_t>(value % divisor != 0);
}

void validateConfig(const CuperflowConfig& config) {
  if (config.hbmChannelCount == 0 || config.hbmChannelCount % kCheckerCount != 0) {
    throw std::invalid_argument("Cuperflow HBM channel 数必须是 8 的正整数倍");
  }
  if (config.sliceSize == 0 || config.rowBatchSize == 0 || config.xSlicesPerBatch == 0) {
    throw std::invalid_argument(
        "Cuperflow sliceSize、rowBatchSize 和 xSlicesPerBatch 必须大于 0");
  }
  if (config.rowBatchSize > kRowMask + 1U) {
    throw std::invalid_argument(
        "Cuperflow rowBatchSize 必须能由 slot v5 的 16-bit batch-local 行标表示");
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
    throw std::overflow_error("Cuperflow slot v5 的 batch-local 行标超过 16 bit");
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

std::size_t peForRowUnchecked(std::size_t row, const CuperflowConfig& config) {
  const std::size_t packet = row / 2U;
  const std::size_t accumulatorGroupSize = config.hbmChannelCount / kCheckerCount;
  const std::size_t checker = packet % kCheckerCount;
  const std::size_t accumulatorOffset = (packet / kCheckerCount) % accumulatorGroupSize;
  const std::size_t peInAccumulator = (packet / config.hbmChannelCount) % kLanesPerBeat;
  return (checker * accumulatorGroupSize + accumulatorOffset) * kLanesPerBeat +
      peInAccumulator;
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
    throw std::out_of_range("Cuperflow slot v5 的 PE-local 行标超过 16 bit");
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
    throw std::out_of_range("Cuperflow PE-local 行标超过 16 bit");
  }

  const std::size_t accumulatorGroupSize = config.hbmChannelCount / kCheckerCount;
  const std::size_t block = physicalPe / kLanesPerBeat;
  const std::size_t checker = block / accumulatorGroupSize;
  const std::size_t accumulatorOffset = block % accumulatorGroupSize;
  const std::size_t peInAccumulator = physicalPe % kLanesPerBeat;
  const std::size_t logicalPacket = checker + kCheckerCount * accumulatorOffset +
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
  decoded.localRow = static_cast<std::uint32_t>((slot >> 32U) & kRowMask);
  const std::uint32_t valueBits = static_cast<std::uint32_t>(slot);
  static_assert(sizeof(valueBits) == sizeof(decoded.value));
  std::memcpy(&decoded.value, &valueBits, sizeof(valueBits));
  return decoded;
}

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
  for (const std::uint32_t column : matrix.columnIndices) {
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
  std::uint64_t matrixSlots = 0;
  const std::vector<std::size_t> physicalToOriginalRows =
      buildRowPermutation(matrix, config);
  for (std::size_t batch = 0; batch < batchCount; ++batch) {
    const std::size_t firstRow = batch * config.rowBatchSize;
    const std::size_t lastRow = std::min(firstRow + config.rowBatchSize, matrix.rows);
    const std::size_t firstNnz = static_cast<std::size_t>(matrix.rowPointers[firstRow]);
    const std::size_t lastNnz = static_cast<std::size_t>(matrix.rowPointers[lastRow]);
    const std::size_t batchNnz = lastNnz - firstNnz;

    // 只为实际出现的 (slice, PE) 建 bucket。百万级矩阵的空组合数量远大于非零元数量，
    // 不再像旧实现一样为所有 slice * PE 组合构造嵌套 vector。
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
        const GroupLaneWords& words = batchGroupLanes[batch][group];
        std::size_t unionBeats = 0;
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          unionBeats = std::max(unionBeats, words[lane].size());
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
              static_cast<std::uint32_t>(dest + words[lane].size())};
          for (std::size_t index = 0; index < words[lane].size(); ++index) {
            channels[channel][dest + index][lane] = words[lane][index];
            entryMasks[channel][dest + index] |= static_cast<std::uint8_t>(1U << lane);
            ++matrixSlots;
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
  if (matrixSlots != matrix.values.size()) {
    throw std::logic_error("Cuperflow 编码后的矩阵 slot 数与输入 nnz 不一致");
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
  package.nonzeros = matrix.values.size();
  package.physicalToOriginalRows = std::move(physicalToOriginalRows);
  package.columnSliceCount = sliceCount;
  package.sliceGroupSize = groupSize;
  package.sliceGroupCount = groupCount;
  package.sliceGroupChannels = std::move(sliceGroupChannels);
  package.channelSliceGroups = std::move(channelSliceGroups);
  package.xUsedColumnsByGroup = std::move(xUsedColumnsByGroup);
  package.xSegmentsByGroup = std::move(xSegmentsByGroup);
  package.channelBatchPointers = std::move(channelBatchPointers);
  package.channelLaneSliceGroupRanges = std::move(channelLaneSliceGroupRanges);
  package.channelGroupARanges = std::move(channelGroupARanges);
  package.matrixChannels = std::move(channels);
  package.matrixEntryMasks = std::move(entryMasks);
  package.stats.batchCount = batchCount;
  package.stats.minimumMatrixBeatsPerChannel = minimumBeatsPerChannel;
  package.stats.maximumMatrixBeatsPerChannel = maximumBeatsPerChannel;
  package.stats.totalMatrixBeats = totalBeats;
  package.stats.matrixSlots = matrixSlots;
  package.stats.zeroFillSlots = totalSlots - matrixSlots;
  package.stats.packedBytes = totalBeats * 64U;
  return package;
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
        map.aOffsetBeats = item.aOffsetBeats;
        map.aBeats = item.aBeats;
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
  package.stats.rangeCount = groupCount;
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

}  // namespace accelerator_sim::spmv::encoding::cuperflow
