#include "golden.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <stdexcept>

namespace accelerator_sim::spmv {

namespace {

std::uint64_t hashOutput(const std::vector<double>& output) {
  std::uint64_t hash = 1469598103934665603ULL;
  for (double value : output) {
    std::uint64_t bits = 0;
    static_assert(sizeof(bits) == sizeof(value));
    std::memcpy(&bits, &value, sizeof(bits));
    for (unsigned int byte = 0; byte < sizeof(bits); ++byte) {
      hash ^= (bits >> (byte * 8U)) & 0xffU;
      hash *= 1099511628211ULL;
    }
  }
  return hash;
}

}

GoldenResult computeGolden(const CsrMatrix& matrix, const std::vector<double>& input) {
  if (matrix.rowPointers.size() != matrix.rows + 1) {
    throw std::runtime_error("invalid CSR row pointer count");
  }
  if (matrix.columnIndices.size() != matrix.values.size()) {
    throw std::runtime_error("CSR column/value count mismatch");
  }
  if (input.size() != matrix.rows) {
    throw std::runtime_error("input vector length must equal matrix size");
  }

  GoldenResult result;
  result.output.assign(matrix.rows, 0.0);

  for (std::size_t row = 0; row < matrix.rows; ++row) {
    double accumulator = 0.0;
    const std::uint64_t begin = matrix.rowPointers[row];
    const std::uint64_t end = matrix.rowPointers[row + 1];
    for (std::uint64_t offset = begin; offset < end; ++offset) {
      const std::size_t index = static_cast<std::size_t>(offset);
      accumulator += matrix.values[index] * input[matrix.columnIndices[index]];
    }
    result.output[row] = accumulator;
    result.checksum += accumulator;
    result.l1Norm += std::abs(accumulator);
    result.maxAbs = std::max(result.maxAbs, std::abs(accumulator));
  }

  result.bitHash = hashOutput(result.output);
  return result;
}

void writeGolden(const std::filesystem::path& path, const std::vector<double>& output) {
  std::filesystem::create_directories(path.parent_path());
  std::ofstream stream(path);
  if (!stream) {
    throw std::runtime_error("failed to open golden output: " + path.string());
  }

  stream << std::setprecision(std::numeric_limits<double>::max_digits10);
  for (double value : output) {
    stream << value << '\n';
  }
  if (!stream) {
    throw std::runtime_error("failed to write golden output: " + path.string());
  }
}

}
