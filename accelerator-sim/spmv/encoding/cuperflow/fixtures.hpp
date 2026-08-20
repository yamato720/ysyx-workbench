#pragma once

#include "cuperflow.hpp"

#include <algorithm>
#include <cstdint>
#include <initializer_list>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace accelerator_sim::spmv::encoding::cuperflow::fixtures {

/** V0 fixture 的逻辑类型；V1/V2 可按名称直接复用同一覆盖语义。 */
enum class V0FixtureKind {
  Full8,
  Tail44,
  Tail2222,
  Pad3And1,
  EmptyPcRow,
  EmptyBatch,
  LastShortBatch,
  SameLocalRowNextBatch,
  MultiWaveSameY,
  ExplicitZero,
  EightXSegments,
};

struct Element {
  std::size_t row = 0;
  std::uint32_t column = 0;
  double value = 0.0;
};

struct V0Fixture {
  std::string name;
  V0FixtureKind kind = V0FixtureKind::Full8;
  CsrMatrix matrix;
  CuperflowConfig config;
};

inline CsrMatrix makeMatrix(std::size_t rows, std::size_t columns,
                            std::initializer_list<Element> elements) {
  std::vector<Element> sorted(elements);
  std::stable_sort(sorted.begin(), sorted.end(), [](const Element& lhs, const Element& rhs) {
    return lhs.row < rhs.row;
  });
  CsrMatrix matrix;
  matrix.rows = rows;
  matrix.columns = columns;
  matrix.rowPointers.assign(rows + 1U, 0U);
  for (const Element& element : sorted) {
    if (element.row >= rows || element.column >= columns) {
      throw std::invalid_argument("Cuperflow V0 fixture CSR 坐标越界");
    }
    ++matrix.rowPointers[element.row + 1U];
  }
  for (std::size_t row = 0; row < rows; ++row) {
    matrix.rowPointers[row + 1U] += matrix.rowPointers[row];
  }
  for (const Element& element : sorted) {
    matrix.columnIndices.push_back(element.column);
    matrix.values.push_back(element.value);
  }
  return matrix;
}

inline CuperflowConfig config(std::size_t sliceGroupSize = 0U) {
  CuperflowConfig value;
  value.rowReorder = false;
  value.sliceGroupSize = sliceGroupSize;
  return value;
}

/**
 * 独立于随机矩阵的 V0 package 输入。fixture 名称是后续 L1 RTL test 的稳定引用名；
 * C++ 回归会对每项执行 encode、package validator、X 唯一装载和 slot round-trip。
 */
inline std::vector<V0Fixture> v0() {
  const double fp32Underflow = static_cast<double>(std::numeric_limits<float>::denorm_min()) / 4.0;
  return {
      {"full8", V0FixtureKind::Full8, makeMatrix(2, 32, {
          {0, 0, 1.0}, {0, 1, 2.0}, {0, 2, 3.0}, {0, 3, 4.0},
          {0, 4, 5.0}, {0, 5, 6.0}, {0, 6, 7.0}, {0, 7, 8.0},
          {1, 8, 1.0}, {1, 9, 2.0}, {1, 10, 3.0}, {1, 11, 4.0},
          {1, 12, 5.0}, {1, 13, 6.0}, {1, 14, 7.0}, {1, 15, 8.0},
          {1, 16, 9.0}, {1, 17, 10.0}, {1, 18, 11.0}, {1, 19, 12.0},
          {1, 20, 13.0}, {1, 21, 14.0}, {1, 22, 15.0}, {1, 23, 16.0},
      }), config()},
      {"tail44", V0FixtureKind::Tail44, makeMatrix(2, 64, {
          {0, 0, 1.0}, {0, 1, 2.0}, {0, 2, 3.0}, {0, 3, 4.0},
          {1, 8, 1.0}, {1, 9, 2.0}, {1, 10, 3.0}, {1, 11, 4.0},
      }), config()},
      {"tail2222", V0FixtureKind::Tail2222, makeMatrix(4, 64, {
          {0, 0, 1.0}, {0, 1, 2.0}, {1, 8, 1.0}, {1, 9, 2.0},
          {2, 16, 1.0}, {2, 17, 2.0}, {3, 24, 1.0}, {3, 25, 2.0},
      }), config()},
      {"pad3_1", V0FixtureKind::Pad3And1, makeMatrix(2, 64, {
          {0, 0, 1.0}, {0, 1, 2.0}, {0, 2, 3.0}, {1, 8, 4.0},
      }), config()},
      {"empty_pc_row", V0FixtureKind::EmptyPcRow, makeMatrix(1, 1024, {
          {0, 0, 1.0}, {0, 64, 2.0},
      }), config()},
      {"empty_batch", V0FixtureKind::EmptyBatch, makeMatrix(8193, 64, {
          {0, 0, 1.0},
      }), config()},
      {"last_short_batch", V0FixtureKind::LastShortBatch, makeMatrix(8193, 64, {
          {8192, 0, 1.0},
      }), config()},
      {"same_local_row_next_batch", V0FixtureKind::SameLocalRowNextBatch,
       makeMatrix(8193, 64, {{0, 0, 1.0}, {8192, 1, 2.0}}), config()},
      {"multi_wave_same_y", V0FixtureKind::MultiWaveSameY, makeMatrix(1, 1088, {
          {0, 0, 1.0}, {0, 1024, 2.0},
      }), config(1U)},
      {"explicit_zero", V0FixtureKind::ExplicitZero, makeMatrix(1, 8, {
          {0, 0, 0.0}, {0, 1, -0.0}, {0, 2, 1.0},
          {0, 3, std::numeric_limits<double>::quiet_NaN()},
          {0, 4, std::numeric_limits<double>::infinity()}, {0, 5, fp32Underflow},
      }), config()},
      {"eight_x_segments", V0FixtureKind::EightXSegments, makeMatrix(1, 16 * 8192, {
          {0, 0, 1.0}, {0, 1000, 2.0}, {0, 2000, 3.0}, {0, 3000, 4.0},
          {0, 4000, 5.0}, {0, 5000, 6.0}, {0, 6000, 7.0}, {0, 7000, 8.0},
      }), config(128U)},
  };
}

}  // namespace accelerator_sim::spmv::encoding::cuperflow::fixtures
