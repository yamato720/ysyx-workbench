#include "report.hpp"
#include "report_ui.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace accelerator_sim::spmv::encoding::cuperflow {

namespace {

void writeJsonString(std::ostream& output, std::string_view value) {
  output << '"';
  for (const char rawCharacter : value) {
    const unsigned char character = static_cast<unsigned char>(rawCharacter);
    switch (character) {
      case '"': output << "\\\""; break;
      case '\\': output << "\\\\"; break;
      case '\b': output << "\\b"; break;
      case '\f': output << "\\f"; break;
      case '\n': output << "\\n"; break;
      case '\r': output << "\\r"; break;
      case '\t': output << "\\t"; break;
      case '<': output << "\\u003c"; break;
      case '>': output << "\\u003e"; break;
      case '&': output << "\\u0026"; break;
      default:
        if (character < 0x20U) {
          output << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                 << static_cast<unsigned int>(character) << std::dec << std::setfill(' ');
        } else {
          output << character;
        }
        break;
    }
  }
  output << '"';
}

std::string hexadecimal(std::uint64_t value, unsigned int digits) {
  std::ostringstream output;
  output << "0x" << std::hex << std::setw(static_cast<int>(digits)) << std::setfill('0')
         << value;
  return output.str();
}

std::string formatFloat(float value) {
  if (std::isnan(value)) {
    return "nan";
  }
  if (std::isinf(value)) {
    return std::signbit(value) ? "-inf" : "inf";
  }
  std::ostringstream output;
  output << std::setprecision(std::numeric_limits<float>::max_digits10) << value;
  return output.str();
}

std::uint32_t floatBits(float value) {
  std::uint32_t bits = 0;
  static_assert(sizeof(bits) == sizeof(value));
  std::memcpy(&bits, &value, sizeof(bits));
  return bits;
}

std::size_t divideRoundedUp(std::size_t value, std::size_t divisor) {
  return value / divisor + static_cast<std::size_t>(value % divisor != 0);
}

struct ContextHistory {
  bool occupied = false;
  std::size_t row = 0;
};

std::size_t findLaneGroup(const CuperflowPackage& package, std::size_t channel,
                          std::size_t firstSegment, std::size_t groupCount,
                          std::size_t lane, std::size_t beat) {
  std::size_t low = 0;
  std::size_t high = groupCount;
  while (low < high) {
    const std::size_t middle = low + (high - low) / 2U;
    if (package.channelLaneSliceGroupRanges[channel][firstSegment + middle][lane].second <= beat) {
      low = middle + 1U;
    } else {
      high = middle;
    }
  }
  if (low >= groupCount) {
    throw std::invalid_argument("Cuperflow HTML 报告无法为矩阵 slot 定位 slice group");
  }
  const auto range = package.channelLaneSliceGroupRanges[channel][firstSegment + low][lane];
  if (range.first > beat || range.second <= beat) {
    throw std::invalid_argument("Cuperflow HTML 报告无法为矩阵 slot 定位 slice group");
  }
  return low;
}

void validatePackage(const CuperflowPackage& package) {
  if (package.physicalToOriginalRows.size() != package.rows) {
    throw std::invalid_argument("Cuperflow HTML 报告的 physical row 映射长度不一致");
  }
  std::vector<bool> seenRows(package.rows, false);
  for (const std::size_t originalRow : package.physicalToOriginalRows) {
    if (originalRow >= package.rows || seenRows[originalRow]) {
      throw std::invalid_argument("Cuperflow HTML 报告的 physical row 映射不是合法排列");
    }
    seenRows[originalRow] = true;
  }
  if (package.matrixChannels.size() != package.config.hbmChannelCount) {
    throw std::invalid_argument("Cuperflow HTML 报告的 HBM channel 数量与 config 不一致");
  }
  if (package.channelBatchPointers.size() != package.config.hbmChannelCount) {
    throw std::invalid_argument("Cuperflow HTML 报告的 per-HBM batch pointer 数量不一致");
  }
  if (package.channelLaneSliceGroupRanges.size() != package.config.hbmChannelCount) {
    throw std::invalid_argument("Cuperflow HTML 报告的 per-HBM slice group range 数量不一致");
  }
  if (package.matrixEntryMasks.size() != package.config.hbmChannelCount) {
    throw std::invalid_argument("Cuperflow HTML 报告的矩阵 slot 带外掩码数量不一致");
  }
  if (package.sliceGroupSize == 0 ||
      package.sliceGroupCount != divideRoundedUp(package.columnSliceCount,
                                                 package.sliceGroupSize)) {
    throw std::invalid_argument("Cuperflow HTML 报告的 slice group 配置不一致");
  }
  if (package.sliceGroupSize > kMaxXRangeElements / package.config.sliceSize ||
      package.sliceGroupChannels.size() != package.sliceGroupCount ||
      package.channelSliceGroups.size() != package.config.hbmChannelCount) {
    throw std::invalid_argument("Cuperflow HTML 报告的 HBM X range ownership 不一致");
  }
  std::vector<bool> seenGroups(package.sliceGroupCount, false);
  for (std::size_t group = 0; group < package.sliceGroupCount; ++group) {
    if (package.sliceGroupChannels[group] >= package.config.hbmChannelCount) {
      throw std::invalid_argument("Cuperflow HTML 报告收到越界的 slice group HBM 映射");
    }
  }
  for (std::size_t channel = 0; channel < package.channelSliceGroups.size(); ++channel) {
    for (const std::size_t group : package.channelSliceGroups[channel]) {
      if (group >= package.sliceGroupCount || seenGroups[group] ||
          package.sliceGroupChannels[group] != channel) {
        throw std::invalid_argument("Cuperflow HTML 报告的 slice group HBM 映射不是排列");
      }
      seenGroups[group] = true;
    }
  }
  if (!std::all_of(seenGroups.begin(), seenGroups.end(), [](bool seen) { return seen; })) {
    throw std::invalid_argument("Cuperflow HTML 报告的 slice group HBM 映射不完整");
  }

  std::uint64_t totalBeats = 0;
  std::size_t minimumBeats = package.matrixChannels.empty() ?
      0 : package.matrixChannels.front().size();
  std::size_t maximumBeats = 0;
  for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
    const std::vector<std::uint32_t>& pointers = package.channelBatchPointers[channel];
    if (pointers.size() != package.stats.batchCount + 1U || pointers.empty() ||
        pointers.front() != 0) {
      throw std::invalid_argument("Cuperflow HTML 报告收到非法 per-HBM batch pointers");
    }
    for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
      if (pointers[batch] > pointers[batch + 1U]) {
        throw std::invalid_argument("Cuperflow HTML 报告要求 per-HBM batch pointers 单调不减");
      }
    }
    if (pointers.back() != package.matrixChannels[channel].size()) {
      throw std::invalid_argument("Cuperflow HTML 报告的 channel 长度与 batch pointer 不一致");
    }
    const auto& laneSliceGroupRanges = package.channelLaneSliceGroupRanges[channel];
    if (package.sliceGroupCount != 0 && package.stats.batchCount >
        std::numeric_limits<std::size_t>::max() / package.sliceGroupCount) {
      throw std::invalid_argument("Cuperflow HTML 报告的 row batch-slice group 数量溢出");
    }
    const std::size_t groupSegmentCount = package.stats.batchCount * package.sliceGroupCount;
    if (laneSliceGroupRanges.size() != groupSegmentCount) {
      throw std::invalid_argument("Cuperflow HTML 报告收到非法 per-HBM slice group ranges");
    }
    for (std::size_t segment = 0; segment < groupSegmentCount; ++segment) {
      const std::size_t group = segment % package.sliceGroupCount;
      for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
        const auto range = laneSliceGroupRanges[segment][lane];
        if (range.first > range.second || range.second > package.matrixChannels[channel].size()) {
          throw std::invalid_argument(
              "Cuperflow HTML 报告要求 per-HBM slice group range 合法");
        }
        if (package.sliceGroupChannels[group] != channel && range.first != range.second) {
          throw std::invalid_argument(
              "Cuperflow HTML 报告发现非 owner HBM 持有 slice group A 数据");
        }
      }
    }
    for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
      const std::size_t firstGroupSegment = batch * package.sliceGroupCount;
      for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
        if (package.sliceGroupCount == 0) {
          continue;
        }
        const auto firstRange = laneSliceGroupRanges[firstGroupSegment][lane];
        const auto lastRange = laneSliceGroupRanges[
            firstGroupSegment + package.sliceGroupCount - 1U][lane];
        if (firstRange.first != pointers[batch] || lastRange.second > pointers[batch + 1U]) {
          throw std::invalid_argument("Cuperflow HTML 报告的 batch/lane group range 不连续");
        }
        for (std::size_t group = 1; group < package.sliceGroupCount; ++group) {
          const auto previousRange = laneSliceGroupRanges[
              firstGroupSegment + group - 1U][lane];
          const auto currentRange = laneSliceGroupRanges[
              firstGroupSegment + group][lane];
          if (previousRange.second != currentRange.first) {
            throw std::invalid_argument(
                "Cuperflow HTML 报告的 slice group range 中间存在空洞");
          }
        }
      }
    }
    if (package.matrixEntryMasks[channel].size() != package.matrixChannels[channel].size()) {
      throw std::invalid_argument("Cuperflow HTML 报告的矩阵 slot 带外掩码长度不一致");
    }
    minimumBeats = std::min(minimumBeats, package.matrixChannels[channel].size());
    maximumBeats = std::max(maximumBeats, package.matrixChannels[channel].size());
    totalBeats += package.matrixChannels[channel].size();
  }
  if (minimumBeats != package.stats.minimumMatrixBeatsPerChannel ||
      maximumBeats != package.stats.maximumMatrixBeatsPerChannel ||
      totalBeats != package.stats.totalMatrixBeats) {
    throw std::invalid_argument("Cuperflow HTML 报告的动态 channel beat 统计不一致");
  }
  if (totalBeats > std::numeric_limits<std::uint64_t>::max() / kLanesPerBeat ||
      totalBeats * kLanesPerBeat != package.stats.matrixSlots + package.stats.zeroFillSlots) {
    throw std::invalid_argument("Cuperflow HTML 报告的动态 channel slot 统计不一致");
  }
  if (totalBeats > std::numeric_limits<std::uint64_t>::max() / 64U ||
      totalBeats * 64U != package.stats.packedBytes) {
    throw std::invalid_argument("Cuperflow HTML 报告的动态 package 字节统计不一致");
  }
}

}  // namespace

void writeHtmlReport(std::ostream& output, const CuperflowPackage& package,
                     std::string_view datasetName, std::string_view sourcePath) {
  validatePackage(package);

  std::vector<std::uint64_t> channelMatrix(package.config.hbmChannelCount, 0);
  std::vector<std::uint64_t> channelZeroFill(package.config.hbmChannelCount, 0);
  std::vector<std::uint64_t> batchMatrix(package.stats.batchCount, 0);
  std::vector<std::uint64_t> batchZeroFill(package.stats.batchCount, 0);
  std::uint64_t matrixSlots = 0;
  std::uint64_t zeroFillSlots = 0;
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
      const std::size_t begin = package.channelBatchPointers[channel][batch];
      const std::size_t end = package.channelBatchPointers[channel][batch + 1U];
      for (std::size_t beat = begin; beat < end; ++beat) {
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          const bool matrixEntry = (package.matrixEntryMasks[channel][beat] & (1U << lane)) != 0U;
          if (!matrixEntry) {
            ++channelZeroFill[channel];
            ++batchZeroFill[batch];
            ++zeroFillSlots;
          } else {
            ++channelMatrix[channel];
            ++batchMatrix[batch];
            ++matrixSlots;
          }
        }
      }
    }
  }
  if (matrixSlots != package.stats.matrixSlots || zeroFillSlots != package.stats.zeroFillSlots) {
    throw std::invalid_argument("Cuperflow HTML 报告的 slot 统计与 package 不一致");
  }

  output << kHtmlPrefix << "{\"dataset\":";
  writeJsonString(output, datasetName);
  output << ",\"source\":";
  writeJsonString(output, sourcePath);
  output << ",\"vectorReport\":";
  writeJsonString(output, std::string(datasetName) + "-x.html");
  output << ",\"timingReport\":";
  writeJsonString(output, std::string(datasetName) + "-timing.html");
  output << ",\"shape\":{\"rows\":" << package.rows
         << ",\"columns\":" << package.columns
         << ",\"nonzeros\":" << package.nonzeros << "},\"physicalToOriginalRows\":[";
  for (std::size_t physicalRow = 0; physicalRow < package.physicalToOriginalRows.size();
       ++physicalRow) {
    output << (physicalRow == 0 ? "" : ",") << package.physicalToOriginalRows[physicalRow];
  }
  output << "],\"config\":{"
         << "\"hbmChannels\":" << package.config.hbmChannelCount
         << ",\"lanesPerBeat\":" << kLanesPerBeat
         << ",\"totalPes\":" << totalPeCount(package.config)
         << ",\"accumulationContexts\":" << kAccumulationContextCount
         << ",\"sliceSize\":" << package.config.sliceSize
         << ",\"rowBatchSize\":" << package.config.rowBatchSize
         << ",\"xSlicesPerBatch\":" << package.config.xSlicesPerBatch
         << ",\"columnSliceCount\":" << package.columnSliceCount
         << ",\"sliceGroupSize\":" << package.sliceGroupSize
         << ",\"sliceGroupCount\":" << package.sliceGroupCount
         << ",\"xRangeMaxElements\":" << kMaxXRangeElements
         << ",\"columnsPerXBatch\":" << columnsPerBatch(package.config)
         << ",\"reorderWindow\":" << package.config.reorderWindow
         << ",\"rowReorder\":" << (package.config.rowReorder ? "true" : "false")
         << "},\"stats\":{"
         << "\"batchCount\":" << package.stats.batchCount
         << ",\"minBeatsPerChannel\":" << package.stats.minimumMatrixBeatsPerChannel
         << ",\"maxBeatsPerChannel\":" << package.stats.maximumMatrixBeatsPerChannel
         << ",\"totalBeats\":" << package.stats.totalMatrixBeats
         << ",\"matrixSlots\":" << package.stats.matrixSlots
         << ",\"zeroFillSlots\":" << package.stats.zeroFillSlots
         << ",\"packedBytes\":" << package.stats.packedBytes
         << "},\"channelBatchPointers\":[";
  for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
    output << (channel == 0 ? "" : ",") << '[';
    const std::vector<std::uint32_t>& pointers = package.channelBatchPointers[channel];
    for (std::size_t index = 0; index < pointers.size(); ++index) {
      output << (index == 0 ? "" : ",") << pointers[index];
    }
    output << ']';
  }
  output << "],\"channelLaneSliceGroupRanges\":[";
  for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
    output << (channel == 0 ? "" : ",") << '[';
    const auto& ranges = package.channelLaneSliceGroupRanges[channel];
    for (std::size_t index = 0; index < ranges.size(); ++index) {
      output << (index == 0 ? "" : ",") << '[';
      for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
        output << (lane == 0 ? "" : ",") << '[' << ranges[index][lane].first << ','
               << ranges[index][lane].second << ']';
      }
      output << ']';
    }
    output << ']';
  }
  output << "],\"sliceGroupChannels\":[";
  for (std::size_t group = 0; group < package.sliceGroupChannels.size(); ++group) {
    output << (group == 0 ? "" : ",") << package.sliceGroupChannels[group];
  }
  output << "],\"channelSliceGroups\":[";
  for (std::size_t channel = 0; channel < package.channelSliceGroups.size(); ++channel) {
    output << (channel == 0 ? "" : ",") << '[';
    const auto& groups = package.channelSliceGroups[channel];
    for (std::size_t index = 0; index < groups.size(); ++index) {
      output << (index == 0 ? "" : ",") << groups[index];
    }
    output << ']';
  }
  output << "],\"batchStats\":[";
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    output << (batch == 0 ? "" : ",") << '[' << batchMatrix[batch] << ','
           << batchZeroFill[batch] << ']';
  }
  output << "],\"channelStats\":[";
  for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
    output << (channel == 0 ? "" : ",") << '[' << channelMatrix[channel] << ','
           << channelZeroFill[channel] << ']';
  }
  output << "],\"slots\":[";

  std::uint64_t sequence = 0;
  bool firstSlot = true;
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    std::vector<std::unordered_map<std::size_t, std::size_t>> lastRows(
        totalPeCount(package.config));
    std::vector<std::array<ContextHistory, kAccumulationContextCount>> contextHistory(
        totalPeCount(package.config));
    for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
      const std::size_t batchBegin = package.channelBatchPointers[channel][batch];
      for (std::size_t beat = batchBegin;
           beat < package.channelBatchPointers[channel][batch + 1U]; ++beat) {
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          const std::size_t pe = channel * kLanesPerBeat + lane;
          const std::uint64_t word = package.matrixChannels[channel][beat][lane];
          const DecodedCuperflowSlot slot = decodeSlot(word);
          const bool matrixEntry =
              (package.matrixEntryMasks[channel][beat] & (1U << lane)) != 0U;
          const std::size_t firstGroupSegment = batch * package.sliceGroupCount;
          std::size_t group = 0;
          std::size_t groupFirstSlice = 0;
          std::size_t slice = 0;
          if (matrixEntry) {
            group = findLaneGroup(package, channel, firstGroupSegment,
                                  package.sliceGroupCount, lane, beat);
            groupFirstSlice = group * package.sliceGroupSize;
            const std::size_t groupSliceCount = std::min(
                package.sliceGroupSize, package.columnSliceCount - groupFirstSlice);
            if (slot.localColumn >= groupSliceCount * package.config.sliceSize) {
              throw std::invalid_argument("Cuperflow HTML 报告发现越界的 group column");
            }
            slice = groupFirstSlice + slot.localColumn / package.config.sliceSize;
          }
          output << (firstSlot ? "" : ",");
          firstSlot = false;
          output << '[' << sequence++ << ',' << batch << ',' << channel << ',' << beat << ','
                 << beat - batchBegin << ',' << lane << ',' << pe << ',';
          writeJsonString(output, hexadecimal(word, 16));
          output << ',' << (matrixEntry ? "true" : "false") << ',' << slot.localColumn << ',';
          if (!matrixEntry) {
            output << "null," << slot.tag << ',' << slot.localRow << ",null,null,";
            writeJsonString(output, hexadecimal(floatBits(slot.value), 8));
            output << ",null,null";
          } else {
            if (slot.tag >= kAccumulationContextCount) {
              throw std::invalid_argument("Cuperflow HTML 报告收到越界的累加上下文");
            }
            const std::size_t physicalRow = physicalRowForBatchLocal(
                batch, slot.localRow, package.config);
            if (physicalRow >= package.physicalToOriginalRows.size()) {
              throw std::invalid_argument("Cuperflow HTML 报告发现越界的 physical row");
            }
            const std::size_t globalRow = package.physicalToOriginalRows[physicalRow];
            const std::size_t globalColumn = groupFirstSlice * package.config.sliceSize +
                slot.localColumn;
            if (globalColumn >= package.columns) {
              throw std::invalid_argument("Cuperflow HTML 报告发现越界的全局列号");
            }
            output << globalColumn << ',' << slot.tag << ',' << slot.localRow << ','
                   << globalRow << ',';
            writeJsonString(output, formatFloat(slot.value));
            output << ',';
            writeJsonString(output, hexadecimal(floatBits(slot.value), 8));
            output << ',';
            const auto previous = lastRows[pe].find(globalRow);
            if (previous == lastRows[pe].end()) {
              output << "null";
            } else {
              output << beat - previous->second;
            }
            lastRows[pe][globalRow] = beat;
            const ContextHistory& previousContext = contextHistory[pe][slot.tag];
            output << ',';
            if (!previousContext.occupied) {
              writeJsonString(output, "首次装入");
            } else if (previousContext.row == globalRow) {
              writeJsonString(output, "驻留命中");
            } else {
              writeJsonString(output, "换出 R" + std::to_string(previousContext.row) +
                  "，装入 R" + std::to_string(globalRow));
            }
            contextHistory[pe][slot.tag] = ContextHistory{true, globalRow};
          }
          output << ',';
          if (matrixEntry) {
            output << slice;
          } else {
            output << "null";
          }
          output << ',';
          if (matrixEntry) {
            output << physicalRowForBatchLocal(batch, slot.localRow, package.config);
          } else {
            output << "null";
          }
          output << ']';
        }
      }
    }
  }

  output << "]}" << kHtmlSuffix;
}

}  // namespace accelerator_sim::spmv::encoding::cuperflow
