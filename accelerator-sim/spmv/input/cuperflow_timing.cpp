#include "cuperflow_timing.hpp"

#include "../encoding/cuperflow/cuperflow.hpp"
#include "../golden.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#ifndef ACCELERATOR_SIM_DEFAULT_DATA_ROOT
#define ACCELERATOR_SIM_DEFAULT_DATA_ROOT "../data"
#endif

#ifndef CUPERFLOW_TIMING_FP64_MUL_LATENCY
#define CUPERFLOW_TIMING_FP64_MUL_LATENCY 4
#endif

#ifndef CUPERFLOW_TIMING_FP64_MUL_II
#define CUPERFLOW_TIMING_FP64_MUL_II 1
#endif

namespace fs = std::filesystem;

namespace accelerator_sim::spmv {
namespace {

namespace cf = encoding::cuperflow;

constexpr std::size_t kPcCount = 16;
constexpr std::size_t kXDecoderLanes = 8;
constexpr std::size_t kWordsPerBeat = cf::kVectorLanesPerBeat;

static_assert(CUPERFLOW_TIMING_FP64_MUL_LATENCY > 0 &&
                  CUPERFLOW_TIMING_FP64_MUL_II > 0,
              "Cuperflow timing 的 FP64 latency/II 必须为正数");

template <typename T>
std::vector<T> readArray(const fs::path& path) {
  std::ifstream input(path);
  if (!input) {
    throw std::runtime_error("无法打开数据文件: " + path.string());
  }
  std::vector<T> values;
  T value{};
  while (input >> value) values.push_back(value);
  if (!input.eof() || values.empty()) {
    throw std::runtime_error("无法解析或数据为空: " + path.string());
  }
  return values;
}

std::vector<std::uint64_t> readNonnegativeIntegers(const fs::path& path) {
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

fs::path resolveDataRoot() {
  if (const char* configured = std::getenv("ACCELERATOR_DATA_ROOT")) {
    if (*configured != '\0') return fs::path(configured);
  }
  return fs::path(ACCELERATOR_SIM_DEFAULT_DATA_ROOT);
}

fs::path findDataset(const std::string& requested) {
  const fs::path requestedPath(requested);
  if (isDatasetDirectory(requestedPath)) return requestedPath;
  const fs::path dataRoot = resolveDataRoot();
  for (const fs::path& root : {dataRoot / "generated" / "cgsolver", dataRoot / "suitesparse"}) {
    if (!fs::is_directory(root)) continue;
    for (const fs::directory_entry& entry : fs::recursive_directory_iterator(
             root, fs::directory_options::skip_permission_denied)) {
      if (entry.is_directory() && entry.path().filename() == requested &&
          isDatasetDirectory(entry.path())) return entry.path();
    }
  }
  throw std::runtime_error("找不到数据集: " + requested);
}

CsrMatrix loadMatrix(const fs::path& dataset) {
  CsrMatrix matrix;
  matrix.rowPointers = readNonnegativeIntegers(dataset / "row_ptr.txt");
  const std::vector<std::uint64_t> columns = readNonnegativeIntegers(dataset / "col_idx.txt");
  matrix.values = readArray<double>(dataset / "values.txt");
  if (matrix.rowPointers.size() < 2 || matrix.rowPointers.front() != 0) {
    throw std::runtime_error("CSR row_ptr.txt 格式错误: " + dataset.string());
  }
  matrix.rows = matrix.rowPointers.size() - 1U;
  matrix.columns = matrix.rows;
  if (matrix.rowPointers.back() != columns.size() || columns.size() != matrix.values.size()) {
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
    switch (character) {
      case '"': output << "\\\""; break;
      case '\\': output << "\\\\"; break;
      case '\n': output << "\\n"; break;
      case '\r': output << "\\r"; break;
      case '\t': output << "\\t"; break;
      default: output << static_cast<char>(character); break;
    }
  }
  output << '"';
}

std::uint64_t addCycles(std::uint64_t lhs, std::uint64_t rhs, const char* message) {
  if (rhs > std::numeric_limits<std::uint64_t>::max() - lhs) {
    throw std::overflow_error(message);
  }
  return lhs + rhs;
}

struct XTimingInfo {
  std::size_t owner = 0;
  std::size_t words = 0;
  std::size_t values = 0;
  std::size_t markers = 0;
  std::size_t beats = 0;
  std::size_t physicalWriteCycles = 0;
};

struct WorkTiming {
  std::size_t index = 0;
  std::size_t group = 0;
  std::size_t batch = 0;
  std::size_t xOwner = 0;
  bool xLoaded = false;
  XTimingInfo x;
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
  std::array<std::uint64_t, kPcCount> aBeats{};
  std::array<std::uint64_t, kPcCount> usefulSlots{};
  std::array<std::uint64_t, kPcCount> physicalSlots{};
  std::uint16_t activePcs = 0;
};

const cf::CuperflowXRange& findXRange(const cf::CuperflowVectorPackage& vectorPackage,
                                      std::size_t channel, std::size_t group) {
  if (channel >= vectorPackage.channelXRanges.size()) {
    throw std::logic_error("Cuperflow X range channel 超出范围");
  }
  const auto& ranges = vectorPackage.channelXRanges[channel];
  const auto found = std::find_if(ranges.begin(), ranges.end(),
      [group](const cf::CuperflowXRange& range) { return range.sliceGroup == group; });
  if (found == ranges.end()) {
    throw std::logic_error("Cuperflow X package 缺少 sliceGroup range");
  }
  return *found;
}

XTimingInfo inspectXRange(const cf::CuperflowVectorPackage& vectorPackage,
                          std::size_t owner, const cf::CuperflowXRange& range) {
  const auto& beats = vectorPackage.channelHbmBeats.at(owner);
  if (range.beatBegin > range.beatEnd || range.beatEnd > beats.size() ||
      range.encodedWordCount > (range.beatEnd - range.beatBegin) * kWordsPerBeat) {
    throw std::logic_error("Cuperflow X range 的 beat/word 边界非法");
  }
  XTimingInfo result{owner, range.encodedWordCount, range.valueCount, range.markerCount,
                     range.beatEnd - range.beatBegin, 0};
  std::uint32_t nextAddress = 0;
  std::size_t wordOffset = 0;
  while (wordOffset < range.encodedWordCount) {
    const std::size_t wordsThisBeat = std::min(kWordsPerBeat,
        range.encodedWordCount - wordOffset);
    std::array<std::uint32_t, kWordsPerBeat> lines{};
    std::size_t lineCount = 0;
    for (std::size_t lane = 0; lane < wordsThisBeat; ++lane, ++wordOffset) {
      const std::uint64_t word = beats[range.beatBegin + wordOffset / kWordsPerBeat]
          [wordOffset % kWordsPerBeat];
      if (cf::isXAddressMarker(word)) {
        nextAddress = cf::decodeXAddressMarker(word);
        if (nextAddress >= range.elementCount) {
          throw std::logic_error("Cuperflow X marker 地址超出 range");
        }
      } else {
        if (nextAddress >= range.elementCount) {
          throw std::logic_error("Cuperflow X value 地址超出 range");
        }
        const std::uint32_t lineAddress = nextAddress / kWordsPerBeat;
        if (std::find(lines.begin(), lines.begin() + lineCount, lineAddress) ==
            lines.begin() + lineCount) {
          lines[lineCount++] = lineAddress;
        }
        ++nextAddress;
      }
    }
    // packed local-X 每拍最多同时提交两个不同 512-bit line。
    result.physicalWriteCycles += (lineCount + 1U) / 2U;
  }
  return result;
}

void inspectAWork(const cf::CuperflowPackage& package, std::size_t batch,
                  std::size_t group, WorkTiming* work) {
  const std::size_t segment = batch * package.sliceGroupCount + group;
  if (package.channelLaneSliceGroupRanges.size() != kPcCount ||
      package.matrixChannels.size() != kPcCount ||
      package.matrixEntryMasks.size() != kPcCount ||
      segment >= package.stats.batchCount * package.sliceGroupCount) {
    throw std::logic_error("Cuperflow timing 的 A/HBM range 表不完整");
  }
  for (std::size_t channel = 0; channel < kPcCount; ++channel) {
    const auto& ranges = package.channelLaneSliceGroupRanges[channel];
    std::size_t begin = package.matrixChannels[channel].size();
    std::size_t end = 0;
    bool active = false;
    for (std::size_t lane = 0; lane < kWordsPerBeat; ++lane) {
      const auto laneRange = ranges[segment][lane];
      if (laneRange.first > laneRange.second ||
          laneRange.second > package.matrixChannels[channel].size() ||
          laneRange.second > package.matrixEntryMasks[channel].size()) {
        throw std::logic_error("Cuperflow A lane range 越过 channel 尾部");
      }
      if (laneRange.first != laneRange.second) {
        active = true;
        begin = std::min(begin, static_cast<std::size_t>(laneRange.first));
        end = std::max(end, static_cast<std::size_t>(laneRange.second));
      }
    }
    if (!active) begin = end = 0;
    work->aBeats[channel] = end - begin;
    work->physicalSlots[channel] = work->aBeats[channel] * kWordsPerBeat;
    if (work->aBeats[channel] != 0) {
      work->activePcs |= static_cast<std::uint16_t>(1U << channel);
    }
    for (std::size_t beat = begin; beat < end; ++beat) {
      const std::uint8_t mask = package.matrixEntryMasks[channel][beat];
      for (std::size_t lane = 0; lane < kWordsPerBeat; ++lane) {
        const auto laneRange = ranges[segment][lane];
        if (beat >= laneRange.first && beat < laneRange.second &&
            (mask & static_cast<std::uint8_t>(1U << lane)) != 0U) {
          ++work->usefulSlots[channel];
        }
      }
    }
  }
}

void writeTimingReport(const fs::path& path, const fs::path& dataset,
                       const cf::CuperflowPackage& package,
                       const std::vector<WorkTiming>& works,
                       std::uint64_t totalCycles, std::uint64_t totalABeats,
                       std::uint64_t usefulSlots, std::uint64_t physicalSlots,
                       std::uint64_t xSourceBeats, std::uint64_t xWords,
                       std::uint64_t xMarkers, std::uint64_t xWriteCycles,
                       std::uint64_t xLoadedGroups) {
  std::ofstream output(path);
  if (!output) throw std::runtime_error("无法写入 Cuperflow 时间报告: " + path.string());
  const double usefulUtilization = physicalSlots == 0 ? 0.0 :
      100.0 * static_cast<double>(usefulSlots) / static_cast<double>(physicalSlots);
  output << R"HTML(<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Cuperflow 时序与吞吐报告</title><style>
:root{color-scheme:light;--bg:#f3f6f7;--ink:#17242b;--muted:#64727a;--line:#d5dfe2;--panel:#fff;--x:#2c6d9a;--write:#8a5a22;--a:#17706d;--mul:#704d91;--done:#28733f}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,sans-serif}header{padding:20px max(16px,calc((100vw - 1380px)/2));background:#fff;border-bottom:1px solid var(--line)}h1{margin:0;font-size:23px}.subtitle{display:flex;gap:9px;align-items:center;flex-wrap:wrap;margin-top:5px;color:var(--muted)}.nav{display:flex;gap:8px;margin-top:12px}.nav a{padding:6px 9px;border:1px solid #82919f;border-radius:4px;color:#155f78;background:#fff;text-decoration:none}.main{max-width:1380px;margin:auto;padding:18px 16px 34px}section{margin-bottom:22px}h2{margin:0 0 10px;font-size:17px}.metrics{display:grid;grid-template-columns:repeat(6,minmax(130px,1fr));gap:8px}.metric{padding:11px 12px;border:1px solid var(--line);border-radius:5px;background:var(--panel);min-width:0}.metric span,.metric small{display:block;color:var(--muted);font-size:12px}.metric strong{display:block;margin:4px 0 1px;font-size:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.note{padding:12px 14px;border-left:4px solid var(--a);background:#fff;border-top:1px solid var(--line);border-bottom:1px solid var(--line);color:var(--muted)}.note strong{color:var(--ink)}.overview{overflow:auto;border:1px solid var(--line);background:#fff}.schedule{position:relative;min-width:900px;padding:10px 12px}.schedule-axis{height:22px;margin-left:128px;position:relative;border-bottom:1px solid var(--line);color:var(--muted);font-size:11px}.schedule-axis span{position:absolute;transform:translateX(-50%)}.schedule-row{display:grid;grid-template-columns:128px 1fr;min-height:27px;border-bottom:1px solid #edf1f2}.schedule-label{padding:5px 8px 5px 0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--muted);font-size:12px}.schedule-track{position:relative;background:repeating-linear-gradient(to right,transparent 0,transparent calc(var(--step) - 1px),rgba(80,95,100,.1) calc(var(--step) - 1px),rgba(80,95,100,.1) var(--step))}.schedule-bar{position:absolute;top:5px;height:17px;min-width:2px;border-radius:2px;background:var(--bar)}.schedule-bar span{display:block;padding:1px 4px;color:#fff;font-size:10px;white-space:nowrap;overflow:hidden}.legend{display:flex;gap:13px;flex-wrap:wrap;margin-top:9px;color:var(--muted);font-size:12px}.key:before{content:'';display:inline-block;width:12px;height:12px;margin-right:5px;background:var(--c);vertical-align:-1px}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:10px}.toolbar label{display:flex;align-items:center;gap:7px;color:var(--muted)}select,input{font:inherit}select{padding:6px 8px;border:1px solid #9aa8b2;border-radius:4px;background:#fff}.toolbar input[type=range]{width:230px;accent-color:var(--a)}.detail{overflow:auto;border:1px solid var(--line);background:#fff}.detail-plane{min-width:980px;padding:10px 12px}.detail-axis{height:25px;margin-left:135px;border-bottom:1px solid var(--line);position:relative;color:var(--muted);font-size:11px}.detail-axis span{position:absolute;transform:translateX(-50%)}.detail-row{display:grid;grid-template-columns:135px 1fr 190px;min-height:30px;border-bottom:1px solid #edf1f2}.detail-label{padding:6px 8px 5px 0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.detail-track{position:relative;background:repeating-linear-gradient(to right,transparent 0,transparent calc(var(--step) - 1px),rgba(80,95,100,.1) calc(var(--step) - 1px),rgba(80,95,100,.1) var(--step))}.detail-bar{position:absolute;top:5px;height:19px;min-width:2px;border-radius:2px;background:var(--bar);color:#fff;font-size:10px;padding:1px 4px;white-space:nowrap;overflow:hidden}.detail-meta{padding:5px 0 5px 8px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.table-wrap{overflow:auto;border:1px solid var(--line);background:#fff}table{width:100%;border-collapse:collapse;white-space:nowrap}th,td{padding:7px 9px;border-bottom:1px solid #e8edef;text-align:right}th{position:sticky;top:0;background:#edf2f4;color:#46545b;font-size:12px}th:first-child,td:first-child{text-align:left}tbody tr:hover{background:#f7fbfb}footer{max-width:1380px;margin:auto;padding:0 16px 24px;color:var(--muted);font-size:12px}@media(max-width:1000px){.metrics{grid-template-columns:repeat(3,minmax(130px,1fr))}}@media(max-width:560px){header,.main,footer{padding-left:12px;padding-right:12px}.metrics{grid-template-columns:repeat(2,minmax(130px,1fr))}.toolbar input[type=range]{width:150px}}
</style></head><body><header><h1>Cuperflow 时序与吞吐报告</h1><div class="subtitle"><span>)HTML";
  writeHtmlText(output, dataset.filename().string());
  output << R"HTML(</span><span>·</span><span>C++ 周期模型</span><span>·</span><span>group-major</span><span>·</span><span>FP64</span></div><nav class="nav"><a href=")HTML";
  writeHtmlText(output, dataset.filename().string() + ".html");
  output << R"HTML(">A 编码报告</a><a href=")HTML";
  writeHtmlText(output, dataset.filename().string() + "-x.html");
  output << R"HTML(">X 编码报告</a></nav></header><main class="main"><section><h2>模型边界</h2><div class="note"><strong>计算顺序：</strong>固定 sliceGroup，依次处理 batch 0、batch 1……；同一 group 的 X 在首次进入时 preload，后续 batch 复用 local_X。每个 work 使用 16 个 PC，每个 PC 配置一个 8-lane X decoder；报告把 owner HBM 的 X range 视为并行复制到各 PC local_X 的逻辑源，X barrier 完成后才发射 A。此页是算法时序模型，不是 Cuperflow RTL 的 Verilator 端口仿真。</div></section><section><h2>总览指标</h2><div class="metrics" id="metrics"></div></section><section><h2>全局 work 时间线</h2><div class="overview"><div class="schedule" id="schedule"></div></div><div class="legend"><span class="key" style="--c:var(--x)">X 输入/解码</span><span class="key" style="--c:var(--write)">local_X 写入</span><span class="key" style="--c:var(--a)">A 读取</span><span class="key" style="--c:var(--mul)">FMUL</span><span class="key" style="--c:var(--done)">work done</span></div></section><section><h2>单个 work 周期明细</h2><div class="toolbar"><label>选择 work <select id="workSelect"></select></label><label>时间缩放 <input id="detailScale" type="range" min="4" max="24" value="9"></label><span id="detailStatus"></span></div><div class="detail"><div class="detail-plane" id="detailPlane"></div></div></section><section><h2>work 统计</h2><div class="table-wrap"><table><thead><tr><th>work</th><th>sliceGroup</th><th>batch</th><th>周期区间</th><th>X</th><th>X 写入</th><th>A beats</th><th>有效 slot</th><th>物理 slot</th><th>有效利用率</th></tr></thead><tbody id="workRows"></tbody></table></div></section></main><footer>物理 slot = A beat × 8；尾部空槽仍进入固定 8-lane FMUL，因此吞吐按物理 slot 统计，有效利用率另按 matrixEntryMasks 统计。FP64 latency=)HTML";
  output << CUPERFLOW_TIMING_FP64_MUL_LATENCY << R"HTML(，II=)HTML" << CUPERFLOW_TIMING_FP64_MUL_II << R"HTML(。</footer><script>const timingTrace={)HTML";
  output << "\"dataset\":";
  writeJsonString(output, dataset.filename().string());
  output << ",\"rows\":" << package.rows
      << ",\"columns\":" << package.columns
      << ",\"nnz\":" << package.nonzeros
      << ",\"pcCount\":" << kPcCount
      << ",\"decoderLanes\":" << kXDecoderLanes
      << ",\"mulLatency\":" << CUPERFLOW_TIMING_FP64_MUL_LATENCY
      << ",\"mulII\":" << CUPERFLOW_TIMING_FP64_MUL_II
      << ",\"sliceGroupCount\":" << package.sliceGroupCount
      << ",\"batchCount\":" << package.stats.batchCount
      << ",\"totalCycles\":" << totalCycles
      << ",\"encodedABeats\":" << package.stats.totalMatrixBeats
      << ",\"totalABeats\":" << totalABeats
      << ",\"usefulSlots\":" << usefulSlots
      << ",\"physicalSlots\":" << physicalSlots
      << ",\"xSourceBeats\":" << xSourceBeats
      << ",\"xWords\":" << xWords
      << ",\"xMarkers\":" << xMarkers
      << ",\"xWriteCycles\":" << xWriteCycles
      << ",\"xLoadedGroups\":" << xLoadedGroups
      << ",\"usefulUtilization\":" << std::setprecision(12) << usefulUtilization
      << ",\"physicalThroughput\":" << kPcCount * kWordsPerBeat
      << ",\"works\":[";
  for (std::size_t index = 0; index < works.size(); ++index) {
    if (index != 0) output << ',';
    const WorkTiming& work = works[index];
    output << "{\"index\":" << work.index
        << ",\"group\":" << work.group
        << ",\"batch\":" << work.batch
        << ",\"xOwner\":" << work.xOwner
        << ",\"xLoaded\":" << (work.xLoaded ? "true" : "false")
        << ",\"xWords\":" << work.x.words
        << ",\"xValues\":" << work.x.values
        << ",\"xMarkers\":" << work.x.markers
        << ",\"xBeats\":" << work.x.beats
        << ",\"xWriteCycles\":" << work.x.physicalWriteCycles
        << ",\"start\":" << work.start
        << ",\"xInputBegin\":" << work.xInputBegin
        << ",\"xInputEnd\":" << work.xInputEnd
        << ",\"xWriteBegin\":" << work.xWriteBegin
        << ",\"xWriteEnd\":" << work.xWriteEnd
        << ",\"xReady\":" << work.xReady
        << ",\"aRequest\":" << work.aRequest
        << ",\"aBegin\":" << work.aBegin
        << ",\"aEnd\":" << work.aEnd
        << ",\"mulRequestBegin\":" << work.mulRequestBegin
        << ",\"mulRequestEnd\":" << work.mulRequestEnd
        << ",\"mulResponseBegin\":" << work.mulResponseBegin
        << ",\"mulResponseEnd\":" << work.mulResponseEnd
        << ",\"done\":" << work.done
        << ",\"activePcs\":" << work.activePcs
        << ",\"aBeats\":[";
    for (std::size_t pc = 0; pc < kPcCount; ++pc) {
      if (pc != 0) output << ',';
      output << work.aBeats[pc];
    }
    output << "],\"usefulSlots\":[";
    for (std::size_t pc = 0; pc < kPcCount; ++pc) {
      if (pc != 0) output << ',';
      output << work.usefulSlots[pc];
    }
    output << "],\"physicalSlots\":[";
    for (std::size_t pc = 0; pc < kPcCount; ++pc) {
      if (pc != 0) output << ',';
      output << work.physicalSlots[pc];
    }
    output << "]}";
  }
  output << R"HTML(]};
const t=timingTrace,fmt=value=>Number(value).toLocaleString(),pct=value=>value.toFixed(2)+'%',metrics=document.querySelector('#metrics'),select=document.querySelector('#workSelect'),detailPlane=document.querySelector('#detailPlane'),detailScale=document.querySelector('#detailScale'),detailStatus=document.querySelector('#detailStatus'),workRows=document.querySelector('#workRows');
function metric(label,value,detail){const node=document.createElement('div');node.className='metric';node.innerHTML='<span>'+label+'</span><strong>'+value+'</strong><small>'+detail+'</small>';metrics.appendChild(node)}
metric('总周期',fmt(t.totalCycles),'cycle');metric('work',fmt(t.works.length),t.sliceGroupCount+' groups × '+t.batchCount+' batches');metric('A 输入',fmt(t.totalABeats),'连续 HBM range beats');metric('A range extra',fmt(t.totalABeats-t.encodedABeats),'相对 package 的连续区间开销');metric('有效 nnz',fmt(t.usefulSlots),'matrixEntryMasks');metric('物理 FMUL',fmt(t.physicalSlots),'FP64 products');metric('物理峰值',fmt(t.physicalThroughput),'FP64 products / cycle');metric('X source',fmt(t.xSourceBeats),'encoded FP64 beats');metric('X token',fmt(t.xWords),t.xMarkers+' address markers');metric('local_X 写入',fmt(t.xWriteCycles),'physical line-write cycles');metric('X preload',fmt(t.xLoadedGroups),'sliceGroup loads');metric('有效利用率',pct(t.usefulUtilization),'useful / physical slot');metric('X decoder 峰值',fmt(t.pcCount*t.decoderLanes),'tokens / cycle');
function bar(parent,start,end,total,color,text){if(end<start)return;const node=document.createElement('div');node.className='schedule-bar';node.style.setProperty('--bar',color);node.style.left=(total?start/total*100:0)+'%';node.style.width=(total?Math.max(0.15,(end-start)/total*100):0)+'%';if(text)node.innerHTML='<span>'+text+'</span>';parent.appendChild(node)}
function axis(parent,total,step){for(let c=0;c<=total;c+=step){const tick=document.createElement('span');tick.style.left=(total?c/total*100:0)+'%';tick.textContent=fmt(c);parent.appendChild(tick)}}
function renderSchedule(){const root=document.querySelector('#schedule');root.replaceChildren();const total=Math.max(1,t.totalCycles),axisStep=Math.max(1,Math.ceil(total/8));const axisNode=document.createElement('div');axisNode.className='schedule-axis';axisNode.style.setProperty('--step',(axisStep/total*100)+'%');axis(axisNode,total,axisStep);root.appendChild(axisNode);for(const w of t.works){const row=document.createElement('div');row.className='schedule-row';const label=document.createElement('div');label.className='schedule-label';label.textContent='G'+w.group+' / B'+w.batch;label.title='work '+w.index+' · cycles '+w.start+'..'+w.done;row.appendChild(label);const track=document.createElement('div');track.className='schedule-track';track.style.setProperty('--step',(axisStep/total*100)+'%');if(w.xLoaded){bar(track,w.xInputBegin,w.xInputEnd,total,'var(--x)','X');bar(track,w.xWriteBegin,w.xWriteEnd,total,'var(--write)','W')}if(w.activePcs){bar(track,w.aBegin,w.aEnd,total,'var(--a)','A');bar(track,w.mulRequestBegin,w.mulResponseEnd,total,'var(--mul)','FMUL')}bar(track,w.done,w.done+1,total,'var(--done)','');row.appendChild(track);root.appendChild(row)}}
function detailBar(parent,start,end,begin,endCycle,color,text){const node=document.createElement('div');node.className='detail-bar';node.style.setProperty('--bar',color);node.style.left=((start-begin)/(endCycle-begin)*100)+'%';node.style.width=(Math.max(0.3,(end-start)/(endCycle-begin)*100))+'%';node.textContent=text||'';parent.appendChild(node)}
function renderDetail(){const w=t.works[Number(select.value)||0],scale=Number(detailScale.value),begin=w.start,end=Math.max(begin+1,w.done+1),span=end-begin;detailPlane.replaceChildren();detailPlane.style.setProperty('--step',scale+'px');const axisNode=document.createElement('div');axisNode.className='detail-axis';axisNode.style.width=(span*scale)+'px';for(let i=0;i<=span;i+=Math.max(1,Math.ceil(span/8))){const tick=document.createElement('span');tick.style.left=(i/span*100)+'%';tick.textContent='c'+fmt(begin+i);axisNode.appendChild(tick)}detailPlane.appendChild(axisNode);const add=(label,meta,events)=>{const row=document.createElement('div');row.className='detail-row';const l=document.createElement('div');l.className='detail-label';l.textContent=label;row.appendChild(l);const track=document.createElement('div');track.className='detail-track';track.style.width=(span*scale)+'px';for(const e of events)detailBar(track,e[0],e[1],begin,end,e[2],e[3]);row.appendChild(track);const m=document.createElement('div');m.className='detail-meta';m.textContent=meta;row.appendChild(m);detailPlane.appendChild(row)};if(w.xLoaded){add('X source HBM '+w.xOwner,'G'+w.group+' · '+fmt(w.xBeats)+' beats · '+fmt(w.xWords)+' tokens',[[w.xInputBegin,w.xInputEnd,'var(--x)','R']]);add('X decoder 0..15','16 × 8 = '+fmt(t.pcCount*t.decoderLanes)+' token/cycle',[[w.xInputBegin,w.xInputEnd,'var(--x)','8-lane decode']]);add('local_X writer 0..15',fmt(w.xWriteCycles)+' physical write cycles',[[w.xWriteBegin,w.xWriteEnd,'var(--write)','write']])}else{add('X reuse','同一 group 的 local_X 已就绪',[[w.start,w.start+1,'var(--done)','ready']])}add('globalXReady / A AR','barrier c'+fmt(w.xReady),[[w.xReady,w.xReady+1,'var(--done)','barrier']]);for(let pc=0;pc<t.pcCount;pc+=1){const beats=w.aBeats[pc],physical=w.physicalSlots[pc],useful=w.usefulSlots[pc];if(!beats){add('PC'+pc,'inactive',[]);continue}add('PC'+pc,'A '+fmt(beats)+' beats · useful '+fmt(useful)+' · physical '+fmt(physical),[[w.aBegin,w.aEnd,'var(--a)','A'],[w.mulRequestBegin,w.mulRequestEnd,'var(--mul)','req'],[w.mulResponseBegin,w.mulResponseEnd,'var(--done)','resp']])}detailStatus.textContent='work '+w.index+' · G'+w.group+' / B'+w.batch+' · cycles '+w.start+'..'+w.done+' · X '+(w.xLoaded?'load':'reuse')}
for(const w of t.works){const option=document.createElement('option');option.value=w.index;option.textContent='work '+w.index+' · G'+w.group+' / B'+w.batch+' · c'+w.start+'..'+w.done;select.appendChild(option)}
function renderTable(){for(const w of t.works){const row=document.createElement('tr'),useful=w.usefulSlots.reduce((a,b)=>a+b,0),physical=w.physicalSlots.reduce((a,b)=>a+b,0),cells=[w.index,'G'+w.group,'B'+w.batch,'c'+fmt(w.start)+'..c'+fmt(w.done),w.xLoaded?fmt(w.xBeats)+' beats':'reuse',fmt(w.xWriteCycles),fmt(w.aBeats.reduce((a,b)=>a+b,0)),fmt(useful),fmt(physical),pct(physical?100*useful/physical:0)];for(const value of cells){const cell=document.createElement('td');cell.textContent=value;row.appendChild(cell)}row.addEventListener('click',()=>{select.value=String(w.index);renderDetail()});workRows.appendChild(row)}}
select.addEventListener('change',renderDetail);detailScale.addEventListener('input',renderDetail);renderSchedule();renderTable();renderDetail();</script></body></html>)HTML";
}

}  // namespace

int runCuperflowTiming(const std::string& requestedDataset) {
  if (requestedDataset.empty() || requestedDataset == "--list") {
    std::cout << "用法: make -C accelerator-sim/spmv cuperflow-timing mainargs=<scale>\n";
    return 0;
  }
  const fs::path dataset = findDataset(requestedDataset);
  const CsrMatrix matrix = loadMatrix(dataset);
  const std::vector<double> input = readArray<double>(dataset / "b.txt");
  if (input.size() != matrix.columns) throw std::runtime_error("b.txt 长度与矩阵列数不一致");

  const cf::CuperflowPackage package = cf::encode(matrix, cf::CuperflowConfig{});
  const cf::CuperflowVectorPackage vectorPackage = cf::encodeVector(input, package);
  if (package.config.hbmChannelCount != kPcCount || package.sliceGroupCount == 0 ||
      vectorPackage.channelXRanges.size() != kPcCount) {
    throw std::runtime_error("Cuperflow timing 当前要求 16 PC 和非空 sliceGroup");
  }

  std::vector<WorkTiming> works;
  works.reserve(package.sliceGroupCount * package.stats.batchCount);
  std::uint64_t cursor = 0, totalABeats = 0, usefulSlots = 0, physicalSlots = 0;
  std::uint64_t xSourceBeats = 0, xWords = 0, xMarkers = 0, xWriteCycles = 0;
  std::uint64_t xLoadedGroups = 0;
  // group-major：同一 sliceGroup 内按 batch 递增，X 首次装载后在后续 batch 复用。
  for (std::size_t group = 0; group < package.sliceGroupCount; ++group) {
    const std::size_t owner = package.sliceGroupChannels.at(group);
    const cf::CuperflowXRange& range = findXRange(vectorPackage, owner, group);
    const XTimingInfo xInfo = inspectXRange(vectorPackage, owner, range);
    for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
      WorkTiming work;
      work.index = works.size();
      work.group = group;
      work.batch = batch;
      work.xOwner = owner;
      work.xLoaded = batch == 0;
      work.x = xInfo;
      work.start = cursor;
      inspectAWork(package, batch, group, &work);
      for (std::size_t pc = 0; pc < kPcCount; ++pc) {
        totalABeats = addCycles(totalABeats, work.aBeats[pc], "A beat 统计溢出");
        usefulSlots = addCycles(usefulSlots, work.usefulSlots[pc], "有效 slot 统计溢出");
        physicalSlots = addCycles(physicalSlots, work.physicalSlots[pc], "物理 slot 统计溢出");
      }
      if (work.xLoaded) {
        ++xLoadedGroups;
        xSourceBeats = addCycles(xSourceBeats, work.x.beats, "X beat 统计溢出");
        xWords = addCycles(xWords, work.x.words, "X token 统计溢出");
        xMarkers = addCycles(xMarkers, work.x.markers, "X marker 统计溢出");
        xWriteCycles = addCycles(xWriteCycles, work.x.physicalWriteCycles, "X 写入周期统计溢出");
        work.xInputBegin = addCycles(work.start, 1U, "X 输入周期溢出");
        work.xInputEnd = addCycles(work.xInputBegin, work.x.beats, "X 输入周期溢出");
        work.xWriteBegin = addCycles(work.start, 2U, "X 写入周期溢出");
        work.xWriteEnd = addCycles(work.xWriteBegin, work.x.physicalWriteCycles, "X 写入周期溢出");
        work.xReady = std::max(work.xInputEnd, work.xWriteEnd);
      } else {
        work.xInputBegin = work.start;
        work.xInputEnd = work.start;
        work.xWriteBegin = work.start;
        work.xWriteEnd = work.start;
        work.xReady = work.start;
      }
      work.aRequest = work.xReady;
      work.aBegin = addCycles(work.aRequest, 1U, "A 输入周期溢出");
      std::uint64_t maximumABeats = 0;
      for (const std::uint64_t beats : work.aBeats) maximumABeats = std::max(maximumABeats, beats);
      work.aEnd = addCycles(work.aBegin, maximumABeats, "A 输入周期溢出");
      work.mulRequestBegin = addCycles(work.aBegin, 1U, "FMUL 周期溢出");
      work.mulRequestEnd = addCycles(work.mulRequestBegin, maximumABeats, "FMUL 周期溢出");
      work.mulResponseBegin = addCycles(work.mulRequestBegin, CUPERFLOW_TIMING_FP64_MUL_LATENCY,
                                        "FMUL 响应周期溢出");
      work.mulResponseEnd = addCycles(work.mulRequestEnd, CUPERFLOW_TIMING_FP64_MUL_LATENCY,
                                      "FMUL 响应周期溢出");
      work.done = maximumABeats == 0 ? work.aRequest : work.mulResponseEnd;
      cursor = addCycles(work.done, 1U, "总周期溢出");
      works.push_back(work);
    }
  }

  fs::path reportRoot = fs::current_path() / "build" / "encoding";
  if (const char* configured = std::getenv("SPMV_ENCODING_REPORT_DIR")) {
    if (*configured != '\0') reportRoot = fs::path(configured);
  }
  const fs::path reportDirectory = reportRoot / "cuperflow";
  fs::create_directories(reportDirectory);
  const fs::path reportPath = reportDirectory / (dataset.filename().string() + "-timing.html");
  if (totalABeats < package.stats.totalMatrixBeats) {
    throw std::logic_error("Cuperflow 连续 A range beat 数少于 package beat 数");
  }
  writeTimingReport(reportPath, dataset, package, works, cursor, totalABeats, usefulSlots,
                    physicalSlots, xSourceBeats, xWords, xMarkers, xWriteCycles, xLoadedGroups);

  const double utilization = physicalSlots == 0 ? 0.0 :
      100.0 * static_cast<double>(usefulSlots) / static_cast<double>(physicalSlots);
  std::cout << "[spmv-cuperflow-timing] dataset=" << dataset << " order=group-major"
            << " groups=" << package.sliceGroupCount << " batches=" << package.stats.batchCount
            << " works=" << works.size() << " cycles=" << cursor << " a_beats=" << totalABeats
            << " a_package_beats=" << package.stats.totalMatrixBeats
            << " a_range_extra=" << (totalABeats - package.stats.totalMatrixBeats)
            << " physical_products=" << physicalSlots << " useful_slots=" << usefulSlots
            << " useful_utilization=" << std::fixed << std::setprecision(3) << utilization << "%"
            << " x_source_beats=" << xSourceBeats << " x_words=" << xWords
            << " x_write_cycles=" << xWriteCycles
            << " peak_a_products_per_cycle=" << kPcCount * kWordsPerBeat
            << " peak_x_tokens_per_cycle=" << kPcCount * kXDecoderLanes << '\n';
  std::cout << "[spmv-cuperflow-timing] html=" << reportPath << '\n';
  return 0;
}

}  // namespace accelerator_sim::spmv
