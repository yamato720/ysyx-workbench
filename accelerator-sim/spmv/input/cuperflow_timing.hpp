#pragma once

#include <string>

namespace accelerator_sim::spmv {

/** 生成 Cuperflow group-major 周期/吞吐 HTML 报告。 */
int runCuperflowTiming(const std::string& requestedDataset);

}  // namespace accelerator_sim::spmv
