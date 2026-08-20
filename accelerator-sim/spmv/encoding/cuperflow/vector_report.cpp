#include "vector_report.hpp"
#include "vector_report_ui.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>

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

template <typename Float>
std::string formatFloat(Float value) {
  if (std::isnan(value)) {
    return "nan";
  }
  if (std::isinf(value)) {
    return std::signbit(value) ? "-inf" : "inf";
  }
  std::ostringstream output;
  output << std::setprecision(std::numeric_limits<Float>::max_digits10) << value;
  return output.str();
}

std::string hexadecimal(std::uint64_t value) {
  std::ostringstream output;
  output << "0x" << std::hex << std::setw(16) << std::setfill('0') << value;
  return output.str();
}

void validatePackage(const CuperflowVectorPackage& package) {
  if (package.columns == 0 || package.sourceValues.size() != package.columns) {
    throw std::invalid_argument("Cuperflow X HTML 报告收到非法源向量长度");
  }
  const std::size_t expectedBatches =
      (package.columns + columnsPerBatch(package.config) - 1U) /
      columnsPerBatch(package.config);
  if (package.batchPointers.size() != expectedBatches + 1U ||
      package.batchPointers.empty() || package.batchPointers.front() != 0) {
    throw std::invalid_argument("Cuperflow X HTML 报告收到非法 batch pointers");
  }
  for (std::size_t index = 1; index < package.batchPointers.size(); ++index) {
    if (package.batchPointers[index] < package.batchPointers[index - 1U]) {
      throw std::invalid_argument("Cuperflow X HTML 报告要求 batch pointers 单调不减");
    }
  }
  if (package.batchPointers.back() != package.stats.payloadBeats ||
      package.hbmBeats.size() != package.stats.allocatedBeats ||
      package.stats.batchCount != expectedBatches ||
      package.stats.validElements != package.columns) {
    throw std::invalid_argument("Cuperflow X HTML 报告的 package 统计不一致");
  }
  if (package.stats.payloadBeats * kVectorLanesPerBeat !=
          package.stats.validElements + package.stats.lanePaddingElements ||
      package.stats.allocatedBeats * kVectorLanesPerBeat !=
          package.stats.validElements + package.stats.lanePaddingElements +
          package.stats.allocationPaddingElements ||
      package.stats.packedBytes != package.stats.payloadBeats * 64U ||
      package.stats.allocatedBytes != package.stats.allocatedBeats * 64U) {
    throw std::invalid_argument("Cuperflow X HTML 报告的 padding 或字节统计不一致");
  }
  const std::size_t sliceCount = columnSliceCount(package.columns, package.config);
  const std::size_t groupSize = effectiveSliceGroupSize(sliceCount, package.config);
  const std::size_t groupCount = (sliceCount + groupSize - 1U) / groupSize;
  if (package.channelHbmBeats.size() != package.config.hbmChannelCount ||
      package.channelXRanges.size() != package.config.hbmChannelCount ||
      package.stats.rangeCount != groupCount ||
      package.stats.maximumRangeElements > kMaxXRangeElements) {
    throw std::invalid_argument("Cuperflow X HTML 报告的 per-HBM range 数量不一致");
  }
  std::vector<bool> seenGroups(groupCount, false);
  std::uint64_t encodedWordCount = 0;
  std::uint64_t encodedValueCount = 0;
  std::uint64_t demandedElements = 0;
  std::uint64_t segmentCount = 0;
  std::uint64_t markerCount = 0;
  std::uint64_t encodedPayloadBeats = 0;
  bool hasMaps = false;
  for (std::size_t channel = 0; channel < package.channelXRanges.size(); ++channel) {
    const auto& ranges = package.channelXRanges[channel];
    const auto& beats = package.channelHbmBeats[channel];
    for (const CuperflowXRange& range : ranges) {
      if (range.sliceGroup >= groupCount || seenGroups[range.sliceGroup] ||
          range.sliceGroup % package.config.hbmChannelCount != channel) {
        throw std::invalid_argument("Cuperflow X HTML 报告收到非法 per-HBM X range ownership");
      }
      const std::size_t groupFirstColumn =
          range.sliceGroup * groupSize * package.config.sliceSize;
      const std::size_t groupElements = std::min(
          groupSize * package.config.sliceSize, package.columns - groupFirstColumn);
      std::size_t segmentElements = 0;
      if (range.segments.empty() || range.segments.size() > kMaxXSegments ||
          range.elementCount > kMaxXRangeElements ||
          range.usedElementCount == 0 || range.usedElementCount > range.elementCount ||
          range.beatBegin > range.beatEnd || range.beatEnd > beats.size() ||
          range.encodedWordCount != range.elementCount ||
          range.valueCount != range.elementCount || range.markerCount != 0 ||
          range.beatEnd - range.beatBegin !=
              (range.encodedWordCount + kVectorLanesPerBeat - 1U) / kVectorLanesPerBeat) {
        throw std::invalid_argument("Cuperflow X HTML 报告收到非法 per-HBM X range");
      }
      for (const CuperflowXSegment& segment : range.segments) {
        if (segment.count == 0 || segment.start + segment.count > groupElements) {
          throw std::invalid_argument("Cuperflow X HTML 报告收到越界的 X 段 descriptor");
        }
        segmentElements += segment.count;
      }
      if (segmentElements != range.elementCount ||
          (!package.flexibleXEncoding &&
           (range.segments.size() != 1 || range.segments[0].start != 0 ||
            range.segments[0].count != groupElements))) {
        throw std::invalid_argument("Cuperflow X HTML 报告收到非法连续 X 段计划");
      }
      if (range.mapBeat != std::numeric_limits<std::uint32_t>::max()) {
        hasMaps = true;
        if (range.mapBeat >= beats.size() || range.mapBeat + 1U != range.beatBegin ||
            !isXMapMarker(beats[range.mapBeat][0])) {
          throw std::invalid_argument("Cuperflow X HTML 报告的 map beat 必须紧挨 token 区间");
        }
      }
      encodedPayloadBeats += range.beatEnd - range.beatBegin;

      for (std::size_t token = 0; token < range.encodedWordCount; ++token) {
        const std::uint64_t word = beats[range.beatBegin + token / kVectorLanesPerBeat]
            [token % kVectorLanesPerBeat];
        if (isXAddressMarker(word)) {
          throw std::invalid_argument("Cuperflow 连续 X span 不能含地址 marker");
        }
      }
      if (range.mapBeat != std::numeric_limits<std::uint32_t>::max()) {
        const CuperflowMapBeat map = unpackMapBeat(beats[range.mapBeat]);
        if (map.xElements != range.elementCount ||
            map.xWords != range.encodedWordCount ||
            map.xBeats != range.beatEnd - range.beatBegin) {
          throw std::invalid_argument("Cuperflow X map 与紧凑 payload 不一致");
        }
        for (std::size_t index = 0; index < kMaxXSegments; ++index) {
          const CuperflowXSegment expected = index < range.segments.size() ?
              range.segments[index] : CuperflowXSegment{};
          if (map.xSegments[index] != expected) {
            throw std::invalid_argument("Cuperflow X map 的段 descriptor 不一致");
          }
        }
      }
      encodedWordCount += range.encodedWordCount;
      encodedValueCount += range.valueCount;
      demandedElements += range.usedElementCount;
      segmentCount += range.segments.size();
      markerCount += range.markerCount;
      seenGroups[range.sliceGroup] = true;
    }
  }
  if (!hasMaps &&
      !std::all_of(seenGroups.begin(), seenGroups.end(), [](bool seen) { return seen; })) {
    throw std::invalid_argument("Cuperflow X HTML 报告的 per-HBM X range 不完整");
  }
  if (encodedWordCount != package.stats.encodedWordCount ||
      encodedValueCount != package.stats.encodedValueCount ||
      demandedElements != package.stats.demandedElements ||
      segmentCount != package.stats.segmentCount ||
      markerCount != package.stats.markerCount ||
      encodedPayloadBeats != package.stats.encodedPayloadBeats ||
      encodedPayloadBeats * kVectorLanesPerBeat < encodedWordCount ||
      encodedPayloadBeats * kVectorLanesPerBeat - encodedWordCount !=
          package.stats.encodedLanePaddingWords) {
    throw std::invalid_argument("Cuperflow X HTML 报告的 encoded token 统计不一致");
  }
}

}  // namespace

void writeVectorHtmlReport(std::ostream& output, const CuperflowVectorPackage& package,
                           std::string_view datasetName, std::string_view sourcePath) {
  validatePackage(package);
  const std::size_t sliceCount = columnSliceCount(package.columns, package.config);
  const std::size_t groupSize = effectiveSliceGroupSize(sliceCount, package.config);
  const std::size_t groupCount = (sliceCount + groupSize - 1U) / groupSize;

  output << kVectorHtmlPrefix << "{\"dataset\":";
  writeJsonString(output, datasetName);
  output << ",\"source\":";
  writeJsonString(output, sourcePath);
  output << ",\"matrixReport\":";
  writeJsonString(output, std::string(datasetName) + ".html");
  output << ",\"timingReport\":";
  writeJsonString(output, std::string(datasetName) + "-timing.html");
  output << ",\"config\":{"
         << "\"columnsPerBatch\":" << columnsPerBatch(package.config)
         << ",\"lanesPerBeat\":" << kVectorLanesPerBeat
         << ",\"sourceBits\":64,\"encodedBits\":64"
         << ",\"flexibleX\":" << (package.flexibleXEncoding ? "true" : "false")
         << ",\"addressMarkerOpcode\":" << kXAddressMarkerOpcode
         << ",\"addressMarkerMagic\":" << kXAddressMarkerMagic
         << ",\"addressBits\":" << kColumnBits
         << ",\"storageAlignmentElements\":" << kVectorStorageAlignmentElements
         << ",\"coreCount\":" << package.config.hbmChannelCount
         << ",\"sliceGroupSize\":" << groupSize
         << ",\"sliceGroupCount\":" << groupCount
         << ",\"xRangeMaxElements\":" << kMaxXRangeElements
         << ",\"replicas\":" << kVectorReplicaCount
         << ",\"partitionFactor\":" << kVectorPartitionFactor << "},\"stats\":{"
         << "\"batchCount\":" << package.stats.batchCount
         << ",\"payloadBeats\":" << package.stats.payloadBeats
         << ",\"allocatedBeats\":" << package.stats.allocatedBeats
         << ",\"validElements\":" << package.stats.validElements
         << ",\"lanePaddingElements\":" << package.stats.lanePaddingElements
         << ",\"allocationPaddingElements\":" << package.stats.allocationPaddingElements
         << ",\"rangeCount\":" << package.stats.rangeCount
         << ",\"segmentCount\":" << package.stats.segmentCount
         << ",\"maximumRangeElements\":" << package.stats.maximumRangeElements
         << ",\"encodedWordCount\":" << package.stats.encodedWordCount
         << ",\"encodedValueCount\":" << package.stats.encodedValueCount
         << ",\"demandedElements\":" << package.stats.demandedElements
         << ",\"markerCount\":" << package.stats.markerCount
         << ",\"encodedPayloadBeats\":" << package.stats.encodedPayloadBeats
         << ",\"encodedLanePaddingWords\":" << package.stats.encodedLanePaddingWords
         << ",\"packedBytes\":" << package.stats.packedBytes
         << ",\"allocatedBytes\":" << package.stats.allocatedBytes
         << "},\"batchPointers\":[";
  for (std::size_t index = 0; index < package.batchPointers.size(); ++index) {
    output << (index == 0 ? "" : ",") << package.batchPointers[index];
  }
  output << "],\"channelXRanges\":[";
  for (std::size_t channel = 0; channel < package.channelXRanges.size(); ++channel) {
    output << (channel == 0 ? "" : ",") << '[';
    const auto& ranges = package.channelXRanges[channel];
    for (std::size_t index = 0; index < ranges.size(); ++index) {
      const CuperflowXRange& range = ranges[index];
      const std::size_t groupFirstColumn = range.sliceGroup * groupSize * package.config.sliceSize;
      output << (index == 0 ? "" : ",") << '[' << range.sliceGroup << ','
             << groupFirstColumn << ',' << range.elementCount << ',' << range.beatBegin << ','
             << range.beatEnd << ",[";
      for (std::size_t segment = 0; segment < range.segments.size(); ++segment) {
        output << (segment == 0 ? "" : ",") << '[' << range.segments[segment].start << ','
               << range.segments[segment].count << ']';
      }
      output << "]]";
    }
    output << ']';
  }
  output << "],\"encodedXRanges\":[";
  for (std::size_t channel = 0; channel < package.channelXRanges.size(); ++channel) {
    output << (channel == 0 ? "" : ",") << '[';
    const auto& ranges = package.channelXRanges[channel];
    for (std::size_t index = 0; index < ranges.size(); ++index) {
      const CuperflowXRange& range = ranges[index];
      const std::size_t groupFirstColumn = range.sliceGroup * groupSize * package.config.sliceSize;
      output << (index == 0 ? "" : ",") << '[' << range.sliceGroup << ','
             << groupFirstColumn << ',' << range.elementCount << ',' << range.usedElementCount << ','
             << range.encodedWordCount << ',' << range.valueCount << ',' << range.markerCount << ','
             << range.beatBegin << ',' << range.beatEnd << ",[";
      for (std::size_t segment = 0; segment < range.segments.size(); ++segment) {
        output << (segment == 0 ? "" : ",") << '[' << range.segments[segment].start << ','
               << range.segments[segment].count << ']';
      }
      output << "]]";
    }
    output << ']';
  }
  output << "],\"elements\":[";

  bool first = true;
  for (std::size_t beat = 0; beat < package.stats.payloadBeats; ++beat) {
    const std::size_t batch = beat * kVectorLanesPerBeat /
        columnsPerBatch(package.config);
    const std::size_t batchBeat = beat - package.batchPointers[batch];
    for (std::size_t lane = 0; lane < kVectorLanesPerBeat; ++lane) {
      const std::size_t column = beat * kVectorLanesPerBeat + lane;
      const bool padding = column >= package.columns;
      const std::uint64_t bits = package.hbmBeats[beat][lane];
      double encoded = 0.0;
      static_assert(sizeof(bits) == sizeof(encoded));
      std::memcpy(&encoded, &bits, sizeof(encoded));
      output << (first ? "" : ",") << '[' << column << ',' << batch << ',' << beat
             << ',' << batchBeat << ',' << lane << ',';
      if (padding) {
        output << "null,null,true,null,";
      } else {
        const std::size_t localColumn = column - batch * columnsPerBatch(package.config);
        output << column << ',' << localColumn << ",false,";
        writeJsonString(output, formatFloat(package.sourceValues[column]));
        output << ',';
      }
      writeJsonString(output, formatFloat(encoded));
      output << ',';
      writeJsonString(output, hexadecimal(bits));
      output << ',' << (padding ? 0 : (column % columnsPerBatch(package.config)) %
          kVectorPartitionFactor) << ']';
      first = false;
    }
  }
  output << "]}" << kVectorHtmlSuffix;
}

}  // namespace accelerator_sim::spmv::encoding::cuperflow
