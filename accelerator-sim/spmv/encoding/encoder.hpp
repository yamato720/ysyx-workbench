#pragma once

#include "cuper/cuper.hpp"

#include <filesystem>
#include <ostream>
#include <string>
#include <string_view>
#include <variant>

namespace accelerator_sim::spmv::encoding {

enum class EncodingFormat {
  Cuper,
};

struct EncodingOptions {
  EncodingFormat format = EncodingFormat::Cuper;
  cuper::CuperConfig cuper;
};

using EncodingPackage = std::variant<cuper::CuperPackage>;

struct EncodedMatrix {
  EncodingFormat format = EncodingFormat::Cuper;
  EncodingPackage package;
};

struct EncodingReportMetadata {
  std::string datasetName;
  std::string sourcePath;
};

EncodingFormat parseEncodingFormat(std::string_view name);
std::string_view encodingFormatName(EncodingFormat format);
EncodedMatrix encodeMatrix(const CsrMatrix& matrix, const EncodingOptions& options = {});
void writeHtmlReport(std::ostream& output, const EncodedMatrix& encoded,
                     const EncodingReportMetadata& metadata);
void writeHtmlReport(const std::filesystem::path& path, const EncodedMatrix& encoded,
                     const EncodingReportMetadata& metadata);

}  // namespace accelerator_sim::spmv::encoding
