#include "golden.hpp"
#include "encoding/encoder.hpp"
#include "input_simulation.hpp"

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
constexpr std::size_t kDefaultCuperXReaderCount = 1;
constexpr std::size_t kDefaultCuperChannelCount = 16;
constexpr std::size_t kDefaultCuperBeatBytes = 64;
constexpr std::size_t kDefaultCuperChannelAlignment = 4096;
constexpr std::size_t kDefaultCuperHbmBytes = 128ULL * 1024ULL * 1024ULL;
constexpr std::uint64_t kDefaultCuperHbmBase = 0x80000000ULL;

struct CuperAConfig {
  std::size_t aReaderCount = kDefaultCuperAReaderCount;
  std::size_t xReaderCount = kDefaultCuperXReaderCount;
  std::size_t hbmChannelCount = kDefaultCuperChannelCount;
  std::uint64_t hbmBase = kDefaultCuperHbmBase;
  std::size_t hbmBytes = kDefaultCuperHbmBytes;
  std::size_t channelAlignment = kDefaultCuperChannelAlignment;
  std::size_t axiAddrWidth = 64;
  std::size_t axiDataWidth = 512;
  std::size_t axiIdWidth = 4;
  std::size_t maxOutstandingBursts = 2;
};

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

CuperAConfig readCuperAConfig() {
  CuperAConfig config;
  config.aReaderCount = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_A_READER_COUNT", config.aReaderCount));
  config.xReaderCount = static_cast<std::size_t>(readUnsignedEnv(
      "SPMV_INPUT_X_READER_COUNT", config.xReaderCount));
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
  if (config.aReaderCount == 0 || config.xReaderCount != 1 ||
      config.hbmChannelCount != config.aReaderCount || config.hbmBytes == 0 ||
      config.channelAlignment == 0 ||
      (config.channelAlignment & (config.channelAlignment - 1)) != 0 ||
      config.axiAddrWidth != 64 || config.axiDataWidth != 512 || config.axiIdWidth == 0 ||
      config.maxOutstandingBursts < 2 ||
      (config.hbmBase & (config.channelAlignment - 1)) != 0 ||
      config.hbmBytes % config.channelAlignment != 0) {
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
  encoding::EncodingOptions options;
  options.format = format;
  const auto start = std::chrono::steady_clock::now();
  const encoding::EncodedMatrix encoded = encoding::encodeMatrix(matrix, options);
  const auto end = std::chrono::steady_clock::now();
  const fs::path reportPath = resolveEncodingReportDirectory() /
      std::string(encoding::encodingFormatName(encoded.format)) / (choice.name + ".html");
  encoding::writeHtmlReport(reportPath, encoded,
      encoding::EncodingReportMetadata{choice.name, choice.path.string()});

  if (format == encoding::EncodingFormat::Cuper) {
    const auto& package = std::get<encoding::cuper::CuperPackage>(encoded.package);
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
              << " valid_slots=" << package.stats.validSlots
              << " padding_slots=" << package.stats.paddingSlots
              << " slot_utilization=" << std::fixed << std::setprecision(6)
              << package.stats.slotUtilization()
              << " packed_bytes=" << package.stats.packedBytes
              << " encode_ms=" << std::setprecision(3) << milliseconds << '\n';
  }
  std::cout << "[spmv-encoding] html=" << reportPath << '\n';
  return 0;
}

#ifdef SPMV_INPUT_TRANSACTION_VERILATOR
encoding::cuper::CuperBeat packXBeat(const std::vector<double>& input, std::size_t begin) {
  encoding::cuper::CuperBeat beat{};
  for (std::size_t lane = 0; lane < beat.size() && begin + lane < input.size(); ++lane) {
    static_assert(sizeof(input[begin + lane]) == sizeof(beat[lane]));
    std::memcpy(&beat[lane], &input[begin + lane], sizeof(beat[lane]));
  }
  return beat;
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
  const auto& package = std::get<encoding::cuper::CuperPackage>(encoded.package);
  const CuperAConfig config = readCuperAConfig();
  const CuperAInstances instances = instantiateCuperA(package, config);
  validateCuperAInstances(package, instances, config);

  InputSimulationData simulation;
  simulation.dataset = choice.name;
  simulation.hbmBase = config.hbmBase;
  simulation.hbmBytes = config.hbmBytes;
  simulation.aChannels = package.matrixChannels;
  simulation.aAddresses.reserve(instances.instances.size());
  for (const CuperAInstance& instance : instances.instances) {
    simulation.aAddresses.push_back(instance.address);
  }
  const std::size_t xOffset = alignValue(instances.hbm.size(), config.channelAlignment);
  simulation.xAddress = config.hbmBase + xOffset;
  simulation.maxOutstandingBursts = config.maxOutstandingBursts;
  for (std::size_t begin = 0; begin < x.size(); begin += encoding::cuper::kLanesPerBeat) {
    simulation.xBeats.push_back(packXBeat(x, begin));
  }
  if (xOffset > config.hbmBytes || simulation.xBeats.size() >
      (config.hbmBytes - xOffset) / (config.axiDataWidth / 8)) {
    throw std::runtime_error("Cuper A and X inputs exceed the configured HBM window");
  }
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
  std::cout << "[spmv-input] dataset=" << choice.name
            << " A_readers=" << simulation.aChannels.size()
            << " A_beats=" << package.stats.totalMatrixBeats
            << " X_broadcast_consumers=16 X_beats=" << simulation.xBeats.size()
            << " cycles=" << result.cycles << " PASS\n";
  if (!result.performanceReport.empty()) {
    std::cout << "[spmv-input] performance=" << result.performanceReport << '\n';
  }
  if (!result.pipelineReport.empty()) {
    std::cout << "[spmv-input] pipeline=" << result.pipelineReport << '\n';
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
