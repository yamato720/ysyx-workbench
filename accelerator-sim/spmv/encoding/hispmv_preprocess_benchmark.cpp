#include "../golden.hpp"
#include "cuperflow/cuperflow.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <numeric>
#include <stdexcept>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

namespace fs = std::filesystem;
namespace accelerator_sim::spmv::encoding::hispmv {
namespace {

constexpr std::size_t kNumChannels = 16;
constexpr std::size_t kNumPes = 128;
constexpr std::size_t kPesPerChannel = 8;
constexpr std::size_t kInterleaveDistance = 5;
constexpr std::size_t kPadding = 1;
constexpr std::size_t kDefaultWindow = 8192;
constexpr std::size_t kMaxRowsPerPe = 3U * 4096U;
constexpr std::size_t kDefaultDepth = kNumPes * kMaxRowsPerPe;

struct HiSpmvConfig {
  std::size_t window = kDefaultWindow;
  std::size_t depth = kDefaultDepth;
  std::size_t sharedRowLimit = kDefaultDepth / 2U;
};

struct RowWorkload {
  std::size_t localRow = 0;
  std::uint32_t nonzeros = 0;
};

struct TileWorkload {
  std::vector<RowWorkload> rows;
  std::uint64_t nonzeros = 0;
};

struct TileSchedule {
  std::size_t baselineMaxLoad = 0;
  std::size_t noShareMaxLoad = 0;
  std::size_t sharedMaxLoad = 0;
  std::size_t treeMaxLoad = 0;
  std::size_t noShareSize = 0;
  std::size_t sharedSize = 0;
  std::size_t treeSize = 0;
  std::size_t sharedRowCount = 0;
  std::uint64_t sharedRowPadding = 0;
  std::uint64_t nonzeros = 0;
};

struct HiSpmvSummary {
  std::size_t tileRows = 0;
  std::size_t tileColumns = 0;
  std::size_t tileCount = 0;
  std::size_t nonemptyTileCount = 0;
  std::uint64_t nonzeros = 0;
  std::uint64_t baselineScheduledSlots = 0;
  std::uint64_t treeLogicalScheduledSlots = 0;
  std::uint64_t treePhysicalSlots = 0;
  std::uint64_t selectedPhysicalSlots = 0;
  std::uint64_t noShareRunLength = 0;
  std::uint64_t sharedRunLength = 0;
  std::uint64_t treeRunLength = 0;
  std::uint64_t sharedRowCount = 0;
  std::uint64_t sharedRowPadding = 0;
  std::uint64_t noSharePadding = 0;
  std::uint64_t sharedPadding = 0;
  std::uint64_t treePadding = 0;
  double baselineImbalance = 0.0;
  double treeImbalance = 0.0;
  int improvementPercent = 0;
  std::string selectedMode;
};

std::size_t divideRoundedUp(std::size_t value, std::size_t divisor) {
  return value / divisor + static_cast<std::size_t>(value % divisor != 0U);
}

template <typename T>
std::vector<T> readArray(const fs::path& path) {
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

fs::path dataRoot() {
#ifdef ACCELERATOR_SIM_DEFAULT_DATA_ROOT
  return fs::path(ACCELERATOR_SIM_DEFAULT_DATA_ROOT);
#else
  return fs::path("../data");
#endif
}

bool isDataset(const fs::path& path) {
  return fs::is_regular_file(path / "row_ptr.txt") &&
      fs::is_regular_file(path / "col_idx.txt") &&
      fs::is_regular_file(path / "values.txt");
}

fs::path findDataset(const std::string& requested) {
  const fs::path direct(requested);
  if (isDataset(direct)) {
    return direct;
  }

  const fs::path root = dataRoot();
  const std::vector<fs::path> candidates = {
      root / "generated" / "cgsolver" / requested,
      root / "suitesparse" / requested,
      root / "suitesparse" / "Schmid" / requested,
      root / "suitesparse" / "Schmid" / "csr" / requested,
  };
  for (const fs::path& candidate : candidates) {
    if (isDataset(candidate)) {
      return candidate;
    }
  }
  const fs::path suitesparseRoot = root / "suitesparse";
  if (fs::is_directory(suitesparseRoot)) {
    for (const fs::directory_entry& entry : fs::recursive_directory_iterator(
             suitesparseRoot, fs::directory_options::skip_permission_denied)) {
      if (entry.is_directory() && entry.path().filename() == requested &&
          isDataset(entry.path())) {
        return entry.path();
      }
    }
  }
  throw std::runtime_error("找不到数据集目录: " + requested);
}

CsrMatrix loadMatrix(const fs::path& dataset) {
  const std::vector<std::uint64_t> rowPointers =
      readArray<std::uint64_t>(dataset / "row_ptr.txt");
  const std::vector<std::uint64_t> columns =
      readArray<std::uint64_t>(dataset / "col_idx.txt");
  const std::vector<double> values = readArray<double>(dataset / "values.txt");
  if (rowPointers.size() < 2U || rowPointers.front() != 0U ||
      rowPointers.back() != columns.size() || columns.size() != values.size()) {
    throw std::runtime_error("CSR row pointer、column、value 数量不一致");
  }

  CsrMatrix matrix;
  matrix.rows = rowPointers.size() - 1U;
  matrix.columns = matrix.rows;
  matrix.rowPointers = rowPointers;
  matrix.values = values;
  matrix.columnIndices.reserve(columns.size());
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    if (rowPointers[row] > rowPointers[row + 1U]) {
      throw std::runtime_error("CSR row pointer 必须单调不减");
    }
  }
  for (std::uint64_t column : columns) {
    if (column >= matrix.columns || column > std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error("CSR column index 超出方阵范围");
    }
    matrix.columnIndices.push_back(static_cast<std::uint32_t>(column));
  }
  return matrix;
}

std::vector<TileWorkload> tileWorkloads(const CsrMatrix& matrix,
                                        const HiSpmvConfig& config,
                                        std::size_t& tileRows,
                                        std::size_t& tileColumns) {
  tileRows = divideRoundedUp(matrix.rows, config.depth);
  tileColumns = divideRoundedUp(matrix.columns, config.window);
  if (tileRows != 0U && tileColumns >
      std::numeric_limits<std::size_t>::max() / tileRows) {
    throw std::overflow_error("HiSpMV tile 数量溢出");
  }
  std::vector<TileWorkload> tiles(tileRows * tileColumns);
  std::vector<std::pair<std::size_t, std::uint32_t>> rowTileCounts;

  // 原始 HiSpMV 会为每个 tile 展开完整 row pointer。这里保留相同的 row/tile
  // 统计语义，只存储实际非空的 row-tile，避免百万级矩阵生成数百 MB 的空指针表。
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    rowTileCounts.clear();
    const std::size_t begin = static_cast<std::size_t>(matrix.rowPointers[row]);
    const std::size_t end = static_cast<std::size_t>(matrix.rowPointers[row + 1U]);
    for (std::size_t index = begin; index < end; ++index) {
      const std::size_t column = matrix.columnIndices[index];
      const std::size_t columnTile = column / config.window;
      auto existing = std::find_if(
          rowTileCounts.begin(), rowTileCounts.end(),
          [columnTile](const auto& item) { return item.first == columnTile; });
      if (existing == rowTileCounts.end()) {
        rowTileCounts.emplace_back(columnTile, 1U);
      } else {
        if (existing->second == std::numeric_limits<std::uint32_t>::max()) {
          throw std::overflow_error("HiSpMV row-tile nonzero 数量溢出");
        }
        ++existing->second;
      }
    }
    const std::size_t rowTile = row / config.depth;
    const std::size_t localRow = row % config.depth;
    for (const auto& item : rowTileCounts) {
      if (item.first >= tileColumns) {
        throw std::logic_error("HiSpMV column tile 超出范围");
      }
      TileWorkload& tile = tiles[rowTile * tileColumns + item.first];
      tile.rows.push_back(RowWorkload{localRow, item.second});
      tile.nonzeros += item.second;
    }
  }
  return tiles;
}

template <typename Loads>
std::size_t maximumLoad(const Loads& loads) {
  std::size_t result = 0;
  for (const auto& pe : loads) {
    for (const auto value : pe) {
      result = std::max(result, static_cast<std::size_t>(value));
    }
  }
  return result;
}

std::size_t selectedSlot(const std::array<std::array<std::size_t, kInterleaveDistance>, kNumPes>& loads,
                         std::size_t pe) {
  std::size_t selected = 0;
  for (std::size_t slot = 1; slot < kInterleaveDistance; ++slot) {
    if (loads[pe][slot] < loads[pe][selected]) {
      selected = slot;
    }
  }
  return selected;
}

std::vector<RowWorkload> sortedRows(const TileWorkload& tile) {
  std::vector<RowWorkload> rows = tile.rows;
  std::stable_sort(rows.begin(), rows.end(), [](const RowWorkload& lhs,
                                                const RowWorkload& rhs) {
    return lhs.nonzeros != rhs.nonzeros ? lhs.nonzeros > rhs.nonzeros :
        lhs.localRow < rhs.localRow;
  });
  return rows;
}

std::size_t ceilRowShare(std::uint32_t rowNonzeros) {
  return divideRoundedUp(static_cast<std::size_t>(rowNonzeros), kNumPes);
}

TileSchedule scheduleTile(const TileWorkload& tile, const HiSpmvConfig& config) {
  TileSchedule result;
  result.nonzeros = tile.nonzeros;
  const std::vector<RowWorkload> rows = sortedRows(tile);

  std::array<std::size_t, kNumPes> baselineLoads{};
  for (const RowWorkload& row : rows) {
    baselineLoads[row.localRow % kNumPes] += row.nonzeros;
  }
  result.baselineMaxLoad = *std::max_element(baselineLoads.begin(), baselineLoads.end());

  std::array<std::array<std::size_t, kInterleaveDistance>, kNumPes> noShareLoads{};
  for (const RowWorkload& row : rows) {
    const std::size_t pe = row.localRow % kNumPes;
    noShareLoads[pe][selectedSlot(noShareLoads, pe)] += row.nonzeros;
  }
  result.noShareMaxLoad = maximumLoad(noShareLoads);

  std::array<std::size_t, kNumPes> balancedLoads = baselineLoads;
  std::uint64_t remaining = tile.nonzeros;
  std::size_t maxLoad = result.baselineMaxLoad;
  std::size_t extraCycles = 0;
  std::unordered_set<std::size_t> sharedRows;
  double imbalance = tile.nonzeros == 0U ? 0.0 :
      static_cast<double>(maxLoad * kNumPes - tile.nonzeros) /
          static_cast<double>(tile.nonzeros);

  const std::size_t candidateCount = std::min(config.sharedRowLimit, rows.size());
  for (std::size_t index = 0; index < candidateCount; ++index) {
    const RowWorkload& row = rows[index];
    if (maxLoad < 2U) {
      break;
    }
    auto candidateLoads = balancedLoads;
    candidateLoads[row.localRow % kNumPes] -= row.nonzeros;
    const std::size_t candidateMax = *std::max_element(
        candidateLoads.begin(), candidateLoads.end());
    const std::uint64_t candidateTotal = remaining - row.nonzeros;
    const double candidateImbalance = candidateTotal == 0U ? 0.0 :
        static_cast<double>(candidateMax * kNumPes - candidateTotal) /
            static_cast<double>(candidateTotal);
    if ((imbalance - candidateImbalance > 0.0) || index < 2U) {
      balancedLoads = candidateLoads;
      remaining = candidateTotal;
      maxLoad = candidateMax;
      extraCycles += ceilRowShare(row.nonzeros);
      sharedRows.insert(row.localRow);
    }
    if (std::abs(imbalance - candidateImbalance) <= 0.01 && candidateImbalance < 2.0) {
      break;
    }
    imbalance = candidateImbalance;
  }

  result.sharedRowCount = sharedRows.size();
  result.sharedMaxLoad = maxLoad;
  for (const RowWorkload& row : rows) {
    if (sharedRows.count(row.localRow) != 0U) {
      result.sharedRowPadding +=
          static_cast<std::uint64_t>(ceilRowShare(row.nonzeros) * kNumPes) - row.nonzeros;
    }
  }

  std::array<std::array<std::size_t, kInterleaveDistance>, kNumPes> sharedLoads{};
  for (const RowWorkload& row : rows) {
    if (sharedRows.count(row.localRow) == 0U) {
      continue;
    }
    const std::size_t load = ceilRowShare(row.nonzeros);
    for (std::size_t pe = 0; pe < kNumPes; ++pe) {
      sharedLoads[pe][selectedSlot(sharedLoads, pe)] += load;
    }
  }
  for (const RowWorkload& row : rows) {
    if (sharedRows.count(row.localRow) != 0U) {
      continue;
    }
    const std::size_t pe = row.localRow % kNumPes;
    sharedLoads[pe][selectedSlot(sharedLoads, pe)] += row.nonzeros;
  }
  result.sharedMaxLoad = maximumLoad(sharedLoads);
  result.treeMaxLoad = maxLoad + extraCycles;
  result.noShareSize = (result.noShareMaxLoad + kPadding) * kInterleaveDistance;
  result.sharedSize = (result.sharedMaxLoad + kPadding) * kInterleaveDistance;
  // 上游 prepareAmtx3 的 tree-adder layout 不再展开 II_DIST 个 interleave slot；
  // tileSizes3 直接使用 maxLoad + extraCycles + PADDING。
  result.treeSize = result.treeMaxLoad + kPadding;
  return result;
}

HiSpmvSummary summarizeHiSpmv(const std::vector<TileWorkload>& tiles,
                              std::size_t tileRows, std::size_t tileColumns,
                              const HiSpmvConfig& config) {
  HiSpmvSummary summary;
  summary.tileRows = tileRows;
  summary.tileColumns = tileColumns;
  summary.tileCount = tiles.size();
  std::uint64_t baselineCapacity = 0;
  std::uint64_t treeLogicalCapacity = 0;
  std::uint64_t treePhysicalCapacity = 0;
  for (const TileWorkload& tile : tiles) {
    const TileSchedule schedule = scheduleTile(tile, config);
    summary.nonzeros += schedule.nonzeros;
    summary.nonemptyTileCount += schedule.nonzeros != 0U;
    summary.baselineScheduledSlots +=
        static_cast<std::uint64_t>(schedule.baselineMaxLoad + kPadding) * kNumPes;
    summary.noShareRunLength += schedule.noShareSize;
    summary.sharedRunLength += schedule.sharedSize;
    summary.treeRunLength += schedule.treeSize;
    summary.sharedRowCount += schedule.sharedRowCount;
    summary.sharedRowPadding += schedule.sharedRowPadding;
    baselineCapacity += static_cast<std::uint64_t>(schedule.noShareSize) * kNumPes;
    treeLogicalCapacity += static_cast<std::uint64_t>(
        schedule.treeMaxLoad + kPadding) * kNumPes;
    treePhysicalCapacity += static_cast<std::uint64_t>(schedule.treeSize) * kNumPes;
  }
  summary.noSharePadding = baselineCapacity >= summary.nonzeros ?
      baselineCapacity - summary.nonzeros : 0U;
  summary.sharedPadding = summary.sharedRunLength * kNumPes >= summary.nonzeros ?
      summary.sharedRunLength * kNumPes - summary.nonzeros : 0U;
  summary.treePadding = treePhysicalCapacity >= summary.nonzeros ?
      treePhysicalCapacity - summary.nonzeros : 0U;
  summary.baselineImbalance = summary.nonzeros == 0U ? 0.0 :
      static_cast<double>(summary.baselineScheduledSlots - summary.nonzeros) /
          static_cast<double>(summary.nonzeros);
  summary.treeLogicalScheduledSlots = treeLogicalCapacity;
  summary.treePhysicalSlots = treePhysicalCapacity;
  summary.treeImbalance = summary.nonzeros == 0U ? 0.0 :
      static_cast<double>(summary.treeLogicalScheduledSlots - summary.nonzeros) /
          static_cast<double>(summary.nonzeros);
  summary.improvementPercent = summary.baselineImbalance <= -1.0 ? 0 :
      static_cast<int>((summary.baselineImbalance - summary.treeImbalance) * 100.0 /
                       (1.0 + summary.baselineImbalance));
  summary.selectedMode = summary.improvementPercent < 10 ?
      "no-row-sharing" : "row-sharing+tree-adder";
  summary.selectedPhysicalSlots = (summary.selectedMode == "no-row-sharing" ?
      summary.noShareRunLength : summary.treeRunLength) * kNumPes;
  return summary;
}

double milliseconds(std::chrono::steady_clock::time_point begin,
                    std::chrono::steady_clock::time_point end) {
  return std::chrono::duration<double, std::milli>(end - begin).count();
}

}  // namespace
}  // namespace accelerator_sim::spmv::encoding::hispmv

int main(int argc, char** argv) {
  using accelerator_sim::spmv::CsrMatrix;
  using accelerator_sim::spmv::encoding::cuperflow::CuperflowPackage;
  using accelerator_sim::spmv::encoding::cuperflow::encode;
  using accelerator_sim::spmv::encoding::hispmv::HiSpmvConfig;
  using accelerator_sim::spmv::encoding::hispmv::dataRoot;
  using accelerator_sim::spmv::encoding::hispmv::findDataset;
  using accelerator_sim::spmv::encoding::hispmv::loadMatrix;
  using accelerator_sim::spmv::encoding::hispmv::milliseconds;
  using accelerator_sim::spmv::encoding::hispmv::summarizeHiSpmv;
  using accelerator_sim::spmv::encoding::hispmv::tileWorkloads;

  try {
    const std::string requested = argc > 1 ? argv[1] : "thermal2";
    HiSpmvConfig config;
    if (argc > 2) {
      config.window = std::stoull(argv[2]);
    }
    if (argc > 3) {
      config.depth = std::stoull(argv[3]);
      config.sharedRowLimit = config.depth / 2U;
    }
    if (config.window == 0U || config.depth == 0U ||
        config.window % 16U != 0U || config.depth > std::numeric_limits<std::uint32_t>::max()) {
      throw std::invalid_argument("HiSpMV window 必须为 16 的倍数，depth 必须适合 32-bit local row");
    }

    const fs::path dataset = findDataset(requested);
    const auto loadBegin = std::chrono::steady_clock::now();
    const CsrMatrix matrix = loadMatrix(dataset);
    const auto loadEnd = std::chrono::steady_clock::now();

    const auto tileBegin = std::chrono::steady_clock::now();
    std::size_t tileRows = 0;
    std::size_t tileColumns = 0;
    const auto tiles = tileWorkloads(matrix, config, tileRows, tileColumns);
    const auto tileEnd = std::chrono::steady_clock::now();
    const auto scheduleBegin = std::chrono::steady_clock::now();
    const auto summary = summarizeHiSpmv(tiles, tileRows, tileColumns, config);
    const auto scheduleEnd = std::chrono::steady_clock::now();

    const auto cuperBegin = std::chrono::steady_clock::now();
    const CuperflowPackage cuperflow = encode(matrix);
    const auto cuperEnd = std::chrono::steady_clock::now();

    const auto percent = [](std::uint64_t value, std::uint64_t total) {
      return total == 0U ? 0.0 : 100.0 * static_cast<double>(value) /
          static_cast<double>(total);
    };
    const std::uint64_t cuperCapacity = cuperflow.stats.totalMatrixBeats *
        accelerator_sim::spmv::encoding::cuperflow::kLanesPerBeat;

    std::cout << std::fixed << std::setprecision(3)
              << "[hispmv] dataset=" << dataset << '\n'
              << "[hispmv] rows=" << matrix.rows
              << " cols=" << matrix.columns
              << " nnz=" << matrix.values.size()
              << " window=" << config.window
              << " depth=" << config.depth
              << " tile_rows=" << tileRows
              << " tile_columns=" << tileColumns
              << " nonempty_tiles=" << summary.nonemptyTileCount << '/' << summary.tileCount << '\n'
              << "[hispmv] load_ms=" << milliseconds(loadBegin, loadEnd)
              << " tile_ms=" << milliseconds(tileBegin, tileEnd)
              << " schedule_ms=" << milliseconds(scheduleBegin, scheduleEnd)
              << " preprocess_ms=" << milliseconds(tileBegin, scheduleEnd) << '\n'
              << "[hispmv] baseline_imbalance=" << summary.baselineImbalance
              << " tree_imbalance=" << summary.treeImbalance
              << " improvement_pct=" << summary.improvementPercent
              << " selected=" << summary.selectedMode
              << " shared_rows=" << summary.sharedRowCount
              << " shared_row_padding=" << summary.sharedRowPadding << '\n'
              << "[hispmv] run_len_no_share=" << summary.noShareRunLength
              << " run_len_shared=" << summary.sharedRunLength
              << " run_len_tree=" << summary.treeRunLength
              << " selected_run_len=" << (summary.selectedMode == "no-row-sharing" ?
                  summary.noShareRunLength : summary.treeRunLength)
              << " selected_hbm_beats_per_channel=" <<
                  (summary.selectedMode == "no-row-sharing" ?
                   summary.noShareRunLength : summary.treeRunLength)
              << " selected_total_hbm_beats=" <<
                  (summary.selectedMode == "no-row-sharing" ?
                   summary.noShareRunLength : summary.treeRunLength) * 16U << '\n'
              << "[hispmv] no_share_padding=" << summary.noSharePadding
              << " shared_padding=" << summary.sharedPadding
              << " tree_padding=" << summary.treePadding
              << " tree_slot_utilization="
              << percent(summary.nonzeros, summary.treePhysicalSlots) << '\n'
              << "[cuperflow] a_encode_ms=" << milliseconds(cuperBegin, cuperEnd)
              << " hbm_beats_min=" << cuperflow.stats.minimumMatrixBeatsPerChannel
              << " hbm_beats_max=" << cuperflow.stats.maximumMatrixBeatsPerChannel
              << " hbm_beat_spread=" <<
                  (cuperflow.stats.maximumMatrixBeatsPerChannel -
                   cuperflow.stats.minimumMatrixBeatsPerChannel)
              << " zero_fill_slots=" << cuperflow.stats.zeroFillSlots
              << " slot_utilization=" << percent(cuperflow.stats.matrixSlots,
                                                    cuperCapacity)
              << " total_beats=" << cuperflow.stats.totalMatrixBeats << '\n'
              << "[compare] hispmv_selected_slots=" << summary.selectedPhysicalSlots
              << " cuperflow_slots=" << cuperCapacity
              << " hispmv_selected_vs_cuperflow_pct="
              << (cuperCapacity == 0U ? 0.0 :
                  100.0 * static_cast<double>(summary.selectedPhysicalSlots) /
                      static_cast<double>(cuperCapacity))
              << '\n'
              << "[compare] data_root=" << dataRoot() << '\n';
  } catch (const std::exception& error) {
    std::cerr << "[hispmv] error: " << error.what() << '\n';
    return 1;
  }
  return 0;
}
