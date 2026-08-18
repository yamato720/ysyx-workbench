#pragma once

#include "cuperflow.hpp"

#include <ostream>
#include <string_view>

namespace accelerator_sim::spmv::encoding::cuperflow {

void writeHtmlReport(std::ostream& output, const CuperflowPackage& package,
                     std::string_view datasetName, std::string_view sourcePath);

}  // namespace accelerator_sim::spmv::encoding::cuperflow
