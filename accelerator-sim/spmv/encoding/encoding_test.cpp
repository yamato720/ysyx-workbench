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
          if ((package.matrixEntryMasks[channel][beat] & (1U << lane)) == 0U) {
            continue;
          }
          const cuper::DecodedCuperSlot slot =
              cuper::decodeSlot(package.matrixChannels[channel][beat][lane]);
          std::uint32_t bits = 0;
          static_assert(sizeof(bits) == sizeof(slot.value));
          std::memcpy(&bits, &slot.value, sizeof(bits));
          restored.push_back(RestoredElement{
              slot.row,
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

std::vector<cuper::DecodedCuperSlot> slotsForPe(const cuper::CuperPackage& package,
                                                std::size_t batch,
                                                std::size_t pe) {
  if (batch >= package.stats.batchCount || pe >= cuper::totalPeCount(package.config)) {
    throw std::invalid_argument("测试请求的 Cuper batch 或 PE 越界");
  }
  const std::size_t channel = pe / cuper::kLanesPerBeat;
  const std::size_t lane = pe % cuper::kLanesPerBeat;
  const std::size_t begin = package.channelBatchPointers[channel][batch];
  const std::size_t end = package.channelBatchPointers[channel][batch + 1U];
  std::vector<cuper::DecodedCuperSlot> slots;
  for (std::size_t beat = begin; beat < end; ++beat) {
    if ((package.matrixEntryMasks[channel][beat] & (1U << lane)) != 0U) {
      slots.push_back(cuper::decodeSlot(package.matrixChannels[channel][beat][lane]));
    }
  }
  return slots;
}

void testRoundTripAndZeroFill() {
  const CsrMatrix matrix = makeMatrix(600, 600, {
      {0, 5, 1.5}, {0, 2, -2.25}, {1, 9, 0.0}, {255, 11, 3.125},
      {256, 12, -4.5}, {511, 13, 7.75}, {599, 599, -0.125}});
  const EncodedMatrix encoded = encodeMatrix(matrix);
  expect(encoded.format == EncodingFormat::Cuper, "统一接口返回了错误格式");
  const auto& package = std::get<cuper::CuperPackage>(encoded.package);
  expect(package.matrixChannels.size() == 16, "默认 Cuper package 必须有 16 个 HBM channel");
  expect(package.stats.matrixSlots == matrix.values.size(), "矩阵 slot 数与 nnz 不一致");
  expect(restore(package) == expectedElements(matrix), "Cuper package 解包后与 CSR 不一致");

  std::uint64_t zeroFill = 0;
  for (std::size_t channel = 0; channel < package.matrixChannels.size(); ++channel) {
    for (std::size_t beat = 0; beat < package.matrixChannels[channel].size(); ++beat) {
      for (std::size_t lane = 0; lane < cuper::kLanesPerBeat; ++lane) {
        if ((package.matrixEntryMasks[channel][beat] & (1U << lane)) == 0U) {
          expect(package.matrixChannels[channel][beat][lane] == cuper::kZeroFillSlot,
                 "零填充 slot 必须是全零且不携带 tag 语义");
          ++zeroFill;
        }
      }
    }
  }
  expect(zeroFill == package.stats.zeroFillSlots, "零填充统计不一致");
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
    if ((package.matrixEntryMasks[channel][beat] & (1U << lane)) != 0U &&
        cuper::decodeSlot(package.matrixChannels[channel][beat][lane]).row == 300) {
      positions.push_back(beat);
    }
  }
  expect(positions.size() == 4, "未找到全部 reorder 测试元素");
  for (std::size_t index = 1; index < positions.size(); ++index) {
    expect(positions[index] - positions[index - 1U] >= package.config.reorderWindow,
           "同一累加地址没有满足 RAW conflict window");
  }
}

void testAccumulationContextEncoding() {
  expect(cuper::kAccumulationContextCount == 8,
         "3-bit tag 必须提供 8 个累加上下文");

  // row 0 和 row 256 落在同一 PE；中间行不会让已驻留的 row 0 更换上下文。
  const CsrMatrix residencyMatrix = makeMatrix(257, 3, {
      {0, 0, 1.0}, {0, 2, 2.0}, {256, 1, 3.0}});
  const cuper::CuperPackage residencyPackage = cuper::encode(residencyMatrix);
  const std::size_t pe = cuper::peForRow(0, residencyPackage.config);
  expect(cuper::peForRow(256, residencyPackage.config) == pe,
         "累加上下文测试行没有落到同一 PE");
  const std::vector<cuper::DecodedCuperSlot> residency =
      slotsForPe(residencyPackage, 0, pe);
  expect(residency.size() == 3 &&
         residency[0].row == 0 && residency[0].tag == 0 &&
         residency[1].row == 256 && residency[1].tag == 1 &&
         residency[2].row == 0 && residency[2].tag == 0,
         "驻留行没有复用原累加上下文");

  // 九个不同驻留行会填满 0..7，并让第九行换出 LRU 的 context 0；随后再次
  // 访问已被换出的 row 0 时，应继续换出 context 1，而不是错误复用旧映射。
  const std::vector<std::size_t> rows = {
      0, 1, 256, 257, 512, 513, 768, 769, 1024, 0};
  std::vector<InputElement> elements;
  for (std::size_t index = 0; index < rows.size(); ++index) {
    expect(cuper::peForRow(rows[index], residencyPackage.config) == pe,
           "LRU 测试行没有落到同一 PE");
    elements.push_back(InputElement{rows[index], static_cast<std::uint32_t>(index),
                                    static_cast<double>(index + 1U)});
  }
  const cuper::CuperPackage lruPackage = cuper::encode(
      makeMatrix(1025, rows.size(), std::move(elements)));
  const std::vector<cuper::DecodedCuperSlot> lru = slotsForPe(lruPackage, 0, pe);
  const std::vector<std::uint32_t> expectedContexts = {
      0, 1, 2, 3, 4, 5, 6, 7, 0, 1};
  expect(lru.size() == rows.size(), "LRU 测试没有保留全部矩阵项");
  for (std::size_t index = 0; index < lru.size(); ++index) {
    expect(lru[index].row == rows[index], "累加上下文编码改变了发射行顺序");
    expect(lru[index].tag == expectedContexts[index], "累加上下文 LRU 次序错误");
    expect(lru[index].tag < cuper::kAccumulationContextCount,
           "累加上下文超出 3-bit tag 范围");
  }

  // 每个 batch 都拥有独立上下文表；第二个 batch 不能继承第一个 batch 的 LRU 状态。
  const CsrMatrix batches = makeMatrix(257, 8193, {
      {0, 0, 1.0}, {256, 8192, 2.0}});
  const cuper::CuperPackage batchPackage = cuper::encode(batches);
  const std::vector<cuper::DecodedCuperSlot> firstBatch =
      slotsForPe(batchPackage, 0, pe);
  const std::vector<cuper::DecodedCuperSlot> secondBatch =
      slotsForPe(batchPackage, 1, pe);
  expect(firstBatch.size() == 1 && secondBatch.size() == 1 &&
         firstBatch[0].tag == 0 && secondBatch[0].tag == 0,
         "累加上下文没有在 batch 边界重新分配");
}

void testSlotV3Layout() {
  const CsrMatrix matrix = makeMatrix(33, 4, {{0, 1, 1.0}, {32, 2, -2.0}});
  const cuper::CuperPackage package = cuper::encode(matrix);
  expect(package.config.reorderWindow == 7, "U55C Cuper 默认 RAW window 应为 7");
  expect(package.stats.maximumMatrixBeatsPerChannel == 1,
         "两个独立 PE 应在同一 beat 发射");
  expect(package.matrixChannels[0].size() == 1 && package.matrixChannels[1].empty(),
         "per-HBM 动态长度没有去除其他 HBM 的统一尾部补齐");
  expect(package.stats.totalMatrixBeats == 1 && package.stats.zeroFillSlots == 6,
         "per-HBM 动态长度的 beat 或零填充统计错误");
  const std::uint64_t firstExpected = (1ULL << 51U) | floatBits(1.0);
  const std::uint64_t secondExpected =
      (2ULL << 51U) | (32ULL << 32U) | floatBits(-2.0);
  expect(package.matrixChannels[0][0][0] == firstExpected,
         "Cuper lane 0 的 slot v3 位域错误");
  expect(package.matrixChannels[0][0][1] == secondExpected,
         "Cuper lane 1 的 slot v3 位域错误");
  const cuper::DecodedCuperSlot first = cuper::decodeSlot(firstExpected);
  expect(first.tag == 0 && first.row == 0 && first.localColumn == 1,
         "Cuper slot v3 的直接行标或 tag 解码错误");
  const std::uint64_t contextSlot = (3ULL << 48U) | (42ULL << 32U) | floatBits(0.5);
  const cuper::DecodedCuperSlot context = cuper::decodeSlot(contextSlot);
  expect(context.tag == 3 && context.row == 42 && context.localColumn == 0,
         "Cuper slot v3 的累加上下文位域影响了其他字段解码");
  const cuper::DecodedCuperSlot zeroFill = cuper::decodeSlot(cuper::kZeroFillSlot);
  expect(zeroFill.tag == 0 && zeroFill.row == 0 && zeroFill.localColumn == 0,
         "Cuper slot v3 的全零填充不应编码特殊 tag");
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
         html.find("id=\"zeroFillPrev\"") != std::string::npos &&
         html.find("id=\"zeroFillNext\"") != std::string::npos &&
         html.find("id=\"bitfield\"") != std::string::npos,
         "Cuper HTML 报告缺少二维平面或 Slot 位域视图");
  expect(html.find("Accum Context [50:48]") != std::string::npos,
         "Cuper HTML 报告没有展示累加上下文位域");
  expect(html.find("首次装入") != std::string::npos &&
         html.find("Context scope") != std::string::npos,
         "Cuper HTML 报告没有记录累加上下文事件或作用域");
  expect(html.find("0x000800003f800000") != std::string::npos,
         "Cuper HTML 报告缺少有效 raw slot");
  expect(html.find("0x0000000000000000") != std::string::npos,
         "Cuper HTML 报告缺少全零填充 slot");
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
  expect(package.stats.totalMatrixBeats == 0 && package.stats.matrixSlots == 0,
         "空矩阵不应产生有效 beat");

  CsrMatrix badPointers = makeMatrix(2, 2, {{0, 0, 1.0}});
  badPointers.rowPointers[1] = 2;
  expectThrows([&badPointers]() { (void)cuper::encode(badPointers); },
               "非法 rowPointers 未被拒绝");

  const CsrMatrix badColumn = makeMatrix(1, 1, {{0, 1, 1.0}});
  expectThrows([&badColumn]() { (void)cuper::encode(badColumn); },
               "越界 column index 未被拒绝");

  cuper::CuperConfig badConfig;
  badConfig.hbmChannelCount = 0;
  const CsrMatrix valid = makeMatrix(1, 1, {{0, 0, 1.0}});
  expectThrows([&valid, &badConfig]() { (void)cuper::encode(valid, badConfig); },
               "非法 HBM channel 配置未被拒绝");
  const CsrMatrix excessiveRows = makeMatrix(65537, 1, {{65536, 0, 1.0}});
  expectThrows([&excessiveRows]() { (void)cuper::encode(excessiveRows); },
               "超过 16-bit 直接行标的矩阵未被拒绝");
  expectThrows([]() { (void)parseEncodingFormat("unknown"); },
               "未知统一编码格式未被拒绝");
}

void testCuperPeMapping() {
  const cuper::CuperConfig config;
  std::vector<std::size_t> rowsPerPe(cuper::totalPeCount(config), 0);
  for (std::size_t row = 0; row < 256; ++row) {
    ++rowsPerPe[cuper::peForRow(row, config)];
  }
  expect(std::all_of(rowsPerPe.begin(), rowsPerPe.end(),
                     [](std::size_t count) { return count == 2; }),
         "原 Cuper 映射没有在 256 行内为每个 PE 分配一对行");
  for (std::size_t row = 0; row < 4096; row += 2U) {
    expect(cuper::peForRow(row, config) == cuper::peForRow(row + 1U, config),
           "原 Cuper 映射的一对相邻行没有落到同一 PE");
    expect(cuper::peForRow(row, config) == cuper::peForRow(row % 256U, config),
           "原 Cuper PE 映射没有按 256 行重复");
  }
  expectThrows([&config]() { (void)cuper::peForRow(65536, config); },
               "slot v3 的 PE 映射未拒绝超过 16-bit 的直接行标");
}

void testVectorEncoding() {
  std::vector<double> input(8193);
  for (std::size_t column = 0; column < input.size(); ++column) {
    input[column] = static_cast<double>(column) + 0.25;
  }
  const EncodedVector encoded = encodeVector(input);
  const auto& package = std::get<cuper::CuperVectorPackage>(encoded.package);
  expect(package.columns == input.size(), "Cuper X package 列数错误");
  expect(package.batchPointers == std::vector<std::uint32_t>({0, 512, 513}),
         "Cuper X 跨 8192 列 batch 的累计指针错误");
  expect(package.stats.batchCount == 2 && package.stats.payloadBeats == 513 &&
         package.stats.allocatedBeats == 576,
         "Cuper X payload 或 1024-element 对齐统计错误");
  expect(package.stats.validElements == 8193 &&
         package.stats.lanePaddingElements == 15 &&
         package.stats.allocationPaddingElements == 1008,
         "Cuper X lane/allocation padding 统计错误");

  for (std::size_t column : {0U, 15U, 16U, 8191U, 8192U}) {
    std::uint32_t expected = 0;
    const float converted = static_cast<float>(input[column]);
    std::memcpy(&expected, &converted, sizeof(expected));
    expect(package.hbmBeats[column / cuper::kVectorLanesPerBeat]
                           [column % cuper::kVectorLanesPerBeat] == expected,
           "Cuper X 没有保持原列顺序或 FP32 bits 错误");
  }
  expect(std::all_of(package.hbmBeats[512].begin() + 1,
                     package.hbmBeats[512].end(), [](std::uint32_t bits) {
                       return bits == 0;
                     }), "Cuper X 最后一个 float_v16 beat 的 lane padding 不是零");
  expect(std::all_of(package.hbmBeats.begin() + 513, package.hbmBeats.end(),
                     [](const cuper::CuperVectorBeat& beat) {
                       return std::all_of(beat.begin(), beat.end(),
                                          [](std::uint32_t bits) { return bits == 0; });
                     }),
         "Cuper X HBM allocation padding 不是零");

  std::ostringstream output;
  writeVectorHtmlReport(output, encoded,
      EncodingReportMetadata{"x\"vector", "/tmp/</script>&b.txt"});
  const std::string html = output.str();
  expect(html.find("id=\"packageView\"") != std::string::npos &&
         html.find("id=\"batchView\"") != std::string::npos &&
         html.find("id=\"elementView\"") != std::string::npos &&
         html.find("id=\"replicas\"") != std::string::npos,
         "Cuper X HTML 报告缺少总览、batch 或本地副本视图");
  expect(html.find("\\u003c/script\\u003e\\u0026b.txt") != std::string::npos,
         "Cuper X HTML 报告没有安全转义数据源路径");

  EncodedVector invalid = encoded;
  std::get<cuper::CuperVectorPackage>(invalid.package).batchPointers.pop_back();
  expectThrows([&invalid]() {
    std::ostringstream ignored;
    writeVectorHtmlReport(ignored, invalid, EncodingReportMetadata{});
  }, "Cuper X HTML 报告未拒绝损坏的 package");

  expectThrows([]() { (void)cuper::encodeVector({}); },
               "Cuper X 编码未拒绝空输入");
  cuper::CuperConfig unaligned;
  unaligned.sliceSize = 3;
  unaligned.columnSlicesPerBatch = 1;
  expectThrows([&unaligned]() { (void)cuper::encodeVector({1.0}, unaligned); },
               "Cuper X 编码未拒绝非 float_v16 对齐的 batch");
}

}  // namespace
}  // namespace accelerator_sim::spmv::encoding

int main() {
  try {
    accelerator_sim::spmv::encoding::testRoundTripAndZeroFill();
    accelerator_sim::spmv::encoding::testReorderWindow();
    accelerator_sim::spmv::encoding::testAccumulationContextEncoding();
    accelerator_sim::spmv::encoding::testSlotV3Layout();
    accelerator_sim::spmv::encoding::testHtmlReport();
    accelerator_sim::spmv::encoding::testColumnBatches();
    accelerator_sim::spmv::encoding::testEmptyAndValidation();
    accelerator_sim::spmv::encoding::testCuperPeMapping();
    accelerator_sim::spmv::encoding::testVectorEncoding();
    std::cout << "[spmv-encoding-test] Cuper A/X packages PASS\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "[spmv-encoding-test] FAIL: " << error.what() << '\n';
    return 1;
  }
}
