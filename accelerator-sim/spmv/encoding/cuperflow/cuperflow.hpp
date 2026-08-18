#pragma once

#include "../../golden.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <utility>
#include <vector>

#ifndef CUPERFLOW_ENABLE_FLEX_X
#define CUPERFLOW_ENABLE_FLEX_X 1
#endif

namespace accelerator_sim::spmv::encoding::cuperflow {

constexpr std::size_t kLanesPerBeat = 8;
constexpr std::size_t kVectorLanesPerBeat = 8;
constexpr std::size_t kVectorStorageAlignmentElements = 1024;
constexpr std::size_t kVectorReplicaCount = 4;
constexpr std::size_t kVectorPartitionFactor = 8;
constexpr std::size_t kMaxXRangeElements = 8192;
constexpr bool kFlexibleXEncodingEnabled = CUPERFLOW_ENABLE_FLEX_X != 0;

/** Cuperflow A slot v5：`groupColumn[63:51] | tag[50:48] | localRow[47:32] | fp32[31:0]`。
  *
  * `localRow` 是当前 row batch 内的物理行标。work/product 中的 `batch` 与
  * `localRow` 共同恢复全局 physical row；HBM channel 只代表本轮独占的列 sliceGroup，
  * 不再承载行身份。预处理器把 `tag` 编码为每个 `(batch, HBM, lane)` 时间流内的
  * 3-bit 累加上下文：同一驻留行复用上下文，
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

/**
 * FP64 X 流中的地址 token。它是一个带固定 opcode 和 magic 的 quiet NaN，低 13 位保存
 * 当前 sliceGroup 内的 BRAM 地址。真实 X 输入必须是有限数，因此不会与该 token 混淆。
 *
 * `ADDR(a)` 的含义是：下一个普通 FP64 word 写入 local_X[a]；连续地址不需要 token。
 */
constexpr std::uint32_t kXAddressMarkerOpcode = 0b001;
constexpr std::uint64_t kXAddressMarkerMagic = 0x1a5a5;
constexpr std::uint64_t kXAddressMarkerAddressMask =
    (std::uint64_t{1} << kColumnBits) - 1U;
constexpr std::uint64_t kXAddressMarkerBase =
    (std::uint64_t{0x7ff} << 52U) |
    (std::uint64_t{1} << 51U) |
    (static_cast<std::uint64_t>(kXAddressMarkerOpcode) << 48U) |
    (kXAddressMarkerMagic << 13U);

std::uint64_t makeXAddressMarker(std::uint32_t address);
bool isXAddressMarker(std::uint64_t word);
std::uint32_t decodeXAddressMarker(std::uint64_t word);

/**
 * X 区里一张固定 1-beat 的 map。lane0 是与 ADDR 不同的 quiet NaN
 *（opcode=010, magic=0x2b6b6）；其余 lane 按小端 uint32 对存放控制字。
 *
 *   lane0  MAP_NAN，bit0=last
 *   lane1  xBeats[31:0] | xWords[63:32]
 *   lane2  aOffsetBeats[31:0] | aBeats[63:32]
 *   lane3  firstBatch[15:0] | sliceGroup[31:16] | xElements[47:32] | last[48]
 *   lane4-7  保留 0
 */
constexpr std::uint32_t kXMapMarkerOpcode = 0b010;
constexpr std::uint64_t kXMapMarkerMagic = 0x2b6b6;
constexpr std::uint64_t kXMapMarkerLastMask = 1U;
constexpr std::uint64_t kXMapMarkerBase =
    (std::uint64_t{0x7ff} << 52U) |
    (std::uint64_t{1} << 51U) |
    (static_cast<std::uint64_t>(kXMapMarkerOpcode) << 48U) |
    (kXMapMarkerMagic << 13U);

struct CuperflowMapBeat {
  std::uint32_t xBeats = 0;
  std::uint32_t xWords = 0;
  std::uint32_t aOffsetBeats = 0;
  std::uint32_t aBeats = 0;
  std::uint16_t firstBatch = 0;
  std::uint16_t sliceGroup = 0;
  std::uint16_t xElements = 0;
  bool last = false;
};

std::uint64_t makeXMapMarker(bool last);
bool isXMapMarker(std::uint64_t word);

struct CuperflowConfig {
  /** A 矩阵使用的独立 HBM channel 数；每路固定有 8 个 slot lane。 */
  std::size_t hbmChannelCount = 16;
  /** 一个 A column-slice 覆盖的连续列数；slice 内按 column-major 顺序组织。 */
  std::size_t sliceSize = 64;
  /** 一个 row batch 覆盖的连续行数。 */
  std::size_t rowBatchSize = 8192;
  /** X 独立传输批次包含的列 slice 数；它不再决定 A 的 row batch。 */
  std::size_t xSlicesPerBatch = 128;
  /** 一个 HBM 独占的 X/A sliceGroup 包含的 slice 数；0 表示按 slice 数量自动均衡。 */
  std::size_t sliceGroupSize = 0;
  /** Cuperflow 不插入 RAW 间隔；保留字段用于报告旧 ABI 的兼容显示。 */
  std::size_t reorderWindow = 0;
  /** 是否在每个 row batch 内按行 nnz 做 PE 负载均衡重排。 */
  bool rowReorder = true;
};

using CuperflowBeat = std::array<std::uint64_t, kLanesPerBeat>;
using CuperflowVectorBeat = std::array<std::uint64_t, kVectorLanesPerBeat>;
static_assert(sizeof(CuperflowBeat) == 64, "Cuperflow A beat 必须为 512 bit");
static_assert(sizeof(CuperflowVectorBeat) == 64, "Cuperflow X beat 必须为 512 bit");

CuperflowVectorBeat packMapBeat(const CuperflowMapBeat& map);
CuperflowMapBeat unpackMapBeat(const CuperflowVectorBeat& beat);

struct CuperflowGroupARange {
  std::size_t sliceGroup = 0;
  std::uint32_t aOffsetBeats = 0;
  std::uint32_t aBeats = 0;
};

struct CuperflowEncodingStats {
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

struct CuperflowPackage {
  /** 编码时采用的静态 HBM、row batch、column slice 和同一行调度参数。 */
  CuperflowConfig config;
  /** 原始 CSR 矩阵维度；由 PE-local 16-bit 行标表达。 */
  std::size_t rows = 0;
  std::size_t columns = 0;
  /** 原始 CSR 的非零元数量，等于所有实际矩阵 slot 的总数。 */
  std::uint64_t nonzeros = 0;
  /** 物理 row 到原始 CSR row 的映射；A 输出必须通过它恢复原始行顺序。 */
  std::vector<std::size_t> physicalToOriginalRows;

  /** 每个 row batch 中的 column-slice 数。 */
  std::size_t columnSliceCount = 0;
  /** 实际采用的 slice group 宽度和数量；最后一个 group 可以不足该宽度。 */
  std::size_t sliceGroupSize = 0;
  std::size_t sliceGroupCount = 0;
  /** slice group 到 HBM 的独占 A/X 映射；group g 归属 channel[g % hbmChannelCount]。 */
  std::vector<std::size_t> sliceGroupChannels;
  /** 每个 HBM 持有的 slice group 编号，按 X 装载顺序排列。 */
  std::vector<std::vector<std::size_t>> channelSliceGroups;
  /** A 遍历实际触及的列，按 sliceGroup 去重后供灵活 X 编码使用。 */
  std::vector<std::vector<std::uint32_t>> xUsedColumnsByGroup;
  // 软件/报告用的 (rowBatch, sliceGroup, lane) 范围，指向 group-major 的 A 流。
  // 同一 group 的全部 row batch 在 owner HBM 上连成一段；空 group 的范围为 [0,0)。
  std::vector<std::vector<std::uint32_t>> channelBatchPointers;
  std::vector<std::vector<std::array<std::pair<std::uint32_t, std::uint32_t>, kLanesPerBeat>>>
      channelLaneSliceGroupRanges;
  /** 每个 PC 按装载顺序排列的非空 group A 区间；硬件 map 只引用这里。 */
  std::vector<std::vector<CuperflowGroupARange>> channelGroupARanges;
  // lane 0 对应 512-bit beat 的 [63:0]，lane 7 对应 [511:448]。
  // 各 channel 只补齐自身 8 lanes，因此长度可以不同。
  std::vector<std::vector<CuperflowBeat>> matrixChannels;
  /** 与 matrixChannels 同形的 host/report 元数据；bit p 表示该 lane 是实际 CSR 元素。
    * 它不写入 HBM，RTL 也完全不读取它。
    */
  std::vector<std::vector<std::uint8_t>> matrixEntryMasks;
  CuperflowEncodingStats stats;
};

struct CuperflowVectorStats {
  std::size_t batchCount = 0;
  std::size_t payloadBeats = 0;
  std::size_t allocatedBeats = 0;
  std::size_t validElements = 0;
  std::size_t lanePaddingElements = 0;
  std::size_t allocationPaddingElements = 0;
  std::uint64_t packedBytes = 0;
  std::uint64_t allocatedBytes = 0;
  /** 独占 X range 数量及其最大元素长度。 */
  std::size_t rangeCount = 0;
  std::size_t maximumRangeElements = 0;
  /** per-HBM X 实际写入的 token 统计；连续模式下 token 就是普通 FP64 value。 */
  std::uint64_t encodedWordCount = 0;
  std::uint64_t encodedValueCount = 0;
  std::uint64_t markerCount = 0;
  std::uint64_t encodedPayloadBeats = 0;
  std::uint64_t encodedLanePaddingWords = 0;
};

struct CuperflowXRange {
  std::size_t sliceGroup = 0;
  std::size_t firstColumn = 0;
  std::size_t elementCount = 0;
  /** range 中实际写入的 token 数；marker 不计入 valueCount。 */
  std::size_t encodedWordCount = 0;
  std::size_t valueCount = 0;
  std::size_t markerCount = 0;
  /** 该 group 在 X 区的 map beat 下标；无 map 的回退路径为 UINT32_MAX。 */
  std::uint32_t mapBeat = std::numeric_limits<std::uint32_t>::max();
  /** X token 区间，不含开头的 map beat。 */
  std::uint32_t beatBegin = 0;
  std::uint32_t beatEnd = 0;
  std::uint32_t aOffsetBeats = 0;
  std::uint32_t aBeats = 0;
  bool last = false;
};

/** Cuperflow X 的 per-HBM 布局及其 Core 本地存储映射。
  *
  * `hbmBeats` 保留原列顺序的连续 FP64 规范副本；实际 HBM 流位于 `channelHbmBeats`。
  * 每个 HBM 只持有不重叠的 X range，range 装入对应 BRAM 后再复制到本地 4 份、8 路
  * cyclic partition 的 local_X 存储中。灵活模式下实际流还会占用普通 FP64 word 之间的
  * 地址 marker lane。
  */
struct CuperflowVectorPackage {
  /** 与 A package 相同的 slice 配置；X 的全局 batch 仍由 xSlicesPerBatch 决定。 */
  CuperflowConfig config;
  /** X 的全局列数。 */
  std::size_t columns = 0;
  /** 保留原始 FP64 输入，供 host golden 与报告使用。 */
  std::vector<double> sourceValues;
  // 累计 batch 边界，单位是 512-bit FP64 beat；不包含 HBM 分配尾部 padding。
  std::vector<std::uint32_t> batchPointers;
  // 包含 host 的 1024-element 对齐尾部，未被 kernel 读取的 beat 保持全零。
  std::vector<CuperflowVectorBeat> hbmBeats;
  /** 每个 A HBM 的独占 X payload；同一路的多个 range 按 slice group 顺序串接。 */
  std::vector<std::vector<CuperflowVectorBeat>> channelHbmBeats;
  /** 每个 A HBM 的 X range 边界；range 之间不共享列，单个 range 不超过 8192 元素。 */
  std::vector<std::vector<CuperflowXRange>> channelXRanges;
  /** 是否按 A 的实际列集合生成了带地址 marker 的 X stream。 */
  bool flexibleXEncoding = false;
  CuperflowVectorStats stats;
};

struct DecodedCuperflowSlot {
  /** 当前 slice group 内的列偏移；group 起始列由所属范围表确定。 */
  std::uint32_t localColumn = 0;
  /** 预处理器分配的累加上下文；当前乘法 RTL 只透明传递，不参与筛选。 */
  std::uint32_t tag = 0;
  /** 16-bit PE-local 物理行标；slot 所在 PE 可将其反解为 physical row。 */
  std::uint32_t localRow = 0;
  float value = 0.0F;
};

std::size_t columnsPerBatch(const CuperflowConfig& config);
std::size_t rowBatchCount(std::size_t rows, const CuperflowConfig& config);
std::size_t columnSliceCount(std::size_t columns, const CuperflowConfig& config);
std::size_t effectiveSliceGroupSize(const CuperflowConfig& config);
std::size_t effectiveSliceGroupSize(std::size_t columnSliceCount,
                                    const CuperflowConfig& config);
std::size_t sliceGroupCount(std::size_t columns, const CuperflowConfig& config);
std::size_t totalPeCount(const CuperflowConfig& config);
/** 以下 row/PE 映射保留给预处理调度；Cuperflow slot 行身份改由 batch-local row 表示。 */
std::size_t peForRow(std::size_t row, const CuperflowConfig& config);
std::size_t localRowForRow(std::size_t row, const CuperflowConfig& config);
std::size_t rowForPeLocal(std::size_t physicalPe, std::size_t localRow,
                          const CuperflowConfig& config);
/** 从 work/product 的 row batch 与 slot localRow 恢复 physical row。 */
std::size_t physicalRowForBatchLocal(std::size_t batch, std::size_t localRow,
                                     const CuperflowConfig& config);
DecodedCuperflowSlot decodeSlot(std::uint64_t slot);
CuperflowPackage encode(const CsrMatrix& matrix, const CuperflowConfig& config = {});
CuperflowVectorPackage encodeVector(const std::vector<double>& input,
                                const CuperflowConfig& config = {});
CuperflowVectorPackage encodeVector(const std::vector<double>& input,
                                    const CuperflowPackage& matrixPackage);

}  // namespace accelerator_sim::spmv::encoding::cuperflow
