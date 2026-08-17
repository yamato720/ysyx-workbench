#include "golden.hpp"
#include "encoding/encoder.hpp"
#include "encoding/cuper/demand_schedule.hpp"
#include "input/input_simulation.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <numeric>
#include <stdexcept>
#include <string>
#include <system_error>
#include <vector>

namespace fs = std::filesystem;

namespace accelerator_sim::spmv {
namespace {

struct DatasetChoice {
  std::string name;
  fs::path path;
  std::size_t rows = 0;
  std::uint64_t nonzeros = 0;
};

template <typename T>
std::vector<T> readArray(const fs::path& path) {
  std::ifstream stream(path);
  if (!stream) {
    throw std::runtime_error("failed to open " + path.string());
  }
  std::vector<T> values;
  T value{};
  while (stream >> value) {
    values.push_back(value);
  }
  if (!stream.eof()) {
    throw std::runtime_error("failed to parse " + path.string());
  }
  if (values.empty()) {
    throw std::runtime_error("empty data file: " + path.string());
  }
  return values;
}

std::vector<std::uint64_t> readNonnegativeIntegers(const fs::path& path) {
  const std::vector<std::int64_t> signedValues = readArray<std::int64_t>(path);
  std::vector<std::uint64_t> values;
  values.reserve(signedValues.size());
  for (std::int64_t value : signedValues) {
    if (value < 0) {
      throw std::runtime_error("negative integer in " + path.string());
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

DatasetChoice inspectDataset(const fs::path& path) {
  const std::vector<std::uint64_t> rowPointers = readNonnegativeIntegers(path / "row_ptr.txt");
  if (rowPointers.size() < 2) {
    throw std::runtime_error("row_ptr.txt must contain at least two entries: " + path.string());
  }
  return DatasetChoice{path.filename().string(), path, rowPointers.size() - 1, rowPointers.back()};
}

std::vector<DatasetChoice> discoverDatasets(const fs::path& dataRoot) {
  const std::vector<fs::path> searchRoots = {
      dataRoot / "generated" / "cgsolver", dataRoot / "suitesparse"};
  std::vector<DatasetChoice> choices;
  for (const fs::path& searchRoot : searchRoots) {
    if (!fs::is_directory(searchRoot)) {
      continue;
    }
    for (const fs::directory_entry& entry :
         fs::recursive_directory_iterator(searchRoot, fs::directory_options::skip_permission_denied)) {
      if (entry.is_directory() && isDatasetDirectory(entry.path())) {
        choices.push_back(inspectDataset(entry.path()));
      }
    }
  }
  std::sort(choices.begin(), choices.end(), [](const DatasetChoice& lhs, const DatasetChoice& rhs) {
    return lhs.rows != rhs.rows ? lhs.rows < rhs.rows : lhs.name < rhs.name;
  });
  return choices;
}

fs::path resolveDataRoot() {
  if (const char* configured = std::getenv("ACCELERATOR_DATA_ROOT")) {
    if (*configured != '\0') {
      return fs::path(configured);
    }
  }
  return fs::path(ACCELERATOR_SIM_DEFAULT_DATA_ROOT);
}

#ifndef SPMV_INPUT_TRANSACTION_VERILATOR
fs::path resolveGoldenDirectory() {
  if (const char* configured = std::getenv("SPMV_GOLDEN_DIR")) {
    if (*configured != '\0') {
      return fs::path(configured);
    }
  }
  return fs::current_path() / "build" / "golden";
}
#endif

fs::path resolveEncodingReportDirectory() {
  if (const char* configured = std::getenv("SPMV_ENCODING_REPORT_DIR")) {
    if (*configured != '\0') {
      return fs::path(configured);
    }
  }
  return fs::current_path() / "build" / "encoding";
}

void printChoices(const fs::path& dataRoot, const std::vector<DatasetChoice>& choices,
                  const std::string& target) {
  std::cout << "Usage: make -C accelerator-sim/spmv " << target << " mainargs=<scale>\n";
  std::cout << "Available scales from " << dataRoot << ":\n";
  if (choices.empty()) {
    std::cout << "  (none; run make -C accelerator-sim/data)\n";
  }
  for (const DatasetChoice& choice : choices) {
    std::error_code error;
    const fs::path relative = fs::relative(choice.path, dataRoot, error);
    std::cout << "  " << std::left << std::setw(20) << choice.name
              << " n=" << std::right << std::setw(8) << choice.rows
              << " nnz=" << std::setw(10) << choice.nonzeros
              << "  " << (error ? choice.path : relative) << '\n';
  }
}

const DatasetChoice& selectDataset(const std::vector<DatasetChoice>& choices,
                                   const std::string& requested) {
  const DatasetChoice* selected = nullptr;
  for (const DatasetChoice& choice : choices) {
    if (choice.name == requested) {
      if (selected != nullptr) {
        throw std::runtime_error("ambiguous dataset scale: " + requested);
      }
      selected = &choice;
    }
  }
  if (selected == nullptr) {
    throw std::runtime_error("unknown dataset scale: " + requested);
  }
  return *selected;
}

CsrMatrix loadMatrix(const DatasetChoice& choice) {
  CsrMatrix matrix;
  matrix.rowPointers = readNonnegativeIntegers(choice.path / "row_ptr.txt");
  const std::vector<std::uint64_t> columns = readNonnegativeIntegers(choice.path / "col_idx.txt");
  matrix.values = readArray<double>(choice.path / "values.txt");
  matrix.rows = matrix.rowPointers.size() - 1;
  matrix.columns = matrix.rows;
  if (matrix.rowPointers.front() != 0) {
    throw std::runtime_error("row_ptr[0] must be zero");
  }
  if (matrix.rowPointers.back() != columns.size() || columns.size() != matrix.values.size()) {
    throw std::runtime_error("CSR row pointer, column, and value counts disagree");
  }
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    if (matrix.rowPointers[row] > matrix.rowPointers[row + 1]) {
      throw std::runtime_error("row_ptr must be nondecreasing");
    }
  }
  matrix.columnIndices.reserve(columns.size());
  for (std::uint64_t column : columns) {
    if (column >= matrix.columns || column > std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error("column index exceeds square matrix bounds");
    }
    matrix.columnIndices.push_back(static_cast<std::uint32_t>(column));
  }
  return matrix;
}

constexpr std::size_t kDefaultCuperAReaderCount = 16;
constexpr std::size_t kDefaultCuperXReaderCount = 2;
constexpr std::size_t kDefaultCuperCtrlReaderCount = 1;
constexpr std::size_t kDefaultCuperChannelCount = 16;
constexpr std::size_t kDefaultCuperBeatBytes = 64;
constexpr std::size_t kDefaultCuperChannelAlignment = 4096;
constexpr std::size_t kDefaultCuperHbmBytes = 128ULL * 1024ULL * 1024ULL;
constexpr std::uint64_t kDefaultCuperHbmBase = 0x80000000ULL;

struct CuperAConfig {
  std::size_t aReaderCount = kDefaultCuperAReaderCount;
  std::size_t xReaderCount = kDefaultCuperXReaderCount;
  std::size_t ctrlReaderCount = kDefaultCuperCtrlReaderCount;
  std::size_t hbmChannelCount = kDefaultCuperChannelCount;
  std::uint64_t hbmBase = kDefaultCuperHbmBase;
  std::size_t hbmBytes = kDefaultCuperHbmBytes;
  std::size_t channelAlignment = kDefaultCuperChannelAlignment;
  std::size_t axiAddrWidth = 64;
  std::size_t axiDataWidth = 512;
  std::size_t axiIdWidth = 4;
  std::size_t maxOutstandingBursts = 2;
  std::size_t xWindowSize = 8192;
  std::string xPortSchedule = "preload";
};

#ifndef SPMV_INPUT_PROFILE_FROZEN
std::uint64_t readUnsignedEnv(const char* name, std::uint64_t fallback) {
  const char* configured = std::getenv(name);
  if (configured == nullptr || *configured == '\0') {
    return fallback;
  }
  std::string text(configured);
  std::size_t consumed = 0;
  try {
    const std::uint64_t value = std::stoull(text, &consumed, 0);
    if (consumed != text.size()) {
      throw std::invalid_argument("trailing characters");
    }
    return value;
  } catch (const std::exception&) {
    throw std::invalid_argument(std::string(name) + " must be an unsigned integer");
  }
}

std::string readStringEnv(const char* name, const std::string& fallback) {
  const char* configured = std::getenv(name);
  return configured == nullptr || *configured == '\0' ? fallback : std::string(configured);
}
#endif

CuperAConfig readCuperAConfig() {
  CuperAConfig config;
#ifdef SPMV_INPUT_PROFILE_FROZEN
  static_assert(SPMV_CUPER_SLOT_COLUMN_BITS_FROZEN == encoding::cuper::kColumnBits &&
      SPMV_CUPER_SLOT_TAG_BITS_FROZEN == encoding::cuper::kTagBits &&
      SPMV_CUPER_SLOT_ROW_BITS_FROZEN == encoding::cuper::kRowBits,
      "冻结 profile 的 Cuper slot v4 位域与 host encoder 不一致");
  config.aReaderCount = SPMV_INPUT_A_READER_COUNT_FROZEN;
  config.xReaderCount = SPMV_INPUT_X_READER_COUNT_FROZEN;
  config.ctrlReaderCount = SPMV_INPUT_CTRL_READER_COUNT_FROZEN;
  config.hbmChannelCount = SPMV_INPUT_HBM_CHANNEL_COUNT_FROZEN;
  config.hbmBase = SPMV_INPUT_HBM_BASE_FROZEN;
  config.hbmBytes = SPMV_INPUT_HBM_BYTES_FROZEN;
  config.channelAlignment = SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES_FROZEN;
  config.axiAddrWidth = SPMV_INPUT_AXI_ADDR_WIDTH_FROZEN;
  config.axiDataWidth = SPMV_INPUT_AXI_DATA_WIDTH_FROZEN;
  config.axiIdWidth = SPMV_INPUT_AXI_ID_WIDTH_FROZEN;
  config.maxOutstandingBursts = SPMV_INPUT_MAX_OUTSTANDING_BURSTS_FROZEN;
  config.xWindowSize = SPMV_INPUT_X_WINDOW_SIZE_DEFAULT;
  config.xPortSchedule = SPMV_INPUT_X_PORT_SCHEDULE_FROZEN;
#else
  config.aReaderCount = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_A_READER_COUNT", config.aReaderCount));
  config.xReaderCount = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_X_READER_COUNT", config.xReaderCount));
  config.ctrlReaderCount = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_CTRL_READER_COUNT", config.ctrlReaderCount));
  config.hbmChannelCount = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_HBM_CHANNEL_COUNT", config.hbmChannelCount));
  config.hbmBase = readUnsignedEnv("SPMV_INPUT_HBM_BASE", config.hbmBase);
  config.hbmBytes = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_HBM_BYTES", config.hbmBytes));
  config.channelAlignment = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES", config.channelAlignment));
  config.axiAddrWidth = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_AXI_ADDR_WIDTH", config.axiAddrWidth));
  config.axiDataWidth = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_AXI_DATA_WIDTH", config.axiDataWidth));
  config.axiIdWidth = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_AXI_ID_WIDTH", config.axiIdWidth));
  config.maxOutstandingBursts = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_MAX_OUTSTANDING_BURSTS", config.maxOutstandingBursts));
#ifndef SPMV_INPUT_X_WINDOW_SIZE_DEFAULT
#define SPMV_INPUT_X_WINDOW_SIZE_DEFAULT 8192
#endif
  config.xWindowSize = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_X_WINDOW_SIZE", SPMV_INPUT_X_WINDOW_SIZE_DEFAULT));
  config.xPortSchedule = readStringEnv("SPMV_INPUT_X_PORT_SCHEDULE", config.xPortSchedule);
#endif
  if (config.aReaderCount == 0 || config.xReaderCount != 2 ||
      config.ctrlReaderCount != 1 ||
      config.hbmChannelCount != config.aReaderCount || config.hbmBytes == 0 ||
      config.channelAlignment == 0 ||
      (config.channelAlignment & (config.channelAlignment - 1)) != 0 ||
      config.axiAddrWidth != 64 || config.axiDataWidth != 512 || config.axiIdWidth == 0 ||
      config.maxOutstandingBursts < 2 ||
      (config.hbmBase & (config.channelAlignment - 1)) != 0 ||
      config.hbmBytes % config.channelAlignment != 0 ||
      config.xWindowSize == 0 || (config.xWindowSize & (config.xWindowSize - 1)) != 0 ||
      (config.xPortSchedule != "preload" && config.xPortSchedule != "pingpong")) {
    throw std::invalid_argument("SPMV_INPUT profile contains an invalid Cuper input layout");
  }
  return config;
}

struct CuperAInstance {
  std::size_t channel = 0;
  std::size_t storageOffset = 0;
  std::uint64_t address = 0;
  std::size_t beats = 0;
};

struct CuperAInstances {
  std::vector<CuperAInstance> instances;
  std::vector<std::uint8_t> hbm;
};

std::size_t alignValue(std::size_t value, std::size_t alignment) {
  if (alignment == 0 || (alignment & (alignment - 1)) != 0 ||
      value > std::numeric_limits<std::size_t>::max() - (alignment - 1)) {
    throw std::runtime_error("invalid Cuper channel alignment");
  }
  return (value + alignment - 1) & ~(alignment - 1);
}

std::uint64_t readLittleEndian64(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
  if (offset > bytes.size() || bytes.size() - offset < sizeof(std::uint64_t)) {
    throw std::runtime_error("Cuper A read exceeds host backing storage");
  }
  std::uint64_t value = 0;
  for (unsigned byte = 0; byte < sizeof(std::uint64_t); ++byte) {
    value |= static_cast<std::uint64_t>(bytes[offset + byte]) << (byte * 8U);
  }
  return value;
}

CuperAInstances instantiateCuperA(const encoding::cuper::CuperPackage& package,
                                  const CuperAConfig& config) {
  const std::size_t beatBytes = config.axiDataWidth / 8;
  if (package.config.hbmChannelCount != config.hbmChannelCount ||
      package.matrixChannels.size() != config.aReaderCount ||
      beatBytes != kDefaultCuperBeatBytes) {
    throw std::runtime_error("Cuper package and SPMV_INPUT A/HBM geometry disagree");
  }

  CuperAInstances result;
  result.instances.resize(config.aReaderCount);
  std::vector<std::size_t> offsets(config.aReaderCount);
  std::size_t storageBytes = 0;
  for (std::size_t channel = 0; channel < config.aReaderCount; ++channel) {
    const auto& beats = package.matrixChannels[channel];
    const std::size_t channelOffset = alignValue(storageBytes, config.channelAlignment);
    if (beats.size() > (std::numeric_limits<std::size_t>::max() - channelOffset) / beatBytes) {
      throw std::overflow_error("Cuper A channel storage size overflow");
    }
    const std::size_t channelBytes = beats.size() * beatBytes;
    if (config.hbmBase > std::numeric_limits<std::uint64_t>::max() - channelOffset) {
      throw std::overflow_error("Cuper A channel address overflow");
    }
    offsets[channel] = channelOffset;
    storageBytes = channelOffset + channelBytes;
    result.instances[channel] = CuperAInstance{
        channel, channelOffset, config.hbmBase + channelOffset, beats.size()};
  }
  if (storageBytes > config.hbmBytes) {
    throw std::runtime_error("Cuper A input exceeds the configured HBM window");
  }

  result.hbm.assign(storageBytes, 0);
  for (std::size_t channel = 0; channel < config.aReaderCount; ++channel) {
    const auto& beats = package.matrixChannels[channel];
    for (std::size_t beat = 0; beat < beats.size(); ++beat) {
      for (std::size_t lane = 0; lane < encoding::cuper::kLanesPerBeat; ++lane) {
        const std::uint64_t slot = beats[beat][lane];
        const std::size_t offset = offsets[channel] + beat * beatBytes + lane * 8;
        for (unsigned byte = 0; byte < sizeof(slot); ++byte) {
          result.hbm[offset + byte] = static_cast<std::uint8_t>(slot >> (byte * 8U));
        }
      }
    }
  }
  return result;
}

void validateCuperAInstances(const encoding::cuper::CuperPackage& package,
                             const CuperAInstances& instances, const CuperAConfig& config) {
  for (const CuperAInstance& instance : instances.instances) {
    const auto& beats = package.matrixChannels[instance.channel];
    if (instance.beats != beats.size() ||
        instance.storageOffset % config.channelAlignment != 0 ||
        instance.address != config.hbmBase + instance.storageOffset) {
      throw std::runtime_error("Cuper A instance metadata does not match its channel");
    }
    for (std::size_t beat = 0; beat < beats.size(); ++beat) {
      for (std::size_t lane = 0; lane < encoding::cuper::kLanesPerBeat; ++lane) {
        const std::size_t offset = instance.storageOffset +
            beat * (config.axiDataWidth / 8) + lane * 8;
        if (readLittleEndian64(instances.hbm, offset) != beats[beat][lane]) {
          throw std::runtime_error("Cuper A channel data changed while materializing HBM input");
        }
      }
    }
  }
}

#ifndef SPMV_INPUT_TRANSACTION_VERILATOR
int runGolden(const DatasetChoice& choice) {
  const auto loadStart = std::chrono::steady_clock::now();
  const CsrMatrix matrix = loadMatrix(choice);
  const std::vector<double> input = readArray<double>(choice.path / "b.txt");
  if (input.size() != matrix.columns) {
    throw std::runtime_error("b.txt length must equal matrix column count");
  }
  const auto loadEnd = std::chrono::steady_clock::now();
  const auto goldenStart = std::chrono::steady_clock::now();
  const GoldenResult golden = computeGolden(matrix, input);
  const auto goldenEnd = std::chrono::steady_clock::now();
  const fs::path outputPath = resolveGoldenDirectory() / (choice.name + ".txt");
  writeGolden(outputPath, golden.output);
  const double loadMilliseconds =
      std::chrono::duration<double, std::milli>(loadEnd - loadStart).count();
  const double goldenMilliseconds =
      std::chrono::duration<double, std::milli>(goldenEnd - goldenStart).count();
  std::cout << "[spmv-host] scale=" << choice.name << " dataset=" << choice.path
            << " input=b.txt dtype=fp64\n";
  std::cout << "[spmv-host] rows=" << matrix.rows << " columns=" << matrix.columns
            << " nnz=" << matrix.values.size() << " load_ms=" << std::fixed
            << std::setprecision(3) << loadMilliseconds << " golden_ms=" << goldenMilliseconds << '\n';
  std::cout << std::setprecision(17) << "[golden] checksum=" << golden.checksum
            << " l1=" << golden.l1Norm << " max_abs=" << golden.maxAbs
            << " hash=0x" << std::hex << golden.bitHash << std::dec << '\n';
  std::cout << "[golden] output=" << outputPath << '\n';
  return 0;
}
#endif

int runCuperASmokeTest(const std::string& requested) {
  const fs::path dataRoot = resolveDataRoot();
  const std::vector<DatasetChoice> choices = discoverDatasets(dataRoot);
  if (requested.empty() || requested == "--list") {
    printChoices(dataRoot, choices, "cuper-a-test");
    return 0;
  }
  if (choices.empty()) {
    throw std::runtime_error("no CSR datasets were found under " + dataRoot.string() +
        "; run make -C accelerator-sim/data");
  }

  const DatasetChoice& choice = selectDataset(choices, requested);
  const CsrMatrix matrix = loadMatrix(choice);
  encoding::EncodingOptions options;
  options.format = encoding::EncodingFormat::Cuper;
  const encoding::EncodedMatrix encoded = encoding::encodeMatrix(matrix, options);
  const auto& package = std::get<encoding::cuper::CuperPackage>(encoded.package);
  const CuperAConfig config = readCuperAConfig();
  const CuperAInstances instances = instantiateCuperA(package, config);
  validateCuperAInstances(package, instances, config);

  std::cout << "[spmv-cuper-a] scale=" << choice.name
            << " instances=" << instances.instances.size()
            << " hbm_channels=" << config.hbmChannelCount
            << " hbm_base=0x" << std::hex << config.hbmBase << std::dec
            << " hbm_bytes=" << config.hbmBytes
            << " total_beats=" << package.stats.totalMatrixBeats
            << " backing_bytes=" << instances.hbm.size() << '\n';
  for (const CuperAInstance& instance : instances.instances) {
    std::cout << "[spmv-cuper-a] channel=" << instance.channel
              << " address=0x" << std::hex << instance.address << std::dec
              << " beats=" << instance.beats << '\n';
  }
  std::cout << "[spmv-cuper-a] " << instances.instances.size()
            << " independent A inputs PASS\n";
  return 0;
}

int runEncoding(const std::string& formatName, const std::string& requested) {
  const fs::path dataRoot = resolveDataRoot();
  const std::vector<DatasetChoice> choices = discoverDatasets(dataRoot);
  const encoding::EncodingFormat format = encoding::parseEncodingFormat(formatName);
  if (requested.empty() || requested == "--list") {
    printChoices(dataRoot, choices, "encode ENCODING=" + formatName);
    return 0;
  }
  if (choices.empty()) {
    throw std::runtime_error("no CSR datasets were found under " + dataRoot.string() +
        "; run make -C accelerator-sim/data");
  }

  const DatasetChoice& choice = selectDataset(choices, requested);
  const CsrMatrix matrix = loadMatrix(choice);
  const std::vector<double> input = readArray<double>(choice.path / "b.txt");
  if (input.size() != matrix.columns) {
    throw std::runtime_error("b.txt length must equal matrix column count");
  }
  encoding::EncodingOptions options;
  options.format = format;
  const auto start = std::chrono::steady_clock::now();
  const encoding::EncodedMatrix encoded = encoding::encodeMatrix(matrix, options);
  const encoding::EncodedVector encodedVector = encoding::encodeVector(input, options);
  const auto end = std::chrono::steady_clock::now();
  const fs::path reportPath = resolveEncodingReportDirectory() /
      std::string(encoding::encodingFormatName(encoded.format)) / (choice.name + ".html");
  const fs::path vectorReportPath = resolveEncodingReportDirectory() /
      std::string(encoding::encodingFormatName(encoded.format)) / (choice.name + "-x.html");
  const fs::path demandSchedulePath = resolveEncodingReportDirectory() /
      std::string(encoding::encodingFormatName(encoded.format)) / (choice.name + "-demand.json");
  encoding::writeVectorHtmlReport(vectorReportPath, encodedVector,
      encoding::EncodingReportMetadata{choice.name, (choice.path / "b.txt").string()});
  encoding::writeHtmlReport(reportPath, encoded,
      encoding::EncodingReportMetadata{choice.name, choice.path.string()});

  if (format == encoding::EncodingFormat::Cuper) {
    const auto& package = std::get<encoding::cuper::CuperPackage>(encoded.package);
    const auto& vectorPackage =
        std::get<encoding::cuper::CuperVectorPackage>(encodedVector.package);
    const encoding::cuper::CuperDemandSchedule demandSchedule =
        encoding::cuper::planXPageSchedule(package);
    {
      std::error_code error;
      fs::create_directories(demandSchedulePath.parent_path(), error);
      if (error) {
        throw std::runtime_error("无法创建 Cuper X demand schedule 目录 " +
            demandSchedulePath.parent_path().string() + ": " + error.message());
      }
      const fs::path temporary = demandSchedulePath.string() + ".tmp";
      std::ofstream demandOutput(temporary);
      if (!demandOutput) {
        throw std::runtime_error("无法打开 Cuper X demand schedule: " + temporary.string());
      }
      encoding::cuper::writeDemandScheduleJson(demandOutput, demandSchedule,
          choice.name, choice.path.string());
      demandOutput.close();
      if (!demandOutput) {
        fs::remove(temporary);
        throw std::runtime_error("无法写入 Cuper X demand schedule: " + temporary.string());
      }
      fs::rename(temporary, demandSchedulePath, error);
      if (error) {
        fs::remove(temporary);
        throw std::runtime_error("无法发布 Cuper X demand schedule " +
            demandSchedulePath.string() + ": " + error.message());
      }
    }
    const double milliseconds = std::chrono::duration<double, std::milli>(end - start).count();
    std::cout << "[spmv-encoding] format=" << encoding::encodingFormatName(encoded.format)
              << " scale=" << choice.name << " dataset=" << choice.path << '\n';
    std::cout << "[spmv-encoding] rows=" << package.rows
              << " columns=" << package.columns
              << " nnz=" << package.nonzeros
              << " batches=" << package.stats.batchCount
              << " hbm_channels=" << package.config.hbmChannelCount
              << " pes=" << encoding::cuper::totalPeCount(package.config) << '\n';
    std::cout << "[spmv-encoding] channel_beats_min="
              << package.stats.minimumMatrixBeatsPerChannel
              << " channel_beats_max=" << package.stats.maximumMatrixBeatsPerChannel
              << " total_beats=" << package.stats.totalMatrixBeats
              << " matrix_slots=" << package.stats.matrixSlots
              << " zero_fill_slots=" << package.stats.zeroFillSlots
              << " matrix_slot_utilization=" << std::fixed << std::setprecision(6)
              << package.stats.matrixSlotUtilization()
              << " packed_bytes=" << package.stats.packedBytes
              << " encode_ms=" << std::setprecision(3) << milliseconds << '\n';
    std::cout << "[spmv-encoding-x] source=fp64 encoded=fp32"
              << " elements=" << vectorPackage.stats.validElements
              << " batches=" << vectorPackage.stats.batchCount
              << " payload_beats=" << vectorPackage.stats.payloadBeats
              << " allocated_beats=" << vectorPackage.stats.allocatedBeats
              << " replicas_per_core=" << encoding::cuper::kVectorReplicaCount
              << " cyclic_banks=" << encoding::cuper::kVectorPartitionFactor << '\n';
    for (const encoding::cuper::CuperDemandBatchPlan& batch : demandSchedule.batches) {
      std::cout << "[spmv-demand] batch=" << batch.batch
                << " pages=" << batch.pageCount
                << " x_load_cycles=" << batch.xLoadCycles
                << " first_a_baseline=" << batch.baseline.firstIssueCycle
                << " first_a_planned=" << batch.planned.firstIssueCycle
                << " a_beats_before_x_complete=" << batch.planned.issuedBeforeXComplete
                << " channels_started_before_x_complete="
                << batch.planned.channelsStartedBeforeXComplete << '\n';
    }
  }
  std::cout << "[spmv-encoding] html=" << reportPath << '\n';
  std::cout << "[spmv-encoding-x] html=" << vectorReportPath << '\n';
  std::cout << "[spmv-demand] json=" << demandSchedulePath << '\n';
  return 0;
}

#ifdef SPMV_INPUT_TRANSACTION_VERILATOR
std::uint64_t fp64Bits(double value) {
  std::uint64_t bits = 0;
  static_assert(sizeof(bits) == sizeof(value));
  std::memcpy(&bits, &value, sizeof(bits));
  return bits;
}

void validateCuperBatchMap(const encoding::cuper::CuperPackage& package,
                           const CuperAConfig& inputConfig) {
  const std::size_t batchWidth = encoding::cuper::columnsPerBatch(package.config);
  const std::size_t expectedBatchCount =
      (package.columns + batchWidth - 1U) / batchWidth;
  if (batchWidth != inputConfig.xWindowSize || package.stats.batchCount != expectedBatchCount ||
      package.channelBatchPointers.size() != inputConfig.aReaderCount ||
      package.matrixChannels.size() != inputConfig.aReaderCount) {
    throw std::runtime_error("Cuper map 与 local_X 窗口或 A HBM 布局不一致");
  }
  if (package.stats.batchCount == 0 || package.stats.batchCount > 256U) {
    throw std::runtime_error("Cuper map batch 数超出当前 256-entry 硬件控制 RAM");
  }
  for (std::size_t channel = 0; channel < package.matrixChannels.size(); ++channel) {
    const auto& pointers = package.channelBatchPointers[channel];
    if (pointers.size() != package.stats.batchCount + 1U || pointers.front() != 0U ||
        pointers.back() != package.matrixChannels[channel].size() ||
        !std::is_sorted(pointers.begin(), pointers.end())) {
      throw std::runtime_error("Cuper map 的 per-channel batch pointer 非法");
    }
  }
}

std::uint64_t computeMixedProductChecksum(const encoding::cuper::CuperPackage& package,
                                          const std::vector<double>& x,
                                          std::size_t batch) {
  std::uint64_t checksum = 0;
  const std::size_t batchWidth = encoding::cuper::columnsPerBatch(package.config);
  if (package.matrixChannels.size() != package.channelBatchPointers.size()) {
    throw std::runtime_error("Cuper package 的 channel 与 map 长度不一致");
  }
  if (batch >= package.stats.batchCount) {
    throw std::out_of_range("Mixed-V3 乘法 golden 请求了不存在的 Cuper batch");
  }
  for (std::size_t channel = 0; channel < package.matrixChannels.size(); ++channel) {
    const auto& beats = package.matrixChannels[channel];
    const auto& pointers = package.channelBatchPointers[channel];
    if (pointers.size() <= batch + 1U) {
      throw std::runtime_error("Cuper map 缺少 channel batch pointer");
    }
    for (std::size_t beat = pointers[batch]; beat < pointers[batch + 1U]; ++beat) {
      for (std::size_t lane = 0; lane < encoding::cuper::kLanesPerBeat; ++lane) {
        const encoding::cuper::DecodedCuperSlot slot =
            encoding::cuper::decodeSlot(beats[beat][lane]);
        const std::size_t column = batch * batchWidth + slot.localColumn;
        if (column >= x.size()) {
          throw std::runtime_error("Mixed-V3 乘法 golden 的列号超出 X 范围");
        }
        checksum ^= fp64Bits(static_cast<double>(slot.value) * x[column]);
      }
    }
  }
  return checksum;
}

std::uint64_t computeMixedProductChecksum(const encoding::cuper::CuperPackage& package,
                                          const std::vector<double>& x) {
  std::uint64_t checksum = 0;
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    checksum ^= computeMixedProductChecksum(package, x, batch);
  }
  return checksum;
}

encoding::cuper::CuperBeat packXBeat(const std::vector<double>& input, std::size_t begin) {
  encoding::cuper::CuperBeat beat{};
  for (std::size_t lane = 0; lane < beat.size() && begin + lane < input.size(); ++lane) {
    static_assert(sizeof(input[begin + lane]) == sizeof(beat[lane]));
    std::memcpy(&beat[lane], &input[begin + lane], sizeof(beat[lane]));
  }
  return beat;
}

std::vector<std::vector<encoding::cuper::CuperBeat>> packXWindow(
    const std::vector<double>& input, std::size_t firstColumn, std::size_t lastColumn,
    std::size_t xReaderCount, const encoding::cuper::CuperDemandBatchPlan& pagePlan,
    std::size_t pageElements) {
  if (firstColumn >= lastColumn || lastColumn > input.size() || xReaderCount != 2U) {
    throw std::invalid_argument("Cuper X 窗口范围或双路条带配置非法");
  }
  if (pageElements == 0 || pageElements % (xReaderCount * encoding::cuper::kLanesPerBeat) != 0U ||
      pagePlan.columns != lastColumn - firstColumn || pagePlan.pageOrder.size() != pagePlan.pageCount) {
    throw std::invalid_argument("Cuper X page 重排与物理 X 条带布局不兼容");
  }
  std::vector<bool> seen(pagePlan.pageCount, false);
  std::vector<double> physicalWindow;
  physicalWindow.reserve(pagePlan.pageCount * pageElements);
  for (std::size_t logicalPage : pagePlan.pageOrder) {
    if (logicalPage >= pagePlan.pageCount || seen[logicalPage]) {
      throw std::invalid_argument("Cuper X page 重排不是一个 page 排列");
    }
    seen[logicalPage] = true;
    for (std::size_t offset = 0; offset < pageElements; ++offset) {
      const std::size_t sourceColumn = firstColumn + logicalPage * pageElements + offset;
      physicalWindow.push_back(sourceColumn < lastColumn ? input[sourceColumn] : 0.0);
    }
  }
  const std::size_t payloadBeats = physicalWindow.size() / encoding::cuper::kLanesPerBeat;
  if (payloadBeats == 0 || payloadBeats % xReaderCount != 0U) {
    throw std::logic_error("Cuper X page 打包没有形成等长单路广播流");
  }
  std::vector<std::vector<encoding::cuper::CuperBeat>> channels(xReaderCount);
  for (std::size_t beat = 0; beat < payloadBeats; ++beat) {
    channels[beat % xReaderCount].push_back(
        packXBeat(physicalWindow, beat * encoding::cuper::kLanesPerBeat));
  }
  return channels;
}

std::size_t multiplySlotCount(const std::vector<encoding::cuper::CuperBeat>& beats) {
  return beats.size() * encoding::cuper::kLanesPerBeat;
}

void writeCtrlWord(encoding::cuper::CuperBeat& beat, std::size_t word, std::uint32_t value) {
  const std::size_t lane = word / 2U;
  if (lane >= beat.size()) {
    throw std::out_of_range("控制面 beat 不能写入超过 16 个 uint32");
  }
  if ((word % 2U) == 0U) {
    beat[lane] = (beat[lane] & 0xffffffff00000000ULL) | value;
  } else {
    beat[lane] = (beat[lane] & 0xffffffffULL) | (static_cast<std::uint64_t>(value) << 32U);
  }
}

std::vector<encoding::cuper::CuperBeat> packCtrlMap(
    const encoding::cuper::CuperPackage& package) {
  if (package.channelBatchPointers.size() != package.config.hbmChannelCount ||
      package.channelBatchPointers.empty()) {
    throw std::runtime_error("Cuper map 的 per-HBM pointer 数量与 channel 数不一致");
  }
  const std::size_t pointerCount = package.channelBatchPointers.front().size();
  for (const auto& pointers : package.channelBatchPointers) {
    if (pointers.size() != pointerCount) {
      throw std::runtime_error("Cuper map 要求各 HBM channel 的 pointer 长度相同");
    }
  }

  constexpr std::uint32_t kCtrlKindMap = 1;
  std::vector<encoding::cuper::CuperBeat> beats(1U + pointerCount);
  writeCtrlWord(beats[0], 0, kCtrlKindMap);
  writeCtrlWord(beats[0], 1, static_cast<std::uint32_t>(package.stats.batchCount));
  writeCtrlWord(beats[0], 2, static_cast<std::uint32_t>(package.rows));
  writeCtrlWord(beats[0], 3, static_cast<std::uint32_t>(package.columns));
  writeCtrlWord(beats[0], 4, static_cast<std::uint32_t>(package.config.hbmChannelCount));
  writeCtrlWord(beats[0], 5, static_cast<std::uint32_t>(pointerCount));
  for (std::size_t index = 0; index < pointerCount; ++index) {
    for (std::size_t channel = 0; channel < package.channelBatchPointers.size(); ++channel) {
      writeCtrlWord(beats[index + 1U], channel, package.channelBatchPointers[channel][index]);
    }
  }
  return beats;
}

int runInputTransactions(const std::string& requested) {
  const fs::path dataRoot = resolveDataRoot();
  const std::vector<DatasetChoice> choices = discoverDatasets(dataRoot);
  const std::string dataset = requested.empty() ? "n512" : requested;
  if (dataset == "--list") {
    printChoices(dataRoot, choices, "run");
    return 0;
  }
  if (choices.empty()) {
    throw std::runtime_error("no CSR datasets were found under " + dataRoot.string() +
        "; run make -C accelerator-sim/data");
  }

  const DatasetChoice& choice = selectDataset(choices, dataset);
  const CsrMatrix matrix = loadMatrix(choice);
  const std::vector<double> x = readArray<double>(choice.path / "b.txt");
  if (x.size() != matrix.columns) {
    throw std::runtime_error("b.txt length must equal matrix column count");
  }
  encoding::EncodingOptions options;
  options.format = encoding::EncodingFormat::Cuper;
  const encoding::EncodedMatrix encoded = encoding::encodeMatrix(matrix, options);
  const auto& sourcePackage = std::get<encoding::cuper::CuperPackage>(encoded.package);
  const CuperAConfig config = readCuperAConfig();
  const encoding::cuper::CuperDemandScheduleConfig demandConfig{
      sourcePackage.config.sliceSize, 8U};
  const encoding::cuper::CuperDemandSchedule demandSchedule =
      encoding::cuper::planXPageSchedule(sourcePackage, demandConfig);
  const encoding::cuper::CuperPackage package =
      encoding::cuper::remapLocalColumnsForXPageSchedule(sourcePackage, demandSchedule);
  const CuperAInstances instances = instantiateCuperA(package, config);
  validateCuperAInstances(package, instances, config);
  validateCuperBatchMap(package, config);

  InputSimulationData simulation;
  simulation.dataset = choice.name;
  simulation.hbmBase = config.hbmBase;
  simulation.hbmBytes = config.hbmBytes;
  simulation.aChannels = package.matrixChannels;
  simulation.aAddresses.reserve(instances.instances.size());
  for (const CuperAInstance& instance : instances.instances) {
    simulation.aAddresses.push_back(instance.address);
  }
  simulation.xAddresses.resize(config.xReaderCount);
  simulation.xChannels.resize(config.xReaderCount);
  simulation.maxOutstandingBursts = config.maxOutstandingBursts;
  simulation.xPortSchedule = config.xPortSchedule == "pingpong"
      ? InputXPortSchedule::PingPong : InputXPortSchedule::Preload;
  std::vector<std::vector<std::size_t>> xBatchOffsets(
      package.stats.batchCount, std::vector<std::size_t>(config.xReaderCount));
  const std::size_t batchWidth = encoding::cuper::columnsPerBatch(package.config);
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    InputSimulationBatch window;
    window.aAddresses.resize(config.aReaderCount);
    window.aChannels.resize(config.aReaderCount);
    for (std::size_t channel = 0; channel < config.aReaderCount; ++channel) {
      const auto& pointers = package.channelBatchPointers[channel];
      const std::size_t begin = pointers[batch];
      const std::size_t end = pointers[batch + 1U];
      window.aChannels[channel] = std::vector<encoding::cuper::CuperBeat>(
          package.matrixChannels[channel].begin() + static_cast<std::ptrdiff_t>(begin),
          package.matrixChannels[channel].begin() + static_cast<std::ptrdiff_t>(end));
      window.aAddresses[channel] = simulation.aAddresses[channel] + begin * kDefaultCuperBeatBytes;
      window.expectedMultiplyCount += multiplySlotCount(window.aChannels[channel]);
    }
    const std::size_t firstColumn = batch * batchWidth;
    const std::size_t lastColumn = std::min(firstColumn + batchWidth, x.size());
    window.xChannels = packXWindow(x, firstColumn, lastColumn, config.xReaderCount,
        demandSchedule.batches[batch], demandSchedule.config.pageElements);
    window.xAddresses.resize(config.xReaderCount);
    for (std::size_t channel = 0; channel < config.xReaderCount; ++channel) {
      xBatchOffsets[batch][channel] = simulation.xChannels[channel].size();
      simulation.xChannels[channel].insert(simulation.xChannels[channel].end(),
          window.xChannels[channel].begin(), window.xChannels[channel].end());
    }
    window.expectedProductChecksum = computeMixedProductChecksum(sourcePackage, x, batch);
    simulation.expectedProductChecksum ^= window.expectedProductChecksum;
    simulation.expectedMultiplyCount += window.expectedMultiplyCount;
    simulation.batches.push_back(std::move(window));
  }
  // slot v4 不再在带内编码 padding；每个物理 lane 都会读 X 并进入 FMUL。
  // CSR nnz 只用于编码密度统计，不能作为实际 FMUL 数的验收基准。
  const std::uint64_t expectedPhysicalMultiplyCount =
      package.stats.totalMatrixBeats * encoding::cuper::kLanesPerBeat;
  if (simulation.expectedMultiplyCount != expectedPhysicalMultiplyCount ||
      simulation.expectedProductChecksum != computeMixedProductChecksum(sourcePackage, x)) {
    throw std::runtime_error("Cuper 分窗口 Mixed-V3 golden 与完整物理 slot 流不一致");
  }
  simulation.multiplyExpected = true;
  std::size_t xStorageEnd = instances.hbm.size();
  for (std::size_t channel = 0; channel < config.xReaderCount; ++channel) {
    const std::size_t xOffset = alignValue(xStorageEnd, config.channelAlignment);
    const std::size_t xBytes = simulation.xChannels[channel].size() *
        (config.axiDataWidth / 8);
    if (xOffset > config.hbmBytes || xBytes > config.hbmBytes - xOffset) {
      throw std::runtime_error("Cuper A and X inputs exceed the configured HBM window");
    }
    simulation.xAddresses[channel] = config.hbmBase + xOffset;
    xStorageEnd = xOffset + xBytes;
  }
  for (std::size_t batch = 0; batch < simulation.batches.size(); ++batch) {
    for (std::size_t channel = 0; channel < config.xReaderCount; ++channel) {
      simulation.batches[batch].xAddresses[channel] = simulation.xAddresses[channel] +
          xBatchOffsets[batch][channel] * kDefaultCuperBeatBytes;
    }
  }
  simulation.ctrlChannel = packCtrlMap(package);
  const std::size_t ctrlOffset = alignValue(xStorageEnd, config.channelAlignment);
  const std::size_t ctrlBytes = simulation.ctrlChannel.size() * (config.axiDataWidth / 8);
  if (ctrlOffset > config.hbmBytes || ctrlBytes > config.hbmBytes - ctrlOffset) {
    throw std::runtime_error("Cuper A/X/Ctrl 输入超过配置的 HBM 窗口");
  }
  simulation.ctrlAddress = config.hbmBase + ctrlOffset;
#ifndef SPMV_PERFORMANCE_HTML_DEFAULT
#define SPMV_PERFORMANCE_HTML_DEFAULT 1
#endif
#ifndef SPMV_PIPELINE_HTML_DEFAULT
#define SPMV_PIPELINE_HTML_DEFAULT 1
#endif
  simulation.performanceHtml = SPMV_PERFORMANCE_HTML_DEFAULT != 0;
  simulation.pipelineHtml = SPMV_PIPELINE_HTML_DEFAULT != 0;
  if (simulation.pipelineHtml && !simulation.performanceHtml) {
    throw std::invalid_argument("SPMV_PIPELINE_HTML requires SPMV_PERFORMANCE_HTML");
  }

  const InputSimulationResult result = runInputSimulation(simulation);
  const std::size_t xBeats = std::accumulate(
      simulation.xChannels.begin(), simulation.xChannels.end(), std::size_t{0},
      [](std::size_t sum, const auto& channel) { return sum + channel.size(); });
  std::cout << "[spmv-input] dataset=" << choice.name
            << " A_readers=" << simulation.aChannels.size()
            << " A_beats=" << package.stats.totalMatrixBeats
            << " X_readers=" << simulation.xChannels.size()
            << " X_broadcast_consumers=16 X_beats=" << xBeats
            << " Ctrl_readers=1 Ctrl_beats=" << simulation.ctrlChannel.size()
            << " Cuper_batches=" << simulation.batches.size()
            << " cycles=" << result.cycles << " PASS\n";
  if (result.multiplyCompared) {
    std::cout << "[spmv-input] mixed-v3 fp64_mul=" << simulation.expectedMultiplyCount
              << " product_checksum=0x" << std::hex << simulation.expectedProductChecksum << std::dec
              << " mul_cycles=" << result.mulCycles << " PASS\n";
    std::cout << "[spmv-input] schedule=" << config.xPortSchedule
              << " x_load_cycles=" << result.xLoadCycles
              << " x_overlap_cycles=" << result.xOverlapCycles
              << " x_drain_cycles=" << result.xDrainCycles
              << " first_a_cycle=" << result.firstABeatCycle
              << " first_fmul_cycle=" << result.firstMulRequestCycle
              << " early_a_batches=" << result.xAEarlyStartBatches << '/'
              << simulation.batches.size() << " PASS\n";
  }
  if (!result.performanceReport.empty()) {
    std::cout << "[spmv-input] performance=" << result.performanceReport << '\n';
  }
  if (!result.inputPipelineReport.empty()) {
    std::cout << "[spmv-input] input_pipeline=" << result.inputPipelineReport << '\n';
  }
  if (!result.timingPipelineReport.empty()) {
    std::cout << "[spmv-input] timing_pipeline=" << result.timingPipelineReport << '\n';
  }
  return 0;
}
#endif

int run(const std::string& requested) {
#ifdef SPMV_INPUT_TRANSACTION_VERILATOR
  return runInputTransactions(requested);
#else
  const fs::path dataRoot = resolveDataRoot();
  const std::vector<DatasetChoice> choices = discoverDatasets(dataRoot);
  if (requested.empty() || requested == "--list") {
    printChoices(dataRoot, choices, "run");
    return 0;
  }
  if (choices.empty()) {
    throw std::runtime_error("no CSR datasets were found under " + dataRoot.string() +
        "; run make -C accelerator-sim/data");
  }
  return runGolden(selectDataset(choices, requested));
#endif
}

}  // namespace
}  // namespace accelerator_sim::spmv

int main(int argc, char** argv) {
  try {
    if (argc >= 2 && std::string(argv[1]) == "--encode") {
      if (argc < 3 || argc > 4) {
        throw std::invalid_argument("用法: spmv-host --encode <format> [dataset]");
      }
      return accelerator_sim::spmv::runEncoding(argv[2], argc == 4 ? argv[3] : "");
    }
    if (argc >= 2 && std::string(argv[1]) == "--check-cuper-a") {
      if (argc > 3) {
        throw std::invalid_argument("用法: spmv-host --check-cuper-a [dataset]");
      }
      return accelerator_sim::spmv::runCuperASmokeTest(argc == 3 ? argv[2] : "");
    }
    return accelerator_sim::spmv::run(argc >= 2 ? argv[1] : "");
  } catch (const std::exception& error) {
    std::cerr << "spmv-host: " << error.what() << '\n';
    return 2;
  }
}
