# Track M：乘法时序与 PC 几何，不改 L1

Track M 与 L1 并行。它只调整 `map -> X -> A -> FMUL -> ProductBeat` 能否在 U55C 上按
250 MHz 收敛，以及 1/8/16 PC 副本是否可综合。它不实现加法树、partial RAM、ROB 或 Y。

状态：进行中。ProductBeat 切面、PC 参数化和 1-PC 综合入口已经存在；单 PC 的 prefix 组合
路径仍未收敛，1-PC Vivado 还没有新的 WNS 结论。

## 目标与非目标

目标：

- 保持 `SpmvCuperflowProductBeat` 字段和握手语义不变。
- 用 1-PC synthesize-only 探针隔离单 lane 关键路径，再放大到 8/16 PC。
- 切断已知同步长路径：map 冷路径、segment prefix、R 弹性、URAM ADDR。
- 保持 CDC 用协议而不是额外 ASYNC_REG 凑合：completion 原子、`hbm_base` 在 shell 域、
  复位按目的域同步。
- 乘法 construction 在 L1 未接上时继续抽干 ProductBeat（`ready=1`）。

本轨道不做：

- 不实现 L1/Y，不把 ProductBeat 接到尚未存在的累加器来“顺便看时序”。
- 不把 AXI 从 512-bit 改成 256/64-bit，也不先改成 8 路独立 64-bit 写。
- 不改 slot v6 / map v4 / BATCH_DESC 的软件含义来换时序。
- 不用 `false_path` 掩盖 map/prefix/A 热路径。
- 不等 V1–V4 完成才开始。

## 已经冻结、M 不得破坏的接口

```text
ProductBeat {
  pc, wave, batch, beatSeq,
  laneValid, localRow[8], rowLast[8], chunkMode, product[8]
}
```

- 一个 A beat 的有效 FMUL response 全部到齐才握手。
- 空 lane 不发 FMUL，join 时 product 补 FP64 `+0.0`。
- `beatSeq` 只对实际 A beat 递增，空 batch 不占号。
- `valid && !ready` 时 payload 稳定；反压传到 A reader，不能丢 context。
- `hbmPcCount` 只改变副本数和 `pc` 位宽，不改变每 PC 的 8 lane / 512-bit beat。

公开入口：

| Config | 用途 |
| --- | --- |
| `SpmvCuperflow1PcSimulationConfig` | 单 PC Verilator |
| `SpmvCuperflow8PcSimulationConfig` | 八 PC Verilator |
| `SpmvCuperflowSimulationConfig` | 十六 PC 默认仿真 |
| `U55cSpmvCuperflow1Pc250MHzTimingProbeConfig` | 1-PC 250 MHz 只综合 |
| `U55cSpmvCuperflow8Pc250MHzTimingProbeConfig` | 8-PC bitstream 探针 |
| `U55cSpmvCuperflow250MHzSynthesisConfig` / `...BitstreamConfig` | 16-PC 乘法资产 |

## 时序工作顺序

上次 16-PC bitstream 的最差同步路径是单 lane 内 `prefix[segment] + (col-start)` 到
URAM `ADDR`，不是 16 份复制本身。因此顺序必须是：

```text
1. 1-PC synthesize-only，看清单 lane WNS
2. 若仍失败：在 DecodeMap 寄存 8 个 prefix；A 热路径只做段选择、减法和一次加法
3. 若 ADDR 仍超：寄存翻译后的 local-X 地址，并同步延迟 A/FMUL 发射
4. 1-PC WNS>=0 后再跑 8-PC、16-PC，区分拥塞/扇出与 lane 内部路径
5. CDC 只处理跨时钟协议，不给同步数据路径加 false_path
```

建议但尚未作为完成证据的切法：

- map/descriptor 捕获与宽校验留在冷路径，多一拍不影响 A 稳态 II。
- R 侧保持弹性缓冲，`flow=false`，避免 512-bit 组合旁路。
- 8 个 prefix 在 `stateDecodeMap` 算完并写入寄存器，不要每拍从 8 个 length 现场累加。
- 控制扇出在 PC 间局部复制，不要一份 FSM 信号穿 16 个 SLR。

1-PC 探针不能自动证明 16-PC 已过时序；它只能证明“这段 path 是不是 lane 内部问题”。

## 与 L1 并行时的施工边界

允许改：

- `SpmvCuperflowLane` 的寄存器切分、LocalX 流水、reader 弹性、DecodeMap 寄存。
- FPGA wrapper 的 CDC FIFO、completion 组包、复位同步。
- 1/8/16 PC 的 Config 与端口宏。
- 乘法仿真 host 的冻结宏（必须来自 construction profile）。

禁止改：

- ProductBeat 字段布局、`chunkMode`/`rowLast`/`localRow` 语义。
- 为了时序把 `ProductBeatJoin` 拆回“八条散落 `SpmvProduct` 交给 L1”。
- Track L 的 `scala/l1` 目录；M 不实现加法树。
- 用环境变量选择 PC 数作为正式入口。

接上 L1 之后（Track I）才允许 ProductBeat 被真实反压。那是集成步骤：join FIFO 深度必须
覆盖 FMUL latency 与 L1 stall，但不把 L1 组合逻辑拉进 prefix/ADDR 路径。集成前的 Vivado
实验继续 `product_ready=1`。

## 验证

乘法正确性继续走现有 Cuperflow construction，不发明第二套软件乘法：

```bash
make -C npc rebuild config=SpmvCuperflow1PcSimulationConfig
make -C npc build-host config=SpmvCuperflow1PcSimulationConfig
make -C npc run config=SpmvCuperflow1PcSimulationConfig mainargs=n512
```

时序：

```bash
make -C npc rebuild config=U55cSpmvCuperflow1Pc250MHzTimingProbeConfig
```

通过后再考虑 8-PC / 16-PC。报告必须写明：PC 数、是否含 L1、WNS/TNS、最差路径起终点、
CDC 结论、未执行的 bitstream/上板阶段。

1-PC 功能测试至少覆盖：空 batch、单 group、`pad3-1` fixture 经真实乘法产生的 ProductBeat
与 C++ golden 一致。这是乘法侧合同测试，不是 L1 完成证据。

## 退出条件

- 1-PC 250 MHz synthesis：目标 4.000 ns，同步路径 WNS>=0、TNS=0，prefix/ADDR 不再是
  组合穿越。
- 8-PC / 16-PC 乘法探针给出拥塞与副本数的结论；失败也要记录，不能用 1-PC 代替。
- ProductBeat ABI 未变，或有明确 bump 且 Track L 同步。
- CDC 无协议级撕裂；map/prefix 不是 false_path。
- FPGA kernel 在未接 L1 时仍可抽干 ProductBeat；文档写明接 L1 后的反压责任在 join FIFO。
- 未宣称“带 L1 的 16-PC 250 MHz 已关闭”——那是 V5。
