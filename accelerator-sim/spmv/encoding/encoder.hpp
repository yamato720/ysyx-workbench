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
using EncodedVectorPackage = std::variant<cuper::CuperVectorPackage>;

struct EncodedMatrix {
  EncodingFormat format = EncodingFormat::Cuper;
  EncodingPackage package;
};

struct EncodedVector {
  EncodingFormat format = EncodingFormat::Cuper;
  EncodedVectorPackage package;
};

struct EncodingReportMetadata {
  std::string datasetName;
  std::string sourcePath;
};

EncodingFormat parseEncodingFormat(std::string_view name);
std::string_view encodingFormatName(EncodingFormat format);
EncodedMatrix encodeMatrix(const CsrMatrix& matrix, const EncodingOptions& options = {});
EncodedVector encodeVector(const std::vector<double>& input,
                           const EncodingOptions& options = {});
void writeHtmlReport(std::ostream& output, const EncodedMatrix& encoded,
                     const EncodingReportMetadata& metadata);
void writeHtmlReport(const std::filesystem::path& path, const EncodedMatrix& encoded,
                     const EncodingReportMetadata& metadata);
void writeVectorHtmlReport(std::ostream& output, const EncodedVector& encoded,
                           const EncodingReportMetadata& metadata);
void writeVectorHtmlReport(const std::filesystem::path& path, const EncodedVector& encoded,
                           const EncodingReportMetadata& metadata);

}  // namespace accelerator_sim::spmv::encoding
