#include "demand_schedule.hpp"

#include <algorithm>
#include <iomanip>
#include <limits>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace accelerator_sim::spmv::encoding::cuperflow {

namespace {

using PageDependencies = std::vector<std::size_t>;
using ChannelDependencies = std::vector<PageDependencies>;

std::size_t divideRoundedUp(std::size_t value, std::size_t divisor) {
  return value / divisor + static_cast<std::size_t>(value % divisor != 0U);
}

void writeJsonString(std::ostream& output, std::string_view value) {
  output << '"';
  for (const char rawCharacter : value) {
    const unsigned char character = static_cast<unsigned char>(rawCharacter);
    switch (character) {
      case '"': output << "\\\""; break;
      case '\\': output << "\\\\"; break;
      case '\b': output << "\\b"; break;
      case '\f': output << "\\f"; break;
      case '\n': output << "\\n"; break;
      case '\r': output << "\\r"; break;
      case '\t': output << "\\t"; break;
      case '<': output << "\\u003c"; break;
      case '>': output << "\\u003e"; break;
      case '&': output << "\\u0026"; break;
      default:
        if (character < 0x20U) {
          output << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                 << static_cast<unsigned int>(character) << std::dec << std::setfill(' ');
        } else {
          output << character;
        }
        break;
    }
  }
  output << '"';
}

bool isReady(const PageDependencies& dependencies, const std::vector<bool>& loaded) {
  return std::all_of(dependencies.begin(), dependencies.end(),
                     [&loaded](std::size_t page) { return loaded[page]; });
}

std::vector<std::size_t> pageArrivalCycles(const std::vector<std::size_t>& pageOrder,
                                           const std::vector<std::size_t>& pageCycles,
                                           std::uint64_t* totalCycles) {
  std::vector<std::size_t> arrivals(pageOrder.size(), 0);
  std::size_t cycle = 0;
  for (std::size_t page : pageOrder) {
    if (page >= pageCycles.size() || pageCycles[page] >
        std::numeric_limits<std::size_t>::max() - cycle) {
      throw std::overflow_error("Cuperflow X page 调度周期溢出");
    }
    cycle += pageCycles[page];
    arrivals[page] = cycle;
  }
  *totalCycles = cycle;
  return arrivals;
}

CuperflowDemandChannelTiming simulateChannel(const ChannelDependencies& beats,
                                          const std::vector<std::size_t>& pageArrivals,
                                          std::uint64_t xLoadCycles) {
  CuperflowDemandChannelTiming timing;
  std::uint64_t nextIssueCycle = 0;
  for (const PageDependencies& dependencies : beats) {
    std::uint64_t readyCycle = 0;
    for (std::size_t page : dependencies) {
      readyCycle = std::max(readyCycle,
                            static_cast<std::uint64_t>(pageArrivals[page]));
    }
    const std::uint64_t issueCycle = std::max(nextIssueCycle, readyCycle);
    if (!timing.active) {
      timing.active = true;
      timing.firstIssueCycle = issueCycle;
    }
    if (issueCycle > nextIssueCycle) {
      timing.stallCycles += issueCycle - nextIssueCycle;
    }
    timing.lastIssueCycle = issueCycle;
    timing.issuedBeforeXComplete += issueCycle < xLoadCycles ? 1U : 0U;
    if (issueCycle == std::numeric_limits<std::uint64_t>::max()) {
      throw std::overflow_error("Cuperflow A 发射周期溢出");
    }
    nextIssueCycle = issueCycle + 1U;
  }
  return timing;
}

void populateBatchSummary(CuperflowDemandBatchPlan* plan) {
  auto summarize = [plan](bool planned) {
    CuperflowDemandBatchTiming timing;
    bool first = true;
    for (const CuperflowDemandChannelPlan& channel : plan->channels) {
      const CuperflowDemandChannelTiming& source = planned ? channel.planned : channel.baseline;
      if (!source.active) {
        continue;
      }
      timing.firstIssueCycle = first ? source.firstIssueCycle :
          std::min(timing.firstIssueCycle, source.firstIssueCycle);
      timing.lastIssueCycle = std::max(timing.lastIssueCycle, source.lastIssueCycle);
      timing.issuedBeforeXComplete += source.issuedBeforeXComplete;
      timing.channelsStartedBeforeXComplete +=
          source.firstIssueCycle < plan->xLoadCycles ? 1U : 0U;
      first = false;
    }
    return timing;
  };
  plan->baseline = summarize(false);
  plan->planned = summarize(true);
}

void writeTimingJson(std::ostream& output, const CuperflowDemandChannelTiming& timing) {
  output << "{\"active\":" << (timing.active ? "true" : "false")
         << ",\"firstIssueCycle\":" << timing.firstIssueCycle
         << ",\"lastIssueCycle\":" << timing.lastIssueCycle
         << ",\"issuedBeforeXComplete\":" << timing.issuedBeforeXComplete
         << ",\"stallCycles\":" << timing.stallCycles << '}';
}

void writeBatchTimingJson(std::ostream& output, const CuperflowDemandBatchTiming& timing) {
  output << "{\"firstIssueCycle\":" << timing.firstIssueCycle
         << ",\"lastIssueCycle\":" << timing.lastIssueCycle
         << ",\"issuedBeforeXComplete\":" << timing.issuedBeforeXComplete
         << ",\"channelsStartedBeforeXComplete\":"
         << timing.channelsStartedBeforeXComplete << '}';
}

}  // namespace

CuperflowDemandSchedule planXPageSchedule(const CuperflowPackage& package,
                                       const CuperflowDemandScheduleConfig& requestedConfig) {
  if (package.config.sliceSize == 0 || package.stats.batchCount == 0 ||
      package.matrixChannels.size() != package.config.hbmChannelCount ||
      package.channelBatchPointers.size() != package.config.hbmChannelCount ||
      package.channelLaneSliceGroupRanges.size() != package.config.hbmChannelCount ||
      package.columnSliceCount == 0) {
    throw std::invalid_argument("Cuperflow X page 调度收到不完整的 matrix package");
  }
  CuperflowDemandSchedule schedule;
  schedule.config = requestedConfig;
  schedule.sliceGroupSize = package.sliceGroupSize;
  schedule.sliceGroupCount = package.sliceGroupCount;
  schedule.sliceGroupChannels = package.sliceGroupChannels;
  schedule.channelSliceGroups = package.channelSliceGroups;
  if (schedule.config.pageElements == 0) {
    schedule.config.pageElements = package.config.sliceSize;
  }
  if (schedule.config.pageElements == 0 ||
      schedule.config.xElementsPerCycle == 0) {
    throw std::invalid_argument("Cuperflow X page 调度的 page 或输入带宽必须为正数");
  }
  if (schedule.config.pageElements != package.config.sliceSize) {
    throw std::invalid_argument("Cuperflow X page 调度要求 pageElements 等于 A column-slice 宽度");
  }

  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    const std::size_t pageCount = package.columnSliceCount;
    std::vector<std::size_t> pageCycles(pageCount, 0);
    for (std::size_t page = 0; page < pageCount; ++page) {
      const std::size_t firstPageColumn = page * schedule.config.pageElements;
      const std::size_t pageColumns = std::min(schedule.config.pageElements,
          package.columns - firstPageColumn);
      pageCycles[page] = divideRoundedUp(pageColumns, schedule.config.xElementsPerCycle);
    }

    std::vector<ChannelDependencies> dependencies(package.config.hbmChannelCount);
    for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
      const auto& laneSliceGroupRanges = package.channelLaneSliceGroupRanges[channel];
      const std::size_t batchBegin = package.channelBatchPointers[channel][batch];
      const std::size_t batchEnd = package.channelBatchPointers[channel][batch + 1U];
      if (laneSliceGroupRanges.size() != package.stats.batchCount * package.sliceGroupCount ||
          package.matrixEntryMasks[channel].size() != package.matrixChannels[channel].size() ||
          batchBegin > batchEnd || batchEnd > package.matrixChannels[channel].size()) {
        throw std::invalid_argument("Cuperflow X page 调度发现非法 slice group range");
      }
      dependencies[channel].resize(batchEnd - batchBegin);
      for (std::size_t group = 0; group < package.sliceGroupCount; ++group) {
        const std::size_t groupSegment = batch * package.sliceGroupCount + group;
        const std::size_t firstSlice = group * package.sliceGroupSize;
        const std::size_t groupSliceCount = std::min(
            package.sliceGroupSize, package.columnSliceCount - firstSlice);
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          const auto range = laneSliceGroupRanges[groupSegment][lane];
          if (range.first > range.second || range.second > package.matrixChannels[channel].size() ||
              range.first < batchBegin || range.second > batchEnd) {
            throw std::invalid_argument("Cuperflow X page 调度发现越过 batch 的 slice group range");
          }
          const std::size_t begin = range.first;
          const std::size_t end = range.second;
          for (std::size_t beat = begin; beat < end; ++beat) {
            if ((package.matrixEntryMasks[channel][beat] & (1U << lane)) == 0U) {
              throw std::logic_error("Cuperflow X page 调度发现 slice group 内有空 slot");
            }
            const std::uint32_t localColumn = decodeSlot(
                package.matrixChannels[channel][beat][lane]).localColumn;
            if (localColumn >= groupSliceCount * package.config.sliceSize) {
              throw std::invalid_argument("Cuperflow X page 调度发现越界的 group column");
            }
            const std::size_t slice = firstSlice + localColumn / package.config.sliceSize;
            if (std::find(dependencies[channel][beat - batchBegin].begin(),
                          dependencies[channel][beat - batchBegin].end(), slice) ==
                    dependencies[channel][beat - batchBegin].end()) {
              dependencies[channel][beat - batchBegin].push_back(slice);
            }
          }
        }
      }
    }

    std::vector<std::size_t> baselineOrder(pageCount);
    for (std::size_t page = 0; page < pageCount; ++page) {
      baselineOrder[page] = page;
    }
    std::uint64_t xLoadCycles = 0;
    const std::vector<std::size_t> baselineArrivals =
        pageArrivalCycles(baselineOrder, pageCycles, &xLoadCycles);

    std::vector<bool> loaded(pageCount, false);
    std::vector<std::size_t> cursors(package.config.hbmChannelCount, 0);
    std::vector<std::size_t> pageOrder;
    pageOrder.reserve(pageCount);

    // 先完成一个最短的首 beat 依赖集合，保证至少一路 A 的启动不晚于原列序。
    std::size_t seedChannel = dependencies.size();
    std::size_t seedCycles = 0;
    std::size_t seedReleased = 0;
    for (std::size_t channel = 0; channel < dependencies.size(); ++channel) {
      if (dependencies[channel].empty()) {
        continue;
      }
      const PageDependencies& firstBeat = dependencies[channel].front();
      std::size_t candidateCycles = 0;
      for (std::size_t page : firstBeat) {
        if (pageCycles[page] > std::numeric_limits<std::size_t>::max() - candidateCycles) {
          throw std::overflow_error("Cuperflow X 首发 page 调度周期溢出");
        }
        candidateCycles += pageCycles[page];
      }
      std::vector<bool> candidateLoaded(pageCount, false);
      for (std::size_t page : firstBeat) {
        candidateLoaded[page] = true;
      }
      std::size_t candidateReleased = 0;
      for (const ChannelDependencies& channelDependencies : dependencies) {
        std::size_t position = 0;
        while (position < channelDependencies.size() &&
               isReady(channelDependencies[position], candidateLoaded)) {
          ++position;
        }
        candidateReleased += position;
      }
      if (seedChannel == dependencies.size() || candidateCycles < seedCycles ||
          (candidateCycles == seedCycles && candidateReleased > seedReleased)) {
        seedChannel = channel;
        seedCycles = candidateCycles;
        seedReleased = candidateReleased;
      }
    }
    if (seedChannel == dependencies.size()) {
      throw std::logic_error("Cuperflow X page 调度未找到首个 A beat");
    }
    for (std::size_t page : dependencies[seedChannel].front()) {
      loaded[page] = true;
      pageOrder.push_back(page);
    }
    for (std::size_t channel = 0; channel < dependencies.size(); ++channel) {
      while (cursors[channel] < dependencies[channel].size() &&
             isReady(dependencies[channel][cursors[channel]], loaded)) {
        ++cursors[channel];
      }
    }

    while (pageOrder.size() < pageCount) {
      std::size_t selected = pageCount;
      std::size_t bestReleased = 0;
      std::size_t bestFallback = 0;
      for (std::size_t candidate = 0; candidate < pageCount; ++candidate) {
        if (loaded[candidate]) {
          continue;
        }
        std::vector<bool> candidateLoaded = loaded;
        candidateLoaded[candidate] = true;
        std::size_t released = 0;
        std::size_t fallback = 0;
        for (std::size_t channel = 0; channel < dependencies.size(); ++channel) {
          std::size_t position = cursors[channel];
          while (position < dependencies[channel].size() &&
                 isReady(dependencies[channel][position], candidateLoaded)) {
            ++position;
          }
          released += position - cursors[channel];
          const std::size_t lookahead = std::min(dependencies[channel].size(),
              cursors[channel] + 32U);
          for (std::size_t index = cursors[channel]; index < lookahead; ++index) {
            if (std::find(dependencies[channel][index].begin(),
                          dependencies[channel][index].end(), candidate) !=
                dependencies[channel][index].end()) {
              fallback += 32U - (index - cursors[channel]);
            }
          }
        }
        if (selected == pageCount || released > bestReleased ||
            (released == bestReleased && fallback > bestFallback) ||
            (released == bestReleased && fallback == bestFallback && candidate < selected)) {
          selected = candidate;
          bestReleased = released;
          bestFallback = fallback;
        }
      }
      if (selected == pageCount) {
        throw std::logic_error("Cuperflow X page 调度未能选择下一个 page");
      }
      loaded[selected] = true;
      pageOrder.push_back(selected);
      for (std::size_t channel = 0; channel < dependencies.size(); ++channel) {
        while (cursors[channel] < dependencies[channel].size() &&
               isReady(dependencies[channel][cursors[channel]], loaded)) {
          ++cursors[channel];
        }
      }
    }

    std::uint64_t plannedXLoadCycles = 0;
    const std::vector<std::size_t> plannedArrivals =
        pageArrivalCycles(pageOrder, pageCycles, &plannedXLoadCycles);
    if (plannedXLoadCycles != xLoadCycles) {
      throw std::logic_error("Cuperflow X page 重排意外改变了 X 总装载周期");
    }

    CuperflowDemandBatchPlan plan;
    plan.batch = batch;
    plan.columns = package.columns;
    plan.pageCount = pageCount;
    plan.xLoadCycles = xLoadCycles;
    plan.pageOrder = std::move(pageOrder);
    plan.channels.reserve(package.config.hbmChannelCount);
    for (std::size_t channel = 0; channel < dependencies.size(); ++channel) {
      CuperflowDemandChannelPlan channelPlan;
      channelPlan.channel = channel;
      channelPlan.aBeats = dependencies[channel].size();
      channelPlan.baseline = simulateChannel(dependencies[channel], baselineArrivals,
                                             xLoadCycles);
      channelPlan.planned = simulateChannel(dependencies[channel], plannedArrivals,
                                            xLoadCycles);
      plan.channels.push_back(channelPlan);
    }
    populateBatchSummary(&plan);
    schedule.batches.push_back(std::move(plan));
  }
  return schedule;
}

CuperflowPackage remapLocalColumnsForXPageSchedule(
    const CuperflowPackage& package, const CuperflowDemandSchedule& schedule) {
  if (schedule.config.pageElements == 0 ||
      schedule.batches.size() != package.stats.batchCount ||
      package.matrixChannels.size() != package.config.hbmChannelCount ||
      package.channelBatchPointers.size() != package.config.hbmChannelCount ||
      package.channelLaneSliceGroupRanges.size() != package.config.hbmChannelCount ||
      package.columnSliceCount == 0) {
    throw std::invalid_argument("Cuperflow X page 重排收到不完整的 package 或 schedule");
  }

  CuperflowPackage remapped = package;
  if (schedule.config.pageElements != package.config.sliceSize) {
    throw std::invalid_argument("Cuperflow X page 重排要求 pageElements 等于 A column-slice 宽度");
  }
  constexpr std::uint64_t columnFieldMask =
      ((std::uint64_t{1} << kColumnBits) - 1U) << 51U;
  std::vector<std::vector<CuperflowBeat>> remappedChannels(package.config.hbmChannelCount);
  std::vector<std::vector<std::uint8_t>> remappedMasks(package.config.hbmChannelCount);
  std::vector<std::vector<std::uint32_t>> remappedBatchPointers(
      package.config.hbmChannelCount, std::vector<std::uint32_t>(package.stats.batchCount + 1U));
  std::vector<std::vector<std::array<std::pair<std::uint32_t, std::uint32_t>, kLanesPerBeat>>>
      remappedLaneSliceGroupRanges(
      package.config.hbmChannelCount,
      std::vector<std::array<std::pair<std::uint32_t, std::uint32_t>, kLanesPerBeat>>(
          package.stats.batchCount * package.sliceGroupCount));
  for (std::size_t batch = 0; batch < package.stats.batchCount; ++batch) {
    const CuperflowDemandBatchPlan& plan = schedule.batches[batch];
    if (plan.batch != batch || plan.columns != package.columns ||
        plan.pageCount != package.columnSliceCount ||
        plan.pageOrder.size() != package.columnSliceCount) {
      throw std::invalid_argument("Cuperflow X page schedule 的 batch 描述与 package 不一致");
    }

    std::vector<std::size_t> physicalPages(package.columnSliceCount, package.columnSliceCount);
    for (std::size_t physicalPage = 0; physicalPage < plan.pageOrder.size(); ++physicalPage) {
      const std::size_t logicalPage = plan.pageOrder[physicalPage];
      if (logicalPage >= package.columnSliceCount ||
          physicalPages[logicalPage] != package.columnSliceCount) {
        throw std::invalid_argument("Cuperflow X page schedule 不是一个 page 排列");
      }
      physicalPages[logicalPage] = physicalPage;
    }

    for (std::size_t channel = 0; channel < package.config.hbmChannelCount; ++channel) {
      std::vector<CuperflowBeat>& outputBeats = remappedChannels[channel];
      std::vector<std::uint8_t>& outputMasks = remappedMasks[channel];
      remappedBatchPointers[channel][batch] = static_cast<std::uint32_t>(outputBeats.size());
      const std::size_t outputBatchBegin = outputBeats.size();
      std::vector<std::vector<std::uint64_t>> laneWords(kLanesPerBeat);
      std::array<std::size_t, kLanesPerBeat> laneOffsets{};
      std::vector<std::array<std::vector<std::uint64_t>, kLanesPerBeat>> sourceSliceWords(
          package.columnSliceCount);
      for (std::size_t group = 0; group < package.sliceGroupCount; ++group) {
        const std::size_t groupSegment = batch * package.sliceGroupCount + group;
        const std::size_t firstSlice = group * package.sliceGroupSize;
        const std::size_t groupSliceCount = std::min(
            package.sliceGroupSize, package.columnSliceCount - firstSlice);
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          const auto range = package.channelLaneSliceGroupRanges[channel][groupSegment][lane];
          if (range.first > range.second || range.second > package.matrixChannels[channel].size() ||
              (range.second - range.first) >
                  package.channelBatchPointers[channel][batch + 1U] -
                  package.channelBatchPointers[channel][batch]) {
            throw std::invalid_argument("Cuperflow X page 重排发现非法 slice group range");
          }
          for (std::size_t beat = range.first; beat < range.second; ++beat) {
            if ((package.matrixEntryMasks[channel][beat] & (1U << lane)) == 0U) {
              throw std::logic_error("Cuperflow X page 重排发现 slice group 内有空 slot");
            }
            const std::uint64_t sourceSlot = package.matrixChannels[channel][beat][lane];
            const std::size_t localColumn = decodeSlot(sourceSlot).localColumn;
            if (localColumn >= groupSliceCount * package.config.sliceSize) {
              throw std::invalid_argument("Cuperflow X page 重排发现越界的 group column");
            }
            const std::size_t sourceSlice = firstSlice + localColumn / package.config.sliceSize;
            sourceSliceWords[sourceSlice][lane].push_back(sourceSlot);
          }
        }
      }

      for (std::size_t physicalSlice = 0; physicalSlice < package.columnSliceCount;
           ++physicalSlice) {
        const std::size_t logicalSlice = plan.pageOrder[physicalSlice];
        const std::size_t physicalGroup = physicalSlice / package.sliceGroupSize;
        const std::size_t physicalSliceInGroup = physicalSlice % package.sliceGroupSize;
        const std::size_t groupSegment = batch * package.sliceGroupCount + physicalGroup;
        const bool firstSliceInGroup = physicalSliceInGroup == 0;
        const bool lastSliceInGroup = physicalSlice + 1U == package.columnSliceCount ||
            physicalSliceInGroup + 1U == package.sliceGroupSize;
        for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
          if (firstSliceInGroup) {
            if (laneOffsets[lane] > std::numeric_limits<std::uint32_t>::max() -
                    outputBatchBegin) {
              throw std::overflow_error("Cuperflow X page 重排的 lane pointer 溢出");
            }
            remappedLaneSliceGroupRanges[channel][groupSegment][lane].first =
                static_cast<std::uint32_t>(outputBatchBegin + laneOffsets[lane]);
          }
          for (const std::uint64_t sourceSlot : sourceSliceWords[logicalSlice][lane]) {
            const std::size_t sourceLocalColumn =
                decodeSlot(sourceSlot).localColumn % package.config.sliceSize;
            const std::size_t remappedColumn = physicalSliceInGroup * package.config.sliceSize +
                sourceLocalColumn;
            if (remappedColumn >= (std::size_t{1} << kColumnBits)) {
              throw std::overflow_error("Cuperflow X page 重排后的 group column 超过 slot 位域");
            }
            laneWords[lane].push_back((sourceSlot & ~columnFieldMask) |
                (static_cast<std::uint64_t>(remappedColumn) << 51U));
            ++laneOffsets[lane];
          }
          if (lastSliceInGroup) {
            if (laneOffsets[lane] > std::numeric_limits<std::uint32_t>::max() -
                    outputBatchBegin) {
              throw std::overflow_error("Cuperflow X page 重排的 lane pointer 溢出");
            }
            remappedLaneSliceGroupRanges[channel][groupSegment][lane].second =
                static_cast<std::uint32_t>(outputBatchBegin + laneOffsets[lane]);
          }
        }
      }
      const std::size_t batchBeats = *std::max_element(laneOffsets.begin(), laneOffsets.end());
      outputBeats.insert(outputBeats.end(), batchBeats, CuperflowBeat{});
      outputMasks.insert(outputMasks.end(), batchBeats, 0U);
      for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
        for (std::size_t position = 0; position < laneWords[lane].size(); ++position) {
          outputBeats[outputBatchBegin + position][lane] = laneWords[lane][position];
          outputMasks[outputBatchBegin + position] |=
              static_cast<std::uint8_t>(1U << lane);
        }
      }
      remappedBatchPointers[channel][batch + 1U] =
          static_cast<std::uint32_t>(outputBeats.size());
    }
  }
  remapped.matrixChannels = std::move(remappedChannels);
  remapped.matrixEntryMasks = std::move(remappedMasks);
  remapped.channelBatchPointers = std::move(remappedBatchPointers);
  remapped.channelLaneSliceGroupRanges = std::move(remappedLaneSliceGroupRanges);
  return remapped;
}

void writeDemandScheduleJson(std::ostream& output, const CuperflowDemandSchedule& schedule,
                             std::string_view datasetName, std::string_view sourcePath) {
  output << "{\"format\":\"cuperflow-x-page-demand-v1\",\"dataset\":";
  writeJsonString(output, datasetName);
  output << ",\"source\":";
  writeJsonString(output, sourcePath);
  output << ",\"pageElements\":" << schedule.config.pageElements
         << ",\"xElementsPerCycle\":" << schedule.config.xElementsPerCycle
         << ",\"sliceGroupSize\":" << schedule.sliceGroupSize
         << ",\"sliceGroupCount\":" << schedule.sliceGroupCount
         << ",\"sliceGroupChannels\":[";
  for (std::size_t group = 0; group < schedule.sliceGroupChannels.size(); ++group) {
    output << (group == 0 ? "" : ",") << schedule.sliceGroupChannels[group];
  }
  output << "],\"channelSliceGroups\":[";
  for (std::size_t channel = 0; channel < schedule.channelSliceGroups.size(); ++channel) {
    output << (channel == 0 ? "" : ",") << '[';
    const auto& groups = schedule.channelSliceGroups[channel];
    for (std::size_t index = 0; index < groups.size(); ++index) {
      output << (index == 0 ? "" : ",") << groups[index];
    }
    output << ']';
  }
  output << "],\"batches\":[";
  for (std::size_t index = 0; index < schedule.batches.size(); ++index) {
    const CuperflowDemandBatchPlan& batch = schedule.batches[index];
    output << (index == 0 ? "" : ",") << "{\"batch\":" << batch.batch
           << ",\"columns\":" << batch.columns
           << ",\"pageCount\":" << batch.pageCount
           << ",\"xLoadCycles\":" << batch.xLoadCycles
           << ",\"baseline\":";
    writeBatchTimingJson(output, batch.baseline);
    output << ",\"planned\":";
    writeBatchTimingJson(output, batch.planned);
    output << ",\"pageOrder\":[";
    for (std::size_t page = 0; page < batch.pageOrder.size(); ++page) {
      output << (page == 0 ? "" : ",") << batch.pageOrder[page];
    }
    output << "],\"channels\":[";
    for (std::size_t channel = 0; channel < batch.channels.size(); ++channel) {
      const CuperflowDemandChannelPlan& plan = batch.channels[channel];
      output << (channel == 0 ? "" : ",") << "{\"channel\":" << plan.channel
             << ",\"aBeats\":" << plan.aBeats << ",\"baseline\":";
      writeTimingJson(output, plan.baseline);
      output << ",\"planned\":";
      writeTimingJson(output, plan.planned);
      output << '}';
    }
    output << "]}";
  }
  output << "]}\n";
}

}  // namespace accelerator_sim::spmv::encoding::cuperflow
