#include "cuper.hpp"

#include <algorithm>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <utility>

namespace accelerator_sim::spmv::encoding::cuper {

namespace {

constexpr std::size_t kCheckerCount = 8;
constexpr std::uint64_t kColumnMask = (1ULL << kColumnBits) - 1ULL;
constexpr std::uint64_t kRowMask = (1ULL << kRowBits) - 1ULL;

struct RawElement {
  std::size_t row = 0;
  std::uint32_t column = 0;
  float value = 0.0F;
};

struct ScheduledElement {
  bool valid = false;
  RawElement element;
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
    throw std::invalid_argument("Cuper column batch 宽度超过 14-bit 局部列号范围");
  }
  if (config.hbmChannelCount > std::numeric_limits<std::size_t>::max() /
          kLanesPerBeat) {
    throw std::overflow_error("Cuper PE 数量溢出");
  }
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

std::uint64_t packSlot(const RawElement& element, std::size_t batch,
                       const CuperConfig& config) {
  const std::size_t totalPes = totalPeCount(config);
  const std::size_t rowGroupSpan = 2U * totalPes;
  const std::size_t rowGroup = element.row / rowGroupSpan;
  if (rowGroup > kMaximumValidRow / 2U) {
    throw std::overflow_error("Cuper 编码行号超过 17-bit 有效地址范围");
  }
  const std::size_t encodedRow = rowGroup * 2U + element.row % 2U;
  if (encodedRow > kMaximumValidRow) {
    throw std::overflow_error("Cuper 编码行号占用了 padding 标志位");
  }

  const std::size_t batchWidth = columnsPerBatch(config);
  const std::size_t localColumn = static_cast<std::size_t>(element.column) - batch * batchWidth;
  if (localColumn > kColumnMask) {
    throw std::overflow_error("Cuper 局部列号超过 14 bit");
  }

  std::uint32_t valueBits = 0;
  static_assert(sizeof(valueBits) == sizeof(element.value));
  std::memcpy(&valueBits, &element.value, sizeof(valueBits));
  return (static_cast<std::uint64_t>(localColumn) << 50U) |
      (static_cast<std::uint64_t>(encodedRow) << 32U) | valueBits;
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

double CuperEncodingStats::slotUtilization() const {
  const std::uint64_t slots = validSlots + paddingSlots;
  return slots == 0 ? 0.0 : static_cast<double>(validSlots) / static_cast<double>(slots);
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

std::size_t decodeOriginalRow(std::uint32_t encodedRow, std::size_t pe,
                              const CuperConfig& config) {
  validateConfig(config);
  if (encodedRow > kMaximumValidRow) {
    throw std::invalid_argument("padding row 不能还原为原始行号");
  }
  const std::size_t totalPes = totalPeCount(config);
  if (pe >= totalPes) {
    throw std::out_of_range("Cuper PE 编号越界");
  }
  const std::size_t accumulatorGroupSize = config.hbmChannelCount / kCheckerCount;
  const std::size_t peInAccumulator = pe % kLanesPerBeat;
  const std::size_t checkerAndOffset = pe / kLanesPerBeat;
  const std::size_t checker = checkerAndOffset / accumulatorGroupSize;
  const std::size_t accumulatorOffset = checkerAndOffset % accumulatorGroupSize;
  const std::size_t packetRemainder = checker + kCheckerCount * accumulatorOffset +
      config.hbmChannelCount * peInAccumulator;
  const std::size_t rowGroup = encodedRow / 2U;
  return (rowGroup * totalPes + packetRemainder) * 2U + encodedRow % 2U;
}

DecodedCuperSlot decodeSlot(std::uint64_t slot) {
  DecodedCuperSlot decoded;
  decoded.localColumn = static_cast<std::uint32_t>((slot >> 50U) & kColumnMask);
  decoded.encodedRow = static_cast<std::uint32_t>((slot >> 32U) & kRowMask);
  decoded.padding = (decoded.encodedRow & (1U << (kRowBits - 1U))) != 0;
  const std::uint32_t valueBits = static_cast<std::uint32_t>(slot);
  static_assert(sizeof(valueBits) == sizeof(decoded.value));
  std::memcpy(&decoded.value, &valueBits, sizeof(valueBits));
  return decoded;
}

CuperPackage encode(const CsrMatrix& matrix, const CuperConfig& config) {
  validateConfig(config);
  validateMatrix(matrix);

  const std::size_t batchWidth = columnsPerBatch(config);
  const std::size_t batchCount = divideRoundedUp(matrix.columns, batchWidth);
  const std::size_t totalPes = totalPeCount(config);
  if (totalPes > std::numeric_limits<std::size_t>::max() / 2U) {
    throw std::overflow_error("Cuper row group 跨度溢出");
  }
  const std::size_t rowGroupSpan = 2U * totalPes;
  const std::size_t rowGroupCount = divideRoundedUp(matrix.rows, rowGroupSpan);

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

  CuperBeat paddingBeat{};
  paddingBeat.fill(kPaddingSlot);
  std::vector<std::vector<CuperBeat>> channels(config.hbmChannelCount);
  std::vector<std::vector<std::uint32_t>> channelBatchPointers(
      config.hbmChannelCount, std::vector<std::uint32_t>(batchCount + 1U, 0));
  std::uint64_t validSlots = 0;
  for (std::size_t batch = 0; batch < batchCount; ++batch) {
    std::vector<std::vector<ScheduledElement>> scheduledStreams(totalPes);
    for (std::size_t pe = 0; pe < totalPes; ++pe) {
      std::vector<RawElement>& elements = batches[batch][pe];
      std::stable_sort(elements.begin(), elements.end(),
                       [](const RawElement& lhs, const RawElement& rhs) {
                         return lhs.column < rhs.column;
                       });

      std::vector<ScheduledElement> scheduled;
      // ping/pong 分别保存偶数行和奇数行，同一 rowGroup 的两个 parity 不存在 RAW 冲突。
      std::vector<std::size_t> nextPosition(rowGroupCount * 2U, 0);
      for (const RawElement& element : elements) {
        const std::size_t rowGroup = element.row / rowGroupSpan;
        const std::size_t accumulatorTarget = rowGroup * 2U + element.row % 2U;
        std::size_t position = nextPosition[accumulatorTarget];
        while (position < scheduled.size() && scheduled[position].valid) {
          ++position;
        }
        if (position == std::numeric_limits<std::size_t>::max()) {
          throw std::overflow_error("Cuper reorder 位置溢出");
        }
        if (position >= scheduled.size()) {
          scheduled.resize(position + 1U);
        }
        scheduled[position] = ScheduledElement{true, element};
        if (config.reorderWindow > std::numeric_limits<std::size_t>::max() - position) {
          throw std::overflow_error("Cuper reorder window 溢出");
        }
        nextPosition[accumulatorTarget] = position + config.reorderWindow;
      }

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
      channelBeats.insert(channelBeats.end(), batchBeats, paddingBeat);
      channelBatchPointers[channel][batch + 1U] =
          static_cast<std::uint32_t>(channelBegin + batchBeats);

      for (std::size_t batchBeat = 0; batchBeat < batchBeats; ++batchBeat) {
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          const std::size_t pe = channel * kLanesPerBeat + lane;
          if (batchBeat >= scheduledStreams[pe].size()) {
            continue;
          }
          const ScheduledElement& scheduled = scheduledStreams[pe][batchBeat];
          if (scheduled.valid) {
            channelBeats[channelBegin + batchBeat][lane] =
                packSlot(scheduled.element, batch, config);
            ++validSlots;
          }
        }
      }
    }
  }
  if (validSlots != matrix.values.size()) {
    throw std::logic_error("Cuper 编码后的有效 slot 数与输入 nnz 不一致");
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
  package.stats.batchCount = batchCount;
  package.stats.minimumMatrixBeatsPerChannel = minimumBeatsPerChannel;
  package.stats.maximumMatrixBeatsPerChannel = maximumBeatsPerChannel;
  package.stats.totalMatrixBeats = totalBeats;
  package.stats.validSlots = validSlots;
  package.stats.paddingSlots = totalSlots - validSlots;
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
