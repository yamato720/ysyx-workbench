#pragma once

#include "cuperflow.hpp"

#include <ostream>
#include <string_view>

namespace accelerator_sim::spmv::encoding::cuperflow {

void writeVectorHtmlReport(std::ostream& output, const CuperflowVectorPackage& package,
                           std::string_view datasetName, std::string_view sourcePath);

}  // namespace accelerator_sim::spmv::encoding::cuperflow
