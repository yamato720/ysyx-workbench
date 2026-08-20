# Cuperflow L1 与横向规约工程路线

本目录把 Cuperflow 从“输入读取与乘法验证”扩展为完整 `Y=A*X` 加速器。版本文档是实施
合同：已完成的版本记录冻结接口，未完成的版本仍是退出条件。接手者必须先核对当前代码，
再按文档验收。

所有版本必须遵守三条硬门禁：硬件结构由 CDE Config/construction 冻结；本地功能与周期
验证使用真实 Verilator RTL；AXI、片上 RAM 和浮点算术只使用 `npc.ip` 统一接口。C++ host
只负责外部存储模型、时钟驱动和独立 golden，不能替代 RTL 数据通路。

## 当前基线

截至当前仓库，乘法侧已经把 L1 需要的事务边界冻住，L1 RTL 尚未开始。

- 编码器位于 `accelerator-sim/spmv/encoding/cuperflow/`。默认 A 布局仍是
  `RowRoundRobin + Pad3To4And1To2`。`hbmChannelCount` 允许 1..16。
- 预处理协议已落地：`cuperflow-map-multisegment-v4`、A slot v6、
  `cuperflow-batch-desc-v1`；profile 为 `PROTOCOL_ABI=spmv-cuperflow-l1-v0`、
  `ACCELERATOR_HOST_ABI=spmv-cuperflow-rtl-v4` / FPGA `spmv-cuperflow-u55c-v4`。
- 输入 RTL 位于 `npc/chisel/accelerators/spmv/scala/input-mul/cuperflow/`。每个 PC
  独立执行 `GROUP_MAP -> X -> BATCH_DESC -> contributor -> A`，并把同一 A beat 的八路
  FMUL 收敛为 `SpmvCuperflowProductBeat`。顶层导出
  `Vec(hbmPcCount, Decoupled(ProductBeat))`。
- HBM **PC 数**可独立选择 1/8/16，AXI beat 仍固定 512 bit、每 PC 仍是 8 个 FP64 lane。
  公开本地入口：`SpmvCuperflowSimulationConfig`（16）、`SpmvCuperflow1PcSimulationConfig`、
  `SpmvCuperflow8PcSimulationConfig`。不要把环境变量
  `SpmvCuperflowPcCountRegressionConfig` 当正式产品入口。
- L1 激励已与乘法 RTL 解耦：C++ `makeProductBeatGolden` 与 test-tree DPI source 可按 V0
  fixture 回放 ProductBeat。DPI 不得进入 construction / FPGA / input-mul。
- 尚无 `npc/chisel/accelerators/spmv/scala/l1/`，也没有 Full8 加法树或 partial RAM。
- FPGA 乘法探针：`U55cSpmvCuperflow1Pc250MHzTimingProbeConfig`（synthesize-only）、
  `U55cSpmvCuperflow8Pc250MHzTimingProbeConfig`、默认 16-PC 综合/bitstream。1-PC 用来
  单独调乘法时序；kernel 当前把 `product_ready` 绑 1，不实例化 L1。
- `rowBatchSize=8192` 是每个 Batch 的行数上限；`xWindowSize=8192` 是每个 PC 的 local-X
  元素上限。两者数值相同但属于不同维度。
- 16 个 PC 各有 8 条 FP64 乘法 lane。U55C 当前 local-X 使用 256 个 URAM。

## 为什么拆成两条轨道

乘法分离的目的不是把 L1 延后，而是让两件事可以同时进行、互不改对方 RTL：

1. **Track M**：关 250 MHz 乘法路径（map、prefix→URAM ADDR、CDC、1/8/16 PC 副本数）。
2. **Track L**：在 ProductBeat 合同上设计 L1 / completion / Y。

隔离面是 `SpmvCuperflowProductBeat`：

```text
乘法 RTL（可改时序、PC 数、LocalX、FMUL）
    -> Decoupled ProductBeat（原子 A beat：sideband + 8 product）
        -> L1 及以后（可改树、RMW、ROB、Y）
```

规则：

- Track M 不得为了 L1 改 slot/map/ProductBeat 字段含义；需要新字段就升 ABI。
- Track L 不得改 `SpmvCuperflowInputTop` / `SpmvCuperflowLocalX` / `SpmvMulEngine` 的
  时序切分。L1 只消费 `Decoupled[ProductBeat]`。
- Track L 的 Verilator 正式路径由 host 按 golden 驱动 L1 顶层的 ProductBeat 口；test-tree
  DPI 只做合同测试。FPGA 与乘法 construction 只能接真实 `InputTop.product`。
- 对接 Track I 把同一份 L1 模块接到 `InputTop.product`，先 1 PC，再 8/16。对接会引入
  背压，但不应该重写乘法内部组合路径。
- 当前 FPGA kernel 抽干 ProductBeat。接上 L1 之前，乘法时序实验继续 `ready=1`。

## 冻结目标

```text
16 PC/HBM 独立 GROUP_MAP -> X -> BATCH_DESC -> A
  -> 每 PC 原子 ProductBeat
  -> 可分裂 8/4/2 加法树
  -> 每 PC 8192 x FP64 L1 partial URAM
  -> 每 PC 8192 行 completion ROB
  -> contributor mask 驱动的跨 PC 横向规约
  -> banked FP64 Y cache，跨 wave 继续累加
  -> 512-bit AXI 写回最终 Y
```

快 PC 可以领先慢 PC，但不能覆盖 completion ROB 中尚未消费的
`{wave,batch,localRow}`。ROB 满或目标 epoch 槽未释放时只反压对应 PC，不增加“所有 PC
完成 Batch k 才能进入 Batch k+1”的无缓冲锁步屏障。

## Slot v6 摘要

```text
[63:51] groupColumn  13 bit
[50:48] segmentId     3 bit
[47]    rowLast       1 bit
[46:45] chunkMode     2 bit
[44:32] localRow     13 bit
[31:0]  matrixValue  FP32
```

| `chunkMode` | 物理 beat 解释 | 最多输出的 `RowPartial` |
| --- | --- | ---: |
| `00` | 一个 8-slot row | 1 |
| `01` | 两个 4-slot row | 2 |
| `10` | 四个 2-slot row | 4 |
| `11` | 保留/非法 | 0 |

`segmentId` 继续选择 map 中的 X 段。`rowLast` 表示该 chunk 是当前
`(PC,wave,batch,row)` 的最后一次局部更新。空 `(PC,row)` 不产生 slot，因此不能仅靠
`rowLast` 判断横向完成，必须另有 contributor metadata。

## Group/Batch 协议摘要

```text
GROUP_MAP
  -> X payload 只加载一次
  -> BATCH_DESC 0 -> A range 0
  -> BATCH_DESC 1 -> A range 1
  ...
  -> 下一张 GROUP_MAP
```

`BATCH_DESC` 至少冻结 `batchId`、`aOffsetBeats`、`aBeats`、contributor metadata
offset/length 和 `lastBatchInGroup`。map 与 descriptor 使用不同 opcode；未知 opcode、长度
越界、Batch 倒退或同 group 重载 X 都必须报错。乘法 lane 已经按这个 FSM 读 HBM；L1 轨道
不要再实现一套 map 解析。

## 版本与轨道索引

| 文档 | 轨道 | 状态 | 交付主题 |
| --- | --- | --- | --- |
| [V0](V0-preprocessing-abi.md) | 共用 | 已完成 | 预处理 ABI、fixture、ProductBeat 合同 |
| [M](M-multiply-timing.md) | M | 进行中 | 乘法 250 MHz、1/8/16 PC、不改 ProductBeat |
| [V1](V1-full8-l1.md) | L | 未开始 | 统一 FP64 add、单 PC Full8 L1 |
| [V2](V2-tail-beat-join.md) | L | 未开始 | 8/4/2 树；消费已 join 的 ProductBeat |
| [V3](V3-completion-horizontal.md) | L | 未开始 | completion ROB 与横向规约 |
| [V4](V4-y-cache-writeback.md) | L | 未开始 | banked Y、跨 wave、AXI 写回 |
| Track I | 对接 | 未开始 | 同一份 L1 接到真实 `InputTop.product` |
| [V5](V5-throughput-fpga.md) | 集成后 | 未开始 | L1/Y 吞吐；带 L1 的 16-PC 250 MHz |
| [V6](V6-capacity-precision.md) | 条件 | 未启动 | 超容量 row tile 与精度变体 |

依赖关系：

```text
V0 已完成 ── ProductBeat ABI ──┬── Track L: V1 -> V2 -> V3 -> V4
                               │
                               └── Track M: 1PC 乘法时序 -> 8/16PC 乘法时序

Track I: 同一份 SpmvCuperflowL1 接到 InputTop.product（先 1PC）
         V1 之后即可开始，不阻塞 V2 的 golden 开发

V4 + Track I + Track M 的 1PC 乘法时序 ──> V5
V6 仅在 V4/V5 实测证明有必要后启动
```

V1 不再以“把乘法 IP 迁到统一接口”或“改 InputTop 时序”为前置。V2 不再实现 FMUL
response join：那已经在乘法侧的 `SpmvCuperflowProductBeatJoin` 完成。V5 不再承担乘法
prefix/CDC 的首次收敛；那些属于 Track M，现在就可以做。

## 共同 Config 与 IP 合同

新增几何必须放入 `SpmvCuperflowConfig` 并写入 simulation/FPGA profile。L1 轨道至少包括：

```text
l1Enabled, rowBatchSize, l1MemoryPrimitive, rowPartialFifoDepth,
completionDepth, fp64AddLatency, fp64AddII, fp64AddResponseFifoDepth,
fp64AddProvider, horizontalReduceLanes, yCacheRows, yBankCount,
yMemoryPrimitive
```

PC 数继续用已有 `hbmPcCount` / `WithSpmvCuperflowPcCount`，不要再引入环境变量几何。

协议变化必须同步 bump `SPMV_CUPERFLOW_MAP_ABI`、`PROTOCOL_ABI` 和
`ACCELERATOR_HOST_ABI`。不允许旧 v3 host 静默驱动新 RTL。ProductBeat 字段变化视为
乘法/L1 共同 ABI，必须两边一起升版本。

- AXI/HBM：使用 `npc.ip.axi`。
- L1/completion/Y RAM：使用 `npc.ip.memory.OnChipTrueDualPortMemory` 或 masked 版本。
- FP64 add：扩展并使用 `npc.ip.arithmetic` provider；禁止 accelerator 私有 `BlackBox`。
- FP64 multiply：Track M 维持现有 Cuperflow provider，直到 M 自己决定迁统一接口。
  Track L 不得借“统一 IP”去改乘法 RTL。
- RAM 必须建模同步读延迟；禁止用 `SyncReadMem` 或寄存器数组冒充最终 FPGA RAM。

## 共同正确性断言

- `localRow < rowBatchSize`，`chunkMode != 11`，`segmentId` 指向有效 X 段。
- 同一 chunk 的 row、mode 和 `rowLast` 一致，lane 分组与 `chunkMode` 相符。
- 任意 Decoupled 接口在 `valid && !ready` 时保持 payload 稳定。
- FMUL/FADD response tag 与请求一致；重复、丢失或非法响应使作业失败。
- partial、completion 和 Y 地址不越界，不覆盖尚未消费的 epoch。
- contributor 未置位的 PC 不提交 partial；置位 PC 必须且只能提交一次最终 partial。
- `done` 等待 reader、FMUL、L1、completion、horizontal、Y 和 AXI write 全部排空。
- reset 后不依赖 BRAM/URAM 初值，使用显式 valid、epoch 或受控清零建立状态。

## 容量基线

thermal2 有 1,228,045 行，当前粗估如下。这里只用于约束设计量级，V4/V5 必须以综合报告
替换估值。

| 模块 | 预计 URAM |
| --- | ---: |
| local-X | 256 |
| 16 路 L1 partial | 约 32 |
| 16 路 completion | 约 32 |
| banked FP64 Y | 约 304 |
| 合计 | 约 624 / 960 |

Y 必须 banking，不能实现为 122 万深的单链 URAM。当前预算尚不要求把正式路径退回 FP32。

## 交接格式

接手者先声明领取的轨道和版本，并阅读该版本列出的依赖文件。每次交付至少报告：

- 修改的 Config、profile 和 ABI；
- 是否改动了 ProductBeat 或乘法 RTL（Track L 的答案应为否，除非走了明确的 ABI bump）；
- Verilator 使用的 construction/version；
- 通过的逐值 golden 数据集和误差规则；
- 周期、各级 stall、高水位和适用的资源结果；
- 尚未执行的 FPGA 阶段；
- 是否满足退出条件，以及交给下一版本的冻结接口。

软件周期模型、AVX 或单独 C++ 算法只能作为参考，不能作为任何版本的 RTL 完成证据。
Golden ProductBeat 可以代替乘法 RTL 作为 L1 的输入激励，不能代替 L1 自己的加法树和 RAM。
