# V5：L1/Y 吞吐扩展与带 L1 的 250 MHz

状态：未开始。本版在 V4 与 Track I 之后。乘法 **prefix / CDC / 1-PC 时序** 已经拆到
[Track M](M-multiply-timing.md)，不要等 V5 才去改 `InputTop`。

V5 不再增加 SpMV 功能，而是用 V0–V4 的真实计数器和综合报告定位 **L1 及以后** 的瓶颈，
再扩大对应级的并行度，并在已经接上 L1 的 16-PC 设计上收敛 250 MHz。任何优化都必须能在
Config 中关闭，并与 V4 单 lane 基线逐值等价。

## 目标与非目标

目标：

- 建立从 ProductBeat 到 AXI writeback 的逐级吞吐账本（接乘法后可把 A reader/FMUL stall
  一并计入，但不把乘法内部时序手术放到本版）。
- 根据触发阈值选择 L1 banking、横向多 lane、metadata 压缩或 Y/writeback 扩展。
- 保持各 PC 独立进度和完整 Y golden。
- 带 L1 的 U55C 设计达到 250 MHz，并检查资源、primitive、CDC 和跨 SLR 路由。

本版不做：

- 不凭直觉同时打开所有候选优化。
- 不修改数学顺序来换性能，除非建立独立精度 Config/报告。
- 不用 `false_path` 掩盖同步数据路径，也不把失败模块替换为 C++ 周期模型。
- 不在本版引入超容量 row tile 或 FP32；这些属于 V6。
- **不再作为第一次关闭乘法 prefix/CDC 的场所。** 那些属于 Track M，可与 V1 同时进行。

## 前置门禁

开始 V5 前必须已经具备：

- V4：n512、thermal2 的 Verilator 完整 Y golden。
- Track I：同一份 L1 已接到真实 `InputTop.product`（至少 1 PC，完整合同是 16 PC）。
- Track M：1-PC 乘法-only 250 MHz 的结论（通过或明确剩余路径）。不要用“L1 还没有”
  推迟乘法时序，也不要用未接 L1 的 WNS 宣称 V5 完成。
- input/ProductBeat、tree、L1、completion、horizontal、Y、writeback 的周期和 stall 计数。
- 各 FIFO/ROB 的高水位。
- 带 L1 的 U55C 初步 synthesis 报告。

如果 V4 尚未正确写回 Y，不能以“先优化性能”为由进入 V5。如果只是乘法 prefix 未收敛，
去 Track M，不要改 L1 树来“帮忙过时序”。

## 统一性能账本

所有计数器都在 RTL 中随真实握手更新，host 只读取和展示。至少包括：

```text
jobCycles
aBeatsAccepted, usefulSlots, paddingSlots
fmulReq/Resp/Stall
joinOccupancyMax, joinFullCycles
rowPartialsByMode[1/2/4]
rowPartialFifoMax, rowPartialBackpressureCycles
l1Req/Retire, l1RawHit/Forward/Stall, l1BankConflict
completionWrites, completionFullCyclesByPc, robHighWaterByPc
horizontalReadyWait, horizontalIssue/Retire
yReq/Retire, yRawStall, yBankConflict
axiAw/W/BFire, axiWriteStall, writeOutstandingMax
drainCycles
```

HTML 报告按模块展示 `active / downstream stall / dependency stall / idle`，各状态对总周期应能
闭合；不能用互相重叠的百分比宣称利用率。核心吞吐同时报告：

```text
useful slot / cycle
RowPartial / cycle
completed row / cycle
written Y row / cycle
128 FMUL lane peak utilization
```

## 优化选择规则

一次实验只打开一个主要变量，保留 V4 Config 作为对照。建议触发标准：

| 观测 | 首选优化 | 不应先做 |
| --- | --- | --- |
| RowPartial FIFO 长期高水位且 L1 busy 主导 | 2/4-bank L1 | 先加横向 lane |
| completion 已齐但 horizontal queue 持续阻塞 | 多 lane 横向树 | 增大 local-X |
| metadata reader 明显占 HBM/控制周期 | bitmap 压缩与流式解码 | 改 A slot |
| Y bank conflict/queue 主导 | 增加 Y bank/RMW lane | 盲目加 AXI outstanding |
| AXI W/B stall 主导 | pack/burst/outstanding 优化 | 复制 FADD |
| Verilator 不堵但 FPGA WNS 失败 | 若路径在 prefix/ADDR/CDC：回 Track M；若在 L1/Y：pipeline/placement/replica | 降低 RTL 功能正确性 |

“长期”建议定义为目标数据集总 job 周期的 5% 以上，并且该级上游确实有可用工作。阈值写入
报告脚本，避免凭截图判断。

## 2/4-Bank L1 候选

按 `localRow` 低位 banking：

```text
bank = localRow & (l1BankCount-1)
index = localRow >> log2Ceil(l1BankCount)
```

每 bank 保持一套统一 TDP RAM、scoreboard 和 forwarding。一个 4-partial beat 可同时命中
1..4 个 bank：

- 不冲突项可同拍发给不同 RMW lane。
- 同 bank 多项按 lane/chunk 顺序排队，不能重排同 row 浮点顺序。
- dispatcher 的 ready 只有在所有本拍 token 都被 bank queue 接收或原子锁存后才能拉高。
- `l1BankCount`、每 bank queue depth 和 RMW lane 数由 Config 冻结。

V0 统计需新增同拍 bank conflict 预测，并与 RTL 实测一致。若 4 bank 仍因行号分布冲突，
可以比较预处理 row remap，但必须保留 physical-to-original row 映射且不破坏 contributor/Y
地址；不能只在 RTL 使用不可逆 hash。

## 多 Lane 横向规约

`horizontalReduceLanes=N` 时最多同时处理 N 个连续 ready row：

- head 选择仍按全局 row 顺序，只有 `head+i` 全部 metadata/partial ready 时才发对应 lane。
- completion bank 需要支持同拍 N 个 row 的读取。可按 localRow banking；冲突时少发，不复制
  16 份大 RAM 只为理想峰值。
- 每条树携带 globalRow tag，结果进入有界 reorder buffer，向 Y 仍按 row 顺序提交。
- 释放 completion 的时点与具体 tree response/output 握手绑定。

增加 lane 后 FP64 加法顺序对单 row 不变，所以 golden 应逐 bit 等价。若为了共享 FADD 改为
时间复用，profile 必须准确记录实际 II。

## Contributor 压缩候选

仅当未压缩 bitmap 的带宽/存储确为瓶颈时比较：

- bitmap：基线，1 bit/PC/row，随机访问简单。
- RLE：适合长连续 active/empty 区间，需要 run FIFO 和边界断言。
- active-row list：适合极稀疏 PC，需顺序游标和缺失 row 比较。

三者向下游暴露同一接口：每周期最多查询/产生一个
`{epoch,localRow,expected}`，并保持 backpressure。压缩格式有独立 ABI/profile 值，host 不能
自动猜。报告同时给压缩率、decode stall、LUT 和时序，不只看 package bytes。

## Y 与 AXI 扩展

若 Y 成为瓶颈：

- `yRmwLanes` 与 `yBankCount` 联动，dispatcher 处理 bank conflict。
- 每 bank forwarding 保证同 row RAW，跨 lane 不得重复接受同一 row。
- final row reorder 后再组成 512-bit beat；多 lane 不得破坏 AXI 顺序。
- 写 master 的 outstanding 增加前先检查 B response 和 W channel 是否真的占主导。

写回 peak 是每拍一个 512-bit beat，即 8 个 Y/拍；超过该速率的内部 final stream 必须由
有界 FIFO 吸收并可反压，不能假设 HBM 永不阻塞。

## 250 MHz 时序策略

目标时钟为 4.000 ns。乘法侧的 map/prefix/R/CDC 策略见 Track M；本版只处理 **接上 L1 之后**
新出现的路径：

- ProductBeat 出口保持 Decoupled 且 `flow=false`，不要把 L1 树组合回 FMUL/ADDR。
- 加法树层间、RMW 地址、completion/Y 的 wide mux 仍超时时，加寄存并同步延迟
  value/valid/tag。
- 16 路 completion/horizontal/Y 的控制扇出局部复制，避免一份 head 指针穿多 SLR。
- URAM/BRAM 输入输出寄存器、cascade 和 SLR 放置以综合报告为依据。
- 若最差路径仍在乘法 prefix/ADDR，退回 Track M，不要在 L1 里打补丁。

CDC 合同沿用 Track M：多 bit completion/status 使用锁存+toggle 或异步 FIFO；HBM base 在
shell 域；复位按目的域同步。map->FSM 等同步路径不能标 false path。

## Config 变体

建议保留以下公开、无参数 terminal：

```text
SpmvCuperflowL1SimulationConfig              # V4/V5 默认
SpmvCuperflowL1BankedSimulationConfig        # 候选优化对照
SpmvCuperflowL1U55cSynthesisConfig           # 带 L1 的 250 MHz synthesis
SpmvCuperflowL1U55cBitstreamConfig           # synthesis 通过后才启动
```

乘法-only 的 `U55cSpmvCuperflow1Pc250MHzTimingProbeConfig` 属于 Track M，不要在本版改它的
几何来夹带 L1。

具体几何放在 `base` key 和 `core` 配方，terminal 不读取环境变量决定硬件结构。profile 至少
冻结 L1/horizontal/Y bank 与 lane 数、所有 FIFO 深度、IP provider/timing、clock 和 protocol
ABI。参数变化必须新 construction 或 `rebuild`。

## 验证矩阵

每个优化 Config 都必须运行：

- V0-V4 的全部边界 fixture。
- 随机 backpressure 与 RAM bank conflict 定向样本。
- n512 和完整 thermal2 的逐行 Y golden。
- 与 V4 默认 Config 的逐 bit/既定容限等价对照。
- 长时间运行覆盖 beatSeq/epoch/reorder tag 回绕。

性能比较必须使用同一 package、同一 useful nnz、同一 FP64 数学顺序和同一 Verilator host。
不能把编码 padding 变化、算法重排和硬件并行度变化混成一个数字。

## 正式构建

```bash
make -C npc rebuild config=SpmvCuperflowL1SimulationConfig
make -C npc build-host config=SpmvCuperflowL1SimulationConfig
make -C npc run config=SpmvCuperflowL1SimulationConfig mainargs=thermal2

make -C npc rebuild config=SpmvCuperflowL1U55cSynthesisConfig
```

只有 synthesis WNS/TNS 和 CDC/DRC 清理后才启动 bitstream：

```bash
make -C npc rebuild config=SpmvCuperflowL1U55cBitstreamConfig
```

实际 Config 名在实现时加入 catalog。报告必须区分：Verilator correctness/performance、Vivado
synthesis/implementation、bitstream 和上板 runtime；缺一项就明确写“未执行”。

## FPGA 检查清单

- WNS >= 0、TNS = 0，目标时钟确为 250 MHz。
- timing report 无未约束路径，CDC report 无协议级错误。
- URAM/DSP/LUT/FF/BRAM 在 U55C 预算内，且 primitive 与 profile 一致。
- 检查最差 20 条路径的起终点、逻辑/布线比例、跨 SLR 和高扇出。
- 检查 local-X、L1、completion、Y 的 URAM 数和 cascade；不能只看总利用率。
- Verilator 与 FPGA provider 的 latency/II/profile 一致。
- bitstream 生成后再运行 XRT host 与完整 Y golden，不能用 synthesis 成功代替上板正确性。

## 退出条件

- 每项启用优化都有明确的 V4 瓶颈证据和单变量 A/B 结果。
- thermal2 完整 Y 保持正确，周期/吞吐提升可由 stall 下降解释。
- 250 MHz implementation 达到 WNS>=0、TNS=0，无错误 CDC/DRC。
- 资源不超过板卡，URAM 组织和统一 IP 映射符合 profile。
- Simulation 与 U55C 使用同一 Chisel 数据通路；没有软件后端替换。
- 若实际启动 bitstream，则上板 output Y 通过 golden；未启动时明确停在 synthesis/implementation。

V5 的最终报告应列出保留和拒绝的候选优化及原因，作为论文中的架构取舍证据。
