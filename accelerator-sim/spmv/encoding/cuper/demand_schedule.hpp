#pragma once

#include "cuper.hpp"

#include <cstddef>
#include <cstdint>
#include <ostream>
#include <string_view>
#include <vector>

namespace accelerator_sim::spmv::encoding::cuper {

/** 离线 X page 调度器使用的输入带宽模型。
  *
  * 当前输入顶层以两路 512-bit X HBM 供数，片上 local_X 按 8 个 FP64/拍写入
  * （每 bank 一颗 1R+1W URAM）。该模型只用于评估 X page 顺序与 A 的保序早启动
  * 机会，不会修改 Cuper A package 或 RTL ABI。
  */
struct CuperDemandScheduleConfig {
  std::size_t pageElements = 0;
  std::size_t xElementsPerCycle = 8;
};

struct CuperDemandChannelTiming {
  bool active = false;
  std::uint64_t firstIssueCycle = 0;
  std::uint64_t lastIssueCycle = 0;
  std::uint64_t issuedBeforeXComplete = 0;
  std::uint64_t stallCycles = 0;
};

struct CuperDemandChannelPlan {
  std::size_t channel = 0;
  std::size_t aBeats = 0;
  CuperDemandChannelTiming baseline;
  CuperDemandChannelTiming planned;
};

struct CuperDemandBatchTiming {
  std::uint64_t firstIssueCycle = 0;
  std::uint64_t lastIssueCycle = 0;
  std::uint64_t issuedBeforeXComplete = 0;
  std::size_t channelsStartedBeforeXComplete = 0;
};

struct CuperDemandBatchPlan {
  std::size_t batch = 0;
  std::size_t columns = 0;
  std::size_t pageCount = 0;
  std::uint64_t xLoadCycles = 0;
  /** page id 的实际装载顺序。此顺序需要未来的 X payload 重排或 scatter 支持。 */
  std::vector<std::size_t> pageOrder;
  CuperDemandBatchTiming baseline;
  CuperDemandBatchTiming planned;
  std::vector<CuperDemandChannelPlan> channels;
};

struct CuperDemandSchedule {
  CuperDemandScheduleConfig config;
  std::vector<CuperDemandBatchPlan> batches;
};

/**
 * 根据当前保序的 A stream 生成候选 X page 顺序。
 *
 * 贪心策略优先选择能让最多 channel 连续 A 前缀变为 ready 的 page；若当前没有 page
 * 能立即解锁 beat，则选择当前阻塞头部与近端 beat 引用最多的 page。这样不会假设可
 * 任意交换 A beat，因而不破坏现有 Cuper RAW 排程的顺序约束。
 */
CuperDemandSchedule planXPageSchedule(
    const CuperPackage& package,
    const CuperDemandScheduleConfig& config = {});

/**
 * 将每个 batch 的 A slot localColumn 改写为 schedule 对应的物理 X page 位置。
 *
 * 返回 package 保留原有 HBM channel、beat、RAW/tag 和 Ctrl pointer 结构；只有
 * `localColumn` 被替换。因此 host 可连续装载重排后的 X page，RTL 不需要 X scatter
 * 写地址。调用方仍应使用原 package 对照 CSR golden。
 */
CuperPackage remapLocalColumnsForXPageSchedule(
    const CuperPackage& package, const CuperDemandSchedule& schedule);

/** 输出供 host 分析和后续 RTL 调度器消费的稳定 JSON，不包含任何重排后的 payload。 */
void writeDemandScheduleJson(std::ostream& output, const CuperDemandSchedule& schedule,
                             std::string_view datasetName, std::string_view sourcePath);

}  // namespace accelerator_sim::spmv::encoding::cuper
