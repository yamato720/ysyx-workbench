#include "golden.hpp"

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
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <system_error>
#include <utility>
#include <vector>

#ifdef SPMV_CSR5_VERILATOR
#include "VSpmvOneHbmCsr5MulSimulationTop.h"
#include "svdpi.h"
#include "verilated.h"
extern "C" {
#include "softfloat.h"
}
#endif

#ifndef SPMV_PERFORMANCE_HTML
#define SPMV_PERFORMANCE_HTML 0
#endif

#ifndef SPMV_PIPELINE_HTML
#define SPMV_PIPELINE_HTML 0
#endif

namespace fs = std::filesystem;

#ifdef SPMV_CSR5_VERILATOR
namespace {

constexpr std::uint64_t kHbmBase = 0x80000000ULL;
constexpr std::size_t kHbmBytes = 128ULL * 1024ULL * 1024ULL;
constexpr std::uint32_t kSpmvHbmLatencyMin = 73;
constexpr std::uint32_t kSpmvHbmLatencyMax = 81;
constexpr std::uint32_t kSpmvHbmFixedLatency =
    (kSpmvHbmLatencyMin + kSpmvHbmLatencyMax) / 2;
std::vector<std::uint8_t> gHbmMemory;
std::uint64_t gHbmReadCount = 0;
bool gInvalidHbmAddress = false;
std::uint64_t gFirstInvalidHbmAddress = 0;

bool spmvEnvFlag(const char* name) {
  const char* configured = std::getenv(name);
  return configured != nullptr && *configured != '\0' && std::strcmp(configured, "0") != 0;
}

#if SPMV_PIPELINE_HTML
struct PipelineHbmRead {
  std::uint64_t cycle = 0;
  bool sourceX = false;
  std::uint32_t beatIndex = 0;
};

std::vector<PipelineHbmRead> gPipelineHbmReads;
std::uint64_t gPipelineCycle = 0;
std::uint64_t gPipelineAAddress = 0;
std::uint64_t gPipelineXAddress = 0;
std::uint32_t gPipelineABeats = 0;
std::uint32_t gPipelineXBeats = 0;
bool gPipelineTraceActive = false;
#endif

struct Fp32Result {
  std::uint32_t bits = 0;
  std::uint32_t flags = 0;
};

Fp32Result multiplyFp32(std::uint32_t a, std::uint32_t x) {
  softfloat_roundingMode = softfloat_round_near_even;
  softfloat_detectTininess = softfloat_tininess_afterRounding;
  softfloat_exceptionFlags = 0;
  const float32_t result = f32_mul(float32_t{a}, float32_t{x});
  return Fp32Result{result.v, static_cast<std::uint32_t>(softfloat_exceptionFlags & 0x1f)};
}

std::uint32_t addFp32(std::uint32_t a, std::uint32_t b) {
  softfloat_roundingMode = softfloat_round_near_even;
  softfloat_detectTininess = softfloat_tininess_afterRounding;
  softfloat_exceptionFlags = 0;
  return f32_add(float32_t{a}, float32_t{b}).v;
}

}  // namespace

extern "C" void spmv_hbm_read512(std::uint64_t address, svBitVecVal* data, svBit* error) {
  std::fill(data, data + 16, 0U);
  *error = 0;
  const bool invalid = (address & 63ULL) != 0 || address < kHbmBase ||
      address > kHbmBase + kHbmBytes - 64ULL || gHbmMemory.size() != kHbmBytes;
  if (invalid) {
    *error = 1;
    if (!gInvalidHbmAddress) {
      gInvalidHbmAddress = true;
      gFirstInvalidHbmAddress = address;
    }
    return;
  }

  const std::size_t offset = static_cast<std::size_t>(address - kHbmBase);
  for (std::size_t word = 0; word < 16; ++word) {
    std::uint32_t value = 0;
    for (std::size_t byte = 0; byte < 4; ++byte) {
      value |= static_cast<std::uint32_t>(gHbmMemory[offset + word * 4 + byte]) << (byte * 8);
    }
    data[word] = value;
  }
  ++gHbmReadCount;
#if SPMV_PIPELINE_HTML
  if (gPipelineTraceActive) {
    const auto recordRead = [](std::uint64_t address, std::uint64_t base,
                                std::uint32_t beats, bool sourceX) {
      if (address < base) return;
      const std::uint64_t offset = address - base;
      if ((offset & 63ULL) != 0 || offset / 64ULL >= beats) return;
      gPipelineHbmReads.push_back(PipelineHbmRead{
          gPipelineCycle, sourceX, static_cast<std::uint32_t>(offset / 64ULL)});
    };
    recordRead(address, gPipelineAAddress, gPipelineABeats, false);
    recordRead(address, gPipelineXAddress, gPipelineXBeats, true);
  }
#endif
}

extern "C" svBit spmv_hbm_no_jitter() {
  return spmvEnvFlag("SPMV_HBM_NO_JITTER") ? 1 : 0;
}

extern "C" void spmv_f32_mul(std::uint32_t a_bits, std::uint32_t x_bits,
    std::uint32_t* result_bits, std::uint32_t* flags) {
  const Fp32Result result = multiplyFp32(a_bits, x_bits);
  *result_bits = result.bits;
  *flags = result.flags;
}

int runHbmDpiSelfTest() {
  gHbmMemory.resize(kHbmBytes);
  for (std::size_t byte = 0; byte < 64; ++byte) {
    gHbmMemory[byte] = static_cast<std::uint8_t>(byte);
  }
  std::array<svBitVecVal, 16> data{};
  svBit error = 0;
  spmv_hbm_read512(kHbmBase, data.data(), &error);
  if (error != 0) {
    throw std::runtime_error("aligned HBM DPI self-test read returned error");
  }
  for (std::size_t word = 0; word < data.size(); ++word) {
    const std::uint32_t expected = static_cast<std::uint32_t>(word * 4) |
        (static_cast<std::uint32_t>(word * 4 + 1) << 8) |
        (static_cast<std::uint32_t>(word * 4 + 2) << 16) |
        (static_cast<std::uint32_t>(word * 4 + 3) << 24);
    if (data[word] != expected) {
      throw std::runtime_error("HBM DPI svBitVecVal word order mismatch at word " +
          std::to_string(word));
    }
  }

  const std::array<std::uint64_t, 3> invalidAddresses = {
      kHbmBase + 1, kHbmBase - 64, kHbmBase + kHbmBytes};
  for (std::uint64_t address : invalidAddresses) {
    error = 0;
    data.fill(0xffffffffU);
    spmv_hbm_read512(address, data.data(), &error);
    if (error == 0 || !std::all_of(data.begin(), data.end(), [](svBitVecVal value) {
          return value == 0;
        })) {
      throw std::runtime_error("illegal HBM DPI address was not rejected");
    }
  }
  std::cout << "[spmv-hbm-dpi-test] 512-bit word order and address bounds PASS\n";
  return 0;
}
#endif

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

#ifndef SPMV_CSR5_VERILATOR
fs::path resolveGoldenDirectory() {
  if (const char* configured = std::getenv("SPMV_GOLDEN_DIR")) {
    if (*configured != '\0') {
      return fs::path(configured);
    }
  }
  return fs::current_path() / "build" / "golden";
}
#endif

void printChoices(const fs::path& dataRoot, const std::vector<DatasetChoice>& choices) {
  std::cout << "Usage: make -C accelerator-sim/spmv run mainargs=<scale>\n";
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
  if (matrix.rowPointers.front() != 0) {
    throw std::runtime_error("row_ptr[0] must be zero");
  }
  if (matrix.rowPointers.back() != columns.size() || columns.size() != matrix.values.size()) {
    throw std::runtime_error("CSR row pointer, column, and value counts disagree");
  }
  matrix.columnIndices.reserve(columns.size());
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    if (matrix.rowPointers[row] > matrix.rowPointers[row + 1]) {
      throw std::runtime_error("row_ptr must be nondecreasing");
    }
  }
  for (std::uint64_t column : columns) {
    if (column >= matrix.rows || column > std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error("column index exceeds square matrix bounds");
    }
    matrix.columnIndices.push_back(static_cast<std::uint32_t>(column));
  }
  return matrix;
}

#ifndef SPMV_CSR5_VERILATOR
int runGolden(const DatasetChoice& choice) {
  const auto loadStart = std::chrono::steady_clock::now();
  const CsrMatrix matrix = loadMatrix(choice);
  const std::vector<double> input = readArray<double>(choice.path / "b.txt");
  if (input.size() != matrix.rows) {
    throw std::runtime_error("b.txt length must equal matrix size");
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
  std::cout << "[spmv-host] rows=" << matrix.rows << " columns=" << matrix.rows
            << " nnz=" << matrix.values.size() << " load_ms=" << std::fixed
            << std::setprecision(3) << loadMilliseconds << " golden_ms=" << goldenMilliseconds << '\n';
  std::cout << std::setprecision(17) << "[golden] checksum=" << golden.checksum
            << " l1=" << golden.l1Norm << " max_abs=" << golden.maxAbs
            << " hash=0x" << std::hex << golden.bitHash << std::dec << '\n';
  std::cout << "[golden] output=" << outputPath << '\n';
  return 0;
}
#else

std::uint32_t floatBits(float value) {
  std::uint32_t bits = 0;
  static_assert(sizeof(bits) == sizeof(value));
  std::memcpy(&bits, &value, sizeof(bits));
  return bits;
}

constexpr std::size_t kBlockDimension = 8192;

#ifdef SPMV_CACHED_X
constexpr bool kCachedX = true;
#else
constexpr bool kCachedX = false;
#endif

struct BlockContext {
  std::uint16_t rowId = 0;
  std::uint16_t colId = 0;
  std::uint32_t rowBase = 0;
  std::uint32_t colBase = 0;
};

struct SourceRecord {
  std::uint64_t word = 0;
  std::uint32_t originalIndex = std::numeric_limits<std::uint32_t>::max();
  std::uint32_t localRow = 0;
  std::uint32_t localCol = 0;
  std::uint32_t x = 0;
  bool rowStart = false;
  bool rowEnd = false;
  std::uint32_t product = 0;
  std::uint32_t flags = 0;
};

struct ExpectedProductBeat {
  std::uint32_t globalTileId = 0;
  std::uint32_t blockTileId = 0;
  std::uint16_t blockRowId = 0;
  std::uint16_t blockColId = 0;
  std::uint32_t blockRowBase = 0;
  std::uint32_t step = 0;
  std::uint32_t aBeatIndex = 0;
  bool tileLast = false;
  std::array<SourceRecord, 8> lanes{};
};

struct PackedStream {
  std::vector<std::uint8_t> bytes;
  std::vector<ExpectedProductBeat> products;
  std::uint32_t packets = 0;
  std::uint64_t validProducts = 0;
};

struct PackedXStream {
  std::vector<std::uint8_t> bytes;
  std::uint32_t crc = 0;
};

#if SPMV_PIPELINE_HTML
// 流水线报告只覆盖 RTL，从 HBM 响应到 ProductBeat 握手；host 归约不属于硬件周期。
constexpr std::size_t kSpmvPipelineStageCount = 9;
constexpr std::size_t kSpmvStageHbm = 0;
constexpr std::size_t kSpmvStageDecode = 1;
constexpr std::size_t kSpmvStageJoin = 2;
constexpr std::size_t kSpmvStageMulS0 = 3;
constexpr std::size_t kSpmvStageMulS1 = 4;
constexpr std::size_t kSpmvStageMulS2 = 5;
constexpr std::size_t kSpmvStageMulS3 = 6;
constexpr std::size_t kSpmvStageProductFifo = 7;
constexpr std::size_t kSpmvStageOutput = 8;
constexpr std::uint64_t kSpmvNoCycle = std::numeric_limits<std::uint64_t>::max();

struct SpmvPipelineRecord {
  std::uint64_t sequence = 0;
  std::uint64_t outputCycle = 0;
  std::uint64_t aCycle = kSpmvNoCycle;
  std::uint64_t xCycle = kSpmvNoCycle;
  std::uint32_t globalTileId = 0;
  std::uint32_t blockTileId = 0;
  std::uint16_t blockRowId = 0;
  std::uint16_t blockColId = 0;
  std::uint32_t step = 0;
  std::uint32_t aBeatIndex = 0;
  std::uint32_t xBeatIndex = 0;
  std::uint32_t validCount = 0;
  std::array<std::uint32_t, 8> originalIndex{};
  std::array<std::uint32_t, 8> localRow{};
  std::array<std::uint32_t, 8> localCol{};
  std::array<bool, 8> valid{};
  std::array<std::uint64_t, kSpmvPipelineStageCount> starts{};
  std::array<std::uint64_t, kSpmvPipelineStageCount> durations{};
};

std::vector<SpmvPipelineRecord> gPipelineReportRecords;

SpmvPipelineRecord makePipelineRecord(const ExpectedProductBeat& expected,
                                      std::uint64_t sequence,
                                      std::uint64_t outputCycle,
                                      bool cachedX,
                                      std::size_t productIndex) {
  SpmvPipelineRecord record;
  record.sequence = sequence;
  record.outputCycle = outputCycle;
  record.globalTileId = expected.globalTileId;
  record.blockTileId = expected.blockTileId;
  record.blockRowId = expected.blockRowId;
  record.blockColId = expected.blockColId;
  record.step = expected.step;
  record.aBeatIndex = expected.aBeatIndex;
  record.xBeatIndex = static_cast<std::uint32_t>(productIndex / 2);
  bool xBeatSelected = false;
  for (std::size_t lane = 0; lane < expected.lanes.size(); ++lane) {
    const SourceRecord& source = expected.lanes[lane];
    record.valid[lane] = (source.word >> 63) != 0;
    record.originalIndex[lane] = source.originalIndex;
    record.localRow[lane] = source.localRow;
    record.localCol[lane] = source.localCol;
    if (record.valid[lane]) {
      ++record.validCount;
      if (cachedX && !xBeatSelected) {
        record.xBeatIndex = source.localCol / 16;
        xBeatSelected = true;
      }
    }
  }
  return record;
}

void assignPipelineStages(SpmvPipelineRecord& record, std::uint64_t aCycle,
                          std::uint64_t xCycle) {
  record.aCycle = aCycle;
  record.xCycle = xCycle;
  const std::uint64_t fallback = record.outputCycle;
  const std::uint64_t observedA = aCycle == kSpmvNoCycle ? fallback : aCycle;
  const std::uint64_t observedX = xCycle == kSpmvNoCycle ? fallback : xCycle;
  const std::uint64_t hbmStart = std::min(observedA, observedX);
  const std::uint64_t hbmEnd = std::max(observedA, observedX);
  record.starts[kSpmvStageHbm] = hbmStart;
  record.durations[kSpmvStageHbm] = hbmEnd - hbmStart + 1;

  // HBM 与 ProductBeat 握手可由 host 观测，其余 RTL 阶段按冻结的固定延迟展开。
  const std::uint64_t decodeStart = hbmEnd + 1;
  record.starts[kSpmvStageDecode] = decodeStart;
  record.durations[kSpmvStageDecode] = 1;
  const std::uint64_t joinStart = decodeStart + 1;
  record.starts[kSpmvStageJoin] = joinStart;
  record.durations[kSpmvStageJoin] = 1;

  std::uint64_t mulStart = joinStart + 1;
  if (record.outputCycle >= 4) {
    mulStart = std::max(mulStart, record.outputCycle - 4);
  }
  for (std::size_t stage = kSpmvStageMulS0; stage <= kSpmvStageMulS3; ++stage) {
    record.starts[stage] = mulStart + (stage - kSpmvStageMulS0);
    record.durations[stage] = 1;
  }
  const std::uint64_t fifoStart = record.starts[kSpmvStageMulS3] + 1;
  record.starts[kSpmvStageProductFifo] = fifoStart;
  record.durations[kSpmvStageProductFifo] = record.outputCycle >= fifoStart
      ? std::max<std::uint64_t>(1, record.outputCycle - fifoStart) : 1;
  record.starts[kSpmvStageOutput] = record.outputCycle;
  record.durations[kSpmvStageOutput] = 1;
}
#endif

struct BlockRecords {
  BlockContext context;
  std::vector<SourceRecord> records;
};

using BlockKey = std::pair<std::uint16_t, std::uint16_t>;
using BlockMap = std::map<BlockKey, BlockRecords>;

void putLe16(std::array<std::uint8_t, 64>& bytes, std::size_t offset, std::uint16_t value) {
  bytes[offset] = static_cast<std::uint8_t>(value);
  bytes[offset + 1] = static_cast<std::uint8_t>(value >> 8);
}

void putLe32(std::array<std::uint8_t, 64>& bytes, std::size_t offset, std::uint32_t value) {
  for (unsigned byte = 0; byte < 4; ++byte) {
    bytes[offset + byte] = static_cast<std::uint8_t>(value >> (byte * 8));
  }
}

std::array<std::uint8_t, 64> payloadBytes(const std::array<SourceRecord, 8>& records) {
  std::array<std::uint8_t, 64> bytes{};
  for (std::size_t lane = 0; lane < records.size(); ++lane) {
    for (unsigned byte = 0; byte < 8; ++byte) {
      bytes[lane * 8 + byte] = static_cast<std::uint8_t>(records[lane].word >> (byte * 8));
    }
  }
  return bytes;
}

std::uint32_t crc32(const std::vector<std::array<std::uint8_t, 64>>& payload) {
  std::uint32_t crc = 0xffffffffU;
  for (const auto& beat : payload) {
    for (std::uint8_t byte : beat) {
      crc ^= byte;
      for (unsigned bit = 0; bit < 8; ++bit) {
        crc = (crc >> 1) ^ ((crc & 1U) ? 0xedb88320U : 0U);
      }
    }
  }
  return crc ^ 0xffffffffU;
}

std::uint32_t crc32(const std::vector<std::uint8_t>& bytes) {
  if (bytes.empty() || bytes.size() % 64 != 0) {
    throw std::runtime_error("X stream must contain complete nonempty HBM beats");
  }
  std::uint32_t crc = 0xffffffffU;
  for (std::uint8_t byte : bytes) {
    crc ^= byte;
    for (unsigned bit = 0; bit < 8; ++bit) {
      crc = (crc >> 1) ^ ((crc & 1U) ? 0xedb88320U : 0U);
    }
  }
  return crc ^ 0xffffffffU;
}

std::uint32_t laneSummary(const std::vector<std::array<SourceRecord, 8>>& payload,
                          std::size_t lane) {
  std::vector<const SourceRecord*> valid;
  for (const auto& beat : payload) {
    if ((beat[lane].word >> 63) != 0) {
      valid.push_back(&beat[lane]);
    }
  }
  if (valid.empty()) {
    return 0;
  }
  std::uint32_t segments = 1;
  for (std::size_t index = 1; index < valid.size(); ++index) {
    if (valid[index - 1]->localRow != valid[index]->localRow) {
      ++segments;
    }
  }
  return (1U << 31) | (static_cast<std::uint32_t>(!valid.front()->rowStart) << 30) |
      (static_cast<std::uint32_t>(!valid.back()->rowEnd) << 29) | (segments << 24) |
      (static_cast<std::uint32_t>(valid.size()) << 16) | valid.front()->localRow;
}

void appendBeat(PackedStream& stream, const std::array<std::uint8_t, 64>& beat) {
  stream.bytes.insert(stream.bytes.end(), beat.begin(), beat.end());
}

BlockMap partitionMatrix(const CsrMatrix& matrix, const std::vector<std::uint32_t>& aBits,
                         const std::vector<std::uint32_t>& xBits) {
  BlockMap blocks;
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    for (std::uint64_t offset = matrix.rowPointers[row]; offset < matrix.rowPointers[row + 1];
         ++offset) {
      const std::size_t index = static_cast<std::size_t>(offset);
      const std::uint32_t column = matrix.columnIndices[index];
      const std::size_t rowId = row / kBlockDimension;
      const std::size_t colId = column / kBlockDimension;
      const BlockKey key{static_cast<std::uint16_t>(colId), static_cast<std::uint16_t>(rowId)};
      auto [iterator, inserted] = blocks.try_emplace(key);
      BlockRecords& block = iterator->second;
      if (inserted) {
        block.context = BlockContext{static_cast<std::uint16_t>(rowId),
            static_cast<std::uint16_t>(colId),
            static_cast<std::uint32_t>(rowId * kBlockDimension),
            static_cast<std::uint32_t>(colId * kBlockDimension)};
      }

      const std::uint32_t localRow = static_cast<std::uint32_t>(row % kBlockDimension);
      const std::uint32_t localCol = column % kBlockDimension;
      const bool rowStart = offset == matrix.rowPointers[row];
      const bool rowEnd = offset + 1 == matrix.rowPointers[row + 1];
      const std::uint32_t coord = (1U << 31) |
          (static_cast<std::uint32_t>(rowStart) << 30) |
          (static_cast<std::uint32_t>(rowEnd) << 29) | (localRow << 16) | localCol;
      const Fp32Result product = multiplyFp32(aBits[index], xBits[column]);
      block.records.push_back(SourceRecord{
          (static_cast<std::uint64_t>(coord) << 32) | aBits[index],
          static_cast<std::uint32_t>(index), localRow, localCol, xBits[column], rowStart, rowEnd,
          product.bits, product.flags});
    }
  }
  return blocks;
}

void appendCsr5Block(PackedStream& stream, const BlockRecords& block,
                     std::uint64_t& nextGlobalTileId) {
  const std::vector<SourceRecord>& records = block.records;
  std::uint64_t blockTileId = 0;
  for (std::size_t base = 0; base < records.size(); base += 128) {
    const std::size_t count = std::min<std::size_t>(128, records.size() - base);
    const bool full = count == 128;
    const std::size_t payloadBeats = full ? 16 : (count + 7) / 8;
    std::vector<std::array<SourceRecord, 8>> payload(payloadBeats);
    for (std::size_t step = 0; step < payloadBeats; ++step) {
      for (std::size_t lane = 0; lane < 8; ++lane) {
        const std::size_t source = full ? lane * 16 + step : step * 8 + lane;
        if (source < count) {
          payload[step][lane] = records[base + source];
        }
      }
    }

    std::vector<std::array<std::uint8_t, 64>> payloadRaw;
    payloadRaw.reserve(payload.size());
    for (const auto& beat : payload) {
      payloadRaw.push_back(payloadBytes(beat));
    }
    std::array<std::uint8_t, 64> metadata{};
    for (std::size_t lane = 0; lane < 8; ++lane) {
      putLe32(metadata, lane * 4, laneSummary(payload, lane));
    }
    metadata[32] = 2;
    metadata[33] = 0;
    metadata[34] = static_cast<std::uint8_t>(full ? 0x5 : 0x2);
    metadata[35] = static_cast<std::uint8_t>(payloadBeats);
    putLe16(metadata, 36, static_cast<std::uint16_t>(count));
    putLe16(metadata, 38, block.context.rowId);
    putLe16(metadata, 40, block.context.colId);
    if (nextGlobalTileId > std::numeric_limits<std::uint32_t>::max() ||
        blockTileId > std::numeric_limits<std::uint32_t>::max() ||
        stream.packets == std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error("CSR5 tile identifier exceeds the Metadata v2 field width");
    }
    putLe32(metadata, 42, static_cast<std::uint32_t>(nextGlobalTileId));
    putLe32(metadata, 46, static_cast<std::uint32_t>(blockTileId));
    putLe32(metadata, 50, block.context.rowBase);
    putLe32(metadata, 54, block.context.colBase);
    putLe32(metadata, 58, crc32(payloadRaw));
    appendBeat(stream, metadata);

    for (std::size_t step = 0; step < payload.size(); ++step) {
      appendBeat(stream, payloadRaw[step]);
      ExpectedProductBeat expected;
      expected.globalTileId = static_cast<std::uint32_t>(nextGlobalTileId);
      expected.blockTileId = static_cast<std::uint32_t>(blockTileId);
      expected.blockRowId = block.context.rowId;
      expected.blockColId = block.context.colId;
      expected.blockRowBase = block.context.rowBase;
      expected.step = static_cast<std::uint32_t>(step);
      expected.aBeatIndex = static_cast<std::uint32_t>(stream.bytes.size() / 64 - 1);
      expected.tileLast = step + 1 == payload.size();
      expected.lanes = payload[step];
      stream.products.push_back(expected);
    }
    ++stream.packets;
    stream.validProducts += count;
    ++nextGlobalTileId;
    ++blockTileId;
  }
}

PackedXStream packPairedX(const std::vector<ExpectedProductBeat>& products) {
  PackedXStream stream;
  stream.bytes.resize(((products.size() + 1) / 2) * 64, 0);
  for (std::size_t group = 0; group < products.size(); ++group) {
    const std::size_t beatOffset = (group / 2) * 64;
    const std::size_t groupOffset = (group % 2) * 32;
    for (std::size_t lane = 0; lane < 8; ++lane) {
      const SourceRecord& source = products[group].lanes[lane];
      const std::uint32_t value = (source.word >> 63) != 0 ? source.x : 0U;
      for (unsigned byte = 0; byte < 4; ++byte) {
        stream.bytes[beatOffset + groupOffset + lane * 4 + byte] =
            static_cast<std::uint8_t>(value >> (byte * 8));
      }
    }
  }
  stream.crc = crc32(stream.bytes);
  return stream;
}

PackedXStream packCachedX(const std::vector<std::uint32_t>& xBits,
                          std::size_t base, std::size_t count) {
  if (count == 0 || base > xBits.size() || count > xBits.size() - base) {
    throw std::runtime_error("cached X slice lies outside b.txt");
  }
  PackedXStream stream;
  stream.bytes.resize(((count + 15) / 16) * 64, 0);
  for (std::size_t index = 0; index < count; ++index) {
    const std::uint32_t value = xBits[base + index];
    for (unsigned byte = 0; byte < 4; ++byte) {
      stream.bytes[index * 4 + byte] = static_cast<std::uint8_t>(value >> (byte * 8));
    }
  }
  stream.crc = crc32(stream.bytes);
  return stream;
}

#define SPMV_LANE_ACCESSOR(NAME, FIELD, TYPE)                                      \
  TYPE NAME(const VSpmvOneHbmCsr5MulSimulationTop& dut, unsigned lane) {           \
    switch (lane) {                                                                \
      case 0: return dut.io_product_bits_lanes_0_##FIELD;                           \
      case 1: return dut.io_product_bits_lanes_1_##FIELD;                           \
      case 2: return dut.io_product_bits_lanes_2_##FIELD;                           \
      case 3: return dut.io_product_bits_lanes_3_##FIELD;                           \
      case 4: return dut.io_product_bits_lanes_4_##FIELD;                           \
      case 5: return dut.io_product_bits_lanes_5_##FIELD;                           \
      case 6: return dut.io_product_bits_lanes_6_##FIELD;                           \
      default: return dut.io_product_bits_lanes_7_##FIELD;                          \
    }                                                                               \
  }

SPMV_LANE_ACCESSOR(productValid, valid, bool)
SPMV_LANE_ACCESSOR(productRowStart, rowStart, bool)
SPMV_LANE_ACCESSOR(productRowEnd, rowEnd, bool)
SPMV_LANE_ACCESSOR(productLocalRow, localRow, std::uint32_t)
SPMV_LANE_ACCESSOR(productBits, product, std::uint32_t)
SPMV_LANE_ACCESSOR(productFlags, exceptionFlags, std::uint32_t)
#undef SPMV_LANE_ACCESSOR

void verifyProductBeat(const VSpmvOneHbmCsr5MulSimulationTop& dut,
                       const ExpectedProductBeat& expected, std::size_t beatIndex,
                       std::vector<std::uint32_t>& products,
                       std::vector<bool>& productSeen) {
  auto mismatch = [beatIndex](const std::string& field, std::uint64_t actual,
                              std::uint64_t wanted) {
    throw std::runtime_error("product beat " + std::to_string(beatIndex) + " " + field +
        " mismatch: actual=" + std::to_string(actual) + " expected=" +
        std::to_string(wanted));
  };
  if (dut.io_product_bits_globalTileId != expected.globalTileId) {
    mismatch("global_tile_id", dut.io_product_bits_globalTileId, expected.globalTileId);
  }
  if (dut.io_product_bits_blockTileId != expected.blockTileId) {
    mismatch("block_tile_id", dut.io_product_bits_blockTileId, expected.blockTileId);
  }
  if (dut.io_product_bits_blockRowId != expected.blockRowId) {
    mismatch("block_row_id", dut.io_product_bits_blockRowId, expected.blockRowId);
  }
  if (dut.io_product_bits_blockColId != expected.blockColId) {
    mismatch("block_col_id", dut.io_product_bits_blockColId, expected.blockColId);
  }
  if (dut.io_product_bits_blockRowBase != expected.blockRowBase) {
    mismatch("block_row_base", dut.io_product_bits_blockRowBase, expected.blockRowBase);
  }
  if (dut.io_product_bits_step != expected.step) {
    mismatch("step", dut.io_product_bits_step, expected.step);
  }
  if (static_cast<bool>(dut.io_product_bits_tileLast) != expected.tileLast) {
    mismatch("tile_last", dut.io_product_bits_tileLast, expected.tileLast);
  }
  for (unsigned lane = 0; lane < 8; ++lane) {
    const SourceRecord& wanted = expected.lanes[lane];
    const bool wantedValid = (wanted.word >> 63) != 0;
    if (productValid(dut, lane) != wantedValid) {
      mismatch("lane_valid[" + std::to_string(lane) + "]", productValid(dut, lane), wantedValid);
    }
    if (!wantedValid) {
      continue;
    }
    if (productRowStart(dut, lane) != wanted.rowStart ||
        productRowEnd(dut, lane) != wanted.rowEnd ||
        productLocalRow(dut, lane) != wanted.localRow) {
      mismatch("lane_sideband[" + std::to_string(lane) + "]", productLocalRow(dut, lane),
          wanted.localRow);
    }
    if (productBits(dut, lane) != wanted.product) {
      mismatch("lane_product[" + std::to_string(lane) + "]", productBits(dut, lane),
          wanted.product);
    }
    if (productFlags(dut, lane) != wanted.flags) {
      mismatch("lane_flags[" + std::to_string(lane) + "]", productFlags(dut, lane),
          wanted.flags);
    }
    if (wanted.originalIndex >= products.size() || productSeen[wanted.originalIndex]) {
      throw std::runtime_error("product stream duplicated or corrupted an original NNZ index");
    }
    products[wanted.originalIndex] = productBits(dut, lane);
    productSeen[wanted.originalIndex] = true;
  }
}

std::uint64_t hashRows(const std::vector<std::uint32_t>& rows) {
  std::uint64_t hash = 1469598103934665603ULL;
  for (std::uint32_t value : rows) {
    for (unsigned byte = 0; byte < 4; ++byte) {
      hash ^= (value >> (byte * 8)) & 0xffU;
      hash *= 1099511628211ULL;
    }
  }
  return hash;
}

#if SPMV_PERFORMANCE_HTML || SPMV_PIPELINE_HTML
#if SPMV_PERFORMANCE_HTML
std::string htmlEscape(const std::string& value) {
  std::string escaped;
  escaped.reserve(value.size());
  for (const char character : value) {
    switch (character) {
      case '&': escaped += "&amp;"; break;
      case '<': escaped += "&lt;"; break;
      case '>': escaped += "&gt;"; break;
      case '"': escaped += "&quot;"; break;
      case '\'': escaped += "&#39;"; break;
      default: escaped += character; break;
    }
  }
  return escaped;
}
#endif

fs::path resolveReportDirectory() {
  if (const char* configured = std::getenv("SPMV_REPORT_DIR")) {
    if (*configured != '\0') {
      return fs::path(configured);
    }
  }
  return fs::current_path();
}

#if SPMV_PERFORMANCE_HTML
void writeHtmlReport(const fs::path& path, const std::string& title,
                     const std::string& body) {
  std::error_code error;
  fs::create_directories(path.parent_path(), error);
  if (error) {
    throw std::runtime_error("failed to create SPMV report directory " +
        path.parent_path().string() + ": " + error.message());
  }
  const fs::path temporary = path.string() + ".tmp";
  std::ofstream stream(temporary);
  if (!stream) {
    throw std::runtime_error("failed to open SPMV report " + temporary.string());
  }
  stream << "<!doctype html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">\n"
          "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
          "<title>" << htmlEscape(title) << "</title>\n"
          "<style>body{font:14px system-ui,sans-serif;margin:2rem;color:#17202a}"
          "main{max-width:1100px;margin:auto}h1{font-size:1.8rem}h2{margin-top:2rem}"
          "table{border-collapse:collapse;width:100%;margin:1rem 0}th,td{border:1px solid #ccd3da;"
          "padding:.45rem .6rem;text-align:left}th{background:#edf1f4}.stage{display:grid;"
          "grid-template-columns:repeat(6,minmax(120px,1fr));gap:.5rem;align-items:stretch}"
          ".stage div{border:1px solid #8ca0b3;padding:.8rem;background:#f7f9fb}"
          ".note{color:#586775;font-size:.92rem}</style></head><body><main>\n"
       << body << "\n</main></body></html>\n";
  stream.close();
  if (!stream) {
    throw std::runtime_error("failed to write SPMV report " + temporary.string());
  }
  std::filesystem::rename(temporary, path, error);
  if (error) {
    std::filesystem::remove(temporary);
    throw std::runtime_error("failed to publish SPMV report " + path.string() + ": " +
        error.message());
  }
}
#endif

#if SPMV_PERFORMANCE_HTML
void writePerformanceReports(const std::string& scale, std::size_t rows, std::size_t nnz,
                             bool cachedX, bool hbmNoJitter, std::uint32_t outstanding,
                             std::uint64_t cycles,
                             std::uint64_t aBeats, std::uint64_t xBeats,
                             std::uint64_t hbmBursts, double pcUtilization,
                             std::uint64_t joinWaitA, std::uint64_t joinWaitX,
                             std::uint64_t cacheLoadCycles, std::uint64_t firstProductLatency,
                             std::uint64_t packets, std::uint64_t productBeats,
                             std::uint64_t products, std::uint64_t stalls,
                             std::uint32_t fpFlags, std::uint64_t resultHash) {
  const fs::path directory = resolveReportDirectory();
  const fs::path performancePath = directory / "performance.html";
  std::ostringstream metrics;
  metrics << "<h1>SPMV CSR5 性能报告</h1>\n"
          << "<p class=\"note\">性能计数只覆盖 RTL：HBM、A/X join、乘法流水线、Product FIFO 与 ProductBeat 握手。host 侧行归约不计入硬件周期。</p>\n"
          << "<table><thead><tr><th>Metric</th><th>Value</th></tr></thead><tbody>"
          << "<tr><td>Scale</td><td>" << htmlEscape(scale) << "</td></tr>"
          << "<tr><td>Rows / NNZ</td><td>" << rows << " / " << nnz << "</td></tr>"
          << "<tr><td>X mode</td><td>" << (cachedX ? "cached" : "paired") << "</td></tr>"
          << "<tr><td>HBM timing</td><td>"
          << (hbmNoJitter ? "fixed first beat (77 cycles)" : "jittered first beat (73-81 cycles)")
          << "</td></tr>"
          << "<tr><td>Outstanding limit</td><td>" << outstanding << "</td></tr>"
          << "<tr><td>RTL cycles</td><td>" << cycles << "</td></tr>"
          << "<tr><td>A / X HBM beats</td><td>" << aBeats << " / " << xBeats << "</td></tr>"
          << "<tr><td>HBM bursts</td><td>" << hbmBursts << "</td></tr>"
          << "<tr><td>PC data utilization</td><td>" << std::fixed << std::setprecision(6)
          << pcUtilization << "</td></tr>"
          << "<tr><td>Join wait A / X</td><td>" << joinWaitA << " / " << joinWaitX << "</td></tr>"
          << "<tr><td>Cache load cycles</td><td>" << cacheLoadCycles << "</td></tr>"
          << "<tr><td>First product latency</td><td>" << firstProductLatency << "</td></tr>"
          << "<tr><td>Packets / ProductBeats / products</td><td>" << packets << " / "
          << productBeats << " / " << products << "</td></tr>"
          << "<tr><td>Output stalls</td><td>" << stalls << "</td></tr>"
          << "<tr><td>FP exception flags</td><td>0x" << std::hex << fpFlags << std::dec << "</td></tr>"
          << "<tr><td>Result hash</td><td>0x" << std::hex << resultHash << std::dec << "</td></tr>"
          << "</tbody></table>";
#if SPMV_PIPELINE_HTML
  metrics << "<p><a href=\"pipeline.html\">Open multiply-add pipeline report</a></p>";
#endif
  writeHtmlReport(performancePath, "SPMV CSR5 性能报告", metrics.str());

  std::cout << "[spmv-csr5] performance_html=" << performancePath << '\n';
}
#endif

#if SPMV_PIPELINE_HTML
void writeJsonString(std::ostream& output, const std::string& value) {
  output << '"';
  for (const unsigned char character : value) {
    switch (character) {
      case '"': output << "\\\""; break;
      case '\\': output << "\\\\"; break;
      case '\b': output << "\\b"; break;
      case '\f': output << "\\f"; break;
      case '\n': output << "\\n"; break;
      case '\r': output << "\\r"; break;
      case '\t': output << "\\t"; break;
      default:
        if (character < 0x20) {
          output << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                 << static_cast<unsigned>(character) << std::dec << std::setfill(' ');
        } else {
          output << character;
        }
        break;
    }
  }
  output << '"';
}

void writeDetailedPipelineHtml(const fs::path& path, const std::string& scale,
                               bool cachedX, bool hbmNoJitter, std::uint32_t outstanding,
                               std::uint64_t cycles, std::uint64_t aBeats,
                               std::uint64_t xBeats, std::uint64_t productBeats,
                               std::uint64_t products, std::uint64_t stalls) {
  std::error_code error;
  fs::create_directories(path.parent_path(), error);
  if (error) {
    throw std::runtime_error("failed to create SPMV pipeline directory " +
        path.parent_path().string() + ": " + error.message());
  }
  const fs::path temporary = path.string() + ".tmp";
  std::ofstream stream(temporary);
  if (!stream) {
    throw std::runtime_error("failed to open SPMV pipeline report " + temporary.string());
  }

  stream << R"HTML(<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SPMV CSR5 流水线时间线</title><style>
:root{color-scheme:light;--bg:#f6f7f9;--ink:#17202a;--muted:#65717e;--line:#d7dce2;--col-seq:64px;--col-tile:190px;--col-step:120px;--col-out:130px;--timeline-width:720px;--hbm:#16697a;--decode:#8f5d18;--join:#8c2f39;--s0:#386641;--s1:#4f772d;--s2:#5a3d8a;--s3:#7b2cbf;--fifo:#b25d19;--output:#155f78;--unobserved:#7a8794}*{box-sizing:border-box}
body{height:100vh;margin:0;overflow:hidden;display:flex;flex-direction:column;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,sans-serif}
header{padding:20px 24px 14px;background:#fff;border-bottom:1px solid var(--line)}.titlebar{display:flex;justify-content:space-between;gap:12px;align-items:center}h1{font-size:22px;margin:0 0 6px}.home{color:#155f78;text-decoration:none;border:1px solid #82919f;border-radius:4px;padding:6px 9px;white-space:nowrap}.summary,.legend,.controls{display:flex;gap:14px;flex-wrap:wrap;align-items:center}.summary{color:var(--muted)}.note{margin-top:4px;color:var(--muted);font-size:13px}.warning{margin-top:10px;padding:8px 10px;border-left:4px solid #b42318;background:#fff1f0;color:#7a271a}
.legend{padding:10px 24px;background:#fff;border-bottom:1px solid var(--line)}.key:before{content:'';display:inline-block;width:12px;height:12px;margin-right:5px;background:var(--c);vertical-align:-1px}.controls{padding:12px 24px}.controls input[type=search]{min-width:280px;padding:7px 9px;border:1px solid #aeb6bf;border-radius:4px}button,select,input{font:inherit}button{padding:6px 10px;border:1px solid #9aa4af;border-radius:4px;background:#fff;cursor:pointer}button:disabled{opacity:.45}
.viewport{flex:1;min-height:0;overflow:auto;scrollbar-gutter:stable;scrollbar-color:#788592 #e4e8ec;border-top:1px solid var(--line);border-bottom:1px solid var(--line);background:#fff;overscroll-behavior:contain}.viewport::-webkit-scrollbar{width:14px;height:14px}.viewport::-webkit-scrollbar-track{background:#e4e8ec}.viewport::-webkit-scrollbar-thumb{background:#788592;border:3px solid #e4e8ec;border-radius:7px}
.head,.row{display:grid;grid-template-columns:var(--col-seq) var(--col-tile) var(--col-step) var(--col-out) var(--timeline-width);width:calc(var(--col-seq) + var(--col-tile) + var(--col-step) + var(--col-out) + var(--timeline-width))}.head{position:sticky;top:0;z-index:3;background:#eef1f4;font-weight:650}.head>div,.meta{padding:7px 8px;border-right:1px solid var(--line)}.row{min-height:42px;border-top:1px solid #eceff2}.row:hover{background:#f8fbff}.meta{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;align-content:center}.head>div:nth-child(-n+4),.row>.meta:nth-child(-n+4){position:sticky;z-index:2;background:#fff}.head>div:nth-child(-n+4){z-index:4;background:#eef1f4}.row:hover>.meta:nth-child(-n+4){background:#f8fbff}.head>div:nth-child(1),.row>.meta:nth-child(1){left:0}.head>div:nth-child(2),.row>.meta:nth-child(2){left:var(--col-seq)}.head>div:nth-child(3),.row>.meta:nth-child(3){left:calc(var(--col-seq) + var(--col-tile))}.head>div:nth-child(4),.row>.meta:nth-child(4){left:calc(var(--col-seq) + var(--col-tile) + var(--col-step));box-shadow:5px 0 7px -6px #59636e}.timeline{position:relative;min-height:41px;background-image:linear-gradient(to right,rgba(70,80,90,.12) 1px,transparent 1px);background-size:var(--cell) 100%}.stage{position:absolute;top:6px;height:29px;color:#fff;padding:4px 5px;overflow:hidden;white-space:nowrap;font-size:12px;background:var(--c);cursor:pointer;user-select:none}.stage.unobserved{background:var(--unobserved);border:1px dashed rgba(255,255,255,.7);padding:3px 4px}.stage:hover,.stage:focus-visible{filter:brightness(1.12);outline:2px solid #1b4f62;outline-offset:-1px;z-index:2}.axis{color:var(--muted);font-size:12px}.footer{padding:12px 24px;display:flex;gap:12px;align-items:center}.empty{padding:30px;color:var(--muted)}
.modal[hidden]{display:none}.modal{position:fixed;inset:0;z-index:20;display:flex;align-items:center;justify-content:center;padding:20px;background:rgba(23,32,42,.42)}.modal-card{width:min(760px,calc(100vw - 32px));max-height:calc(100vh - 40px);overflow:auto;background:#fff;border:1px solid var(--line);border-radius:6px;box-shadow:0 18px 48px rgba(23,32,42,.28)}.modal-header{display:flex;justify-content:space-between;gap:12px;align-items:center;padding:14px 16px;border-bottom:1px solid var(--line);background:#f8fafb}.modal-header h2{margin:0;font-size:17px}.modal-close{padding:5px 9px;border:1px solid #9aa4af;border-radius:4px;background:#fff;color:var(--ink);cursor:pointer}.modal-body{padding:14px 16px}.detail-table{width:100%;border-collapse:collapse;table-layout:fixed}.detail-table th,.detail-table td{padding:7px 8px;border-bottom:1px solid #e7ebef;text-align:left;vertical-align:top;overflow-wrap:anywhere}.detail-table th{width:160px;color:var(--muted);font-weight:600;background:#f8fafb}.detail-heading{margin:16px 0 8px;font-size:14px}
@media(max-width:700px){:root{--col-seq:48px;--col-tile:150px;--col-step:100px;--col-out:110px}header,.legend,.controls,.footer{padding-left:12px;padding-right:12px}.controls input[type=search]{min-width:100%;width:100%}}@media(max-width:520px){:root{--col-seq:38px;--col-tile:118px;--col-step:84px;--col-out:96px}.head>div,.meta{padding-left:5px;padding-right:5px}}
</style></head><body><header><div class="titlebar"><h1>SPMV CSR5 RTL 流水线时间线</h1><a class="home" href="performance.html">返回性能主页</a></div><div class="note">本页只展示 RTL：HBM beat 周期来自 DPI 握手观测；CSR5 decode、X join/unpack 与 MUL S0-S3 按冻结 RTL 延迟推导；host 侧 FP32 行归约完全排除。</div><div class="summary" id="summary"></div><div class="warning" id="warning" hidden></div></header>
<div class="legend"><span class="key" style="--c:var(--hbm)">HBM beat</span><span class="key" style="--c:var(--decode)">CSR5 decode</span><span class="key" style="--c:var(--join)">X join / unpack</span><span class="key" style="--c:var(--s0)">MUL S0</span><span class="key" style="--c:var(--s1)">MUL S1</span><span class="key" style="--c:var(--s2)">MUL S2</span><span class="key" style="--c:var(--s3)">MUL S3</span><span class="key" style="--c:var(--fifo)">Product FIFO</span><span class="key" style="--c:var(--output)">Product handshake</span><span class="key" style="--c:var(--unobserved)">UNOBSERVED / 未观测</span></div>
<div class="controls"><input id="search" type="search" placeholder="搜索 tile、block、step 或 lane index"><label>每页 <select id="pageSize"><option>50</option><option selected>100</option><option>250</option><option>500</option></select></label><label>周期宽度 <input id="zoom" type="range" min="4" max="28" value="12"></label></div>
<div class="modal" id="detailModal" hidden role="dialog" aria-modal="true" aria-labelledby="detailTitle"><div class="modal-card"><div class="modal-header"><h2 id="detailTitle">周期详情</h2><button class="modal-close" id="detailClose" type="button">关闭</button></div><div class="modal-body" id="detailBody"></div></div></div>
<div class="viewport"><div class="head"><div>#</div><div>tile / block</div><div>step / lanes</div><div>output cycle</div><div class="axis">周期时间线</div></div><div id="rows"></div></div><div class="footer"><button id="prev">上一页</button><span id="page"></span><button id="next">下一页</button></div><script>const trace=)HTML";
  stream << "{\"scale\":";
  writeJsonString(stream, scale);
  stream << ",\"mode\":";
  writeJsonString(stream, cachedX ? "cached" : "paired");
  stream << ",\"hbmTiming\":";
  writeJsonString(stream, hbmNoJitter ? "fixed-77" : "jitter-73-81");
  stream << ",\"outstanding\":" << outstanding << ",\"cycles\":" << cycles
          << ",\"aBeats\":" << aBeats << ",\"xBeats\":" << xBeats
          << ",\"productBeats\":" << productBeats << ",\"products\":" << products
          << ",\"stalls\":" << stalls << ",\"captured\":" << gPipelineReportRecords.size()
          << ",\"records\":[";
  for (std::size_t index = 0; index < gPipelineReportRecords.size(); ++index) {
    const SpmvPipelineRecord& record = gPipelineReportRecords[index];
    if (index != 0) stream << ',';
    stream << "{\"n\":" << record.sequence
           << ",\"tile\":" << record.globalTileId
           << ",\"blockTile\":" << record.blockTileId
           << ",\"rowBlock\":" << record.blockRowId
           << ",\"colBlock\":" << record.blockColId
           << ",\"step\":" << record.step
           << ",\"out\":" << record.outputCycle
           << ",\"aBeat\":" << record.aBeatIndex
           << ",\"xBeat\":" << record.xBeatIndex
           << ",\"aCycle\":";
    if (record.aCycle == kSpmvNoCycle) stream << "null"; else stream << record.aCycle;
    stream << ",\"xCycle\":";
    if (record.xCycle == kSpmvNoCycle) stream << "null"; else stream << record.xCycle;
    stream << ",\"valid\":[";
    for (std::size_t lane = 0; lane < record.valid.size(); ++lane)
      stream << (lane == 0 ? "" : ",") << (record.valid[lane] ? "true" : "false");
    stream << "],\"orig\":[";
    for (std::size_t lane = 0; lane < record.originalIndex.size(); ++lane)
      stream << (lane == 0 ? "" : ",") << record.originalIndex[lane];
    stream << "],\"row\":[";
    for (std::size_t lane = 0; lane < record.localRow.size(); ++lane)
      stream << (lane == 0 ? "" : ",") << record.localRow[lane];
    stream << "],\"col\":[";
    for (std::size_t lane = 0; lane < record.localCol.size(); ++lane)
      stream << (lane == 0 ? "" : ",") << record.localCol[lane];
    stream << "],\"s\":[";
    for (std::size_t stage = 0; stage < kSpmvPipelineStageCount; ++stage)
      stream << (stage == 0 ? "" : ",") << record.starts[stage];
    stream << "],\"d\":[";
    for (std::size_t stage = 0; stage < kSpmvPipelineStageCount; ++stage)
      stream << (stage == 0 ? "" : ",") << record.durations[stage];
    stream << "]}";
  }
  stream << R"HTML(]};const names=['HBM beat','CSR5 decode','X join / unpack','MUL S0','MUL S1','MUL S2','MUL S3','Product FIFO','Product handshake'];const colors=['var(--hbm)','var(--decode)','var(--join)','var(--s0)','var(--s1)','var(--s2)','var(--s3)','var(--fifo)','var(--output)'];
let page=0,filtered=trace.records;const viewport=document.querySelector('.viewport'),rows=document.querySelector('#rows'),search=document.querySelector('#search'),size=document.querySelector('#pageSize'),zoom=document.querySelector('#zoom'),detailModal=document.querySelector('#detailModal'),detailTitle=document.querySelector('#detailTitle'),detailBody=document.querySelector('#detailBody'),detailClose=document.querySelector('#detailClose');
  document.querySelector('#summary').textContent='scale='+trace.scale+' · '+trace.mode+' X · HBM='+trace.hbmTiming+' · outstanding='+trace.outstanding+' · RTL cycles='+trace.cycles.toLocaleString()+' · HBM A/X='+trace.aBeats.toLocaleString()+'/'+trace.xBeats.toLocaleString()+' beats · ProductBeat='+trace.productBeats.toLocaleString()+' · products='+trace.products.toLocaleString()+' · output stalls='+trace.stalls.toLocaleString()+' · '+trace.captured.toLocaleString()+' records';
  function timelineBlocks(record){const blocks=[];let previousEnd=null,previousName='开始';for(let stage=0;stage<record.d.length;stage++){const duration=record.d[stage];if(!duration)continue;const start=record.s[stage],end=start+duration-1;if(previousEnd!==null&&start>previousEnd){const gapStart=previousEnd+1;blocks.push({kind:'unobserved',name:'UNOBSERVED / 未观测',start:gapStart,end:start-1,duration:start-gapStart,from:previousName,to:names[stage]});}blocks.push({kind:'stage',name:names[stage],stage:stage,start:start,end:end,duration:duration});previousEnd=Math.max(previousEnd===null?0:previousEnd,end);previousName=names[stage]}return blocks}
function addDetailRow(table,label,value){const tr=document.createElement('tr'),th=document.createElement('th'),td=document.createElement('td');th.textContent=label;td.textContent=value;tr.appendChild(th);tr.appendChild(td);table.appendChild(tr)}let lastFocusedBlock=null;function closeDetail(){detailModal.hidden=true;if(lastFocusedBlock!==null){lastFocusedBlock.focus();lastFocusedBlock=null}}
function openDetail(record,block,target){lastFocusedBlock=target;detailTitle.textContent=block.name+' 周期详情 · ProductBeat #'+record.n;detailBody.textContent='';const table=document.createElement('table');table.className='detail-table';addDetailRow(table,'记录','ProductBeat #'+record.n+' · tile '+record.tile+' · block '+record.rowBlock+'/'+record.colBlock);addDetailRow(table,'阶段',block.name);addDetailRow(table,'周期',block.start+'–'+block.end);addDetailRow(table,'驻留',block.duration+' cycles');addDetailRow(table,'输出握手',String(record.out));addDetailRow(table,'A beat / cycle',record.aBeat+' / '+(record.aCycle===null?'未观测':record.aCycle));addDetailRow(table,'X beat / cycle',record.xBeat+' / '+(record.xCycle===null?'未观测':record.xCycle));detailBody.appendChild(table);const heading=document.createElement('h3');heading.className='detail-heading';heading.textContent='该 ProductBeat 各阶段';detailBody.appendChild(heading);const stages=document.createElement('table');stages.className='detail-table';for(let i=0;i<names.length;i++)addDetailRow(stages,names[i],record.d[i]?record.s[i]+'–'+(record.s[i]+record.d[i]-1)+'（'+record.d[i]+' cycles）':'未经过');detailBody.appendChild(stages);const laneHeading=document.createElement('h3');laneHeading.className='detail-heading';laneHeading.textContent='8 lane sideband';detailBody.appendChild(laneHeading);const lanes=document.createElement('table');lanes.className='detail-table';for(let lane=0;lane<8;lane++)addDetailRow(lanes,'lane '+lane,record.valid[lane]?'valid · original '+record.orig[lane]+' · localRow '+record.row[lane]+' · localCol '+record.col[lane]:'invalid / zero fill');detailBody.appendChild(lanes);detailModal.hidden=false;detailClose.focus()}
  function render(){const count=+size.value,pages=Math.max(1,Math.ceil(filtered.length/count));page=Math.min(page,pages-1);const part=filtered.slice(page*count,(page+1)*count);rows.textContent='';if(!part.length){viewport.style.setProperty('--timeline-width','720px');rows.innerHTML='<div class="empty">没有匹配的 ProductBeat。</div>'}else{const prepared=part.map(record=>({record:record,blocks:timelineBlocks(record)})),min=Math.min.apply(null,prepared.flatMap(item=>item.blocks.map(block=>block.start))),max=Math.max.apply(null,prepared.flatMap(item=>item.blocks.map(block=>block.end)));const cell=+zoom.value,timelineWidth=Math.max(720,(max-min+1)*cell);viewport.style.setProperty('--timeline-width',timelineWidth+'px');for(const item of prepared){const r=item.record,row=document.createElement('div');row.className='row';for(const value of [r.n,'tile '+r.tile+' · block '+r.rowBlock+'/'+r.colBlock,'step '+r.step+' · valid '+r.valid.filter(Boolean).length,'cycle '+r.out]){const m=document.createElement('div');m.className='meta';m.textContent=value;m.setAttribute('aria-label',String(value));row.appendChild(m)}const line=document.createElement('div');line.className='timeline';line.style.setProperty('--cell',cell+'px');item.blocks.forEach(block=>{const b=document.createElement('div');b.className=block.kind==='unobserved'?'stage unobserved':'stage';b.style.setProperty('--c',block.kind==='unobserved'?'var(--unobserved)':colors[block.stage]);b.style.left=((block.start-min)*cell)+'px';b.style.width=Math.max(2,block.duration*cell)+'px';b.textContent=block.duration>=3?block.name:'';b.setAttribute('role','button');b.tabIndex=0;b.setAttribute('aria-label',block.name+': cycles '+block.start+'–'+block.end+', duration '+block.duration);b.addEventListener('click',function(){openDetail(r,block,b)});b.addEventListener('keydown',function(event){if(event.key==='Enter'||event.key===' '){event.preventDefault();openDetail(r,block,b)}});line.appendChild(b)});row.appendChild(line);rows.appendChild(row)}}document.querySelector('#page').textContent='第 '+(page+1)+'/'+pages+' 页，共 '+filtered.length.toLocaleString()+' 条';document.querySelector('#prev').disabled=page===0;document.querySelector('#next').disabled=page>=pages-1}
function filter(){const query=search.value.trim().toLowerCase();filtered=query?trace.records.filter(function(record){return [record.n,record.tile,record.blockTile,record.rowBlock,record.colBlock,record.step,record.aBeat,record.xBeat].concat(record.orig,record.row,record.col).join(' ').toLowerCase().includes(query)}):trace.records;page=0;render()}search.addEventListener('input',filter);size.addEventListener('change',render);zoom.addEventListener('input',render);document.querySelector('#prev').addEventListener('click',function(){page--;render()});document.querySelector('#next').addEventListener('click',function(){page++;render()});detailClose.addEventListener('click',closeDetail);detailModal.addEventListener('click',function(event){if(event.target===detailModal)closeDetail()});document.addEventListener('keydown',function(event){if(event.key==='Escape'&&!detailModal.hidden)closeDetail()});render();</script></body></html>)HTML";
  stream.close();
  if (!stream) {
    fs::remove(temporary);
    throw std::runtime_error("failed to write SPMV pipeline report " + temporary.string());
  }
  fs::rename(temporary, path, error);
  if (error) {
    fs::remove(temporary);
    throw std::runtime_error("failed to publish SPMV pipeline report " + path.string() + ": " +
        error.message());
  }
}
#endif
#endif

std::uint32_t outstandingLimit() {
  const char* configured = std::getenv("SPMV_HBM_OUTSTANDING");
  if (configured == nullptr || *configured == '\0') {
    return 2;
  }
  if (std::string(configured) == "1") {
    return 1;
  }
  if (std::string(configured) == "2") {
    return 2;
  }
  throw std::runtime_error("SPMV_HBM_OUTSTANDING must be 1 or 2");
}

std::size_t alignUp(std::size_t value, std::size_t alignment) {
  if (alignment == 0 || (alignment & (alignment - 1)) != 0 ||
      value > std::numeric_limits<std::size_t>::max() - (alignment - 1)) {
    throw std::runtime_error("invalid HBM region alignment");
  }
  return (value + alignment - 1) & ~(alignment - 1);
}

int runSimulation(const DatasetChoice& choice, int argc, char** argv) {
  const CsrMatrix matrix = loadMatrix(choice);
  const std::vector<double> inputDouble = readArray<double>(choice.path / "b.txt");
  if (matrix.rows == 0 || inputDouble.size() != matrix.rows) {
    throw std::runtime_error("CSR5 v3 requires a nonempty square matrix and matching b.txt");
  }
  if (matrix.values.size() > std::numeric_limits<std::uint32_t>::max()) {
    throw std::runtime_error("CSR nonzero count exceeds the ProductBeat original-index width");
  }
  const std::size_t blockCount = (matrix.rows - 1) / kBlockDimension + 1;
  if (blockCount > static_cast<std::size_t>(std::numeric_limits<std::uint16_t>::max()) + 1) {
    throw std::runtime_error("matrix dimension exceeds the Metadata v2 block-id range");
  }
  std::vector<std::uint32_t> aBits;
  std::vector<std::uint32_t> xBits;
  aBits.reserve(matrix.values.size());
  xBits.reserve(inputDouble.size());
  for (double value : matrix.values) {
    const float converted = static_cast<float>(value);
    if (!std::isfinite(value) || !std::isfinite(converted)) {
      throw std::runtime_error("matrix value is NaN, Inf, or outside finite FP32 range");
    }
    aBits.push_back(floatBits(converted));
  }
  for (double value : inputDouble) {
    const float converted = static_cast<float>(value);
    if (!std::isfinite(value) || !std::isfinite(converted)) {
      throw std::runtime_error("b.txt value is NaN, Inf, or outside finite FP32 range");
    }
    xBits.push_back(floatBits(converted));
  }
  if (aBits.empty()) {
    throw std::runtime_error("CSR5 v3 requires at least one nonzero");
  }

  const BlockMap blocks = partitionMatrix(matrix, aBits, xBits);
  if (blocks.empty()) {
    throw std::runtime_error("CSR5 partitioning produced no nonempty blocks");
  }
  gHbmMemory.assign(kHbmBytes, 0);
  gHbmReadCount = 0;
  gInvalidHbmAddress = false;
  gFirstInvalidHbmAddress = 0;
#if SPMV_PIPELINE_HTML
  gPipelineReportRecords.clear();
  gPipelineHbmReads.clear();
  gPipelineTraceActive = false;
#endif

  VerilatedContext context;
  context.commandArgs(argc, argv);
  VSpmvOneHbmCsr5MulSimulationTop dut(&context);
  std::uint64_t cycles = 0;
  auto cycle = [&](const auto& beforeEdge) {
    dut.clock = 0;
    dut.eval();
    beforeEdge();
#if SPMV_PIPELINE_HTML
    gPipelineCycle = cycles + 1;
#endif
    dut.clock = 1;
    dut.eval();
    context.timeInc(1);
    ++cycles;
  };

  dut.io_config_valid = 0;
  dut.io_start = 0;
  dut.io_product_ready = 0;
  dut.reset = 1;
  for (unsigned index = 0; index < 4; ++index) {
    cycle([] {});
  }
  dut.reset = 0;
  cycle([] {});

  std::vector<std::uint32_t> products(matrix.values.size(), 0);
  std::vector<bool> productSeen(matrix.values.size(), false);
  std::uint32_t randomState = 0x2468ace1U;
  const bool disableOutputStalls = std::getenv("SPMV_DISABLE_OUTPUT_STALLS") != nullptr;
  const bool hbmNoJitter = spmvEnvFlag("SPMV_HBM_NO_JITTER");
  const std::uint32_t configuredOutstanding = outstandingLimit();
  constexpr std::uint64_t kCycleLimit = 50000000ULL;
  std::uint64_t nextGlobalTileId = 0;
  std::uint64_t totalABeats = 0;
  std::uint64_t totalXBeats = 0;
  std::uint64_t totalABursts = 0;
  std::uint64_t totalXBursts = 0;
  std::uint64_t totalPackets = 0;
  std::uint64_t totalProductBeats = 0;
  std::uint64_t totalProducts = 0;
  std::uint64_t totalStalls = 0;
  std::uint64_t totalPcDataCycles = 0;
  std::uint64_t totalPcIdleCycles = 0;
  std::uint64_t totalJoinWaitA = 0;
  std::uint64_t totalJoinWaitX = 0;
  std::uint64_t totalCacheLoadCycles = 0;
  std::uint32_t totalFpFlags = 0;
  std::size_t columnGroups = 0;
  std::uint64_t firstProductLatency = 0;
  bool firstProductObserved = false;

  // 同一列块共享一份 X slice；该列块中的所有非空行块合成一次 HBM stream。
  for (auto groupBegin = blocks.begin(); groupBegin != blocks.end();) {
    const std::uint16_t blockColId = groupBegin->first.first;
    auto groupEnd = groupBegin;
    PackedStream packed;
#if SPMV_PIPELINE_HTML
    std::vector<SpmvPipelineRecord> groupPipelineRecords;
#endif
    while (groupEnd != blocks.end() && groupEnd->first.first == blockColId) {
      appendCsr5Block(packed, groupEnd->second, nextGlobalTileId);
      ++groupEnd;
    }
    if (packed.bytes.empty() || packed.bytes.size() % 64 != 0 || packed.bytes.size() > kHbmBytes ||
        packed.bytes.size() / 64 > std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error("column-group CSR5 stream does not fit the 128 MiB HBM window");
    }
    if (packed.validProducts == 0 ||
        packed.validProducts > std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error("column-group product count exceeds the config field width");
    }
    const std::size_t colBase = static_cast<std::size_t>(blockColId) * kBlockDimension;
    if (colBase >= matrix.rows) {
      throw std::runtime_error("partitioned column block lies outside the matrix");
    }
    const std::size_t blockCols = std::min(kBlockDimension, matrix.rows - colBase);
    const std::size_t blockRows = std::min(kBlockDimension, matrix.rows);
    const PackedXStream packedX = kCachedX
        ? packCachedX(xBits, colBase, blockCols)
        : packPairedX(packed.products);
    const std::size_t xOffset = alignUp(packed.bytes.size(), 4096);
    if (packedX.bytes.empty() || packedX.bytes.size() % 64 != 0 ||
        xOffset > kHbmBytes || packedX.bytes.size() > kHbmBytes - xOffset) {
      throw std::runtime_error("A and X streams do not fit the shared 128 MiB HBM window");
    }
    std::fill(gHbmMemory.begin(), gHbmMemory.begin() + xOffset + packedX.bytes.size(), 0);
    std::copy(packed.bytes.begin(), packed.bytes.end(), gHbmMemory.begin());
    std::copy(packedX.bytes.begin(), packedX.bytes.end(), gHbmMemory.begin() + xOffset);
    const std::uint32_t aStreamBeats = static_cast<std::uint32_t>(packed.bytes.size() / 64);
    const std::uint32_t xStreamBeats = static_cast<std::uint32_t>(packedX.bytes.size() / 64);
#if SPMV_PIPELINE_HTML
    gPipelineHbmReads.clear();
    gPipelineAAddress = kHbmBase;
    gPipelineXAddress = kHbmBase + xOffset;
    gPipelineABeats = aStreamBeats;
    gPipelineXBeats = xStreamBeats;
#endif

    dut.io_product_ready = 0;
    dut.io_config_bits_blockRows = static_cast<std::uint16_t>(blockRows);
    dut.io_config_bits_blockCols = static_cast<std::uint16_t>(blockCols);
    dut.io_config_bits_aAddress = kHbmBase;
    dut.io_config_bits_aBeats = aStreamBeats;
    dut.io_config_bits_xAddress = kHbmBase + xOffset;
    dut.io_config_bits_xBeats = xStreamBeats;
    dut.io_config_bits_xCrc32 = packedX.crc;
    dut.io_config_bits_expectedPackets = packed.packets;
    dut.io_config_bits_expectedProductBeats = static_cast<std::uint32_t>(packed.products.size());
    dut.io_config_bits_expectedProducts = static_cast<std::uint32_t>(packed.validProducts);
    dut.io_config_bits_outstandingLimit = configuredOutstanding;
    dut.io_config_valid = 1;
    bool configAccepted = false;
    while (!configAccepted) {
      cycle([&] { configAccepted = dut.io_config_ready; });
    }
    dut.io_config_valid = 0;
#if SPMV_PIPELINE_HTML
    gPipelineTraceActive = true;
#endif
    dut.io_start = 1;
    cycle([] {});
    dut.io_start = 0;

    const std::uint64_t dpiReadsBefore = gHbmReadCount;
    std::size_t productBeat = 0;
    while (!dut.io_done) {
      randomState ^= randomState << 13;
      randomState ^= randomState >> 17;
      randomState ^= randomState << 5;
      dut.io_product_ready = disableOutputStalls || (randomState & 3U) != 0;
      cycle([&] {
        if (dut.io_product_valid && dut.io_product_ready) {
          if (productBeat >= packed.products.size()) {
            throw std::runtime_error("RTL emitted more ProductBeat values than the column-group stream");
          }
          verifyProductBeat(dut, packed.products[productBeat], totalProductBeats + productBeat,
              products, productSeen);
#if SPMV_PIPELINE_HTML
          groupPipelineRecords.push_back(makePipelineRecord(
              packed.products[productBeat], totalProductBeats + productBeat,
              cycles + 1, kCachedX, productBeat));
#endif
          ++productBeat;
        }
      });
      if (dut.io_errorMask != 0) {
        std::ostringstream message;
        message << "RTL sticky error mask is 0x" << std::hex << dut.io_errorMask
                << " in column block " << std::dec << blockColId
                << " after product beat " << productBeat;
        if (productBeat != 0 && productBeat <= packed.products.size()) {
          const ExpectedProductBeat& recent = packed.products[productBeat - 1];
          message << " (global tile " << recent.globalTileId << ", block row "
                  << recent.blockRowId << ", step " << recent.step << ")";
        }
        throw std::runtime_error(message.str());
      }
      if (cycles > kCycleLimit) {
        throw std::runtime_error("Verilator simulation exceeded the cycle limit");
      }
    }
    dut.io_product_ready = 0;
#if SPMV_PIPELINE_HTML
    gPipelineTraceActive = false;
    std::vector<std::uint64_t> aBeatCycles(aStreamBeats, kSpmvNoCycle);
    std::vector<std::uint64_t> xBeatCycles(xStreamBeats, kSpmvNoCycle);
    for (const PipelineHbmRead& read : gPipelineHbmReads) {
      std::vector<std::uint64_t>& cyclesBySource = read.sourceX ? xBeatCycles : aBeatCycles;
      if (read.beatIndex < cyclesBySource.size() && cyclesBySource[read.beatIndex] == kSpmvNoCycle) {
        cyclesBySource[read.beatIndex] = read.cycle;
      }
    }
    for (SpmvPipelineRecord& record : groupPipelineRecords) {
      const std::uint64_t aCycle = record.aBeatIndex < aBeatCycles.size()
          ? aBeatCycles[record.aBeatIndex] : kSpmvNoCycle;
      std::uint64_t xCycle = kSpmvNoCycle;
      if (kCachedX) {
        for (std::size_t lane = 0; lane < record.valid.size(); ++lane) {
          if (!record.valid[lane]) continue;
          const std::uint32_t word = record.localCol[lane] / 16;
          if (word < xBeatCycles.size() && xBeatCycles[word] != kSpmvNoCycle) {
            xCycle = xCycle == kSpmvNoCycle ? xBeatCycles[word] :
                std::max(xCycle, xBeatCycles[word]);
          }
        }
      } else if (record.xBeatIndex < xBeatCycles.size()) {
        xCycle = xBeatCycles[record.xBeatIndex];
      }
      assignPipelineStages(record, aCycle, xCycle);
      gPipelineReportRecords.push_back(record);
    }
#endif
    if (!firstProductObserved) {
      firstProductLatency = dut.io_firstProductLatency;
      firstProductObserved = true;
    }

    if (gInvalidHbmAddress) {
      std::ostringstream message;
      message << "illegal HBM beat address 0x" << std::hex << gFirstInvalidHbmAddress;
      throw std::runtime_error(message.str());
    }
    if (dut.io_errorMask != 0) {
      std::ostringstream message;
      message << "RTL sticky error mask is 0x" << std::hex << dut.io_errorMask
              << " in column block " << std::dec << blockColId;
      throw std::runtime_error(message.str());
    }
    const std::uint64_t expectedDpiReads =
        static_cast<std::uint64_t>(aStreamBeats) + xStreamBeats;
    const std::uint32_t expectedXGroups = kCachedX ? 0U :
        static_cast<std::uint32_t>(packed.products.size());
    if (productBeat != packed.products.size() || dut.io_aBeatCount != aStreamBeats ||
        dut.io_xBeatCount != xStreamBeats ||
        dut.io_packetCount != packed.packets || dut.io_productBeatCount != productBeat ||
        dut.io_productCount != packed.validProducts ||
        gHbmReadCount - dpiReadsBefore != expectedDpiReads ||
        dut.io_xGroupCount != expectedXGroups) {
      throw std::runtime_error("column-group drain counters do not match the packed stream");
    }

    totalABeats += dut.io_aBeatCount;
    totalXBeats += dut.io_xBeatCount;
    totalABursts += dut.io_aBurstCount;
    totalXBursts += dut.io_xBurstCount;
    totalPackets += dut.io_packetCount;
    totalProductBeats += dut.io_productBeatCount;
    totalProducts += dut.io_productCount;
    totalStalls += dut.io_stallCycles;
    totalPcDataCycles += dut.io_pcDataCycles;
    totalPcIdleCycles += dut.io_pcIdleCycles;
    totalJoinWaitA += dut.io_joinWaitACycles;
    totalJoinWaitX += dut.io_joinWaitXCycles;
    totalCacheLoadCycles += dut.io_cacheLoadCycles;
    totalFpFlags |= dut.io_fpFlags;
    ++columnGroups;
    groupBegin = groupEnd;
  }
  dut.final();

  if (totalProducts != matrix.values.size() ||
      !std::all_of(productSeen.begin(), productSeen.end(), [](bool seen) { return seen; })) {
    throw std::runtime_error("ProductBeat stream did not cover every CSR nonzero exactly once");
  }

  std::vector<std::uint32_t> actualRows(matrix.rows, 0);
  std::vector<std::uint32_t> goldenRows(matrix.rows, 0);
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    std::uint32_t actual = 0;
    std::uint32_t golden = 0;
    for (std::uint64_t offset = matrix.rowPointers[row]; offset < matrix.rowPointers[row + 1]; ++offset) {
      const std::size_t index = static_cast<std::size_t>(offset);
      actual = addFp32(actual, products[index]);
      golden = addFp32(golden, multiplyFp32(aBits[index], xBits[matrix.columnIndices[index]]).bits);
    }
    actualRows[row] = actual;
    goldenRows[row] = golden;
    if (actual != golden) {
      std::ostringstream message;
      message << "row " << row << " FP32 mismatch: actual=0x" << std::hex << actual
              << " expected=0x" << golden;
      throw std::runtime_error(message.str());
    }
  }
  const std::uint64_t totalHbmBeats = totalABeats + totalXBeats;
  const std::uint64_t totalHbmBursts = totalABursts + totalXBursts;
  const std::uint64_t totalPcCycles = totalPcDataCycles + totalPcIdleCycles;
  const double pcUtilization = totalPcCycles == 0 ? 0.0 :
      static_cast<double>(totalPcDataCycles) / static_cast<double>(totalPcCycles);
  if (gHbmReadCount != totalHbmBeats) {
    throw std::runtime_error("DPI read count does not equal A plus X hardware beat counts");
  }

  const std::uint64_t resultHash = hashRows(actualRows);

  std::cout << "[spmv-csr5] scale=" << choice.name << " rows=" << matrix.rows
            << " cols=" << matrix.rows << " nnz=" << matrix.values.size()
            << " x_mode=" << (kCachedX ? "cached" : "paired")
            << " hbm_timing=" << (hbmNoJitter ? "fixed-77" : "jitter-73-81")
            << " outstanding=" << configuredOutstanding
            << " nonempty_blocks=" << blocks.size() << " column_groups=" << columnGroups
            << " cycles=" << cycles << '\n';
  std::cout << "[spmv-csr5] a_beats=" << totalABeats
            << " x_beats=" << totalXBeats << " hbm_beats=" << totalHbmBeats
            << " a_bursts=" << totalABursts << " x_bursts=" << totalXBursts
            << " hbm_bursts=" << totalHbmBursts
            << " pc_data_cycles=" << totalPcDataCycles
            << " pc_idle_cycles=" << totalPcIdleCycles
            << " pc_utilization=" << std::fixed << std::setprecision(6) << pcUtilization
            << " join_wait_a=" << totalJoinWaitA << " join_wait_x=" << totalJoinWaitX
            << " cache_load_cycles=" << totalCacheLoadCycles
            << " first_product_latency=" << firstProductLatency
            << " dpi_read512_calls=" << gHbmReadCount << " packets=" << totalPackets
            << " product_beats=" << totalProductBeats << " products=" << totalProducts
            << " stalls=" << totalStalls
            << " fp_flags=0x" << std::hex << totalFpFlags
            << " result_hash=0x" << resultHash << std::dec << '\n';
#if SPMV_PERFORMANCE_HTML || SPMV_PIPELINE_HTML
#if SPMV_PERFORMANCE_HTML
  writePerformanceReports(choice.name, matrix.rows, matrix.values.size(), kCachedX,
      hbmNoJitter, configuredOutstanding, cycles, totalABeats, totalXBeats, totalHbmBursts,
      pcUtilization, totalJoinWaitA, totalJoinWaitX, totalCacheLoadCycles,
      firstProductLatency, totalPackets, totalProductBeats, totalProducts,
      totalStalls, totalFpFlags, resultHash);
#endif
#if SPMV_PIPELINE_HTML
  writeDetailedPipelineHtml(resolveReportDirectory() / "pipeline.html", choice.name, kCachedX,
      hbmNoJitter, configuredOutstanding, cycles, totalABeats, totalXBeats, totalProductBeats,
      totalProducts, totalStalls);
  std::cout << "[spmv-csr5] pipeline_html=" << (resolveReportDirectory() / "pipeline.html") << '\n';
#endif
#endif
  return 0;
}
#endif

int run(const std::string& requested, int argc, char** argv) {
  const fs::path dataRoot = resolveDataRoot();
  const std::vector<DatasetChoice> choices = discoverDatasets(dataRoot);
  if (requested.empty() || requested == "--list") {
    printChoices(dataRoot, choices);
    return 0;
  }
  if (choices.empty()) {
    throw std::runtime_error("no CSR datasets were found under " + dataRoot.string() +
        "; run make -C accelerator-sim/data");
  }
  const DatasetChoice& choice = selectDataset(choices, requested);
#ifdef SPMV_CSR5_VERILATOR
  return runSimulation(choice, argc, argv);
#else
  (void)argc;
  (void)argv;
  return runGolden(choice);
#endif
}

}  // namespace

}  // namespace accelerator_sim::spmv

int main(int argc, char** argv) {
  try {
#ifdef SPMV_CSR5_VERILATOR
    if (argc >= 2 && std::string(argv[1]) == "--self-test-hbm-dpi") {
      return runHbmDpiSelfTest();
    }
#endif
    const std::string requested = argc >= 2 ? argv[1] : "";
    return accelerator_sim::spmv::run(requested, argc, argv);
  } catch (const std::exception& error) {
    std::cerr << "spmv-host: " << error.what() << '\n';
    return 2;
  }
}
