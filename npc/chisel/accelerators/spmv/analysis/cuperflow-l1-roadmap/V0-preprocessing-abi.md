# V0：预处理 ABI、统计与边界样本

状态：已完成（编码、Scala decode、乘法 RTL 接收、ProductBeat 切面）。本文保留为 ABI
合同，不再作为“尚未实现”的任务书。Track L / Track M 都从这里列出的字段出发，不要
回改 v6/v4 含义来换时序或换累加结构。

V0 冻结 L1 后续版本要消费的数据格式，不实现 FP64 加法树、partial RAM 或横向规约。
它的核心结果不是“编码程序能运行”，而是软件 package、HTML、host 和未来 RTL 对同一组
字段有唯一解释。

## 已交付（相对原文任务书）

- A slot v6、`cuperflow-map-multisegment-v4`、`cuperflow-batch-desc-v1`。
- 显式 `+0/-0` canonicalize；NaN/Inf 保留。全零 64-bit slot 仍是物理 padding。
- 11 个具名 V0 fixture：C++ `fixtures.hpp`、Scala `SpmvCuperflowL1Fixtures`、DPI
  selector 共用同一组名字。
- 乘法 RTL 接受 L1 v0 package，不再在 elaboration 拒绝。每 PC 已走
  `GROUP_MAP -> X -> BATCH_DESC -> contributor -> A`。
- `SpmvCuperflowProductBeat` 与 `ProductBeatJoin` 已在乘法侧落地；C++
  `makeProductBeatGolden` 按真实 A 顺序回放。这是原文 V2 的 join 职责，提前冻在乘法
  轨道，以便 L1 并行。
- `hbmChannelCount` / `hbmPcCount` 为 1..16；AXI 仍为 512-bit。
- profile：`PROTOCOL_ABI=spmv-cuperflow-l1-v0`。

不在 V0 完成范围内、也不阻塞 Track L 开工的：1-PC 250 MHz WNS、L1 RTL、把乘法 IP 迁到
`npc.ip.arithmetic`。

## 目标与非目标

目标：

- A slot 从 v5 升到 v6，正式编码 `rowLast`、`chunkMode` 和 13-bit `localRow`。
- 把每 group 一次的 X 描述与每 Batch 一次的 A 描述分开。
- 生成空贡献语义完整的 contributor metadata。
- 给 V1/V2 提供可重复的合成输入、分布统计和 ABI round-trip 测试。

本版不做：

- 不实例化任何 L1 RTL 或 FP64 ADD IP。
- 不根据软件估算宣称 L1 周期或 FPGA 吞吐。
- 不压缩 contributor metadata；先拿到未压缩基线。
- 不改变每个 PC 独占列区间、X payload 顺序紧凑装载和最多八段 X 的合同。

## 前置依赖

实施前应核对：

- `accelerator-sim/spmv/encoding/cuperflow/cuperflow.hpp`
- `accelerator-sim/spmv/encoding/cuperflow/cuperflow.cpp`
- `accelerator-sim/spmv/encoding/cuperflow/report.cpp`
- `accelerator-sim/spmv/encoding/cuperflow/fixtures.hpp`
- `accelerator-sim/spmv/encoding/encoding_test.cpp`
- `accelerator-sim/spmv/host.cpp`
- `npc/chisel/accelerators/spmv/scala/input-mul/common/SpmvCuperDecode.scala`
- `npc/chisel/accelerators/spmv/scala/input-mul/common/SpmvCuperflowL1Fixtures.scala`
- `npc/chisel/configs/accelerators/spmv/core/SpmvCuperflowSimulationProfile.scala`

## Slot v6 合同

```text
[63:51] groupColumn  13 bit
[50:48] segmentId     3 bit
[47]    rowLast       1 bit
[46:45] chunkMode     2 bit
[44:32] localRow     13 bit
[31:0]  matrixValue  FP32
```

字段语义：

- `groupColumn` 是当前 sliceGroup 内的列号，不是压缩后的 local-X 地址。
- `segmentId` 选择 GROUP_MAP 的八个 X segment descriptor 之一；RTL 通过
  `prefix + groupColumn - start` 得到 local-X 地址。
- `localRow` 是当前 Batch 内的物理行号，必须小于实际 Batch 行数和 8192。
- `rowLast` 对 chunk 生效。同一 chunk 的所有有效 lane 取值必须相同。
- `chunkMode=00/01/10` 分别把 beat 解释为 `8`、`4+4`、`2+2+2+2`。
- `chunkMode=11` 不得由编码器生成，解码时作为 ABI 错误。

默认 `Pad3To4And1To2` 的逻辑尾部分解为：

| 剩余 nnz | 发射 chunk | 物理 padding |
| ---: | --- | ---: |
| 7 | 4 + 4 | 1 |
| 6 | 4 + 2 | 0 |
| 5 | 4 + 2 | 1 |
| 4 | 4 | 0 |
| 3 | 4 | 1 |
| 2 | 2 | 0 |
| 1 | 2 | 1 |

同宽 chunk 按 `localRow` 递增顺序拼入一个 beat，不能跨 chunk 偷用空 lane。8-slot 主块仍按
row round-robin 发射：先处理各行第一个 8，再处理各行第二个 8。

### Padding 与显式零

当前 RTL 用全零 64-bit slot 识别物理 padding。V0 必须固定以下规则之一并写入 ABI 测试；
建议采用第一项：

1. 编码前 canonicalize CSR，删除显式 FP32 `+0/-0`，统计 `droppedExplicitZeros`；数学结果
   不变，也不会让合法元素与全零 padding 冲突。
2. 若必须逐项保留显式零，则需另加 lane-valid metadata，不能只依赖 slot 是否全零。

不能同时声称“全零 slot 是 padding”和“所有显式零 slot 可无损恢复”。NaN/Inf 不得被
canonicalize，必须原样进入 golden 的特殊值策略。

## GROUP_MAP 与 BATCH_DESC

建议把每个 PC 的低地址控制/X 流组织为：

```text
[GROUP_MAP][X payload][BATCH_DESC 0][contributor metadata 0]
                         ...
           [BATCH_DESC n][contributor metadata n][next GROUP_MAP]
```

A payload 仍位于该 PC 的高地址 A 区，descriptor 通过 beat offset 引用，不复制 A。

`GROUP_MAP` 保留 map v3 的 X 字段：`xBeats`、`xWords`、`sliceGroup`、八个
`{segmentStart,segmentCount}` 和 `lastGroup`。原来的单个 `firstBatch/aOffset/aBeats` 迁出；
map 新增 `batchDescriptorCount`，确保硬件知道何时进入下一 group。

`BATCH_DESC` 的逻辑字段至少包括：

```text
batchId:                 UInt32
aOffsetBeats:            UInt32
aBeats:                  UInt32
contributorOffsetWords:  UInt32
contributorWordCount:    UInt32
activeRowCount:          UInt32
lastBatchInGroup:        Bool
```

V0 应在 `cuperflow.hpp` 中给出逐 lane 位布局、独立 marker opcode、magic、version 和 reserved
位要求，并让 pack/unpack 共享常量。所有 reserved 位必须编码为 0，RTL 发现非零即报错。

### Contributor metadata

逻辑上每个 `(wave,batch,row)` 有 16-bit mask，bit `p` 表示 PC `p` 会提交一个最终
partial。未压缩布局为 `row 0, row 1, ...` 的小端 `uint16_t` 数组；8192 行正好 16 KiB。

为了维持 16 个 PC 的独立读取，V0 package 同时生成每 PC 的 1-bit active-row bitmap，
并在该 PC 的 `BATCH_DESC` 中引用它。16 份 1-bit bitmap 的总信息量仍是 16 KiB，host 和
测试可无损转置回 16-bit row-major mask。V3 只有在 16 个 PC 对应 Batch 的 descriptor 都
ready 后，才认为该 epoch 的 contributor 信息完整；这只是 metadata readiness，不阻止快
PC 继续计算并写入 completion ROB。

需要定义的边界语义：

- mask 为 0 的 row 不产生横向结果；若该 row 在合法矩阵范围内，则最终 Y 贡献为 0。
- mask 某 bit 为 1 时，该 PC 必须最终恰好产生一次 `rowLast` partial。
- 空 Batch 可有 `aBeats=0`，但 descriptor 仍推进 Batch/epoch。
- 最后一个 Batch 的 metadata 只覆盖有效行数，其余 bitmap 位必须为 0。
- 同 group 的所有 PC 必须对 `batchId` 和有效行数达成一致，A offset/length 可不同。

## 数据结构与文件落点

建议在现有文件中新增：

- `CuperflowPackedSlotV6` 的 pack/decode 常量和 `DecodedCuperflowSlot` 新字段；
- `CuperflowBatchDescriptor`、marker pack/unpack 和校验函数；
- package 中按 PC/group/batch 索引的 A range、active-row bitmap 和 descriptor 表；
- `CuperflowEncodingStats` 中的 L1 相关统计；
- report 中仅保存 Batch 0/HBM 0/Slot 0 的值级样本，其余页面保留聚合统计，避免再次生成
  GB 级 HTML。

若结构开始挤压 `cuperflow.cpp`，可新增：

```text
accelerator-sim/spmv/encoding/cuperflow/l1_protocol.hpp
accelerator-sim/spmv/encoding/cuperflow/l1_protocol.cpp
accelerator-sim/spmv/encoding/cuperflow/l1_stats.hpp
accelerator-sim/spmv/encoding/cuperflow/l1_stats.cpp
```

不要复制一套独立 encoder；V0 必须扩展正式 Cuperflow package。

## 必须输出的统计

- 8/4/2 chunk 数、物理 beat 数、padding 数和 slot 利用率。
- 每 beat 产生 1/2/4 个 `RowPartial` 的占比。
- 每 PC 的 A beat、有效 slot、active row 和空 Batch 分布。
- 同一 `(PC,batch,row)` 相邻 chunk 的 beat 距离；特别统计小于候选 FADD latency 的次数。
- contributor mask 的 popcount 0..16 直方图。
- 每 `(wave,batch)` active row 数和 16 个 PC 的最大进度差。
- 用真实 PC 顺序回放得到的 completion ROB 最大占用；该值只是容量估算，不是 RTL 周期。
- X payload 次数，证明 descriptor 数增长没有导致同 group 重载 X。

## ABI 与 Config 变化

- `SPMV_CUPERFLOW_MAP_ABI` 从当前 `cuperflow-map-multisegment-v3` 升级到明确的新版本。
- `PROTOCOL_ABI` 和 `ACCELERATOR_HOST_ABI` 同步升级；旧 host 遇到新 package 必须拒绝。
- package 文件头写入 slot version、map version、descriptor version、PC 数、Batch 行数和
  endian。
- `rowBatchSize` 从只存在于 C++ encoder 的配置迁入 `SpmvCuperflowConfig`/profile，软件和
  RTL 必须校验相等。

当前输入 RTL 已经接受该 ABI，并输出 ProductBeat。不要为了“兼容旧仿真”把 profile 退回
v3 字符串。旧 host 遇到新 package 必须拒绝。

## 断言与负向测试

- slot pack 后再 unpack，所有非 padding 数学元素保持 row/column/value。
- 同 chunk 的 mode、row、rowLast 不一致时解码失败。
- `segmentId` 越界、column 不在 segment span 内、`localRow>=8192` 时失败。
- map/descriptor opcode 混淆、reserved 非零、offset+length 越界时失败。
- Batch ID 重复、倒退、跨 group 缺失或 `lastBatchInGroup` 提前时失败。
- active bitmap 为 0 但存在 `rowLast`，或 bit 为 1 却没有 `rowLast` 时 package validator
  失败。
- descriptor 增加后每 group 的 X payload 仍恰好出现一次。

## 测试矩阵

至少新增以下小型 CSR fixture：

| 样本 | 主要覆盖 |
| --- | --- |
| `full8` | 一个及多个完整 8-slot row |
| `tail44` | 两个 4-row chunk 共 beat |
| `tail2222` | 四个 2-row chunk 共 beat |
| `pad3_1` | 3 补 4、1 补 2 |
| `empty_pc_row` | mask 中部分 PC 为 0 |
| `empty_batch` | `aBeats=0` 的 descriptor 推进 |
| `last_short_batch` | 最后不足 8192 行 |
| `same_local_row_next_batch` | epoch 防别名输入 |
| `multi_wave_same_y` | V4 跨 wave 地址前置条件 |
| `explicit_zero` | CSR canonicalization 与计数 |
| `eight_x_segments` | 最大 segmentId 和 prefix |

真实数据至少保留 `n512` 和完整 thermal2。

## 构建与验证

编码阶段使用正式目标：

```bash
make -C accelerator-sim/spmv encoding-test
make -C accelerator-sim/spmv cuperflow-encoding-html-test mainargs=n512
make -C accelerator-sim/spmv cuperflow-encoding-html-test mainargs=thermal2
```

若修改 Config/profile，再运行配置生成检查；V0 不需要伪造 L1 Verilator 结果。命令的 stdout
需记录 package ABI、矩阵维度、nnz、padding、descriptor 数和 X load 数。

## 退出条件

- 所有小型 fixture 和真实矩阵通过 package validator 与 pack/unpack round-trip。
- 所有非零数学项可唯一恢复；显式零按冻结的 canonicalization 规则统计。
- `rowLast/chunkMode` 足以唯一划分每个 beat 的 RowPartial。
- contributor metadata 与从原始 CSR 独立计算的 16-bit mask 完全相同。
- Batch descriptor 不增加 X payload 次数，也不越过 A/X region。
- 新旧 ABI 不会静默互认。
- 输出 V1/V2 可直接引用的 C++ 字段表、Scala 位域常量和 fixture package。

## 交给下一版

V1 直接引用本文的 slot/map/descriptor、fixture 名称和 ProductBeat 字段。V1 不得修改乘法
A decode 来“再带一次” `rowLast/chunkMode`：它们已经在 ProductBeat 里。Track M 调时序时
也不得改这些字段含义。

V2 的 8/4/2 树消费 ProductBeat，不再在 L1 目录重做 FMUL join。
