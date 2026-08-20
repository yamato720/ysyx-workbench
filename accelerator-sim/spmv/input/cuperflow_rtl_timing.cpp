#include "cuperflow_timing.hpp"

#ifdef SPMV_CUPERFLOW_RTL_VERILATOR

#include "../encoding/cuperflow/cuperflow.hpp"
#include "../golden.hpp"
#include "VSpmvCuperflowInputTop.h"
#include "verilated.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <numeric>
#include <stdexcept>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>
#include <unistd.h>

namespace fs = std::filesystem;

#ifndef ACCELERATOR_SIM_DEFAULT_DATA_ROOT
#define ACCELERATOR_SIM_DEFAULT_DATA_ROOT "../data"
#endif
#ifndef SPMV_CUPERFLOW_HBM_PC_COUNT_FROZEN
#define SPMV_CUPERFLOW_HBM_PC_COUNT_FROZEN 16
#endif
#ifndef SPMV_CUPERFLOW_HBM_BASE_FROZEN
#define SPMV_CUPERFLOW_HBM_BASE_FROZEN 0x80000000ULL
#endif
#ifndef SPMV_CUPERFLOW_HBM_BYTES_FROZEN
#define SPMV_CUPERFLOW_HBM_BYTES_FROZEN (128ULL * 1024ULL * 1024ULL)
#endif
#ifndef SPMV_CUPERFLOW_X_REGION_BYTES_FROZEN
#define SPMV_CUPERFLOW_X_REGION_BYTES_FROZEN (64ULL * 1024ULL * 1024ULL)
#endif
#ifndef SPMV_CUPERFLOW_AXI_ADDR_WIDTH_FROZEN
#define SPMV_CUPERFLOW_AXI_ADDR_WIDTH_FROZEN 64
#endif
#ifndef SPMV_CUPERFLOW_AXI_DATA_WIDTH_FROZEN
#define SPMV_CUPERFLOW_AXI_DATA_WIDTH_FROZEN 512
#endif
#ifndef SPMV_CUPERFLOW_AXI_ID_WIDTH_FROZEN
#define SPMV_CUPERFLOW_AXI_ID_WIDTH_FROZEN 4
#endif
#ifndef SPMV_CUPERFLOW_MAX_OUTSTANDING_BURSTS_FROZEN
#define SPMV_CUPERFLOW_MAX_OUTSTANDING_BURSTS_FROZEN 2
#endif
#ifndef SPMV_CUPERFLOW_X_WINDOW_SIZE_FROZEN
#define SPMV_CUPERFLOW_X_WINDOW_SIZE_FROZEN 8192
#endif
#ifndef SPMV_CUPERFLOW_X_ELEMENT_WIDTH_FROZEN
#define SPMV_CUPERFLOW_X_ELEMENT_WIDTH_FROZEN 64
#endif
#ifndef SPMV_CUPERFLOW_X_LOAD_LANES_FROZEN
#define SPMV_CUPERFLOW_X_LOAD_LANES_FROZEN 8
#endif
#ifndef SPMV_CUPERFLOW_MAP_ABI_FROZEN
#define SPMV_CUPERFLOW_MAP_ABI_FROZEN "cuperflow-map-multisegment-v3"
#endif
#ifndef SPMV_CUPERFLOW_FP64_MUL_LATENCY_FROZEN
#define SPMV_CUPERFLOW_FP64_MUL_LATENCY_FROZEN 4
#endif
#ifndef SPMV_CUPERFLOW_FP64_MUL_II_FROZEN
#define SPMV_CUPERFLOW_FP64_MUL_II_FROZEN 1
#endif

namespace accelerator_sim::spmv {
namespace {

namespace cf = encoding::cuperflow;

constexpr std::size_t kPcCount = SPMV_CUPERFLOW_HBM_PC_COUNT_FROZEN;
constexpr std::size_t kXLoadLanes = SPMV_CUPERFLOW_X_LOAD_LANES_FROZEN;
constexpr std::size_t kWordsPerBeat = SPMV_CUPERFLOW_AXI_DATA_WIDTH_FROZEN /
    SPMV_CUPERFLOW_X_ELEMENT_WIDTH_FROZEN;
constexpr std::size_t kBeatBytes = SPMV_CUPERFLOW_AXI_DATA_WIDTH_FROZEN / 8;
constexpr std::size_t kMulLatency = SPMV_CUPERFLOW_FP64_MUL_LATENCY_FROZEN;
constexpr std::size_t kMulII = SPMV_CUPERFLOW_FP64_MUL_II_FROZEN;

static_assert(kPcCount == 16 && kXLoadLanes == 8 && kWordsPerBeat == 8 &&
    kBeatBytes == 64 && kMulLatency > 0 && kMulII > 0,
    "Cuperflow RTL host 的冻结几何与 Verilator ABI 不一致");
static_assert(std::string_view(SPMV_CUPERFLOW_MAP_ABI_FROZEN) ==
    "cuperflow-map-multisegment-v3",
    "Cuperflow RTL host 只支持多段紧凑 X map ABI v3");
static_assert(SPMV_CUPERFLOW_AXI_ADDR_WIDTH_FROZEN == 64 &&
    SPMV_CUPERFLOW_AXI_ID_WIDTH_FROZEN == 4 &&
    SPMV_CUPERFLOW_X_WINDOW_SIZE_FROZEN == 8192 &&
    SPMV_CUPERFLOW_X_ELEMENT_WIDTH_FROZEN == 64,
    "Cuperflow RTL host 只支持当前 FP64/8192 配置");



struct HbmPort {
  CData* arReady;
  CData* arValid;
  CData* arId;
  CData* arLength;
  CData* arSize;
  CData* arBurst;
  QData* arAddress;
  CData* rReady;
  CData* rValid;
  CData* rId;
  CData* rResponse;
  CData* rLast;
  VlWide<16>* rData;
};

using Beat = cf::CuperflowBeat;

enum class HbmRegionKind { x, a };

struct HbmRegion {
  const std::vector<Beat>* beats = nullptr;
  std::size_t begin = 0;
  std::size_t end = 0;
  std::uint64_t base = 0;
  std::size_t nextIssued = 0;
  std::size_t nextData = 0;

  std::size_t size() const { return end - begin; }
  bool enabled() const { return beats != nullptr && begin < end; }
};

struct HbmBurst {
  HbmRegionKind region = HbmRegionKind::a;
  std::size_t remaining = 0;
};

struct HbmModel {
  HbmRegion x;
  HbmRegion a;
  std::deque<HbmBurst> bursts;

  bool complete() const {
    const bool xComplete = !x.enabled() || x.nextData == x.size();
    const bool aComplete = !a.enabled() || a.nextData == a.size();
    return xComplete && aComplete && bursts.empty();
  }
};

struct CycleRecord {
  std::uint64_t cycle = 0;
  std::uint16_t xAr = 0;
  std::uint16_t xR = 0;
  std::uint16_t aAr = 0;
  std::uint16_t aR = 0;
  std::array<std::uint8_t, kPcCount> aValidSlotMask{};
};

struct WorkTiming {
  std::size_t index = 0;
  std::size_t wave = 0;
  std::size_t batch = 0;
  /** 每个 PC 在本 wave 中独占的 sliceGroup；尾 wave 的空 PC 标为 false。 */
  std::array<bool, kPcCount> groupActive{};
  std::array<std::size_t, kPcCount> sliceGroups{};
  /** 每个 PC 只在 wave 的 batch 0 装载自己的 X range。 */
  std::array<bool, kPcCount> xLoaded{};
  std::array<std::size_t, kPcCount> xElements{};
  std::array<std::size_t, kPcCount> xWords{};
  std::array<std::size_t, kPcCount> xOffsetBeats{};
  std::array<std::size_t, kPcCount> xBeats{};
  std::array<std::size_t, kPcCount> xMarkers{};
  std::array<std::size_t, kPcCount> xWriteCycles{};
  std::uint64_t start = 0;
  std::uint64_t xInputBegin = 0;
  std::uint64_t xInputEnd = 0;
  std::uint64_t xWriteBegin = 0;
  std::uint64_t xWriteEnd = 0;
  std::uint64_t xReady = 0;
  std::uint64_t aRequest = 0;
  std::uint64_t aBegin = 0;
  std::uint64_t aEnd = 0;
  std::uint64_t mulRequestBegin = 0;
  std::uint64_t mulRequestEnd = 0;
  std::uint64_t mulResponseBegin = 0;
  std::uint64_t mulResponseEnd = 0;
  std::uint64_t done = 0;
  std::array<std::uint64_t, kPcCount> aOffsetBeats{};
  std::array<std::uint64_t, kPcCount> aBeats{};
  std::array<std::array<std::uint64_t, kWordsPerBeat>, kPcCount> aLaneOffsetBeats{};
  std::array<std::array<std::uint64_t, kWordsPerBeat>, kPcCount> aLaneBeats{};
  std::array<std::uint64_t, kPcCount> usefulSlots{};
  std::array<std::uint64_t, kPcCount> physicalSlots{};
  std::array<std::uint64_t, kPcCount> expectedProductChecksumByPc{};
  std::uint64_t expectedProductChecksum = 0;
  std::uint64_t rtlProductChecksum = 0;
};

#define CUPERFLOW_HBM_PORT(index) HbmPort{ \
    &dut.io_hbm_##index##_ar_ready, &dut.io_hbm_##index##_ar_valid, \
    &dut.io_hbm_##index##_ar_bits_id, &dut.io_hbm_##index##_ar_bits_len, \
    &dut.io_hbm_##index##_ar_bits_size, &dut.io_hbm_##index##_ar_bits_burst, \
    &dut.io_hbm_##index##_ar_bits_addr, &dut.io_hbm_##index##_r_ready, \
    &dut.io_hbm_##index##_r_valid, &dut.io_hbm_##index##_r_bits_id, \
    &dut.io_hbm_##index##_r_bits_resp, &dut.io_hbm_##index##_r_bits_last, \
    &dut.io_hbm_##index##_r_bits_data}

std::array<HbmPort, kPcCount> hbmPorts(VSpmvCuperflowInputTop& dut) {
  return {{CUPERFLOW_HBM_PORT(0), CUPERFLOW_HBM_PORT(1), CUPERFLOW_HBM_PORT(2),
      CUPERFLOW_HBM_PORT(3), CUPERFLOW_HBM_PORT(4), CUPERFLOW_HBM_PORT(5),
      CUPERFLOW_HBM_PORT(6), CUPERFLOW_HBM_PORT(7), CUPERFLOW_HBM_PORT(8),
      CUPERFLOW_HBM_PORT(9), CUPERFLOW_HBM_PORT(10), CUPERFLOW_HBM_PORT(11),
      CUPERFLOW_HBM_PORT(12), CUPERFLOW_HBM_PORT(13), CUPERFLOW_HBM_PORT(14),
      CUPERFLOW_HBM_PORT(15)}};
}

#undef CUPERFLOW_HBM_PORT

template <typename T>
std::vector<T> readArray(const fs::path& path) {
  std::ifstream input(path);
  if (!input) throw std::runtime_error("无法打开数据文件: " + path.string());
  std::vector<T> values;
  T value{};
  while (input >> value) values.push_back(value);
  if (!input.eof() || values.empty()) {
    throw std::runtime_error("无法解析或数据为空: " + path.string());
  }
  return values;
}

std::vector<std::uint64_t> readIntegers(const fs::path& path) {
  const std::vector<std::int64_t> signedValues = readArray<std::int64_t>(path);
  std::vector<std::uint64_t> values;
  values.reserve(signedValues.size());
  for (const std::int64_t value : signedValues) {
    if (value < 0) throw std::runtime_error("数据文件包含负整数: " + path.string());
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

fs::path findDataset(const std::string& requested) {
  const fs::path direct(requested);
  if (isDatasetDirectory(direct)) return direct;
  const fs::path root = std::getenv("ACCELERATOR_DATA_ROOT") != nullptr &&
      *std::getenv("ACCELERATOR_DATA_ROOT") != '\0'
      ? fs::path(std::getenv("ACCELERATOR_DATA_ROOT"))
      : fs::path(ACCELERATOR_SIM_DEFAULT_DATA_ROOT);
  for (const fs::path& parent : {root / "generated" / "cgsolver", root / "suitesparse"}) {
    if (!fs::is_directory(parent)) continue;
    for (const fs::directory_entry& entry : fs::recursive_directory_iterator(
             parent, fs::directory_options::skip_permission_denied)) {
      if (entry.is_directory() && entry.path().filename() == requested &&
          isDatasetDirectory(entry.path())) return entry.path();
    }
  }
  throw std::runtime_error("找不到数据集: " + requested);
}

CsrMatrix loadMatrix(const fs::path& dataset) {
  CsrMatrix matrix;
  matrix.rowPointers = readIntegers(dataset / "row_ptr.txt");
  const std::vector<std::uint64_t> columns = readIntegers(dataset / "col_idx.txt");
  matrix.values = readArray<double>(dataset / "values.txt");
  if (matrix.rowPointers.size() < 2 || matrix.rowPointers.front() != 0 ||
      matrix.rowPointers.back() != columns.size() || columns.size() != matrix.values.size()) {
    throw std::runtime_error("CSR row pointer、column 和 value 数量不一致");
  }
  matrix.rows = matrix.rowPointers.size() - 1U;
  matrix.columns = matrix.rows;
  matrix.columnIndices.reserve(columns.size());
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    if (matrix.rowPointers[row] > matrix.rowPointers[row + 1U]) {
      throw std::runtime_error("CSR row pointer 必须单调不减");
    }
  }
  for (const std::uint64_t column : columns) {
    if (column >= matrix.columns || column > std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error("CSR column index 超出方阵范围");
    }
    matrix.columnIndices.push_back(static_cast<std::uint32_t>(column));
  }
  return matrix;
}

void makeExtremeSparseXSpanDataset(CsrMatrix& matrix, std::vector<double>& x) {
  constexpr std::size_t kGroupColumns = 8192;
  constexpr std::size_t kGroupCount = 16;
  constexpr std::size_t kColumns = kGroupCount * kGroupColumns;
  constexpr std::size_t kDemandedColumns = kGroupColumns / 2;

  matrix = CsrMatrix{};
  matrix.rows = kColumns;
  matrix.columns = kColumns;
  matrix.rowPointers.resize(matrix.rows + 1U);
  matrix.columnIndices.reserve(kDemandedColumns);
  matrix.values.reserve(kDemandedColumns);
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    if (row < kDemandedColumns) {
      matrix.columnIndices.push_back(static_cast<std::uint32_t>(2U * row + 1U));
      matrix.values.push_back(1.0 + static_cast<double>(row % 7U));
    }
    matrix.rowPointers[row + 1U] = matrix.columnIndices.size();
  }
  x.resize(kColumns);
  for (std::size_t column = 0; column < x.size(); ++column) {
    x[column] = 0.25 + static_cast<double>(column);
  }
}

void makeThreeIslandXSpanDataset(CsrMatrix& matrix, std::vector<double>& x) {
  constexpr std::size_t kGroupColumns = 8192;
  constexpr std::size_t kGroupCount = 16;
  constexpr std::size_t kColumns = kGroupCount * kGroupColumns;
  constexpr std::size_t kIslandElements = 100;
  constexpr std::size_t kDemandedColumns = 3 * kIslandElements;

  matrix = CsrMatrix{};
  matrix.rows = kColumns;
  matrix.columns = kColumns;
  matrix.rowPointers.resize(matrix.rows + 1U);
  matrix.columnIndices.reserve(kDemandedColumns);
  matrix.values.reserve(kDemandedColumns);
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    std::size_t column = 0;
    bool occupied = false;
    if (row < kIslandElements) {
      column = row;
      occupied = true;
    } else if (row < 2 * kIslandElements) {
      column = kGroupColumns / 2 + row - kIslandElements;
      occupied = true;
    } else if (row < kDemandedColumns) {
      column = kGroupColumns - kIslandElements + row - 2 * kIslandElements;
      occupied = true;
    }
    if (occupied) {
      matrix.columnIndices.push_back(static_cast<std::uint32_t>(column));
      matrix.values.push_back(1.0 + static_cast<double>(row % 7U));
    }
    matrix.rowPointers[row + 1U] = matrix.columnIndices.size();
  }
  x.resize(kColumns);
  for (std::size_t column = 0; column < x.size(); ++column) {
    x[column] = 0.25 + static_cast<double>(column);
  }
}

void clearBeat(VlWide<16>& data) {
  std::fill(data.m_storage, data.m_storage + 16, 0U);
}

void driveBeat(VlWide<16>& target, const Beat& beat) {
  for (std::size_t lane = 0; lane < beat.size(); ++lane) {
    target.m_storage[lane * 2U] = static_cast<std::uint32_t>(beat[lane]);
    target.m_storage[lane * 2U + 1U] = static_cast<std::uint32_t>(beat[lane] >> 32U);
  }
}

const HbmRegion& regionFor(const HbmModel& model, HbmRegionKind kind) {
  return kind == HbmRegionKind::x ? model.x : model.a;
}

HbmRegion& regionFor(HbmModel& model, HbmRegionKind kind) {
  return kind == HbmRegionKind::x ? model.x : model.a;
}

HbmRegionKind regionAtAddress(const HbmModel& model, std::uint64_t address) {
  const auto contains = [address](const HbmRegion& region) {
    if (!region.enabled()) return false;
    const std::uint64_t end = region.base + region.size() * kBeatBytes;
    return address >= region.base && address < end;
  };
  if (contains(model.x)) return HbmRegionKind::x;
  if (contains(model.a)) return HbmRegionKind::a;
  throw std::runtime_error("Cuperflow RTL AR 没有落在当前 HBM 的 X/A range");
}

void driveHbm(HbmPort& port, const HbmModel& model) {
  *port.arReady = (model.x.enabled() || model.a.enabled()) &&
      model.bursts.size() < SPMV_CUPERFLOW_MAX_OUTSTANDING_BURSTS_FROZEN;
  *port.rValid = !model.bursts.empty();
  *port.rId = 0;
  *port.rResponse = 0;
  *port.rLast = !model.bursts.empty() && model.bursts.front().remaining == 1U;
  if (!model.bursts.empty()) {
    const HbmRegion& region = regionFor(model, model.bursts.front().region);
    if (region.nextData >= region.size()) {
      throw std::runtime_error("Cuperflow HBM R 游标超过当前 X/A range");
    }
    driveBeat(*port.rData, (*region.beats)[region.begin + region.nextData]);
  } else {
    clearBeat(*port.rData);
  }
}

void acceptAddress(const HbmPort& port, HbmModel& model) {
  if (*port.arSize != 6U || *port.arBurst != 1U ||
      (*port.arAddress & (kBeatBytes - 1U)) != 0U) {
    throw std::runtime_error("Cuperflow RTL 发出了非法 512-bit AXI AR");
  }
  const HbmRegionKind kind = regionAtAddress(model, *port.arAddress);
  HbmRegion& region = regionFor(model, kind);
  if (*port.arAddress != region.base + region.nextIssued * kBeatBytes) {
    throw std::runtime_error("Cuperflow RTL AR 没有连续覆盖当前 X/A range");
  }
  const std::size_t beats = static_cast<std::size_t>(*port.arLength) + 1U;
  if (beats > region.size() - region.nextIssued ||
      ((*port.arAddress & 0xfffU) + beats * kBeatBytes) > 4096U) {
    throw std::runtime_error("Cuperflow RTL AXI burst 越过 range 尾部或 4 KiB 边界");
  }
  region.nextIssued += beats;
  model.bursts.push_back(HbmBurst{kind, beats});
}

void consumeData(HbmModel& model) {
  if (model.bursts.empty()) {
    throw std::runtime_error("Cuperflow RTL R 握手没有对应的 AXI burst");
  }
  HbmBurst& burst = model.bursts.front();
  HbmRegion& region = regionFor(model, burst.region);
  if (region.nextData >= region.size() || burst.remaining == 0U) {
    throw std::runtime_error("Cuperflow RTL R 游标超过当前 X/A range");
  }
  ++region.nextData;
  if (--burst.remaining == 0U) model.bursts.pop_front();
}

void resetHbm(HbmModel& model, const std::vector<Beat>* xBeats, std::size_t xBegin,
              std::size_t xEnd, std::uint64_t xBase, const std::vector<Beat>* aBeats,
              std::size_t aBegin, std::size_t aEnd, std::uint64_t aBase) {
  model.x = HbmRegion{xBeats, xBegin, xEnd, xBase, 0, 0};
  model.a = HbmRegion{aBeats, aBegin, aEnd, aBase, 0, 0};
  model.bursts.clear();
}

std::uint64_t fp64Bits(double value) {
  std::uint64_t bits = 0;
  std::memcpy(&bits, &value, sizeof(bits));
  return bits;
}

std::uint64_t expectedPackageChecksum(const cf::CuperflowPackage& package,
                                      const std::vector<double>& x) {
  std::uint64_t checksum = 0;
  for (std::size_t pc = 0; pc < package.matrixChannels.size(); ++pc) {
    for (const cf::CuperflowGroupARange& group : package.channelGroupARanges[pc]) {
      const std::size_t groupFirstColumn =
          group.sliceGroup * package.sliceGroupSize * package.config.sliceSize;
      for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
        const std::size_t segment = batch * package.sliceGroupCount + group.sliceGroup;
        const auto& ranges = package.channelLaneSliceGroupRanges[pc][segment];
        for (std::size_t lane = 0; lane < kWordsPerBeat; ++lane) {
          for (std::size_t beat = ranges[lane].first; beat < ranges[lane].second; ++beat) {
            const cf::DecodedCuperflowSlot slot =
                cf::decodeSlot(package.matrixChannels[pc][beat][lane]);
            const std::size_t column = groupFirstColumn + slot.localColumn;
            if (column >= x.size()) {
              throw std::runtime_error("RTL golden 列号超出 X 范围");
            }
            checksum ^= fp64Bits(static_cast<double>(slot.value) * x[column]);
          }
        }
      }
    }
  }
  return checksum;
}

std::size_t sequentialXLoadCycles(const cf::CuperflowXRange& range) {
  if (range.markerCount != 0 || range.encodedWordCount != range.elementCount ||
      range.valueCount != range.elementCount) {
    throw std::runtime_error(
        "Cuperflow map ABI v3 的紧凑 X payload 不允许地址 marker 或非顺序 payload");
  }
  const std::size_t beats = range.beatEnd - range.beatBegin;
  const std::size_t expectedBeats = (range.elementCount + kWordsPerBeat - 1U) /
      kWordsPerBeat;
  if (beats != expectedBeats) {
    throw std::runtime_error("Cuperflow 紧凑 X payload 的 beat 数与长度不一致");
  }
  return beats;
}

void writeHtmlText(std::ostream& output, std::string_view value) {
  for (const char character : value) {
    switch (character) {
      case '&': output << "&amp;"; break;
      case '<': output << "&lt;"; break;
      case '>': output << "&gt;"; break;
      case '"': output << "&quot;"; break;
      case '\'': output << "&#39;"; break;
      default: output << character; break;
    }
  }
}

void writeJsonString(std::ostream& output, std::string_view value) {
  output << '"';
  for (const unsigned char character : value) {
    if (character == '"') output << "\\\"";
    else if (character == '\\') output << "\\\\";
    else if (character == '\n') output << "\\n";
    else if (character == '\r') output << "\\r";
    else output << static_cast<char>(character);
  }
  output << '"';
}

template <typename T>
void writeJsonPcArray(std::ostream& output, const std::array<T, kPcCount>& values) {
  output << '[';
  for (std::size_t pc = 0; pc < kPcCount; ++pc) {
    if (pc != 0) output << ',';
    output << values[pc];
  }
  output << ']';
}

void writeJsonPcBoolArray(std::ostream& output,
                          const std::array<bool, kPcCount>& values) {
  output << '[';
  for (std::size_t pc = 0; pc < kPcCount; ++pc) {
    if (pc != 0) output << ',';
    output << (values[pc] ? "true" : "false");
  }
  output << ']';
}

void writeJsonSliceGroups(std::ostream& output, const WorkTiming& work) {
  output << '[';
  for (std::size_t pc = 0; pc < kPcCount; ++pc) {
    if (pc != 0) output << ',';
    if (work.groupActive[pc]) output << work.sliceGroups[pc];
    else output << "null";
  }
  output << ']';
}

void writeTimingJson(std::ostream& output, const std::string& dataset,
                     const std::vector<WorkTiming>& works,
                     const std::vector<CycleRecord>& cycles, std::uint64_t totalABeats,
                     std::uint64_t encodedABeats, std::uint64_t usefulSlots,
                     std::uint64_t physicalSlots, std::uint64_t xSourceBeats,
                     std::uint64_t xWords, std::uint64_t xMarkers,
                     std::uint64_t xWriteCycles, std::size_t sliceGroupCount,
                     std::size_t waveCount, std::size_t batchCount,
                     std::size_t xLoadedGroups,
                     std::uint64_t rtlChecksum, std::uint64_t expectedChecksum) {
  output << "{\"dataset\":";
  writeJsonString(output, dataset);
  output << ",\"schedule\":\"per-pc-map-x-a\",\"pcCount\":16,\"xLoadLanes\":"
      << kXLoadLanes
      << ",\"sliceGroupCount\":" << sliceGroupCount << ",\"waveCount\":" << waveCount
      << ",\"batchCount\":" << batchCount << ",\"mulLatency\":" << kMulLatency
      << ",\"mulII\":" << kMulII << ",\"totalCycles\":"
      << cycles.size() << ",\"totalABeats\":" << totalABeats
      << ",\"encodedABeats\":" << encodedABeats
      << ",\"usefulSlots\":" << usefulSlots
      << ",\"physicalSlots\":" << physicalSlots
      << ",\"xSourceBeats\":" << xSourceBeats << ",\"xWords\":" << xWords
      << ",\"xMarkers\":" << xMarkers << ",\"xWriteCycles\":" << xWriteCycles
      << ",\"xLoadedGroups\":" << xLoadedGroups
      << ",\"rtlChecksum\":" << rtlChecksum
      << ",\"expectedChecksum\":" << expectedChecksum << ",\"works\":[";
  for (std::size_t index = 0; index < works.size(); ++index) {
    const WorkTiming& work = works[index];
    if (index != 0) output << ',';
    output << "{\"index\":" << work.index << ",\"wave\":" << work.wave
        << ",\"batch\":" << work.batch << ",\"sliceGroups\":";
    writeJsonSliceGroups(output, work);
    output << ",\"xLoaded\":";
    writeJsonPcBoolArray(output, work.xLoaded);
    output << ",\"xElements\":";
    writeJsonPcArray(output, work.xElements);
    output << ",\"xWords\":";
    writeJsonPcArray(output, work.xWords);
    output << ",\"xBeats\":";
    writeJsonPcArray(output, work.xBeats);
    output << ",\"xMarkers\":";
    writeJsonPcArray(output, work.xMarkers);
    output << ",\"xWriteCycles\":";
    writeJsonPcArray(output, work.xWriteCycles);
    output << ",\"start\":" << work.start << ",\"xInputBegin\":" << work.xInputBegin
        << ",\"xInputEnd\":" << work.xInputEnd << ",\"xWriteBegin\":"
        << work.xWriteBegin << ",\"xWriteEnd\":" << work.xWriteEnd
        << ",\"xReady\":" << work.xReady << ",\"aRequest\":" << work.aRequest
        << ",\"aBegin\":" << work.aBegin << ",\"aEnd\":" << work.aEnd
        << ",\"mulRequestBegin\":" << work.mulRequestBegin
        << ",\"mulRequestEnd\":" << work.mulRequestEnd
        << ",\"mulResponseBegin\":" << work.mulResponseBegin
        << ",\"mulResponseEnd\":" << work.mulResponseEnd
        << ",\"done\":" << work.done << ",\"aBeats\":";
    writeJsonPcArray(output, work.aBeats);
    output << ",\"usefulSlots\":";
    writeJsonPcArray(output, work.usefulSlots);
    output << ",\"physicalSlots\":";
    writeJsonPcArray(output, work.physicalSlots);
    output << ",\"rtlProductChecksum\":" << work.rtlProductChecksum
        << ",\"expectedProductChecksum\":" << work.expectedProductChecksum << '}';
  }
  output << "],\"cycles\":[";
  for (std::size_t index = 0; index < cycles.size(); ++index) {
    if (index != 0) output << ',';
    const CycleRecord& cycle = cycles[index];
    output << "{\"c\":" << cycle.cycle << ",\"xAr\":" << cycle.xAr
        << ",\"xR\":" << cycle.xR << ",\"aAr\":" << cycle.aAr
        << ",\"aR\":" << cycle.aR << "}";
  }
  output << "]}";
}

void writeTimingReport(const fs::path& path, const std::string& dataset,
                       const std::vector<WorkTiming>& works,
                       const std::vector<CycleRecord>& cycles, std::uint64_t totalABeats,
                       std::uint64_t encodedABeats, std::uint64_t usefulSlots,
                       std::uint64_t physicalSlots, std::uint64_t xSourceBeats,
                       std::uint64_t xWords, std::uint64_t xMarkers,
                       std::uint64_t xWriteCycles, std::size_t sliceGroupCount,
                       std::size_t waveCount, std::size_t batchCount,
                       std::size_t xLoadedGroups,
                       std::uint64_t rtlChecksum, std::uint64_t expectedChecksum) {
  std::ofstream output(path);
  if (!output) throw std::runtime_error("无法写入 Cuperflow RTL HTML: " + path.string());
  output << R"HTML(<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Cuperflow 时序与吞吐报告</title><style>body{margin:0;background:#f3f6f7;color:#17242b;font:14px/1.5 system-ui,sans-serif}header,main,footer{max-width:1380px;margin:auto;padding:20px}header{background:#fff;border-bottom:1px solid #d5dfe2;max-width:none;padding-left:max(20px,calc((100vw - 1380px)/2));padding-right:max(20px,calc((100vw - 1380px)/2))}h1{margin:0;font-size:24px}.sub{color:#64727a;margin-top:5px}.metrics{display:grid;grid-template-columns:repeat(6,minmax(130px,1fr));gap:8px}.metric,section{background:#fff;border:1px solid #d5dfe2;border-radius:5px}.metric{padding:11px 12px}.metric span,.metric small{display:block;color:#64727a}.metric strong{display:block;font-size:20px;margin:4px 0}.timeline{overflow:auto;background:#fff;border:1px solid #d5dfe2}.track{position:relative;min-width:900px;height:calc(var(--rows) * 34px + 30px);background:repeating-linear-gradient(to right,transparent 0,transparent calc(var(--step) - 1px),#edf1f2 calc(var(--step) - 1px),#edf1f2 var(--step))}.bar{position:absolute;height:22px;border-radius:3px;color:#fff;padding:2px 5px;font-size:11px;overflow:hidden;white-space:nowrap}table{width:100%;border-collapse:collapse;white-space:nowrap}th,td{padding:7px 9px;border-bottom:1px solid #e7ebef;text-align:right}th:first-child,td:first-child{text-align:left}section{border:0;background:transparent;margin:20px 0}h2{font-size:17px;margin:0 0 10px}.pass{color:#17653a;font-weight:650}@media(max-width:900px){.metrics{grid-template-columns:repeat(3,minmax(130px,1fr))}}@media(max-width:540px){.metrics{grid-template-columns:repeat(2,minmax(130px,1fr))}header,main,footer{padding-left:12px;padding-right:12px}}</style></head><body><header><h1>Cuperflow 时序与吞吐报告</h1><div class="sub">)HTML";
  writeHtmlText(output, dataset);
  output << R"HTML( · Verilator RTL simulation · 16 PC 独立 map → X → A · 每 PC 8-lane 连续 X 装填 · FP64</div></header><main><section><div class="metrics" id="metrics"></div></section><section><h2>全作业 AXI 时间线</h2><div class="timeline"><div class="track" id="track"></div></div></section><section><h2>PC 工作汇总</h2><div class="timeline"><table><thead><tr><th>work</th><th>PC → sliceGroup</th><th>周期区间</th><th>X</th><th>X HBM / A 首个 R</th><th>物理 FMUL 槽位</th><th>golden</th></tr></thead><tbody id="rows"></tbody></table></div></section><footer>本报告由 VSpmvCuperflowInputTop 的真实 Verilator 时钟推进生成；每个 PC 独立访问自己的 map、X 与 A 区间。AXI AR/R、完成信号和 RTL productChecksum 均来自 RTL 端口；连续 X 装填周期仅按 map 的 payload beat 数统计。</footer><script>const timingTrace=)HTML";
  writeTimingJson(output, dataset, works, cycles, totalABeats, encodedABeats, usefulSlots,
      physicalSlots, xSourceBeats, xWords, xMarkers, xWriteCycles, sliceGroupCount,
      waveCount, batchCount, xLoadedGroups,
      rtlChecksum, expectedChecksum);
  output << R"HTML(;
const t=timingTrace;const m=document.querySelector('#metrics');const items=[['RTL cycles',t.totalCycles],['sliceGroups',t.sliceGroupCount],['A range beats',t.totalABeats+' / package '+t.encodedABeats],['physical slots',t.physicalSlots],['useful slots',t.usefulSlots],['X source beats',t.xSourceBeats],['sequential X load cycles',t.xWriteCycles],['address markers',t.xMarkers],['golden',t.rtlChecksum===t.expectedChecksum?'PASS':'FAIL']];for(const item of items){const d=document.createElement('div');d.className='metric';d.innerHTML='<span>'+item[0]+'</span><strong>'+item[1]+'</strong><small>per-PC RTL</small>';m.appendChild(d)}const track=document.querySelector('#track');track.style.setProperty('--rows',t.works.length);track.style.setProperty('--step',Math.max(6,Math.min(18,1200/Math.max(1,t.totalCycles)))+'px');const colors=['#2c6d9a','#17706d'];for(const [i,w] of t.works.entries()){const y=30+i*34;const add=(start,end,color,label)=>{if(end<=start)return;const b=document.createElement('div');b.className='bar';b.style.left=(start/t.totalCycles*100)+'%';b.style.width=Math.max(.15,(end-start)/t.totalCycles*100)+'%';b.style.top=y+'px';b.style.background=color;b.textContent=label;track.appendChild(b)};const loadedPcs=w.xLoaded.flatMap((loaded,pc)=>loaded?[pc]:[]);const hasA=w.aBeats.some((beats)=>beats>0);if(loadedPcs.length!==0)add(w.xInputBegin,w.xInputEnd,colors[0],'X HBM P'+loadedPcs.join(',P'));if(hasA)add(w.aBegin,w.aEnd,colors[1],'A HBM')}for(const w of t.works){const mapping=w.sliceGroups.map((group,pc)=>group===null?'P'+pc+': groups':'P'+pc+': G'+group).join(' ');const xInfo=w.xLoaded.some(Boolean)?w.xLoaded.flatMap((loaded,pc)=>loaded?['P'+pc+': '+w.xBeats[pc]+' beats']:[]).join(' '):'none';const r=document.createElement('tr');for(const v of [w.index,mapping,'c'+w.start+'..c'+w.done,xInfo,'c'+w.xInputEnd+' / c'+w.aBegin,w.physicalSlots.reduce((a,b)=>a+b,0),w.rtlProductChecksum===w.expectedProductChecksum?'PASS':'FAIL']){const c=document.createElement('td');c.textContent=v;r.appendChild(c)}document.querySelector('#rows').appendChild(r)}</script></main></body></html>)HTML";
}

fs::path constructionRoot() {
  std::error_code error;
  const fs::path executable = fs::read_symlink("/proc/self/exe", error);
  if (error || executable.empty()) throw std::runtime_error("无法定位 Cuperflow RTL host");
  const fs::path root = executable.parent_path().parent_path().parent_path();
  if (!fs::is_regular_file(root / "profile.env")) {
    throw std::runtime_error("Cuperflow RTL host 未运行在正式 construction 中");
  }
  return root;
}

fs::path reportDirectory(const std::string& dataset) {
  const auto now = std::chrono::system_clock::now().time_since_epoch();
  const auto timestamp = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
  const fs::path root = constructionRoot() / "runtime" / dataset;
  const fs::path run = root / (std::to_string(timestamp) + "-" +
      std::to_string(static_cast<long long>(getpid())));
  fs::create_directories(run);
  return run;
}

void updateLatest(const fs::path& run) {
  const fs::path root = run.parent_path();
  const fs::path temporary = root / (".latest-" + std::to_string(getpid()));
  std::error_code error;
  fs::remove(temporary, error);
  fs::create_directory_symlink(run.filename(), temporary, error);
  if (error) throw std::runtime_error("无法创建 Cuperflow RTL latest 报告链接");
  fs::rename(temporary, root / "latest", error);
  if (error) {
    fs::remove(root / "latest", error);
    error.clear();
    fs::rename(temporary, root / "latest", error);
  }
  if (error) throw std::runtime_error("无法更新 Cuperflow RTL latest 报告链接");
}

}  // namespace

int runCuperflowTiming(const std::string& requestedDataset) {
  const std::string requested = requestedDataset.empty() ? "n512" : requestedDataset;
  if (requested == "--list") {
    std::cout << "Cuperflow RTL host: make -C accelerator-sim/spmv run "
              << "mainargs=<dataset|synthetic-extreme-x-span|synthetic-three-islands-x-span>\n";
    return 0;
  }
  const bool syntheticExtremeSparseSpan = requested == "synthetic-extreme-x-span";
  const bool syntheticThreeIslandSpan = requested == "synthetic-three-islands-x-span";
  const bool syntheticDataset = syntheticExtremeSparseSpan || syntheticThreeIslandSpan;
  const fs::path datasetPath = syntheticDataset ? fs::path(requested) : findDataset(requested);
  CsrMatrix matrix;
  std::vector<double> x;
  if (syntheticExtremeSparseSpan) {
    makeExtremeSparseXSpanDataset(matrix, x);
  } else if (syntheticThreeIslandSpan) {
    makeThreeIslandXSpanDataset(matrix, x);
  } else {
    matrix = loadMatrix(datasetPath);
    x = readArray<double>(datasetPath / "b.txt");
    if (x.size() != matrix.columns) throw std::runtime_error("b.txt 长度与矩阵列数不一致");
  }

  const cf::CuperflowPackage package = cf::encode(matrix, cf::CuperflowConfig{});
  const cf::CuperflowVectorPackage vectorPackage = cf::encodeVector(x, package);
  if (package.config.hbmChannelCount != kPcCount || vectorPackage.channelXRanges.size() != kPcCount ||
      package.sliceGroupCount == 0) {
    throw std::runtime_error("Cuperflow RTL host 当前要求 16 PC 和非空 sliceGroup");
  }
  const GoldenResult golden = computeGolden(matrix, x);

  VerilatedContext context;
  context.commandArgs(0, static_cast<char**>(nullptr));
  VSpmvCuperflowInputTop dut(&context);
  const auto hbmPortsArray = hbmPorts(dut);
  for (const HbmPort& port : hbmPortsArray) {
    *port.arReady = 0;
    *port.rValid = 0;
    *port.rId = 0;
    *port.rResponse = 0;
    *port.rLast = 0;
    clearBeat(*port.rData);
  }
  dut.io_start = 0;
  dut.clock = 0;
  dut.reset = 1;
  dut.eval();
  for (unsigned edge = 0; edge < 2; ++edge) {
    dut.clock = 1;
    dut.eval();
    dut.clock = 0;
    dut.eval();
  }
  dut.reset = 0;

  std::vector<std::vector<Beat>> xChannels(kPcCount);
  std::array<HbmModel, kPcCount> models;
  for (std::size_t pc = 0; pc < kPcCount; ++pc) {
    xChannels[pc].resize(vectorPackage.channelHbmBeats[pc].size());
    for (std::size_t beat = 0; beat < xChannels[pc].size(); ++beat) {
      xChannels[pc][beat] = vectorPackage.channelHbmBeats[pc][beat];
    }
    resetHbm(models[pc],
        xChannels[pc].empty() ? nullptr : &xChannels[pc],
        0, xChannels[pc].size(),
        SPMV_CUPERFLOW_HBM_BASE_FROZEN,
        package.matrixChannels[pc].empty() ? nullptr : &package.matrixChannels[pc],
        0, package.matrixChannels[pc].size(),
        SPMV_CUPERFLOW_HBM_BASE_FROZEN + SPMV_CUPERFLOW_X_REGION_BYTES_FROZEN);
  }

  const std::uint64_t expectedChecksum = expectedPackageChecksum(package, x);
  std::vector<WorkTiming> works(1);
  WorkTiming& wholeJob = works[0];
  wholeJob.index = 0;
  wholeJob.expectedProductChecksum = expectedChecksum;

  std::uint64_t usefulSlots = 0;
  std::uint64_t physicalSlots = 0;
  std::uint64_t xSourceBeats = 0;
  std::uint64_t xWords = 0;
  std::uint64_t xMarkers = 0;
  std::uint64_t xWriteCyclesTotal = 0;
  std::size_t xLoadedGroups = 0;
  for (std::size_t pc = 0; pc < kPcCount; ++pc) {
    for (const cf::CuperflowXRange& range : vectorPackage.channelXRanges[pc]) {
      ++xLoadedGroups;
      xSourceBeats += range.beatEnd - range.beatBegin;
      xWords += range.encodedWordCount;
      xMarkers += range.markerCount;
      const std::size_t loadCycles = sequentialXLoadCycles(range);
      xWriteCyclesTotal += loadCycles;
      wholeJob.xLoaded[pc] = true;
      wholeJob.xElements[pc] += range.elementCount;
      wholeJob.xWords[pc] += range.encodedWordCount;
      wholeJob.xBeats[pc] += range.beatEnd - range.beatBegin;
      wholeJob.xMarkers[pc] += range.markerCount;
      wholeJob.xWriteCycles[pc] += loadCycles;
    }
    for (const cf::CuperflowGroupARange& group : package.channelGroupARanges[pc]) {
      physicalSlots += static_cast<std::uint64_t>(group.aBeats) * kWordsPerBeat;
      wholeJob.aBeats[pc] += group.aBeats;
      wholeJob.physicalSlots[pc] += static_cast<std::uint64_t>(group.aBeats) * kWordsPerBeat;
    }
    for (std::uint8_t entryMask : package.matrixEntryMasks[pc]) {
      for (std::size_t lane = 0; lane < kWordsPerBeat; ++lane) {
        wholeJob.usefulSlots[pc] += (entryMask >> lane) & 1U;
      }
    }
  }
  usefulSlots = package.stats.matrixSlots;
  std::vector<CycleRecord> cycles;
  bool started = false;
  bool completed = false;
  bool sawX = false;
  bool sawA = false;
  for (std::uint64_t localCycle = 0; localCycle < 20000000ULL; ++localCycle) {
    dut.io_start = !started ? 1 : 0;
    for (std::size_t pc = 0; pc < kPcCount; ++pc) {
      driveHbm(const_cast<HbmPort&>(hbmPortsArray[pc]), models[pc]);
    }
    dut.eval();
    CycleRecord record;
    record.cycle = cycles.size();
    std::array<bool, kPcCount> arFire{};
    std::array<bool, kPcCount> rFire{};
    for (std::size_t pc = 0; pc < kPcCount; ++pc) {
      arFire[pc] = *hbmPortsArray[pc].arValid && *hbmPortsArray[pc].arReady;
      rFire[pc] = *hbmPortsArray[pc].rValid && *hbmPortsArray[pc].rReady;
      if (arFire[pc]) {
        const HbmRegionKind kind = regionAtAddress(models[pc],
            *hbmPortsArray[pc].arAddress);
        if (kind == HbmRegionKind::x) {
          record.xAr |= static_cast<std::uint16_t>(1U << pc);
        } else {
          record.aAr |= static_cast<std::uint16_t>(1U << pc);
        }
      }
      if (rFire[pc]) {
        if (models[pc].bursts.empty()) {
          throw std::runtime_error("Cuperflow RTL R 握手时 HBM burst 队列为空");
        }
        if (models[pc].bursts.front().region == HbmRegionKind::x) {
          record.xR |= static_cast<std::uint16_t>(1U << pc);
        } else {
          record.aR |= static_cast<std::uint16_t>(1U << pc);
        }
      }
    }
    if (record.xR != 0) {
      if (!sawX) {
        wholeJob.xInputBegin = record.cycle;
        wholeJob.xWriteBegin = record.cycle;
        sawX = true;
      }
      wholeJob.xInputEnd = record.cycle + 1U;
      wholeJob.xWriteEnd = record.cycle + 1U;
    }
    if (record.aAr != 0 && wholeJob.aRequest == 0) {
      wholeJob.aRequest = record.cycle;
    }
    if (record.aR != 0) {
      if (!sawA) {
        wholeJob.aBegin = record.cycle;
        sawA = true;
      }
      wholeJob.aEnd = record.cycle + 1U;
    }
    for (std::size_t pc = 0; pc < kPcCount; ++pc) {
      if (arFire[pc]) acceptAddress(hbmPortsArray[pc], models[pc]);
    }
    dut.clock = 1;
    dut.eval();
    dut.clock = 0;
    dut.eval();
    for (std::size_t pc = 0; pc < kPcCount; ++pc) {
      if (rFire[pc]) consumeData(models[pc]);
    }
    if (!started) {
      started = true;
      wholeJob.start = record.cycle;
    }
    cycles.push_back(record);
    if (dut.io_done != 0) {
      wholeJob.done = record.cycle;
      wholeJob.rtlProductChecksum = dut.io_productChecksum;
      completed = true;
      break;
    }
  }
  if (!completed) {
    throw std::runtime_error("Cuperflow RTL 超过 20000000 周期未完成");
  }
  const std::uint64_t rtlChecksum = wholeJob.rtlProductChecksum;
  if (rtlChecksum != expectedChecksum) {
    throw std::runtime_error("Cuperflow RTL productChecksum 与 golden 不一致");
  }
  const std::size_t waveCount = 1;
  const std::uint64_t totalABeats = package.stats.totalMatrixBeats;
  (void)xLoadedGroups;
  const fs::path run = reportDirectory(datasetPath.filename().string());
  writeTimingReport(run / "performance.html", datasetPath.filename().string(), works, cycles,
      totalABeats, package.stats.totalMatrixBeats, usefulSlots, physicalSlots, xSourceBeats,
      xWords, xMarkers, xWriteCyclesTotal, package.sliceGroupCount, waveCount,
      package.stats.batchCount, xLoadedGroups, rtlChecksum, expectedChecksum);
  writeTimingReport(run / "cuperflow-timing.html", datasetPath.filename().string(), works, cycles,
      totalABeats, package.stats.totalMatrixBeats, usefulSlots, physicalSlots, xSourceBeats,
      xWords, xMarkers, xWriteCyclesTotal, package.sliceGroupCount, waveCount,
      package.stats.batchCount, xLoadedGroups, rtlChecksum, expectedChecksum);
  updateLatest(run);

  const double utilization = physicalSlots == 0 ? 0.0 :
      100.0 * static_cast<double>(usefulSlots) / static_cast<double>(physicalSlots);
  std::cout << "[spmv-cuperflow-rtl] Verilator RTL simulation dataset=" << datasetPath
      << " order=map-x-a groups=" << package.sliceGroupCount
      << " batches=" << package.stats.batchCount << " works=" << works.size()
      << " cycles=" << cycles.size() << " a_beats=" << totalABeats
      << " physical_slots=" << physicalSlots << " useful_slots=" << usefulSlots
      << " useful_utilization=" << std::fixed << std::setprecision(3) << utilization << "%"
      << " rtl_checksum=0x" << std::hex << rtlChecksum << " golden_checksum=0x"
      << expectedChecksum << std::dec << '\n';
  std::cout << "[spmv-cuperflow-rtl] golden_output_hash=0x" << std::hex << golden.bitHash
      << std::dec << " performance=" << (run / "performance.html")
      << " timing=" << (run / "cuperflow-timing.html") << '\n';
  return 0;
}

}  // namespace accelerator_sim::spmv

#endif
