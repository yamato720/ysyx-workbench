#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <vector>

namespace accelerator_sim::spmv {

struct CsrMatrix {
  std::size_t rows = 0;
  std::vector<std::uint64_t> rowPointers;
  std::vector<std::uint32_t> columnIndices;
  std::vector<double> values;
};

struct GoldenResult {
  std::vector<double> output;
  double checksum = 0.0;
  double l1Norm = 0.0;
  double maxAbs = 0.0;
  std::uint64_t bitHash = 0;
};

GoldenResult computeGolden(const CsrMatrix& matrix, const std::vector<double>& input);
void writeGolden(const std::filesystem::path& path, const std::vector<double>& output);

}
