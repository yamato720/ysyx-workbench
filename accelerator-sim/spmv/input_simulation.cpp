#include "input_simulation.hpp"

#ifdef SPMV_INPUT_TRANSACTION_VERILATOR

#include "VSpmvInputTop.h"
#include "verilated.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <limits>
#include <numeric>
#include <optional>
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
constexpr std::size_t kXReaderCount = 2;
constexpr std::size_t kCtrlReaderCount = 1;
constexpr std::size_t kWordsPerBeat = 16;
constexpr std::size_t kBeatBytes = 64;
constexpr std::size_t kSlotsPerABeat = 8;
constexpr std::size_t kFp64MulLatency = SPMV_FP64_MUL_LATENCY_FROZEN;
constexpr std::size_t kFp64MulInitiationInterval = SPMV_FP64_MUL_II_FROZEN;
constexpr std::size_t kFp64MulResponseFifoDepth =
    SPMV_FP64_MUL_RESPONSE_FIFO_DEPTH_FROZEN;
constexpr std::size_t kFp64MulLaneCount = SPMV_FP64_MUL_LANES_FROZEN;
constexpr std::size_t kFp64MulCoreCount = SPMV_FP64_MUL_CORE_COUNT_FROZEN;
constexpr std::size_t kFp64MulTotalLaneCount = SPMV_FP64_MUL_TOTAL_LANES_FROZEN;
static_assert(kFp64MulLatency > 0 && kFp64MulInitiationInterval > 0 &&
    kFp64MulResponseFifoDepth > 0 && kFp64MulLaneCount == kSlotsPerABeat &&
    kFp64MulCoreCount == kAReaderCount &&
    kFp64MulTotalLaneCount == kFp64MulCoreCount * kFp64MulLaneCount);

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
  IData* ctrlBeats;
  QData* aChecksum;
  QData* xChecksum;
  QData* ctrlChecksum;
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
  std::uint8_t xRequestMask = 0;
  std::uint8_t xAddressMask = 0;
  std::uint8_t xDataMask = 0;
  std::uint8_t xDoneMask = 0;
  std::uint8_t ctrlRequestMask = 0;
  std::uint8_t ctrlAddressMask = 0;
  std::uint8_t ctrlDataMask = 0;
  std::uint8_t ctrlDoneMask = 0;
  std::uint32_t minimumABeats = 0;
  std::uint32_t maximumABeats = 0;
  std::uint32_t xBeats = 0;
};

struct MulTimingRecord {
  std::uint64_t cycle = 0;
  std::size_t batch = 0;
  std::uint16_t beatAcceptedMask = 0;
  std::array<std::uint8_t, kAReaderCount> validSlotMasks{};
  std::array<std::uint8_t, kAReaderCount> paddingMasks{};
  std::array<std::uint8_t, kAReaderCount> xReadMasks{};
  std::array<std::uint8_t, kAReaderCount> mulRequestMasks{};
  std::array<std::uint8_t, kAReaderCount> mulResponseMasks{};
  std::uint16_t computeDoneMask = 0;
  bool mulReady = false;
  bool streamsComplete = false;
  bool computeDone = false;
};

struct MulTimingPort {
  CData* beatAccepted;
  CData* validSlotMask;
  CData* paddingMask;
  CData* xReadMask;
  CData* mulRequestMask;
  CData* mulResponseMask;
  CData* computeDone;
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
    &dut.io_consumerCtrlBeats_##index, \
    &dut.io_consumerAChecksum_##index, &dut.io_consumerXChecksum_##index, \
    &dut.io_consumerCtrlChecksum_##index, \
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

#define SPMV_X_DUT_PORT(index) DutPort{ \
    &dut.io_xRequest_##index##_ready, &dut.io_xRequest_##index##_valid, \
    &dut.io_xRequest_##index##_bits_address, &dut.io_xRequest_##index##_bits_beats, \
    &dut.io_xHbm_##index##_ar_ready, &dut.io_xHbm_##index##_ar_valid, \
    &dut.io_xHbm_##index##_ar_bits_addr, &dut.io_xHbm_##index##_ar_bits_len, \
    &dut.io_xHbm_##index##_ar_bits_size, &dut.io_xHbm_##index##_ar_bits_burst, \
    &dut.io_xHbm_##index##_r_ready, &dut.io_xHbm_##index##_r_valid, \
    &dut.io_xHbm_##index##_r_bits_id, &dut.io_xHbm_##index##_r_bits_data, \
    &dut.io_xHbm_##index##_r_bits_resp, &dut.io_xHbm_##index##_r_bits_last, \
    &dut.io_xIdle_##index, &dut.io_xDone_##index, &dut.io_xError_##index}

std::array<DutPort, kXReaderCount> xPorts(VSpmvInputTop& dut) {
  return {{SPMV_X_DUT_PORT(0), SPMV_X_DUT_PORT(1)}};
}

#define SPMV_CTRL_DUT_PORT(index) DutPort{ \
    &dut.io_ctrlRequest_##index##_ready, &dut.io_ctrlRequest_##index##_valid, \
    &dut.io_ctrlRequest_##index##_bits_address, &dut.io_ctrlRequest_##index##_bits_beats, \
    &dut.io_ctrlHbm_##index##_ar_ready, &dut.io_ctrlHbm_##index##_ar_valid, \
    &dut.io_ctrlHbm_##index##_ar_bits_addr, &dut.io_ctrlHbm_##index##_ar_bits_len, \
    &dut.io_ctrlHbm_##index##_ar_bits_size, &dut.io_ctrlHbm_##index##_ar_bits_burst, \
    &dut.io_ctrlHbm_##index##_r_ready, &dut.io_ctrlHbm_##index##_r_valid, \
    &dut.io_ctrlHbm_##index##_r_bits_id, &dut.io_ctrlHbm_##index##_r_bits_data, \
    &dut.io_ctrlHbm_##index##_r_bits_resp, &dut.io_ctrlHbm_##index##_r_bits_last, \
    &dut.io_ctrlIdle_##index, &dut.io_ctrlDone_##index, &dut.io_ctrlError_##index}

std::array<DutPort, kCtrlReaderCount> ctrlPorts(VSpmvInputTop& dut) {
  return {{SPMV_CTRL_DUT_PORT(0)}};
}

#define SPMV_MUL_TIMING_PORT(index) MulTimingPort{ \
    &dut.io_timingBeatAcceptedByChannel_##index, \
    &dut.io_timingValidSlotMaskByChannel_##index, \
    &dut.io_timingPaddingMaskByChannel_##index, \
    &dut.io_timingXReadMaskByChannel_##index, \
    &dut.io_timingMulRequestMaskByChannel_##index, \
    &dut.io_timingMulResponseMaskByChannel_##index, \
    &dut.io_timingComputeDoneByChannel_##index}

std::array<MulTimingPort, kAReaderCount> mulTimingPorts(VSpmvInputTop& dut) {
  return {{
      SPMV_MUL_TIMING_PORT(0), SPMV_MUL_TIMING_PORT(1),
      SPMV_MUL_TIMING_PORT(2), SPMV_MUL_TIMING_PORT(3),
      SPMV_MUL_TIMING_PORT(4), SPMV_MUL_TIMING_PORT(5),
      SPMV_MUL_TIMING_PORT(6), SPMV_MUL_TIMING_PORT(7),
      SPMV_MUL_TIMING_PORT(8), SPMV_MUL_TIMING_PORT(9),
      SPMV_MUL_TIMING_PORT(10), SPMV_MUL_TIMING_PORT(11),
      SPMV_MUL_TIMING_PORT(12), SPMV_MUL_TIMING_PORT(13),
      SPMV_MUL_TIMING_PORT(14), SPMV_MUL_TIMING_PORT(15)}};
}

#undef SPMV_A_DUT_PORT
#undef SPMV_X_DUT_PORT
#undef SPMV_CTRL_DUT_PORT
#undef SPMV_CONSUMER_STATUS
#undef SPMV_MUL_TIMING_PORT

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

std::size_t totalXBeats(const InputSimulationData& input) {
  std::size_t result = 0;
  for (const auto& channel : input.xChannels) result += channel.size();
  return result;
}

std::uint64_t xChecksum(const InputSimulationData& input) {
  std::uint64_t result = 0;
  for (const auto& channel : input.xChannels) result ^= checksum(channel);
  return result;
}

std::uint64_t validSlotCount(const std::vector<encoding::cuper::CuperBeat>& beats) {
  std::uint64_t result = 0;
  for (const auto& beat : beats) {
    for (std::uint64_t slot : beat) {
      result += !encoding::cuper::decodeSlot(slot).padding;
    }
  }
  return result;
}

void idlePort(DutPort& port) {
  *port.requestValid = 0;
  *port.arReady = 0;
  *port.rValid = 0;
  *port.rId = 0;
  *port.rResponse = 0;
  *port.rLast = 0;
  clearBeat(*port.rData);
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

void resetReadModel(HbmModel& model) {
  model.nextIssuedBeat = 0;
  model.nextDataBeat = 0;
  model.burstBeats.clear();
  model.requestAccepted = model.beats.empty();
}

bool streamsComplete(const std::array<HbmModel, kAReaderCount>& models,
                     const std::array<DutPort, kAReaderCount>& ports) {
  return std::all_of(models.begin(), models.end(), [](const HbmModel& model) {
           return model.nextDataBeat == model.beats.size() && model.burstBeats.empty();
         }) && std::all_of(ports.begin(), ports.end(), [](const DutPort& port) {
           return *port.idle != 0;
         });
}

void validateContinuousStream(const std::vector<CycleRecord>& cycles, std::size_t inputKind,
                              std::size_t lane, std::size_t expectedBeats) {
  std::size_t observed = 0;
  bool started = false;
  for (const CycleRecord& cycle : cycles) {
    bool fire = false;
    switch (inputKind) {
      case 0: fire = (cycle.dataMask & (1U << lane)) != 0; break;
      case 1: fire = (cycle.xDataMask & (1U << lane)) != 0; break;
      case 2: fire = (cycle.ctrlDataMask & (1U << lane)) != 0; break;
      default: throw std::invalid_argument("未知的 SPMV 输入时序类型");
    }
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
                            bool inputPipelineAvailable, bool timingPipelineAvailable,
                            const InputSimulationResult& result) {
  std::ofstream output(path);
  if (!output) throw std::runtime_error("无法写入 SPMV 性能报告: " + path.string());

  std::uint64_t totalABeats = 0;
  for (const auto& channel : input.aChannels) totalABeats += channel.size();
  const std::size_t xBeats = totalXBeats(input);
  const std::uint64_t ctrlBeats = input.ctrlChannel.size();
  const std::uint64_t hbmBeats = totalABeats + xBeats + ctrlBeats;
  const std::uint64_t broadcastBeats = xBeats * kAReaderCount;
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
  writeMetric(output, "X 输入", std::to_string(xBeats), "512-bit beats");
  writeMetric(output, "A 通道", std::to_string(input.aChannels.size()), "独立 reader");
  writeMetric(output, "A 最小负载", std::to_string(minimumABeats), "beats / channel");
  writeMetric(output, "A 最大负载", std::to_string(maximumABeats), "beats / channel");
  writeMetric(output, "X 广播交付", std::to_string(broadcastBeats), "consumer beats");
  writeMetric(output, "Ctrl 输入", std::to_string(ctrlBeats), "512-bit beats");
  writeMetric(output, "Cuper 窗口", std::to_string(input.batches.size()), "X 载入 / A 回放");
  if (result.multiplyCompared) {
    writeMetric(output, "FP64 乘法", std::to_string(result.mulCycles),
        "Cuper 分窗口乘法 IP 验证周期");
  }
  output << "</div></section><section><h2>输入配置</h2><div class=\"pipeline-meta\">"
      "<span class=\"badge\">16 路 A reader</span><span class=\"badge\">2 路 X reader</span>"
      "<span class=\"badge\">1 路 Ctrl reader</span>"
      "<span class=\"badge\">16 个消费端</span><span class=\"badge\">X 双 beat 原子广播</span>"
      "<span class=\"badge\">Ctrl 广播</span>"
      << (result.multiplyCompared
          ? "<span class=\"badge\">Mixed-V3 Cuper 分窗口 FP64 乘法</span>"
          : "<span class=\"badge\">仅输入校验</span>")
      << "<span class=\"badge\">Ctrl → X → A 阶段顺序</span><span class=\"badge\">2 outstanding bursts</span>"
      "<span class=\"badge\">AR burst "
      << addressCount << " 次</span></div><div class=\"table-wrap\"><table><thead><tr>"
      "<th>地址窗口</th><th>A 最小..最大</th><th>A 通道差</th><th>X / Ctrl 地址</th>"
      "<th>HBM 输入 beat</th><th>协议</th></tr></thead><tbody><tr><td class=\"mono\">"
      << hex64(input.hbmBase) << " + " << input.hbmBytes << " B</td><td>"
      << minimumABeats << ".." << maximumABeats << "</td><td>"
      << maximumABeats - minimumABeats << " beats</td><td class=\"mono\">";
  for (std::size_t channel = 0; channel < input.xAddresses.size(); ++channel) {
    if (channel != 0) output << " / ";
    output << "X" << channel << "=" << hex64(input.xAddresses[channel]);
  }
  output << " / Ctrl=" << hex64(input.ctrlAddress)
      << "</td><td>" << hbmBeats
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
      "<th>X checksum</th><th>Ctrl 已消费</th><th>Ctrl checksum</th>"
      "<th>状态</th></tr></thead><tbody>";
  for (std::size_t lane = 0; lane < status.size(); ++lane) {
    output << "<tr><td>C" << lane << " / A" << lane << "</td><td class=\"mono\">"
        << hex64(input.aAddresses[lane]) << "</td><td>" << input.aChannels[lane].size()
        << "</td><td>" << *status[lane].aBeats << "</td><td class=\"mono\">"
        << hex64(*status[lane].aChecksum) << "</td><td>" << *status[lane].xBeats
        << "</td><td class=\"mono\">" << hex64(*status[lane].xChecksum)
        << "</td><td>" << *status[lane].ctrlBeats
        << "</td><td class=\"mono\">" << hex64(*status[lane].ctrlChecksum)
        << "</td><td class=\"pass\">通过</td></tr>";
  }
  output << "</tbody></table></div>";
  if (inputPipelineAvailable || timingPipelineAvailable) {
    output << "<div class=\"actions\">";
    if (inputPipelineAvailable) {
      output << "<a href=\"input-pipeline.html\" target=\"_blank\" "
          "rel=\"noopener\">查看输入流水时间线</a>";
    }
    if (timingPipelineAvailable) {
      output << "<a href=\"timing-pipeline.html\" target=\"_blank\" "
          "rel=\"noopener\">查看 FP64 乘法流水统计</a>";
    }
    output << "</div>";
  }
  output << "</section></main><footer>"
      << (result.multiplyCompared
          ? "Ctrl map 只载入一次；每个 Cuper 窗口依次载入 X、回放 map 对应的 A 区间，并完成 Mixed-V3 FP64 乘法验证。"
          : "Ctrl map 和 X 载入完成后执行 A 输入校验。")
      << "</footer></body></html>";
}

void writeInputPipelineReport(const fs::path& path, const InputSimulationData& input,
                              const std::vector<CycleRecord>& cycles) {
  std::ofstream output(path);
  if (!output) throw std::runtime_error("无法写入 SPMV 流水报告: " + path.string());

  output << R"HTML(<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SPMV 输入流水时间线</title><style>:root{color-scheme:light;--bg:#f6f7f9;--ink:#17202a;--muted:#65717e;--line:#d7dce2;--col-stream:82px;--col-kind:116px;--col-beats:92px;--timeline-width:720px;--request:#67489a;--address:#16697a;--data:#386641;--done:#a36716}*{box-sizing:border-box}body{height:100vh;margin:0;overflow:hidden;display:flex;flex-direction:column;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,sans-serif}header{padding:20px 24px 14px;background:#fff;border-bottom:1px solid var(--line)}.titlebar{display:flex;justify-content:space-between;gap:12px;align-items:center}h1{font-size:22px;margin:0 0 6px;letter-spacing:0}.home{color:#155f78;text-decoration:none;border:1px solid #82919f;border-radius:4px;padding:6px 9px;white-space:nowrap}.summary,.legend,.controls{display:flex;gap:14px;flex-wrap:wrap;align-items:center}.summary{color:var(--muted)}.legend{padding:10px 24px;background:#fff;border-bottom:1px solid var(--line)}.key:before{content:'';display:inline-block;width:12px;height:12px;margin-right:5px;background:var(--c);vertical-align:-1px}.controls{padding:12px 24px}.controls input[type=search]{min-width:260px;padding:7px 9px;border:1px solid #aeb6bf;border-radius:4px}button,select,input{font:inherit}.viewport{flex:1;min-height:0;overflow:auto;scrollbar-gutter:stable;scrollbar-color:#788592 #e4e8ec;border-top:1px solid var(--line);border-bottom:1px solid var(--line);background:#fff;overscroll-behavior:contain}.viewport::-webkit-scrollbar{width:14px;height:14px}.viewport::-webkit-scrollbar-track{background:#e4e8ec}.viewport::-webkit-scrollbar-thumb{background:#788592;border:3px solid #e4e8ec;border-radius:7px}.hscroll{position:relative;flex:0 0 18px;background:#e4e8ec;border-bottom:1px solid var(--line);cursor:pointer;touch-action:none}.hscroll[hidden]{display:none}.hscroll-thumb{position:absolute;top:3px;left:0;height:12px;min-width:40px;border-radius:6px;background:#788592;cursor:grab}.hscroll-thumb:active{cursor:grabbing;background:#596875}.hscroll:focus-visible{outline:2px solid #16697a;outline-offset:-2px}.head,.row{display:grid;grid-template-columns:var(--col-stream) var(--col-kind) var(--col-beats) var(--timeline-width);width:calc(var(--col-stream) + var(--col-kind) + var(--col-beats) + var(--timeline-width))}.head{position:sticky;top:0;z-index:3;background:#eef1f4;font-weight:650}.head>div,.meta{padding:7px 8px;border-right:1px solid var(--line)}.row{min-height:42px;border-top:1px solid #eceff2}.row:hover{background:#f8fbff}.meta{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;align-content:center}.head>div:nth-child(-n+3),.row>.meta:nth-child(-n+3){position:sticky;z-index:2;background:#fff}.head>div:nth-child(-n+3){z-index:4;background:#eef1f4}.row:hover>.meta:nth-child(-n+3){background:#f8fbff}.head>div:nth-child(1),.row>.meta:nth-child(1){left:0}.head>div:nth-child(2),.row>.meta:nth-child(2){left:var(--col-stream)}.head>div:nth-child(3),.row>.meta:nth-child(3){left:calc(var(--col-stream) + var(--col-kind));box-shadow:5px 0 7px -6px #59636e}.timeline{position:relative;min-height:41px;background-image:linear-gradient(to right,rgba(70,80,90,.12) 1px,transparent 1px);background-size:var(--cell) 100%}.event{position:absolute;top:6px;height:29px;color:#fff;padding:5px 3px;overflow:hidden;white-space:nowrap;font-size:11px;font-weight:650;background:var(--c);border:0}.event.done{box-shadow:inset 0 0 0 3px var(--done)}.axis{color:var(--muted);font-size:12px}.footer{padding:12px 24px;color:var(--muted)}.empty{padding:30px;color:var(--muted)}@media(max-width:700px){:root{--col-stream:62px;--col-kind:94px;--col-beats:76px}header,.legend,.controls,.footer{padding-left:12px;padding-right:12px}.controls input[type=search]{min-width:100%;width:100%}}@media(max-width:520px){:root{--col-stream:52px;--col-kind:78px;--col-beats:66px}.head>div,.meta{padding-left:5px;padding-right:5px}}</style></head><body><header><div class="titlebar"><h1>SPMV 输入流水时间线</h1><a class="home" href="performance.html">返回性能主页</a></div><div class="summary" id="summary"></div></header><div class="legend"><span class="key" style="--c:var(--request)">请求接受</span><span class="key" style="--c:var(--address)">AXI AR</span><span class="key" style="--c:var(--data)">HBM R / 消费</span><span class="key" style="--c:var(--done)">完成边框</span></div><div class="controls"><input id="search" type="search" placeholder="搜索 A0..A15、X 或 Ctrl"><label>周期宽度 <input id="zoom" type="range" min="5" max="28" value="14"></label></div><div class="viewport"><div class="head"><div>输入</div><div>连接</div><div>beat</div><div class="axis">周期时间线</div></div><div id="rows"></div></div><div class="hscroll" id="hscroll" role="scrollbar" aria-label="时间线横向滚动" aria-orientation="horizontal" tabindex="0"><div class="hscroll-thumb" id="hscrollThumb"></div></div><div class="footer">空白周期表示该输入没有发生握手；悬停事件可查看当拍进度。</div><script>const trace={"dataset":)HTML";
  writeJsonString(output, input.dataset);
  output << ",\"cycles\":" << cycles.size() << ",\"aExpected\":[";
  for (std::size_t lane = 0; lane < input.aChannels.size(); ++lane) {
    if (lane != 0) output << ',';
    output << input.aChannels[lane].size();
  }
  output << "],\"xExpected\":[";
  for (std::size_t channel = 0; channel < input.xChannels.size(); ++channel) {
    if (channel != 0) output << ',';
    output << input.xChannels[channel].size();
  }
  output << "],\"ctrlExpected\":" << input.ctrlChannel.size()
      << ",\"batchCount\":" << input.batches.size() << ",\"records\":[";
  for (std::size_t index = 0; index < cycles.size(); ++index) {
    if (index != 0) output << ',';
    const CycleRecord& cycle = cycles[index];
    output << "{\"c\":" << cycle.cycle << ",\"q\":" << cycle.requestMask
        << ",\"a\":" << cycle.addressMask << ",\"r\":" << cycle.dataMask
        << ",\"d\":" << cycle.doneMask << ",\"xq\":"
        << static_cast<unsigned>(cycle.xRequestMask) << ",\"xa\":"
        << static_cast<unsigned>(cycle.xAddressMask) << ",\"xr\":"
        << static_cast<unsigned>(cycle.xDataMask) << ",\"xd\":"
        << static_cast<unsigned>(cycle.xDoneMask) << ",\"cq\":"
        << static_cast<unsigned>(cycle.ctrlRequestMask) << ",\"ca\":"
        << static_cast<unsigned>(cycle.ctrlAddressMask) << ",\"cr\":"
        << static_cast<unsigned>(cycle.ctrlDataMask) << ",\"cd\":"
        << static_cast<unsigned>(cycle.ctrlDoneMask) << ",\"amin\":" << cycle.minimumABeats
        << ",\"amax\":" << cycle.maximumABeats << ",\"xp\":" << cycle.xBeats << '}';
  }
  output << R"HTML(]};const colors={q:'var(--request)',a:'var(--address)',r:'var(--data)',d:'var(--done)'};const viewport=document.querySelector('.viewport'),hscroll=document.querySelector('#hscroll'),hscrollThumb=document.querySelector('#hscrollThumb'),rows=document.querySelector('#rows'),search=document.querySelector('#search'),zoom=document.querySelector('#zoom');const streams=[{name:'Ctrl',kind:'控制面广播到 C0..C15',beats:trace.ctrlExpected,lane:18},...trace.xExpected.map((beats,index)=>({name:`X${index}`,kind:'条带广播到 C0..C15',beats,lane:16+index})),...trace.aExpected.map((beats,index)=>({name:`A${index}`,kind:`consumer C${index} / Mixed-V3`,beats,lane:index}))];document.querySelector('#summary').textContent=`${trace.dataset} · ${trace.cycles.toLocaleString()} cycles · Ctrl → X0/X1 → A0..A15 单遍 FP64 乘法`;function eventFor(record,lane){const types=[];if(lane<16){const bit=1<<lane;if(record.q&bit)types.push('q');if(record.a&bit)types.push('a');if(record.r&bit)types.push('r');if(record.d&bit)types.push('d')}else if(lane<18){const bit=1<<(lane-16);if(record.xq&bit)types.push('q');if(record.xa&bit)types.push('a');if(record.xr&bit)types.push('r');if(record.xd&bit)types.push('d')}else{if(record.cq&1)types.push('q');if(record.ca&1)types.push('a');if(record.cr&1)types.push('r');if(record.cd&1)types.push('d')}return types}function render(){const query=search.value.trim().toLowerCase(),cell=+zoom.value,visible=streams.filter(stream=>!query||stream.name.toLowerCase().includes(query)||stream.kind.toLowerCase().includes(query)),timelineWidth=Math.max(720,trace.cycles*cell);viewport.style.setProperty('--timeline-width',timelineWidth+'px');rows.textContent='';for(const stream of visible){const row=document.createElement('div');row.className='row';for(const value of [stream.name,stream.kind,stream.beats]){const meta=document.createElement('div');meta.className='meta';meta.textContent=value;meta.title=String(value);row.appendChild(meta)}const line=document.createElement('div');line.className='timeline';line.style.setProperty('--cell',cell+'px');for(const record of trace.records){const types=eventFor(record,stream.lane);if(!types.length)continue;const event=document.createElement('div'),primary=types.includes('r')?'r':types.includes('a')?'a':types.includes('q')?'q':'d';event.className='event'+(types.includes('d')?' done':'');event.style.setProperty('--c',colors[primary]);event.style.left=(record.c*cell)+'px';event.style.width=Math.max(5,cell)+'px';event.textContent=types.filter(type=>type!=='d').map(type=>({q:'Q',a:'AR',r:'R'}[type])).join('+')+(types.includes('d')?'✓':'');event.title=`周期 ${record.c}：${types.map(type=>({q:'请求接受',a:'AXI AR',r:'HBM R / 消费',d:'完成'}[type])).join('、')}；A 进度 ${record.amin}..${record.amax}，X 进度 ${record.xp}`;line.appendChild(event)}row.appendChild(line);rows.appendChild(row)}if(!visible.length)rows.innerHTML='<div class="empty">没有匹配的输入通道。</div>';requestAnimationFrame(syncHorizontalScrollbar)}function horizontalScrollMax(){return Math.max(0,viewport.scrollWidth-viewport.offsetWidth)}function syncHorizontalScrollbar(){const max=horizontalScrollMax(),width=hscroll.clientWidth,thumbWidth=max?Math.max(40,width*viewport.offsetWidth/viewport.scrollWidth):width,travel=Math.max(0,width-thumbWidth);hscroll.hidden=max===0;hscrollThumb.style.width=thumbWidth+'px';hscrollThumb.style.transform=`translateX(${max?viewport.scrollLeft/max*travel:0}px)`;hscroll.setAttribute('aria-valuemin','0');hscroll.setAttribute('aria-valuemax',String(max));hscroll.setAttribute('aria-valuenow',String(Math.round(viewport.scrollLeft)))}let drag=null;hscrollThumb.addEventListener('pointerdown',event=>{event.preventDefault();drag={x:event.clientX,left:viewport.scrollLeft};hscrollThumb.setPointerCapture(event.pointerId)});hscrollThumb.addEventListener('pointermove',event=>{if(!drag)return;const max=horizontalScrollMax(),travel=hscroll.clientWidth-hscrollThumb.offsetWidth;viewport.scrollLeft=drag.left+(event.clientX-drag.x)*max/Math.max(1,travel)});hscrollThumb.addEventListener('pointerup',event=>{drag=null;hscrollThumb.releasePointerCapture(event.pointerId)});hscroll.addEventListener('pointerdown',event=>{if(event.target!==hscroll)return;const rect=hscroll.getBoundingClientRect(),max=horizontalScrollMax(),travel=rect.width-hscrollThumb.offsetWidth;viewport.scrollLeft=((event.clientX-rect.left-hscrollThumb.offsetWidth/2)/Math.max(1,travel))*max});hscroll.addEventListener('keydown',event=>{const max=horizontalScrollMax(),step=Math.max(40,viewport.offsetWidth*.8);if(event.key==='ArrowLeft')viewport.scrollLeft-=40;else if(event.key==='ArrowRight')viewport.scrollLeft+=40;else if(event.key==='PageUp')viewport.scrollLeft-=step;else if(event.key==='PageDown')viewport.scrollLeft+=step;else if(event.key==='Home')viewport.scrollLeft=0;else if(event.key==='End')viewport.scrollLeft=max;else return;event.preventDefault()});viewport.addEventListener('scroll',syncHorizontalScrollbar);window.addEventListener('resize',syncHorizontalScrollbar);search.addEventListener('input',render);zoom.addEventListener('input',render);render();</script></body></html>)HTML";
}

void writeTimingPipelineReport(const fs::path& path, const InputSimulationData& input,
                               const std::vector<MulTimingRecord>& records) {
  std::ofstream output(path);
  if (!output) throw std::runtime_error("无法写入 SPMV 计算时序报告: " + path.string());
  if (input.batches.empty()) {
    throw std::invalid_argument("SPMV 计算时序报告缺少 Cuper batch 元数据");
  }

  std::array<std::uint64_t, kAReaderCount> expectedBeats{};
  std::array<std::uint64_t, kAReaderCount> expectedValidSlots{};
  std::uint64_t expectedMultiply = 0;
  for (std::size_t core = 0; core < kAReaderCount; ++core) {
    expectedBeats[core] = input.aChannels[core].size();
    expectedValidSlots[core] = validSlotCount(input.aChannels[core]);
    expectedMultiply += expectedValidSlots[core];
  }

  output << R"HTML(<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SPMV FP64 乘法计算流水</title><style>
:root{color-scheme:light;--bg:#f4f7f8;--ink:#17242c;--muted:#62707b;--line:#d6dfe3;--panel:#fff;--issue:#146d83;--response:#34754e;--inflight:#735196;--decode:#3f6fba;--bubble:#b6632a;--good:#17643b;--warn:#914c15;--col-name:126px;--col-kind:180px;--col-count:90px;--timeline-width:760px}*{box-sizing:border-box}body{height:100vh;min-height:100vh;margin:0;overflow:auto;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,sans-serif}header{padding:18px max(20px,calc((100vw - 1240px)/2));background:#fff;border-bottom:1px solid var(--line)}.titlebar,.nav,.summary,.controls,.legend{display:flex;align-items:center;flex-wrap:wrap}.titlebar{justify-content:space-between;gap:12px}.nav{gap:8px}.nav a,.controls button{padding:6px 9px;border:1px solid #82919f;border-radius:4px;color:#155f78;background:#fff;text-decoration:none;white-space:nowrap;font:inherit;cursor:pointer}h1{margin:0;font-size:23px;letter-spacing:0}.summary{gap:10px;margin-top:5px;color:var(--muted)}main{max-width:1240px;margin:0 auto;padding:18px 20px 34px}section{margin:0 0 24px}h2{margin:0;font-size:17px;letter-spacing:0}.verdict{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:18px;align-items:center;padding:16px 18px;background:#fff;border:1px solid var(--line);border-left:5px solid var(--inflight)}.verdict.warning{border-left-color:var(--bubble)}.verdict.good{border-left-color:var(--good)}.eyebrow{margin:0 0 4px;color:var(--muted);font-size:12px;font-weight:650}.verdict h2{font-size:20px}.verdict p:last-child{margin:5px 0 0;color:var(--muted)}.verdict-value{text-align:right}.verdict-value strong{display:block;font-size:28px}.verdict-value span{color:var(--muted);font-size:12px}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin-top:10px}.metric{min-width:0;padding:11px 12px;background:var(--panel);border:1px solid var(--line);border-radius:5px}.metric span,.metric small{display:block;color:var(--muted);font-size:12px}.metric strong{display:block;margin:4px 0 1px;font-size:21px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.overview{padding:14px;background:#fff;border:1px solid var(--line);margin-top:10px}.overview canvas{display:block;width:100%;height:178px}.legend{gap:14px;margin:10px 0 0;color:var(--muted);font-size:12px}.key:before{content:'';display:inline-block;width:12px;height:12px;margin-right:5px;background:var(--c);vertical-align:-1px}.overview-meta{margin-top:8px;color:var(--muted);font-size:12px}.table-wrap{overflow:auto;border:1px solid var(--line);background:#fff}.core-table{width:100%;border-collapse:collapse;white-space:nowrap}.core-table th,.core-table td{padding:8px 10px;border-bottom:1px solid #e7edef;text-align:right}.core-table th{position:sticky;top:0;background:#edf2f4;color:#46525e;font-size:12px}.core-table th:first-child,.core-table td:first-child{text-align:left}.core-table tbody tr:hover{background:#f8fbfc}.pass{color:var(--good);font-weight:650}.fail{color:#a24233;font-weight:650}.controls{justify-content:space-between;gap:12px;margin:10px 0}.control-group{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.controls select,.controls input{font:inherit}.controls select{padding:6px 8px;border:1px solid #9aa8b2;border-radius:4px;background:#fff}.controls input[type=range]{width:220px;accent-color:var(--issue)}.window-status{color:var(--muted);font-size:12px}.viewport{min-height:0;overflow:auto;scrollbar-gutter:stable;border:1px solid var(--line);background:#fff;overscroll-behavior:contain}.head,.row{display:grid;grid-template-columns:var(--col-name) var(--col-kind) var(--col-count) var(--timeline-width);width:calc(var(--col-name) + var(--col-kind) + var(--col-count) + var(--timeline-width))}.head{position:sticky;top:0;z-index:3;background:#edf2f4;font-weight:650}.head>div,.meta{padding:7px 8px;border-right:1px solid var(--line)}.row{min-height:40px;border-top:1px solid #e9eef0}.row:hover{background:#f8fbfc}.meta{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;align-content:center}.head>div:nth-child(-n+3),.row>.meta:nth-child(-n+3){position:sticky;z-index:2;background:#fff}.head>div:nth-child(-n+3){z-index:4;background:#edf2f4}.row:hover>.meta:nth-child(-n+3){background:#f8fbfc}.head>div:nth-child(1),.row>.meta:nth-child(1){left:0}.head>div:nth-child(2),.row>.meta:nth-child(2){left:var(--col-name)}.head>div:nth-child(3),.row>.meta:nth-child(3){left:calc(var(--col-name) + var(--col-kind));box-shadow:5px 0 7px -6px #596a74}.timeline{position:relative;min-height:39px;background-image:linear-gradient(to right,rgba(70,80,90,.13) 1px,transparent 1px);background-size:var(--cell) 100%}.event{position:absolute;top:6px;height:27px;min-width:2px;padding:4px 3px;overflow:hidden;white-space:nowrap;color:#fff;background:var(--c);font-size:11px;font-weight:650}.hscroll{position:relative;height:18px;margin-top:2px;background:#e4e9ec;border:1px solid var(--line);cursor:pointer;touch-action:none}.hscroll[hidden]{display:none}.hscroll-thumb{position:absolute;top:3px;left:0;height:10px;min-width:40px;background:#748590;cursor:grab}.hscroll-thumb:active{cursor:grabbing;background:#596a74}.footer{max-width:1240px;margin:0 auto;padding:0 20px 24px;color:var(--muted);font-size:12px}.empty{padding:28px;color:var(--muted)}@media(max-width:900px){.metrics{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:700px){:root{--col-name:90px;--col-kind:126px;--col-count:72px}header,main,.footer{padding-left:12px;padding-right:12px}.verdict{grid-template-columns:1fr}.verdict-value{text-align:left}.controls input[type=range]{width:100%;order:3}.control-group{width:100%}.metric strong{font-size:18px}}@media(max-width:520px){.metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.controls button{margin-left:auto}}
</style></head><body><header><div class="titlebar"><h1>SPMV FP64 乘法计算流水</h1><nav class="nav"><a href="performance.html">性能主页</a><a href="input-pipeline.html">输入流水</a></nav></div><div class="summary" id="summary"></div></header><main><section class="verdict" id="verdict"><div><p class="eyebrow">FMUL 流水判定</p><h2 id="verdictTitle"></h2><p id="verdictDetail"></p></div><div class="verdict-value"><strong id="verdictValue"></strong><span>PE 计算前端 II=1</span></div></section><section><h2>计算专用指标</h2><div class="metrics" id="metrics"></div></section><section><h2>16 个 Cuper PE 状态</h2><div class="table-wrap"><table class="core-table"><thead><tr><th>PE</th><th>A beat</th><th>A 平均 II</th><th>FMUL 请求</th><th>连续发射</th><th>lane 利用</th><th>最大在飞</th><th>掩码对齐</th><th>流水</th></tr></thead><tbody id="coreRows"></tbody></table></div></section><section><h2>聚合乘法窗口</h2><div class="overview"><canvas id="overview" aria-label="全部 PE 的 FP64 乘法请求、响应与在飞操作概览"></canvas><div class="legend"><span class="key" style="--c:var(--issue)">FMUL req</span><span class="key" style="--c:var(--response)">FMUL resp</span><span class="key" style="--c:var(--inflight)">IP 在飞深度</span></div><div class="overview-meta" id="overviewMeta"></div></div></section><section><h2>计算窗口明细</h2><div class="controls"><div class="control-group"><label>观察对象 <select id="coreSelect"></select></label><label>窗口周期 <select id="windowSize"><option value="64">64</option><option value="128" selected>128</option><option value="256">256</option><option value="512">512</option><option value="1024">1024</option><option value="all">全部</option></select></label><button id="fullWindow" type="button">全窗口</button></div><div class="control-group"><label>窗口起点 <input id="windowStart" type="range"></label><span class="window-status" id="windowStatus"></span></div></div><div class="viewport"><div class="head"><div>阶段</div><div>含义</div><div>周期</div><div>计算周期</div></div><div id="rows"></div></div><div class="hscroll" id="hscroll" role="scrollbar" aria-label="计算窗口横向滚动" aria-orientation="horizontal" tabindex="0"><div class="hscroll-thumb" id="hscrollThumb"></div></div></section></main><footer>统计范围仅为 `mulEnable` 后的计算窗口。每个 PE 分别检查 A beat II=1、下一拍 local_X/FMUL 掩码传递、固定响应延迟及请求排空；padding 造成的无有效乘法周期单独标记，不算作控制气泡。</footer><script>const timingTrace={"dataset":)HTML";
  writeJsonString(output, input.dataset);
  output << ",\"coreCount\":" << kAReaderCount
      << ",\"lanesPerCore\":" << kFp64MulLaneCount
      << ",\"mulLatency\":" << kFp64MulLatency
      << ",\"mulII\":" << kFp64MulInitiationInterval
      << ",\"mulResponseFifoDepth\":" << kFp64MulResponseFifoDepth
      << ",\"expectedMultiply\":" << expectedMultiply << ",\"expectedBeats\":[";
  for (std::size_t core = 0; core < kAReaderCount; ++core) {
    if (core != 0) output << ',';
    output << expectedBeats[core];
  }
  output << "],\"expectedValid\":[";
  for (std::size_t core = 0; core < kAReaderCount; ++core) {
    if (core != 0) output << ',';
    output << expectedValidSlots[core];
  }
  output << "],\"batches\":[";
  for (std::size_t batchIndex = 0; batchIndex < input.batches.size(); ++batchIndex) {
    if (batchIndex != 0) output << ',';
    const InputSimulationBatch& batch = input.batches[batchIndex];
    std::uint64_t batchExpectedMultiply = 0;
    output << "{\"index\":" << batchIndex << ",\"expectedBeats\":[";
    for (std::size_t core = 0; core < kAReaderCount; ++core) {
      if (core != 0) output << ',';
      output << batch.aChannels[core].size();
    }
    output << "],\"expectedValid\":[";
    for (std::size_t core = 0; core < kAReaderCount; ++core) {
      if (core != 0) output << ',';
      const std::uint64_t valid = validSlotCount(batch.aChannels[core]);
      batchExpectedMultiply += valid;
      output << valid;
    }
    output << "],\"expectedMultiply\":" << batchExpectedMultiply << '}';
  }
  output << "],\"records\":[";
  const auto writeMasks = [&output](const std::array<std::uint8_t, kAReaderCount>& masks) {
    output << '[';
    for (std::size_t core = 0; core < masks.size(); ++core) {
      if (core != 0) output << ',';
      output << static_cast<unsigned>(masks[core]);
    }
    output << ']';
  };
  for (std::size_t index = 0; index < records.size(); ++index) {
    if (index != 0) output << ',';
    const MulTimingRecord& record = records[index];
    output << "{\"c\":" << record.cycle << ",\"w\":" << record.batch
        << ",\"b\":" << record.beatAcceptedMask
        << ",\"v\":";
    writeMasks(record.validSlotMasks);
    output << ",\"p\":";
    writeMasks(record.paddingMasks);
    output << ",\"x\":";
    writeMasks(record.xReadMasks);
    output << ",\"q\":";
    writeMasks(record.mulRequestMasks);
    output << ",\"r\":";
    writeMasks(record.mulResponseMasks);
    output << ",\"d\":" << record.computeDoneMask
        << ",\"ready\":" << static_cast<unsigned>(record.mulReady)
        << ",\"streams\":" << static_cast<unsigned>(record.streamsComplete)
        << ",\"done\":" << static_cast<unsigned>(record.computeDone) << '}';
  }
  output << R"HTML(]};
const colors={issue:'#146d83',response:'#34754e',inflight:'#735196',decode:'#3f6fba',bubble:'#b6632a'};
const coreCount=timingTrace.coreCount,lanes=timingTrace.lanesPerCore;
const requestedBatch=Number.parseInt(new URLSearchParams(window.location.search).get('batch')||'0',10);
const selectedBatch=Number.isInteger(requestedBatch)&&requestedBatch>=0&&requestedBatch<timingTrace.batches.length?requestedBatch:0;
const batchInfo=timingTrace.batches[selectedBatch];
timingTrace.expectedBeats=batchInfo.expectedBeats;
timingTrace.expectedValid=batchInfo.expectedValid;
timingTrace.expectedMultiply=batchInfo.expectedMultiply;
const records=timingTrace.records.filter(record=>record.w===selectedBatch).map((record,index)=>({...record,c:index,g:record.c}));
const popcount=mask=>{let value=mask>>>0,count=0;while(value!==0){count+=value&1;value>>>=1}return count};
const sum=values=>values.reduce((total,value)=>total+value,0),average=values=>values.length?sum(values)/values.length:0;
const format=value=>Number.isInteger(value)?value.toLocaleString():value.toFixed(2);
const longestRun=cycles=>{if(!cycles.length)return 0;let best=1,run=1;for(let index=1;index<cycles.length;index+=1){run=cycles[index]===cycles[index-1]+timingTrace.mulII?run+1:1;best=Math.max(best,run)}return best};
function coreStats(core){const bit=1<<core,accepted=[],issued=[],responded=[],pending=Array.from({length:lanes},()=>[]),latencies=[];let expectedMask=0,stageMismatches=0,inFlight=0,peakInFlight=0,valid=0,padding=0,requests=0,responses=0,paddingOnly=0,latencyErrors=0;for(const record of records){const requestMask=record.q[core],responseMask=record.r[core];if(requestMask!==expectedMask)stageMismatches+=1;const acceptedNow=(record.b&bit)!==0;if(acceptedNow){accepted.push(record.c);valid+=popcount(record.v[core]);padding+=popcount(record.p[core]);if(record.v[core]===0)paddingOnly+=1}for(let lane=0;lane<lanes;lane+=1){const laneBit=1<<lane;if(requestMask&laneBit)pending[lane].push(record.c);if(responseMask&laneBit){const issuedAt=pending[lane].shift();if(issuedAt===undefined||record.c-issuedAt!==timingTrace.mulLatency)latencyErrors+=1;else latencies.push(record.c-issuedAt)}}const requestCount=popcount(requestMask),responseCount=popcount(responseMask);requests+=requestCount;responses+=responseCount;inFlight+=requestCount-responseCount;peakInFlight=Math.max(peakInFlight,inFlight);if(requestMask)issued.push(record.c);if(responseMask)responded.push(record.c);expectedMask=acceptedNow?record.v[core]:0}const beatGaps=accepted.slice(1).map((cycle,index)=>cycle-accepted[index]),issueGaps=issued.slice(1).map((cycle,index)=>cycle-issued[index]);const bubbleCycles=sum(beatGaps.map(gap=>Math.max(0,gap-1))),pendingCount=sum(pending.map(queue=>queue.length));const expectedBeats=timingTrace.expectedBeats[core],expectedValid=timingTrace.expectedValid[core];const structuralOk=accepted.length===expectedBeats&&bubbleCycles===0&&stageMismatches===0&&valid===expectedValid&&requests===expectedValid&&responses===expectedValid&&pendingCount===0&&latencyErrors===0&&inFlight===0;return{core,accepted,issued,responded,beatGaps,issueGaps,bubbleCycles,stageMismatches,valid,padding,requests,responses,paddingOnly,peakInFlight,averageInFlight:0,latencies,pendingCount,latencyErrors,inFlight,expectedBeats,expectedValid,structuralOk,beatII:average(beatGaps),issueII:average(issueGaps),longestIssueRun:longestRun(issued),issueDensity:accepted.length?issued.length/accepted.length:0,laneUtilization:accepted.length?requests/(accepted.length*lanes):0}};
const stats=Array.from({length:coreCount},(_,core)=>coreStats(core));
const flowingCores=stats.filter(stat=>stat.structuralOk).length,totalRequests=sum(stats.map(stat=>stat.requests)),totalResponses=sum(stats.map(stat=>stat.responses)),totalValid=sum(stats.map(stat=>stat.valid)),totalPadding=sum(stats.map(stat=>stat.padding)),totalPaddingOnly=sum(stats.map(stat=>stat.paddingOnly)),totalMismatches=sum(stats.map(stat=>stat.stageMismatches));
let aggregateInFlight=0,aggregatePeak=0;const aggregateRequestCycles=records.filter(record=>record.q.some(mask=>mask!==0)).map(record=>record.c),aggregateResponseCycles=records.filter(record=>record.r.some(mask=>mask!==0)).map(record=>record.c);for(const record of records){aggregateInFlight+=sum(record.q.map(popcount))-sum(record.r.map(popcount));aggregatePeak=Math.max(aggregatePeak,aggregateInFlight)}
const aggregateIssueGaps=aggregateRequestCycles.slice(1).map((cycle,index)=>cycle-aggregateRequestCycles[index]),expectedDepth=coreCount*lanes*Math.ceil(timingTrace.mulLatency/timingTrace.mulII),allFlowing=flowingCores===coreCount,allIssueContinuous=stats.every(stat=>stat.issued.length<2||stat.issueGaps.every(gap=>gap===timingTrace.mulII));
const observedLatency=stats.flatMap(stat=>stat.latencies);const latencyRange=observedLatency.length?`${Math.min(...observedLatency)}..${Math.max(...observedLatency)}`:'--';
document.querySelector('#summary').textContent=`${timingTrace.dataset} · Cuper 窗口 ${selectedBatch+1}/${timingTrace.batches.length} · ${records.length.toLocaleString()} 个计算周期 · ${coreCount} 个独立 PE · ${lanes*coreCount} 条 FP64 IP lane`;
const verdict=document.querySelector('#verdict'),verdictTitle=document.querySelector('#verdictTitle'),verdictDetail=document.querySelector('#verdictDetail'),verdictValue=document.querySelector('#verdictValue');verdict.classList.add(allFlowing?'good':'warning');verdictTitle.textContent=allFlowing?'全部 PE 的 A→FMUL 计算前端为 II=1':'存在未流起的 PE 计算前端';verdictDetail.textContent=allFlowing?`16 个 PE 均保持 A beat 连续接收、有效 lane 下一拍发射、${timingTrace.mulLatency}-cycle 响应排空。${totalPaddingOnly.toLocaleString()} 个 padding-only beat 仅造成数据空窗。`:`通过 ${flowingCores}/${coreCount} 个 PE；掩码错位 ${totalMismatches.toLocaleString()}，请查看每 PE 表与窗口明细。`;verdictValue.textContent=`${flowingCores}/${coreCount}`;
const metrics=[['PE 前端 II=1',`${flowingCores}/${coreCount}`,'A beat 连续、掩码对齐、请求和响应完整'],['有效 FMUL',totalRequests,`期望 ${timingTrace.expectedMultiply.toLocaleString()} · 请求/响应 ${totalRequests}/${totalResponses}`],['FMUL 活跃周期',`${aggregateRequestCycles.length}/${records.length}`,`聚合占比 ${(records.length?aggregateRequestCycles.length/records.length*100:0).toFixed(1)}%`],['总 lane 利用',`${(totalRequests/Math.max(1,records.length*coreCount*lanes)*100).toFixed(1)}%`,`${totalRequests.toLocaleString()} / ${(records.length*coreCount*lanes).toLocaleString()} 计算槽`],['连续 FMUL 发射',longestRun(aggregateRequestCycles),`聚合 II=${format(average(aggregateIssueGaps))} · ${allIssueContinuous?'各 PE 无非空数据间隔':'含 padding 数据空窗'}`],['全 padding A beat',totalPaddingOnly,'不计为控制气泡'],['掩码下一拍对齐',totalMismatches?'异常':'通过',`有效 slot -> FMUL req；错位 ${totalMismatches}`],['IP 在飞',`${aggregatePeak}/${expectedDepth}`,`响应延迟 ${latencyRange} cycles · profile ${timingTrace.mulLatency}`]];
for(const [label,value,detail] of metrics){const node=document.createElement('div');node.className='metric';const title=document.createElement('span'),strong=document.createElement('strong'),small=document.createElement('small');title.textContent=label;strong.textContent=typeof value==='number'?value.toLocaleString():value;small.textContent=detail;node.append(title,strong,small);document.querySelector('#metrics').appendChild(node)}
const coreRows=document.querySelector('#coreRows');for(const stat of stats){const row=document.createElement('tr'),cells=[`PE${stat.core}`,`${stat.accepted.length}/${stat.expectedBeats}`,format(stat.beatII),`${stat.requests}/${stat.expectedValid}`,stat.longestIssueRun,`${(stat.laneUtilization*100).toFixed(1)}%`,stat.peakInFlight,stat.stageMismatches?'异常':'通过',stat.structuralOk?'II=1':'异常'];for(let index=0;index<cells.length;index+=1){const cell=document.createElement('td');cell.textContent=String(cells[index]);if(index===7)cell.className=stat.stageMismatches?'fail':'pass';if(index===8)cell.className=stat.structuralOk?'pass':'fail';row.appendChild(cell)}coreRows.appendChild(row)}
const overview=document.querySelector('#overview');function drawOverview(){const box=overview.getBoundingClientRect(),ratio=window.devicePixelRatio||1,width=Math.max(1,box.width),height=Math.max(1,box.height);overview.width=Math.round(width*ratio);overview.height=Math.round(height*ratio);const context=overview.getContext('2d');context.setTransform(ratio,0,0,ratio,0,0);context.fillStyle='#fbfcfc';context.fillRect(0,0,width,height);const left=46,right=10,top=10,rowHeight=(height-top-12)/3,plotWidth=Math.max(1,width-left-right),range=Math.max(1,records.length),x=cycle=>left+cycle*plotWidth/range;const labels=[['REQ',colors.issue],['RESP',colors.response],['在飞',colors.inflight]];context.font='12px system-ui';context.textBaseline='middle';for(let index=0;index<labels.length;index+=1){const y=top+index*rowHeight;context.fillStyle='#edf2f4';context.fillRect(left,y,plotWidth,rowHeight-4);context.fillStyle='#5e6b75';context.fillText(labels[index][0],4,y+rowHeight/2-2)}let inFlight=0;for(const record of records){const point=x(record.c),requestCount=sum(record.q.map(popcount)),responseCount=sum(record.r.map(popcount));inFlight+=requestCount-responseCount;if(requestCount){context.fillStyle=colors.issue;context.fillRect(point,top+4,Math.max(1,plotWidth/range),rowHeight-12)}if(responseCount){context.fillStyle=colors.response;context.fillRect(point,top+rowHeight+4,Math.max(1,plotWidth/range),rowHeight-12)}if(inFlight){const valueHeight=(rowHeight-10)*inFlight/Math.max(1,aggregatePeak);context.fillStyle=colors.inflight;context.fillRect(point,top+3*rowHeight-6-valueHeight,Math.max(1,plotWidth/range),valueHeight)}}context.fillStyle='#5e6b75';context.textAlign='left';context.fillText('c0',left,height-2);context.textAlign='right';context.fillText(`c${Math.max(0,records.length-1)}`,width-right,height-2)}drawOverview();window.addEventListener('resize',drawOverview);document.querySelector('#overviewMeta').textContent=`${records.length.toLocaleString()} 个计算周期 · ${totalRequests.toLocaleString()} 条 FMUL 请求 · 最大在飞 ${aggregatePeak}/${expectedDepth} · padding slot ${totalPadding.toLocaleString()}`;
const coreSelect=document.querySelector('#coreSelect');const batchSelect=document.createElement('select'),batchLabel=document.createElement('label');batchSelect.id='batchSelect';batchLabel.textContent='Cuper 窗口 ';batchLabel.appendChild(batchSelect);coreSelect.closest('label').before(batchLabel);for(const batch of timingTrace.batches){const option=document.createElement('option');option.value=String(batch.index);option.textContent=`窗口 ${batch.index+1} / ${timingTrace.batches.length}`;batchSelect.appendChild(option)}batchSelect.value=String(selectedBatch);const allOption=document.createElement('option');allOption.value='all';allOption.textContent='聚合 16 个 PE';coreSelect.appendChild(allOption);for(let core=0;core<coreCount;core+=1){const option=document.createElement('option');option.value=String(core);option.textContent=`PE${core}`;coreSelect.appendChild(option)}
const viewport=document.querySelector('.viewport'),rows=document.querySelector('#rows'),windowSize=document.querySelector('#windowSize'),windowStart=document.querySelector('#windowStart'),windowStatus=document.querySelector('#windowStatus'),hscroll=document.querySelector('#hscroll'),thumb=document.querySelector('#hscrollThumb');function selectedCore(){return coreSelect.value==='all'?null:Number(coreSelect.value)}function selectedMask(record,key){const core=selectedCore();return core===null?sum(record[key].map(popcount)):popcount(record[key][core])}function selectedBeat(record){const core=selectedCore();return core===null?popcount(record.b):(record.b&(1<<core)?1:0)}function selectedPadding(record){const core=selectedCore();return core===null?sum(record.p.map(popcount)):popcount(record.p[core])}function selectedInFlight(){const core=selectedCore();let inFlight=0;return records.map(record=>{inFlight+=selectedMask(record,'q')-selectedMask(record,'r');return inFlight})}function selectedWindowSize(){return windowSize.value==='all'?Math.max(1,records.length):Number(windowSize.value)}function updateWindowControl(){const size=selectedWindowSize(),maximum=Math.max(0,records.length-size),current=Math.min(maximum,Math.max(0,Number(windowStart.value)||0));windowStart.min='0';windowStart.max=String(maximum);windowStart.value=String(current);windowStart.disabled=maximum===0}function segments(lane,windowRecords,start){const result=[];for(const record of windowRecords){const event=lane.get(record);if(!event)continue;const previous=result.at(-1);if(lane.group&&previous&&previous.key===event.key&&previous.end+1===record.c){previous.end=record.c}else result.push({...event,start:record.c,end:record.c})}return result}function renderDetail(){updateWindowControl();const size=selectedWindowSize(),start=Number(windowStart.value),end=Math.min(records.length-1,start+size-1),windowRecords=records.filter(record=>record.c>=start&&record.c<=end),inFlights=selectedInFlight(),cell=9,timelineWidth=Math.max(720,windowRecords.length*cell);viewport.style.setProperty('--timeline-width',`${timelineWidth}px`);rows.textContent='';const laneDefs=[{name:'A beat 接受',kind:'PE 接受 512-bit Cuper A beat',get:record=>selectedBeat(record)?{key:'beat',label:`${selectedBeat(record)} beat`,color:'decode'}:null},{name:'local_X read',kind:'有效 lane 的片上 X 读',get:record=>selectedMask(record,'x')?{key:'x',label:`${selectedMask(record,'x')} lane`,color:'inflight'}:null},{name:'FMUL req',kind:'下一拍送入 FP64 IP',get:record=>selectedMask(record,'q')?{key:'req',label:`${selectedMask(record,'q')} lane`,color:'issue'}:null},{name:'IP 在飞',kind:'已请求、未响应的 lane',group:true,get:record=>inFlights[record.c]?{key:`flight-${inFlights[record.c]}`,label:String(inFlights[record.c]),color:'inflight'}:null},{name:'FMUL resp',kind:'FP64 IP 响应',get:record=>selectedMask(record,'r')?{key:'resp',label:`${selectedMask(record,'r')} lane`,color:'response'}:null},{name:'padding slot',kind:'不产生 FMUL 请求的 A slot',get:record=>selectedPadding(record)?{key:'padding',label:`${selectedPadding(record)} slot`,color:'bubble'}:null}];for(const lane of laneDefs){const events=segments(lane,windowRecords,start),row=document.createElement('div');row.className='row';for(const value of [lane.name,lane.kind,events.reduce((total,event)=>total+event.end-event.start+1,0)]){const meta=document.createElement('div');meta.className='meta';meta.textContent=value;meta.title=String(value);row.appendChild(meta)}const timeline=document.createElement('div');timeline.className='timeline';timeline.style.setProperty('--cell',`${cell}px`);for(const item of events){const event=document.createElement('div');event.className='event';event.style.setProperty('--c',colors[item.color]);event.style.left=`${(item.start-start)*cell}px`;event.style.width=`${Math.max(2,(item.end-item.start+1)*cell)}px`;event.textContent=item.label;event.title=`周期 ${item.start}${item.end>item.start?`..${item.end}`:''}：${item.label}`;timeline.appendChild(event)}row.appendChild(timeline);rows.appendChild(row)}windowStatus.textContent=`${coreSelect.options[coreSelect.selectedIndex].text} · c${start}..c${end} · req ${sum(windowRecords.map(record=>selectedMask(record,'q'))).toLocaleString()} · resp ${sum(windowRecords.map(record=>selectedMask(record,'r'))).toLocaleString()}`;requestAnimationFrame(syncScroll)}function maxScroll(){return Math.max(0,viewport.scrollWidth-viewport.offsetWidth)}function syncScroll(){const maximum=maxScroll(),width=hscroll.clientWidth,thumbWidth=maximum?Math.max(40,width*viewport.offsetWidth/viewport.scrollWidth):width,travel=Math.max(0,width-thumbWidth);hscroll.hidden=maximum===0;thumb.style.width=`${thumbWidth}px`;thumb.style.transform=`translateX(${maximum?viewport.scrollLeft/maximum*travel:0}px)`;hscroll.setAttribute('aria-valuemin','0');hscroll.setAttribute('aria-valuemax',String(maximum));hscroll.setAttribute('aria-valuenow',String(Math.round(viewport.scrollLeft)))}let drag=null;thumb.addEventListener('pointerdown',event=>{event.preventDefault();drag={x:event.clientX,left:viewport.scrollLeft};thumb.setPointerCapture(event.pointerId)});thumb.addEventListener('pointermove',event=>{if(!drag)return;viewport.scrollLeft=drag.left+(event.clientX-drag.x)*maxScroll()/Math.max(1,hscroll.clientWidth-thumb.offsetWidth)});thumb.addEventListener('pointerup',event=>{drag=null;thumb.releasePointerCapture(event.pointerId)});hscroll.addEventListener('pointerdown',event=>{if(event.target!==hscroll)return;const rect=hscroll.getBoundingClientRect();viewport.scrollLeft=(event.clientX-rect.left-thumb.offsetWidth/2)*maxScroll()/Math.max(1,rect.width-thumb.offsetWidth)});hscroll.addEventListener('keydown',event=>{const step=Math.max(40,viewport.offsetWidth*.8);if(event.key==='ArrowLeft')viewport.scrollLeft-=40;else if(event.key==='ArrowRight')viewport.scrollLeft+=40;else if(event.key==='PageUp')viewport.scrollLeft-=step;else if(event.key==='PageDown')viewport.scrollLeft+=step;else if(event.key==='Home')viewport.scrollLeft=0;else if(event.key==='End')viewport.scrollLeft=maxScroll();else return;event.preventDefault()});viewport.addEventListener('scroll',syncScroll);window.addEventListener('resize',syncScroll);coreSelect.addEventListener('change',()=>{windowStart.value='0';renderDetail()});windowSize.addEventListener('change',()=>{windowStart.value='0';renderDetail()});windowStart.addEventListener('input',renderDetail);document.querySelector('#fullWindow').addEventListener('click',()=>{windowSize.value='all';windowStart.value='0';renderDetail()});renderDetail();
document.querySelector('footer').textContent='统计范围为所选 `mulEnable` 后 Cuper 窗口；A beat II、local_X/FMUL 一拍传递、固定响应延迟和排空均在窗口内检查，窗口间 X 装载不计为计算气泡。';
batchSelect.addEventListener('change',()=>{const query=new URLSearchParams(window.location.search);query.set('batch',batchSelect.value);window.location.search=query.toString()});
</script></body></html>)HTML";
}
void validateSinglePassFlow(const std::vector<CycleRecord>& cycles,
                            const InputSimulationData& input) {
  constexpr std::uint16_t kAllAReaders = 0xffffU;
  constexpr std::uint8_t kAllXReaders = 0x3U;
  constexpr std::uint8_t kAllCtrlReaders = 0x1U;
  const auto firstCycle = [&cycles](auto predicate) {
    const auto found = std::find_if(cycles.begin(), cycles.end(), predicate);
    return found == cycles.end() ? std::numeric_limits<std::uint64_t>::max() : found->cycle;
  };
  const std::uint64_t ctrlQ = firstCycle([](const CycleRecord& record) {
    return record.ctrlRequestMask != 0;
  });
  const std::uint64_t ctrlAr = firstCycle([](const CycleRecord& record) {
    return record.ctrlAddressMask != 0;
  });
  const std::uint64_t ctrlR = firstCycle([](const CycleRecord& record) {
    return record.ctrlDataMask != 0;
  });
  const std::uint64_t ctrlDone = firstCycle([](const CycleRecord& record) {
    return record.ctrlDoneMask != 0;
  });
  const std::uint64_t xQ = firstCycle([](const CycleRecord& record) {
    return record.xRequestMask != 0;
  });
  const std::uint64_t xAr = firstCycle([](const CycleRecord& record) {
    return record.xAddressMask != 0;
  });
  const std::uint64_t xR = firstCycle([](const CycleRecord& record) {
    return record.xDataMask != 0;
  });
  const std::uint64_t xDone = firstCycle([](const CycleRecord& record) {
    return record.xDoneMask != 0;
  });
  const std::uint64_t aQ = firstCycle([](const CycleRecord& record) {
    return record.requestMask != 0;
  });
  const std::uint64_t aAr = firstCycle([](const CycleRecord& record) {
    return record.addressMask != 0;
  });
  const std::uint64_t aR = firstCycle([](const CycleRecord& record) {
    return record.dataMask != 0;
  });
  if (ctrlQ != 0 || ctrlAr != ctrlQ + 1U || ctrlR != ctrlQ + 2U ||
      xQ <= ctrlDone || xAr != xQ + 1U || xR != xQ + 2U ||
      aQ <= xDone || aAr != aQ + 1U || aR != aQ + 2U) {
      throw std::runtime_error("单遍作业必须按 Ctrl -> X -> mulEnable -> A 的阶段顺序执行");
  }
  std::uint16_t aRequestMask = 0;
  std::uint16_t aAddressMask = 0;
  std::uint16_t aDoneMask = 0;
  std::uint8_t xRequestMask = 0;
  std::uint8_t xAddressMask = 0;
  std::uint8_t xDoneMask = 0;
  std::uint8_t ctrlRequestMask = 0;
  std::uint8_t ctrlAddressMask = 0;
  std::uint8_t ctrlDoneMask = 0;
  for (const CycleRecord& record : cycles) {
    aRequestMask |= record.requestMask;
    aAddressMask |= record.addressMask;
    aDoneMask |= record.doneMask;
    xRequestMask |= record.xRequestMask;
    xAddressMask |= record.xAddressMask;
    xDoneMask |= record.xDoneMask;
    ctrlRequestMask |= record.ctrlRequestMask;
    ctrlAddressMask |= record.ctrlAddressMask;
    ctrlDoneMask |= record.ctrlDoneMask;
  }
  if (aRequestMask != kAllAReaders || aAddressMask != kAllAReaders ||
      aDoneMask != kAllAReaders || xRequestMask != kAllXReaders ||
      xAddressMask != kAllXReaders || xDoneMask != kAllXReaders ||
      ctrlRequestMask != kAllCtrlReaders || ctrlAddressMask != kAllCtrlReaders ||
      ctrlDoneMask != kAllCtrlReaders) {
    throw std::runtime_error("单遍作业没有完整覆盖 Ctrl、X 和 16 路 A 的 Q/AR/done");
  }
  for (std::size_t lane = 0; lane < kAReaderCount; ++lane) {
    const std::size_t observed = static_cast<std::size_t>(std::count_if(
        cycles.begin(), cycles.end(), [lane](const CycleRecord& record) {
          return (record.dataMask & (1U << lane)) != 0;
        }));
    if (observed != input.aChannels[lane].size()) {
      throw std::runtime_error("单遍 FP64 乘法的 A beat 数不完整，lane=" + std::to_string(lane));
    }
  }
  for (std::size_t lane = 0; lane < kXReaderCount; ++lane) {
    validateContinuousStream(cycles, 1, lane, input.xChannels[lane].size());
  }
  validateContinuousStream(cycles, 2, 0, input.ctrlChannel.size());
}

}  // namespace

namespace {

template <std::size_t Count>
void initializeModels(std::array<HbmModel, Count>& models,
                      const std::vector<std::uint64_t>& addresses,
                      const std::vector<std::vector<encoding::cuper::CuperBeat>>& channels,
                      std::size_t maxOutstandingBursts, const char* name) {
  if (addresses.size() != Count || channels.size() != Count) {
    throw std::invalid_argument(std::string("SPMV ") + name + " batch 的 HBM 通道数不匹配");
  }
  for (std::size_t lane = 0; lane < Count; ++lane) {
    models[lane].base = addresses[lane];
    models[lane].beats = channels[lane];
    models[lane].nextIssuedBeat = 0;
    models[lane].nextDataBeat = 0;
    models[lane].burstBeats.clear();
    models[lane].maxOutstandingBursts = maxOutstandingBursts;
    models[lane].requestAccepted = models[lane].beats.empty();
  }
}

template <typename Mask>
void validateContinuousWindowData(const std::vector<CycleRecord>& cycles, std::size_t begin,
                                  std::size_t end, std::size_t expectedBeats,
                                  Mask&& selected, const std::string& name) {
  std::vector<std::uint64_t> observed;
  for (std::size_t index = begin; index < end; ++index) {
    if (selected(cycles[index])) observed.push_back(cycles[index].cycle);
  }
  if (observed.size() != expectedBeats || observed.empty() ||
      std::adjacent_find(observed.begin(), observed.end(),
          [](std::uint64_t previous, std::uint64_t current) {
            return current != previous + 1U;
          }) != observed.end()) {
    throw std::runtime_error(name + " 没有保持逐拍连续的 HBM R 返回");
  }
}

void validateBatchMultiplyTiming(const InputSimulationBatch& batch,
                                 const std::vector<MulTimingRecord>& records,
                                 std::size_t batchIndex) {
  if (records.empty() || !records.back().computeDone ||
      std::count_if(records.begin(), records.end(), [](const MulTimingRecord& record) {
        return record.computeDone;
      }) != 1) {
    throw std::runtime_error("Cuper batch " + std::to_string(batchIndex) +
        " 没有在所有 PE 排空后完成");
  }
  for (std::size_t core = 0; core < kAReaderCount; ++core) {
    const std::uint16_t coreBit = static_cast<std::uint16_t>(1U << core);
    const std::uint64_t expectedBeats = batch.aChannels[core].size();
    const std::uint64_t expectedValid = validSlotCount(batch.aChannels[core]);
    std::uint64_t accepted = 0;
    std::uint64_t valid = 0;
    std::uint64_t padding = 0;
    std::uint64_t xReads = 0;
    std::uint64_t requests = 0;
    std::uint64_t responses = 0;
    std::uint64_t computeDone = 0;
    std::uint8_t stagedMask = 0;
    std::array<std::deque<std::uint64_t>, kSlotsPerABeat> pendingRequests;
    std::vector<std::uint64_t> acceptedCycles;
    for (const MulTimingRecord& timing : records) {
      const std::uint8_t validMask = timing.validSlotMasks[core];
      const std::uint8_t requestMask = timing.mulRequestMasks[core];
      const std::uint8_t responseMask = timing.mulResponseMasks[core];
      const bool acceptedNow = (timing.beatAcceptedMask & coreBit) != 0;
      if (requestMask != stagedMask ||
          timing.xReadMasks[core] != (acceptedNow ? validMask : 0U) ||
          (acceptedNow && (validMask | timing.paddingMasks[core]) != 0xffU) ||
          (!acceptedNow && (validMask != 0U || timing.paddingMasks[core] != 0U))) {
        throw std::runtime_error("Cuper batch " + std::to_string(batchIndex) +
            " 的 A -> local_X -> FMUL 掩码流水错位，PE=" + std::to_string(core));
      }
      if (acceptedNow) {
        ++accepted;
        acceptedCycles.push_back(timing.cycle);
        valid += bitCount(validMask);
        padding += bitCount(timing.paddingMasks[core]);
      }
      xReads += bitCount(timing.xReadMasks[core]);
      requests += bitCount(requestMask);
      responses += bitCount(responseMask);
      for (std::size_t lane = 0; lane < kSlotsPerABeat; ++lane) {
        const std::uint8_t laneBit = static_cast<std::uint8_t>(1U << lane);
        if ((requestMask & laneBit) != 0) pendingRequests[lane].push_back(timing.cycle);
        if ((responseMask & laneBit) != 0) {
          if (pendingRequests[lane].empty() ||
              timing.cycle - pendingRequests[lane].front() != kFp64MulLatency) {
            throw std::runtime_error("Cuper batch " + std::to_string(batchIndex) +
                " 的 FP64 IP req/resp 延迟异常，PE=" + std::to_string(core));
          }
          pendingRequests[lane].pop_front();
        }
      }
      computeDone += (timing.computeDoneMask & coreBit) != 0;
      stagedMask = acceptedNow ? validMask : 0U;
    }
    if (accepted != expectedBeats || valid != expectedValid ||
        padding != expectedBeats * kSlotsPerABeat - expectedValid ||
        xReads != expectedValid || requests != expectedValid || responses != expectedValid ||
        computeDone != 1 ||
        std::adjacent_find(acceptedCycles.begin(), acceptedCycles.end(),
            [](std::uint64_t previous, std::uint64_t current) {
              return current != previous + 1U;
            }) != acceptedCycles.end() ||
        std::any_of(pendingRequests.begin(), pendingRequests.end(),
            [](const std::deque<std::uint64_t>& pending) { return !pending.empty(); })) {
      throw std::runtime_error("Cuper batch " + std::to_string(batchIndex) +
          " 没有保持每 PE A beat II=1 或未完整排空 FMUL，PE=" + std::to_string(core));
    }
  }
}

InputSimulationResult runWindowedInputSimulation(const InputSimulationData& input) {
  if (input.aChannels.size() != kAReaderCount || input.aAddresses.size() != kAReaderCount ||
      input.xChannels.size() != kXReaderCount || input.xAddresses.size() != kXReaderCount ||
      input.ctrlChannel.empty() || input.batches.empty()) {
    throw std::invalid_argument(
        "SPMV 窗口事务仿真要求 16 路 A、两路 X、一路 Ctrl 以及至少一个 Cuper batch");
  }
  if (input.pipelineHtml && !input.performanceHtml) {
    throw std::invalid_argument("SPMV 流水 HTML 必须在性能 HTML 主页之上启用");
  }
  if (input.maxOutstandingBursts < 2) {
    throw std::invalid_argument("满带宽输入至少需要两笔 outstanding burst");
  }
  for (const InputSimulationBatch& batch : input.batches) {
    if (batch.aAddresses.size() != kAReaderCount || batch.aChannels.size() != kAReaderCount ||
        batch.xAddresses.size() != kXReaderCount || batch.xChannels.size() != kXReaderCount ||
        std::any_of(batch.xChannels.begin(), batch.xChannels.end(),
                    [](const auto& channel) { return channel.empty(); })) {
      throw std::invalid_argument("Cuper batch 缺少完整的 A 子区间或双路 X 条带");
    }
  }

  VerilatedContext context;
  context.commandArgs(0, static_cast<char**>(nullptr));
  VSpmvInputTop dut(&context);
  auto ports = aPorts(dut);
  auto status = consumers(dut);
  auto x = xPorts(dut);
  auto ctrl = ctrlPorts(dut);
  auto mulTiming = mulTimingPorts(dut);
  std::array<HbmModel, kAReaderCount> aModels;
  std::array<HbmModel, kXReaderCount> xModels;
  std::array<HbmModel, kCtrlReaderCount> ctrlModels;
  initializeModels(ctrlModels, std::vector<std::uint64_t>{input.ctrlAddress},
      std::vector<std::vector<encoding::cuper::CuperBeat>>{input.ctrlChannel},
      input.maxOutstandingBursts, "Ctrl");
  for (DutPort& port : ports) idlePort(port);
  for (DutPort& port : x) idlePort(port);
  for (DutPort& port : ctrl) idlePort(port);
  dut.io_mulEnable = 0;
  dut.io_mulBatch = 0;
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
  std::vector<MulTimingRecord> timingRecords;
  constexpr std::uint64_t kMaximumCycles = 2000000;
  std::uint64_t addressCount = 0;
  auto sampleCycle = [&](std::optional<std::size_t> batch,
                         std::uint16_t* previousComputeDoneMask) {
    dut.eval();
    CycleRecord record;
    record.cycle = cycles.size();
    std::optional<MulTimingRecord> timing;
    if (batch.has_value()) {
      timing.emplace();
      timing->cycle = timingRecords.size();
      timing->batch = *batch;
      timing->mulReady = dut.io_mulReady != 0;
      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        if (*mulTiming[lane].beatAccepted) {
          timing->beatAcceptedMask |= static_cast<std::uint16_t>(1U << lane);
        }
        timing->validSlotMasks[lane] = static_cast<std::uint8_t>(*mulTiming[lane].validSlotMask);
        timing->paddingMasks[lane] = static_cast<std::uint8_t>(*mulTiming[lane].paddingMask);
        timing->xReadMasks[lane] = static_cast<std::uint8_t>(*mulTiming[lane].xReadMask);
        timing->mulRequestMasks[lane] = static_cast<std::uint8_t>(*mulTiming[lane].mulRequestMask);
        timing->mulResponseMasks[lane] = static_cast<std::uint8_t>(*mulTiming[lane].mulResponseMask);
      }
    }
    std::array<bool, kAReaderCount> aRequestFire{};
    std::array<bool, kAReaderCount> aAddressFire{};
    std::array<bool, kAReaderCount> aDataFire{};
    for (std::size_t lane = 0; lane < ports.size(); ++lane) {
      aRequestFire[lane] = *ports[lane].requestValid && *ports[lane].requestReady;
      aAddressFire[lane] = *ports[lane].arValid && *ports[lane].arReady;
      aDataFire[lane] = *ports[lane].rValid && *ports[lane].rReady;
      if (aRequestFire[lane]) record.requestMask |= static_cast<std::uint16_t>(1U << lane);
      if (aAddressFire[lane]) {
        record.addressMask |= static_cast<std::uint16_t>(1U << lane);
        acceptAddress(ports[lane], aModels[lane]);
      }
      if (aDataFire[lane]) record.dataMask |= static_cast<std::uint16_t>(1U << lane);
    }
    std::array<bool, kXReaderCount> xRequestFire{};
    std::array<bool, kXReaderCount> xAddressFire{};
    std::array<bool, kXReaderCount> xDataFire{};
    for (std::size_t lane = 0; lane < x.size(); ++lane) {
      xRequestFire[lane] = *x[lane].requestValid && *x[lane].requestReady;
      xAddressFire[lane] = *x[lane].arValid && *x[lane].arReady;
      xDataFire[lane] = *x[lane].rValid && *x[lane].rReady;
      if (xRequestFire[lane]) record.xRequestMask |= static_cast<std::uint8_t>(1U << lane);
      if (xAddressFire[lane]) {
        record.xAddressMask |= static_cast<std::uint8_t>(1U << lane);
        acceptAddress(x[lane], xModels[lane]);
      }
      if (xDataFire[lane]) record.xDataMask |= static_cast<std::uint8_t>(1U << lane);
    }
    std::array<bool, kCtrlReaderCount> ctrlRequestFire{};
    std::array<bool, kCtrlReaderCount> ctrlAddressFire{};
    std::array<bool, kCtrlReaderCount> ctrlDataFire{};
    for (std::size_t lane = 0; lane < ctrl.size(); ++lane) {
      ctrlRequestFire[lane] = *ctrl[lane].requestValid && *ctrl[lane].requestReady;
      ctrlAddressFire[lane] = *ctrl[lane].arValid && *ctrl[lane].arReady;
      ctrlDataFire[lane] = *ctrl[lane].rValid && *ctrl[lane].rReady;
      if (ctrlRequestFire[lane]) record.ctrlRequestMask |= static_cast<std::uint8_t>(1U << lane);
      if (ctrlAddressFire[lane]) {
        record.ctrlAddressMask |= static_cast<std::uint8_t>(1U << lane);
        acceptAddress(ctrl[lane], ctrlModels[lane]);
      }
      if (ctrlDataFire[lane]) record.ctrlDataMask |= static_cast<std::uint8_t>(1U << lane);
    }
    dut.clock = 1;
    dut.eval();
    dut.clock = 0;
    dut.eval();
    record.minimumABeats = std::numeric_limits<std::uint32_t>::max();
    for (std::size_t lane = 0; lane < ports.size(); ++lane) {
      if (*ports[lane].done) record.doneMask |= static_cast<std::uint16_t>(1U << lane);
      record.minimumABeats = std::min(record.minimumABeats, *status[lane].aBeats);
      record.maximumABeats = std::max(record.maximumABeats, *status[lane].aBeats);
    }
    for (std::size_t lane = 0; lane < x.size(); ++lane) {
      if (*x[lane].done) record.xDoneMask |= static_cast<std::uint8_t>(1U << lane);
    }
    for (std::size_t lane = 0; lane < ctrl.size(); ++lane) {
      if (*ctrl[lane].done) record.ctrlDoneMask |= static_cast<std::uint8_t>(1U << lane);
    }
    if (timing.has_value()) {
      std::uint16_t currentComputeDoneMask = 0;
      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        if (*mulTiming[lane].computeDone) currentComputeDoneMask |= static_cast<std::uint16_t>(1U << lane);
      }
      timing->computeDoneMask = currentComputeDoneMask & ~*previousComputeDoneMask;
      *previousComputeDoneMask = currentComputeDoneMask;
      timing->streamsComplete = dut.io_timingStreamsComplete != 0;
      timing->computeDone = dut.io_computeDone != 0;
      timingRecords.push_back(*timing);
    }
    record.xBeats = *status.front().xBeats;
    addressCount += bitCount(record.addressMask) + bitCount(record.xAddressMask) +
        bitCount(record.ctrlAddressMask);
    cycles.push_back(record);
    for (std::size_t lane = 0; lane < ports.size(); ++lane) {
      if (aRequestFire[lane]) aModels[lane].requestAccepted = true;
      if (aDataFire[lane]) consumeData(aModels[lane]);
    }
    for (std::size_t lane = 0; lane < x.size(); ++lane) {
      if (xRequestFire[lane]) xModels[lane].requestAccepted = true;
      if (xDataFire[lane]) consumeData(xModels[lane]);
    }
    for (std::size_t lane = 0; lane < ctrl.size(); ++lane) {
      if (ctrlRequestFire[lane]) ctrlModels[lane].requestAccepted = true;
      if (ctrlDataFire[lane]) consumeData(ctrlModels[lane]);
    }
  };

  const std::size_t ctrlBegin = cycles.size();
  bool ctrlComplete = false;
  for (std::uint64_t cycle = 0; cycle < kMaximumCycles; ++cycle) {
    for (DutPort& port : ports) idlePort(port);
    for (DutPort& port : x) idlePort(port);
    for (std::size_t lane = 0; lane < ctrl.size(); ++lane) drivePort(ctrl[lane], ctrlModels[lane]);
    sampleCycle(std::nullopt, nullptr);
    const bool hbmComplete = std::all_of(ctrlModels.begin(), ctrlModels.end(),
        [](const HbmModel& model) { return model.nextDataBeat == model.beats.size() && model.burstBeats.empty(); }) &&
        std::all_of(ctrl.begin(), ctrl.end(), [](const DutPort& port) { return *port.idle != 0; });
    if (hbmComplete && dut.io_ctrlMapReady != 0) {
      ctrlComplete = true;
      break;
    }
  }
  if (!ctrlComplete || dut.io_mulError != 0) {
    throw std::runtime_error("Cuper Ctrl map 载入或硬件解释失败");
  }
  validateContinuousWindowData(cycles, ctrlBegin, cycles.size(), input.ctrlChannel.size(),
      [](const CycleRecord& record) { return (record.ctrlDataMask & 1U) != 0; }, "Ctrl map");

  InputSimulationResult result;
  std::uint64_t aggregateProductChecksum = 0;
  std::size_t multiplyCount = 0;
  for (std::size_t batchIndex = 0; batchIndex < input.batches.size(); ++batchIndex) {
    const InputSimulationBatch& batch = input.batches[batchIndex];
    dut.io_mulEnable = 0;
    dut.io_mulBatch = batchIndex;
    initializeModels(xModels, batch.xAddresses, batch.xChannels,
        input.maxOutstandingBursts, "X");
    const std::size_t xBegin = cycles.size();
    bool xComplete = false;
    for (std::uint64_t cycle = 0; cycle < kMaximumCycles; ++cycle) {
      for (DutPort& port : ports) idlePort(port);
      for (DutPort& port : ctrl) idlePort(port);
      for (std::size_t lane = 0; lane < x.size(); ++lane) drivePort(x[lane], xModels[lane]);
      sampleCycle(std::nullopt, nullptr);
      const bool hbmComplete = std::all_of(xModels.begin(), xModels.end(),
          [](const HbmModel& model) { return model.nextDataBeat == model.beats.size() && model.burstBeats.empty(); }) &&
          std::all_of(x.begin(), x.end(), [](const DutPort& port) { return *port.idle != 0; });
      if (hbmComplete) {
        xComplete = true;
        break;
      }
    }
    if (!xComplete || std::any_of(x.begin(), x.end(), [](const DutPort& port) {
      return *port.error != 0;
    })) {
      throw std::runtime_error("Cuper batch " + std::to_string(batchIndex) + " 的 X 窗口载入失败");
    }
    for (std::size_t lane = 0; lane < kXReaderCount; ++lane) {
      validateContinuousWindowData(cycles, xBegin, cycles.size(), batch.xChannels[lane].size(),
          [lane](const CycleRecord& record) { return (record.xDataMask & (1U << lane)) != 0; },
          "Cuper batch " + std::to_string(batchIndex) + " X" + std::to_string(lane));
    }

    initializeModels(aModels, batch.aAddresses, batch.aChannels,
        input.maxOutstandingBursts, "A");
    dut.io_mulEnable = 1;
    dut.io_mulBatch = batchIndex;
    dut.eval();
    const std::size_t timingBegin = timingRecords.size();
    std::uint16_t previousComputeDoneMask = 0;
    bool computeComplete = false;
    for (std::uint64_t cycle = 0; cycle < kMaximumCycles; ++cycle) {
      if (dut.io_mulReady != 0) {
        for (std::size_t lane = 0; lane < ports.size(); ++lane) drivePort(ports[lane], aModels[lane]);
      } else {
        for (DutPort& port : ports) idlePort(port);
      }
      for (DutPort& port : x) idlePort(port);
      for (DutPort& port : ctrl) idlePort(port);
      sampleCycle(batchIndex, &previousComputeDoneMask);
      if (streamsComplete(aModels, ports) && dut.io_computeDone != 0) {
        computeComplete = true;
        break;
      }
    }
    const auto timingEnd = timingRecords.end();
    const std::vector<MulTimingRecord> batchTiming(timingRecords.begin() +
        static_cast<std::ptrdiff_t>(timingBegin), timingEnd);
    if (!computeComplete || dut.io_mulError != 0) {
      throw std::runtime_error("Cuper batch " + std::to_string(batchIndex) +
          " 的 Mixed-V3 FP64 乘法未完成或报告错误");
    }
    const std::uint64_t batchChecksum = dut.io_mulProductChecksum;
    if (batchChecksum != batch.expectedProductChecksum) {
      std::ostringstream message;
      message << "Cuper batch " << batchIndex << " 的 Mixed-V3 乘积 checksum 与 golden 不一致"
              << " expected=" << std::hex << batch.expectedProductChecksum
              << " got=" << batchChecksum;
      throw std::runtime_error(message.str());
    }
    validateBatchMultiplyTiming(batch, batchTiming, batchIndex);
    aggregateProductChecksum ^= batchChecksum;
    multiplyCount += batch.expectedMultiplyCount;
  }
  if (aggregateProductChecksum != input.expectedProductChecksum ||
      multiplyCount != input.expectedMultiplyCount) {
    throw std::runtime_error("Cuper 分窗口乘法聚合 checksum 或有效 slot 数与完整 golden 不一致");
  }
  result.cycles = cycles.size();
  result.mulCycles = timingRecords.size();
  result.multiplyCompared = true;

  const std::size_t expectedXBeats = totalXBeats(input);
  const std::uint64_t expectedXChecksum = xChecksum(input);
  const std::uint64_t expectedCtrlChecksum = checksum(input.ctrlChannel);
  for (std::size_t lane = 0; lane < status.size(); ++lane) {
    const std::uint64_t expectedAChecksum = checksum(input.aChannels[lane]);
    if (*status[lane].aBeats != input.aChannels[lane].size() ||
        *status[lane].xBeats != expectedXBeats ||
        *status[lane].ctrlBeats != input.ctrlChannel.size() ||
        *status[lane].aChecksum != expectedAChecksum ||
        *status[lane].xChecksum != expectedXChecksum ||
        *status[lane].ctrlChecksum != expectedCtrlChecksum || *status[lane].error ||
        *ports[lane].error) {
      throw std::runtime_error("Cuper 多窗口消费计数/checksum 校验失败，lane=" +
          std::to_string(lane));
    }
  }

  if (input.performanceHtml) {
    const fs::path runDirectory = reportDirectory(input.dataset);
    if (input.pipelineHtml) {
      result.inputPipelineReport = runDirectory / "input-pipeline.html";
      writeInputPipelineReport(result.inputPipelineReport, input, cycles);
      result.timingPipelineReport = runDirectory / "timing-pipeline.html";
      writeTimingPipelineReport(result.timingPipelineReport, input, timingRecords);
    }
    result.performanceReport = runDirectory / "performance.html";
    writePerformanceReport(result.performanceReport, input, status, result.cycles,
        addressCount, input.pipelineHtml, input.pipelineHtml, result);
    updateLatestReport(runDirectory);
  }
  dut.final();
  return result;
}

}  // namespace

InputSimulationResult runInputSimulation(const InputSimulationData& input) {
  return runWindowedInputSimulation(input);
  if (input.aChannels.size() != kAReaderCount || input.aAddresses.size() != kAReaderCount ||
      input.xChannels.size() != kXReaderCount || input.xAddresses.size() != kXReaderCount ||
      input.ctrlChannel.empty() ||
      std::any_of(input.xChannels.begin(), input.xChannels.end(),
                  [](const auto& channel) { return channel.empty(); })) {
    throw std::invalid_argument(
        "SPMV transaction simulation requires 16 A streams, two X streams and one Ctrl stream");
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
  auto x = xPorts(dut);
  auto ctrl = ctrlPorts(dut);
  auto mulTiming = mulTimingPorts(dut);
  std::array<HbmModel, kAReaderCount> aModels;
  for (std::size_t lane = 0; lane < kAReaderCount; ++lane) {
    aModels[lane].base = input.aAddresses[lane];
    aModels[lane].beats = input.aChannels[lane];
    aModels[lane].maxOutstandingBursts = input.maxOutstandingBursts;
    aModels[lane].requestAccepted = input.aChannels[lane].empty();
  }
  std::array<HbmModel, kXReaderCount> xModels;
  for (std::size_t lane = 0; lane < kXReaderCount; ++lane) {
    xModels[lane].base = input.xAddresses[lane];
    xModels[lane].beats = input.xChannels[lane];
    xModels[lane].maxOutstandingBursts = input.maxOutstandingBursts;
  }
  std::array<HbmModel, kCtrlReaderCount> ctrlModels;
  ctrlModels[0].base = input.ctrlAddress;
  ctrlModels[0].beats = input.ctrlChannel;
  ctrlModels[0].maxOutstandingBursts = input.maxOutstandingBursts;

  for (DutPort& port : ports) {
    *port.requestValid = 0;
    *port.arReady = 0;
    *port.rValid = 0;
    *port.rId = 0;
    *port.rResponse = 0;
    *port.rLast = 0;
    clearBeat(*port.rData);
  }
  for (DutPort& port : x) {
    *port.requestValid = 0;
    *port.arReady = 0;
    *port.rValid = 0;
    *port.rId = 0;
    *port.rResponse = 0;
    *port.rLast = 0;
    clearBeat(*port.rData);
  }
  for (DutPort& port : ctrl) {
    *port.requestValid = 0;
    *port.arReady = 0;
    *port.rValid = 0;
    *port.rId = 0;
    *port.rResponse = 0;
    *port.rLast = 0;
    clearBeat(*port.rData);
  }
  dut.io_mulEnable = 0;
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
  std::vector<MulTimingRecord> timingRecords;
  constexpr std::uint64_t kMaximumCycles = 2000000;
  std::uint64_t cycleCount = 0;
  std::uint64_t addressCount = 0;
  bool ctrlLoaded = false;
  bool complete = false;
  for (std::uint64_t cycle = 0; cycle < kMaximumCycles; ++cycle) {
    if (ctrlLoaded) {
      for (DutPort& port : ctrl) idlePort(port);
      for (std::size_t lane = 0; lane < x.size(); ++lane) {
        drivePort(x[lane], xModels[lane]);
      }
    } else {
      for (std::size_t lane = 0; lane < ctrl.size(); ++lane) {
        drivePort(ctrl[lane], ctrlModels[lane]);
      }
    }
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
    std::array<bool, kXReaderCount> xRequestFire{};
    std::array<bool, kXReaderCount> xAddressFire{};
    std::array<bool, kXReaderCount> xDataFire{};
    for (std::size_t lane = 0; lane < x.size(); ++lane) {
      xRequestFire[lane] = *x[lane].requestValid && *x[lane].requestReady;
      xAddressFire[lane] = *x[lane].arValid && *x[lane].arReady;
      xDataFire[lane] = *x[lane].rValid && *x[lane].rReady;
      if (xRequestFire[lane]) record.xRequestMask |= static_cast<std::uint8_t>(1U << lane);
      if (xAddressFire[lane]) record.xAddressMask |= static_cast<std::uint8_t>(1U << lane);
      if (xDataFire[lane]) record.xDataMask |= static_cast<std::uint8_t>(1U << lane);
    }
    std::array<bool, kCtrlReaderCount> ctrlRequestFire{};
    std::array<bool, kCtrlReaderCount> ctrlAddressFire{};
    std::array<bool, kCtrlReaderCount> ctrlDataFire{};
    for (std::size_t lane = 0; lane < ctrl.size(); ++lane) {
      ctrlRequestFire[lane] = *ctrl[lane].requestValid && *ctrl[lane].requestReady;
      ctrlAddressFire[lane] = *ctrl[lane].arValid && *ctrl[lane].arReady;
      ctrlDataFire[lane] = *ctrl[lane].rValid && *ctrl[lane].rReady;
      if (ctrlRequestFire[lane]) record.ctrlRequestMask |= static_cast<std::uint8_t>(1U << lane);
      if (ctrlAddressFire[lane]) record.ctrlAddressMask |= static_cast<std::uint8_t>(1U << lane);
      if (ctrlDataFire[lane]) record.ctrlDataMask |= static_cast<std::uint8_t>(1U << lane);
    }

    // AR 信息只在握手周期有效，必须在时钟沿改变 reader 状态前完成协议校验。
    for (std::size_t lane = 0; lane < ports.size(); ++lane) {
      if (addressFire[lane]) acceptAddress(ports[lane], aModels[lane]);
    }
    for (std::size_t lane = 0; lane < x.size(); ++lane) {
      if (xAddressFire[lane]) acceptAddress(x[lane], xModels[lane]);
    }
    for (std::size_t lane = 0; lane < ctrl.size(); ++lane) {
      if (ctrlAddressFire[lane]) acceptAddress(ctrl[lane], ctrlModels[lane]);
    }

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
    for (std::size_t lane = 0; lane < x.size(); ++lane) {
      if (*x[lane].done) record.xDoneMask |= static_cast<std::uint8_t>(1U << lane);
    }
    for (std::size_t lane = 0; lane < ctrl.size(); ++lane) {
      if (*ctrl[lane].done) record.ctrlDoneMask |= static_cast<std::uint8_t>(1U << lane);
    }
    record.xBeats = *status.front().xBeats;
    cycleCount = cycle + 1U;
    addressCount += bitCount(record.addressMask) + bitCount(record.xAddressMask) +
        bitCount(record.ctrlAddressMask);
    cycles.push_back(record);

    for (std::size_t lane = 0; lane < ports.size(); ++lane) {
      if (requestFire[lane]) aModels[lane].requestAccepted = true;
      if (dataFire[lane]) consumeData(aModels[lane]);
    }
    for (std::size_t lane = 0; lane < x.size(); ++lane) {
      if (xRequestFire[lane]) xModels[lane].requestAccepted = true;
      if (xDataFire[lane]) consumeData(xModels[lane]);
    }
    for (std::size_t lane = 0; lane < ctrl.size(); ++lane) {
      if (ctrlRequestFire[lane]) ctrlModels[lane].requestAccepted = true;
      if (ctrlDataFire[lane]) consumeData(ctrlModels[lane]);
    }

    const bool ctrlNowComplete = std::all_of(
        ctrlModels.begin(), ctrlModels.end(), [](const HbmModel& model) {
          return model.nextDataBeat == model.beats.size() && model.burstBeats.empty();
        }) && std::all_of(ctrl.begin(), ctrl.end(), [](const DutPort& port) {
          return *port.idle != 0;
        });
    if (!ctrlLoaded && ctrlNowComplete) ctrlLoaded = true;
    complete = ctrlLoaded && std::all_of(xModels.begin(), xModels.end(), [](const HbmModel& model) {
          return model.nextDataBeat == model.beats.size() && model.burstBeats.empty();
        }) && std::all_of(x.begin(), x.end(), [](const DutPort& port) {
          return *port.idle != 0;
        });
    if (complete) break;
  }
  if (!complete) throw std::runtime_error("SPMV input transaction simulation timed out");

  InputSimulationResult result;
  result.cycles = cycleCount;
  if (input.multiplyExpected) {
    for (HbmModel& model : aModels) resetReadModel(model);
    for (DutPort& port : ports) {
      *port.requestValid = 0;
      *port.arReady = 0;
      *port.rValid = 0;
      *port.rId = 0;
      *port.rResponse = 0;
      *port.rLast = 0;
      clearBeat(*port.rData);
    }
    for (DutPort& port : x) {
      *port.requestValid = 0;
      *port.arReady = 0;
      *port.rValid = 0;
      clearBeat(*port.rData);
    }
    for (DutPort& port : ctrl) {
      *port.requestValid = 0;
      *port.arReady = 0;
      *port.rValid = 0;
      clearBeat(*port.rData);
    }
    dut.io_mulEnable = 1;
    dut.eval();

    constexpr std::uint64_t kMaximumMulCycles = 2000000;
    bool aIssued = false;
    bool computeComplete = false;
    std::uint16_t previousComputeDoneMask = 0;
    for (std::uint64_t cycle = 0; cycle < kMaximumMulCycles; ++cycle) {
      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        drivePort(ports[lane], aModels[lane]);
      }
      if (dut.io_mulReady == 0) {
        for (DutPort& port : ports) *port.requestValid = 0;
      }
      dut.eval();

      std::array<bool, kAReaderCount> requestFire{};
      std::array<bool, kAReaderCount> addressFire{};
      std::array<bool, kAReaderCount> dataFire{};
      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        requestFire[lane] = *ports[lane].requestValid && *ports[lane].requestReady;
        addressFire[lane] = *ports[lane].arValid && *ports[lane].arReady;
        dataFire[lane] = *ports[lane].rValid && *ports[lane].rReady;
      }
      CycleRecord record;
      record.cycle = cycles.size();
      MulTimingRecord timing;
      timing.cycle = cycle;
      timing.mulReady = dut.io_mulReady != 0;
      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        if (requestFire[lane]) record.requestMask |= static_cast<std::uint16_t>(1U << lane);
        if (addressFire[lane]) record.addressMask |= static_cast<std::uint16_t>(1U << lane);
        if (dataFire[lane]) record.dataMask |= static_cast<std::uint16_t>(1U << lane);
        if (*mulTiming[lane].beatAccepted) {
          timing.beatAcceptedMask |= static_cast<std::uint16_t>(1U << lane);
        }
        timing.validSlotMasks[lane] = static_cast<std::uint8_t>(*mulTiming[lane].validSlotMask);
        timing.paddingMasks[lane] = static_cast<std::uint8_t>(*mulTiming[lane].paddingMask);
        timing.xReadMasks[lane] = static_cast<std::uint8_t>(*mulTiming[lane].xReadMask);
        timing.mulRequestMasks[lane] = static_cast<std::uint8_t>(*mulTiming[lane].mulRequestMask);
        timing.mulResponseMasks[lane] = static_cast<std::uint8_t>(*mulTiming[lane].mulResponseMask);
      }
      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        if (addressFire[lane]) acceptAddress(ports[lane], aModels[lane]);
      }

      dut.clock = 1;
      dut.eval();
      dut.clock = 0;
      dut.eval();

      std::uint16_t currentComputeDoneMask = 0;
      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        if (*ports[lane].done) record.doneMask |= static_cast<std::uint16_t>(1U << lane);
        if (*mulTiming[lane].computeDone) {
          currentComputeDoneMask |= static_cast<std::uint16_t>(1U << lane);
        }
      }
      // `computeDone` 是 PE 的完成态，不是单拍脉冲；报告只记录其上升沿，避免先
      // 排空的 PE 在其他 PE 仍有 IP 在飞时被重复计数。
      timing.computeDoneMask = currentComputeDoneMask & ~previousComputeDoneMask;
      previousComputeDoneMask = currentComputeDoneMask;
      timing.streamsComplete = dut.io_timingStreamsComplete != 0;
      timing.computeDone = dut.io_computeDone != 0;
      timingRecords.push_back(timing);

      record.minimumABeats = std::numeric_limits<std::uint32_t>::max();
      for (const ConsumerStatus& consumer : status) {
        record.minimumABeats = std::min(record.minimumABeats, *consumer.aBeats);
        record.maximumABeats = std::max(record.maximumABeats, *consumer.aBeats);
      }
      record.xBeats = *status.front().xBeats;
      addressCount += bitCount(record.addressMask);
      cycles.push_back(record);

      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        if (requestFire[lane]) {
          aModels[lane].requestAccepted = true;
          aIssued = true;
        }
        if (dataFire[lane]) consumeData(aModels[lane]);
      }
      result.mulCycles = cycle + 1U;
      result.cycles = cycles.size();
      if (aIssued && streamsComplete(aModels, ports) && dut.io_computeDone != 0) {
        computeComplete = true;
        break;
      }
    }
    if (!computeComplete) {
      throw std::runtime_error("SPMV Mixed-V3 单遍 A FP64 乘法超时");
    }
    if (dut.io_mulError != 0) {
      throw std::runtime_error("SPMV Mixed-V3 乘法引擎报告错误");
    }

    const std::uint64_t gotChecksum = dut.io_mulProductChecksum;
    if (gotChecksum != input.expectedProductChecksum) {
      std::ostringstream message;
      message << "SPMV Mixed-V3 FP64 乘积 checksum 与 golden 不一致"
              << " expected=" << std::hex << input.expectedProductChecksum
              << " got=" << gotChecksum;
      throw std::runtime_error(message.str());
    }
    result.multiplyCompared = true;

    const std::uint64_t expectedMultiply = std::accumulate(
        input.aChannels.begin(), input.aChannels.end(), std::uint64_t{0},
        [](std::uint64_t count, const auto& channel) { return count + validSlotCount(channel); });
    if (expectedMultiply != input.expectedMultiplyCount || timingRecords.empty() ||
        std::count_if(timingRecords.begin(), timingRecords.end(), [](const MulTimingRecord& record) {
          return record.computeDone;
        }) != 1 || !timingRecords.back().computeDone) {
      throw std::runtime_error("SPMV Mixed-V3 乘法全局时序事件计数与编码输入不一致");
    }
    for (std::size_t core = 0; core < kAReaderCount; ++core) {
      const std::uint16_t coreBit = static_cast<std::uint16_t>(1U << core);
      const std::uint64_t expectedBeats = input.aChannels[core].size();
      const std::uint64_t expectedValid = validSlotCount(input.aChannels[core]);
      std::uint64_t accepted = 0;
      std::uint64_t valid = 0;
      std::uint64_t padding = 0;
      std::uint64_t xReads = 0;
      std::uint64_t requests = 0;
      std::uint64_t responses = 0;
      std::uint64_t computeDone = 0;
      std::uint8_t stagedMask = 0;
      std::array<std::deque<std::uint64_t>, kSlotsPerABeat> pendingRequests;
      std::vector<std::uint64_t> acceptedCycles;
      for (const MulTimingRecord& timing : timingRecords) {
        const std::uint8_t validMask = timing.validSlotMasks[core];
        const std::uint8_t requestMask = timing.mulRequestMasks[core];
        const std::uint8_t responseMask = timing.mulResponseMasks[core];
        if (requestMask != stagedMask) {
          throw std::runtime_error("FP64 乘法流水没有在 local_X 读后一拍完整发射 lane mask，PE=" +
              std::to_string(core));
        }
        const bool acceptedNow = (timing.beatAcceptedMask & coreBit) != 0;
        if (acceptedNow && timing.xReadMasks[core] != validMask) {
          throw std::runtime_error("有效 Cuper slot 没有在 A beat 接受周期读取 local_X，PE=" +
              std::to_string(core));
        }
        if (acceptedNow) {
          ++accepted;
          acceptedCycles.push_back(timing.cycle);
          valid += bitCount(validMask);
          padding += bitCount(timing.paddingMasks[core]);
        }
        xReads += bitCount(timing.xReadMasks[core]);
        requests += bitCount(requestMask);
        responses += bitCount(responseMask);
        for (std::size_t lane = 0; lane < kSlotsPerABeat; ++lane) {
          const std::uint8_t laneBit = static_cast<std::uint8_t>(1U << lane);
          if ((requestMask & laneBit) != 0) pendingRequests[lane].push_back(timing.cycle);
          if ((responseMask & laneBit) != 0) {
            if (pendingRequests[lane].empty() ||
                timing.cycle - pendingRequests[lane].front() != kFp64MulLatency) {
              throw std::runtime_error("FP64 乘法 IP 的逐 lane req/resp 延迟与冻结 profile 不一致，PE=" +
                  std::to_string(core));
            }
            pendingRequests[lane].pop_front();
          }
        }
        computeDone += (timing.computeDoneMask & coreBit) != 0;
        stagedMask = acceptedNow ? validMask : 0;
      }
      if (accepted != expectedBeats || valid != expectedValid || xReads != expectedValid ||
          requests != expectedValid || responses != expectedValid ||
          padding != expectedBeats * kSlotsPerABeat - expectedValid || computeDone != 1 ||
          std::adjacent_find(acceptedCycles.begin(), acceptedCycles.end(),
              [](std::uint64_t previous, std::uint64_t current) {
                return current != previous + 1U;
              }) != acceptedCycles.end() ||
          std::any_of(pendingRequests.begin(), pendingRequests.end(),
              [](const std::deque<std::uint64_t>& pending) { return !pending.empty(); })) {
        throw std::runtime_error("SPMV Mixed-V3 PE 乘法时序事件计数或 II=1 与编码输入不一致，PE=" +
            std::to_string(core));
      }
    }
  } else {
    bool aComplete = false;
    for (std::uint64_t cycle = 0; cycle < kMaximumCycles; ++cycle) {
      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        drivePort(ports[lane], aModels[lane]);
      }
      dut.eval();

      std::array<bool, kAReaderCount> requestFire{};
      std::array<bool, kAReaderCount> addressFire{};
      std::array<bool, kAReaderCount> dataFire{};
      CycleRecord record;
      record.cycle = cycles.size();
      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        requestFire[lane] = *ports[lane].requestValid && *ports[lane].requestReady;
        addressFire[lane] = *ports[lane].arValid && *ports[lane].arReady;
        dataFire[lane] = *ports[lane].rValid && *ports[lane].rReady;
        if (requestFire[lane]) record.requestMask |= static_cast<std::uint16_t>(1U << lane);
        if (addressFire[lane]) {
          record.addressMask |= static_cast<std::uint16_t>(1U << lane);
          acceptAddress(ports[lane], aModels[lane]);
        }
        if (dataFire[lane]) record.dataMask |= static_cast<std::uint16_t>(1U << lane);
      }

      dut.clock = 1;
      dut.eval();
      dut.clock = 0;
      dut.eval();
      record.minimumABeats = std::numeric_limits<std::uint32_t>::max();
      for (std::size_t lane = 0; lane < ports.size(); ++lane) {
        if (*ports[lane].done) record.doneMask |= static_cast<std::uint16_t>(1U << lane);
        record.minimumABeats = std::min(record.minimumABeats, *status[lane].aBeats);
        record.maximumABeats = std::max(record.maximumABeats, *status[lane].aBeats);
        if (requestFire[lane]) aModels[lane].requestAccepted = true;
        if (dataFire[lane]) consumeData(aModels[lane]);
      }
      record.xBeats = *status.front().xBeats;
      addressCount += bitCount(record.addressMask);
      cycles.push_back(record);

      result.cycles = cycles.size();
      if (streamsComplete(aModels, ports)) {
        aComplete = true;
        break;
      }
    }
    if (!aComplete) throw std::runtime_error("SPMV 单遍 A 输入校验超时");
  }

  validateSinglePassFlow(cycles, input);
  const std::size_t expectedXBeats = totalXBeats(input);
  const std::uint64_t expectedXChecksum = xChecksum(input);
  const std::uint64_t expectedCtrlChecksum = checksum(input.ctrlChannel);
  for (std::size_t lane = 0; lane < status.size(); ++lane) {
    const std::uint64_t expectedAChecksum = checksum(input.aChannels[lane]);
    if (*status[lane].aBeats != input.aChannels[lane].size() ||
        *status[lane].xBeats != expectedXBeats ||
        *status[lane].ctrlBeats != input.ctrlChannel.size() ||
        *status[lane].aChecksum != expectedAChecksum ||
        *status[lane].xChecksum != expectedXChecksum ||
        *status[lane].ctrlChecksum != expectedCtrlChecksum || *status[lane].error ||
        *ports[lane].error || std::any_of(x.begin(), x.end(), [](const DutPort& port) {
          return *port.error != 0;
        }) || std::any_of(ctrl.begin(), ctrl.end(), [](const DutPort& port) {
          return *port.error != 0;
        })) {
      throw std::runtime_error("SPMV 单遍消费计数/checksum 校验失败，lane=" +
          std::to_string(lane));
    }
  }

  if (input.performanceHtml) {
    const fs::path runDirectory = reportDirectory(input.dataset);
    if (input.pipelineHtml) {
      result.inputPipelineReport = runDirectory / "input-pipeline.html";
      writeInputPipelineReport(result.inputPipelineReport, input, cycles);
    }
    if (input.pipelineHtml && result.multiplyCompared) {
      result.timingPipelineReport = runDirectory / "timing-pipeline.html";
      writeTimingPipelineReport(result.timingPipelineReport, input, timingRecords);
    }
    result.performanceReport = runDirectory / "performance.html";
    writePerformanceReport(result.performanceReport, input, status, result.cycles,
        addressCount, input.pipelineHtml, input.pipelineHtml && result.multiplyCompared, result);
    updateLatestReport(runDirectory);
  }
  dut.final();
  return result;
}

}  // namespace accelerator_sim::spmv

#endif
