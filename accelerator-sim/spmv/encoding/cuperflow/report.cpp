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

std::string_view tailPackingName(CuperflowTailPacking packing) {
  switch (packing) {
    case CuperflowTailPacking::Compact421: return "4/2/1 tails";
    case CuperflowTailPacking::Pad3To4And1To2: return "3->4, 1->2 tails";
    case CuperflowTailPacking::PadAllTo4: return "all tails ->4";
  }
  throw std::invalid_argument("Cuperflow HTML 报告收到未知 row tail 策略");
}

std::string aPackingName(const CuperflowConfig& config) {
  switch (config.aPacking) {
    case CuperflowAPacking::RowRoundRobin:
      return "row-round-robin + " + std::string(tailPackingName(config.tailPacking));
    case CuperflowAPacking::LaneStriped: return "lane-striped";
  }
  throw std::invalid_argument("Cuperflow HTML 报告收到未知 A 打包模式");
}

std::size_t findLaneGroup(const CuperflowPackage& package, std::size_t channel,
                          std::size_t firstSegment, std::size_t groupCount,
                          std::size_t lane, std::size_t beat) {
  for (std::size_t group = 0; group < groupCount; ++group) {
    const auto range = package.channelLaneSliceGroupRanges[channel][firstSegment + group][lane];
    if (range.first != range.second && range.first <= beat && beat < range.second) {
      return group;
    }
  }
  throw std::invalid_argument("Cuperflow HTML 报告无法为矩阵 slot 定位 slice group");
}

struct BatchChannelStats {
  std::uint64_t beats = 0;
  std::uint64_t matrixSlots = 0;
  std::uint64_t zeroFillSlots = 0;
  std::array<std::uint64_t, kLanesPerBeat> laneMatrix{};
  std::array<std::uint64_t, kLanesPerBeat> laneZeroFill{};
};

void writeDetailSampleSlot(std::ostream& output, const CuperflowPackage& package,
                           std::size_t batch, std::size_t channel, std::size_t beat,
                           std::size_t batchBegin, std::size_t lane) {
  const std::size_t pe = channel * kLanesPerBeat + lane;
  const std::uint64_t word = package.matrixChannels[channel][beat][lane];
  const DecodedCuperflowSlot slot = decodeSlot(word);
  const bool matrixEntry =
      (package.matrixEntryMasks[channel][beat] & (std::uint8_t{1} << lane)) != 0U;

  output << '[' << 0 << ',' << batch << ',' << channel << ',' << beat << ','
         << beat - batchBegin << ',' << lane << ',' << pe << ',';
  writeJsonString(output, hexadecimal(word, 16));
  output << ',' << (matrixEntry ? "true" : "false") << ',' << slot.localColumn << ',';
  if (!matrixEntry) {
    output << "null," << slot.segmentId << ',' << (slot.rowLast ? "true" : "false") << ','
           << static_cast<unsigned>(slot.chunkMode) << ',' << slot.localRow << ",null,null,";
    writeJsonString(output, hexadecimal(floatBits(slot.value), 8));
    output << ",null,null,null,null]";
    return;
  }

  const std::size_t group = findLaneGroup(package, channel,
      batch * package.sliceGroupCount, package.sliceGroupCount, lane, beat);
  const std::size_t groupFirstSlice = group * package.sliceGroupSize;
  const std::size_t groupSliceCount = std::min(
      package.sliceGroupSize, package.columnSliceCount - groupFirstSlice);
  if (slot.localColumn >= groupSliceCount * package.config.sliceSize) {
    throw std::invalid_argument("Cuperflow HTML 示例 slot 的 group column 越界");
  }
  const auto& segments = package.xSegmentsByGroup[group];
  if (slot.segmentId >= segments.size()) {
    throw std::invalid_argument("Cuperflow HTML 示例 slot 的 X 段号越界");
  }
  const CuperflowXSegment& segment = segments[slot.segmentId];
  if (slot.localColumn < segment.start ||
      slot.localColumn - segment.start >= segment.count) {
    throw std::invalid_argument("Cuperflow HTML 示例 slot 不属于其 X 段");
  }
  const std::size_t physicalRow = physicalRowForBatchLocal(
      batch, slot.localRow, package.config);
  if (physicalRow >= package.physicalToOriginalRows.size()) {
    throw std::invalid_argument("Cuperflow HTML 示例 slot 的 physical row 越界");
  }
  const std::size_t globalColumn = groupFirstSlice * package.config.sliceSize +
      slot.localColumn;
  if (globalColumn >= package.columns) {
    throw std::invalid_argument("Cuperflow HTML 示例 slot 的 global column 越界");
  }

  output << globalColumn << ',' << slot.segmentId << ',' << (slot.rowLast ? "true" : "false")
         << ',' << static_cast<unsigned>(slot.chunkMode) << ',' << slot.localRow << ','
         << package.physicalToOriginalRows[physicalRow] << ',';
  writeJsonString(output, formatFloat(slot.value));
  output << ',';
  writeJsonString(output, hexadecimal(floatBits(slot.value), 8));
  output << ",null,";
  writeJsonString(output, "segment " + std::to_string(slot.segmentId) + ": local [" +
      std::to_string(segment.start) + ", " +
      std::to_string(static_cast<std::size_t>(segment.start) + segment.count) + ")");
  output << ',' << groupFirstSlice + slot.localColumn / package.config.sliceSize << ','
         << physicalRow << ']';
}

void validateReportPackage(const CuperflowPackage& package) {
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
      package.channelSliceGroups.size() != package.config.hbmChannelCount ||
      package.xSegmentsByGroup.size() != package.sliceGroupCount) {
    throw std::invalid_argument("Cuperflow HTML 报告的 HBM X range ownership 不一致");
  }
  std::vector<bool> seenGroups(package.sliceGroupCount, false);
  for (std::size_t group = 0; group < package.sliceGroupCount; ++group) {
    if (package.sliceGroupChannels[group] >= package.config.hbmChannelCount) {
      throw std::invalid_argument("Cuperflow HTML 报告收到越界的 slice group HBM 映射");
    }
    const std::size_t groupFirstColumn = group * package.sliceGroupSize * package.config.sliceSize;
    const std::size_t groupElements = std::min(
        package.sliceGroupSize * package.config.sliceSize, package.columns - groupFirstColumn);
    const auto& segments = package.xSegmentsByGroup[group];
    if (segments.size() > kMaxXSegments) {
      throw std::invalid_argument("Cuperflow HTML 报告的 X 段数超过 slot segmentId 范围");
    }
    for (const CuperflowXSegment& segment : segments) {
      if (segment.count == 0 || segment.start + segment.count > groupElements) {
        throw std::invalid_argument("Cuperflow HTML 报告收到越界的 X 段 descriptor");
      }
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
    if (package.channelGroupARanges.size() != package.config.hbmChannelCount) {
      throw std::invalid_argument("Cuperflow HTML 报告缺少 group-major A 区间");
    }
    std::uint32_t covered = 0;
    for (const CuperflowGroupARange& range : package.channelGroupARanges[channel]) {
      if (range.aOffsetBeats != covered ||
          range.aOffsetBeats + range.aBeats < range.aOffsetBeats ||
          range.aOffsetBeats + range.aBeats > package.matrixChannels[channel].size()) {
        throw std::invalid_argument("Cuperflow HTML 报告的 group A 区间必须按装载顺序紧密排列");
      }
      covered = range.aOffsetBeats + range.aBeats;
    }
    if (covered != package.matrixChannels[channel].size()) {
      throw std::invalid_argument("Cuperflow HTML 报告的 group A 区间未覆盖整个 channel");
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
  validateReportPackage(package);

  std::vector<std::vector<BatchChannelStats>> batchChannelStats(
      package.stats.batchCount,
      std::vector<BatchChannelStats>(package.config.hbmChannelCount));
  std::vector<std::uint64_t> channelMatrix(package.config.hbmChannelCount, 0);
  std::vector<std::uint64_t> channelZeroFill(package.config.hbmChannelCount, 0);
  std::vector<std::uint64_t> batchMatrix(package.stats.batchCount, 0);
  std::vector<std::uint64_t> batchZeroFill(package.stats.batchCount, 0);
  std::uint64_t matrixSlots = 0;
  std::uint64_t zeroFillSlots = 0;
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
      BatchChannelStats& stats = batchChannelStats[batch][channel];
      for (const std::size_t group : package.channelSliceGroups[channel]) {
        const auto& ranges = package.channelLaneSliceGroupRanges[channel][
            batch * package.sliceGroupCount + group];
        std::size_t begin = package.matrixChannels[channel].size();
        std::size_t end = 0;
        for (const auto& range : ranges) {
          if (range.first != range.second) {
            begin = std::min(begin, static_cast<std::size_t>(range.first));
            end = std::max(end, static_cast<std::size_t>(range.second));
          }
        }
        if (begin == package.matrixChannels[channel].size()) {
          continue;
        }
        stats.beats += end - begin;
        for (std::size_t beat = begin; beat < end; ++beat) {
          for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
            const bool matrixEntry =
                (package.matrixEntryMasks[channel][beat] & (std::uint8_t{1} << lane)) != 0U;
            if (!matrixEntry) {
              ++stats.zeroFillSlots;
              ++stats.laneZeroFill[lane];
              ++channelZeroFill[channel];
              ++batchZeroFill[batch];
              ++zeroFillSlots;
            } else {
              ++stats.matrixSlots;
              ++stats.laneMatrix[lane];
              ++channelMatrix[channel];
              ++batchMatrix[batch];
              ++matrixSlots;
            }
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
         << ",\"nonzeros\":" << package.nonzeros << "},\"config\":{"
         << "\"hbmChannels\":" << package.config.hbmChannelCount
         << ",\"lanesPerBeat\":" << kLanesPerBeat
         << ",\"totalPes\":" << totalPeCount(package.config)
         << ",\"xSegmentLimit\":" << kMaxXSegments
         << ",\"sliceSize\":" << package.config.sliceSize
         << ",\"rowBatchSize\":" << package.config.rowBatchSize
         << ",\"xSlicesPerBatch\":" << package.config.xSlicesPerBatch
         << ",\"columnSliceCount\":" << package.columnSliceCount
         << ",\"sliceGroupSize\":" << package.sliceGroupSize
         << ",\"sliceGroupCount\":" << package.sliceGroupCount
         << ",\"activeXPayloadGroupCount\":" << package.stats.expectedXPayloadLoadCount
         << ",\"contributorWaveCount\":" << package.contributorWaveCount
         << ",\"xRangeMaxElements\":" << kMaxXRangeElements
         << ",\"columnsPerXBatch\":" << columnsPerBatch(package.config)
         << ",\"reorderWindow\":" << package.config.reorderWindow
         << ",\"rowReorder\":" << (package.config.rowReorder ? "true" : "false")
         << ",\"aPacking\":";
  writeJsonString(output, aPackingName(package.config));
  output << "},\"stats\":{"
         << "\"batchCount\":" << package.stats.batchCount
         << ",\"minBeatsPerChannel\":" << package.stats.minimumMatrixBeatsPerChannel
         << ",\"maxBeatsPerChannel\":" << package.stats.maximumMatrixBeatsPerChannel
         << ",\"totalBeats\":" << package.stats.totalMatrixBeats
         << ",\"matrixSlots\":" << package.stats.matrixSlots
         << ",\"zeroFillSlots\":" << package.stats.zeroFillSlots
         << ",\"droppedExplicitZeros\":" << package.stats.droppedExplicitZeros
         << ",\"full8ChunkCount\":" << package.stats.full8ChunkCount
         << ",\"two4ChunkCount\":" << package.stats.two4ChunkCount
         << ",\"four2ChunkCount\":" << package.stats.four2ChunkCount
         << ",\"rowPartialBeatCounts\":[" << package.stats.rowPartial1BeatCount << ','
         << package.stats.rowPartial2BeatCount << ',' << package.stats.rowPartial4BeatCount << ']'
         << ",\"batchDescriptorCount\":" << package.stats.batchDescriptorCount
         << ",\"emptyBatchCount\":" << package.stats.emptyBatchCount
         << ",\"chunkInterBeatDistance\":{\"count\":"
         << package.stats.chunkInterBeatDistanceCount << ",\"total\":"
         << package.stats.chunkInterBeatDistanceTotal << ",\"minimum\":"
         << package.stats.chunkInterBeatDistanceMinimum << ",\"maximum\":"
         << package.stats.chunkInterBeatDistanceMaximum << ",\"belowCandidateFaddLatency\":"
         << package.stats.chunkInterBeatDistanceBelowFaddLatency << ",\"candidateFaddLatency\":"
         << package.stats.candidateFaddLatency << '}'
         << ",\"contributorPopcountHistogram\":[";
  for (std::size_t popcount = 0;
       popcount < package.stats.contributorPopcountHistogram.size(); ++popcount) {
    output << (popcount == 0 ? "" : ",")
           << package.stats.contributorPopcountHistogram[popcount];
  }
  output << "]"
         << ",\"completionRobPeak\":" << package.stats.completionRobPeak
         << ",\"xPayloadLoadCount\":" << package.stats.xPayloadLoadCount
         << ",\"expectedXPayloadLoadCount\":" << package.stats.expectedXPayloadLoadCount
         << ",\"packedBytes\":" << package.stats.packedBytes
         << "},\"pcL1Stats\":[";
  for (std::size_t channel = 0; channel < package.pcL1Stats.size(); ++channel) {
    const CuperflowPcL1Stats& stats = package.pcL1Stats[channel];
    output << (channel == 0 ? "" : ",") << '[' << stats.aBeats << ','
           << stats.effectiveSlots << ',' << stats.activeRows << ',' << stats.emptyBatches << ']';
  }
  output << "],\"waveBatchL1Stats\":[";
  for (std::size_t index = 0; index < package.waveBatchL1Stats.size(); ++index) {
    const CuperflowWaveBatchL1Stats& stats = package.waveBatchL1Stats[index];
    output << (index == 0 ? "" : ",") << '[' << stats.activeRows << ','
           << stats.maxPcProgressGap << ']';
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
  output << "],\"batchChannelStats\":[";
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    output << (batch == 0 ? "" : ",") << '[';
    for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
      const BatchChannelStats& stats = batchChannelStats[batch][channel];
      output << (channel == 0 ? "" : ",") << '[' << stats.beats << ','
             << stats.matrixSlots << ',' << stats.zeroFillSlots << ",[";
      for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
        output << (lane == 0 ? "" : ",") << '[' << stats.laneMatrix[lane] << ','
               << stats.laneZeroFill[lane] << ']';
      }
      output << "]]";
    }
    output << ']';
  }
  output << "],\"detailSample\":{\"batch\":0,\"channel\":0,\"slot\":0,\"data\":";
  bool detailWritten = false;
  if (package.stats.batchCount != 0 && package.config.hbmChannelCount != 0) {
    for (const std::size_t group : package.channelSliceGroups[0]) {
      const auto& ranges = package.channelLaneSliceGroupRanges[0][group];
      const auto& laneZeroRange = ranges[0];
      for (std::size_t beat = laneZeroRange.first; beat < laneZeroRange.second; ++beat) {
        if ((package.matrixEntryMasks[0][beat] & std::uint8_t{1}) == 0U) {
          continue;
        }
        writeDetailSampleSlot(output, package, 0, 0, beat, laneZeroRange.first, 0);
        detailWritten = true;
        break;
      }
      if (detailWritten) {
        break;
      }
    }
  }
  if (!detailWritten) {
    output << "null";
  }
  output << "}}" << kHtmlSuffix;
}

}  // namespace accelerator_sim::spmv::encoding::cuperflow
