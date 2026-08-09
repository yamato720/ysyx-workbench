#include "encoder.hpp"
#include "cuper/report.hpp"

#include <fstream>
#include <stdexcept>
#include <system_error>

namespace accelerator_sim::spmv::encoding {

EncodingFormat parseEncodingFormat(std::string_view name) {
  if (name == "cuper") {
    return EncodingFormat::Cuper;
  }
  throw std::invalid_argument("未知的 SpMV 编码格式: " + std::string(name));
}

std::string_view encodingFormatName(EncodingFormat format) {
  switch (format) {
    case EncodingFormat::Cuper:
      return "cuper";
  }
  throw std::invalid_argument("无效的 SpMV EncodingFormat");
}

EncodedMatrix encodeMatrix(const CsrMatrix& matrix, const EncodingOptions& options) {
  switch (options.format) {
    case EncodingFormat::Cuper:
      return EncodedMatrix{options.format, cuper::encode(matrix, options.cuper)};
  }
  throw std::invalid_argument("无效的 SpMV EncodingFormat");
}

void writeHtmlReport(std::ostream& output, const EncodedMatrix& encoded,
                     const EncodingReportMetadata& metadata) {
  switch (encoded.format) {
    case EncodingFormat::Cuper:
      cuper::writeHtmlReport(output, std::get<cuper::CuperPackage>(encoded.package),
                             metadata.datasetName, metadata.sourcePath);
      return;
  }
  throw std::invalid_argument("无效的 SpMV EncodingFormat");
}

void writeHtmlReport(const std::filesystem::path& path, const EncodedMatrix& encoded,
                     const EncodingReportMetadata& metadata) {
  std::error_code error;
  std::filesystem::create_directories(path.parent_path(), error);
  if (error) {
    throw std::runtime_error("无法创建 SpMV 编码报告目录 " +
        path.parent_path().string() + ": " + error.message());
  }

  const std::filesystem::path temporary = path.string() + ".tmp";
  std::ofstream output(temporary);
  if (!output) {
    throw std::runtime_error("无法打开 SpMV 编码报告: " + temporary.string());
  }
  writeHtmlReport(output, encoded, metadata);
  output.close();
  if (!output) {
    std::filesystem::remove(temporary);
    throw std::runtime_error("无法写入 SpMV 编码报告: " + temporary.string());
  }

  std::filesystem::rename(temporary, path, error);
  if (error) {
    std::filesystem::remove(temporary);
    throw std::runtime_error("无法发布 SpMV 编码报告 " + path.string() + ": " +
        error.message());
  }
}

}  // namespace accelerator_sim::spmv::encoding
