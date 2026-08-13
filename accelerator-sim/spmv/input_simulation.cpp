#include "input_simulation.hpp"

#ifdef SPMV_INPUT_TRANSACTION_VERILATOR

#include "VSpmvInputTop.h"
#include "verilated.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <system_error>
#include <unistd.h>
#include <vector>

namespace fs = std::filesystem;

namespace accelerator_sim::spmv {
namespace {

constexpr std::size_t kAReaderCount = 16;
constexpr std::size_t kWordsPerBeat = 16;
constexpr std::size_t kBeatBytes = 64;

struct DutPort {
  CData* requestReady;
  CData* requestValid;
  QData* requestAddress;
  IData* requestBeats;
  CData* arReady;
  CData* arValid;
  QData* arAddress;
  CData* arLength;
  CData* arSize;
  CData* arBurst;
  CData* rReady;
  CData* rValid;
  CData* rId;
  VlWide<kWordsPerBeat>* rData;
  CData* rResponse;
  CData* rLast;
  CData* idle;
  CData* done;
  CData* error;
};

struct ConsumerStatus {
  IData* aBeats;
  IData* xBeats;
  QData* aChecksum;
  QData* xChecksum;
  CData* error;
};

struct HbmModel {
  std::uint64_t base = 0;
  std::vector<encoding::cuper::CuperBeat> beats;
  std::size_t nextIssuedBeat = 0;
  std::size_t nextDataBeat = 0;
  std::deque<std::size_t> burstBeats;
  std::size_t maxOutstandingBursts = 2;
  bool requestAccepted = false;
};

struct CycleRecord {
  std::uint64_t cycle = 0;
  std::uint16_t requestMask = 0;
  std::uint16_t addressMask = 0;
  std::uint16_t dataMask = 0;
  std::uint16_t doneMask = 0;
  bool xRequest = false;
  bool xAddress = false;
  bool xData = false;
  bool xDone = false;
  std::uint32_t minimumABeats = 0;
  std::uint32_t maximumABeats = 0;
  std::uint32_t xBeats = 0;
};

#define SPMV_A_DUT_PORT(index) DutPort{ \
    &dut.io_aRequest_##index##_ready, &dut.io_aRequest_##index##_valid, \
    &dut.io_aRequest_##index##_bits_address, &dut.io_aRequest_##index##_bits_beats, \
    &dut.io_aHbm_##index##_ar_ready, &dut.io_aHbm_##index##_ar_valid, \
    &dut.io_aHbm_##index##_ar_bits_addr, &dut.io_aHbm_##index##_ar_bits_len, \
    &dut.io_aHbm_##index##_ar_bits_size, &dut.io_aHbm_##index##_ar_bits_burst, \
    &dut.io_aHbm_##index##_r_ready, &dut.io_aHbm_##index##_r_valid, \
    &dut.io_aHbm_##index##_r_bits_id, &dut.io_aHbm_##index##_r_bits_data, \
    &dut.io_aHbm_##index##_r_bits_resp, &dut.io_aHbm_##index##_r_bits_last, \
    &dut.io_aIdle_##index, &dut.io_aDone_##index, &dut.io_aError_##index}

#define SPMV_CONSUMER_STATUS(index) ConsumerStatus{ \
    &dut.io_consumerABeats_##index, &dut.io_consumerXBeats_##index, \
    &dut.io_consumerAChecksum_##index, &dut.io_consumerXChecksum_##index, \
    &dut.io_consumerError_##index}

std::array<DutPort, kAReaderCount> aPorts(VSpmvInputTop& dut) {
  return {{
      SPMV_A_DUT_PORT(0), SPMV_A_DUT_PORT(1), SPMV_A_DUT_PORT(2), SPMV_A_DUT_PORT(3),
      SPMV_A_DUT_PORT(4), SPMV_A_DUT_PORT(5), SPMV_A_DUT_PORT(6), SPMV_A_DUT_PORT(7),
      SPMV_A_DUT_PORT(8), SPMV_A_DUT_PORT(9), SPMV_A_DUT_PORT(10), SPMV_A_DUT_PORT(11),
      SPMV_A_DUT_PORT(12), SPMV_A_DUT_PORT(13), SPMV_A_DUT_PORT(14), SPMV_A_DUT_PORT(15)}};
}

std::array<ConsumerStatus, kAReaderCount> consumers(VSpmvInputTop& dut) {
  return {{
      SPMV_CONSUMER_STATUS(0), SPMV_CONSUMER_STATUS(1),
      SPMV_CONSUMER_STATUS(2), SPMV_CONSUMER_STATUS(3),
      SPMV_CONSUMER_STATUS(4), SPMV_CONSUMER_STATUS(5),
      SPMV_CONSUMER_STATUS(6), SPMV_CONSUMER_STATUS(7),
      SPMV_CONSUMER_STATUS(8), SPMV_CONSUMER_STATUS(9),
      SPMV_CONSUMER_STATUS(10), SPMV_CONSUMER_STATUS(11),
      SPMV_CONSUMER_STATUS(12), SPMV_CONSUMER_STATUS(13),
      SPMV_CONSUMER_STATUS(14), SPMV_CONSUMER_STATUS(15)}};
}

DutPort xPort(VSpmvInputTop& dut) {
  return DutPort{
      &dut.io_xRequest_0_ready, &dut.io_xRequest_0_valid,
      &dut.io_xRequest_0_bits_address, &dut.io_xRequest_0_bits_beats,
      &dut.io_xHbm_0_ar_ready, &dut.io_xHbm_0_ar_valid,
      &dut.io_xHbm_0_ar_bits_addr, &dut.io_xHbm_0_ar_bits_len,
      &dut.io_xHbm_0_ar_bits_size, &dut.io_xHbm_0_ar_bits_burst,
      &dut.io_xHbm_0_r_ready, &dut.io_xHbm_0_r_valid,
      &dut.io_xHbm_0_r_bits_id, &dut.io_xHbm_0_r_bits_data,
      &dut.io_xHbm_0_r_bits_resp, &dut.io_xHbm_0_r_bits_last,
      &dut.io_xIdle_0, &dut.io_xDone_0, &dut.io_xError_0};
}

#undef SPMV_A_DUT_PORT
#undef SPMV_CONSUMER_STATUS

void clearBeat(VlWide<kWordsPerBeat>& value) {
  std::fill(value.m_storage, value.m_storage + kWordsPerBeat, 0U);
}

void driveBeat(VlWide<kWordsPerBeat>& target, const encoding::cuper::CuperBeat& beat) {
  for (std::size_t lane = 0; lane < beat.size(); ++lane) {
    target.m_storage[lane * 2] = static_cast<std::uint32_t>(beat[lane]);
    target.m_storage[lane * 2 + 1] = static_cast<std::uint32_t>(beat[lane] >> 32U);
  }
}

std::uint64_t checksum(const std::vector<encoding::cuper::CuperBeat>& beats) {
  std::uint64_t result = 0;
  for (const auto& beat : beats) {
    for (std::uint64_t lane : beat) result ^= lane;
  }
  return result;
}

void writeHtmlText(std::ostream& output, std::string_view value) {
  for (char character : value) {
    switch (character) {
      case '&': output << "&amp;"; break;
      case '<': output << "&lt;"; break;
      case '>': output << "&gt;"; break;
      case '"': output << "&quot;"; break;
      case '\'': output << "&#39;"; break;
      default: output << character;
    }
  }
}

void writeJsonString(std::ostream& output, std::string_view value) {
  output << '"';
  for (unsigned char character : value) {
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
                 << static_cast<unsigned>(character) << std::dec << std::setfill(' ');
        } else {
          output << static_cast<char>(character);
        }
    }
  }
  output << '"';
}

std::uint32_t bitCount(std::uint16_t mask) {
  std::uint32_t count = 0;
  while (mask != 0) {
    count += mask & 1U;
    mask >>= 1U;
  }
  return count;
}

std::string hex64(std::uint64_t value) {
  std::ostringstream stream;
  stream << "0x" << std::hex << std::setw(16) << std::setfill('0') << value;
  return stream.str();
}

fs::path constructionRoot() {
  std::error_code error;
  const fs::path executable = fs::read_symlink("/proc/self/exe", error);
  if (error || executable.empty()) {
    throw std::runtime_error("无法定位 SPMV host 可执行文件，不能保存 HTML 报告");
  }
  const fs::path root = executable.parent_path().parent_path().parent_path();
  if (!fs::is_regular_file(root / "profile.env")) {
    throw std::runtime_error("SPMV transaction host 未运行在正式 construction 中");
  }
  return root;
}

fs::path reportDirectory(const std::string& dataset) {
  const auto now = std::chrono::system_clock::now().time_since_epoch();
  const auto timestamp = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
  const fs::path datasetRoot = constructionRoot() / "runtime" / dataset;
  const fs::path run = datasetRoot /
      (std::to_string(timestamp) + "-" + std::to_string(static_cast<long long>(getpid())));
  fs::create_directories(run);
  return run;
}

void updateLatestReport(const fs::path& runDirectory) {
  const fs::path datasetRoot = runDirectory.parent_path();
  const fs::path temporary = datasetRoot / (".latest-" + std::to_string(getpid()));
  std::error_code error;
  fs::remove(temporary, error);
  fs::create_directory_symlink(runDirectory.filename(), temporary);
  fs::rename(temporary, datasetRoot / "latest", error);
  if (error) {
    fs::remove(datasetRoot / "latest", error);
    error.clear();
    fs::rename(temporary, datasetRoot / "latest", error);
  }
  if (error) throw std::runtime_error("无法更新 SPMV HTML 报告 latest 链接");
}

void writeMetric(std::ostream& output, std::string_view label, std::string_view value,
                 std::string_view detail) {
  output << "<div class=\"metric\"><span>";
  writeHtmlText(output, label);
  output << "</span><strong>";
  writeHtmlText(output, value);
  output << "</strong><small>";
  writeHtmlText(output, detail);
  output << "</small></div>";
}

std::string fixed(double value, unsigned precision) {
  std::ostringstream stream;
  stream << std::fixed << std::setprecision(precision) << value;
  return stream.str();
}

void writePerformanceReport(const fs::path& path, const InputSimulationData& input,
                            const std::array<ConsumerStatus, kAReaderCount>& status,
                            std::uint64_t cycleCount, std::uint64_t addressCount,
                            bool pipelineAvailable) {
  std::ofstream output(path);
  if (!output) throw std::runtime_error("无法写入 SPMV 性能报告: " + path.string());

  std::uint64_t totalABeats = 0;
  for (const auto& channel : input.aChannels) totalABeats += channel.size();
  const std::uint64_t hbmBeats = totalABeats + input.xBeats.size();
  const std::uint64_t broadcastBeats = input.xBeats.size() * kAReaderCount;
  std::size_t maximumABeats = 0;
  std::size_t minimumABeats = std::numeric_limits<std::size_t>::max();
  for (const auto& channel : input.aChannels) {
    maximumABeats = std::max(maximumABeats, channel.size());
    minimumABeats = std::min(minimumABeats, channel.size());
  }

  output << R"HTML(<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SPMV 输入性能报告</title><style>:root{color-scheme:light;--bg:#f4f6f8;--ink:#18222d;--muted:#63707d;--line:#d8dee5;--panel:#fff;--request:#67489a;--address:#147582;--data:#35714a;--done:#a36716;--accent:#176b87}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 system-ui,sans-serif}header{padding:22px max(20px,calc((100vw - 1240px)/2));background:#fff;border-bottom:1px solid var(--line)}h1{margin:0;font-size:24px;letter-spacing:0}.subtitle{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:5px;color:var(--muted)}.status{padding:2px 8px;border-radius:4px;font-weight:650}.good{color:#17653a;background:#e6f5eb}main{max-width:1240px;margin:0 auto;padding:18px 20px 34px}section{margin:0 0 24px}h2{font-size:17px;margin:0 0 10px}.metrics{display:grid;grid-template-columns:repeat(6,minmax(130px,1fr));gap:8px}.metric{min-width:0;padding:12px 13px;background:var(--panel);border:1px solid var(--line);border-radius:6px}.metric span,.metric small{display:block;color:var(--muted)}.metric strong{display:block;margin:5px 0 1px;font-size:22px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.metric small{font-size:12px}.band{background:#fff;border-top:1px solid var(--line);border-bottom:1px solid var(--line)}.band-inner{max-width:1240px;margin:auto;padding:18px 20px}.pipeline-meta{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px}.badge{padding:4px 8px;border:1px solid #b8c2cc;border-radius:4px;background:#f8fafb}.load-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px 18px}.load-row{display:grid;grid-template-columns:28px 1fr 34px;gap:7px;align-items:center}.track{height:12px;background:#e7ebef;border-radius:3px;overflow:hidden}.fill{height:100%;background:var(--data);min-width:2px}.table-wrap{overflow:auto;border:1px solid var(--line);background:#fff}table{width:100%;border-collapse:collapse;white-space:nowrap}th,td{padding:8px 10px;border-bottom:1px solid #e7ebef;text-align:right}th{position:sticky;top:0;background:#edf1f4;color:#46525e;font-size:12px}th:first-child,td:first-child{text-align:left}tbody tr:hover{background:#f7fafc}.muted{color:var(--muted)}.mono{font-family:ui-monospace,SFMono-Regular,monospace}.pass{color:#17653a;font-weight:650}.actions{display:flex;gap:10px;margin-top:12px}.actions a{display:inline-flex;padding:7px 10px;border:1px solid #82919f;border-radius:4px;color:#155f78;background:#fff;text-decoration:none}footer{max-width:1240px;margin:auto;padding:0 20px 24px;color:var(--muted);font-size:12px}@media(max-width:900px){.metrics{grid-template-columns:repeat(3,1fr)}.load-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:520px){header,main,.band-inner,footer{padding-left:12px;padding-right:12px}.metrics{grid-template-columns:repeat(2,1fr)}.metric strong{font-size:18px}.load-grid{grid-template-columns:1fr}.actions{flex-wrap:wrap}}</style></head><body><header><h1>SPMV 输入性能报告</h1><div class="subtitle"><span>)HTML";
  writeHtmlText(output, input.dataset);
  output << R"HTML(</span><span>·</span><span>Verilator</span><span class="status good">通过</span></div></header><main><section><h2>执行总览</h2><div class="metrics">)HTML";
  writeMetric(output, "硬件周期", std::to_string(cycleCount), "cycles");
  writeMetric(output, "A 输入", std::to_string(totalABeats), "512-bit beats");
  writeMetric(output, "X 输入", std::to_string(input.xBeats.size()), "512-bit beats");
  writeMetric(output, "A 平均并行度", fixed(maximumABeats == 0 ? 0.0 :
      static_cast<double>(totalABeats) / maximumABeats, 2), "最多 16 beats / cycle");
  writeMetric(output, "16/16 连续", std::to_string(minimumABeats), "full-bandwidth cycles");
  writeMetric(output, "X 广播交付", std::to_string(broadcastBeats), "consumer beats");
  output << "</div></section><section><h2>输入配置</h2><div class=\"pipeline-meta\">"
      "<span class=\"badge\">16 路 A reader</span><span class=\"badge\">1 路 X reader</span>"
      "<span class=\"badge\">16 个消费端</span><span class=\"badge\">X 原子广播</span>"
      "<span class=\"badge\">512-bit AXI 满带宽</span><span class=\"badge\">2 outstanding bursts</span>"
      "<span class=\"badge\">AR burst "
      << addressCount << " 次</span></div><div class=\"table-wrap\"><table><thead><tr>"
      "<th>地址窗口</th><th>A 最小..最大</th><th>A 通道差</th><th>X 地址</th>"
      "<th>HBM 输入 beat</th><th>协议</th></tr></thead><tbody><tr><td class=\"mono\">"
      << hex64(input.hbmBase) << " + " << input.hbmBytes << " B</td><td>"
      << minimumABeats << ".." << maximumABeats << "</td><td>"
      << maximumABeats - minimumABeats << " beats</td><td class=\"mono\">"
      << hex64(input.xAddress) << "</td><td>" << hbmBeats
      << "</td><td>AXI4 INCR / 4 KiB 边界</td></tr></tbody></table></div></section></main>";

  output << "<div class=\"band\"><div class=\"band-inner\"><section><h2>A 通道负载分布</h2>"
      "<div class=\"load-grid\">";
  for (std::size_t lane = 0; lane < input.aChannels.size(); ++lane) {
    const double width = maximumABeats == 0 ? 0.0 :
        static_cast<double>(input.aChannels[lane].size()) * 100.0 / maximumABeats;
    output << "<div class=\"load-row\"><span>A" << lane
        << "</span><div class=\"track\"><div class=\"fill\" style=\"width:"
        << fixed(width, 3) << "%\"></div></div><strong>"
        << input.aChannels[lane].size() << "</strong></div>";
  }
  output << "</div></section></div></div><main><section><h2>消费端校验</h2>"
      "<div class=\"table-wrap\"><table><thead><tr><th>消费端</th><th>A 地址</th>"
      "<th>A 期望</th><th>A 已消费</th><th>A checksum</th><th>X 已消费</th>"
      "<th>X checksum</th><th>状态</th></tr></thead><tbody>";
  for (std::size_t lane = 0; lane < status.size(); ++lane) {
    output << "<tr><td>C" << lane << " / A" << lane << "</td><td class=\"mono\">"
        << hex64(input.aAddresses[lane]) << "</td><td>" << input.aChannels[lane].size()
        << "</td><td>" << *status[lane].aBeats << "</td><td class=\"mono\">"
        << hex64(*status[lane].aChecksum) << "</td><td>" << *status[lane].xBeats
        << "</td><td class=\"mono\">" << hex64(*status[lane].xChecksum)
        << "</td><td class=\"pass\">通过</td></tr>";
  }
  output << "</tbody></table></div>";
  if (pipelineAvailable) {
    output << "<div class=\"actions\"><a href=\"pipeline.html\" target=\"_blank\" "
        "rel=\"noopener\">查看输入流水时间线</a></div>";
  }
  output << "</section></main><footer>consumer 暂只校验输入完整性，不执行 SpMV 乘加。</footer>"
      "</body></html>";
}

void writePipelineReport(const fs::path& path, const InputSimulationData& input,
                         const std::vector<CycleRecord>& cycles) {
  std::ofstream output(path);
  if (!output) throw std::runtime_error("无法写入 SPMV 流水报告: " + path.string());

  output << R"HTML(<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SPMV 输入流水时间线</title><style>:root{color-scheme:light;--bg:#f6f7f9;--ink:#17202a;--muted:#65717e;--line:#d7dce2;--col-stream:82px;--col-kind:116px;--col-beats:92px;--timeline-width:720px;--request:#67489a;--address:#16697a;--data:#386641;--done:#a36716}*{box-sizing:border-box}body{height:100vh;margin:0;overflow:hidden;display:flex;flex-direction:column;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,sans-serif}header{padding:20px 24px 14px;background:#fff;border-bottom:1px solid var(--line)}.titlebar{display:flex;justify-content:space-between;gap:12px;align-items:center}h1{font-size:22px;margin:0 0 6px;letter-spacing:0}.home{color:#155f78;text-decoration:none;border:1px solid #82919f;border-radius:4px;padding:6px 9px;white-space:nowrap}.summary,.legend,.controls{display:flex;gap:14px;flex-wrap:wrap;align-items:center}.summary{color:var(--muted)}.legend{padding:10px 24px;background:#fff;border-bottom:1px solid var(--line)}.key:before{content:'';display:inline-block;width:12px;height:12px;margin-right:5px;background:var(--c);vertical-align:-1px}.controls{padding:12px 24px}.controls input[type=search]{min-width:260px;padding:7px 9px;border:1px solid #aeb6bf;border-radius:4px}button,select,input{font:inherit}.viewport{flex:1;min-height:0;overflow:auto;scrollbar-gutter:stable;scrollbar-color:#788592 #e4e8ec;border-top:1px solid var(--line);border-bottom:1px solid var(--line);background:#fff;overscroll-behavior:contain}.viewport::-webkit-scrollbar{width:14px;height:14px}.viewport::-webkit-scrollbar-track{background:#e4e8ec}.viewport::-webkit-scrollbar-thumb{background:#788592;border:3px solid #e4e8ec;border-radius:7px}.hscroll{position:relative;flex:0 0 18px;background:#e4e8ec;border-bottom:1px solid var(--line);cursor:pointer;touch-action:none}.hscroll[hidden]{display:none}.hscroll-thumb{position:absolute;top:3px;left:0;height:12px;min-width:40px;border-radius:6px;background:#788592;cursor:grab}.hscroll-thumb:active{cursor:grabbing;background:#596875}.hscroll:focus-visible{outline:2px solid #16697a;outline-offset:-2px}.head,.row{display:grid;grid-template-columns:var(--col-stream) var(--col-kind) var(--col-beats) var(--timeline-width);width:calc(var(--col-stream) + var(--col-kind) + var(--col-beats) + var(--timeline-width))}.head{position:sticky;top:0;z-index:3;background:#eef1f4;font-weight:650}.head>div,.meta{padding:7px 8px;border-right:1px solid var(--line)}.row{min-height:42px;border-top:1px solid #eceff2}.row:hover{background:#f8fbff}.meta{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;align-content:center}.head>div:nth-child(-n+3),.row>.meta:nth-child(-n+3){position:sticky;z-index:2;background:#fff}.head>div:nth-child(-n+3){z-index:4;background:#eef1f4}.row:hover>.meta:nth-child(-n+3){background:#f8fbff}.head>div:nth-child(1),.row>.meta:nth-child(1){left:0}.head>div:nth-child(2),.row>.meta:nth-child(2){left:var(--col-stream)}.head>div:nth-child(3),.row>.meta:nth-child(3){left:calc(var(--col-stream) + var(--col-kind));box-shadow:5px 0 7px -6px #59636e}.timeline{position:relative;min-height:41px;background-image:linear-gradient(to right,rgba(70,80,90,.12) 1px,transparent 1px);background-size:var(--cell) 100%}.event{position:absolute;top:6px;height:29px;color:#fff;padding:5px 3px;overflow:hidden;white-space:nowrap;font-size:11px;font-weight:650;background:var(--c);border:0}.event.done{box-shadow:inset 0 0 0 3px var(--done)}.axis{color:var(--muted);font-size:12px}.footer{padding:12px 24px;color:var(--muted)}.empty{padding:30px;color:var(--muted)}@media(max-width:700px){:root{--col-stream:62px;--col-kind:94px;--col-beats:76px}header,.legend,.controls,.footer{padding-left:12px;padding-right:12px}.controls input[type=search]{min-width:100%;width:100%}}@media(max-width:520px){:root{--col-stream:52px;--col-kind:78px;--col-beats:66px}.head>div,.meta{padding-left:5px;padding-right:5px}}</style></head><body><header><div class="titlebar"><h1>SPMV 输入流水时间线</h1><a class="home" href="performance.html">返回性能主页</a></div><div class="summary" id="summary"></div></header><div class="legend"><span class="key" style="--c:var(--request)">请求接受</span><span class="key" style="--c:var(--address)">AXI AR</span><span class="key" style="--c:var(--data)">HBM R / 消费</span><span class="key" style="--c:var(--done)">完成边框</span></div><div class="controls"><input id="search" type="search" placeholder="搜索 A0..A15 或 X"><label>周期宽度 <input id="zoom" type="range" min="5" max="28" value="14"></label></div><div class="viewport"><div class="head"><div>输入</div><div>连接</div><div>beat</div><div class="axis">周期时间线</div></div><div id="rows"></div></div><div class="hscroll" id="hscroll" role="scrollbar" aria-label="时间线横向滚动" aria-orientation="horizontal" tabindex="0"><div class="hscroll-thumb" id="hscrollThumb"></div></div><div class="footer">空白周期表示该输入没有发生握手；悬停事件可查看当拍进度。</div><script>const trace={"dataset":)HTML";
  writeJsonString(output, input.dataset);
  output << ",\"cycles\":" << cycles.size() << ",\"aExpected\":[";
  for (std::size_t lane = 0; lane < input.aChannels.size(); ++lane) {
    if (lane != 0) output << ',';
    output << input.aChannels[lane].size();
  }
  output << "],\"xExpected\":" << input.xBeats.size() << ",\"records\":[";
  for (std::size_t index = 0; index < cycles.size(); ++index) {
    if (index != 0) output << ',';
    const CycleRecord& cycle = cycles[index];
    output << "{\"c\":" << cycle.cycle << ",\"q\":" << cycle.requestMask
        << ",\"a\":" << cycle.addressMask << ",\"r\":" << cycle.dataMask
        << ",\"d\":" << cycle.doneMask << ",\"xq\":" << cycle.xRequest
        << ",\"xa\":" << cycle.xAddress << ",\"xr\":" << cycle.xData
        << ",\"xd\":" << cycle.xDone << ",\"amin\":" << cycle.minimumABeats
        << ",\"amax\":" << cycle.maximumABeats << ",\"xp\":" << cycle.xBeats << '}';
  }
  output << R"HTML(]};const colors={q:'var(--request)',a:'var(--address)',r:'var(--data)',d:'var(--done)'};const viewport=document.querySelector('.viewport'),hscroll=document.querySelector('#hscroll'),hscrollThumb=document.querySelector('#hscrollThumb'),rows=document.querySelector('#rows'),search=document.querySelector('#search'),zoom=document.querySelector('#zoom');const streams=trace.aExpected.map((beats,index)=>({name:`A${index}`,kind:`consumer C${index}`,beats,lane:index}));streams.push({name:'X',kind:'广播到 C0..C15',beats:trace.xExpected,lane:16});document.querySelector('#summary').textContent=`${trace.dataset} · ${trace.cycles.toLocaleString()} cycles · 16 路 A 独立输入 · 1 路 X 原子广播`;function eventFor(record,lane){const bit=lane<16?1<<lane:0,types=[];if(lane<16){if(record.q&bit)types.push('q');if(record.a&bit)types.push('a');if(record.r&bit)types.push('r');if(record.d&bit)types.push('d')}else{if(record.xq)types.push('q');if(record.xa)types.push('a');if(record.xr)types.push('r');if(record.xd)types.push('d')}return types}function render(){const query=search.value.trim().toLowerCase(),cell=+zoom.value,visible=streams.filter(stream=>!query||stream.name.toLowerCase().includes(query)||stream.kind.toLowerCase().includes(query)),timelineWidth=Math.max(720,trace.cycles*cell);viewport.style.setProperty('--timeline-width',timelineWidth+'px');rows.textContent='';for(const stream of visible){const row=document.createElement('div');row.className='row';for(const value of [stream.name,stream.kind,stream.beats]){const meta=document.createElement('div');meta.className='meta';meta.textContent=value;meta.title=String(value);row.appendChild(meta)}const line=document.createElement('div');line.className='timeline';line.style.setProperty('--cell',cell+'px');for(const record of trace.records){const types=eventFor(record,stream.lane);if(!types.length)continue;const event=document.createElement('div'),primary=types.includes('r')?'r':types.includes('a')?'a':types.includes('q')?'q':'d';event.className='event'+(types.includes('d')?' done':'');event.style.setProperty('--c',colors[primary]);event.style.left=(record.c*cell)+'px';event.style.width=Math.max(5,cell)+'px';event.textContent=types.filter(type=>type!=='d').map(type=>({q:'Q',a:'AR',r:'R'}[type])).join('+')+(types.includes('d')?'✓':'');event.title=`周期 ${record.c}：${types.map(type=>({q:'请求接受',a:'AXI AR',r:'HBM R / 消费',d:'完成'}[type])).join('、')}；A 进度 ${record.amin}..${record.amax}，X 进度 ${record.xp}`;line.appendChild(event)}row.appendChild(line);rows.appendChild(row)}if(!visible.length)rows.innerHTML='<div class="empty">没有匹配的输入通道。</div>';requestAnimationFrame(syncHorizontalScrollbar)}function horizontalScrollMax(){return Math.max(0,viewport.scrollWidth-viewport.offsetWidth)}function syncHorizontalScrollbar(){const max=horizontalScrollMax(),width=hscroll.clientWidth,thumbWidth=max?Math.max(40,width*viewport.offsetWidth/viewport.scrollWidth):width,travel=Math.max(0,width-thumbWidth);hscroll.hidden=max===0;hscrollThumb.style.width=thumbWidth+'px';hscrollThumb.style.transform=`translateX(${max?viewport.scrollLeft/max*travel:0}px)`;hscroll.setAttribute('aria-valuemin','0');hscroll.setAttribute('aria-valuemax',String(max));hscroll.setAttribute('aria-valuenow',String(Math.round(viewport.scrollLeft)))}let drag=null;hscrollThumb.addEventListener('pointerdown',event=>{event.preventDefault();drag={x:event.clientX,left:viewport.scrollLeft};hscrollThumb.setPointerCapture(event.pointerId)});hscrollThumb.addEventListener('pointermove',event=>{if(!drag)return;const max=horizontalScrollMax(),travel=hscroll.clientWidth-hscrollThumb.offsetWidth;viewport.scrollLeft=drag.left+(event.clientX-drag.x)*max/Math.max(1,travel)});hscrollThumb.addEventListener('pointerup',event=>{drag=null;hscrollThumb.releasePointerCapture(event.pointerId)});hscroll.addEventListener('pointerdown',event=>{if(event.target!==hscroll)return;const rect=hscroll.getBoundingClientRect(),max=horizontalScrollMax(),travel=rect.width-hscrollThumb.offsetWidth;viewport.scrollLeft=((event.clientX-rect.left-hscrollThumb.offsetWidth/2)/Math.max(1,travel))*max});hscroll.addEventListener('keydown',event=>{const max=horizontalScrollMax(),step=Math.max(40,viewport.offsetWidth*.8);if(event.key==='ArrowLeft')viewport.scrollLeft-=40;else if(event.key==='ArrowRight')viewport.scrollLeft+=40;else if(event.key==='PageUp')viewport.scrollLeft-=step;else if(event.key==='PageDown')viewport.scrollLeft+=step;else if(event.key==='Home')viewport.scrollLeft=0;else if(event.key==='End')viewport.scrollLeft=max;else return;event.preventDefault()});viewport.addEventListener('scroll',syncHorizontalScrollbar);window.addEventListener('resize',syncHorizontalScrollbar);search.addEventListener('input',render);zoom.addEventListener('input',render);render();</script></body></html>)HTML";
}

void drivePort(DutPort& port, HbmModel& model) {
  *port.requestValid = !model.requestAccepted && !model.beats.empty();
  *port.requestAddress = model.base;
  *port.requestBeats = static_cast<IData>(model.beats.size());
  *port.arReady = model.burstBeats.size() < model.maxOutstandingBursts;
  *port.rValid = !model.burstBeats.empty();
  *port.rId = 0;
  *port.rResponse = 0;
  *port.rLast = !model.burstBeats.empty() && model.burstBeats.front() == 1;
  if (!model.burstBeats.empty()) driveBeat(*port.rData, model.beats.at(model.nextDataBeat));
  else clearBeat(*port.rData);
}

void acceptAddress(const DutPort& port, HbmModel& model) {
  if (model.burstBeats.size() >= model.maxOutstandingBursts) {
    throw std::runtime_error("reader 发出的 outstanding burst 超过满带宽模型容量");
  }
  if (*port.arSize != 6 || *port.arBurst != 1 || (*port.arAddress & (kBeatBytes - 1U)) != 0) {
    throw std::runtime_error("reader 发出了非法 512-bit AXI AR");
  }
  if (*port.arAddress < model.base) throw std::runtime_error("reader AR 地址低于输入基地址");
  const std::uint64_t byteOffset = *port.arAddress - model.base;
  if (byteOffset % kBeatBytes != 0 || byteOffset / kBeatBytes != model.nextIssuedBeat) {
    throw std::runtime_error("reader AR 地址没有连续覆盖输入 beat");
  }
  const std::size_t beats = static_cast<std::size_t>(*port.arLength) + 1U;
  if (beats > model.beats.size() - model.nextIssuedBeat ||
      ((*port.arAddress & 0xfffU) + beats * kBeatBytes) > 4096U) {
    throw std::runtime_error("reader AXI burst 越过输入末尾或 4 KiB 边界");
  }
  model.nextIssuedBeat += beats;
  model.burstBeats.push_back(beats);
}

void consumeData(HbmModel& model) {
  if (model.burstBeats.empty()) throw std::runtime_error("HBM R 握手没有对应的已接受 AR");
  ++model.nextDataBeat;
  if (--model.burstBeats.front() == 0) model.burstBeats.pop_front();
}

void validateContinuousStream(const std::vector<CycleRecord>& cycles, std::size_t lane,
                              std::size_t expectedBeats) {
  std::size_t observed = 0;
  bool started = false;
  for (const CycleRecord& cycle : cycles) {
    const bool fire = lane < kAReaderCount ?
        (cycle.dataMask & (1U << lane)) != 0 : cycle.xData;
    if (fire) {
      if (observed == expectedBeats) throw std::runtime_error("满带宽输入在完成后又出现 R beat");
      started = true;
      ++observed;
    } else if (started && observed < expectedBeats) {
      throw std::runtime_error("满带宽输入的连续 R 区间出现空拍，lane=" +
          std::to_string(lane));
    }
  }
  if (observed != expectedBeats) {
    throw std::runtime_error("满带宽输入的 R beat 数不完整，lane=" + std::to_string(lane));
  }
}

void validateFullBandwidth(const std::vector<CycleRecord>& cycles,
                           const InputSimulationData& input) {
  constexpr std::uint16_t kAllAReaders = 0xffffU;
  if (cycles.size() < 3 || cycles[0].requestMask != kAllAReaders || !cycles[0].xRequest ||
      cycles[1].addressMask != kAllAReaders || !cycles[1].xAddress ||
      cycles[2].dataMask != kAllAReaders || !cycles[2].xData) {
    throw std::runtime_error("满带宽输入要求 Q、AR、首个 R 分别在连续三拍覆盖全部输入");
  }
  for (std::size_t index = 1; index < cycles.size(); ++index) {
    if (cycles[index].requestMask != 0 || cycles[index].xRequest) {
      throw std::runtime_error("满带宽输入请求不能在首拍之后重复握手");
    }
  }
  for (std::size_t lane = 0; lane < kAReaderCount; ++lane) {
    validateContinuousStream(cycles, lane, input.aChannels[lane].size());
  }
  validateContinuousStream(cycles, kAReaderCount, input.xBeats.size());
}

}  // namespace

InputSimulationResult runInputSimulation(const InputSimulationData& input) {
  if (input.aChannels.size() != kAReaderCount || input.aAddresses.size() != kAReaderCount ||
      input.xBeats.empty()) {
    throw std::invalid_argument("SPMV transaction simulation requires 16 A streams and one X stream");
  }
  if (input.pipelineHtml && !input.performanceHtml) {
    throw std::invalid_argument("SPMV 流水 HTML 必须在性能 HTML 主页之上启用");
  }
  if (input.maxOutstandingBursts < 2) {
    throw std::invalid_argument("满带宽输入至少需要两笔 outstanding burst");
  }
  VerilatedContext context;
  context.commandArgs(0, static_cast<char**>(nullptr));
  VSpmvInputTop dut(&context);
  auto ports = aPorts(dut);
  auto status = consumers(dut);
  DutPort x = xPort(dut);
  std::array<HbmModel, kAReaderCount> aModels;
  for (std::size_t lane = 0; lane < kAReaderCount; ++lane) {
    aModels[lane].base = input.aAddresses[lane];
    aModels[lane].beats = input.aChannels[lane];
    aModels[lane].maxOutstandingBursts = input.maxOutstandingBursts;
    aModels[lane].requestAccepted = input.aChannels[lane].empty();
  }
  HbmModel xModel;
  xModel.base = input.xAddress;
  xModel.beats = input.xBeats;
  xModel.maxOutstandingBursts = input.maxOutstandingBursts;

  for (DutPort& port : ports) {
    *port.requestValid = 0;
    *port.arReady = 0;
    *port.rValid = 0;
    *port.rId = 0;
    *port.rResponse = 0;
    *port.rLast = 0;
    clearBeat(*port.rData);
  }
  *x.requestValid = 0;
  *x.arReady = 0;
  *x.rValid = 0;
  *x.rId = 0;
  *x.rResponse = 0;
  *x.rLast = 0;
  clearBeat(*x.rData);
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

  std::vector<CycleRecord> cycles;
  constexpr std::uint64_t kMaximumCycles = 2000000;
  std::uint64_t cycleCount = 0;
  std::uint64_t addressCount = 0;
  bool complete = false;
  for (std::uint64_t cycle = 0; cycle < kMaximumCycles; ++cycle) {
    for (std::size_t lane = 0; lane < ports.size(); ++lane) {
      drivePort(ports[lane], aModels[lane]);
    }
    drivePort(x, xModel);
    dut.eval();

    CycleRecord record;
    record.cycle = cycle;
    std::array<bool, kAReaderCount> requestFire{};
    std::array<bool, kAReaderCount> addressFire{};
    std::array<bool, kAReaderCount> dataFire{};
    for (std::size_t lane = 0; lane < ports.size(); ++lane) {
      requestFire[lane] = *ports[lane].requestValid && *ports[lane].requestReady;
      addressFire[lane] = *ports[lane].arValid && *ports[lane].arReady;
      dataFire[lane] = *ports[lane].rValid && *ports[lane].rReady;
      if (requestFire[lane]) record.requestMask |= static_cast<std::uint16_t>(1U << lane);
      if (addressFire[lane]) record.addressMask |= static_cast<std::uint16_t>(1U << lane);
      if (dataFire[lane]) record.dataMask |= static_cast<std::uint16_t>(1U << lane);
    }
    const bool xRequestFire = *x.requestValid && *x.requestReady;
    const bool xAddressFire = *x.arValid && *x.arReady;
    const bool xDataFire = *x.rValid && *x.rReady;
    record.xRequest = xRequestFire;
    record.xAddress = xAddressFire;
    record.xData = xDataFire;

    // AR 信息只在握手周期有效，必须在时钟沿改变 reader 状态前完成协议校验。
    for (std::size_t lane = 0; lane < ports.size(); ++lane) {
      if (addressFire[lane]) acceptAddress(ports[lane], aModels[lane]);
    }
    if (xAddressFire) acceptAddress(x, xModel);

    dut.clock = 1;
    dut.eval();
    dut.clock = 0;
    dut.eval();

    // done 和消费计数是本次上升沿产生的状态，边沿后采样才能记录最后一个 beat。
    record.minimumABeats = std::numeric_limits<std::uint32_t>::max();
    for (std::size_t lane = 0; lane < ports.size(); ++lane) {
      if (*ports[lane].done) record.doneMask |= static_cast<std::uint16_t>(1U << lane);
      record.minimumABeats = std::min(record.minimumABeats, *status[lane].aBeats);
      record.maximumABeats = std::max(record.maximumABeats, *status[lane].aBeats);
    }
    record.xDone = *x.done;
    record.xBeats = *status.front().xBeats;
    cycleCount = cycle + 1U;
    addressCount += bitCount(record.addressMask) + (record.xAddress ? 1U : 0U);
    cycles.push_back(record);

    for (std::size_t lane = 0; lane < ports.size(); ++lane) {
      if (requestFire[lane]) aModels[lane].requestAccepted = true;
      if (dataFire[lane]) consumeData(aModels[lane]);
    }
    if (xRequestFire) xModel.requestAccepted = true;
    if (xDataFire) consumeData(xModel);

    complete = xModel.nextDataBeat == xModel.beats.size() && xModel.burstBeats.empty() &&
        *x.idle && std::all_of(aModels.begin(), aModels.end(), [](const HbmModel& model) {
          return model.nextDataBeat == model.beats.size() && model.burstBeats.empty();
        }) && std::all_of(ports.begin(), ports.end(), [](const DutPort& port) {
          return *port.idle != 0;
        });
    if (complete) break;
  }
  if (!complete) throw std::runtime_error("SPMV input transaction simulation timed out");
  validateFullBandwidth(cycles, input);

  const std::uint64_t expectedXChecksum = checksum(input.xBeats);
  for (std::size_t lane = 0; lane < status.size(); ++lane) {
    const std::uint64_t expectedAChecksum = checksum(input.aChannels[lane]);
    if (*status[lane].aBeats != input.aChannels[lane].size() ||
        *status[lane].xBeats != input.xBeats.size() ||
        *status[lane].aChecksum != expectedAChecksum ||
        *status[lane].xChecksum != expectedXChecksum || *status[lane].error ||
        *ports[lane].error || *x.error) {
      throw std::runtime_error("SPMV consumer count/checksum validation failed at lane " +
          std::to_string(lane));
    }
  }

  InputSimulationResult result;
  result.cycles = cycleCount;
  if (input.performanceHtml) {
    const fs::path runDirectory = reportDirectory(input.dataset);
    if (input.pipelineHtml) {
      result.pipelineReport = runDirectory / "pipeline.html";
      writePipelineReport(result.pipelineReport, input, cycles);
    }
    result.performanceReport = runDirectory / "performance.html";
    writePerformanceReport(result.performanceReport, input, status, cycleCount,
        addressCount, input.pipelineHtml);
    updateLatestReport(runDirectory);
  }
  dut.final();
  return result;
}

}  // namespace accelerator_sim::spmv

#endif
