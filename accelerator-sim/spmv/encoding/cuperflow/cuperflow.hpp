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

/** Cuperflow A slot v6：`groupColumn[63:51] | segmentId[50:48] | rowLast[47] |
  * chunkMode[46:45] | localRow[44:32] | fp32[31:0]`。
  *
  * `localRow` 是当前 row batch 内的物理行标。work/product 中的 `batch` 与
  * `localRow` 共同恢复全局 physical row；HBM channel 只代表本轮独占的列 sliceGroup，
  * 不再承载行身份。`segmentId` 选择当前 map 中至多八段连续 X 的一段；乘法通路据此
  * 计算 `prefix(segmentId) + localColumn - segmentStart`，从顺序装入 local-X 的 payload
  * 中取数。它不再是累加上下文，行身份只由 `batch + localRow` 表达。
  *
  * 编码器的物理空槽仍是全零 slot；A 的非零掩码跳过它的 local-X 读取和 FMUL 请求。
  */
constexpr std::uint32_t kColumnBits = 13;
constexpr std::uint32_t kTagBits = 3;
constexpr std::size_t kMaxXSegments = std::size_t{1} << kTagBits;
constexpr std::uint32_t kXSegmentCountBits = 14;
constexpr std::uint32_t kRowBits = 13;
constexpr std::uint32_t kCuperflowSlotVersion = 6;
constexpr std::uint32_t kCuperflowMapVersion = 4;
constexpr std::uint32_t kCuperflowBatchDescriptorVersion = 1;
/** V0 统计使用的候选 FADD latency；它只用于 FIFO/ROB 容量分析，不代表 RTL 时序。 */
constexpr std::uint32_t kAnalysisCandidateFaddLatency = 4;
constexpr std::uint64_t kZeroFillSlot = 0;

using CuperflowBeat = std::array<std::uint64_t, kLanesPerBeat>;
using CuperflowVectorBeat = std::array<std::uint64_t, kVectorLanesPerBeat>;
static_assert(sizeof(CuperflowBeat) == 64, "Cuperflow A beat 必须为 512 bit");
static_assert(sizeof(CuperflowVectorBeat) == 64, "Cuperflow X beat 必须为 512 bit");

/** 每个 A beat 内固定划分的 row partial 宽度。00/01/10 是 v6 的唯三合法编码。 */
enum class CuperflowChunkMode : std::uint8_t {
  Full8 = 0b00,
  Two4 = 0b01,
  Four2 = 0b10,
};

std::size_t slotsPerChunk(CuperflowChunkMode mode);

/** A beat 的物理排布。
  *
  * RowRoundRobin 是 Cuperflow 的正式布局：同一物理行的完整八项先构成一个 beat，
  * 然后按 batch-local row 0..rowBatchSize-1 轮转；下一轮才继续每行的后续八项。
  * 短行尾部按照 `tailPacking` 切为不跨行的子块；同宽子块按行号拼入一个物理
  * beat。默认 `pad3-1` 只使用 4、2 两种对齐粒度；`all-to-4` 对照策略会把全部
  * 短尾补到四项。V0 不生成 1-slot chunk。空行不发射 beat。
  *
  * LaneStriped 仅保留给旧 X-page 局部列重排实验；它把八条 lane 流按相同位置横拼，
  * 不适合作为后续行累加器的输入契约。
  */
enum class CuperflowAPacking {
  RowRoundRobin,
  LaneStriped,
};

/** RowRoundRobin 短行尾部的子块策略。
  *
  * Pad3To4And1To2 是正式默认值：3 项尾部补一项为 4，1 项尾部补一项为 2，L1
  * 只需处理 8/4/2 对齐粒度。Compact421 仅为旧吞吐实验保留，slot v6 的
  * RowRoundRobin 编码器必须拒绝它。PadAllTo4 把每个不足 4 项的尾部补到 4。
  */
enum class CuperflowTailPacking {
  Compact421,
  Pad3To4And1To2,
  PadAllTo4,
};

/**
 * 为后续多段 `SEG_NAN` 方案保留的 FP64 地址 token。它是一个带固定 opcode 和 magic 的
 * quiet NaN，低 13 位保存当前 sliceGroup 内的 BRAM 地址。map ABI v4 的正式 payload
 * 不使用它，编码器和 RTL 都要求 markerCount 为零。
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
 *   lane0  MAP_NAN，包含 version 和 bit0=last
 *   lane1  xBeats[31:0] | xWords[63:32]
 *   lane2  batchDescriptorCount[31:0] | reserved[63:32]
 *   lane3  sliceGroup[15:0] | xElements[31:16] | reserved[63:32]
 *   lane4  segment0[31:0] | segment1[63:32]
 *   lane5  segment2[31:0] | segment3[63:32]
 *   lane6  segment4[31:0] | segment5[63:32]
 *   lane7  segment6[31:0] | segment7[63:32]
 *
 * 一个 descriptor 是 `start[12:0] | count[26:13] | reserved[31:27]`。`count=0`
 * 表示未使用段；payload 始终按 segment0、segment1 ... 的顺序原样存放 FP64 value。
 */
constexpr std::uint32_t kXMapMarkerOpcode = 0b010;
constexpr std::uint64_t kXMapMarkerMagic = 0x2b6b6;
constexpr std::uint64_t kXMapMarkerLastMask = 1U;
constexpr std::uint32_t kXMapMarkerVersionShift = 1;
constexpr std::uint32_t kXMapMarkerVersionBits = 12;
constexpr std::uint64_t kXMapMarkerBase =
    (std::uint64_t{0x7ff} << 52U) |
    (std::uint64_t{1} << 51U) |
    (static_cast<std::uint64_t>(kXMapMarkerOpcode) << 48U) |
    (kXMapMarkerMagic << 13U);

struct CuperflowXSegment {
  /** 相对于当前 sliceGroup 起始列的 13-bit 列偏移。 */
  std::uint16_t start = 0;
  /** 连续段长度；1..8192 有效，0 表示未使用 descriptor。 */
  std::uint16_t count = 0;
};

inline bool operator==(const CuperflowXSegment& lhs, const CuperflowXSegment& rhs) {
  return lhs.start == rhs.start && lhs.count == rhs.count;
}

inline bool operator!=(const CuperflowXSegment& lhs, const CuperflowXSegment& rhs) {
  return !(lhs == rhs);
}

struct CuperflowMapBeat {
  std::uint32_t xBeats = 0;
  std::uint32_t xWords = 0;
  std::uint32_t batchDescriptorCount = 0;
  std::uint16_t sliceGroup = 0;
  /** 所有非空段长度之和，也就是顺序 payload 的 FP64 数量。 */
  std::uint16_t xElements = 0;
  std::array<CuperflowXSegment, kMaxXSegments> xSegments{};
  bool last = false;
};

std::uint64_t makeXMapMarker(bool last);
bool isXMapMarker(std::uint64_t word);

/** 一张固定 1-beat 的 BATCH_DESC，独立于 GROUP_MAP 出现。
  *
  * lane0  descriptor marker，包含 magic、version 和 `lastBatchInGroup`；
  * lane1  batchId[31:0] | aOffsetBeats[63:32]；
  * lane2  aBeats[31:0] | contributorOffsetWords[63:32]；
  * lane3  contributorWordCount[31:0] | activeRowCount[63:32]；
  * lane4..7 必须为零。
  */
constexpr std::uint32_t kBatchDescriptorMarkerOpcode = 0b011;
constexpr std::uint64_t kBatchDescriptorMarkerMagic = 0x35ca7;
constexpr std::uint64_t kBatchDescriptorMarkerLastMask = 1U;
constexpr std::uint32_t kBatchDescriptorMarkerVersionShift = 1;
constexpr std::uint32_t kBatchDescriptorMarkerVersionBits = 12;
constexpr std::uint64_t kBatchDescriptorMarkerBase =
    (std::uint64_t{0x7ff} << 52U) |
    (std::uint64_t{1} << 51U) |
    (static_cast<std::uint64_t>(kBatchDescriptorMarkerOpcode) << 48U) |
    (kBatchDescriptorMarkerMagic << 13U);

struct CuperflowBatchDescriptor {
  std::uint32_t batchId = 0;
  std::uint32_t aOffsetBeats = 0;
  std::uint32_t aBeats = 0;
  std::uint32_t contributorOffsetWords = 0;
  std::uint32_t contributorWordCount = 0;
  std::uint32_t activeRowCount = 0;
  bool lastBatchInGroup = false;
};

std::uint64_t makeBatchDescriptorMarker(bool lastBatchInGroup);
bool isBatchDescriptorMarker(std::uint64_t word);
void validateBatchDescriptor(const CuperflowBatchDescriptor& descriptor);
CuperflowVectorBeat packBatchDescriptor(const CuperflowBatchDescriptor& descriptor);
CuperflowBatchDescriptor unpackBatchDescriptor(const CuperflowVectorBeat& beat);

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
  /** A beat 排布；默认以同 row beat 支持后续行累加。 */
  CuperflowAPacking aPacking = CuperflowAPacking::RowRoundRobin;
  /** RowRoundRobin 的短行尾部策略；默认采用 L1 友好的 3->4、1->2 对齐。 */
  CuperflowTailPacking tailPacking = CuperflowTailPacking::Pad3To4And1To2;
};

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
  /** 编码到 FP32 后为 +0/-0 而被删除的 CSR 项。 */
  std::uint64_t droppedExplicitZeros = 0;
  std::uint64_t full8ChunkCount = 0;
  std::uint64_t two4ChunkCount = 0;
  std::uint64_t four2ChunkCount = 0;
  std::uint64_t rowPartial1BeatCount = 0;
  std::uint64_t rowPartial2BeatCount = 0;
  std::uint64_t rowPartial4BeatCount = 0;
  std::uint64_t batchDescriptorCount = 0;
  std::uint64_t emptyBatchCount = 0;
  /** 同一 `(PC,batch,row)` 相邻 RowPartial 的 A beat 距离统计。 */
  std::uint64_t chunkInterBeatDistanceCount = 0;
  std::uint64_t chunkInterBeatDistanceTotal = 0;
  std::uint64_t chunkInterBeatDistanceMinimum = 0;
  std::uint64_t chunkInterBeatDistanceMaximum = 0;
  std::uint64_t chunkInterBeatDistanceBelowFaddLatency = 0;
  std::uint32_t candidateFaddLatency = kAnalysisCandidateFaddLatency;
  /** `(wave,batch,row)` 16-PC contributor mask 的 popcount 直方图。 */
  std::array<std::uint64_t, kLanesPerBeat * 2U + 1U> contributorPopcountHistogram{};
  /** 用每个 PC 的真实 A beat 顺序回放得到的 completion ROB 峰值。 */
  std::uint64_t completionRobPeak = 0;
  /** 每个非空 A sliceGroup 恰好一次的 X payload 装载计划及其期望值。 */
  std::uint64_t xPayloadLoadCount = 0;
  std::uint64_t expectedXPayloadLoadCount = 0;
  std::uint64_t packedBytes = 0;

  double matrixSlotUtilization() const;
};

/** 单个 PC 的 V0 预处理负载。activeRows 是该 PC 在所有 group/batch 的 rowLast 总数。 */
struct CuperflowPcL1Stats {
  std::uint64_t aBeats = 0;
  std::uint64_t effectiveSlots = 0;
  std::uint64_t activeRows = 0;
  std::uint64_t emptyBatches = 0;
};

inline bool operator==(const CuperflowPcL1Stats& lhs, const CuperflowPcL1Stats& rhs) {
  return lhs.aBeats == rhs.aBeats && lhs.effectiveSlots == rhs.effectiveSlots &&
      lhs.activeRows == rhs.activeRows && lhs.emptyBatches == rhs.emptyBatches;
}

inline bool operator!=(const CuperflowPcL1Stats& lhs, const CuperflowPcL1Stats& rhs) {
  return !(lhs == rhs);
}

/** `(wave,batch)` 的横向规约前置条件统计。 */
struct CuperflowWaveBatchL1Stats {
  std::uint64_t activeRows = 0;
  std::uint64_t maxPcProgressGap = 0;
};

inline bool operator==(const CuperflowWaveBatchL1Stats& lhs,
                       const CuperflowWaveBatchL1Stats& rhs) {
  return lhs.activeRows == rhs.activeRows &&
      lhs.maxPcProgressGap == rhs.maxPcProgressGap;
}

inline bool operator!=(const CuperflowWaveBatchL1Stats& lhs,
                       const CuperflowWaveBatchL1Stats& rhs) {
  return !(lhs == rhs);
}

struct CuperflowPackage {
  /** 编码时采用的静态 HBM、row batch、column slice 和同一行调度参数。 */
  CuperflowConfig config;
  /** 原始 CSR 矩阵维度；由 batch + 13-bit localRow 表达物理行。 */
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
  /** 每个 wave 同时覆盖至多一个 sliceGroup/PC。 */
  std::size_t contributorWaveCount = 0;
  /** slice group 到 HBM 的独占 A/X 映射；group g 归属 channel[g % hbmChannelCount]。 */
  std::vector<std::size_t> sliceGroupChannels;
  /** 每个 HBM 持有的 slice group 编号，按 X 装载顺序排列。 */
  std::vector<std::vector<std::size_t>> channelSliceGroups;
  /** A 遍历实际触及的列，按 sliceGroup 去重后供灵活 X 编码使用。 */
  std::vector<std::vector<std::uint32_t>> xUsedColumnsByGroup;
  /** 每个 sliceGroup 的连续 X 段计划；A slot 的 segmentId 直接索引此表。 */
  std::vector<std::vector<CuperflowXSegment>> xSegmentsByGroup;
  // 软件/报告用的 (rowBatch, sliceGroup, lane) 范围，指向 group-major 的 A 流。
  // LaneStriped 时每个 lane 的有效 slot 连续；RowRoundRobin 时八个 lane 都登记本组
  // 的完整 beat 区间，必须再用 matrixEntryMasks 过滤短行尾部空 slot。
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
  /** 每个 channel/group 的 BATCH_DESC 起始下标；UINT32_MAX 表示该 channel 不拥有 group。 */
  std::vector<std::vector<std::uint32_t>> channelGroupDescriptorOffsets;
  /** 每个 channel 依 group、batch 顺序排列的 BATCH_DESC。 */
  std::vector<std::vector<CuperflowBatchDescriptor>> channelBatchDescriptors;
  /** 每个 channel 的 1-bit active-row bitmap，按 descriptor 的 offset/count 寻址。 */
  std::vector<std::vector<std::uint64_t>> channelContributorWords;
  /** `(batch * contributorWaveCount + wave)` 的 row-major 16-bit contributor mask。 */
  std::vector<std::vector<std::uint16_t>> contributorMasksByWaveBatch;
  /** 每个 PC 的 A/slot/row 负载，长度固定为 hbmChannelCount。 */
  std::vector<CuperflowPcL1Stats> pcL1Stats;
  /** `(batch * contributorWaveCount + wave)` 的 active-row 与 PC progress 分布。 */
  std::vector<CuperflowWaveBatchL1Stats> waveBatchL1Stats;
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
  /** 所有 map 的非空连续 X 段总数，单个 map 最多 8 段。 */
  std::size_t segmentCount = 0;
  std::size_t maximumRangeElements = 0;
  /** per-HBM X 实际写入的 token 统计；连续模式下 token 就是普通 FP64 value。 */
  std::uint64_t encodedWordCount = 0;
  std::uint64_t encodedValueCount = 0;
  /** A 实际访问的列数；连续 span 中为保持顺序搬运而包含的 hole 不计入此项。 */
  std::uint64_t demandedElements = 0;
  std::uint64_t markerCount = 0;
  std::uint64_t encodedPayloadBeats = 0;
  std::uint64_t encodedLanePaddingWords = 0;
};

struct CuperflowXRange {
  std::size_t sliceGroup = 0;
  /** map 中的局部连续段；payload 按此数组顺序串接。 */
  std::vector<CuperflowXSegment> segments;
  /** 所有段的长度之和，即 local-X 中顺序搬运的 FP64 数。 */
  std::size_t elementCount = 0;
  /** A 真正触及的列数；小于 `elementCount` 只会出现在 >8 段的连续 span 回退。 */
  std::size_t usedElementCount = 0;
  /** range 中实际写入的 token 数；第一刀中它始终等于 `elementCount`。 */
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
  std::uint32_t batchDescriptorCount = 0;
  /** descriptor/bitmap 在 channelHbmBeats 中的闭开 beat 区间。 */
  std::uint32_t descriptorBeatBegin = 0;
  std::uint32_t descriptorBeatEnd = 0;
  bool last = false;
};

/** Cuperflow X 的 per-HBM 布局及其 Core 本地存储映射。
  *
  * `hbmBeats` 保留原列顺序的连续 FP64 规范副本；实际 HBM 流位于 `channelHbmBeats`。
  * 每个 HBM 只持有不重叠的 X range，range 装入对应 BRAM 后再复制到本地 4 份、8 路
 * cyclic partition 的 local_X 存储中。灵活模式将至多八段连续列按段顺序紧凑搬运；payload
 * 始终是连续 FP64 value，不在普通 value 之间插入地址 marker。
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
  /** 是否按 A 的实际列集合生成至多八段紧凑 X payload。 */
  bool flexibleXEncoding = false;
  CuperflowVectorStats stats;
};

struct DecodedCuperflowSlot {
  /** 当前 slice group 内的列偏移；group 起始列由所属范围表确定。 */
  std::uint32_t localColumn = 0;
  /** 当前 map 的 X 段号；A 侧用它恢复顺序 payload 内的 local-X 地址。 */
  std::uint32_t segmentId = 0;
  /** 当前 chunk 是否是该 PC 对本行的最终 partial。 */
  bool rowLast = false;
  CuperflowChunkMode chunkMode = CuperflowChunkMode::Full8;
  /** 13-bit batch-local 物理行标。 */
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
/** 验证 slot、rowLast、active bitmap 和 descriptor 的完整 V0 package 合同。 */
void validatePackage(const CuperflowPackage& package);
/** 验证 GROUP_MAP/BATCH_DESC 没有让同一 sliceGroup 的 X payload 重复装载。 */
void validateXPayloadLoads(const CuperflowPackage& matrixPackage,
                           const CuperflowVectorPackage& vectorPackage);
CuperflowPackage encode(const CsrMatrix& matrix, const CuperflowConfig& config = {});
CuperflowVectorPackage encodeVector(const std::vector<double>& input,
                                const CuperflowConfig& config = {});
CuperflowVectorPackage encodeVector(const std::vector<double>& input,
                                    const CuperflowPackage& matrixPackage);

}  // namespace accelerator_sim::spmv::encoding::cuperflow
