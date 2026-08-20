# V2：8/4/2 可分裂树与 RowPartial FIFO

状态：未开始。轨道：L。乘法侧的 A-beat join 已经完成；本版只在 ProductBeat 之后把
`chunkMode` 拆成 1/2/4 个 `RowPartial`。

V2 把 V0 的正式 `pad3-1` 输入接到 V1 的 L1。八路 FMUL 已经在
`SpmvCuperflowProductBeatJoin` 里按原始 A beat 收齐，L1 看到的是原子 ProductBeat，不再
面对散落的 `SpmvProduct`。

## 目标与非目标

目标：

- 同一棵结构化加法树按 `chunkMode` 输出 1、2 或 4 个 `RowPartial`。
- 使用小型 Decoupled FIFO 串行进入 V1 的单路 partial RAM RMW。
- 用 V0 fixture 和 ProductBeat golden 在 standalone L1 上跑通 `full8` / `tail44` /
  `tail2222` / `pad3_1`。
- 在不改乘法 RTL 的前提下，用 golden 覆盖 n512 与 thermal2 的逐 row local-partial。

本版不做：

- 不在 L1 目录重做 FMUL response join，也不改 `SpmvMulEngine` / `InputTop`。
- 不实现 2/4-bank L1 或四个并行 RMW 口；先测量单路瓶颈。
- 不实现 16-PC completion 横向规约和最终 Y。
- 不恢复 1-slot 默认编码；正式路径只接收 8/4/2。
- 不要求乘法 250 MHz 已关闭。

## 前置依赖

- V0 已冻结 slot v6、descriptor/package ABI、fixture 和 `makeProductBeatGolden`。
- V1 已冻结统一 FP64 **add**、Full8 树、partial RAM 和 RAW 行为。
- 乘法侧已冻结 ProductBeat：`beatSeq`、`laneValid`、`localRow[8]`、`rowLast[8]`、
  `chunkMode`、`product[8]`。V2 不得从 response 到达顺序猜 beat。

重点文件：

- `npc/chisel/accelerators/spmv/scala/l1/cuperflow/`（V1 新目录）
- `npc/chisel/accelerators/spmv/scala/input-mul/cuperflow/SpmvCuperflowProductBeat.scala`
  （只读合同）
- `accelerator-sim/spmv/encoding/cuperflow/product_beat_golden.cpp`

## 已完成的乘法侧 Join（本版只消费）

`SpmvCuperflowProductBeatJoin` 已经：

- 在 A beat 被 FMUL 接受的同拍锁存 sideband；
- 按 `laneValid` 等待对应 response；
- 空 lane 补 FP64 `+0.0`；
- 反压传到 A reader，而不是拆散 beat。

V2 若发现 ProductBeat 缺字段，只能走 ABI bump 并通知 Track M，不能在 L1 里“再 join
一次”。对接真实乘法（Track I）时，把 `InputTop.product` 接到同一棵树即可；golden 路径
与真实路径必须接受完全相同的 Bundle。

## 8/4/2 可分裂加法树

树的节点可复用 V1 的 8-way 结构，但输出 tap 由 mode 决定：

```text
chunkMode=00: sum(0..7)                                      -> 1 partial
chunkMode=01: sum(0..3), sum(4..7)                           -> 2 partial
chunkMode=10: sum(0..1), sum(2..3), sum(4..5), sum(6..7)     -> 4 partial
```

不得先把八项全加完再尝试拆 row。每个 tap 携带对应 chunk 的 `localRow` 和 `rowLast`。结构上
可让 level0 永远工作，4-way 模式再走 level1，8-way 模式再走 level2；未被当前 mode 使用的
节点不提交事务。

同一物理 beat 的 RowPartial 输出顺序固定为 lane 低到高，例如 4+4 先 `[0:3]` 后 `[4:7]`。
这个顺序直接影响同 row RAW 和 golden，必须写进 ABI。加法括号同样固定：
`((p0+p1)+(p2+p3))`，不能因 provider 或优化版本变化而重排。

## RowPartial FIFO 与背压

一个输入 beat 最多瞬时生成四个 RowPartial，而 V2 L1 每周期最多接受一个。因此在 tree 与
L1 之间放 `Queue[RowPartial]`：

- 入队侧必须能原子接收一个 beat 的 1/2/4 个输出，或使用明确的 4-entry staging register
  逐项排出。
- staging 未腾空时不能接受下一个 tree completion；反压继续传到 join、FMUL response、
  context table，最终传到 A reader。
- 不能只拉低一部分 FMUL lane 的 ready 后继续接受新 A beat，否则 beat context 会交错失配。
- FIFO 的 `flow` 建议为 false，避免重新形成长组合旁路；`pipe` 是否开启由时序报告决定。

推荐第一版结构：tree 完成时把最多四项原子锁存进 `pendingPartials[4] + count + index`，每拍
最多向普通单写口 Queue 发一项。只有 pending 为空才接受下一个 tree result。这样逻辑简单，
代价会被 stall 计数准确暴露。

## L1 接入行为

V1 的 L1 接口从 Full8 专用输入收敛为通用：

```text
Decoupled[RowPartial] {
  pc, wave, batch, localRow, rowLast, value
}
```

一个 row 可能收到多个 8/4/2 partial。L1 对每个 token 做相同 RMW，只有带 `rowLast=true`
的 token 把 `newPartial` 旁路到 completion。若同一 beat 的多个 chunk 恰好属于同 row，固定
输出顺序和 V1 RAW forwarding 必须保证它们串行累加，不能覆盖。

Batch 变化前，属于旧 Batch 的 tree、FIFO 和 L1 token 必须带显式 epoch；不能仅靠乘法
lane 的 `firstBatch` 电平。ProductBeat 已携带 `batch`，L1 以该字段为准。

## 建议模块与文件落点

```text
npc/chisel/accelerators/spmv/scala/l1/cuperflow/
  SpmvCuperflowL1Types.scala
  SpmvCuperflowChunkTree.scala
  SpmvCuperflowRowPartialBuffer.scala
  SpmvCuperflowL1.scala
```

不要新增 `SpmvCuperflowProductJoin.scala`。`SpmvMulEngine` 只负责 A/X decode 和乘法，不应
内嵌 L1 RAM。Standalone V2 顶层是 `ProductBeat -> tree -> FIFO -> L1`。接到乘法时由
Track I 把 `InputTop.product` 接到同一模块；那时才把 FPGA/host 的 `product.ready := 1`
改成真实 L1 反压。checksum 继续只做乘法旁路。

## Config 与 ABI

建议新增并冻结：

```text
rowPartialFifoDepth
chunkModes = 8/4/2
slotAbi = v6
l1InputAbi = row-partial-v1
```

`mulContextDepth` / `mulJoinDepth` 已由乘法侧 `ProductBeatJoin` 的 FIFO 深度承担，V2 不要
再复制一套。新增正式仿真 Config，例如 `SpmvCuperflowL1SimulationConfig`（仍建议 1 PC
standalone）。它与 V1 Full8 Config 并存。L1 host ABI 相对乘法 `spmv-cuperflow-rtl-v4`
必须可区分，不能复用 v3。

## RTL 断言

- 输入必须是完整 ProductBeat；`chunkMode` 与 `localRow` 分组一致。
- padding 对应的 `laneValid=0` 且 product 为 `+0.0`，树仍按固定括号加。
- 一个输入 beat 恰好产生 mode 规定数量的 RowPartial，顺序固定为 lane 低到高。
- pending/FIFO 满时 payload 保持稳定，ProductBeat `ready` 拉低。
- 每个 `rowLast` token 恰好产生一个 local completion。
- Batch 完成必须等待该 Batch 的 tree/FIFO/L1 context 排空。

## 测试矩阵

- V0 的 `full8`、`tail44`、`tail2222`、`pad3_1`。
- 连续每拍 4-partial beat，制造 FIFO/pending 饱和并验证无丢失。
- 多 beat 同 row 与同 beat 两 chunk 同 row，覆盖 RAW forwarding。
- 随机 ProductBeat 输入与 L1 输出 backpressure。
- n512 和完整 thermal2 的 golden ProductBeat，逐 `(pc,batch,row)` 比较 local partial。
- Track I 之后才需要：真实乘法随机 FMUL latency；join 本身已在乘法侧测试。

golden 从 V0 解码后的 slot 按固定树顺序独立计算，不读取 RTL checksum 推导结果。

## 构建与性能报告

```bash
make -C npc rebuild config=SpmvCuperflowL1SimulationConfig
make -C npc build-host config=SpmvCuperflowL1SimulationConfig
make -C npc run config=SpmvCuperflowL1SimulationConfig mainargs=n512
make -C npc run config=SpmvCuperflowL1SimulationConfig mainargs=thermal2
```

如果仓库要求 version，使用 construction 保存路径运行同一 Verilator model。HTML/JSON 至少
报告：A beat、有效 slot、FMUL issue/response、join occupancy、tree mode、RowPartial 数、
pending/FIFO 高水位、FIFO stall、RAW stall 和总周期。

## 退出条件

- 正式默认 `pad3-1` 不降级即可在 standalone L1 上跑通 n512 与 thermal2 的 local partial。
- 所有 local partial 按 `{pc,wave,batch,row}` 与 golden 对齐。
- 1/2/4 partial 的计数与 V0 统计一致，背压下无丢失、重复或乱序。
- 未修改乘法 RTL。
- 明确量化单路 L1 被 tail 放大的 stall；只有数据证明其主导周期，V5 才做 multi-bank。
- 向 V3 交付稳定的 `CompletedLocalRow` 流和 Batch drain 条件。
