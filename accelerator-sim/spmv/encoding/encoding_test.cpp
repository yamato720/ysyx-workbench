#include "encoder.hpp"
#include "cuper/demand_schedule.hpp"
#include "cuperflow/demand_schedule.hpp"
#include "cuperflow/fixtures.hpp"
#include "cuperflow/product_beat_golden.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <iostream>
#include <limits>
#include <numeric>
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

std::uint64_t doubleBits(double value) {
  std::uint64_t bits = 0;
  static_assert(sizeof(bits) == sizeof(value));
  std::memcpy(&bits, &value, sizeof(bits));
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
              cuper::rowForPeLocal(channel * cuper::kLanesPerBeat + lane,
                                   slot.localRow, package.config),
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

std::vector<RestoredElement> expectedCuperflowElements(const CsrMatrix& matrix) {
  std::vector<RestoredElement> expected;
  for (std::size_t row = 0; row < matrix.rows; ++row) {
    for (std::size_t index = matrix.rowPointers[row];
         index < matrix.rowPointers[row + 1U]; ++index) {
      if (matrix.values[index] == 0.0) {
        continue;
      }
      expected.push_back(RestoredElement{
          row, matrix.columnIndices[index], floatBits(matrix.values[index])});
    }
  }
  std::sort(expected.begin(), expected.end());
  return expected;
}

std::vector<RestoredElement> restoreCuperflow(
    const cuperflow::CuperflowPackage& package,
    const cuperflow::CuperflowDemandSchedule* schedule = nullptr) {
  std::vector<RestoredElement> restored;
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    for (std::size_t channel = 0; channel < package.matrixChannels.size(); ++channel) {
      for (std::size_t group = 0; group < package.sliceGroupCount; ++group) {
        const std::size_t groupSegment = batch * package.sliceGroupCount + group;
        const std::size_t firstSlice = group * package.sliceGroupSize;
        const std::size_t groupSliceCount = std::min(
            package.sliceGroupSize, package.columnSliceCount - firstSlice);
        for (std::size_t lane = 0; lane < cuperflow::kLanesPerBeat; ++lane) {
          const auto range = package.channelLaneSliceGroupRanges[channel][groupSegment][lane];
          const std::size_t begin = range.first;
          const std::size_t end = range.second;
          for (std::size_t beat = begin; beat < end; ++beat) {
            if ((package.matrixEntryMasks[channel][beat] & (1U << lane)) == 0U) {
              continue;
            }
            const cuperflow::DecodedCuperflowSlot slot =
                cuperflow::decodeSlot(package.matrixChannels[channel][beat][lane]);
            expect(slot.localColumn < groupSliceCount * package.config.sliceSize,
                   "Cuperflow group column 超出所属 slice group");
            const std::size_t physicalSlice = firstSlice +
                slot.localColumn / package.config.sliceSize;
            const std::size_t logicalSlice = schedule == nullptr ? physicalSlice :
                schedule->batches[batch].pageOrder[physicalSlice];
            const std::size_t physicalRow = cuperflow::physicalRowForBatchLocal(
                batch, slot.localRow, package.config);
            expect(physicalRow < package.physicalToOriginalRows.size(),
                   "Cuperflow slot 的 physical row 超出重排映射");
            std::uint32_t bits = 0;
            static_assert(sizeof(bits) == sizeof(slot.value));
            std::memcpy(&bits, &slot.value, sizeof(bits));
            restored.push_back(RestoredElement{
                package.physicalToOriginalRows[physicalRow],
                logicalSlice * package.config.sliceSize +
                    slot.localColumn % package.config.sliceSize, bits});
          }
        }
      }
    }
  }
  std::sort(restored.begin(), restored.end());
  return restored;
}

void testCuperflowRowBatchesAndColumnSlices() {
  const CsrMatrix matrix = makeMatrix(9000, 9000, {
      {0, 0, 1.0}, {0, 63, 2.0}, {0, 64, 3.0}, {0, 4095, 4.0},
      {8191, 1, 5.0}, {8191, 8192, 6.0}, {8192, 2, 7.0}, {8999, 8999, 8.0}});
  EncodingOptions options;
  options.format = EncodingFormat::Cuperflow;
  const EncodedMatrix encoded = encodeMatrix(matrix, options);
  const auto& package = std::get<cuperflow::CuperflowPackage>(encoded.package);
  expect(package.stats.batchCount == 2, "Cuperflow batch 必须沿行方向划分");
  expect(package.columnSliceCount == 141, "Cuperflow column-slice 数量错误");
  expect(package.sliceGroupSize == 8 && package.sliceGroupCount == 18 &&
         package.sliceGroupChannels.size() == package.sliceGroupCount &&
         package.channelSliceGroups.size() == package.config.hbmChannelCount,
         "Cuperflow slice group 默认宽度或数量错误");
  for (std::size_t group = 0; group < package.sliceGroupCount; ++group) {
    expect(package.sliceGroupChannels[group] == group % package.config.hbmChannelCount,
           "Cuperflow slice group 没有轮转映射到 HBM");
  }
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
      for (std::size_t group = 0; group < package.sliceGroupCount; ++group) {
        const auto& ranges = package.channelLaneSliceGroupRanges[channel][
            batch * package.sliceGroupCount + group];
        for (const auto& range : ranges) {
          expect(package.sliceGroupChannels[group] == channel || range.first == range.second,
                 "Cuperflow 非 owner HBM 仍包含该 slice group 的 A 数据");
        }
      }
    }
  }
  expect(package.stats.matrixSlots == matrix.values.size(),
         "Cuperflow 矩阵 slot 数与 nnz 不一致");
  expect(package.physicalToOriginalRows.size() == matrix.rows,
         "Cuperflow row 重排映射长度错误");
  expect(restoreCuperflow(package) == expectedElements(matrix),
         "Cuperflow row batch/column slice 解包后与 CSR 不一致");
  const cuperflow::CuperflowDemandSchedule schedule =
      cuperflow::planXPageSchedule(package);
  expect(schedule.batches.size() == package.stats.batchCount,
         "Cuperflow X page 调度 batch 数量错误");
  expect(schedule.sliceGroupSize == package.sliceGroupSize &&
         schedule.sliceGroupCount == package.sliceGroupCount &&
         schedule.sliceGroupChannels == package.sliceGroupChannels &&
         schedule.channelSliceGroups == package.channelSliceGroups,
         "Cuperflow X page 调度没有携带 slice group 几何");
  EncodingOptions stripedOptions = options;
  stripedOptions.cuperflow.aPacking = cuperflow::CuperflowAPacking::LaneStriped;
  const EncodedMatrix stripedEncoded = encodeMatrix(matrix, stripedOptions);
  const auto& striped = std::get<cuperflow::CuperflowPackage>(stripedEncoded.package);
  const cuperflow::CuperflowDemandSchedule stripedSchedule =
      cuperflow::planXPageSchedule(striped);
  const cuperflow::CuperflowPackage remapped =
      cuperflow::remapLocalColumnsForXPageSchedule(striped, stripedSchedule);
  expect(restoreCuperflow(remapped, &stripedSchedule) == expectedElements(matrix),
      "Cuperflow X page 重排后解包结果与 CSR 不一致");
  expect(remapped.channelLaneSliceGroupRanges.size() == striped.config.hbmChannelCount,
         "Cuperflow X page 重排丢失 slice group range");
  for (const auto& ranges : package.channelLaneSliceGroupRanges) {
    expect(ranges.size() == package.stats.batchCount * package.sliceGroupCount,
           "Cuperflow slice group range 形状错误");
    for (const auto& rangeSet : ranges) {
      for (std::size_t lane = 0; lane < cuperflow::kLanesPerBeat; ++lane) {
        expect(rangeSet[lane].first <= rangeSet[lane].second,
               "Cuperflow slice group range 非法");
      }
    }
  }

  options.cuperflow.sliceGroupSize = 32;
  expectThrows([&matrix, &options]() { (void)encodeMatrix(matrix, options); },
               "Cuperflow 未拒绝无法覆盖全部 HBM 的 slice group");
  options.cuperflow.sliceGroupSize = 8;
  const EncodedMatrix narrowGroupEncoded = encodeMatrix(matrix, options);
  const auto& narrowGroup = std::get<cuperflow::CuperflowPackage>(narrowGroupEncoded.package);
  expect(narrowGroup.sliceGroupSize == 8 && narrowGroup.sliceGroupCount == 18 &&
         restoreCuperflow(narrowGroup) == expectedElements(matrix),
         "Cuperflow 可配置的 slice group 破坏了 CSR round-trip");

  std::vector<double> vectorInput(65536);
  for (std::size_t column = 0; column < vectorInput.size(); ++column) {
    vectorInput[column] = static_cast<double>(column);
  }
  const cuperflow::CuperflowVectorPackage vectorPackage =
      cuperflow::encodeVector(vectorInput);
  expect(vectorPackage.stats.rangeCount == 16 &&
         vectorPackage.stats.maximumRangeElements == 4096 &&
         vectorPackage.stats.payloadBeats == 8192 &&
         vectorPackage.stats.allocatedBeats == 8192 &&
         vectorPackage.channelXRanges.size() == 16,
         "Cuperflow X 没有按 HBM 形成 4096 元素独占 range");
  for (std::size_t column : {0U, 7U, 8U, 4095U, 4096U, 65535U}) {
    std::uint64_t expected = 0;
    std::memcpy(&expected, &vectorInput[column], sizeof(expected));
    expect(vectorPackage.hbmBeats[column / cuperflow::kVectorLanesPerBeat]
                                   [column % cuperflow::kVectorLanesPerBeat] == expected,
           "Cuperflow X 没有保持 FP64 原始 bits");
  }
  for (std::size_t channel = 0; channel < vectorPackage.channelXRanges.size(); ++channel) {
    expect(vectorPackage.channelXRanges[channel].size() == 1 &&
           vectorPackage.channelXRanges[channel][0].sliceGroup == channel &&
           vectorPackage.channelXRanges[channel][0].elementCount == 4096 &&
           vectorPackage.channelHbmBeats[channel].size() == 512,
           "Cuperflow per-HBM X payload 边界错误");
  }
}

void testCuperflowRowRoundRobinPacking() {
  std::vector<InputElement> elements;
  for (std::uint32_t column = 0; column < 9; ++column) {
    elements.push_back(InputElement{0, column, static_cast<double>(column + 1U)});
  }
  elements.insert(elements.end(), {
      {1, 16, 1.0}, {1, 17, 2.0}, {1, 18, 3.0}, {1, 19, 4.0}, {1, 20, 5.0}, {1, 21, 6.0},
      {2, 24, 1.0}, {2, 25, 2.0}, {2, 26, 3.0}, {2, 27, 4.0},
      {3, 32, 1.0}, {3, 33, 2.0}, {3, 34, 3.0},
      {4, 36, 1.0}, {4, 37, 2.0}, {5, 40, 1.0},
      {8191, 41, 1.0}, {8192, 48, 1.0}});
  const CsrMatrix matrix = makeMatrix(8193, 64, std::move(elements));

  EncodingOptions options;
  options.format = EncodingFormat::Cuperflow;
  options.cuperflow.rowReorder = false;
  const EncodedMatrix encoded = encodeMatrix(matrix, options);
  const auto& package = std::get<cuperflow::CuperflowPackage>(encoded.package);
  expect(package.config.aPacking == cuperflow::CuperflowAPacking::RowRoundRobin,
         "Cuperflow 默认 A 打包必须是 row-round-robin");
  expect(package.stats.batchCount == 2 && package.sliceGroupCount == 1,
         "Cuperflow row-round-robin 测试矩阵几何错误");

  cuperflow::validatePackage(package);
  std::array<std::size_t, 3> chunkCounts{};
  for (std::size_t beat = 0; beat < package.matrixChannels[0].size(); ++beat) {
    const std::uint8_t mask = package.matrixEntryMasks[0][beat];
    for (std::size_t lane = 0; lane < cuperflow::kLanesPerBeat; ++lane) {
      const std::uint64_t word = package.matrixChannels[0][beat][lane];
      if ((mask & (std::uint8_t{1} << lane)) == 0U) {
        expect(word == cuperflow::kZeroFillSlot,
               "Cuperflow 8/4/2 尾包的无效 lane 必须以全零 slot 补齐");
        continue;
      }
      const cuperflow::DecodedCuperflowSlot slot = cuperflow::decodeSlot(word);
      expect(static_cast<unsigned>(slot.chunkMode) <=
                 static_cast<unsigned>(cuperflow::CuperflowChunkMode::Four2),
             "Cuperflow slot v6 不得生成 chunkMode=11");
      ++chunkCounts[static_cast<std::size_t>(slot.chunkMode)];
    }
  }
  expect(chunkCounts[0] != 0 && chunkCounts[1] != 0 && chunkCounts[2] != 0,
         "Cuperflow row-round-robin 未覆盖完整 8、4+4、2+2+2+2 chunk");
  expect(package.stats.full8ChunkCount != 0 && package.stats.two4ChunkCount != 0 &&
             package.stats.four2ChunkCount != 0,
         "Cuperflow V0 chunk 统计没有记录 8/4/2 边界");
  expect(restoreCuperflow(package) == expectedElements(matrix),
         "Cuperflow row-round-robin 无法恢复原始 CSR 矩阵");
}

void testCuperflowTailPackingVariants() {
  const CsrMatrix matrix = makeMatrix(3, 64, {
      {0, 0, 1.0}, {0, 1, 2.0}, {0, 2, 3.0},
      {1, 8, 1.0}, {2, 16, 1.0}, {2, 17, 2.0},
  });
  const auto check = [&](cuperflow::CuperflowTailPacking packing,
                         const std::array<std::uint8_t, 2>& expectedMasks) {
    EncodingOptions options;
    options.format = EncodingFormat::Cuperflow;
    options.cuperflow.rowReorder = false;
    options.cuperflow.tailPacking = packing;
    const EncodedMatrix encoded = encodeMatrix(matrix, options);
    const auto& package = std::get<cuperflow::CuperflowPackage>(encoded.package);
    expect(package.config.tailPacking == packing,
           "Cuperflow package 没有保留 row tail 打包策略");
    expect(package.matrixEntryMasks[0].size() == expectedMasks.size(),
           "Cuperflow row tail 策略产生了错误的 A beat 数量");
    for (std::size_t beat = 0; beat < expectedMasks.size(); ++beat) {
      expect(package.matrixEntryMasks[0][beat] == expectedMasks[beat],
             "Cuperflow row tail 策略的内部 padding 位置错误");
    }
    expect(restoreCuperflow(package) == expectedElements(matrix),
           "Cuperflow row tail 策略无法恢复原始 CSR 矩阵");
  };

  EncodingOptions defaultOptions;
  defaultOptions.format = EncodingFormat::Cuperflow;
  defaultOptions.cuperflow.rowReorder = false;
  const EncodedMatrix defaultEncoded = encodeMatrix(matrix, defaultOptions);
  const auto& defaultPackage = std::get<cuperflow::CuperflowPackage>(defaultEncoded.package);
  expect(defaultPackage.config.tailPacking == cuperflow::CuperflowTailPacking::Pad3To4And1To2,
         "Cuperflow 默认 row tail 策略必须是 pad3-1");
  expect(defaultPackage.matrixEntryMasks[0] == std::vector<std::uint8_t>({0x07U, 0x0dU}),
         "Cuperflow 默认 pad3-1 的内部 padding 位置错误");

  expectThrows([&]() {
    EncodingOptions compactOptions;
    compactOptions.format = EncodingFormat::Cuperflow;
    compactOptions.cuperflow.rowReorder = false;
    compactOptions.cuperflow.tailPacking = cuperflow::CuperflowTailPacking::Compact421;
    (void)encodeMatrix(matrix, compactOptions);
  }, "Cuperflow slot v6 未拒绝会产生 1-slot chunk 的 Compact421");
  check(cuperflow::CuperflowTailPacking::Pad3To4And1To2, {{0x07U, 0x0dU}});
  check(cuperflow::CuperflowTailPacking::PadAllTo4, {{0x17U, 0x03U}});
}

void testCuperflowV0Protocol() {
  const CsrMatrix matrix = makeMatrix(8193, 64, {
      {0, 0, 1.0}, {0, 1, 2.0}, {0, 2, 3.0},
      {1, 8, 4.0}, {1, 9, 5.0},
      {8192, 16, 6.0},
  });
  EncodingOptions options;
  options.format = EncodingFormat::Cuperflow;
  options.cuperflow.rowReorder = false;
  const EncodedMatrix encoded = encodeMatrix(matrix, options);
  const auto& package = std::get<cuperflow::CuperflowPackage>(encoded.package);
  cuperflow::validatePackage(package);
  expect(package.stats.batchDescriptorCount == 2 &&
             package.channelBatchDescriptors[0].size() == 2,
         "Cuperflow V0 没有为每个 batch 写入 descriptor");
  const auto& first = package.channelBatchDescriptors[0][0];
  const auto& second = package.channelBatchDescriptors[0][1];
  expect(first.batchId == 0 && first.activeRowCount == 8192 &&
             first.contributorWordCount == 128 && !first.lastBatchInGroup &&
             second.batchId == 1 && second.activeRowCount == 1 &&
             second.contributorWordCount == 1 && second.lastBatchInGroup,
         "Cuperflow V0 BATCH_DESC 的完整/短 batch 字段错误");
  const cuperflow::CuperflowVectorBeat packed = cuperflow::packBatchDescriptor(first);
  expect(cuperflow::isBatchDescriptorMarker(packed[0]) &&
             cuperflow::unpackBatchDescriptor(packed).activeRowCount == 8192,
         "Cuperflow V0 BATCH_DESC pack/unpack round-trip 失败");
  expectThrows([&]() {
    auto corrupt = packed;
    corrupt[5] = 1U;
    (void)cuperflow::unpackBatchDescriptor(corrupt);
  }, "Cuperflow V0 BATCH_DESC 未拒绝 reserved 位");

  std::vector<double> x(64, 0.25);
  const cuperflow::CuperflowVectorPackage vectorPackage =
      cuperflow::encodeVector(x, package);
  cuperflow::validateXPayloadLoads(package, vectorPackage);
  const cuperflow::CuperflowXRange& range = vectorPackage.channelXRanges[0][0];
  const cuperflow::CuperflowMapBeat map =
      cuperflow::unpackMapBeat(vectorPackage.channelHbmBeats[0][range.mapBeat]);
  expect(map.batchDescriptorCount == 2 && range.batchDescriptorCount == 2 &&
             range.descriptorBeatEnd - range.descriptorBeatBegin == 19,
         "Cuperflow V0 控制流没有按 [DESC][bitmap] 写入两个 batch");
  expect(cuperflow::unpackBatchDescriptor(
             vectorPackage.channelHbmBeats[0][range.descriptorBeatBegin]).batchId == 0 &&
             cuperflow::unpackBatchDescriptor(
                 vectorPackage.channelHbmBeats[0][range.descriptorBeatBegin + 17U]).batchId == 1,
         "Cuperflow V0 descriptor/bitmap 的控制流顺序错误");
  expectThrows([&]() {
    auto corrupt = vectorPackage.channelHbmBeats[0][range.mapBeat];
    corrupt[2] |= std::uint64_t{1} << 32U;
    (void)cuperflow::unpackMapBeat(corrupt);
  }, "Cuperflow V0 GROUP_MAP 未拒绝 reserved 位");
  expectThrows([&]() {
    auto corrupt = vectorPackage.channelHbmBeats[0][range.mapBeat];
    corrupt[4] |= std::uint64_t{1} << 27U;
    (void)cuperflow::unpackMapBeat(corrupt);
  }, "Cuperflow V0 GROUP_MAP 未拒绝 segment descriptor reserved 位");
  expectThrows([&]() {
    (void)cuperflow::unpackBatchDescriptor(
        vectorPackage.channelHbmBeats[0][range.mapBeat]);
  }, "Cuperflow V0 未拒绝 map/descriptor opcode 混淆");

  auto invalidBitmap = package;
  invalidBitmap.channelContributorWords[0][first.contributorOffsetWords] ^= 1U;
  expectThrows([&]() { cuperflow::validatePackage(invalidBitmap); },
               "Cuperflow V0 package validator 未发现 bitmap/rowLast 不一致");
  auto invalidSlot = package;
  bool corruptedSlot = false;
  for (std::size_t beat = 0; beat < invalidSlot.matrixChannels[0].size() && !corruptedSlot;
       ++beat) {
    const std::uint8_t mask = invalidSlot.matrixEntryMasks[0][beat];
    for (std::size_t lane = 0; lane < cuperflow::kLanesPerBeat; ++lane) {
      if ((mask & (std::uint8_t{1} << lane)) != 0U) {
        invalidSlot.matrixChannels[0][beat][lane] |= std::uint64_t{0x3} << 45U;
        corruptedSlot = true;
        break;
      }
    }
  }
  expect(corruptedSlot, "Cuperflow V0 负向 slot 测试没有找到有效 lane");
  expectThrows([&]() { cuperflow::validatePackage(invalidSlot); },
               "Cuperflow V0 package validator 未拒绝 chunkMode=11");

  const CsrMatrix multiPc = makeMatrix(1, 1024, {
      {0, 0, 1.0}, {0, 64, 2.0},
  });
  const EncodedMatrix multiPcEncoded = encodeMatrix(multiPc, options);
  const auto& multiPcPackage =
      std::get<cuperflow::CuperflowPackage>(multiPcEncoded.package);
  expect(multiPcPackage.sliceGroupCount == 16 && multiPcPackage.contributorWaveCount == 1 &&
             multiPcPackage.contributorMasksByWaveBatch[0].size() == 1 &&
             multiPcPackage.contributorMasksByWaveBatch[0][0] == 0x0003U,
         "Cuperflow V0 没有将两个 PC 的 rowLast bitmap 转置为同一 wave 的 16-bit mask");
  cuperflow::validatePackage(multiPcPackage);

  const CsrMatrix emptyBatch = makeMatrix(8193, 64, {{0, 0, 1.0}});
  const EncodedMatrix emptyBatchEncoded = encodeMatrix(emptyBatch, options);
  const auto& emptyBatchPackage =
      std::get<cuperflow::CuperflowPackage>(emptyBatchEncoded.package);
  expect(emptyBatchPackage.channelBatchDescriptors[0].size() == 2 &&
             emptyBatchPackage.channelBatchDescriptors[0][1].aBeats == 0 &&
             emptyBatchPackage.channelBatchDescriptors[0][1].lastBatchInGroup &&
             emptyBatchPackage.stats.emptyBatchCount == 1,
         "Cuperflow V0 空 batch 没有通过 BATCH_DESC 推进 epoch");
  cuperflow::validatePackage(emptyBatchPackage);

  const CsrMatrix explicitZero = makeMatrix(1, 8, {
      {0, 0, 0.0}, {0, 1, -0.0}, {0, 2, 1.0},
      {0, 3, std::numeric_limits<double>::quiet_NaN()},
  });
  const EncodedMatrix zeroEncoded = encodeMatrix(explicitZero, options);
  const auto& zeroPackage = std::get<cuperflow::CuperflowPackage>(zeroEncoded.package);
  expect(zeroPackage.stats.droppedExplicitZeros == 2 && zeroPackage.nonzeros == 2 &&
             zeroPackage.stats.matrixSlots == 2,
         "Cuperflow V0 没有 canonicalize 显式 FP32 正负零");
  cuperflow::validatePackage(zeroPackage);
}

void testCuperflowV0Fixtures() {
  for (const cuperflow::fixtures::V0Fixture& fixture : cuperflow::fixtures::v0()) {
    const std::string prefix = "Cuperflow V0 fixture '" + fixture.name + "': ";
    const cuperflow::CuperflowPackage package =
        cuperflow::encode(fixture.matrix, fixture.config);
    std::vector<double> input(fixture.matrix.columns);
    for (std::size_t column = 0; column < input.size(); ++column) {
      input[column] = 0.25 + static_cast<double>(column);
    }
    const cuperflow::CuperflowVectorPackage vectorPackage =
        cuperflow::encodeVector(input, package);
    cuperflow::validatePackage(package);
    cuperflow::validateXPayloadLoads(package, vectorPackage);
    expect(restoreCuperflow(package) == expectedCuperflowElements(fixture.matrix),
           prefix + "slot round-trip 与冻结的显式零规则不一致");

    const auto& stats = package.stats;
    expect(stats.rowPartial1BeatCount + stats.rowPartial2BeatCount +
               stats.rowPartial4BeatCount == stats.totalMatrixBeats,
           prefix + "RowPartial beat 统计未覆盖全部 A beat");
    const std::uint64_t pcBeats = std::accumulate(
        package.pcL1Stats.begin(), package.pcL1Stats.end(), std::uint64_t{0},
        [](std::uint64_t sum, const cuperflow::CuperflowPcL1Stats& pc) {
          return sum + pc.aBeats;
        });
    const std::uint64_t pcSlots = std::accumulate(
        package.pcL1Stats.begin(), package.pcL1Stats.end(), std::uint64_t{0},
        [](std::uint64_t sum, const cuperflow::CuperflowPcL1Stats& pc) {
          return sum + pc.effectiveSlots;
        });
    expect(pcBeats == stats.totalMatrixBeats && pcSlots == stats.matrixSlots,
           prefix + "per-PC A beat 或有效 slot 汇总错误");
    expect(package.waveBatchL1Stats.size() ==
               stats.batchCount * package.contributorWaveCount,
           prefix + "(wave,batch) L1 统计长度错误");
    expect(stats.xPayloadLoadCount == vectorPackage.stats.rangeCount &&
               stats.expectedXPayloadLoadCount == vectorPackage.stats.rangeCount,
           prefix + "BATCH_DESC 改变了每 group 一次的 X payload 计划");

    switch (fixture.kind) {
      case cuperflow::fixtures::V0FixtureKind::Full8:
        expect(stats.full8ChunkCount == 3U, prefix + "未保留完整 8-slot chunk");
        break;
      case cuperflow::fixtures::V0FixtureKind::Tail44:
        expect(stats.two4ChunkCount == 2U && stats.rowPartial2BeatCount == 1U,
               prefix + "未形成两个 4-slot RowPartial 的共享 beat");
        break;
      case cuperflow::fixtures::V0FixtureKind::Tail2222:
        expect(stats.four2ChunkCount == 4U && stats.rowPartial4BeatCount == 1U,
               prefix + "未形成四个 2-slot RowPartial 的共享 beat");
        break;
      case cuperflow::fixtures::V0FixtureKind::Pad3And1:
        expect(stats.zeroFillSlots == 12U && stats.two4ChunkCount == 1U &&
                   stats.four2ChunkCount == 1U,
               prefix + "3->4 / 1->2 的物理 padding 或 chunkMode 错误");
        break;
      case cuperflow::fixtures::V0FixtureKind::EmptyPcRow:
        expect(package.contributorWaveCount == 1U &&
                   package.contributorMasksByWaveBatch[0][0] == 0x0003U,
               prefix + "部分 PC 空贡献的 mask 错误");
        break;
      case cuperflow::fixtures::V0FixtureKind::EmptyBatch:
        expect(package.channelBatchDescriptors[0][1].aBeats == 0U &&
                   package.channelBatchDescriptors[0][1].lastBatchInGroup,
               prefix + "空 batch 未写出推进 epoch 的 descriptor");
        break;
      case cuperflow::fixtures::V0FixtureKind::LastShortBatch:
        expect(package.channelBatchDescriptors[0][1].activeRowCount == 1U,
               prefix + "最后短 batch 的有效行数错误");
        break;
      case cuperflow::fixtures::V0FixtureKind::SameLocalRowNextBatch:
        expect(package.contributorMasksByWaveBatch[0][0] == 0x0001U &&
                   package.contributorMasksByWaveBatch[1][0] == 0x0001U,
               prefix + "相同 localRow 跨 batch 发生 epoch 别名");
        break;
      case cuperflow::fixtures::V0FixtureKind::MultiWaveSameY:
        expect(package.contributorWaveCount == 2U &&
                   package.contributorMasksByWaveBatch[0][0] == 0x0001U &&
                   package.contributorMasksByWaveBatch[1][0] == 0x0001U,
               prefix + "同一 Y 行跨 wave 的 contributor epoch 错误");
        break;
      case cuperflow::fixtures::V0FixtureKind::ExplicitZero: {
        bool preservedUnderflow = false;
        for (std::size_t beat = 0; beat < package.matrixChannels[0].size(); ++beat) {
          const std::uint8_t mask = package.matrixEntryMasks[0][beat];
          for (std::size_t lane = 0; lane < cuperflow::kLanesPerBeat; ++lane) {
            if ((mask & (std::uint8_t{1} << lane)) == 0U) {
              continue;
            }
            const auto slot = cuperflow::decodeSlot(package.matrixChannels[0][beat][lane]);
            preservedUnderflow = preservedUnderflow ||
                (slot.localColumn == 5U && slot.value == 0.0F);
          }
        }
        expect(stats.droppedExplicitZeros == 2U && stats.matrixSlots == 4U &&
                   preservedUnderflow,
               prefix + "仅 +0/-0 可 canonicalize，FP32 下溢/NaN/Inf 必须保留");
        break;
      }
      case cuperflow::fixtures::V0FixtureKind::EightXSegments:
        expect(package.xSegmentsByGroup[0].size() == cuperflow::kMaxXSegments &&
                   vectorPackage.channelXRanges[0][0].segments.size() ==
                       cuperflow::kMaxXSegments,
               prefix + "未生成八段 X 的最大 segmentId package");
        break;
    }
  }
}

void testCuperflowProductBeatGolden() {
  const auto fixtures = cuperflow::fixtures::v0();
  const auto full8 = std::find_if(fixtures.begin(), fixtures.end(),
      [](const auto& fixture) {
        return fixture.kind == cuperflow::fixtures::V0FixtureKind::Full8;
      });
  expect(full8 != fixtures.end(), "Cuperflow ProductBeat golden 缺少 full8 fixture");

  std::vector<double> x(full8->matrix.columns);
  for (std::size_t column = 0; column < x.size(); ++column) {
    x[column] = 0.25 + static_cast<double>(column);
  }
  const cuperflow::CuperflowPackage package = cuperflow::encode(full8->matrix, full8->config);
  const std::vector<cuperflow::CuperflowProductBeatGolden> beats =
      cuperflow::makeProductBeatGolden(package, x);
  expect(beats.size() == 3U && beats[0].pc == 0U && beats[0].wave == 0U &&
             beats[0].batch == 0U && beats[0].beatSeq == 0U &&
             beats[0].laneValid == 0xffU && beats[0].chunkMode == 0U,
         "Cuperflow ProductBeat golden 没有冻结 full8 的原子 sideband");
  for (std::size_t lane = 0; lane < cuperflow::kLanesPerBeat; ++lane) {
    expect(beats[0].localRow[lane] == 0U && beats[0].rowLast[lane] &&
               beats[0].product[lane] == doubleBits(
                   static_cast<double>(lane + 1U) * x[lane]),
           "Cuperflow ProductBeat golden 的 full8 首 beat 乘积或 row sideband 错误");
  }
  expect(beats[1].beatSeq == 1U && beats[2].beatSeq == 2U &&
             !beats[1].rowLast[0] && beats[2].rowLast[0],
         "Cuperflow ProductBeat golden 没有保留同 row 的 rowLast 顺序");

  for (const auto& fixture : fixtures) {
    std::vector<double> input(fixture.matrix.columns);
    for (std::size_t column = 0; column < input.size(); ++column) {
      input[column] = 0.25 + static_cast<double>(column);
    }
    const cuperflow::CuperflowPackage fixturePackage =
        cuperflow::encode(fixture.matrix, fixture.config);
    const std::vector<cuperflow::CuperflowProductBeatGolden> fixtureBeats =
        cuperflow::makeProductBeatGolden(fixturePackage, input);
    expect(fixtureBeats.size() == fixturePackage.stats.totalMatrixBeats,
           "Cuperflow ProductBeat golden 没有覆盖全部有效 A beat");
    for (const auto& beat : fixtureBeats) {
      expect(beat.laneValid != 0U && beat.chunkMode != 0x3U,
             "Cuperflow ProductBeat golden 生成了空 beat 或非法 chunkMode");
      for (std::size_t lane = 0; lane < cuperflow::kLanesPerBeat; ++lane) {
        if ((beat.laneValid & (std::uint8_t{1} << lane)) == 0U) {
          expect(beat.product[lane] == 0U && beat.localRow[lane] == 0U &&
                     !beat.rowLast[lane],
                 "Cuperflow ProductBeat golden 的 padding lane 不符合 +0.0 合同");
        }
      }
    }
  }
}

void testCuperflowRowReorder() {
  std::vector<InputElement> elements;
  for (std::uint32_t column = 0; column < 8; ++column) {
    elements.push_back(InputElement{0, column, static_cast<double>(column + 1U)});
  }
  elements.push_back(InputElement{1, 15, -2.0});
  const CsrMatrix matrix = makeMatrix(256, 16, std::move(elements));

  EncodingOptions options;
  options.format = EncodingFormat::Cuperflow;
  const EncodedMatrix encoded = encodeMatrix(matrix, options);
  const auto& package = std::get<cuperflow::CuperflowPackage>(encoded.package);
  expect(package.config.rowReorder, "Cuperflow 默认应启用 row 重排");
  expect(restoreCuperflow(package) == expectedElements(matrix),
         "Cuperflow row 重排后无法恢复原始 CSR 行");
  bool moved = false;
  for (std::size_t physicalRow = 0;
       physicalRow < package.physicalToOriginalRows.size(); ++physicalRow) {
    moved = moved || package.physicalToOriginalRows[physicalRow] != physicalRow;
  }
  expect(moved,
         "Cuperflow row 重排没有改变不均衡行的物理位置");

  options.cuperflow.rowReorder = false;
  const EncodedMatrix identityEncoded = encodeMatrix(matrix, options);
  const auto& identity = std::get<cuperflow::CuperflowPackage>(identityEncoded.package);
  bool identityRows = true;
  for (std::size_t physicalRow = 0;
       physicalRow < identity.physicalToOriginalRows.size(); ++physicalRow) {
    identityRows = identityRows &&
        identity.physicalToOriginalRows[physicalRow] == physicalRow;
  }
  expect(identityRows,
         "Cuperflow 关闭 row 重排时映射不是 identity");
  expect(restoreCuperflow(identity) == expectedElements(matrix),
         "Cuperflow 关闭 row 重排后无法恢复原始 CSR 行");
}

void testCuperflowPcParameterization() {
  std::vector<InputElement> elements;
  for (std::size_t row = 0; row < 256; ++row) {
    elements.push_back(InputElement{row, static_cast<std::uint32_t>((row % 16U) * 64U),
                                    static_cast<double>(row + 1U)});
  }
  const CsrMatrix matrix = makeMatrix(256, 1024, std::move(elements));
  const std::vector<double> input(1024, 0.5);

  for (std::size_t channelCount = 1; channelCount <= 16; ++channelCount) {
    cuperflow::CuperflowConfig config;
    config.hbmChannelCount = channelCount;
    config.sliceGroupSize = 1;
    const cuperflow::CuperflowPackage package = cuperflow::encode(matrix, config);
    const cuperflow::CuperflowVectorPackage vectorPackage =
        cuperflow::encodeVector(input, package);
    cuperflow::validatePackage(package);
    cuperflow::validateXPayloadLoads(package, vectorPackage);
    expect(restoreCuperflow(package) == expectedCuperflowElements(matrix),
           "Cuperflow 参数化 PC 数破坏 slot round-trip");

    std::vector<bool> seen(channelCount, false);
    for (std::size_t packet = 0; packet < channelCount * cuperflow::kLanesPerBeat;
         ++packet) {
      const std::size_t pe = cuperflow::peForRow(packet * 2U, config);
      seen[pe / cuperflow::kLanesPerBeat] = true;
    }
    expect(std::all_of(seen.begin(), seen.end(), [](bool value) { return value; }),
           "Cuperflow 参数化 PC 数没有覆盖全部 HBM channel");
  }

  const cuperflow::CuperflowConfig eight = [] {
    cuperflow::CuperflowConfig config;
    config.hbmChannelCount = 8;
    return config;
  }();
  const cuperflow::CuperflowConfig sixteen = [] {
    cuperflow::CuperflowConfig config;
    config.hbmChannelCount = 16;
    return config;
  }();
  for (std::size_t packet = 0; packet < 8; ++packet) {
    expect(cuperflow::peForRow(packet * 2U, eight) / cuperflow::kLanesPerBeat == packet,
           "8-PC Cuperflow checker 顺序发生变化");
  }
  const std::array<std::size_t, 16> expected16 = {
      0, 2, 4, 6, 8, 10, 12, 14, 1, 3, 5, 7, 9, 11, 13, 15};
  for (std::size_t packet = 0; packet < expected16.size(); ++packet) {
    expect(cuperflow::peForRow(packet * 2U, sixteen) / cuperflow::kLanesPerBeat ==
               expected16[packet],
           "16-PC Cuperflow checker 顺序发生变化");
  }
}

void testCuperflowFlexibleXEncoding() {
  const CsrMatrix matrix = makeMatrix(32, 64, {
      {0, 1, 1.0}, {0, 2, 2.0}, {0, 6, 6.0}, {0, 7, 7.0}});
  const cuperflow::CuperflowPackage matrixPackage = cuperflow::encode(matrix);
  expect(matrixPackage.xUsedColumnsByGroup.size() == 1 &&
         matrixPackage.xUsedColumnsByGroup[0] ==
             std::vector<std::uint32_t>({1, 2, 6, 7}),
         "Cuperflow A 遍历没有收集并去重 sliceGroup 列集合");

  std::vector<double> input(64);
  for (std::size_t column = 0; column < input.size(); ++column) {
    input[column] = static_cast<double>(column) + 0.5;
  }
  const cuperflow::CuperflowVectorPackage vectorPackage =
      cuperflow::encodeVector(input, matrixPackage);
  expect(vectorPackage.flexibleXEncoding == cuperflow::kFlexibleXEncodingEnabled,
         "Cuperflow 灵活 X 开关状态没有反映编译宏");

  const auto& ranges = vectorPackage.channelXRanges[0];
  expect(ranges.size() == 1 && ranges[0].sliceGroup == 0 &&
         ranges[0].usedElementCount == 4,
         "Cuperflow 多段 X 的实际列统计错误");
  const cuperflow::CuperflowXRange& range = ranges[0];
  if (cuperflow::kFlexibleXEncodingEnabled) {
    expect(range.segments == std::vector<cuperflow::CuperflowXSegment>(
               {{1, 2}, {6, 2}}) && range.elementCount == 4,
           "Cuperflow 多段 X 的逻辑地址区间错误");
    expect(range.encodedWordCount == 4 && range.valueCount == 4 &&
           range.markerCount == 0 && range.beatEnd - range.beatBegin == 1 &&
           range.mapBeat != std::numeric_limits<std::uint32_t>::max() &&
           range.aBeats != 0 && vectorPackage.stats.markerCount == 0 &&
           vectorPackage.stats.demandedElements == 4 && vectorPackage.stats.segmentCount == 2,
           "Cuperflow 灵活 X 没有生成两段紧凑 payload");
    const auto& mapBeat = vectorPackage.channelHbmBeats[0][range.mapBeat];
    const cuperflow::CuperflowMapBeat map = cuperflow::unpackMapBeat(mapBeat);
    expect(cuperflow::isXMapMarker(mapBeat[0]) && map.xBeats == 1 && map.xWords == 4 &&
           map.xElements == 4 && map.xSegments[0].start == 1 && map.xSegments[0].count == 2 &&
           map.xSegments[1].start == 6 && map.xSegments[1].count == 2 && map.last,
           "Cuperflow 多段 X 没有在 payload 前写入正确 map");
    const auto& beat = vectorPackage.channelHbmBeats[0][range.beatBegin];
    expect(!cuperflow::isXAddressMarker(beat[0]) &&
           !cuperflow::isXAddressMarker(beat[1]) &&
           !cuperflow::isXAddressMarker(beat[3]) &&
           beat[0] == doubleBits(input[1]) && beat[1] == doubleBits(input[2]) &&
           beat[2] == doubleBits(input[6]) && beat[3] == doubleBits(input[7]),
           "Cuperflow 多段 X 的 payload 不是按段原样 FP64 顺序流");
    expect(vectorPackage.stats.encodedPayloadBeats == 1 &&
           vectorPackage.stats.encodedLanePaddingWords == 4,
           "Cuperflow 多段 X 的物理 beat 尾部统计错误");

    const CsrMatrix denseFromZero = makeMatrix(8, 16, {
        {0, 0, 1.0}, {0, 1, 1.0}, {0, 2, 1.0}, {0, 3, 1.0}});
    const cuperflow::CuperflowVectorPackage densePackage =
        cuperflow::encodeVector(std::vector<double>(16, 0.5),
            cuperflow::encode(denseFromZero));
    const cuperflow::CuperflowXRange& denseRange = densePackage.channelXRanges[0][0];
    expect(denseRange.valueCount == 4 && denseRange.segments.size() == 1 &&
           denseRange.segments[0].start == 0 && denseRange.segments[0].count == 4 &&
           denseRange.markerCount == 0 &&
           !cuperflow::isXAddressMarker(
               densePackage.channelHbmBeats[0][denseRange.beatBegin][0]),
           "从本地地址 0 起的连续 X 不应再插入 origin marker");
  } else {
    expect(!vectorPackage.flexibleXEncoding && range.segments ==
               std::vector<cuperflow::CuperflowXSegment>({{0, 64}}) &&
           range.elementCount == 64 && range.encodedWordCount == 64 &&
           range.valueCount == 64 && range.markerCount == 0 &&
           range.beatEnd - range.beatBegin == 8,
           "关闭灵活 X 宏后没有回到连续满载路径");
  }

  expect(cuperflow::isXAddressMarker(cuperflow::makeXAddressMarker(8191)) &&
         cuperflow::decodeXAddressMarker(cuperflow::makeXAddressMarker(8191)) == 8191U &&
         !cuperflow::isXAddressMarker(0x3ff0000000000000ULL),
         "Cuperflow 64-bit 地址 marker 位型错误");
  expectThrows([]() { (void)cuperflow::makeXAddressMarker(8192); },
               "Cuperflow 地址 marker 未拒绝超出 13-bit 的 BRAM 地址");
  expectThrows([]() {
    (void)cuperflow::encodeVector(std::vector<double>{1.0, std::numeric_limits<double>::quiet_NaN()});
  }, "Cuperflow X 编码未拒绝 NaN 输入");
  expectThrows([]() {
    (void)cuperflow::encodeVector(std::vector<double>{1.0, std::numeric_limits<double>::infinity()});
  }, "Cuperflow X 编码未拒绝 Inf 输入");

  EncodedVector encoded{EncodingFormat::Cuperflow, vectorPackage};
  std::ostringstream output;
  writeVectorHtmlReport(output, encoded,
      EncodingReportMetadata{"flex-x", "/tmp/flex-x.txt"});
  const std::string html = output.str();
  expect(html.find("\"flexibleX\":true") != std::string::npos ||
         html.find("\"flexibleX\":false") != std::string::npos,
         "Cuperflow X HTML 报告缺少灵活模式配置");
  if (cuperflow::kFlexibleXEncodingEnabled) {
    expect(html.find("encodedXRanges") != std::string::npos &&
           html.find("\"demandedElements\":4") != std::string::npos &&
           html.find("\"markerCount\":0") != std::string::npos,
           "Cuperflow X HTML 报告缺少多段 payload 统计");
  }
}

void testCuperflowExtremeSparseXSpan() {
  constexpr std::size_t kGroupColumns = 8192;
  constexpr std::size_t kColumns = 16 * kGroupColumns;
  constexpr std::size_t kDemandedColumns = kGroupColumns / 2;
  std::vector<InputElement> elements;
  elements.reserve(kDemandedColumns);
  for (std::size_t row = 0; row < kDemandedColumns; ++row) {
    elements.push_back(InputElement{row, static_cast<std::uint32_t>(2U * row + 1U),
                                    1.0 + static_cast<double>(row % 7U)});
  }
  const CsrMatrix matrix = makeMatrix(kDemandedColumns, kColumns, std::move(elements));
  const cuperflow::CuperflowPackage matrixPackage = cuperflow::encode(matrix);
  expect(matrixPackage.sliceGroupCount == 16 && matrixPackage.sliceGroupSize == 128 &&
         matrixPackage.xUsedColumnsByGroup.size() == 16 &&
         matrixPackage.xUsedColumnsByGroup[0].size() == kDemandedColumns,
         "Cuperflow 极端稀疏 X 测试没有形成默认的 8192 列 group");
  expect(matrixPackage.xUsedColumnsByGroup[0].front() == 1U &&
         matrixPackage.xUsedColumnsByGroup[0].back() == 8191U,
         "Cuperflow 极端稀疏 X 列集合边界错误");

  std::vector<double> input(kColumns);
  for (std::size_t column = 0; column < input.size(); ++column) {
    input[column] = 0.25 + static_cast<double>(column);
  }
  const cuperflow::CuperflowVectorPackage vectorPackage =
      cuperflow::encodeVector(input, matrixPackage);
  const auto& ranges = vectorPackage.channelXRanges[0];
  expect(ranges.size() == 1 && ranges[0].sliceGroup == 0 &&
         ranges[0].usedElementCount == kDemandedColumns,
         "Cuperflow 极端稀疏 X range 需求统计错误");
  const cuperflow::CuperflowXRange& range = ranges[0];
  if (cuperflow::kFlexibleXEncodingEnabled) {
    expect(range.segments == std::vector<cuperflow::CuperflowXSegment>({{1, 8191}}) &&
           range.elementCount == 8191 &&
           range.encodedWordCount == 8191 && range.valueCount == 8191 &&
           range.markerCount == 0 && range.beatEnd - range.beatBegin == 1024,
           "Cuperflow 极端稀疏 X 没有退化为正确的连续包围 span");
    const cuperflow::CuperflowMapBeat map =
        cuperflow::unpackMapBeat(vectorPackage.channelHbmBeats[0][range.mapBeat]);
    expect(map.xBeats == 1024 && map.xWords == 8191 && map.xElements == 8191 &&
           map.xSegments[0].start == 1 && map.xSegments[0].count == 8191 && map.last,
           "Cuperflow 极端稀疏 X map 没有描述完整连续 span");
    const auto& firstBeat = vectorPackage.channelHbmBeats[0][range.beatBegin];
    const auto& lastBeat = vectorPackage.channelHbmBeats[0][range.beatEnd - 1U];
    expect(firstBeat[0] == doubleBits(input[1]) && firstBeat[2] == doubleBits(input[3]) &&
           lastBeat[6] == doubleBits(input[8191]) && lastBeat[7] == 0U &&
           vectorPackage.stats.encodedLanePaddingWords == 1,
           "Cuperflow 极端稀疏 X payload 或末尾 mask 几何错误");
  } else {
    expect(range.segments == std::vector<cuperflow::CuperflowXSegment>({{0, kGroupColumns}}) &&
           range.elementCount == kGroupColumns &&
           range.encodedWordCount == kGroupColumns && range.beatEnd - range.beatBegin == 1024,
           "关闭灵活 X 宏后极端稀疏 X 没有回到满载路径");
  }
}

void testCuperflowThreeIslandXSpan() {
  constexpr std::size_t kGroupColumns = 8192;
  constexpr std::size_t kColumns = 16 * kGroupColumns;
  constexpr std::size_t kIslandElements = 100;
  constexpr std::size_t kDemandedColumns = 3 * kIslandElements;
  std::vector<InputElement> elements;
  elements.reserve(kDemandedColumns);
  const auto addIsland = [&elements](std::size_t firstColumn) {
    for (std::size_t offset = 0; offset < kIslandElements; ++offset) {
      elements.push_back(InputElement{elements.size(),
                                      static_cast<std::uint32_t>(firstColumn + offset),
                                      1.0 + static_cast<double>(offset % 7U)});
    }
  };
  addIsland(0);
  addIsland(kGroupColumns / 2);
  addIsland(kGroupColumns - kIslandElements);

  const CsrMatrix matrix = makeMatrix(kDemandedColumns, kColumns, std::move(elements));
  const cuperflow::CuperflowPackage matrixPackage = cuperflow::encode(matrix);
  expect(matrixPackage.sliceGroupCount == 16 && matrixPackage.sliceGroupSize == 128 &&
         matrixPackage.xUsedColumnsByGroup[0].size() == kDemandedColumns,
         "Cuperflow 三岛 X 测试没有形成目标 8192 列 group");
  const std::vector<std::uint32_t>& usedColumns = matrixPackage.xUsedColumnsByGroup[0];
  expect(usedColumns.front() == 0U && usedColumns[99] == 99U &&
         usedColumns[100] == 4096U && usedColumns[199] == 4195U &&
         usedColumns[200] == 8092U && usedColumns.back() == 8191U,
         "Cuperflow 三岛 X 列集合错误");

  std::vector<double> input(kColumns);
  for (std::size_t column = 0; column < input.size(); ++column) {
    input[column] = 0.25 + static_cast<double>(column);
  }
  const cuperflow::CuperflowVectorPackage vectorPackage =
      cuperflow::encodeVector(input, matrixPackage);
  const auto& ranges = vectorPackage.channelXRanges[0];
  expect(ranges.size() == 1 && ranges[0].sliceGroup == 0 &&
         ranges[0].usedElementCount == kDemandedColumns,
         "Cuperflow 三岛 X range 需求统计错误");
  const cuperflow::CuperflowXRange& range = ranges[0];
  if (cuperflow::kFlexibleXEncodingEnabled) {
    expect(range.segments == std::vector<cuperflow::CuperflowXSegment>(
               {{0, kIslandElements}, {4096, kIslandElements},
                {kGroupColumns - kIslandElements, kIslandElements}}) &&
           range.elementCount == kDemandedColumns && range.encodedWordCount == kDemandedColumns &&
           range.valueCount == kDemandedColumns && range.markerCount == 0 &&
           range.beatEnd - range.beatBegin == 38,
           "Cuperflow 三岛 X 没有生成三个紧凑段");
  } else {
    expect(range.segments == std::vector<cuperflow::CuperflowXSegment>({{0, kGroupColumns}}) &&
           range.elementCount == kGroupColumns && range.encodedWordCount == kGroupColumns &&
           range.valueCount == kGroupColumns && range.markerCount == 0 &&
           range.beatEnd - range.beatBegin == 1024,
           "关闭灵活 X 宏后三岛 X 没有回到满载路径");
  }
  const cuperflow::CuperflowMapBeat map =
      cuperflow::unpackMapBeat(vectorPackage.channelHbmBeats[0][range.mapBeat]);
  expect(map.xBeats == (cuperflow::kFlexibleXEncodingEnabled ? 38 : 1024) &&
         map.xWords == (cuperflow::kFlexibleXEncodingEnabled ? kDemandedColumns : kGroupColumns) &&
         map.xElements == (cuperflow::kFlexibleXEncodingEnabled ? kDemandedColumns : kGroupColumns) &&
         map.xSegments[0].start == 0 &&
         map.xSegments[0].count == (cuperflow::kFlexibleXEncodingEnabled ? kIslandElements : kGroupColumns) &&
         map.last && vectorPackage.stats.demandedElements == kDemandedColumns &&
         vectorPackage.stats.encodedLanePaddingWords ==
             (cuperflow::kFlexibleXEncodingEnabled ? 4 : 0),
         "Cuperflow 三岛 X map 或 payload 统计错误");
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

std::size_t globalRowForSlot(const cuper::CuperPackage& package, std::size_t pe,
                             const cuper::DecodedCuperSlot& slot) {
  return cuper::rowForPeLocal(pe, slot.localRow, package.config);
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
        globalRowForSlot(package, pe,
                         cuper::decodeSlot(package.matrixChannels[channel][beat][lane])) == 300) {
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
         globalRowForSlot(residencyPackage, pe, residency[0]) == 0 && residency[0].tag == 0 &&
         globalRowForSlot(residencyPackage, pe, residency[1]) == 256 && residency[1].tag == 1 &&
         globalRowForSlot(residencyPackage, pe, residency[2]) == 0 && residency[2].tag == 0,
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
    expect(globalRowForSlot(lruPackage, pe, lru[index]) == rows[index],
           "累加上下文编码改变了发射行顺序");
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
  const std::uint64_t secondExpected = (2ULL << 51U) | floatBits(-2.0);
  expect(package.matrixChannels[0][0][0] == firstExpected,
         "Cuper lane 0 的 slot v4 位域错误");
  expect(package.matrixChannels[0][0][1] == secondExpected,
         "Cuper lane 1 的 slot v4 位域错误");
  const cuper::DecodedCuperSlot first = cuper::decodeSlot(firstExpected);
  expect(first.tag == 0 && first.localRow == 0 && first.localColumn == 1,
         "Cuper slot v4 的 PE-local 行标或 tag 解码错误");
  const cuper::DecodedCuperSlot second = cuper::decodeSlot(secondExpected);
  expect(second.localRow == 0 &&
         cuper::rowForPeLocal(1, second.localRow, package.config) == 32,
         "不同 PE 的相同行 localRow 没有反解为各自的全局行号");
  const std::uint64_t contextSlot = (3ULL << 48U) | (42ULL << 32U) | floatBits(0.5);
  const cuper::DecodedCuperSlot context = cuper::decodeSlot(contextSlot);
  expect(context.tag == 3 && context.localRow == 42 && context.localColumn == 0,
         "Cuper slot v4 的累加上下文位域影响了其他字段解码");
  const cuper::DecodedCuperSlot zeroFill = cuper::decodeSlot(cuper::kZeroFillSlot);
  expect(zeroFill.tag == 0 && zeroFill.localRow == 0 && zeroFill.localColumn == 0,
         "Cuper slot v4 的全零填充不应编码特殊 tag");
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
}

void testPeLocalRowVirtualization() {
  const cuper::CuperConfig config;
  for (const std::size_t row : std::array<std::size_t, 7>{
           0, 1, 255, 256, 65535, 65536, 1228044}) {
    const std::size_t pe = cuper::peForRow(row, config);
    const std::size_t localRow = cuper::localRowForRow(row, config);
    expect(localRow <= ((std::size_t{1} << cuper::kRowBits) - 1U),
           "Cuper PE-local 行标超过 slot 位域");
    expect(cuper::rowForPeLocal(pe, localRow, config) == row,
           "Cuper PE-local 行标无法反解全局行号");
  }

  const CsrMatrix large = makeMatrix(1228045, 4, {
      {0, 0, 1.0}, {65535, 1, -2.0}, {65536, 2, 3.0},
      {1228044, 3, -4.0}});
  const cuper::CuperPackage package = cuper::encode(large, config);
  expect(restore(package) == expectedElements(large),
         "超过 16-bit 全局行号的 Cuper package 未能 round-trip");
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

void testDemandSchedule() {
  const CsrMatrix matrix = makeMatrix(256, 256, {{0, 192, 1.0}});
  const cuper::CuperPackage package = cuper::encode(matrix);
  const cuper::CuperDemandSchedule schedule = cuper::planXPageSchedule(package);
  expect(schedule.config.pageElements == 64 && schedule.config.xElementsPerCycle == 8,
         "Cuper demand schedule 没有采用当前输入顶层的 page/bandwidth 模型");
  expect(schedule.batches.size() == 1 && schedule.batches[0].pageCount == 4,
         "Cuper demand schedule 的 page 数量错误");
  const cuper::CuperDemandBatchPlan& batch = schedule.batches[0];
  expect(batch.xLoadCycles == 32 && batch.pageOrder.size() == 4 &&
         batch.pageOrder[0] == 0 && batch.pageOrder[1] == 3,
         "Cuper demand schedule 未优先装载可释放首个 A beat 的 page");
  expect(batch.channels[0].aBeats == 1 &&
         batch.channels[0].baseline.firstIssueCycle == 32 &&
         batch.channels[0].planned.firstIssueCycle == 16 &&
         batch.planned.issuedBeforeXComplete == 1,
         "Cuper demand schedule 没有保序计算 A beat 的早启动周期");
  expect(batch.planned.firstIssueCycle <= batch.baseline.firstIssueCycle,
         "Cuper demand schedule 不应推迟最早 A beat 的启动");
  const cuper::CuperPackage remapped =
      cuper::remapLocalColumnsForXPageSchedule(package, schedule);
  expect(cuper::decodeSlot(remapped.matrixChannels[0][0][0]).localColumn == 64,
         "Cuper X page 重排没有同步改写 A slot 的 local column");
  expect(remapped.channelBatchPointers == package.channelBatchPointers &&
         remapped.stats.matrixSlots == package.stats.matrixSlots,
         "Cuper X page 重排不应改变 A HBM 边界或矩阵统计");
  std::ostringstream output;
  cuper::writeDemandScheduleJson(output, schedule,
      "demand\"schedule", "/tmp/</script>&matrix");
  const std::string json = output.str();
  expect(json.find("\"format\":\"cuper-x-page-demand-v1\"") != std::string::npos &&
         json.find("\"pageOrder\":[0,3") != std::string::npos &&
         json.find("</script>&matrix") == std::string::npos &&
         json.find("\\u003c/script\\u003e\\u0026matrix") != std::string::npos,
         "Cuper demand schedule JSON 缺少计划字段或未安全转义元数据");
}

}  // namespace
}  // namespace accelerator_sim::spmv::encoding

int main() {
  try {
    accelerator_sim::spmv::encoding::testRoundTripAndZeroFill();
    accelerator_sim::spmv::encoding::testReorderWindow();
    accelerator_sim::spmv::encoding::testAccumulationContextEncoding();
    accelerator_sim::spmv::encoding::testCuperflowRowBatchesAndColumnSlices();
    accelerator_sim::spmv::encoding::testCuperflowRowRoundRobinPacking();
    accelerator_sim::spmv::encoding::testCuperflowTailPackingVariants();
    accelerator_sim::spmv::encoding::testCuperflowV0Protocol();
    accelerator_sim::spmv::encoding::testCuperflowV0Fixtures();
    accelerator_sim::spmv::encoding::testCuperflowProductBeatGolden();
    accelerator_sim::spmv::encoding::testCuperflowRowReorder();
    accelerator_sim::spmv::encoding::testCuperflowPcParameterization();
    accelerator_sim::spmv::encoding::testCuperflowFlexibleXEncoding();
    accelerator_sim::spmv::encoding::testCuperflowExtremeSparseXSpan();
    accelerator_sim::spmv::encoding::testCuperflowThreeIslandXSpan();
    accelerator_sim::spmv::encoding::testSlotV3Layout();
    accelerator_sim::spmv::encoding::testHtmlReport();
    accelerator_sim::spmv::encoding::testColumnBatches();
    accelerator_sim::spmv::encoding::testEmptyAndValidation();
    accelerator_sim::spmv::encoding::testCuperPeMapping();
    accelerator_sim::spmv::encoding::testPeLocalRowVirtualization();
    accelerator_sim::spmv::encoding::testVectorEncoding();
    accelerator_sim::spmv::encoding::testDemandSchedule();
    std::cout << "[spmv-encoding-test] Cuper A/X packages PASS\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "[spmv-encoding-test] FAIL: " << error.what() << '\n';
    return 1;
  }
}
