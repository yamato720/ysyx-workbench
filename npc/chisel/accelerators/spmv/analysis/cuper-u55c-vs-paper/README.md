# Cuper Jacobi 改版与原论文性能对比

更新日期：2026-08-09

## 结论摘要

当前 `Project-XPlus/DLC/Cuper-jacobi-iteration` 已经完成了有价值的功能复现和协议探索，
但还不能称为达到原论文 Cuper 的性能水平。

- 原论文 Cuper 在 U280 上使用 16 路矩阵 HBM、16 个 core、每 core 8 个 PE，共 128 个
  FP32 PE；DATA clock 为 205 MHz，18 个 HBM channel 的总带宽口径为 258 GB/s。
- U55C 当前 `strip16` 同样有 16 路矩阵 HBM 和 128 个 PE，DATA clock 为 207 MHz，完整
  `thermal2` 达到 `13.2865 GFLOP/s`。它约为论文 12 矩阵反推几何平均
  `22.1644 GFLOP/s` 的 `59.95%`。
- 但是 `strip16` 的 routed timing 严重未收敛：WNS `-1.488 ns`、TNS
  `-24182.635 ns`、setup failing endpoints `62619`。因此 `13.2865 GFLOP/s` 只能作为
  “已上板跑通的实验峰值”，不能作为可签核、可稳定复现的性能基线。
- timing-clean 的 8-HBM `original8` 和 `strip8` 在相同 150 MHz 下分别为
  `6.2544` 和 `6.3225 GFLOP/s`。去掉 per-HBM 尾部 padding 后只快 `1.09%`，直接证明
  padding 不是当前主瓶颈。
- 去更多空洞的 `lanereal16` 将 matrix beats 减少 `16.17%`，但 accumulator II 从
  `2` 退化到 `5`，性能降到 `7.2848 GFLOP/s`。`compact16` 虽减少 `13.54%` beats，
  但动态 lane 分发使性能降到 `0.5667 GFLOP/s`。
- 完整 Jacobi 的 timing-clean bitstream 已能正确跑完整 `thermal2`。单轮时间为
  `4.806930 ms`；按非对角矩阵 `R` 的 `2 * nnz / time` 折算约为
  `3.0590 GFLOP/s`，但该时间还包含 Jacobi update 和 X 写回，不能与论文 single SpMV
  直接比较。
- U280 换成 U55C 不是主要性能解释。两者完整器件资源规模相同，HBM 标称带宽也同属
  约 460 GB/s；当前更明确的限制是 DATA clock、时序收敛、accumulator II、lane 调度和
  连续供数。

更准确的定位是：**功能复现已经完成，`thermal2` 上的实验峰值达到论文 12 矩阵几何平均
的约 60%，但 timing-clean、同数据集的论文级性能复现尚未完成。**

## 1. 分析范围和版本边界

本报告读取了以下内容：

- 原论文：[Cuper.pdf](../../refer/Cuper/Cuper.pdf)
- 原版源码：[upstream/src/Cuper.cpp](../../refer/Cuper/upstream/src/Cuper.cpp)
- 改版源码：`/home/pyx/project-x/Project-XPlus/DLC/Cuper-jacobi-iteration`
- single SpMV 实验报告：
  `/home/pyx/project-x/Project-XPlus/395bitstream/cuper_spmv_optimization_20260618.html`
- 综合汇总：
  `/home/pyx/project-x/Project-XPlus/395bitstream/cuper_spmv_u55c_compare_20260524.html`
- Jacobi 归档：
  `/home/pyx/project-x/Project-XPlus/docs/bitstream_summaries/2026-06-10-cuper-tapa-jacobi-iteration`
- SpMV 优化归档：
  `/home/pyx/project-x/Project-XPlus/docs/bitstream_summaries/2026-05-28-cuper-tapa-spmv-single-optimization`
- U55C 资源口径：`/home/pyx/project-x/Project-XPlus/U55C_RESOURCE_TABLE.md`

分析时 `Project-XPlus` 为 HEAD `2b41b0e`，工作区有 20 项未提交变化。本文的性能结论
以归档 bitstream UUID、`.xclbin.info` 和上板日志汇总为准，不假设当前脏工作区源码与
归档 bitstream 完全一致。

## 2. Jacobi 改版实际做了什么

### 2.1 完整 Jacobi 数据流

host 将矩阵拆成：

$$
A = D + R
$$

并生成 `diag_inv[i] = 1 / A[i,i]`。kernel 的 vector loader 读取旧解时先取负，使
Cuper service 直接计算：

$$
-R x^{(k)}
$$

后级完成：

$$
x^{(k+1)} = D^{-1}(b - R x^{(k)})
$$

当前归档成功版使用单个 `X` buffer 原地更新。master controller 每轮依次触发矩阵读取、
SpMV、update 和 X HBM 写回，收到整轮写响应后才开始下一轮。这样减少了一个 X buffer，
也避免了轮次间读写重叠造成的数据相关问题。

成功 bitstream 为：

| 项目 | 值 |
| --- | --- |
| 文件 | `cuper-tapa-jacobi-u55c-20260615-demo.xclbin` |
| UUID | `c37ecdbf-92ab-5d06-11bd-e2f9edc7f720` |
| DATA / KERNEL / HBM | `150 / 500 / 450 MHz` |
| routed timing | WNS `0.003 ns`，TNS `0.000 ns`，0 failing endpoint |
| 功能范围 | `MAX_ITERS=1` 到完整 `thermal2` 通过；固定轮数完整运行通过 |

需要注意，成功归档版是固定轮数实现。`Status=1` 表示到达 `MAX_ITERS`，不是 FPGA 内部
根据 `tau` 提前收敛。

### 2.2 padding 优化并不是一种实现

目前至少有四种不同边界，不能统称为“已经把 padding 去掉”：

| 版本 | 做法 | 完整 `thermal2` beats 节省 | 主要代价 |
| --- | --- | ---: | --- |
| `strip16` | 每个 HBM 使用独立 batch 边界，去掉跨 HBM 尾部 padding | `5.91%` | 数据格式和 accumulator 基本不变 |
| `compact16` | beat 内动态紧凑填充，携带原 lane tag | `13.54%` | 动态 lane 解码、分发和回写串行化 |
| `lanereal16` | 固定 lane，每 batch 只发送真实元素 | `16.17%` | accumulator II 从 `2` 退到 `5` |
| `lane-static real/stream` | 固定 lane，跨 batch 合并真实元素 | 预计 `21.78%` | 目前只是 pack profile 上限，没有成功硬件性能数据 |

在完整 `thermal2` 上，旧格式密度为 `78.09%`，`strip16` 为 `83.00%`，
compact-scheduled 为 `90.40%`。profile 估计 `lane-static real/stream` 可达到
`99.83%`，但这仍需要新的 per-lane/per-HBM 长度协议和持续供数硬件。

## 3. 原论文 Cuper 基线

论文 DATE 2024 的 Cuper 配置如下：

| 项目 | 原论文 Cuper |
| --- | ---: |
| FPGA | Xilinx Alveo U280 |
| 工具 | Vitis 2021.2 / Vivado HLS |
| 数值类型 | FP32 |
| DATA clock | `205 MHz` |
| HBM channel | 18 个，总带宽 `258 GB/s` |
| 矩阵 HBM channel | 16 个 |
| 计算结构 | 16 core x 8 PE = 128 PE |
| 运行次数 | XRT 测量 50 次平均执行时间 |
| LUT | `307K (26.4%)` |
| FF | `314K (13.5%)` |
| DSP | `920 (10.8%)` |
| BRAM | `1024 (29.2%)`，论文口径 |
| URAM | `512 (53.3%)` |

论文正文对吞吐公式有一句 `#non-zeros / execution time`，但论文指标是 GFLOP/s，且仓库内
原版 Cuper host 实际使用：

```text
GFLOP/s = 2 * nnz / kernel_time
```

本地所有表格也使用同一公式，所以本文按 `2 * nnz / time` 比较。

论文没有给 `thermal2` 数据。其 FPGA 对比只使用 12 个其它 SuiteSparse 矩阵，因此不
存在严格的同矩阵直接对比。下表用论文表 IV 的 bandwidth efficiency 乘以
`258 GB/s` 反推 Cuper 吞吐；原表只保留两位小数，因此这些值是近似值。

| 论文矩阵 | Cuper GFLOP/s |
| --- | ---: |
| `sit100` | 16.9738 |
| `olafu` | 37.3919 |
| `Si10H16` | 27.3661 |
| `finance256` | 16.3159 |
| `3dtube` | 18.1761 |
| `crankseg_2` | 37.2036 |
| `Si34H36` | 33.5581 |
| `mycielskian17` | 25.5007 |
| `Ga19As19H42` | 35.8775 |
| `troll` | 33.2536 |
| `web-BerkStan` | 13.9862 |
| `webbase-1M` | 5.1368 |
| **12 点几何平均** | **22.1644** |

论文另报告在 2757 个 SuiteSparse 矩阵上的最大吞吐为 `46.74 GFLOP/s`。该最大值不是
12 矩阵比较表的同一统计量，不能拿来当平均性能。

## 4. U55C 的 `thermal2` 实测

完整 `thermal2` 为 `N=1,228,045`、`nnz=8,580,313`。以下 single SpMV 均使用
kernel/TAPA reported time，不包含 host 预处理、xclbin load、BO setup、H2D 或 D2H。

| 版本 | HBM / DATA clock | 时间 | GFLOP/s | 论文 12 点几何平均占比 | timing / 结论 |
| --- | --- | ---: | ---: | ---: | --- |
| `strip16` | 16 matrix HBM / 207 MHz | 1.291580 ms | **13.2865** | **59.95%** | WNS `-1.488 ns`，实验峰值，不可签核 |
| Cuper-compatible one-shot | 16 matrix HBM / 147 MHz | 1.781541 ms | 9.6325 | 43.46% | 最接近标准 Cuper one-shot 数据流 |
| `lanereal16` | 16 matrix HBM / 197 MHz | 2.355660 ms | 7.2848 | 32.87% | WNS `-0.073 ns`，accumulator II=5 |
| `strip8` | 8 matrix HBM / 150 MHz | 2.714200 ms | 6.3225 | 28.53% | timing clean，去尾部 padding |
| `original8` | 8 matrix HBM / 150 MHz | 2.743790 ms | 6.2544 | 28.22% | timing clean，8-HBM 基线 |
| `compact16` | 16 matrix HBM / 200 MHz | 30.280400 ms | 0.5667 | 2.56% | 动态 lane 路径严重退化 |

不能只看第一行就宣布达到论文的 60%。`strip16` 有 62619 个 setup failing endpoints，
硬件虽然在当前板上跑通，但 PVT 变化后没有时序保证。工程上更可信的已签核数字是
`original8/strip8`，但它们只有论文一半的矩阵通道且时钟只有 150 MHz。

## 5. 频率、通道和数据流归一化

### 5.1 `strip16` 与论文几乎不需要频率/通道归一化

论文和 `strip16` 都是 16 个矩阵 HBM channel、16 x 8 PE。DATA clock 分别为
205 MHz 和 207 MHz，只差约 1%。HBM clock 的差异也只有几个百分点。因此：

```text
strip16 归一化到 205 MHz = 13.2865 * 205 / 207 = 13.1582 GFLOP/s
```

仍然只有论文 12 点几何平均的约 `59.4%`。所以即使忽略时序违例，当前差距也不能主要
解释为 U280 -> U55C 或主频下降。

若以 128 PE 每周期一个乘法和一次累加估计计算峰值：

```text
论文 205 MHz 理论峰值 = 128 * 205 MHz * 2 = 52.48 GFLOP/s
strip16 207 MHz 理论峰值 = 128 * 207 MHz * 2 = 52.99 GFLOP/s
```

论文 12 点几何平均约占该峰值 `42.23%`，12 点最高约 `71.25%`，2757 矩阵最高值约
`89.06%`；`strip16` 在 `thermal2` 上只占自身计算峰值 `25.07%`。这更像 pipeline
利用率、RAW/accumulator 和供数连续性问题。

### 5.2 one-shot 到 `strip16` 的提升主要来自时钟

原始时间看，`strip16` 比 one-shot 快 `1.379x`。但两者 DATA clock 分别为 207 MHz 和
147 MHz，时钟比为 `1.408x`：

```text
one-shot 归一化到 205 MHz = 9.6325 * 205 / 147 = 13.4330 GFLOP/s
strip16 归一化到 205 MHz = 13.2865 * 205 / 207 = 13.1582 GFLOP/s
同频 strip16 / one-shot = 0.9795
```

这个线性归一化只是近似，但足以说明不能把 raw 1.38 倍提升归功于 `5.91%` 的 padding
减少。相同 150 MHz、相同 8-HBM 的硬件对照更可靠：

```text
original8: 2.743790 ms
strip8:    2.714200 ms
speedup:   1.0109x
```

即去尾部 padding 的实测收益约 `1.09%`。

### 5.3 8-HBM timing-clean 基线的粗略外推

如果极粗略地假设通道和频率线性扩展：

```text
6.2544 * (16 / 8) * (205 / 150) = 17.0952 GFLOP/s
```

相当于论文 12 点几何平均的 `77.1%`。这只能作为“timing-clean 基础数据流并非完全失速”
的方向性证据，不能作为性能结果，因为实际 8 -> 16 路会受到 routing、crossbar、HBM
供数和 accumulator 汇聚影响，不会严格线性。

## 6. 完整 Jacobi 性能如何看

`thermal2` 每行都有一个对角元，因此：

```text
A nnz = 8,580,313
R nnz = 8,580,313 - 1,228,045 = 7,352,268
```

当前 timing-clean Jacobi bitstream 的数据为：

| 模式 | 迭代数 | kernel time | 平均每轮 | 按 R 的等效 SpMV GFLOP/s |
| --- | ---: | ---: | ---: | ---: |
| 单轮 | 1 | 4.806930 ms | 4.806930 ms | 3.0590 |
| 完整固定轮数 | 24409 | 113035 ms | 4.630874 ms | 3.1753 |

这里的“等效 SpMV GFLOP/s”只按 `2 * R.nnz / time` 计算，分母还包含：

- `b + (-R*x)` 和乘 `diag_inv`；
- 单 X buffer 的 HBM 写回；
- 每轮 command/done 控制；
- 固定轮数循环开销。

因此它适合衡量完整 Jacobi 每轮成本，不适合放进论文 single SpMV 柱状图。它比
`strip16` 的 full-A single SpMV 慢约 `4.34x`，其中还叠加了 DATA clock 从 207 MHz
降到 150 MHz 和完整 update/writeback 路径。

## 7. U280 换 U55C 的影响

不能把当前差距简单归因于 U55C 资源更少：

| 完整器件资源 | U280 | U55C 当前 XSA 元数据 |
| --- | ---: | ---: |
| LUT | 1,303,680 | 1,303,680 |
| FF | 2,607,360 | 2,607,360 |
| DSP | 9,024 | 9,024 |
| BRAM36 | 2,016 | 2,016 |
| URAM | 960 | 960 |
| HBM 标称总带宽 | 约 460 GB/s | 约 460 GB/s |

U55C platform 给用户动态区可见的资源约为 1,146,240 LUT、2,292,480 FF、8376 DSP 和
1776 BRAM36，和论文 Cuper 的利用规模相容。真正观察到的平台差异是：

- 24 路 SpMV-only 可以生成 xclbin，但 DATA 仅 141 MHz 且 WNS `-0.420 ns`；
- 32 路因为 U55C HMSS top-level master connection 用尽而无法 link；
- 16 路 `strip16` 为追求 207 MHz 产生严重时序违例；
- timing-clean Jacobi 和 8-HBM 实验最后都退到 150 MHz。

这些是 platform shell、端口数量、布局布线和当前微架构共同造成的工程约束。它们会影响
扩展到 24/32 路，但不能解释 16 路、205/207 MHz 同规格下相对论文几何平均仍有约 40%
差距。

## 8. 当前水平判断

### 已经达到

- 原 Cuper 的 sparse slice、128 PE、HBM streaming、accumulator/checker/sort 基础链路已
  复现，并扩展成可重复触发的 Jacobi service。
- 完整 `thermal2` 的 single SpMV、多轮 Jacobi 和数值校验都已有上板通过记录。
- timing-clean Jacobi full graph 已闭合，说明这不再只是软件仿真原型。
- 去 padding 的三个方向都已有定量结果，已经能排除“只要压缩 beats 就一定更快”的错误
  假设。

### 尚未达到

- 没有 timing-clean 的 16-HBM、约 205 MHz single-SpMV 性能版本。
- 没有在论文 12 个矩阵上运行 U55C bitstream，所以无法把 matrix pattern 影响与实现差距
  分离。
- `strip16` accumulator 仍为 II=2；`lanereal16` 为 II=5；更激进 OOO 路线还存在 II=14、
  II=98 或上板 timeout 等失败边界。
- lane-static real/stream 的 `99.83%` 密度仍是 host profile，不是硬件吞吐结果。

## 9. 建议的下一步

1. 首先做出 timing-clean 的 16-HBM single-SpMV 基线，目标 DATA 200--205 MHz、
   WNS >= 0，并保留和论文一致的 128 PE。没有这个基线前，不应继续用 `strip16` 的
   13.2865 GFLOP/s 做正式对外结论。
2. 下载并转换论文的 12 个矩阵，在同一个 timing-clean xclbin 上按 50 次 kernel average
   跑完。这样可以直接对比论文，而不是用 `thermal2` 对不同矩阵的几何平均。
3. 保留简单的 per-HBM strip 逻辑，因为它成本低且不会破坏固定 lane；但预期收益应按
   1% 左右而不是 38% 规划。
4. 不继续投入 `compact16` 动态 lane accumulator。后续去空洞应沿
   `lane-static real/stream`，前提是 accumulator 至少保持 II<=2，最好达到 II=1。
5. 优先解决 accumulator 的 URAM read-modify-write recurrence 和持续供数，而不是继续
   单独追求 beat 数下降。对于当前数据，II 从 2 退到 5 的损失明显大于 16% 的读取节省。
6. Jacobi 单独维护 end-to-end 指标：每轮时间、总迭代时间和解误差。不要用 Jacobi 完整轮
   时间替代 Cuper single-SpMV GFLOP/s。

## 10. 数据可信度说明

- 论文 12 点吞吐是从表 IV 的 bandwidth efficiency 反推，受原表两位小数舍入影响。
- 论文未测试 `thermal2`，所以所有“占论文几何平均多少”的数字只是跨矩阵定位，不是
  apples-to-apples 复现结论。
- 本地 single SpMV 明确使用 kernel/TAPA reported time，排除了 host 预处理和单独记录的
  数据搬运；论文写明用 XRT 测量 50 次 accelerator execution time，但没有像本地报告一样
  细分 H2D/D2H 边界。两边都没有把离线矩阵重排时间计入 SpMV GFLOP/s；Jacobi 完整轮则
  额外包含 kernel 内 update/writeback。
- `strip16`、`lanereal16` 和 `compact16` 有不同程度的时序违例；只有明确标为
  timing-clean 的结果才可视为签核基线。
- 当前 `Project-XPlus` 工作区存在未提交变化，后续复现实验必须用 UUID/SHA256 固定
  bitstream，并记录对应源码 commit 或 source diff。

## 11. U55C FP32 加法器与 RAW window

### 11.1 实际可用的 IP 配置

使用 Vivado 2022.2 对 `xcu55c-fsvh2892-2L-e` 的 `floating_point v7.1` 做了 OOC
综合。配置与现有 Cuper 一致：FP32 Add、`Speed_Optimized`、`Full_Usage`、吞吐率 1、
NonBlocking。正式扫描范围为 `c_latency=4..11`：

| IP latency | CLB LUT | FF | DSP48E2 | 200 MHz WNS | 300 MHz WNS |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 4 | 220 | 144 | 2 | `+2.215 ns` | `+0.548 ns` |
| 5 | 209 | 225 | 2 | `+2.756 ns` | `+1.089 ns` |
| 6 | 231 | 219 | 2 | `+2.948 ns` | `+1.281 ns` |
| 7 | 229 | 238 | 2 | `+3.059 ns` | `+1.392 ns` |
| 8 | 196 | 266 | 2 | `+3.394 ns` | `+1.727 ns` |
| 9 | 197 | 271 | 2 | `+3.394 ns` | `+1.727 ns` |
| 10 | 198 | 288 | 2 | `+3.394 ns` | `+1.727 ns` |
| 11 | 199 | 315 | 2 | `+3.931 ns` | `+2.264 ns` |

`c_latency=0` 也能被 IP 参数接受，但综合后 `aclk` 会被优化掉，成为纯组合加法器，不适合
当前 URAM read-modify-write 流水。`c_latency=4` 是本轮选定的最短保守配置：它在 300 MHz
OOC 估算下仍有正裕量，在当前约 200 MHz DATA clock 下有超过 2 ns 裕量。完整 xclbin 的
布局布线仍需单独签核，OOC 结果不能替代 full-design routed WNS。

结果表保存在 `u55c_fadd_sweep.csv`，复现脚本为 `u55c_fadd_sweep.tcl`。

### 11.2 为什么 window 是 7，不是 4 或 5

当前实际数据相关路径不是只有 Xilinx IP：

```text
floating_point c_latency                 4
TAPA/HLS wrapper 输入寄存器              1
SyncReadMem 读取与写回/再读边界          2
------------------------------------------
同一 accumulator target 安全 issue 间隔  7
```

旧实现是 `c_latency=11`、wrapper 总加法延迟 12，对应同一地址的静态安全间隔约 14；已有
`window14/window16` 才让 accumulator 达到 II=1 的 HLS 结果与这个推导一致。新实现把
Project-XPlus 的 IP 改为 `c_latency=4`，Chisel/RTL scoreboard 和仿真模型统一改为 5 拍，
Jacobi host 和 simulator package 的默认 `reorderWindow` 均改为 7。

另一个旧问题是 host 只以 `rowGroup` 判断冲突，但硬件实际比较的是 `(group, pong)`。
偶数行写 ping，奇数行写 pong，两套存储独立；新调度以 `(rowGroup, parity)` 为 key，HTML
中的 RAW gap 也按同一真实目标计算，不再给 ping/pong 之间插入假 padding。

如果后续 full-design 在 200 MHz 不能收敛，保守回退是 `c_latency=5`、wrapper 6 拍、
`reorderWindow=8`。不能只回退 IP 或只回退 package，IP、scoreboard、仿真模型和 host window
必须成组修改。

### 11.3 `thermal2_n65536` 的打包变化

| 指标 | 旧 window10，仅 rowGroup key | 新 window7，ping/pong key | 变化 |
| --- | ---: | ---: | ---: |
| 总 beats | 65,252 | 59,442 | `-8.90%` |
| Padding slots | 85,016 | 38,536 | `-54.67%` |
| Slot 利用率 | 83.71% | 91.90% | `+8.18 pct` |
| Packed bytes | 4,176,128 | 3,804,288 | `-8.90%` |

这只是输入工作量改善，不等价于上板性能提升。缩短加法器后还要重新生成 xclbin，检查
DATA routed WNS、accumulator stall/II 和完整 `thermal2` kernel time，才能判断最终收益。

功能回归已覆盖：Cuper package 单元测试、`n512`/`thermal2_n65536` HTML 回归、Chisel
datapath 7 组 smoke、AXI top 2 组 smoke，以及 Jacobi quick software regression。后者的
`cant` 和 `thermal2_n65536` 都是 `Error Num=0`。尚未执行的是 full xclbin link 和 U55C
上板测试。
