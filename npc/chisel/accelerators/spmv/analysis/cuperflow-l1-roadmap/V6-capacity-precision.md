# V6：容量与精度变体

状态：未启动。条件版本，仍在 V4/V5 之后；不与 Track M 的第一次乘法时序收敛混在一起。

V6 只在 V4/V5 的完整 Y 与 FPGA 数据证明“Y cache 容量不足”或“FP64 资源/时序无法满足
目标”后启动。容量方案和精度方案必须是独立 Config、独立 ABI、独立实验，不能在默认路径
中静默降级。

## 启动条件

至少满足一项才进入 V6：

- 目标矩阵 `rows > yCacheRows`，且 V5 无法在 U55C 资源内继续扩大 banked FP64 Y。
- FP64 正式设计无法在资源/时序目标内实现，且报告已证明瓶颈来自精度相关 IP/存储。
- 论文需要系统比较 FP64、混合精度和 FP32，而不是只为绕过当前实现问题。

如果 thermal2 在约 624/960 URAM 的预算内且 250 MHz 可收敛，不应仅因“也许更大矩阵”修改
V4/V5 默认协议。

## 两条相互独立的工作线

```text
V6A: rows > yCacheRows 的 row tiling / spill
V6B: FP64、混合精度、FP32 的精度与资源对照
```

V6A 不自动改变数值格式，V6B 不自动改变 row 调度。若实验需要组合，两项分别完成后再创建
第三个显式 Config，不能用一个布尔开关形成无法辨认的混合结果。

## V6A：容量方案

### 方案 A：Row Tile 遍历全部 Wave

把全局 row 划分为不超过 `yCacheRows` 的 tile：

```text
for rowTile in rows:
  clear/new Y epoch for tile
  for wave/group in all column ranges:
    process only rows in tile
  write back final tile Y
```

优点：

- Y partial 始终片上，写回每 row 一次。
- V4 的 Y RMW 和 writeback 可直接复用，只增加 `tileBase/tileRows`。

代价：

- 每个 row tile 都要重新遍历 column group，可能重复加载同一 X。
- A package 若不是按 row tile 连续组织，可能产生更多小 range 或需要预处理重排。
- tile 切换需要 drain 全部旧 epoch，不能与未完成 wave 混用 Y bank。

必须统计 `X reload bytes`、`A reread bytes`、descriptor 数和 tile 切换周期。

### 方案 B：Group-Major Y Spill

保持当前每 PC/group 的 X 只加载一次：

```text
for wave/group:
  process all row batches
  load old Y partial tile from HBM
  add current wave partial
  spill updated Y partial to HBM
```

优点：

- 保留 Cuperflow “每 group/PC 一次 X 装载”的主要特性。
- A/X 顺序更接近 V4/V5。

代价：

- 非最后 wave 对 Y 产生额外 read + write 带宽。
- 需要 AXI read/write 仲裁、Y spill buffer 和原子 job 恢复语义。
- HBM 中间 Y 不再只是最终输出，host ABI 和地址空间更复杂。

必须统计 `2 * rows * (waveCount-1) * 8` 量级的理论 Y 流量与实测 burst 效率。

### 容量方案选择

对同一超容量矩阵比较：

| 指标 | Row Tile | Group-Major Spill |
| --- | ---: | ---: |
| X load bytes/次数 | 必测 | 必测 |
| A read bytes | 必测 | 必测 |
| Y read/write bytes | 必测 | 必测 |
| 总周期与 HBM stall | 必测 | 必测 |
| URAM/BRAM/LUT | 必测 | 必测 |
| 250 MHz WNS/TNS | 必测 | 必测 |

不能只按理论 bytes 选方案；HBM channel 独占关系、burst 连续性和 PC 不均衡都要通过真实 RTL
计数器验证。

## V6A 协议与模块

host work descriptor 新增：

```text
rowTileBase
rowTileRows
rowTileCount
spillBase / spillBytes（仅 spill Config）
```

A slot 的 `localRow` 仍是 Batch 内 13 bit，不因全局 rows 变大而扩宽。地址恢复为：

```text
globalRow = rowTileBase + batchInTile*rowBatchSize + localRow
```

若沿用全局 batchId，则必须防止乘法溢出并在 profile 记录位宽。Y cache 的 bank/index 只接收
tile-local row；writeback 再加 tile base。

建议新增而不是隐式改默认 Config：

```text
SpmvCuperflowL1RowTiledSimulationConfig
SpmvCuperflowL1RowTiledU55cSynthesisConfig
SpmvCuperflowL1SpillSimulationConfig
SpmvCuperflowL1SpillU55cSynthesisConfig
```

V4/V5 默认 Config 在 `rows > yCacheRows` 时仍应启动失败，不能自动选策略。

## V6B：精度变体

至少比较三个明确模式：

| 模式 | X | A | Multiply | L1/Horizontal/Y |
| --- | --- | --- | --- | --- |
| FP64 baseline | FP64 | FP32 promote | FP64 | FP64 |
| Mixed | FP32 或 FP64 | FP32 | FP32 multiply | FP64 accumulate |
| FP32 | FP32 | FP32 | FP32 | FP32 |

具体 mixed 定义必须在名字和 profile 中写清，不能只写 `mixed=true`。例如
`fp32-mul-fp64-acc-v1` 与 `fp64-mul-fp64-acc-v1` 是不同 protocol/precision ABI。

### 数据格式影响

- FP32 X 时 512-bit beat 为 16 个元素，map 的 `xWords/xBeats/segment count` 单位必须明确。
- local-X RAM 的 line packing、replica 和读 lane 接口相应改变。
- FP32 NaN marker 即使位数足够，也不能复用旧 marker 而不升 ABI；正式多段 X 当前使用 map
  descriptor，不应重新引入散写 marker。
- A slot 仍含 FP32 matrix value；其余 v6 控制位不变，除非另立 slot version。
- output Y 的元素位宽决定 AXI pack/strb 和 host buffer ABI。

### 统一算术 IP

所有 FP32/混合算术继续扩展 `npc.ip.arithmetic`：

- simulation provider 与 Xilinx adapter 使用同 req/resp/tag/backpressure 合同。
- profile 分别冻结 multiply/add latency、II、rounding 和 subnormal 行为。
- 不允许在 mixed Config 中让 host 先算 FP64 product 再喂给 RTL。

若 mixed 路径需要 FP32 product promote 到 FP64，转换也应走统一算术/转换 IP 合同，而不是
accelerator 私有 vendor BlackBox。

## 精度验证

每个精度 Config 保存：

- 完整输出 Y。
- 相对 FP64 CPU reference 的最大/平均绝对误差、相对误差和 ULP（适用时）。
- SpMV 迭代算法的收敛曲线、最终 residual 和达到阈值的迭代次数。
- NaN、Inf、overflow、underflow 和 subnormal 计数。
- Verilator 周期、HBM bytes、URAM/DSP/LUT/FF、WNS/TNS。

“算法能收敛”不能替代单次 SpMV Y 对照；两者都要报告。数据集至少覆盖均衡稠密、均衡稀疏、
不均衡稀疏和不均衡较密四类，避免只以 thermal2 得出普遍结论。

## 状态、背压与错误

### Row tile

- tile 切换前旧 tile 的 input/L1/completion/horizontal/Y/writeback 全部 drain。
- `rowTileRows=0`、tile 越过 rows、tile 重叠/缺口为协议错误。
- 最后短 tile 的 Batch/AXI 尾部使用真实有效长度。
- output backpressure 可阻止 tile 释放，但不能覆盖 Y cache。

### Spill

- 读取旧 partial 的 AXI error 终止作业，不能按 0 继续。
- 当前 wave 更新完成且全部 B response 返回后才能把 spill epoch 标为有效。
- 最后 wave 才写 final output；中间 buffer 与 final buffer 是否共址由 ABI 明确。
- reset/中断后的 spill image 不承诺可恢复，除非另做事务日志；第一版直接让作业失败重启。

### 精度

- provider `illegal`、格式不匹配或非预期 NaN 传播置 error 并记录首个 row。
- 不同精度 package/host/RTL ABI 不匹配时启动前拒绝。

## 测试矩阵

容量测试：

- `rows = yCacheRows-1, yCacheRows, yCacheRows+1`。
- 2 个完整 tile 加最后 1/7/8191 行短 tile。
- 单 wave和多 wave，空 Batch和空 contributor row。
- AXI read/write 随机背压、4 KiB 边界和 spill response error。
- 至少一个真实超容量 SuiteSparse 矩阵。

精度测试：

- V4/V5 全部小型 fixture 和 n512/thermal2。
- 大动态范围、正负抵消、subnormal、overflow 和 NaN/Inf 定向向量。
- 目标迭代算法的多轮收敛测试。

所有测试仍使用 Config -> Verilator -> host -> golden。软件 reference 可高精度计算，但不能
代替对应精度的 RTL 算术路径。

## 构建与报告

每个变体使用独立公开 Config，例如：

```bash
make -C npc rebuild config=SpmvCuperflowL1RowTiledSimulationConfig
make -C npc run config=SpmvCuperflowL1RowTiledSimulationConfig mainargs=<large-dataset>

make -C npc rebuild config=SpmvCuperflowL1MixedSimulationConfig
make -C npc run config=SpmvCuperflowL1MixedSimulationConfig mainargs=thermal2

make -C npc rebuild config=<对应 U55C Synthesis Config>
```

报告表必须把容量策略和精度策略分列，包含 protocol ABI、数据位宽和有效工作量，禁止直接
比较不同 nnz/package 或不同误差目标的裸周期。

## 退出条件

V6A：

- 至少一种策略对 `rows > yCacheRows` 的矩阵产生完整正确 Y。
- 所有额外 X/A/Y HBM 流量由 RTL 计数器闭合。
- 默认 V4/V5 Config 行为不变，超容量策略不会被静默启用。
- 对候选策略给出 Verilator、综合资源和 250 MHz 结果，明确最终取舍。

V6B：

- 每个精度模式有独立 Config/profile/protocol ABI 和统一 IP provider。
- 单次 SpMV golden 与迭代收敛报告齐全。
- 性能、资源、时序和误差在相同数据集上可复现。
- 论文默认精度由数据决定；若保留 FP64，明确说明候选低精度未替换它的原因。

V6 完成不等于所有变体都成为正式默认路径。最终只保留有测量依据、可综合并通过完整 golden
的 Config，其余作为实验对照记录。
