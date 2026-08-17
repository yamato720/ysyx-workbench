#include "report.hpp"
#include "report_ui.hpp"

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

namespace accelerator_sim::spmv::encoding::cuper {

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

struct ContextHistory {
  bool occupied = false;
  std::size_t row = 0;
};

void validatePackage(const CuperPackage& package) {
  if (package.matrixChannels.size() != package.config.hbmChannelCount) {
    throw std::invalid_argument("Cuper HTML 报告的 HBM channel 数量与 config 不一致");
  }
  if (package.channelBatchPointers.size() != package.config.hbmChannelCount) {
    throw std::invalid_argument("Cuper HTML 报告的 per-HBM batch pointer 数量不一致");
  }
  if (package.matrixEntryMasks.size() != package.config.hbmChannelCount) {
    throw std::invalid_argument("Cuper HTML 报告的矩阵 slot 带外掩码数量不一致");
  }

  std::uint64_t totalBeats = 0;
  std::size_t minimumBeats = package.matrixChannels.empty() ?
      0 : package.matrixChannels.front().size();
  std::size_t maximumBeats = 0;
  for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
    const std::vector<std::uint32_t>& pointers = package.channelBatchPointers[channel];
    if (pointers.size() != package.stats.batchCount + 1U || pointers.empty() ||
        pointers.front() != 0) {
      throw std::invalid_argument("Cuper HTML 报告收到非法 per-HBM batch pointers");
    }
    for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
      if (pointers[batch] > pointers[batch + 1U]) {
        throw std::invalid_argument("Cuper HTML 报告要求 per-HBM batch pointers 单调不减");
      }
    }
    if (pointers.back() != package.matrixChannels[channel].size()) {
      throw std::invalid_argument("Cuper HTML 报告的 channel 长度与 batch pointer 不一致");
    }
    if (package.matrixEntryMasks[channel].size() != package.matrixChannels[channel].size()) {
      throw std::invalid_argument("Cuper HTML 报告的矩阵 slot 带外掩码长度不一致");
    }
    minimumBeats = std::min(minimumBeats, package.matrixChannels[channel].size());
    maximumBeats = std::max(maximumBeats, package.matrixChannels[channel].size());
    totalBeats += package.matrixChannels[channel].size();
  }
  if (minimumBeats != package.stats.minimumMatrixBeatsPerChannel ||
      maximumBeats != package.stats.maximumMatrixBeatsPerChannel ||
      totalBeats != package.stats.totalMatrixBeats) {
    throw std::invalid_argument("Cuper HTML 报告的动态 channel beat 统计不一致");
  }
  if (totalBeats > std::numeric_limits<std::uint64_t>::max() / kLanesPerBeat ||
      totalBeats * kLanesPerBeat != package.stats.matrixSlots + package.stats.zeroFillSlots) {
    throw std::invalid_argument("Cuper HTML 报告的动态 channel slot 统计不一致");
  }
  if (totalBeats > std::numeric_limits<std::uint64_t>::max() / 64U ||
      totalBeats * 64U != package.stats.packedBytes) {
    throw std::invalid_argument("Cuper HTML 报告的动态 package 字节统计不一致");
  }
}

}  // namespace

void writeHtmlReport(std::ostream& output, const CuperPackage& package,
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
    throw std::invalid_argument("Cuper HTML 报告的 slot 统计与 package 不一致");
  }

  output << kHtmlPrefix << "{\"dataset\":";
  writeJsonString(output, datasetName);
  output << ",\"source\":";
  writeJsonString(output, sourcePath);
  output << ",\"vectorReport\":";
  writeJsonString(output, std::string(datasetName) + "-x.html");
  output << ",\"shape\":{\"rows\":" << package.rows
         << ",\"columns\":" << package.columns
         << ",\"nonzeros\":" << package.nonzeros << "},\"config\":{"
         << "\"hbmChannels\":" << package.config.hbmChannelCount
         << ",\"lanesPerBeat\":" << kLanesPerBeat
         << ",\"totalPes\":" << totalPeCount(package.config)
         << ",\"accumulationContexts\":" << kAccumulationContextCount
         << ",\"sliceSize\":" << package.config.sliceSize
         << ",\"slicesPerBatch\":" << package.config.columnSlicesPerBatch
         << ",\"columnsPerBatch\":" << columnsPerBatch(package.config)
         << ",\"reorderWindow\":" << package.config.reorderWindow << "},\"stats\":{"
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
      const std::size_t begin = package.channelBatchPointers[channel][batch];
      const std::size_t end = package.channelBatchPointers[channel][batch + 1U];
      for (std::size_t beat = begin; beat < end; ++beat) {
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          const std::size_t pe = channel * kLanesPerBeat + lane;
          const std::uint64_t word = package.matrixChannels[channel][beat][lane];
          const DecodedCuperSlot slot = decodeSlot(word);
          const bool matrixEntry =
              (package.matrixEntryMasks[channel][beat] & (1U << lane)) != 0U;
          output << (firstSlot ? "" : ",");
          firstSlot = false;
          output << '[' << sequence++ << ',' << batch << ',' << channel << ',' << beat << ','
                 << beat - begin << ',' << lane << ',' << pe << ',';
          writeJsonString(output, hexadecimal(word, 16));
          output << ',' << (matrixEntry ? "true" : "false") << ',' << slot.localColumn << ',';
          if (!matrixEntry) {
            output << "null," << slot.tag << ',' << slot.localRow << ",null,null,";
            writeJsonString(output, hexadecimal(floatBits(slot.value), 8));
            output << ",null,null";
          } else {
            if (slot.tag >= kAccumulationContextCount) {
              throw std::invalid_argument("Cuper HTML 报告收到越界的累加上下文");
            }
            const std::size_t globalRow = rowForPeLocal(pe, slot.localRow, package.config);
            output << batch * columnsPerBatch(package.config) + slot.localColumn << ','
                   << slot.tag << ',' << slot.localRow << ',' << globalRow << ',';
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
          output << ']';
        }
      }
    }
  }

  output << "]}" << kHtmlSuffix;
}

}  // namespace accelerator_sim::spmv::encoding::cuper
