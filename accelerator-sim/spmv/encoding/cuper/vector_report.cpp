#include "vector_report.hpp"
#include "vector_report_ui.hpp"

#include <cmath>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>

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

std::string hexadecimal(std::uint32_t value) {
  std::ostringstream output;
  output << "0x" << std::hex << std::setw(8) << std::setfill('0') << value;
  return output.str();
}

void validatePackage(const CuperVectorPackage& package) {
  if (package.columns == 0 || package.sourceValues.size() != package.columns) {
    throw std::invalid_argument("Cuper X HTML 报告收到非法源向量长度");
  }
  const std::size_t expectedBatches =
      (package.columns + columnsPerBatch(package.config) - 1U) /
      columnsPerBatch(package.config);
  if (package.batchPointers.size() != expectedBatches + 1U ||
      package.batchPointers.empty() || package.batchPointers.front() != 0) {
    throw std::invalid_argument("Cuper X HTML 报告收到非法 batch pointers");
  }
  for (std::size_t index = 1; index < package.batchPointers.size(); ++index) {
    if (package.batchPointers[index] < package.batchPointers[index - 1U]) {
      throw std::invalid_argument("Cuper X HTML 报告要求 batch pointers 单调不减");
    }
  }
  if (package.batchPointers.back() != package.stats.payloadBeats ||
      package.hbmBeats.size() != package.stats.allocatedBeats ||
      package.stats.batchCount != expectedBatches ||
      package.stats.validElements != package.columns) {
    throw std::invalid_argument("Cuper X HTML 报告的 package 统计不一致");
  }
  if (package.stats.payloadBeats * kVectorLanesPerBeat !=
          package.stats.validElements + package.stats.lanePaddingElements ||
      package.stats.allocatedBeats * kVectorLanesPerBeat !=
          package.stats.validElements + package.stats.lanePaddingElements +
          package.stats.allocationPaddingElements ||
      package.stats.packedBytes != package.stats.payloadBeats * 64U ||
      package.stats.allocatedBytes != package.stats.allocatedBeats * 64U) {
    throw std::invalid_argument("Cuper X HTML 报告的 padding 或字节统计不一致");
  }
}

}  // namespace

void writeVectorHtmlReport(std::ostream& output, const CuperVectorPackage& package,
                           std::string_view datasetName, std::string_view sourcePath) {
  validatePackage(package);

  output << kVectorHtmlPrefix << "{\"dataset\":";
  writeJsonString(output, datasetName);
  output << ",\"source\":";
  writeJsonString(output, sourcePath);
  output << ",\"matrixReport\":";
  writeJsonString(output, std::string(datasetName) + ".html");
  output << ",\"config\":{"
         << "\"columnsPerBatch\":" << columnsPerBatch(package.config)
         << ",\"lanesPerBeat\":" << kVectorLanesPerBeat
         << ",\"sourceBits\":64,\"encodedBits\":32"
         << ",\"storageAlignmentElements\":" << kVectorStorageAlignmentElements
         << ",\"coreCount\":" << package.config.hbmChannelCount
         << ",\"replicas\":" << kVectorReplicaCount
         << ",\"partitionFactor\":" << kVectorPartitionFactor << "},\"stats\":{"
         << "\"batchCount\":" << package.stats.batchCount
         << ",\"payloadBeats\":" << package.stats.payloadBeats
         << ",\"allocatedBeats\":" << package.stats.allocatedBeats
         << ",\"validElements\":" << package.stats.validElements
         << ",\"lanePaddingElements\":" << package.stats.lanePaddingElements
         << ",\"allocationPaddingElements\":" << package.stats.allocationPaddingElements
         << ",\"packedBytes\":" << package.stats.packedBytes
         << ",\"allocatedBytes\":" << package.stats.allocatedBytes
         << "},\"batchPointers\":[";
  for (std::size_t index = 0; index < package.batchPointers.size(); ++index) {
    output << (index == 0 ? "" : ",") << package.batchPointers[index];
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
      const std::uint32_t bits = package.hbmBeats[beat][lane];
      float encoded = 0.0F;
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

}  // namespace accelerator_sim::spmv::encoding::cuper
