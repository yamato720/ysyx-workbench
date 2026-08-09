#include "encoder.hpp"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <functional>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

namespace accelerator_sim::spmv::encoding {
namespace {

struct InputElement {
  std::size_t row = 0;
  std::uint32_t column = 0;
  double value = 0.0;
};

struct RestoredElement {
  std::size_t row = 0;
  std::size_t column = 0;
  std::uint32_t valueBits = 0;

  bool operator<(const RestoredElement& other) const {
    return std::tie(row, column, valueBits) <
        std::tie(other.row, other.column, other.valueBits);
  }

  bool operator==(const RestoredElement& other) const {
    return row == other.row && column == other.column && valueBits == other.valueBits;
  }
};

void expect(bool condition, const std::string& message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

void expectThrows(const std::function<void()>& operation, const std::string& message) {
  try {
    operation();
  } catch (const std::exception&) {
    return;
  }
  throw std::runtime_error(message);
}

std::uint32_t floatBits(double value) {
  const float converted = static_cast<float>(value);
  std::uint32_t bits = 0;
  static_assert(sizeof(bits) == sizeof(converted));
  std::memcpy(&bits, &converted, sizeof(bits));
  return bits;
}

CsrMatrix makeMatrix(std::size_t rows, std::size_t columns,
                     std::vector<InputElement> elements) {
  std::stable_sort(elements.begin(), elements.end(),
                   [](const InputElement& lhs, const InputElement& rhs) {
                     return lhs.row < rhs.row;
                   });
  CsrMatrix matrix;
  matrix.rows = rows;
  matrix.columns = columns;
  matrix.rowPointers.assign(rows + 1U, 0);
  for (const InputElement& element : elements) {
    if (element.row >= rows) {
      throw std::invalid_argument("测试矩阵行号越界");
    }
    ++matrix.rowPointers[element.row + 1U];
  }
  for (std::size_t row = 0; row < rows; ++row) {
    matrix.rowPointers[row + 1U] += matrix.rowPointers[row];
  }
  for (const InputElement& element : elements) {
    matrix.columnIndices.push_back(element.column);
    matrix.values.push_back(element.value);
  }
  return matrix;
}

std::vector<RestoredElement> restore(const cuper::CuperPackage& package) {
  std::vector<RestoredElement> restored;
  const std::size_t batchWidth = cuper::columnsPerBatch(package.config);
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    for (std::size_t channel = 0; channel < package.matrixChannels.size(); ++channel) {
      const std::size_t begin = package.channelBatchPointers[channel][batch];
      const std::size_t end = package.channelBatchPointers[channel][batch + 1U];
      for (std::size_t beat = begin; beat < end; ++beat) {
        for (std::size_t lane = 0; lane < cuper::kLanesPerBeat; ++lane) {
          const cuper::DecodedCuperSlot slot =
              cuper::decodeSlot(package.matrixChannels[channel][beat][lane]);
          if (slot.padding) {
            continue;
          }
          std::uint32_t bits = 0;
          static_assert(sizeof(bits) == sizeof(slot.value));
          std::memcpy(&bits, &slot.value, sizeof(bits));
          const std::size_t pe = channel * cuper::kLanesPerBeat + lane;
          restored.push_back(RestoredElement{
              cuper::decodeOriginalRow(slot.encodedRow, pe, package.config),
              batch * batchWidth + slot.localColumn,
              bits});
        }
      }
    }
  }
  std::sort(restored.begin(), restored.end());
  return restored;
}

std::vector<RestoredElement> expectedElements(const CsrMatrix& matrix) {
  std::vector<RestoredElement> expected;
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    for (std::size_t index = matrix.rowPointers[row];
         index < matrix.rowPointers[row + 1U]; ++index) {
      expected.push_back(RestoredElement{
          row, matrix.columnIndices[index], floatBits(matrix.values[index])});
    }
  }
  std::sort(expected.begin(), expected.end());
  return expected;
}

void testRoundTripAndPadding() {
  const CsrMatrix matrix = makeMatrix(600, 600, {
      {0, 5, 1.5}, {0, 2, -2.25}, {1, 9, 0.0}, {255, 11, 3.125},
      {256, 12, -4.5}, {511, 13, 7.75}, {599, 599, -0.125}});
  const EncodedMatrix encoded = encodeMatrix(matrix);
  expect(encoded.format == EncodingFormat::Cuper, "统一接口返回了错误格式");
  const auto& package = std::get<cuper::CuperPackage>(encoded.package);
  expect(package.matrixChannels.size() == 16, "默认 Cuper package 必须有 16 个 HBM channel");
  expect(package.stats.validSlots == matrix.values.size(), "有效 slot 数与 nnz 不一致");
  expect(restore(package) == expectedElements(matrix), "Cuper package 解包后与 CSR 不一致");

  std::uint64_t padding = 0;
  for (const auto& channel : package.matrixChannels) {
    for (const cuper::CuperBeat& beat : channel) {
      for (std::uint64_t slot : beat) {
        if (cuper::decodeSlot(slot).padding) {
          expect(slot == cuper::kPaddingSlot, "padding slot 不是原版固定标记");
          ++padding;
        }
      }
    }
  }
  expect(padding == package.stats.paddingSlots, "padding 统计不一致");
}

void testReorderWindow() {
  const CsrMatrix matrix = makeMatrix(512, 512, {
      {300, 8, 1.0}, {300, 2, 2.0}, {300, 6, 3.0}, {300, 4, 4.0}});
  const cuper::CuperPackage package = cuper::encode(matrix);
  const std::size_t pe = cuper::peForRow(300, package.config);
  const std::size_t channel = pe / cuper::kLanesPerBeat;
  const std::size_t lane = pe % cuper::kLanesPerBeat;
  std::vector<std::size_t> positions;
  for (std::size_t beat = 0; beat < package.matrixChannels[channel].size(); ++beat) {
    const cuper::DecodedCuperSlot slot =
        cuper::decodeSlot(package.matrixChannels[channel][beat][lane]);
    if (!slot.padding && cuper::decodeOriginalRow(slot.encodedRow, pe, package.config) == 300) {
      positions.push_back(beat);
    }
  }
  expect(positions.size() == 4, "未找到全部 reorder 测试元素");
  for (std::size_t index = 1; index < positions.size(); ++index) {
    expect(positions[index] - positions[index - 1U] >= package.config.reorderWindow,
           "同一累加地址没有满足 RAW conflict window");
  }
}

void testOriginalSlotLayout() {
  const CsrMatrix matrix = makeMatrix(2, 4, {{0, 1, 1.0}, {1, 2, -2.0}});
  const cuper::CuperPackage package = cuper::encode(matrix);
  expect(package.config.reorderWindow == 7, "U55C Cuper 默认 RAW window 应为 7");
  expect(package.stats.maximumMatrixBeatsPerChannel == 2,
         "同一 row group 的 ping/pong 不应被当成 RAW 冲突");
  expect(package.matrixChannels[0].size() == 2 && package.matrixChannels[1].empty(),
         "per-HBM 动态长度没有去除其他 HBM 的统一尾部补齐");
  expect(package.stats.totalMatrixBeats == 2 && package.stats.paddingSlots == 14,
         "per-HBM 动态长度的 beat 或 padding 统计错误");
  const std::uint64_t firstExpected = (1ULL << 50U) | floatBits(1.0);
  const std::uint64_t secondExpected =
      (2ULL << 50U) | (1ULL << 32U) | floatBits(-2.0);
  expect(package.matrixChannels[0][0][0] == firstExpected,
         "Cuper lane 0 的 col/row/value 位域不兼容原版");
  expect(package.matrixChannels[0][1][0] == secondExpected,
         "Cuper ping/pong 连续 slot 的位域不兼容原版");
}

void testHtmlReport() {
  const CsrMatrix matrix = makeMatrix(2, 4, {{0, 1, 1.0}, {1, 2, -2.0}});
  const EncodedMatrix encoded = encodeMatrix(matrix);
  std::ostringstream output;
  writeHtmlReport(output, encoded,
                  EncodingReportMetadata{"tiny\"matrix", "/tmp/</script>&source"});
  const std::string html = output.str();
  expect(html.find("<!doctype html>") != std::string::npos,
         "Cuper HTML 报告缺少文档声明");
  expect(html.find("id=\"packageView\"") != std::string::npos &&
         html.find("id=\"batchView\"") != std::string::npos &&
         html.find("id=\"channelView\"") != std::string::npos &&
         html.find("id=\"slotView\"") != std::string::npos,
         "Cuper HTML 报告缺少四层下钻视图");
  expect(html.find("id=\"batchGrid\"") != std::string::npos &&
         html.find("id=\"channelGrid\"") != std::string::npos &&
         html.find("id=\"slotMatrix\"") != std::string::npos &&
         html.find("id=\"matrixMode\"") != std::string::npos &&
         html.find("id=\"paddingPrev\"") != std::string::npos &&
         html.find("id=\"paddingNext\"") != std::string::npos &&
         html.find("id=\"bitfield\"") != std::string::npos,
         "Cuper HTML 报告缺少二维平面或 Slot 位域视图");
  expect(html.find("0x000400003f800000") != std::string::npos,
         "Cuper HTML 报告缺少有效 raw slot");
  expect(html.find("0x0003ffff00000000") != std::string::npos,
         "Cuper HTML 报告缺少 padding raw slot");
  expect(html.find("</script>&source") == std::string::npos &&
         html.find("\\u003c/script\\u003e\\u0026source") != std::string::npos,
         "Cuper HTML 报告没有安全转义数据集元数据");

  EncodedMatrix invalid = encoded;
  std::get<cuper::CuperPackage>(invalid.package).matrixChannels.pop_back();
  expectThrows([&invalid]() {
    std::ostringstream ignored;
    writeHtmlReport(ignored, invalid, EncodingReportMetadata{});
  }, "Cuper HTML 报告未拒绝损坏的 package");
}

void testColumnBatches() {
  const CsrMatrix matrix = makeMatrix(4, 9000, {
      {0, 8191, 1.0}, {0, 8192, 2.0}, {1, 10, 3.0}, {3, 8999, 4.0}});
  const cuper::CuperPackage package = cuper::encode(matrix);
  expect(package.channelBatchPointers.size() == package.config.hbmChannelCount,
         "per-HBM batch pointer 数量错误");
  for (const auto& pointers : package.channelBatchPointers) {
    expect(pointers.size() == 3 && pointers[0] == 0 && pointers[1] <= pointers[2],
           "per-HBM batch pointer 不满足累计边界语义");
  }
  expect(restore(package) == expectedElements(matrix), "跨 batch 的局部列号还原失败");
}

void testEmptyAndValidation() {
  const CsrMatrix empty = makeMatrix(4, 9000, {});
  const cuper::CuperPackage package = cuper::encode(empty);
  expect(std::all_of(package.channelBatchPointers.begin(),
                     package.channelBatchPointers.end(), [](const auto& pointers) {
                       return pointers == std::vector<std::uint32_t>({0, 0, 0});
                     }), "空矩阵的 per-HBM batch pointers 错误");
  expect(package.stats.totalMatrixBeats == 0 && package.stats.validSlots == 0,
         "空矩阵不应产生有效 beat");

  CsrMatrix badPointers = makeMatrix(2, 2, {{0, 0, 1.0}});
  badPointers.rowPointers[1] = 2;
  expectThrows([&badPointers]() { (void)cuper::encode(badPointers); },
               "非法 rowPointers 未被拒绝");

  const CsrMatrix badColumn = makeMatrix(1, 1, {{0, 1, 1.0}});
  expectThrows([&badColumn]() { (void)cuper::encode(badColumn); },
               "越界 column index 未被拒绝");

  cuper::CuperConfig badConfig;
  badConfig.hbmChannelCount = 10;
  const CsrMatrix valid = makeMatrix(1, 1, {{0, 0, 1.0}});
  expectThrows([&valid, &badConfig]() { (void)cuper::encode(valid, badConfig); },
               "非法 HBM channel 配置未被拒绝");
  expectThrows([]() { (void)parseEncodingFormat("unknown"); },
               "未知统一编码格式未被拒绝");
}

void testOriginalPeMapping() {
  const cuper::CuperConfig config;
  for (std::size_t row = 0; row < 4096; ++row) {
    const std::size_t packet = row / 2U;
    const std::size_t expected =
        ((packet % 8U) * 2U + (packet / 8U) % 2U) * 8U + (packet / 16U) % 8U;
    const std::size_t pe = cuper::peForRow(row, config);
    expect(pe == expected, "默认 16-HBM PE 映射与原版 Cuper 不一致");
    const std::uint32_t encodedRow = static_cast<std::uint32_t>(
        (row / (2U * cuper::totalPeCount(config))) * 2U + row % 2U);
    expect(cuper::decodeOriginalRow(encodedRow, pe, config) == row,
           "Cuper row/PE 逆映射失败");
  }
}

}  // namespace
}  // namespace accelerator_sim::spmv::encoding

int main() {
  try {
    accelerator_sim::spmv::encoding::testRoundTripAndPadding();
    accelerator_sim::spmv::encoding::testReorderWindow();
    accelerator_sim::spmv::encoding::testOriginalSlotLayout();
    accelerator_sim::spmv::encoding::testHtmlReport();
    accelerator_sim::spmv::encoding::testColumnBatches();
    accelerator_sim::spmv::encoding::testEmptyAndValidation();
    accelerator_sim::spmv::encoding::testOriginalPeMapping();
    std::cout << "[spmv-encoding-test] Cuper package PASS\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "[spmv-encoding-test] FAIL: " << error.what() << '\n';
    return 1;
  }
}
