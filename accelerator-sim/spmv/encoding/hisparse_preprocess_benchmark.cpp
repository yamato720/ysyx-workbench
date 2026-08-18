#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <numeric>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace fs = std::filesystem;

namespace {

constexpr std::size_t kPackSize = 8;
constexpr std::size_t kHbmChannels = 16;
constexpr std::size_t kInterleaveFactor = 1;
constexpr std::uint32_t kIndexMarker = 0xffffffffU;
constexpr std::size_t kDefaultVectorBlock = 32768;
constexpr std::size_t kDefaultOutputBlock = 1048576;

struct CsrMatrix {
  std::size_t rows = 0;
  std::size_t columns = 0;
  std::vector<std::uint32_t> rowPointers;
  std::vector<std::uint32_t> columnIndices;
  std::vector<float> values;
};

struct RawPartition {
  std::vector<std::uint32_t> rowPointers;
  std::vector<std::uint32_t> columnIndices;
  std::vector<float> values;
};

struct PackedIndex {
  std::array<std::uint32_t, kPackSize> data{};
};

struct PackedValue {
  std::array<float, kPackSize> data{};
};

struct FormattedPartition {
  std::vector<PackedIndex> rowPointers;
  std::vector<PackedIndex> columnIndices;
  std::vector<PackedValue> values;
};

struct CpsrMatrix {
  std::size_t rowPartitions = 0;
  std::size_t columnPartitions = 0;
  std::vector<FormattedPartition> partitions;
};

struct MatrixPacket {
  PackedIndex indices;
  PackedValue values;
};

struct ChannelPartitionPointer {
  std::uint32_t start = 0;
  PackedIndex nnz;
};

struct FormattedMatrix {
  CpsrMatrix cpsr;
  std::vector<std::vector<MatrixPacket>> channelPackets;
  std::uint64_t checksum = 0;
};

std::size_t roundUp(std::size_t value, std::size_t divisor) {
  return value + (divisor - value % divisor) % divisor;
}

template <typename T>
std::vector<T> readValues(const fs::path& path) {
  std::ifstream input(path);
  if (!input) {
    throw std::runtime_error("无法打开数据文件: " + path.string());
  }
  std::vector<T> values;
  T value{};
  while (input >> value) {
    values.push_back(value);
  }
  if (!input.eof()) {
    throw std::runtime_error("数据文件包含无法解析的内容: " + path.string());
  }
  return values;
}

fs::path findDataset(const std::string& requested) {
  const fs::path direct(requested);
  if (fs::is_directory(direct)) {
    return direct;
  }

#ifdef ACCELERATOR_SIM_DEFAULT_DATA_ROOT
  const fs::path root(ACCELERATOR_SIM_DEFAULT_DATA_ROOT);
#else
  const fs::path root = fs::path("../data");
#endif
  const std::vector<fs::path> candidates = {
      root / "generated" / "cgsolver" / requested,
      root / "suitesparse" / requested,
      root / "suitesparse" / "Schmid" / requested,
      root / "suitesparse" / "Schmid" / "csr" / requested,
  };
  for (const fs::path& candidate : candidates) {
    if (fs::is_directory(candidate)) {
      return candidate;
    }
  }
  throw std::runtime_error("找不到数据集目录: " + requested);
}

CsrMatrix loadMatrix(const fs::path& dataset) {
  CsrMatrix matrix;
  matrix.rowPointers = readValues<std::uint32_t>(dataset / "row_ptr.txt");
  const std::vector<std::uint32_t> columns =
      readValues<std::uint32_t>(dataset / "col_idx.txt");
  matrix.values = readValues<float>(dataset / "values.txt");
  if (matrix.rowPointers.size() < 2 || matrix.rowPointers.front() != 0) {
    throw std::runtime_error("CSR row pointer 格式错误");
  }
  if (matrix.rowPointers.back() != columns.size() ||
      columns.size() != matrix.values.size()) {
    throw std::runtime_error("CSR row pointer、column、value 数量不一致");
  }
  matrix.rows = matrix.rowPointers.size() - 1U;
  matrix.columns = matrix.rows;
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    if (matrix.rowPointers[row] > matrix.rowPointers[row + 1U]) {
      throw std::runtime_error("CSR row pointer 必须单调不减");
    }
  }
  for (const std::uint32_t column : columns) {
    if (column >= matrix.columns) {
      throw std::runtime_error("CSR column index 超出方阵范围");
    }
  }
  matrix.columnIndices = columns;
  return matrix;
}

void roundCsrMatrixDimension(CsrMatrix& matrix) {
  const std::size_t roundedRows = roundUp(matrix.rows, kPackSize * kHbmChannels *
                                                   kInterleaveFactor);
  const std::size_t roundedColumns = roundUp(matrix.columns, kPackSize);
  matrix.rowPointers.resize(roundedRows + 1U, matrix.rowPointers.back());
  matrix.rows = roundedRows;
  matrix.columns = roundedColumns;
}

float markerValue(std::uint32_t value) {
  float result = 0.0F;
  static_assert(sizeof(result) == sizeof(value));
  std::memcpy(&result, &value, sizeof(result));
  return result;
}

void convertCsrToDds(const CsrMatrix& matrix,
                     std::size_t firstRow,
                     std::size_t rowCount,
                     std::size_t vectorBlock,
                     std::vector<RawPartition>& partitions) {
  const std::size_t columnPartitionCount = partitions.size();
  for (RawPartition& partition : partitions) {
    partition.rowPointers.assign(rowCount + 1U, 0);
  }

  std::vector<std::uint32_t> nnzCount(columnPartitionCount, 0);
  for (std::size_t localRow = 0; localRow < rowCount; ++localRow) {
    const std::size_t row = firstRow + localRow;
    for (std::uint32_t offset = matrix.rowPointers[row];
         offset < matrix.rowPointers[row + 1U]; ++offset) {
      const std::size_t partition = matrix.columnIndices[offset] / vectorBlock;
      ++nnzCount[partition];
    }
    for (std::size_t partition = 0; partition < columnPartitionCount; ++partition) {
      partitions[partition].rowPointers[localRow + 1U] = nnzCount[partition];
    }
  }

  for (std::size_t partition = 0; partition < columnPartitionCount; ++partition) {
    partitions[partition].columnIndices.resize(nnzCount[partition]);
    partitions[partition].values.resize(nnzCount[partition]);
  }

  std::vector<std::uint32_t> positions(columnPartitionCount, 0);
  for (std::size_t localRow = 0; localRow < rowCount; ++localRow) {
    const std::size_t row = firstRow + localRow;
    for (std::uint32_t offset = matrix.rowPointers[row];
         offset < matrix.rowPointers[row + 1U]; ++offset) {
      const std::size_t partition = matrix.columnIndices[offset] / vectorBlock;
      const std::uint32_t position = positions[partition]++;
      partitions[partition].columnIndices[position] =
          matrix.columnIndices[offset] - static_cast<std::uint32_t>(partition * vectorBlock);
      partitions[partition].values[position] = matrix.values[offset];
    }
  }
}

void padRowMarkers(RawPartition& partition) {
  const std::size_t rowCount = partition.rowPointers.size() - 1U;
  std::vector<bool> emptyRows(rowCount, false);
  for (std::size_t row = kHbmChannels * kPackSize; row < rowCount; ++row) {
    emptyRows[row] = partition.rowPointers[row] == partition.rowPointers[row + 1U];
  }

  std::vector<std::uint32_t> nonemptyPrefix(rowCount, 0);
  if (rowCount != 0) {
    nonemptyPrefix[0] = !emptyRows[0];
    for (std::size_t row = 1; row < rowCount; ++row) {
      nonemptyPrefix[row] = nonemptyPrefix[row - 1U] + !emptyRows[row];
    }
  }
  const std::size_t nonemptyRows = rowCount == 0 ? 0 : nonemptyPrefix.back();
  const std::vector<std::uint32_t> oldPointers = partition.rowPointers;
  std::vector<std::uint32_t> oldIndices = std::move(partition.columnIndices);
  std::vector<float> oldValues = std::move(partition.values);
  partition.columnIndices.resize(oldIndices.size() + nonemptyRows);
  partition.values.resize(oldValues.size() + nonemptyRows);

  std::vector<std::uint32_t> markerCounts(rowCount, 0);
  for (std::size_t lane = 0; lane < kHbmChannels * kPackSize; ++lane) {
    for (std::size_t row = lane; row < rowCount;) {
      const std::size_t next = row + kHbmChannels * kPackSize;
      if (!emptyRows[row]) {
        markerCounts[row] = 1;
        for (std::size_t following = next;
             following < rowCount && emptyRows[following];
             following += kHbmChannels * kPackSize) {
          ++markerCounts[row];
        }
      }
      row = next;
    }
  }

  std::size_t writePosition = 0;
  for (std::size_t row = 0; row < rowCount; ++row) {
    if (emptyRows[row]) {
      continue;
    }
    for (std::uint32_t offset = oldPointers[row]; offset < oldPointers[row + 1U]; ++offset) {
      partition.columnIndices[writePosition] = oldIndices[offset];
      partition.values[writePosition] = oldValues[offset];
      ++writePosition;
    }
    partition.columnIndices[writePosition] = kIndexMarker;
    partition.values[writePosition] = markerValue(markerCounts[row]);
    ++writePosition;
  }
  if (writePosition != partition.columnIndices.size()) {
    throw std::runtime_error("HiSparse marker padding 结果长度错误");
  }
  partition.rowPointers = oldPointers;
  for (std::size_t row = 0; row < rowCount; ++row) {
    partition.rowPointers[row + 1U] += nonemptyPrefix[row];
  }
}

std::vector<FormattedPartition> packRows(const RawPartition& partition) {
  const std::size_t rowCount = partition.rowPointers.size() - 1U;
  const std::size_t rowsPerPack = kHbmChannels * kPackSize;
  const std::size_t packCount = (rowCount + rowsPerPack - 1U) / rowsPerPack;
  std::vector<FormattedPartition> result(kHbmChannels);
  for (FormattedPartition& channel : result) {
    channel.rowPointers.reserve(packCount + 1U);
  }

  std::array<PackedIndex, kHbmChannels> running{};
  for (std::size_t channel = 0; channel < kHbmChannels; ++channel) {
    result[channel].rowPointers.push_back(running[channel]);
  }
  for (std::size_t pack = 0; pack < packCount; ++pack) {
    for (std::size_t channel = 0; channel < kHbmChannels; ++channel) {
      PackedIndex& pointer = running[channel];
      for (std::size_t lane = 0; lane < kPackSize; ++lane) {
        const std::size_t row = pack * rowsPerPack + channel * kPackSize + lane;
        if (row < rowCount) {
          pointer.data[lane] += partition.rowPointers[row + 1U] -
                                partition.rowPointers[row];
        }
      }
      result[channel].rowPointers.push_back(pointer);
    }
  }

  std::array<std::uint32_t, kHbmChannels> maxNnz{};
  for (std::size_t channel = 0; channel < kHbmChannels; ++channel) {
    maxNnz[channel] = *std::max_element(running[channel].data.begin(),
                                        running[channel].data.end());
  }

  for (std::size_t channel = 0; channel < kHbmChannels; ++channel) {
    result[channel].columnIndices.resize(maxNnz[channel]);
    result[channel].values.resize(maxNnz[channel]);
  }
  for (std::size_t channel = 0; channel < kHbmChannels; ++channel) {
    for (std::size_t lane = 0; lane < kPackSize; ++lane) {
      std::uint32_t writePosition = 0;
      for (std::size_t pack = 0; pack < packCount; ++pack) {
        const std::size_t row = pack * rowsPerPack + channel * kPackSize + lane;
        if (row >= rowCount) {
          continue;
        }
        for (std::uint32_t offset = partition.rowPointers[row];
             offset < partition.rowPointers[row + 1U]; ++offset) {
          result[channel].columnIndices[writePosition].data[lane] =
              partition.columnIndices[offset];
          result[channel].values[writePosition].data[lane] = partition.values[offset];
          ++writePosition;
        }
      }
    }
  }
  return result;
}

FormattedMatrix formatHiSparse(const CsrMatrix& input,
                               std::size_t vectorBlock,
                               std::size_t outputBlock) {
  CsrMatrix matrix = input;
  roundCsrMatrixDimension(matrix);
  const std::size_t rowPartitions = (matrix.rows + outputBlock - 1U) / outputBlock;
  const std::size_t columnPartitions =
      (matrix.columns + vectorBlock - 1U) / vectorBlock;
  const std::size_t partitionCount = rowPartitions * columnPartitions;

  FormattedMatrix result;
  result.cpsr.rowPartitions = rowPartitions;
  result.cpsr.columnPartitions = columnPartitions;
  result.cpsr.partitions.resize(partitionCount * kHbmChannels * kInterleaveFactor);

  for (std::size_t rowPartition = 0; rowPartition < rowPartitions; ++rowPartition) {
    const std::size_t firstRow = rowPartition * outputBlock;
    const std::size_t rowCount = std::min(outputBlock, matrix.rows - firstRow);
    std::vector<RawPartition> dds(columnPartitions);
    convertCsrToDds(matrix, firstRow, rowCount, vectorBlock, dds);
    for (std::size_t columnPartition = 0; columnPartition < columnPartitions;
         ++columnPartition) {
      padRowMarkers(dds[columnPartition]);
      std::vector<FormattedPartition> formatted = packRows(dds[columnPartition]);
      for (std::size_t channel = 0; channel < kHbmChannels; ++channel) {
        result.cpsr.partitions[(rowPartition * columnPartitions + columnPartition) *
                                  kHbmChannels + channel] = std::move(formatted[channel]);
      }
    }
  }

  using PointerTable = std::vector<std::vector<ChannelPartitionPointer>>;
  PointerTable pointers(kHbmChannels * kInterleaveFactor,
                        std::vector<ChannelPartitionPointer>(partitionCount));
  std::vector<std::vector<PackedIndex>> channelIndices(kHbmChannels * kInterleaveFactor);
  std::vector<std::vector<PackedValue>> channelValues(kHbmChannels * kInterleaveFactor);
  result.channelPackets.resize(kHbmChannels);

  for (std::size_t physicalChannel = 0; physicalChannel < kHbmChannels;
       ++physicalChannel) {
    for (std::size_t rowPartition = 0; rowPartition < rowPartitions; ++rowPartition) {
      for (std::size_t columnPartition = 0; columnPartition < columnPartitions;
           ++columnPartition) {
        const std::size_t partition = rowPartition * columnPartitions + columnPartition;
        std::array<std::uint32_t, kInterleaveFactor> packets{};
        for (std::size_t factor = 0; factor < kInterleaveFactor; ++factor) {
          const std::size_t virtualChannel = physicalChannel + factor * kHbmChannels;
          const FormattedPartition& formatted =
              result.cpsr.partitions[partition * kHbmChannels + virtualChannel];
          packets[factor] = formatted.rowPointers.back().data[0];
          for (std::size_t lane = 1; lane < kPackSize; ++lane) {
            packets[factor] = std::max(packets[factor],
                                       formatted.rowPointers.back().data[lane]);
          }
        }
        const std::uint32_t maxPackets =
            *std::max_element(packets.begin(), packets.end());
        for (std::size_t factor = 0; factor < kInterleaveFactor; ++factor) {
          const std::size_t virtualChannel = physicalChannel + factor * kHbmChannels;
          const FormattedPartition& formatted =
              result.cpsr.partitions[partition * kHbmChannels + virtualChannel];
          auto& indices = channelIndices[virtualChannel];
          auto& values = channelValues[virtualChannel];
          const std::size_t start = pointers[virtualChannel][partition].start;
          indices.insert(indices.end(), formatted.columnIndices.begin(),
                         formatted.columnIndices.end());
          values.insert(values.end(), formatted.values.begin(), formatted.values.end());
          indices.resize(start + maxPackets);
          values.resize(start + maxPackets);
          pointers[virtualChannel][partition].nnz = formatted.rowPointers.back();
          if (!(rowPartition + 1U == rowPartitions &&
                columnPartition + 1U == columnPartitions)) {
            pointers[virtualChannel][partition + 1U].start =
                static_cast<std::uint32_t>(start + maxPackets);
          }
        }
      }
    }

    const std::size_t virtualPartitionHeader =
        partitionCount * (1U + kInterleaveFactor);
    const std::size_t packetCount =
        virtualPartitionHeader + channelIndices[physicalChannel].size() * kInterleaveFactor;
    result.channelPackets[physicalChannel].resize(packetCount);
    for (std::size_t partition = 0; partition < partitionCount; ++partition) {
      MatrixPacket& header = result.channelPackets[physicalChannel]
          [partition * (1U + kInterleaveFactor)];
      header.indices.data[0] = pointers[physicalChannel][partition].start *
                               kInterleaveFactor;
      for (std::size_t factor = 0; factor < kInterleaveFactor; ++factor) {
        const std::size_t virtualChannel = physicalChannel + factor * kHbmChannels;
        result.channelPackets[physicalChannel]
            [partition * (1U + kInterleaveFactor) + 1U + factor]
            .indices = pointers[virtualChannel][partition].nnz;
      }
    }
    const std::size_t offset = virtualPartitionHeader;
    for (std::size_t index = 0; index < channelIndices[physicalChannel].size(); ++index) {
      for (std::size_t factor = 0; factor < kInterleaveFactor; ++factor) {
        const std::size_t virtualChannel = physicalChannel + factor * kHbmChannels;
        const std::size_t packet = offset + index * kInterleaveFactor + factor;
        result.channelPackets[physicalChannel][packet].indices =
            channelIndices[virtualChannel][index];
        result.channelPackets[physicalChannel][packet].values =
            channelValues[virtualChannel][index];
      }
    }
  }
  return result;
}

std::uint64_t checksum(const FormattedMatrix& matrix) {
  std::uint64_t result = 1469598103934665603ULL;
  for (const auto& channel : matrix.channelPackets) {
    for (const MatrixPacket& packet : channel) {
      result ^= packet.indices.data[0];
      result *= 1099511628211ULL;
      result ^= packet.values.data[0] == 0.0F ? 0U : 1U;
      result *= 1099511628211ULL;
    }
  }
  return result;
}

std::uint64_t countPackedSlots(const CpsrMatrix& matrix) {
  std::uint64_t result = 0;
  for (const FormattedPartition& partition : matrix.partitions) {
    result += partition.values.size();
  }
  return result;
}

std::uint64_t countPacketBytes(const FormattedMatrix& matrix) {
  std::uint64_t packets = 0;
  for (const auto& channel : matrix.channelPackets) {
    packets += channel.size();
  }
  return packets * sizeof(MatrixPacket);
}

double milliseconds(std::chrono::steady_clock::time_point begin,
                    std::chrono::steady_clock::time_point end) {
  return std::chrono::duration<double, std::milli>(end - begin).count();
}

}  // namespace

int main(int argc, char** argv) {
  try {
    const std::string requested = argc > 1 ? argv[1] : "thermal2";
    const std::size_t vectorBlock = argc > 2 ? std::stoull(argv[2]) : kDefaultVectorBlock;
    const std::size_t outputBlock = argc > 3 ? std::stoull(argv[3]) : kDefaultOutputBlock;
    if (vectorBlock == 0 || outputBlock == 0 || vectorBlock % kPackSize != 0 ||
        outputBlock % (kPackSize * kHbmChannels) != 0) {
      throw std::invalid_argument(
          "vectorBlock 必须是 8 的倍数，outputBlock 必须是 128 的倍数");
    }

    const auto loadBegin = std::chrono::steady_clock::now();
    const fs::path dataset = findDataset(requested);
    const CsrMatrix matrix = loadMatrix(dataset);
    const auto loadEnd = std::chrono::steady_clock::now();

    const auto formatBegin = std::chrono::steady_clock::now();
    const FormattedMatrix formatted = formatHiSparse(matrix, vectorBlock, outputBlock);
    const auto formatEnd = std::chrono::steady_clock::now();

    const auto totalEnd = formatEnd;
    const std::uint64_t packedSlots = countPackedSlots(formatted.cpsr);
    const std::uint64_t packetBytes = countPacketBytes(formatted);
    const std::uint64_t outputChecksum = checksum(formatted);
    std::cout << std::fixed << std::setprecision(3)
              << "[hisparse-preprocess] dataset=" << dataset.string() << '\n'
              << "[hisparse-preprocess] rows=" << matrix.rows
              << " cols=" << matrix.columns
              << " nnz=" << matrix.values.size() << '\n'
              << "[hisparse-preprocess] hbm_channels=" << kHbmChannels
              << " pack_size=" << kPackSize
              << " vector_block=" << vectorBlock
              << " output_block=" << outputBlock << '\n'
              << "[hisparse-preprocess] row_partitions=" << formatted.cpsr.rowPartitions
              << " column_partitions=" << formatted.cpsr.columnPartitions << '\n'
              << "[hisparse-preprocess] packed_slots=" << packedSlots
              << " packet_bytes=" << packetBytes << '\n'
              << "[hisparse-preprocess] timing_load_ms=" << milliseconds(loadBegin, loadEnd)
              << " timing_format_ms=" << milliseconds(formatBegin, formatEnd)
              << " timing_total_ms=" << milliseconds(loadBegin, totalEnd) << '\n'
              << "[hisparse-preprocess] checksum=" << outputChecksum << '\n';
  } catch (const std::exception& error) {
    std::cerr << "[hisparse-preprocess] error: " << error.what() << '\n';
    return EXIT_FAILURE;
  }
  return EXIT_SUCCESS;
}
