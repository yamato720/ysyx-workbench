# V4：Banked FP64 Y、跨 Wave 与 AXI 写回

状态：未开始。轨道：L。可继续用 golden ProductBeat + contributor 做 standalone 完整 Y；
接到真实乘法属于 Track I，不是本版的起始阻塞。

V4 把 V3 的 wave partial 累加成最终 Y，并由 RTL 写回外部内存。完成 V4 后，Cuperflow 才从
“局部归约实验”变成具备完整 `Y=A*X` 语义的加速器。

## 目标与非目标

目标：

- 以 `globalRow` 为地址在片上 banked FP64 Y cache 中做跨 wave RMW。
- 明确定义 Y 初始化、同 row RAW、最后 wave 完成和输出顺序。
- 通过 `npc.ip.axi` 512-bit write master 把最终 Y 写回 HBM。
- host 从 RTL 写回区域读取完整 Y，与独立 CSR golden 逐行比较。
- n512、最后短 Batch、多 wave样本和完整 thermal2 通过。

本版不做：

- 不先做多 lane 横向/L1 性能优化；V4 优先冻结完整正确性。
- 不引入 row tiling；默认要求 `rows <= yCacheRows`。
- 不把 checksum 当最终结果，也不让 host 代替 RTL 完成 wave 累加。
- 不静默切换 FP32 或混合精度。

## 输入与输出合同

输入沿用 V3：

```text
Decoupled[WaveRowPartial] {
  wave, batch, localRow, globalRow, contributorMask, value
}
```

还需有 job 级元数据：

```text
rows, waveCount, outputBase, outputBytes, accumulateExistingY
```

第一版建议 `accumulateExistingY=false`，Y 初始值由 RTL 视为 FP64 `+0.0`。若以后支持
`Y=beta*Y+A*X`，必须另升 ABI，不能把 output buffer 旧内容默认当输入。

输出端向 HBM 写入 `rows` 个小端 FP64，按全局 row 递增；最后不足 8 个元素的 512-bit beat
使用 AXI `strb` 屏蔽无效 byte，不越界写 output buffer。

## Banked Y Cache

逻辑容量为 `yCacheRows x 64 bit`，按低位 banking：

```text
bank = globalRow % yBankCount
index = globalRow / yBankCount
```

`yBankCount` 必须是 Config 中受检的二次幂，地址计算使用低位切分而不是昂贵除法。每个 bank
使用 `npc.ip.memory.OnChipTrueDualPortMemory`；实际 primitive 在 Simulation/U55C profile
中分别冻结为行为模型/UltraRAM。

每 entry 只保存 FP64 value，不保存 row 地址。有效性使用 epoch/valid metadata：

- 该 job 第一次访问 row 时，old value 取 `+0.0`。
- 后续 wave 访问同 row 时读取并累加旧值。
- reset/job start 不扫描清零全部 122 万行，除非明确把清零周期纳入协议；优先使用 job epoch。
- epoch 位宽回绕前必须执行受控清理或保证旧 valid 已失效。

thermal2 粗估 Y 占约 304 URAM。禁止把 1,228,045 行实现成单条深 cascade；V4 综合必须检查
bank 实例数、每 bank 深度、URAM primitive 和 SLR 分布。

## Y RMW 与 RAW

每个 `WaveRowPartial` 触发：

```text
read Y[bank,index]
 -> epoch ? old : +0.0
 -> FP64 add(old, wavePartial)
 -> write new Y
```

V3 按 row 顺序输出时，相同 row 的不同 wave 通常隔得很远，但协议不能依赖 thermal2 的偶然
距离。必须使用与 L1 相同的 scoreboard/forwarding 原则处理同 bank 同 row RAW。

不同 bank 可并行，但 V4 第一版可以只有一个 Y RMW issue lane。单 lane 接口仍需 bank 化，
因为容量、URAM cascade 和 V5 扩展都依赖这一物理结构。记录每 bank 访问和冲突，即使当前
issue lane 不会产生同拍冲突。

判断 final row 的条件必须来自明确的 `lastWave`/`waveCount`，不能看到某段暂时无贡献就提前
写回。最后 wave 的 new Y 可同时写 cache 并进入 writeback staging；若后续不再读取该 row，
也可旁路以减少一次 RAM read，但 cache 状态与旁路必须保持一致。

## 写回架构

建议拆为：

```text
Y final stream
 -> reorder/pack buffer（按 globalRow 递增）
 -> 8 x FP64 组成 512-bit beat
 -> npc.ip.axi Axi4WriteMasterIO
 -> output HBM region
```

V3/V4 正常按 row 顺序产生 final stream，因此无需大规模乱序缓存；仍应保存 `nextWriteRow`
并断言输入 row 连续。若多 lane 优化导致乱序，V5 必须显式加入 reorder，而不是让 AXI host
猜顺序。

AXI 写 master 必须处理：

- 最大 burst 长度和 4 KiB 边界；
- AW/W/B 三通道独立背压；
- W `last`、`strb` 和 ID/response error；
- output 地址加法和 buffer 边界；
- 所有 B response 返回后才允许 job done。

外部端口使用 `npc.ip.axi.Axi4WriteMasterIO` 或公共 SPMV 独立端口包装，禁止新增私有 AXI
Bundle。若读写共享同一 HBM PC，需要使用统一 AXI arbiter/读写 master 合同，不能手连信号。

## 建议模块与文件落点

```text
npc/chisel/accelerators/spmv/scala/l1/cuperflow/
  SpmvCuperflowYBank.scala
  SpmvCuperflowYAccumulator.scala
  SpmvCuperflowYWriteback.scala
  SpmvCuperflowL1Top.scala
```

顶层建议从 `SpmvCuperflowInputTop` 演进为明确的完整 accelerator 顶层，例如
`SpmvCuperflowTop`，输入乘法旧顶层仍可保留做回归，但正式 L1 Config 必须 elaboration 新顶层。
standalone V1/V2 模块继续复用，不能复制。

## Config、地址和 ABI

新增并冻结：

```text
yCacheRows
yBankCount
yMemoryPrimitive
yEpochBits
yRmwLanes = 1
yForwardDepth
axiWriteMaxOutstanding
outputAlignmentBytes
```

host ABI 增加 `rows/waveCount/outputBase/outputBytes`，并验证：

- `rows <= yCacheRows`；否则本版启动前失败。
- `outputBytes >= rows*8`。
- output 区与输入 A/X image 不重叠。
- output base 满足 AXI/profile 对齐要求。

新增正式 Simulation Config 和 U55C synthesis Config。两者共享顶层与协议，只替换统一 IP
provider/primitive。profile 必须列出 Y 容量、bank 数、FADD timing 和 write AXI 几何。

## Golden 规则

host 从原始 CSR 和输入 X 独立计算 Y。为了复现实 RTL 的确定性浮点顺序，golden 至少固定：

1. 每 chunk 内按 V2 树顺序求和。
2. 同 PC 内按编码 beat 顺序累加。
3. 16 PC 按 V3 固定树顺序横向相加。
4. wave 按编号递增累加到 Y。

有限值采用明确的 bit-exact 或 ULP/相对误差阈值；NaN 比较按分类和 payload 政策处理。报告
必须列出最大绝对误差、最大相对误差、最大 ULP 和首个失败 row，不能只给 checksum。

## RTL 断言

- `globalRow == batch*rowBatchSize+localRow` 且 `< rows <= yCacheRows`。
- 每个 `{wave,row}` 恰好进入一次 Y accumulator，wave 单调且不重复。
- Y epoch 未命中时 old value 必须为 `+0.0`。
- Y RAW 命中必须 forwarding 或 stall，不读陈旧值。
- final stream 的 row 连续，pack buffer 不重复、不跳 row。
- AXI burst 不跨 4 KiB，W beat 数、last、strb 与 AW length 一致。
- B response error 置 job error；未收到全部 response 不得 done。
- 最后短 beat 的无效 byte strobe 为 0，output buffer 外内存保持不变。

## 测试矩阵

- rows 为 0、1、7、8、9、8191、8192、8193。
- 单 wave 与多 wave 更新同一 Y row。
- 某 wave 某 row 的 contributor mask=0。
- 连续同 row Y 请求，强制 RAW forwarding/stall。
- 各种 AW/W/B 随机 backpressure 和错误 B response。
- output base 临近 4 KiB 边界、最后 partial beat 和 guard bytes。
- n512、V0 多 wave fixture、完整 thermal2。

每个用例在 output 前后放 guard region，确认 RTL 未越界写。

## 构建与验证

建议正式入口：

```bash
make -C npc rebuild config=SpmvCuperflowL1SimulationConfig
make -C npc build-host config=SpmvCuperflowL1SimulationConfig
make -C npc run config=SpmvCuperflowL1SimulationConfig mainargs=n512
make -C npc run config=SpmvCuperflowL1SimulationConfig mainargs=thermal2
make -C npc rebuild config=SpmvCuperflowL1U55cSynthesisConfig
```

Config 名是建议新增项，落地后以 catalog 中公开类为准。V4 的 FPGA 门禁只要求 construction
能生成可综合 RTL、统一 IP attachment 和初步资源报告。乘法-only 250 MHz 属于 Track M；
带 L1 的 250 MHz 闭环属于 V5。

## 可观测指标

- Y RMW request/retire、epoch miss、RAW hit/forward/stall。
- 每 bank 访问数、bank conflict 和 queue 高水位。
- final row 生成到 AXI W handshake 的延迟。
- AW burst 数、平均 burst 长度、W/B backpressure、outstanding 高水位。
- 总读/写 HBM bytes 和写回有效 byte 利用率。
- 逐级周期：input、L1、completion、horizontal、Y、writeback、drain。

## 退出条件

- RTL 真实写回完整 Y，n512、边界 fixture 和 thermal2 逐行通过 golden。
- 多 wave、最后短 Batch、最后短 AXI beat、随机 backpressure 均正确。
- checksum 只是观测值，验收使用 output buffer 中的 Y。
- RAM/AXI/FADD 全部通过 `npc.ip`，Simulation 与 U55C construction 共享同一上层 RTL。
- 综合确认 banked Y 映射，不出现 122 万深单链 URAM。
- 输出 V5 所需的 stall、bank、资源和初步时序数据。
