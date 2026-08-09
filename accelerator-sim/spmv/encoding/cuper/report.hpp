#pragma once

#include "cuper.hpp"

#include <ostream>
#include <string_view>

namespace accelerator_sim::spmv::encoding::cuper {

void writeHtmlReport(std::ostream& output, const CuperPackage& package,
                     std::string_view datasetName, std::string_view sourcePath);

}  // namespace accelerator_sim::spmv::encoding::cuper
