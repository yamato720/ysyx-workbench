#pragma once

#if defined(SPMV_INPUT_TRANSACTION_VERILATOR) || defined(SPMV_INPUT_XRT)

#include "../encoding/cuper/cuper.hpp"

#include <array>
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace accelerator_sim::spmv {

enum class InputXPortSchedule {
  Preload,
  PingPong,
};

/** 一次 Cuper 列窗口的 X 装载与 A 回放范围。
  *
  * `aChannels` 是由 Ctrl map 相邻 pointer 截出的每通道子区间；`xChannels` 则只含本
  * 8192 列窗口的条带 beat。两个 vector 都保留实际 HBM 地址，因而同一个 Verilator
  * DUT 可以连续执行多个窗口而不丢失 AXI 协议证据。
  */
struct InputSimulationBatch {
  std::vector<std::uint64_t> aAddresses;
  std::vector<std::vector<encoding::cuper::CuperBeat>> aChannels;
  std::vector<std::uint64_t> xAddresses;
  std::vector<std::vector<encoding::cuper::CuperBeat>> xChannels;
  std::uint64_t expectedProductChecksum = 0;
  std::size_t expectedMultiplyCount = 0;
};

struct InputSimulationData {
  std::string dataset;
  std::uint64_t hbmBase = 0;
  std::size_t hbmBytes = 0;
  std::vector<std::uint64_t> aAddresses;
  std::vector<std::vector<encoding::cuper::CuperBeat>> aChannels;
  std::vector<std::uint64_t> xAddresses;
  std::vector<std::vector<encoding::cuper::CuperBeat>> xChannels;
  std::uint64_t ctrlAddress = 0;
  std::vector<encoding::cuper::CuperBeat> ctrlChannel;
  /** Ctrl map 驱动的按列窗口执行顺序，至少包含一个 batch。 */
  std::vector<InputSimulationBatch> batches;
  std::size_t maxOutstandingBursts = 2;
  InputXPortSchedule xPortSchedule = InputXPortSchedule::Preload;
  bool performanceHtml = true;
  bool pipelineHtml = true;
  bool multiplyExpected = false;
  std::uint64_t expectedProductChecksum = 0;
  std::size_t expectedMultiplyCount = 0;
};

/** 生成 Verilator 与 U55C XRT host 共用的 Cuper A/X/Ctrl HBM 输入。 */
InputSimulationData buildInputSimulationData(const std::string& dataset);

#ifdef SPMV_INPUT_TRANSACTION_VERILATOR

struct InputSimulationResult {
  std::uint64_t cycles = 0;
  std::uint64_t mulCycles = 0;
  /** 首个原始 A beat 被 PE 接受及首个 FMUL request 的全局周期。 */
  std::uint64_t firstABeatCycle = 0;
  std::uint64_t firstMulRequestCycle = 0;
  bool startedA = false;
  bool startedMul = false;
  /** local_X 端口调度的负载、重叠和排空周期。 */
  std::uint64_t xLoadCycles = 0;
  std::uint64_t xOverlapCycles = 0;
  std::uint64_t xDrainCycles = 0;
  /** X 未完全写满前已经接受 A beat 的 batch 数。 */
  std::size_t xAEarlyStartBatches = 0;
  bool multiplyCompared = false;
  std::filesystem::path performanceReport;
  std::filesystem::path inputPipelineReport;
  std::filesystem::path timingPipelineReport;
};

InputSimulationResult runInputSimulation(const InputSimulationData& input);

#endif

#ifdef SPMV_INPUT_XRT

/** 运行 U55C 的乘法-only XRT 链路。 */
int runInputXrt(int argc, char** argv);

#endif

}  // namespace accelerator_sim::spmv

#endif
