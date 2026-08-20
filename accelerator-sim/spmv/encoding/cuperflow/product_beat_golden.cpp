#include "product_beat_golden.hpp"

#include <cstring>
#include <limits>
#include <stdexcept>

namespace accelerator_sim::spmv::encoding::cuperflow {
namespace {

std::uint64_t fp64Bits(double value) {
  std::uint64_t bits = 0;
  static_assert(sizeof(bits) == sizeof(value));
  std::memcpy(&bits, &value, sizeof(bits));
  return bits;
}

std::size_t groupFirstColumn(const CuperflowPackage& package, std::size_t group) {
  if (group > std::numeric_limits<std::size_t>::max() / package.sliceGroupSize ||
      group * package.sliceGroupSize >
          std::numeric_limits<std::size_t>::max() / package.config.sliceSize) {
    throw std::overflow_error("Cuperflow ProductBeat golden 的 group 列基址溢出");
  }
  return group * package.sliceGroupSize * package.config.sliceSize;
}

}  // namespace

std::vector<CuperflowProductBeatGolden> makeProductBeatGolden(
    const CuperflowPackage& package, const std::vector<double>& x) {
  validatePackage(package);
  if (package.matrixChannels.size() != package.config.hbmChannelCount ||
      package.channelSliceGroups.size() != package.config.hbmChannelCount ||
      package.channelGroupDescriptorOffsets.size() != package.config.hbmChannelCount ||
      package.channelBatchDescriptors.size() != package.config.hbmChannelCount ||
      package.matrixEntryMasks.size() != package.config.hbmChannelCount) {
    throw std::invalid_argument("Cuperflow ProductBeat golden 缺少完整 per-PC package 索引");
  }
  if (package.config.hbmChannelCount > std::numeric_limits<std::uint16_t>::max() ||
      package.contributorWaveCount > std::numeric_limits<std::uint16_t>::max()) {
    throw std::overflow_error("Cuperflow ProductBeat golden 的 PC 或 wave 超过 DPI ABI");
  }

  std::vector<CuperflowProductBeatGolden> result;
  for (std::size_t pc = 0; pc < package.config.hbmChannelCount; ++pc) {
    std::uint32_t beatSeq = 0;
    const auto& channel = package.matrixChannels[pc];
    const auto& masks = package.matrixEntryMasks[pc];
    if (channel.size() != masks.size()) {
      throw std::invalid_argument("Cuperflow ProductBeat golden 的 A beat/mask 长度不一致");
    }
    for (const std::size_t group : package.channelSliceGroups[pc]) {
      if (group >= package.sliceGroupCount ||
          group >= package.channelGroupDescriptorOffsets[pc].size()) {
        throw std::invalid_argument("Cuperflow ProductBeat golden 的 slice group 越界");
      }
      const std::uint32_t descriptorOffset = package.channelGroupDescriptorOffsets[pc][group];
      if (descriptorOffset == std::numeric_limits<std::uint32_t>::max() ||
          descriptorOffset > package.channelBatchDescriptors[pc].size() ||
          package.stats.batchCount >
              package.channelBatchDescriptors[pc].size() - descriptorOffset) {
        throw std::invalid_argument("Cuperflow ProductBeat golden 缺少 group BATCH_DESC");
      }
      const std::size_t columnBase = groupFirstColumn(package, group);
      for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
        const CuperflowBatchDescriptor& descriptor =
            package.channelBatchDescriptors[pc][descriptorOffset + batch];
        if (descriptor.batchId != batch || descriptor.aOffsetBeats > channel.size() ||
            descriptor.aBeats > channel.size() - descriptor.aOffsetBeats ||
            descriptor.batchId > std::numeric_limits<std::uint16_t>::max()) {
          throw std::invalid_argument("Cuperflow ProductBeat golden 的 BATCH_DESC A range 非法");
        }
        for (std::size_t offset = 0; offset < descriptor.aBeats; ++offset) {
          const std::size_t beatIndex = descriptor.aOffsetBeats + offset;
          const std::uint8_t mask = masks[beatIndex];
          if (mask == 0U) {
            throw std::invalid_argument("Cuperflow ProductBeat golden 遇到无有效 lane 的 A beat");
          }
          CuperflowProductBeatGolden beat;
          beat.pc = static_cast<std::uint16_t>(pc);
          beat.wave = static_cast<std::uint16_t>(group / package.config.hbmChannelCount);
          beat.batch = static_cast<std::uint16_t>(descriptor.batchId);
          beat.beatSeq = beatSeq++;
          beat.laneValid = mask;
          bool chunkModeSet = false;
          for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
            if ((mask & (std::uint8_t{1} << lane)) == 0U) {
              continue;
            }
            const DecodedCuperflowSlot slot = decodeSlot(channel[beatIndex][lane]);
            const std::size_t column = columnBase + slot.localColumn;
            if (column >= x.size() || slot.localRow >= package.config.rowBatchSize) {
              throw std::out_of_range("Cuperflow ProductBeat golden 的 slot 坐标超出 X/row batch");
            }
            if (!chunkModeSet) {
              beat.chunkMode = static_cast<std::uint8_t>(slot.chunkMode);
              chunkModeSet = true;
            } else if (beat.chunkMode != static_cast<std::uint8_t>(slot.chunkMode)) {
              throw std::invalid_argument("Cuperflow ProductBeat golden 的单 beat chunkMode 不一致");
            }
            beat.localRow[lane] = static_cast<std::uint16_t>(slot.localRow);
            beat.rowLast[lane] = slot.rowLast;
            beat.product[lane] = fp64Bits(static_cast<double>(slot.value) * x[column]);
          }
          result.push_back(beat);
        }
      }
    }
  }
  return result;
}

}  // namespace accelerator_sim::spmv::encoding::cuperflow
