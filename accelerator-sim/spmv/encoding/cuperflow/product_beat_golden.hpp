#pragma once

#include "cuperflow.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace accelerator_sim::spmv::encoding::cuperflow {

/** FMUL 后、L1 前的独立 golden 事务。
  *
  * 这是 `SpmvCuperflowProductBeat` 的 C++ 对等物，只服务 standalone L1/L2 的
  * Verilator/DPI 测试。正式 FPGA 路径必须消费真实 RTL 的 ProductBeat，不能实例化
  * 或链接这个 source。
  */
struct CuperflowProductBeatGolden {
  std::uint16_t pc = 0;
  std::uint16_t wave = 0;
  std::uint16_t batch = 0;
  std::uint32_t beatSeq = 0;
  std::uint8_t laneValid = 0;
  std::array<std::uint16_t, kLanesPerBeat> localRow{};
  std::array<bool, kLanesPerBeat> rowLast{};
  std::uint8_t chunkMode = 0;
  std::array<std::uint64_t, kLanesPerBeat> product{};
};

/** 由已验证的 V0 package 和输入 X 生成逐 PC、原始 A 顺序的 ProductBeat golden。
  *
  * `beatSeq` 的递增规则与正式 input-mul lane 相同：仅对实际 A beat 递增，空 batch
  * 不占用序号。无效 padding lane 的乘积为 FP64 +0.0，控制字段也是零。
  */
std::vector<CuperflowProductBeatGolden> makeProductBeatGolden(
    const CuperflowPackage& package, const std::vector<double>& x);

}  // namespace accelerator_sim::spmv::encoding::cuperflow
