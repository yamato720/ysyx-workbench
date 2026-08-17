#pragma once

#include "../../golden.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace accelerator_sim::spmv::encoding::cuper {

constexpr std::size_t kLanesPerBeat = 8;
constexpr std::size_t kVectorLanesPerBeat = 16;
constexpr std::size_t kVectorStorageAlignmentElements = 1024;
constexpr std::size_t kVectorReplicaCount = 4;
constexpr std::size_t kVectorPartitionFactor = 8;

/** Cuper A slot v4：`localColumn[63:51] | tag[50:48] | localRow[47:32] | fp32[31:0]`。
  *
  * `localRow` 是 Cuper PE 内的行标；结合 slot 所在 HBM channel/lane 可以反解全局 CSR
  * 行标。预处理器把 `tag`
  * 编码为每个 `(batch, PE)` 时间流内的 3-bit 累加上下文：同一驻留行复用上下文，
  * tag 不是行号，而是每个物理 PE/lane 上 8 个累加上下文的编号。row 是真正身份，
  * tag 类似硬件线程槽位，目前 3 bit 凑 0~7 来用
   到达的 (row, tag)    L1 当前状态    动作
  ━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   context 无效         空             写入 row，以 product 初始化
  ───────────────────  ─────────────  ────────────────────────────────
   context.row 相同     命中           sum += product
  ───────────────────  ─────────────  ────────────────────────────────
   context.row 不同     上下文切换     先送出旧 (row,sum)，再装入新行
  *
  * 8 项占满后按 LRU 换出。当前乘法 RTL 不解释该语义，只随 FMUL 响应透明传递。
  * 编码器的物理空槽仍是全零 slot，和所有普通 slot 一样读取 X、进入 FMUL。
  */
constexpr std::uint32_t kColumnBits = 13;
constexpr std::uint32_t kTagBits = 3;
constexpr std::size_t kAccumulationContextCount = std::size_t{1} << kTagBits;
constexpr std::uint32_t kRowBits = 16;
constexpr std::uint64_t kZeroFillSlot = 0;

struct CuperConfig {
  /** A 矩阵使用的独立 HBM channel 数；每路固定有 8 个 slot lane。 */
  std::size_t hbmChannelCount = 16;
  /** 一个列 slice 覆盖的连续 X 元素数。 */
  std::size_t sliceSize = 64;
  /** 每个 Cuper batch 包含的列 slice 数；默认 128 * 64 = 8192 列。 */
  std::size_t columnSlicesPerBatch = 128;
  /** 同一 Cuper ping/pong 累加目标的最小发射间隔。 */
  std::size_t reorderWindow = 7;
};

using CuperBeat = std::array<std::uint64_t, kLanesPerBeat>;
using CuperVectorBeat = std::array<std::uint32_t, kVectorLanesPerBeat>;

struct CuperEncodingStats {
  std::size_t batchCount = 0;
  std::size_t minimumMatrixBeatsPerChannel = 0;
  std::size_t maximumMatrixBeatsPerChannel = 0;
  std::uint64_t totalMatrixBeats = 0;
  /** 来自 CSR 的实际矩阵元素数量。 */
  std::uint64_t matrixSlots = 0;
  /** 编码布局为固定 beat 补入的全零 slot 数量；它不改变硬件控制语义。 */
  std::uint64_t zeroFillSlots = 0;
  std::uint64_t packedBytes = 0;

  double matrixSlotUtilization() const;
};

struct CuperPackage {
  /** 编码时采用的静态 HBM、列窗口和同一行调度参数。 */
  CuperConfig config;
  /** 原始 CSR 矩阵维度；由 PE-local 16-bit 行标表达。 */
  std::size_t rows = 0;
  std::size_t columns = 0;
  /** 原始 CSR 的非零元数量，等于所有实际矩阵 slot 的总数。 */
  std::uint64_t nonzeros = 0;

  // 每个 HBM channel 独立的累计 batch 边界，单位是该 channel 的 512-bit beat。
  std::vector<std::vector<std::uint32_t>> channelBatchPointers;
  // lane 0 对应 512-bit beat 的 [63:0]，lane 7 对应 [511:448]。
  // 各 channel 只补齐自身 8 lanes，因此长度可以不同。
  std::vector<std::vector<CuperBeat>> matrixChannels;
  /** 与 matrixChannels 同形的 host/report 元数据；bit p 表示该 lane 是实际 CSR 元素。
    * 它不写入 HBM，RTL 也完全不读取它。
    */
  std::vector<std::vector<std::uint8_t>> matrixEntryMasks;
  CuperEncodingStats stats;
};

struct CuperVectorStats {
  std::size_t batchCount = 0;
  std::size_t payloadBeats = 0;
  std::size_t allocatedBeats = 0;
  std::size_t validElements = 0;
  std::size_t lanePaddingElements = 0;
  std::size_t allocationPaddingElements = 0;
  std::uint64_t packedBytes = 0;
  std::uint64_t allocatedBytes = 0;
};

/** Cuper X 的 HBM 布局及其 Core 本地存储映射。
  *
  * HBM 中的元素保持原列顺序，FP64 输入先转换成 FP32，再按 float_v16 打包。
  * Core 按 8192 列 batch 接收数据，并把每个 batch 复制到 4 份、8 路 cyclic partition
  * 的 local_X 存储中。
  */
struct CuperVectorPackage {
  /** 与 A package 相同的列窗口配置。 */
  CuperConfig config;
  /** X 的全局列数。 */
  std::size_t columns = 0;
  /** 保留原始 FP64 输入，供 host golden 与报告使用。 */
  std::vector<double> sourceValues;
  // 累计 batch 边界，单位是 512-bit float_v16 beat；不包含 HBM 分配尾部 padding。
  std::vector<std::uint32_t> batchPointers;
  // 包含 host 的 1024-element 对齐尾部，未被 kernel 读取的 beat 保持全零。
  std::vector<CuperVectorBeat> hbmBeats;
  CuperVectorStats stats;
};

struct DecodedCuperSlot {
  std::uint32_t localColumn = 0;
  /** 预处理器分配的累加上下文；当前乘法 RTL 只透明传递，不参与筛选。 */
  std::uint32_t tag = 0;
  /** 16-bit PE-local 行标；slot 所在 PE 可将其反解为全局 CSR 行标。 */
  std::uint32_t localRow = 0;
  float value = 0.0F;
};

std::size_t columnsPerBatch(const CuperConfig& config);
std::size_t totalPeCount(const CuperConfig& config);
std::size_t peForRow(std::size_t row, const CuperConfig& config);
std::size_t localRowForRow(std::size_t row, const CuperConfig& config);
std::size_t rowForPeLocal(std::size_t physicalPe, std::size_t localRow,
                          const CuperConfig& config);
DecodedCuperSlot decodeSlot(std::uint64_t slot);
CuperPackage encode(const CsrMatrix& matrix, const CuperConfig& config = {});
CuperVectorPackage encodeVector(const std::vector<double>& input,
                                const CuperConfig& config = {});

}  // namespace accelerator_sim::spmv::encoding::cuper
