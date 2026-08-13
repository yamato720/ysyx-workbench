#pragma once

#ifdef SPMV_INPUT_TRANSACTION_VERILATOR

#include "encoding/cuper/cuper.hpp"

#include <array>
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace accelerator_sim::spmv {

struct InputSimulationData {
  std::string dataset;
  std::uint64_t hbmBase = 0;
  std::size_t hbmBytes = 0;
  std::vector<std::uint64_t> aAddresses;
  std::vector<std::vector<encoding::cuper::CuperBeat>> aChannels;
  std::uint64_t xAddress = 0;
  std::vector<encoding::cuper::CuperBeat> xBeats;
  std::size_t maxOutstandingBursts = 2;
  bool performanceHtml = true;
  bool pipelineHtml = true;
};

struct InputSimulationResult {
  std::uint64_t cycles = 0;
  std::filesystem::path performanceReport;
  std::filesystem::path pipelineReport;
};

InputSimulationResult runInputSimulation(const InputSimulationData& input);

}  // namespace accelerator_sim::spmv

#endif
