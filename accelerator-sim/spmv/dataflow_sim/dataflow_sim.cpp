#include "../encoding/cuperflow/cuperflow.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include <immintrin.h>

#ifndef ACCELERATOR_SIM_DEFAULT_DATA_ROOT
#define ACCELERATOR_SIM_DEFAULT_DATA_ROOT "../data"
#endif

namespace fs = std::filesystem;

namespace accelerator_sim::spmv::dataflow_sim {
namespace {

using encoding::cuperflow::CuperflowConfig;
using encoding::cuperflow::CuperflowPackage;
using encoding::cuperflow::CuperflowVectorPackage;
using encoding::cuperflow::CuperflowXRange;

template <typename T>
std::vector<T> readArray(const fs::path& path) {
  std::ifstream stream(path);
  if (!stream) {
    throw std::runtime_error("无法打开数据文件: " + path.string());
  }
  std::vector<T> values;
  T value{};
  while (stream >> value) {
    values.push_back(value);
  }
  if (!stream.eof()) {
    throw std::runtime_error("无法解析数据文件: " + path.string());
  }
  if (values.empty()) {
    throw std::runtime_error("数据文件为空: " + path.string());
  }
  return values;
}

std::vector<std::uint64_t> readNonnegativeIntegers(const fs::path& path) {
  const std::vector<std::int64_t> signedValues = readArray<std::int64_t>(path);
  std::vector<std::uint64_t> values;
  values.reserve(signedValues.size());
  for (const std::int64_t value : signedValues) {
    if (value < 0) {
      throw std::runtime_error("数据文件包含负整数: " + path.string());
    }
    values.push_back(static_cast<std::uint64_t>(value));
  }
  return values;
}

bool isDatasetDirectory(const fs::path& path) {
  return fs::is_regular_file(path / "row_ptr.txt") &&
      fs::is_regular_file(path / "col_idx.txt") &&
      fs::is_regular_file(path / "values.txt") &&
      fs::is_regular_file(path / "b.txt");
}

fs::path resolveDataRoot() {
  if (const char* configured = std::getenv("ACCELERATOR_DATA_ROOT")) {
    if (*configured != '\0') {
      return fs::path(configured);
    }
  }
  return fs::path(ACCELERATOR_SIM_DEFAULT_DATA_ROOT);
}

fs::path findDataset(const std::string& requested) {
  const fs::path requestedPath(requested);
  if (isDatasetDirectory(requestedPath)) {
    return requestedPath;
  }

  const fs::path dataRoot = resolveDataRoot();
  const std::vector<fs::path> searchRoots = {
      dataRoot / "generated" / "cgsolver", dataRoot / "suitesparse"};
  for (const fs::path& searchRoot : searchRoots) {
    if (!fs::is_directory(searchRoot)) {
      continue;
    }
    for (const fs::directory_entry& entry : fs::recursive_directory_iterator(
             searchRoot, fs::directory_options::skip_permission_denied)) {
      if (entry.is_directory() && entry.path().filename() == requested &&
          isDatasetDirectory(entry.path())) {
        return entry.path();
      }
    }
  }
  throw std::runtime_error("找不到数据集: " + requested +
      "，请传入数据集名或包含 row_ptr.txt 的目录");
}

CsrMatrix loadMatrix(const fs::path& dataset) {
  CsrMatrix matrix;
  matrix.rowPointers = readNonnegativeIntegers(dataset / "row_ptr.txt");
  const std::vector<std::uint64_t> columns =
      readNonnegativeIntegers(dataset / "col_idx.txt");
  matrix.values = readArray<double>(dataset / "values.txt");
  if (matrix.rowPointers.size() < 2 || matrix.rowPointers.front() != 0) {
    throw std::runtime_error("CSR row_ptr.txt 格式错误: " + dataset.string());
  }
  matrix.rows = matrix.rowPointers.size() - 1U;
  matrix.columns = matrix.rows;
  if (matrix.rowPointers.back() != columns.size() ||
      columns.size() != matrix.values.size()) {
    throw std::runtime_error("CSR row pointer、column 和 value 数量不一致");
  }
  matrix.columnIndices.reserve(columns.size());
  for (const std::uint64_t column : columns) {
    if (column >= matrix.columns || column > std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error("CSR column index 超出方阵范围");
    }
    matrix.columnIndices.push_back(static_cast<std::uint32_t>(column));
  }
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    if (matrix.rowPointers[row] > matrix.rowPointers[row + 1U]) {
      throw std::runtime_error("CSR row pointer 必须单调不减");
    }
  }
  return matrix;
}

struct SimulationData {
  fs::path datasetPath;
  CsrMatrix matrix;
  CuperflowPackage package;
  CuperflowVectorPackage vectorPackage;
  double matrixLoadMilliseconds = 0.0;
  double aEncodingMilliseconds = 0.0;
  double xEncodingMilliseconds = 0.0;
};

SimulationData prepareSimulation(const std::string& dataset) {
  SimulationData data;
  data.datasetPath = findDataset(dataset);
  const auto matrixLoadStart = std::chrono::steady_clock::now();
  data.matrix = loadMatrix(data.datasetPath);
  const auto matrixLoadEnd = std::chrono::steady_clock::now();
  data.matrixLoadMilliseconds =
      std::chrono::duration<double, std::milli>(matrixLoadEnd - matrixLoadStart).count();
  const std::vector<double> input = readArray<double>(data.datasetPath / "b.txt");
  if (input.size() != data.matrix.columns) {
    throw std::runtime_error("b.txt 长度与矩阵列数不一致");
  }

  const CuperflowConfig config;
  const auto aEncodingStart = std::chrono::steady_clock::now();
  data.package = encoding::cuperflow::encode(data.matrix, config);
  const auto aEncodingEnd = std::chrono::steady_clock::now();
  data.aEncodingMilliseconds =
      std::chrono::duration<double, std::milli>(aEncodingEnd - aEncodingStart).count();
  const auto xEncodingStart = std::chrono::steady_clock::now();
  data.vectorPackage = encoding::cuperflow::encodeVector(input, config);
  const auto xEncodingEnd = std::chrono::steady_clock::now();
  data.xEncodingMilliseconds =
      std::chrono::duration<double, std::milli>(xEncodingEnd - xEncodingStart).count();
  if (data.package.sliceGroupCount != data.vectorPackage.stats.rangeCount ||
      data.package.sliceGroupChannels.size() != data.package.sliceGroupCount ||
      data.vectorPackage.channelXRanges.size() != data.package.config.hbmChannelCount) {
    throw std::runtime_error("A/X package 的 slice group 几何不一致");
  }
  return data;
}

struct Avx512BatchStats {
  std::uint64_t aSlots = 0;
  std::size_t activeChannels = 0;
};

bool hasAvx512F() {
#if defined(__GNUC__) || defined(__clang__)
  return __builtin_cpu_supports("avx512f");
#else
  return false;
#endif
}

float decodeFloat(std::uint32_t bits) {
  float value = 0.0F;
  static_assert(sizeof(value) == sizeof(bits));
  std::memcpy(&value, &bits, sizeof(value));
  return value;
}

std::vector<float> loadXRange(const CuperflowVectorPackage& package,
                              std::size_t ownerChannel,
                              const CuperflowXRange& xRange) {
  const auto& channelBeats = package.channelHbmBeats[ownerChannel];
  if (xRange.beatEnd > channelBeats.size()) {
    throw std::runtime_error("X range 超出 owner HBM 的 payload");
  }

  std::vector<float> values(xRange.elementCount);
  for (std::size_t offset = 0; offset < xRange.elementCount; ++offset) {
    values[offset] = decodeFloat(
        channelBeats[xRange.beatBegin + offset / encoding::cuperflow::kVectorLanesPerBeat]
            [offset % encoding::cuperflow::kVectorLanesPerBeat]);
  }
  return values;
}

Avx512BatchStats accumulateGroupBatchAvx512(
    const CuperflowPackage& package, const CuperflowXRange& xRange,
    const std::vector<float>& xRangeValues, std::size_t group, std::size_t batch,
    std::vector<float>* physicalOutput) {
  Avx512BatchStats stats;
  const std::size_t firstSlice = group * package.sliceGroupSize;
  const std::size_t groupSegment = batch * package.sliceGroupCount + group;
  const std::size_t groupFirstColumn = firstSlice * package.config.sliceSize;

  for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
    bool channelActive = false;
    const auto& channelBeats = package.matrixChannels[channel];
    for (std::size_t lane = 0; lane < encoding::cuperflow::kLanesPerBeat; ++lane) {
      const auto range = package.channelLaneSliceGroupRanges[channel][groupSegment][lane];
      for (std::size_t beat = range.first; beat < range.second; beat += 2U) {
          std::array<std::int32_t, encoding::cuperflow::kVectorLanesPerBeat> xIndices{};
          std::array<std::uint32_t, encoding::cuperflow::kVectorLanesPerBeat> physicalRows{};
          std::array<float, encoding::cuperflow::kVectorLanesPerBeat> aValues{};
          __mmask16 activeMask = 0;

          // A beat 只有 8 个 slot，两个 beat 组成一组 AVX512 FP32 输入。
          for (std::size_t beatOffset = 0; beatOffset < 2U; ++beatOffset) {
            const std::size_t currentBeat = beat + beatOffset;
            if (currentBeat >= range.second) {
              break;
            }
            const std::size_t vectorLane = beatOffset * encoding::cuperflow::kLanesPerBeat;
            const std::uint8_t entryMask = package.matrixEntryMasks[channel][currentBeat];
            if ((entryMask & (std::uint8_t{1} << lane)) == 0U) {
              continue;
            }

            const auto decoded = encoding::cuperflow::decodeSlot(
                channelBeats[currentBeat][lane]);
            const std::size_t globalColumn = groupFirstColumn + decoded.localColumn;
            if (globalColumn < xRange.firstColumn ||
                globalColumn >= xRange.firstColumn + xRange.elementCount) {
              throw std::runtime_error("A slot 的列号超出当前 X range");
            }
            const std::size_t xIndex = globalColumn - xRange.firstColumn;
            const std::size_t physicalPe = channel * encoding::cuperflow::kLanesPerBeat + lane;
            const std::size_t physicalRow = encoding::cuperflow::rowForPeLocal(
                physicalPe, decoded.localRow, package.config);
            if (physicalRow >= physicalOutput->size()) {
              throw std::runtime_error("A slot 的 physical row 超出输出范围");
            }

            xIndices[vectorLane] = static_cast<std::int32_t>(xIndex);
            physicalRows[vectorLane] = static_cast<std::uint32_t>(physicalRow);
            aValues[vectorLane] = decoded.value;
            activeMask |= static_cast<__mmask16>(std::uint16_t{1} << vectorLane);
            ++stats.aSlots;
            channelActive = true;
          }

          if (activeMask == 0) {
            continue;
          }
          const __m512i indices = _mm512_loadu_si512(xIndices.data());
          const __m512 x = _mm512_mask_i32gather_ps(
              _mm512_setzero_ps(), activeMask, indices, xRangeValues.data(), 4);
          const __m512 a = _mm512_maskz_loadu_ps(activeMask, aValues.data());
          const __m512 products = _mm512_mul_ps(a, x);
          std::array<float, encoding::cuperflow::kVectorLanesPerBeat> productValues{};
          _mm512_mask_storeu_ps(productValues.data(), activeMask, products);
          for (std::size_t vectorLane = 0;
               vectorLane < encoding::cuperflow::kVectorLanesPerBeat; ++vectorLane) {
            if ((activeMask & (std::uint16_t{1} << vectorLane)) != 0U) {
              (*physicalOutput)[physicalRows[vectorLane]] += productValues[vectorLane];
            }
          }
      }
    }
    stats.activeChannels += static_cast<std::size_t>(channelActive);
  }
  return stats;
}

std::vector<float> computeFp32Reference(const CsrMatrix& matrix,
                                        const std::vector<double>& input) {
  std::vector<float> reference(matrix.rows, 0.0F);
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    float accumulator = 0.0F;
    for (std::uint64_t offset = matrix.rowPointers[row];
         offset < matrix.rowPointers[row + 1U]; ++offset) {
      const std::size_t index = static_cast<std::size_t>(offset);
      const float value = static_cast<float>(matrix.values[index]);
      const float x = static_cast<float>(input[matrix.columnIndices[index]]);
      accumulator += value * x;
    }
    reference[row] = accumulator;
  }
  return reference;
}

void printSummary(const SimulationData& data) {
  const CuperflowPackage& package = data.package;
  std::cout << "[dataflow-sim] dataset=" << data.datasetPath
            << " rows=" << data.matrix.rows
            << " columns=" << data.matrix.columns
            << " row_batches=" << package.stats.batchCount
            << " slice_groups=" << package.sliceGroupCount
            << " hbm_channels=" << package.config.hbmChannelCount << '\n';
  std::cout << "[dataflow-sim] order=group-major, group0/batch0 -> group0/batch1 -> ...\n";
}

void printStep(std::size_t step, std::size_t group, std::size_t batch,
               std::size_t ownerChannel, const CuperflowXRange& xRange,
               std::uint64_t aSlots, std::size_t activeChannels) {
  std::cout << "step=" << step
            << " group=" << group
            << " batch=" << batch
            << " owner_hbm=" << ownerChannel
            << " x=" << (batch == 0 ? "load" : "reuse")
            << " x_range=[" << xRange.firstColumn << ','
            << xRange.firstColumn + xRange.elementCount << ')'
            << " x_elements=" << xRange.elementCount
            << " x_beats=" << xRange.beatEnd - xRange.beatBegin
            << " a_slots=" << aSlots
            << " active_a_hbm=" << activeChannels << '\n';
}

void printTotals(std::size_t totalSteps, const CuperflowPackage& package) {
  const std::size_t xLoads =
      package.stats.batchCount == 0 ? 0 : package.sliceGroupCount;
  const std::size_t xReuses =
      package.sliceGroupCount *
      (package.stats.batchCount == 0 ? 0 : package.stats.batchCount - 1U);
  std::cout << "[dataflow-sim] total_steps=" << totalSteps
            << " x_loads=" << xLoads
            << " x_reuses=" << xReuses << '\n';
}

void printVerification(const std::vector<float>& actual,
                       const std::vector<float>& expected) {
  constexpr double kAbsoluteTolerance = 1.0e-4;
  constexpr double kRelativeTolerance = 1.0e-5;
  std::size_t mismatches = 0;
  double maxAbsoluteError = 0.0;
  for (std::size_t index = 0; index < actual.size(); ++index) {
    const double error = std::fabs(static_cast<double>(actual[index]) - expected[index]);
    const double tolerance = kAbsoluteTolerance +
        kRelativeTolerance * std::fabs(static_cast<double>(expected[index]));
    maxAbsoluteError = std::max(maxAbsoluteError, error);
    if (!std::isfinite(actual[index]) || error > tolerance) {
      ++mismatches;
    }
  }
  std::cout << "[dataflow-sim] avx512_fp32="
            << (mismatches == 0 ? "PASS" : "FAIL")
            << " mismatches=" << mismatches
            << " max_abs_error=" << std::setprecision(8) << maxAbsoluteError
            << std::setprecision(6) << '\n';
}

void printUsage() {
  std::cout << "用法: dataflow-sim [dataset] [--max-steps=N]\n"
            << "默认 dataset 为 n512；dataset 可以是数据集名或目录。\n";
}

}  // namespace
}  // namespace accelerator_sim::spmv::dataflow_sim

int main(int argc, char** argv) {
  using namespace accelerator_sim::spmv::dataflow_sim;
  try {
    // 默认模拟 n512；命令行可替换数据集，并限制输出的步骤数。
    std::string dataset = "n512";
    std::size_t maxSteps = std::numeric_limits<std::size_t>::max();
    for (int index = 1; index < argc; ++index) {
      const std::string argument(argv[index]);
      if (argument == "--help" || argument == "-h") {
        printUsage();
        return 0;
      }
      constexpr std::string_view maxStepsPrefix = "--max-steps=";
      if (argument.rfind(maxStepsPrefix, 0) == 0) {
        const std::string value = argument.substr(maxStepsPrefix.size());
        std::size_t consumed = 0;
        maxSteps = std::stoull(value, &consumed, 10);
        if (consumed != value.size()) {
          throw std::invalid_argument("--max-steps 必须是非负整数");
        }
      } else if (index == 1) {
        dataset = argument;
      } else {
        throw std::invalid_argument("未知参数: " + argument);
      }
    }

    // 数据加载、CSR 校验和 A/X 编码都收拢在一个短函数中。
    const auto prepareStart = std::chrono::steady_clock::now();
    const SimulationData data = prepareSimulation(dataset);
    const auto prepareEnd = std::chrono::steady_clock::now();
    const CuperflowPackage& package = data.package;
    const CuperflowVectorPackage& vectorPackage = data.vectorPackage;
    if (!hasAvx512F()) {
      throw std::runtime_error("当前 CPU 不支持 AVX512F，无法运行 AVX512 数据流内核");
    }
    printSummary(data);

    const std::size_t totalSteps = package.sliceGroupCount * package.stats.batchCount;
    const std::size_t stepsToPrint = std::min(maxSteps, totalSteps);
    std::size_t step = 0;
    std::vector<float> physicalOutput(data.matrix.rows, 0.0F);

    // ==================== 正片：数据流推进 ====================
    // group 是 X 的驻留单位：先固定一个 group，再依次计算它的所有 row batch。
    const auto dataflowStart = std::chrono::steady_clock::now();
    for (std::size_t group = 0; group < package.sliceGroupCount; ++group) {
      const std::size_t ownerChannel = package.sliceGroupChannels[group];
      if (ownerChannel >= vectorPackage.channelXRanges.size()) {
        throw std::runtime_error("slice group 的 HBM owner 超出 X package 范围");
      }

      // 找到当前 group 在 owner HBM 上对应的 X 连续区间。
      const auto& ownerRanges = vectorPackage.channelXRanges[ownerChannel];
      const auto rangeIt = std::find_if(ownerRanges.begin(), ownerRanges.end(),
          [group](const CuperflowXRange& range) {
            return range.sliceGroup == group;
          });
      if (rangeIt == ownerRanges.end()) {
        throw std::runtime_error("X package 缺少 slice group 的 HBM range");
      }
      const CuperflowXRange& xRange = *rangeIt;
      const std::vector<float> xRangeValues =
          loadXRange(vectorPackage, ownerChannel, xRange);

      // batch0 装载 X；同一 group 的后续 batch 直接复用这段 X。
      for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
        const Avx512BatchStats batchStats = accumulateGroupBatchAvx512(
            package, xRange, xRangeValues, group, batch, &physicalOutput);
        if (step < stepsToPrint) {
          printStep(step, group, batch, ownerChannel, xRange, batchStats.aSlots,
                    batchStats.activeChannels);
        }
        ++step;
      }
    }
    const auto dataflowEnd = std::chrono::steady_clock::now();

    if (stepsToPrint < totalSteps) {
      std::cout << "[dataflow-sim] omitted_steps=" << totalSteps - stepsToPrint << '\n';
    }
    printTotals(totalSteps, package);
    const double prepareMilliseconds =
        std::chrono::duration<double, std::milli>(prepareEnd - prepareStart).count();
    const double dataflowMilliseconds =
        std::chrono::duration<double, std::milli>(dataflowEnd - dataflowStart).count();
    std::cout << "[dataflow-sim] timing_prepare_ms=" << std::fixed
              << std::setprecision(3) << prepareMilliseconds
              << " timing_dataflow_ms=" << dataflowMilliseconds << '\n';
    std::cout << "[dataflow-sim] timing_matrix_load_ms=" << data.matrixLoadMilliseconds
              << " timing_a_encode_ms=" << data.aEncodingMilliseconds
              << " timing_x_encode_ms=" << data.xEncodingMilliseconds << '\n';
    if (stepsToPrint == totalSteps) {
      std::vector<float> actualOutput(data.matrix.rows, 0.0F);
      for (std::size_t physicalRow = 0; physicalRow < physicalOutput.size(); ++physicalRow) {
        const std::size_t originalRow = package.physicalToOriginalRows[physicalRow];
        actualOutput[originalRow] = physicalOutput[physicalRow];
      }
      const std::vector<float> expectedOutput = computeFp32Reference(
          data.matrix, vectorPackage.sourceValues);
      printVerification(actualOutput, expectedOutput);
    } else {
      std::cout << "[dataflow-sim] avx512_fp32=SKIPPED (max-steps 未覆盖完整数据流)\n";
    }
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "[dataflow-sim] FAIL: " << error.what() << '\n';
    return 1;
  }
}
