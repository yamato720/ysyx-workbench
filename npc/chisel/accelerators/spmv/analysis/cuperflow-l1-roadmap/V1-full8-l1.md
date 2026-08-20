# V1：统一 FP64 add 与单 PC Full8 L1

状态：未开始。轨道：L。输入已经是冻结的 `ProductBeat`，不依赖乘法 RTL 是否正在改时序。

V1 先建立最小但真实的局部累加闭环：一个 PC 的 8 个 product 经过 FP64 加法树归约，
再对 8192 行 partial RAM 做读改写。它只接受完整 8-slot row，目的是把算术 IP、同步 URAM
时序、同 row RAW 和 Decoupled 背压分别验证清楚。

## 目标与非目标

目标：

- 扩展 `npc.ip.arithmetic`，提供统一 FP64 **add**（simulation provider + U55C adapter +
  contract test）。
- 新增 standalone L1 RTL，顶层只有 `Decoupled[ProductBeat]` 输入和 completion 输出。
- 实现单 PC、单 `RowPartial`/beat 的 8-way tree 与 `8192 x FP64` partial RAM RMW。
- 在任意合法 FADD latency 和输出背压下处理同 row RAW。
- 正式 Verilator 路径由 host 喂 C++ `makeProductBeatGolden`；test-tree DPI 只做合同测试。

本版不做：

- 不支持 `chunkMode=01/10`，收到后明确置 error/assertion。
- 不接 completion ROB、横向规约、Y cache 或 AXI 写回。
- 不用 Chisel `+` 冒充最终 FP64 add，也不在 accelerator 内声明私有浮点 BlackBox。
- 不拿 thermal2 周期作最终性能结论，因为默认编码包含 4/2 尾部。
- **不改乘法 RTL、LocalX、prefix 或 FMUL provider。** 把 Cuperflow multiply 迁到统一
  `npc.ip.arithmetic` 属于 Track M 的可选工作，不是 V1 退出条件。
- 不把 DPI BlackBox 放进 construction / FPGA manifest。
- 不要求先关闭 250 MHz 乘法时序。

## 前置依赖

V0 已完成。实施前核对：

- `npc/chisel/accelerators/spmv/scala/input-mul/cuperflow/SpmvCuperflowProductBeat.scala`
- `accelerator-sim/spmv/encoding/cuperflow/product_beat_golden.hpp`
- `accelerator-sim/spmv/encoding/cuperflow/fixtures.hpp`
- `npc/chisel/accelerators/spmv/scala/input-mul/common/SpmvCuperflowL1Fixtures.scala`
- `npc/chisel/ip-interface/scala/ArithmeticOperator.scala`
- `npc/chisel/ip-interface/test/`
- `npc/chisel/ip-interface/scala/OnChipMemory.scala`
- `npc/chisel/configs/accelerators/spmv/base/SpmvCuperflowConfig.scala`

不要把 `SpmvMulEngine.scala` / `SpmvCuperflowInputTop.scala` 列为本版必改文件。

## 内部接口

建议定义共享 Bundle，文件落在新的 L1 目录而不是塞入顶层：

```text
npc/chisel/accelerators/spmv/scala/l1/cuperflow/
  SpmvCuperflowL1Types.scala
  SpmvCuperflowFull8Tree.scala
  SpmvCuperflowL1.scala
  ElaborateSpmvCuperflowL1Top.scala
```

`ProductBeat` 已在乘法侧冻结，V1 只消费、不重新定义：

```text
pc:          UInt(log2Ceil(hbmPcCount))
wave:        UInt(16)
batch:       UInt(16)
beatSeq:     UInt(32)
laneValid:   UInt(8.W)
localRow[8]: Vec[UInt(13.W)]
rowLast[8]:  Vec[Bool]
chunkMode:   UInt(2.W)
product[8]:  Vec[UInt(64.W)]
```

Standalone 顶层建议固定 `hbmPcCount=1`，`pc` 恒为 0。V1 约束 `laneValid=0xff`、八个
`localRow` 相同、八个 `rowLast` 相同且 `chunkMode=00`。输出 `CompletedLocalRow`：

```text
pc, wave, batch, localRow, partial: FP64 bits, error
```

只有输入 chunk 的 `rowLast=true` 时产生输出；非末尾 chunk 只更新 partial RAM。

## 统一 FP64 加法 IP

当前 `ArithmeticIpProvider` 只正式暴露整数乘除。V1 只扩展 **FP64 add**，供 L1 树和 RMW
使用：

- 明确的 operation/type 合同，至少增加 `Fp64Add`；
- `makeFp64Adder` 或等价的类型安全 endpoint factory；
- 与现有 `ArithmeticOperatorIO` 一致的 `req/resp/tag/illegal` 语义；
- `ArithmeticIpTiming(latency, initiationInterval, responseFifoDepth)`；
- Verilator 行为 provider；
- Xilinx `floating_point v7.1` Add adapter/provider（PG060 Add，不是 Accumulator，也不是
  FMA）；
- simulation 与 FPGA provider 的 contract test。

不要在本版强制迁移 Cuperflow 现有 FP64 multiply。乘法继续走当前 provider，以免 Track M
的时序实验和 L1 IP 工作互相改同一模块。

FP64 请求的 `operandA/B` 保存 IEEE-754 binary64 bits，`operandC/pc/instruction` 若不用必须
由合同规定为 0，不能让 provider 自行解释。NaN、Inf、signed zero 和 rounding mode 的行为
必须在 contract test 中固定。第一版建议采用 vendor 默认 round-to-nearest-even，并禁止
fast-math 改写 Verilator reference。Xilinx Accumulator 是带 TLAST 的标量状态机且默认
RTZ，不能拿来当 8 路树节点。

tag 至少覆盖 `{treeNode, beatSeq, rowContext}`，位宽由 Config 推导。response 背压时 IP
wrapper 必须保存结果；不能假定 vendor IP 有 ready 而丢弃返回值。

## 8 路加法树

逻辑结构为三层：

```text
level 0: p0+p1  p2+p3  p4+p5  p6+p7
level 1:   s0+s1          s2+s3
level 2:          t0+t1
```

每层使用统一 FP64 add endpoint。每个 tree context 随请求通过 tag/上下文 FIFO 前进，不能
假设所有 endpoint 永远同拍返回。若每个 endpoint 固定相同 latency，仍需 assertion 检查
四路、两路和一路返回完整后再提交。

目标启动间隔是每周期接收一个 Full8 beat。若单 endpoint 的 `II>1`，Config elaboration 应
拒绝这个 profile，或明确复制 endpoint；不允许仿真声称 II=1、FPGA 实际 II>1。

## Partial RAM 与 RMW

每 PC 使用一份 `8192 x 64` 逻辑 RAM，实例化
`npc.ip.memory.OnChipTrueDualPortMemory`：

- Port A：发出 `localRow` 读取旧 partial，同步一拍返回。
- Port B：写入 `oldPartial + beatSum`。
- `rowLast` 的 `newPartial` 在写回同拍旁路到 completion 输出，不再从 RAM 二次读取。
- 新 Batch/epoch 的首次写视旧值为 FP64 `+0.0`，不得依赖 URAM 上电初值。

建议每行另外保存有效 epoch。若 epoch RAM 与 data RAM 分离，二者读地址、返回 valid 和
写回必须锁步。若采用受控 clear，必须把 8192 拍清零成本计入周期，不能在 host 中偷清。

RMW 时序示意：

```text
C0 accept RowPartial, issue RAM read
C1 receive old/epoch, issue final FADD
C1+L receive newPartial
C2+L write Port B; rowLast 时同时 enqueue completion
```

## 同 row RAW

只要后一项在前一项写回前读取同一 `localRow`，同步 RAM 就可能读到旧值。V1 必须实现
scoreboard 或 forwarding：

- scoreboard 记录所有 in-flight `{epoch,localRow}`；
- 若新请求命中可转发的已计算结果，直接选择 forwarding value；
- 若命中尚未产生结果的 FADD，反压该 row 请求，其他 row 是否可越过必须由接口顺序合同
  决定；第一版可整体 stall，但必须计数；
- writeback 与新 read 同拍同地址时，显式定义 write-first/read-first 行为，不能依赖 vendor
  primitive 默认值。

建议 forwarding entry 保存 `{valid,epoch,row,valueReady,value}`，深度至少覆盖
`RAM read latency + FADD latency + response buffering`。深度由 Config/require 推导。

## Config 与 construction

在 `SpmvCuperflowConfig` 中建议新增：

```text
l1Enabled
rowBatchSize = 8192
l1MemoryPrimitive
fp64AddLatency
fp64AddInitiationInterval
fp64AddResponseFifoDepth
fp64AddProvider
l1ForwardDepth
```

新增真正有差异的公开 terminal，例如：

```scala
class SpmvCuperflowL1Full8SimulationConfig extends CDEConfig(
  new WithSpmvCuperflowL1Full8Config ++
    new WithSpmvCuperflowPcCount(1) ++
    new WithSpmvCuperflowConfig(SpmvCuperflowConfig.Simulation)
) with LocalSpmvCuperflowL1Terminal
```

名称可调整。不要再使用已不存在的 `SpmvCuperflowSimulationConfigforL1` 别名，也不要让
L1 terminal 去 elaborate `SpmvCuperflowInputTop`。profile 至少冻结 L1 enable、RAM
primitive、FADD provider/timing、rowBatchSize 和 ProductBeat ABI。

standalone 顶层和以后接到乘法的顶层必须实例化同一个 `SpmvCuperflowL1`。standalone 的
host 从 V0 package 生成 ProductBeat golden 并驱动 `io.product`；接乘法是 Track I，本版
不阻塞。

## RTL 断言

- V1 只接受 `chunkMode=00`、八 lane valid、同 row 和同 rowLast。
- `localRow < rowBatchSize`，epoch 不覆盖尚未完成的旧 Batch。
- 每个接受的 tree input 恰好产生一个 beat sum，tag 不重复、不丢失。
- FADD `illegal` 立即置顶层 error；response tag 必须命中 in-flight entry。
- RAM 读返回必须有对应 context；禁止无请求写回。
- 同 row 冲突必须 stall 或 forwarding，不能静默读取陈旧 partial。
- completion 在 `valid && !ready` 时保持稳定，且只在 `rowLast` 产生一次。
- `done` 等待 tree、final FADD、scoreboard 和 completion queue 全部为空。

## 测试矩阵

1. 单 row 一个 Full8，结果与严格 FP64 顺序树 golden 对齐。
2. 同 row 多个 Full8，`rowLast` 仅在最后一 beat。
3. 交错两个 row，验证不同地址可流水。
4. 同 row 重访间隔为 1、`L-1`、`L`、`L+1`，其中 `L` 为实际 FADD latency。
5. 输入和 completion 端分别施加随机 backpressure。
6. FP64 `+0/-0`、subnormal、Inf、quiet NaN；比较合同规定的 bit/分类行为。
7. localRow 0、8191 和越界 8192。
8. reset 发生在空闲和存在 in-flight 请求时；后者必须按顶层 reset 合同清空或报错。

golden 必须按同一棵确定性树计算，不能用任意顺序求和后只比 checksum。普通有限值可选择
逐 bit 或 ULP 容限，但规则必须固定在 host ABI 中。

## 构建与验证

新增 Config 后的正式路径应为：

```bash
make -C npc rebuild config=SpmvCuperflowL1Full8SimulationConfig
make -C npc build-host config=SpmvCuperflowL1Full8SimulationConfig
make -C npc run config=SpmvCuperflowL1Full8SimulationConfig mainargs=<full8-fixture>
```

具体命令以生成的 construction catalog 为准；若 `make run` 要求 version，则先执行
`make -C npc version config=...` 并使用保存的 version。必须确认 host 链接
`abi/verilator/` 中的模型，而不是旧 `build/` 可执行文件。

统一 IP 还需运行 `npc/chisel/ip-interface/test/` 中新增的 contract test。修改 RTL、Config
或 IP ABI 后必须 `rebuild`，只改 host 才允许 `host-build`。

## 可观测指标

- tree accepted/retired beat 数及各层 occupancy。
- FADD request stall、response stall、illegal 数。
- RAM read/write 数、首次 epoch 初始化数。
- RAW hit、forward hit、RAW stall 周期。
- completion queue 高水位和输出 backpressure 周期。
- 首输入到首 completion latency、稳态 II 和总周期。

## 退出条件

- 统一 FP64 **add** 的 simulation provider 与 U55C adapter 有同接口和 contract test。
- standalone L1 经过 Config -> elaborate -> Verilator -> host -> 逐 row golden；激励来自
  ProductBeat golden，不实例化乘法顶层。
- 所有 RAW 间隔和随机背压测试通过，无 payload 稳定性错误。
- 综合可见的 partial RAM 来自 `npc.ip.memory`，加法来自 `npc.ip.arithmetic`。
- `chunkMode=01/10/11` 均明确失败，不静默计算。
- 未修改 `SpmvCuperflowInputTop` / LocalX / MulEngine。
- 交付冻结的 `CompletedLocalRow`、FADD timing 和 scoreboard 行为给 V2。ProductBeat 维持
  V0/乘法侧合同。

V1 不以未支持默认尾部的 thermal2 周期宣称整体性能，也不以乘法 250 MHz 报告作为本版证据。
