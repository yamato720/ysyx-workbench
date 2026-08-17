#include "cuper.hpp"

#include <algorithm>
#include <cstring>
#include <iterator>
#include <limits>
#include <stdexcept>
#include <utility>

namespace accelerator_sim::spmv::encoding::cuper {

namespace {

constexpr std::size_t kCheckerCount = 8;
constexpr std::uint64_t kColumnMask = (1ULL << kColumnBits) - 1ULL;
constexpr std::uint64_t kTagMask = (1ULL << kTagBits) - 1ULL;
constexpr std::uint64_t kRowMask = (1ULL << kRowBits) - 1ULL;

struct RawElement {
  std::size_t row = 0;
  std::uint32_t column = 0;
  float value = 0.0F;
};

struct ScheduledSlot {
  bool occupied = false;
  RawElement element;
  std::uint32_t accumulationContext = 0;
};

struct ContextResidency {
  bool occupied = false;
  std::size_t row = 0;
  std::size_t lastUsedPosition = 0;
};

std::size_t divideRoundedUp(std::size_t value, std::size_t divisor) {
  return value / divisor + static_cast<std::size_t>(value % divisor != 0);
}

void validateConfig(const CuperConfig& config) {
  if (config.hbmChannelCount == 0 || config.hbmChannelCount % kCheckerCount != 0) {
    throw std::invalid_argument("Cuper HBM channel 数必须是 8 的正整数倍");
  }
  if (config.sliceSize == 0 || config.columnSlicesPerBatch == 0) {
    throw std::invalid_argument("Cuper sliceSize 和 columnSlicesPerBatch 必须大于 0");
  }
  if (config.sliceSize > std::numeric_limits<std::size_t>::max() /
          config.columnSlicesPerBatch) {
    throw std::overflow_error("Cuper column batch 宽度溢出");
  }
  if (columnsPerBatch(config) > (1ULL << kColumnBits)) {
    throw std::invalid_argument("Cuper column batch 宽度超过 13-bit 局部列号范围");
  }
  if (config.hbmChannelCount > std::numeric_limits<std::size_t>::max() /
          kLanesPerBeat) {
    throw std::overflow_error("Cuper PE 数量溢出");
  }
}

std::size_t rowGroupSpan(const CuperConfig& config) {
  const std::size_t totalPes = totalPeCount(config);
  if (totalPes > std::numeric_limits<std::size_t>::max() / 2U) {
    throw std::overflow_error("Cuper row group 跨度溢出");
  }
  return 2U * totalPes;
}

std::size_t localRowForRowUnchecked(std::size_t row, const CuperConfig& config) {
  const std::size_t rowGroup = row / rowGroupSpan(config);
  if (rowGroup > std::numeric_limits<std::size_t>::max() / 2U) {
    throw std::overflow_error("Cuper PE-local 行标溢出");
  }
  return 2U * rowGroup + row % 2U;
}

void validateMatrix(const CsrMatrix& matrix, const CuperConfig& config) {
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
  if (matrix.rows != 0 && localRowForRowUnchecked(matrix.rows - 1U, config) > kRowMask) {
    throw std::invalid_argument("Cuper slot v4 的 16-bit PE-local 行标不能表示该矩阵行数");
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

void assignAccumulationContexts(std::vector<ScheduledSlot>& scheduled) {
  std::array<ContextResidency, kAccumulationContextCount> contexts{};

  // 这 3 位不是 row 的截断或哈希，而是未来局部累加器的驻留表索引。分配器按
  // RAW 重排后的真实发射位置扫描：命中行就复用上下文，未满时取最小空闲编号，
  // 满表后换出最久未使用的行。同一 tag 随后携带不同 row，即表示旧行片段退休、
  // 新行片段开始。空 slot 不参与分配并保持全零；函数每次只处理一个 batch 的
  // 一个 PE，因此上下文不会跨 batch 或 PE 泄漏。
  for (std::size_t position = 0; position < scheduled.size(); ++position) {
    ScheduledSlot& slot = scheduled[position];
    if (!slot.occupied) {
      continue;
    }

    auto selected = std::find_if(contexts.begin(), contexts.end(),
                                 [&slot](const ContextResidency& context) {
                                   return context.occupied &&
                                       context.row == slot.element.row;
                                 });
    if (selected == contexts.end()) {
      selected = std::find_if(contexts.begin(), contexts.end(),
                              [](const ContextResidency& context) {
                                return !context.occupied;
                              });
    }
    if (selected == contexts.end()) {
      selected = std::min_element(contexts.begin(), contexts.end(),
                                  [](const ContextResidency& lhs,
                                     const ContextResidency& rhs) {
                                    return lhs.lastUsedPosition < rhs.lastUsedPosition;
                                  });
    }

    const std::size_t context = static_cast<std::size_t>(
        std::distance(contexts.begin(), selected));
    slot.accumulationContext = static_cast<std::uint32_t>(context);
    *selected = ContextResidency{true, slot.element.row, position};
  }
}

std::uint64_t packSlot(const RawElement& element, std::uint32_t accumulationContext,
                       std::size_t batch, const CuperConfig& config) {
  const std::size_t localRow = localRowForRowUnchecked(element.row, config);
  if (localRow > kRowMask) {
    throw std::overflow_error("Cuper slot v4 的 PE-local 行标超过 16 bit");
  }
  if (accumulationContext >= kAccumulationContextCount) {
    throw std::overflow_error("Cuper 累加上下文超过 3 bit");
  }

  const std::size_t batchWidth = columnsPerBatch(config);
  const std::size_t localColumn = static_cast<std::size_t>(element.column) - batch * batchWidth;
  if (localColumn > kColumnMask) {
    throw std::overflow_error("Cuper 局部列号超过 13 bit");
  }

  std::uint32_t valueBits = 0;
  static_assert(sizeof(valueBits) == sizeof(element.value));
  std::memcpy(&valueBits, &element.value, sizeof(valueBits));
  return (static_cast<std::uint64_t>(localColumn) << 51U) |
      (static_cast<std::uint64_t>(accumulationContext) << 48U) |
      (static_cast<std::uint64_t>(localRow) << 32U) | valueBits;
}

std::size_t peForRowUnchecked(std::size_t row, const CuperConfig& config) {
  const std::size_t packet = row / 2U;
  const std::size_t accumulatorGroupSize = config.hbmChannelCount / kCheckerCount;
  const std::size_t checker = packet % kCheckerCount;
  const std::size_t accumulatorOffset = (packet / kCheckerCount) % accumulatorGroupSize;
  const std::size_t peInAccumulator = (packet / config.hbmChannelCount) % kLanesPerBeat;
  return (checker * accumulatorGroupSize + accumulatorOffset) * kLanesPerBeat +
      peInAccumulator;
}

}  // namespace

double CuperEncodingStats::matrixSlotUtilization() const {
  const std::uint64_t slots = matrixSlots + zeroFillSlots;
  return slots == 0 ? 0.0 : static_cast<double>(matrixSlots) / static_cast<double>(slots);
}

std::size_t columnsPerBatch(const CuperConfig& config) {
  return config.sliceSize * config.columnSlicesPerBatch;
}

std::size_t totalPeCount(const CuperConfig& config) {
  return config.hbmChannelCount * kLanesPerBeat;
}

std::size_t peForRow(std::size_t row, const CuperConfig& config) {
  validateConfig(config);
  return peForRowUnchecked(row, config);
}

std::size_t localRowForRow(std::size_t row, const CuperConfig& config) {
  validateConfig(config);
  const std::size_t localRow = localRowForRowUnchecked(row, config);
  if (localRow > kRowMask) {
    throw std::out_of_range("Cuper slot v4 的 PE-local 行标超过 16 bit");
  }
  return localRow;
}

std::size_t rowForPeLocal(std::size_t physicalPe, std::size_t localRow,
                          const CuperConfig& config) {
  validateConfig(config);
  const std::size_t totalPes = totalPeCount(config);
  if (physicalPe >= totalPes) {
    throw std::out_of_range("Cuper 物理 PE 超出配置范围");
  }
  if (localRow > kRowMask) {
    throw std::out_of_range("Cuper PE-local 行标超过 16 bit");
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
    throw std::overflow_error("Cuper 全局行标反解溢出");
  }
  const std::size_t packet = rowGroup * totalPes + logicalPacket;
  if (packet > (std::numeric_limits<std::size_t>::max() - localRow % 2U) / 2U) {
    throw std::overflow_error("Cuper 全局行标反解溢出");
  }
  return 2U * packet + localRow % 2U;
}

DecodedCuperSlot decodeSlot(std::uint64_t slot) {
  DecodedCuperSlot decoded;
  decoded.localColumn = static_cast<std::uint32_t>((slot >> 51U) & kColumnMask);
  decoded.tag = static_cast<std::uint32_t>((slot >> 48U) & kTagMask);
  decoded.localRow = static_cast<std::uint32_t>((slot >> 32U) & kRowMask);
  const std::uint32_t valueBits = static_cast<std::uint32_t>(slot);
  static_assert(sizeof(valueBits) == sizeof(decoded.value));
  std::memcpy(&decoded.value, &valueBits, sizeof(valueBits));
  return decoded;
}

CuperPackage encode(const CsrMatrix& matrix, const CuperConfig& config) {
  validateConfig(config);
  validateMatrix(matrix, config);

  const std::size_t batchWidth = columnsPerBatch(config);
  const std::size_t batchCount = divideRoundedUp(matrix.columns, batchWidth);
  const std::size_t totalPes = totalPeCount(config);
  const std::size_t rowGroupCount = divideRoundedUp(matrix.rows, rowGroupSpan(config));

  using PeElements = std::vector<std::vector<RawElement>>;
  std::vector<PeElements> batches;
  batches.reserve(batchCount);
  for (std::size_t batch = 0; batch < batchCount; ++batch) {
    batches.emplace_back(totalPes);
  }

  for (std::size_t row = 0; row < matrix.rows; ++row) {
    const std::size_t begin = static_cast<std::size_t>(matrix.rowPointers[row]);
    const std::size_t end = static_cast<std::size_t>(matrix.rowPointers[row + 1U]);
    for (std::size_t index = begin; index < end; ++index) {
      const std::uint32_t column = matrix.columnIndices[index];
      const std::size_t batch = static_cast<std::size_t>(column) / batchWidth;
      const std::size_t pe = peForRowUnchecked(row, config);
      batches[batch][pe].push_back(RawElement{row, column,
          static_cast<float>(matrix.values[index])});
    }
  }

  CuperBeat zeroFillBeat{};
  zeroFillBeat.fill(kZeroFillSlot);
  std::vector<std::vector<CuperBeat>> channels(config.hbmChannelCount);
  std::vector<std::vector<std::uint8_t>> entryMasks(config.hbmChannelCount);
  std::vector<std::vector<std::uint32_t>> channelBatchPointers(
      config.hbmChannelCount, std::vector<std::uint32_t>(batchCount + 1U, 0));
  std::uint64_t matrixSlots = 0;
  for (std::size_t batch = 0; batch < batchCount; ++batch) {
    std::vector<std::vector<ScheduledSlot>> scheduledStreams(totalPes);
    for (std::size_t pe = 0; pe < totalPes; ++pe) {
      std::vector<RawElement>& elements = batches[batch][pe];
      std::stable_sort(elements.begin(), elements.end(),
                       [](const RawElement& lhs, const RawElement& rhs) {
                         return lhs.column < rhs.column;
                       });

      std::vector<ScheduledSlot> scheduled;
      // 保持原 Cuper ping/pong 排程；L1 的目标映射以后单独定义。
      std::vector<std::size_t> nextPosition(rowGroupCount * 2U, 0);
      for (const RawElement& element : elements) {
        const std::size_t rowGroup = element.row / rowGroupSpan(config);
        const std::size_t accumulatorTarget = rowGroup * 2U + element.row % 2U;
        std::size_t position = nextPosition[accumulatorTarget];
        while (position < scheduled.size() && scheduled[position].occupied) {
          ++position;
        }
        if (position == std::numeric_limits<std::size_t>::max()) {
          throw std::overflow_error("Cuper reorder 位置溢出");
        }
        if (position >= scheduled.size()) {
          scheduled.resize(position + 1U);
        }
        scheduled[position] = ScheduledSlot{true, element, 0};
        if (config.reorderWindow > std::numeric_limits<std::size_t>::max() - position) {
          throw std::overflow_error("Cuper reorder window 溢出");
        }
        nextPosition[accumulatorTarget] = position + config.reorderWindow;
      }

      assignAccumulationContexts(scheduled);
      scheduledStreams[pe] = std::move(scheduled);
    }

    for (std::size_t channel = 0; channel < config.hbmChannelCount; ++channel) {
      std::size_t batchBeats = 0;
      for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
        const std::size_t pe = channel * kLanesPerBeat + lane;
        batchBeats = std::max(batchBeats, scheduledStreams[pe].size());
      }

      std::vector<CuperBeat>& channelBeats = channels[channel];
      const std::size_t channelBegin = channelBeats.size();
      if (channelBegin > std::numeric_limits<std::uint32_t>::max() ||
          batchBeats > std::numeric_limits<std::uint32_t>::max() - channelBegin) {
        throw std::overflow_error("Cuper per-HBM batch pointer 超过 uint32_t 范围");
      }
      channelBeats.insert(channelBeats.end(), batchBeats, zeroFillBeat);
      entryMasks[channel].insert(entryMasks[channel].end(), batchBeats, 0U);
      channelBatchPointers[channel][batch + 1U] =
          static_cast<std::uint32_t>(channelBegin + batchBeats);

      for (std::size_t batchBeat = 0; batchBeat < batchBeats; ++batchBeat) {
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          const std::size_t pe = channel * kLanesPerBeat + lane;
          if (batchBeat >= scheduledStreams[pe].size()) {
            continue;
          }
          const ScheduledSlot& scheduled = scheduledStreams[pe][batchBeat];
          if (scheduled.occupied) {
            channelBeats[channelBegin + batchBeat][lane] =
                packSlot(scheduled.element, scheduled.accumulationContext, batch, config);
            entryMasks[channel][channelBegin + batchBeat] |= static_cast<std::uint8_t>(1U << lane);
            ++matrixSlots;
          }
        }
      }
    }
  }
  if (matrixSlots != matrix.values.size()) {
    throw std::logic_error("Cuper 编码后的矩阵 slot 数与输入 nnz 不一致");
  }

  std::size_t minimumBeatsPerChannel = channels.empty() ? 0 : channels.front().size();
  std::size_t maximumBeatsPerChannel = 0;
  std::uint64_t totalBeats = 0;
  for (const std::vector<CuperBeat>& channel : channels) {
    minimumBeatsPerChannel = std::min(minimumBeatsPerChannel, channel.size());
    maximumBeatsPerChannel = std::max(maximumBeatsPerChannel, channel.size());
    if (channel.size() > std::numeric_limits<std::uint64_t>::max() - totalBeats) {
      throw std::overflow_error("Cuper 总 beat 数溢出");
    }
    totalBeats += channel.size();
  }
  if (totalBeats > std::numeric_limits<std::uint64_t>::max() / kLanesPerBeat) {
    throw std::overflow_error("Cuper 总 slot 数溢出");
  }
  const std::uint64_t totalSlots = totalBeats * kLanesPerBeat;
  if (totalBeats > std::numeric_limits<std::uint64_t>::max() / 64U) {
    throw std::overflow_error("Cuper package 字节数溢出");
  }

  CuperPackage package;
  package.config = config;
  package.rows = matrix.rows;
  package.columns = matrix.columns;
  package.nonzeros = matrix.values.size();
  package.channelBatchPointers = std::move(channelBatchPointers);
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

CuperVectorPackage encodeVector(const std::vector<double>& input,
                                const CuperConfig& config) {
  validateConfig(config);
  if (input.empty()) {
    throw std::invalid_argument("Cuper X 输入不能为空");
  }

  const std::size_t batchWidth = columnsPerBatch(config);
  if (batchWidth % kVectorLanesPerBeat != 0) {
    throw std::invalid_argument("Cuper X column batch 宽度必须按 float_v16 对齐");
  }
  const std::size_t batchCount = divideRoundedUp(input.size(), batchWidth);
  const std::size_t payloadBeats = divideRoundedUp(input.size(), kVectorLanesPerBeat);
  const std::size_t allocatedElements = divideRoundedUp(
      input.size(), kVectorStorageAlignmentElements) * kVectorStorageAlignmentElements;
  const std::size_t allocatedBeats = allocatedElements / kVectorLanesPerBeat;
  if (payloadBeats > std::numeric_limits<std::uint32_t>::max()) {
    throw std::overflow_error("Cuper X batch pointer 超过 uint32_t 范围");
  }
  if (allocatedBeats > std::numeric_limits<std::uint64_t>::max() / 64U) {
    throw std::overflow_error("Cuper X HBM 分配字节数溢出");
  }

  CuperVectorPackage package;
  package.config = config;
  package.columns = input.size();
  package.sourceValues = input;
  package.batchPointers.resize(batchCount + 1U, 0);
  package.hbmBeats.resize(allocatedBeats);

  for (std::size_t batch = 0; batch < batchCount; ++batch) {
    const std::size_t endColumn = std::min((batch + 1U) * batchWidth, input.size());
    package.batchPointers[batch + 1U] = static_cast<std::uint32_t>(
        divideRoundedUp(endColumn, kVectorLanesPerBeat));
  }
  for (std::size_t column = 0; column < input.size(); ++column) {
    const float encoded = static_cast<float>(input[column]);
    std::uint32_t bits = 0;
    static_assert(sizeof(bits) == sizeof(encoded));
    std::memcpy(&bits, &encoded, sizeof(bits));
    package.hbmBeats[column / kVectorLanesPerBeat][column % kVectorLanesPerBeat] = bits;
  }

  package.stats.batchCount = batchCount;
  package.stats.payloadBeats = payloadBeats;
  package.stats.allocatedBeats = allocatedBeats;
  package.stats.validElements = input.size();
  package.stats.lanePaddingElements = payloadBeats * kVectorLanesPerBeat - input.size();
  package.stats.allocationPaddingElements = allocatedElements -
      payloadBeats * kVectorLanesPerBeat;
  package.stats.packedBytes = payloadBeats * 64U;
  package.stats.allocatedBytes = allocatedBeats * 64U;
  return package;
}

}  // namespace accelerator_sim::spmv::encoding::cuper
