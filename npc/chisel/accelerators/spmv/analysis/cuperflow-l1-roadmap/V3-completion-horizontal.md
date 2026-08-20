# V3：Completion ROB 与跨 PC 横向规约

状态：未开始。轨道：L。乘法 lane 已经按 BATCH_DESC 读 HBM；本版不要重写
`SpmvCuperflowInputTop` 的 map/X/A FSM。

V3 把多个独立 PC 的 local completion 合并成 wave partial。它解决的是有界异步进度：快
PC 可以继续处理后续 Batch，但必须把结果放进带 epoch 的 completion ROB；横向规约只等待
contributor mask 中真正有数据的 PC。Standalone 可以用 package golden 提供 ProductBeat 和
contributor bitmap，不必先跑 16 路乘法 RTL。

## 目标与非目标

目标：

- 消费 V0 的 contributor metadata（standalone 从 package golden 装入；对接乘法后可复用
  lane 已经读到的 bitmap）。
- 每 PC 建立 8192 行 completion 存储，隔离不同 Batch/epoch 的同一 `localRow`。
- 各 PC 不锁步计算，只有 ROB 容量和目标槽占用造成局部反压。
- 按 `{wave,batch,localRow}` 和 contributor mask 生成逐行 wave partial。
- 验证 PC 最大相差 8192 行时仍不覆盖、不死锁。第一版可用 1/2/8 PC Config 缩小规模，
  16 PC 是完整合同。

本版不做：

- 不做跨 wave Y RMW 和最终 AXI 写回。
- 第一版只有一条横向规约 lane，性能不是终版。
- 不压缩 contributor metadata。
- 不允许用“等所有 PC Batch 完成”代替 completion ROB。
- 不重写乘法 map/X/A 控制流，也不改 ProductBeat。

## 控制流边界

乘法 lane **已经**按下面的 FSM 读 HBM，V3 不要再实现一套：

```text
Idle -> RequestGroupMap -> DecodeGroupMap -> LoadX
     -> RequestBatchDesc -> DecodeBatchDesc -> LoadContributor -> ReadA
     -> DrainCurrentBatch -> RequestNextBatchDesc
     -> lastBatchInGroup ? RequestNextGroupMap : ...
```

X 只在 `DecodeGroupMap/LoadX` 执行一次，同 group 的所有 Batch 复用 active local-X。读取下一
Batch descriptor 不得触发 `clearLoad` 或 `activate`。这是乘法侧既有行为；V3 只要求
standalone 模拟同等 epoch/bitmap 就绪条件。

每个 PC 可以独立进入下一 Batch。descriptor/active bitmap 到达时设置该 PC 对应 epoch 的
`metadataReady`；横向 head row 只有在 **Config 中的全部 PC** 对该 `{wave,batch}` 的
metadata 都 ready 后才解释 contributor mask。快 PC 的 completion 可在此之前写入 ROB。
1-PC standalone 时 contributor mask 只有 bit0 有意义。

## Completion 存储合同

每 PC 的逻辑容量为 8192 个 local row，至少保存：

```text
value:       FP64
valid:       Bool
epoch:       UInt
rowLastSeen: Bool（可与 valid 合并）
```

数据 RAM 使用 `npc.ip.memory.OnChipTrueDualPortMemory`。valid/epoch 可使用统一 RAM 或小型
寄存/BRAM，但不能依赖 URAM 初值。若 completion value 直接来自 L1 `rowLast` 旁路，则每个
contributor 只写一次，不需要再读 L1 RAM。

地址可以是 `localRow`，但命中条件必须是 `valid && storedEpoch==requestedEpoch`。下一 Batch
复用同一地址时：

- 旧 epoch 已被横向消费：允许覆盖并更新 epoch。
- 旧 epoch 尚未消费：只反压该 PC 的 completion/L1，禁止覆盖。
- epoch wrap 前必须证明所有同值旧 epoch 已清空；否则扩大 epoch 位宽或在 job 边界清理。

“8192 深”只允许某 PC 最多领先一个完整 row Batch。若工作流允许领先更多 Batch，需要
增加 `completionEpochSlots` 或在 descriptor 边界等待；该几何由 Config 明确，不能依赖偶然
调度。

## Contributor metadata 接收

V0 的每 PC 1-bit active-row bitmap 在该 PC 的 descriptor 阶段装入本地 expected bitmap。
横向控制器对当前 row 拼成：

```text
contributorMask(row) = Cat(activePc15(row), ..., activePc0(row))
```

这在信息上等价于未压缩 16-bit row-major mask，同时避免新增第 17 个 HBM reader。每个
bitmap 对应明确 `{wave,batch,epoch}`；不能在 PC 提前切换 descriptor 时覆盖横向仍在消费的
旧 bitmap。建议使用与 completion 相同的双 epoch 规则或把 expected bit 并入 completion
metadata bank。

mask 语义：

- bit=1：该 PC 必须提交恰好一个最终 local partial。
- bit=0：横向规约把该 PC 当作 FP64 `+0.0`，且该 PC 若提交则报错。
- mask=0：该 row 的 wave contribution 为 0，可直接输出 0，或由 V4 的 Y 初始化规则跳过；
  两者必须在接口中冻结一种。

## 横向规约调度

横向 head 按全局顺序推进：

```text
wave -> batchId -> localRow
```

对 head row：

1. 等待 16 个 PC 的该 epoch metadata ready。
2. 读取 contributor mask。
3. 只检查置位 PC 的 completion valid/epoch。
4. 全部到齐后原子锁存 16 个值和 mask。
5. 使用统一 FP64 add 构成固定顺序的 16-way 树。
6. 输出 `WaveRowPartial`；握手后释放相关 completion 槽并推进 head。

第一版“从左向右”应解释为确定性括号，而不是 16 拍串行链。建议平衡树：

```text
L0: (pc0+pc1), (pc2+pc3), ...
L1: pair sums
L2: quartet sums
L3: final wave sum
```

mask=0 的叶子输入 `+0.0`。如果为了节省 IP 先做单 endpoint 的 16 拍 fold，必须把其 II=16
明确写入 Config/报告；本路线默认一条“每周期可接收一个 ready row”的流水树。

输出接口：

```text
Decoupled[WaveRowPartial] {
  wave, batch, localRow, globalRow, contributorMask, value
}
```

`globalRow=batchId*rowBatchSize+localRow`，最后 Batch 超出矩阵 rows 的地址非法。

## 背压与释放

- `WaveRowPartial.valid && !ready` 时不释放 completion，不推进 head。
- 树可接受新 row 时才从 ROB 锁存；若树内部有多个 in-flight row，释放点必须与对应 tag
  对齐，不能在发射时提前清 valid。
- 某 PC ROB 目标槽占用时，只反压该 PC 的 L1 completion；其他 PC 可继续，直到各自容量满。
- descriptor/bitmap bank 无空 epoch 时，只阻止该 PC 切换 Batch，不回滚已经发出的 A。
- job done 等待全部 descriptor、A、L1、ROB、横向树和输出 queue 排空。

## 建议模块与文件落点

```text
npc/chisel/accelerators/spmv/scala/l1/cuperflow/
  SpmvCuperflowCompletionBank.scala
  SpmvCuperflowContributorBank.scala
  SpmvCuperflowHorizontalReduce.scala
  SpmvCuperflowL1Top.scala
```

Cuperflow reader/FSM 的 GROUP_MAP/BATCH_DESC 解析仍放在 input lane 或抽出的 protocol reader
中；completion/horizontal 不应解析 512-bit HBM beat。跨模块使用类型化 Decoupled Bundle。

## Config 与 ABI

新增并冻结：

```text
completionDepth = 8192
completionEpochSlots
completionMemoryPrimitive
contributorLayout = per-pc-bitmap-v1
horizontalReduceLanes = 1
horizontalAddLatency / II / provider
```

`rowBatchSize` 必须被 13-bit localRow 覆盖。profile 记录 completion 总逻辑位数、epoch 数和
横向树形态。V3 的 protocol/host ABI 再 bump，host 必须提供 descriptor/bitmap HBM image。

## RTL 断言

- bit=1 的 `(pc,epoch,row)` 只能写一次 completion；bit=0 不得写。
- 写目标 valid 且 epoch 未消费时必须 stall，不得覆盖。
- 横向发射前所有置位 contributor 均 valid 且 epoch 匹配。
- 横向释放只发生在对应输出已经握手后；每个置位槽恰好释放一次。
- metadata 未 ready 时不得把缺失 PC 当 0。
- Batch ID、wave 和 epoch 单调推进，wrap 不与有效旧 entry 别名。
- 最后 Batch 的无效 localRow 不得输出。
- 任意单 PC 长期 stall 不应破坏其他 PC 已存结果；恢复后作业可继续。

## 测试矩阵

| 场景 | 预期 |
| --- | --- |
| 16 PC 同速 | 每 row mask 到齐即流水规约 |
| PC 相差 1/8191 行 | 快 PC 不被全局 barrier 阻塞 |
| PC 领先 8192 行 | 对应 ROB 满后仅该 PC 局部 stall |
| mask=0/单 bit/16 bit | 0、直通和完整树均正确 |
| 某 PC 整 Batch 空 | 不等待不存在的 completion |
| descriptor 延迟到达 | metadata ready 前不误判空贡献 |
| epoch wrap | 不读取或覆盖旧 Batch |
| 随机 PC/output stall | 无死锁、无乱序、无重复 |

真实矩阵先比较每个 wave 的 row partial，不与最终 CSR Y 混淆。thermal2 需完整运行，记录每
PC 最大领先距离、ROB 高水位和 head-of-line 等待来源。

## 构建与报告

```bash
make -C npc rebuild config=SpmvCuperflowL1SimulationConfig
make -C npc build-host config=SpmvCuperflowL1SimulationConfig
make -C npc run config=SpmvCuperflowL1SimulationConfig mainargs=n512
make -C npc run config=SpmvCuperflowL1SimulationConfig mainargs=thermal2
```

报告至少包含每 PC completion 写入/覆盖阻塞、ROB 高水位、metadata 等待、各 mask popcount
周期、横向树 issue/retire、输出 backpressure 和总周期。host golden 按相同固定 PC 树顺序
求 wave partial。

## 退出条件

- GROUP_MAP 只加载一次 X，多个 BATCH_DESC 均能正确处理。
- 16 PC 在随机独立 stall 下产生逐 row 正确的 wave partial。
- PC 领先 8192 行时表现为有界局部背压，而不是覆盖或全局无缓冲 barrier。
- 空贡献、空 Batch、最后短 Batch和 epoch wrap 全部通过。
- completion/contributor RAM 使用统一 memory IP，横向 FADD 使用统一 arithmetic IP。
- 向 V4 交付稳定的 `WaveRowPartial`、全局 row 计算和槽释放合同。
