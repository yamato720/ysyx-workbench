# SPMV Host、Golden 与 Cuper 编码

本目录保留三类独立能力：CPU golden、Cuper 编码，以及由 `SpmvInputSimulationConfig` 驱动的
Verilator 输入流水。输入流水执行真实 AXI 读事务和消费端校验；Ctrl map 载入后，矩阵按 8192 列
Cuper 窗口依次执行 `X0/X1 -> mulEnable -> A0..A15`，每个窗口的 A 子区间都会驱动 Mixed-V3 FP64
乘法 IP 并对照乘积位型 checksum。暂不涉及浮点加法或 Y 写回。

输入事务仿真的实现、接口与 HTML 回归检查统一位于 `input/`；顶层 `host.cpp` 仅负责数据集选择、
编码、CPU golden 与各仿真入口的编排。

共享 CSR 数据位于 `accelerator-sim/data`。首次使用可执行：

```bash
make -C accelerator-sim/data
```

## 输入流水仿真

正式 Config 的构造流程是：

```text
elaborate -> verilator -> accelerator-host
```

它生成 `SpmvInputTop.sv` 和 `VSpmvInputTop`。host 将 Cuper 编码后的矩阵分发给 16 路 A reader，
并把数据集 `b.txt` 按每拍 8 个 FP64 打包，再按全局 beat 偶/奇序号条带化到 X0/X1。
Cuper map（1 拍 header + 每窗 16 路 pointer）走独立的控制面 HBM，并广播到全部消费端。
每路 A 连接一个消费端；两路 X 在同一拍向 16 个消费端原子广播，合计交付 16 个 FP64/cycle；若总 beat 数为奇数，
X0 最后单独交付一个尾 beat。广播后的 X 同时写入 16 个 Cuper PE 各自的片上 `local_X`：每个 PE 有
8192 元素 FP64 窗口（与 Cuper A 分片同宽）、4 份副本、按 16 个写通道分 bank。作业顺序是
`Ctrl -> X0/X1 -> mulEnable -> A0..A15`。满带宽 HBM 模型令全部端口的 AR ready；Ctrl 和 X
在已接受 burst 后逐拍连续返回 R，reader 通过 2 笔 outstanding burst 提前跨越 4 KiB 边界。
`mulReady` 在 `mulEnable` 后立即拉高，此时 host 放行 16 路 A 请求。A 的唯一一次读取同时送给消费端
做 checksum，并送给 8-lane 引擎做 Mixed-V3 FP64 乘法；引擎每拍接受一个 A beat、下一拍并行发射有效 lane，
A 可以被引擎背压，不要求 R 连续，但仍校验 burst 连续性、
64-byte 对齐、4 KiB 边界、beat 数和 XOR checksum。FP64 乘法使用公共算术 IP `req/resp` 接口，默认
latency=4、II=1、response FIFO=4，并按 channel 0..15、lane 0..7 的顺序对照 golden。

```bash
make -C npc build config=SpmvInputSimulationConfig
make -C npc run config=SpmvInputSimulationConfig mainargs=n65536
make -C npc build-host config=SpmvInputSimulationConfig
```

profile 固定 `ACCELERATOR_HOST_ABI=spmv-input-report-v12` 和
`PROTOCOL_ABI=spmv-input-windowed-v11`，对应的 `abi/spmv/host.env` 使用 `HOST_FORMAT=12`。输入布局通过
`SPMV_INPUT_*` 字段传给 host：16 个 A reader、2 个 X reader、1 个 Ctrl reader、16 个消费端、X 双 beat 原子广播、
Ctrl 广播、16 个 A HBM channel、`0x80000000`/128 MiB 窗口、4 KiB channel 对齐、64/512/4 AXI 参数、
2 笔 outstanding burst，以及冻结到 `host.env` 的片上 `local_X` 窗口/副本/bank/元素位宽。
保存构造会把这些输入几何编译进 `spmv-host`；运行时同名 `SPMV_INPUT_*` 环境变量不会覆盖已冻结的 profile。

Cuper A 使用不兼容的 `cuper-a-slot-v3`：`localColumn[63:51] | tag[50:48] |
row[47:32] | fp32[31:0]`。`row` 是直接 CSR 行标，因而单次编码支持 `0..65535` 行；它不再从
HBM channel、PE 或 lane 反推。矩阵项仍沿用原 Cuper 的行到 PE 映射和 RAW 排程；排程完成后，预处理器
在每条 `(batch, PE)` 时间流上用 8 项 LRU 分配 `tag`：驻留的同一行复用原上下文，空闲时取最小编号，
占满后换出最久未使用的行。同一 `tag` 随后出现不同 `row`，表示旧行片段退休和新行片段开始。
当前乘法 RTL 不解释累加上下文，`tag` 不参与 X 读取或 FMUL 发射，只随乘法响应透明传递，因此该编码不改变
现有 Chisel 的控制和时序。固定 beat 产生的空槽不参与上下文分配，仍编码为全零 slot；它会读取 `X[0]`
并参与 FMUL，其位置仅以 host/report 带外元数据记录。
`SPMV_CUPER_SLOT_*` 与乘法时序一起冻结，不能用新 host 解读旧构造。

`SpmvInputReportConfig` 独立控制报告行为：`performanceHtml` 生成报告主页，`pipelineHtml` 生成输入与计算
两个逐周期子页且要求主页已开启。公开 Config 使用 `PerformancePipeline` preset，两项 profile 字段分别为
`SPMV_PERFORMANCE_HTML=1` 和 `SPMV_PIPELINE_HTML=1`；冻结 host 严格按这两个字段生成页面。

成功运行后的目录遵循其他 construction 的运行报告布局：

```text
npc/constructions/accelerators.spmv.SpmvInputSimulationConfig/
  runtime/<dataset>/<timestamp>-<pid>/{performance.html,input-pipeline.html,timing-pipeline.html}
```

同级 `<dataset>/latest` 指向最近一次成功写完的报告。`performance.html` 是主页，包含周期、输入吞吐、
A 通道负载和 16 个消费端的计数/checksum；`input-pipeline.html` 是可搜索、缩放和横向滚动的 19 输入
泳道时间线，按 Ctrl、X0/X1、A0..A15 展示 request、AXI AR、HBM R/消费和 done。
`timing-pipeline.html` 是 FP64 乘法计算专用统计页：以连续 A beat 接受的向量 II=1 作为主判据，
并显示每拍有效/padding/local-X/请求/响应 lane mask、最大/平均在飞数和逐 lane 响应延迟。
页面下方只保留 A beat、slot mask、local X read、FMUL req/resp、IP 在飞和 A beat 空拍的可滑动
计算窗口；多窗口作业可选择 Cuper 窗口，窗口间的 X 装载不计为计算气泡。

## CPU golden

独立 host 读取 `row_ptr.txt`、`col_idx.txt`、`values.txt` 和 `b.txt`，计算 FP64 `Y=A*X` 并写入
`build/golden/<scale>.txt`：

```bash
make -C accelerator-sim/spmv build-host
make -C accelerator-sim/spmv run mainargs=n512
```

数据根目录默认是 `accelerator-sim/data`，可用 `ACCELERATOR_DATA_ROOT=/path/to/data` 覆盖。
U55C resource-probe 的 `build-host` 与全局 `run` 复用这条 CPU golden 路径，不要求先完成 RTL 或
xclbin 构造。

## Cuper 编码

编码器同时描述 A 和 X。A 使用 16 路 HBM、每路 8 个 64-bit slot、64 列 slice 和每 batch
128 个 slice（8192 列）。每个 HBM channel 保留自己的 batch 边界和 beat 长度，slot 中编码
local column、row 与 FP32 value；padding 与同一累加目标的 RAW gap 也属于 package 数据。

X 在 host/HBM 中不改变列顺序：`b.txt` 的 FP64 元素按原列号转换为 FP32，再按每个 512-bit beat
16 个元素的 `float_v16` 打包，HBM 分配长度按 1024 个元素对齐。进入 Cuper 后，X 按同一个 8192 列
batch 切分，沿 16 个 Core 串接广播；每个 Core 把当前 batch 复制到 4 份 `local_X`，并按
`localColumn % 8` 做 cyclic bank 映射，为 8 个矩阵 lane 提供并行读取。

这里的 X 编码页复现原版 Cuper kernel 的 FP32 `float_v16` 单流格式；本地 transaction-input RTL
则保留 `b.txt` 的 FP64，每个 512-bit beat 含 8 个元素，并将连续全局 beat 按偶/奇序号分别放入
两路 X HBM。两路同周期广播后，消费顺序仍等价于原始列序。

原版 Cuper 源码中，`SpElement_list_ptr_Loader`、`Vector_Loader` 和 16 个 `Matrix_Loader` 是并发
task，并不约束 HBM 必须串行完成。每个 Core 的阻塞消费顺序则是确定的：先从 `PE_Param` 读取四个
全局参数和本轮 `SpElement_list_ptr[i]`，再接收并转发当前 batch 的 X、填满 `local_X`，随后读取
`SpElement_list_ptr[i+1]`，最后消费区间 `[start, end)` 的 A beat 并计算。因此更精确的表述是
“batch map 指针夹在 X 两侧，X 缓存完成后才消费 A 并计算”，而不是“完整 map 全部传完后才传 X”。

```bash
make -C accelerator-sim/spmv encoding-test
make -C accelerator-sim/spmv cuper-a-test mainargs=n512
make -C accelerator-sim/spmv encode mainargs=n512 ENCODING=cuper
make -C accelerator-sim/spmv encoding-html-test mainargs=n512
```

`cuperflow` 是沿行方向划分 A batch 的实验编码：每个 row batch 内按行 nnz 做稳定的最长行优先
负载均衡，把原始 CSR row 映射到物理 row、PE 和 lane。slot 的 `localRow` 表示物理 row，package
额外保存 `physicalToOriginalRows`，计算结果回写 CSR 时必须使用该映射恢复原始行顺序；X 仍按列顺序
编码，A 的每个 slice 则沿对应 lane 连续打包。默认启用 row 重排，也可通过
`CuperflowConfig::rowReorder = false` 生成 identity 映射用于对照。

column-slice 另外按 `sliceGroupSize` 组织 HBM 独占的 X range。默认值会根据 column-slice 数量自动选择，保证
有足够的 group 覆盖 16 路 HBM，同时尽量增大每个 range；单个 range 不超过 8192 个 X 元素。group `g`
归属 HBM `g % hbmChannelCount`，每路 HBM 的 X payload 与范围边界都会写入 package，因而同一路加载到
BRAM 的 X 可以持续服务本路对应的 slice group。这里仍只完成 A/X 预处理和 ownership 元数据，跨 HBM 的
部分和归约留给后续硬件；现有 row-to-PE 编码不在此处改变。

`cuperflow` 的 A slot 位域把 `[63:51]` 定义为 `groupColumn`，即当前 slice group 内的列偏移，而不是
单个 slice 内的列偏移。由于一个 group 最多覆盖 8192 列，它恰好可以放入 13-bit 位域；消费一个 group
时由 `groupFirstColumn + groupColumn` 得到全局列号。A 的指针只保留
`(rowBatch, sliceGroup, lane)` 的连续 `[begin, end)`，不再保存百万级的
`(rowBatch, columnSlice, lane)` 边界表；这张 group 指针表既适合 host 仿真，也适合后续映射到 FPGA 的
BRAM/寄存器表。

### FP64 灵活 X

`cuperflow` 的 X 预处理支持通过 C++ 宏手动选择两条路径：

```bash
# 默认：A package 收集实际列，生成带地址 marker 的灵活 X
make -C accelerator-sim/spmv cuperflow-encoding-html-test mainargs=n65536

# 对列使用较密集的矩阵切回连续满载 X
CUPERFLOW_ENABLE_FLEX_X=0 make -C accelerator-sim/spmv \
  cuperflow-encoding-html-test mainargs=n65536
```

宏开启时，A 编码遍历会在 `CuperflowPackage::xUsedColumnsByGroup` 中收集每个 sliceGroup 的实际列，
X range 只写入这些列。普通 FP64 word 保持原始 64-bit IEEE-754 位型；发生跳跃时插入一个 64-bit
quiet NaN 地址 token：`sign=0`、`exponent=0x7ff`、`quiet=1`、`opcode=001`、`magic=0x1a5a5`，
低 13 位为该 group 内的 BRAM 地址。比如列 `1,2,6,7` 编码为：

```text
ADDR(1), X1, X2, ADDR(6), X6, X7
```

输入 X 的 NaN 和 Inf 会被预处理器拒绝，以避免与地址 token 混淆。没有 A package 信息的旧
`encodeVector(input, config)` 接口始终走连续 X 回退路径；host 的 `cuperflow-encode` 会使用联合
`encodeVector(input, matrixPackage)` 接口。`hbmBeats` 仍保留连续 FP64 规范副本，实际 HBM range
的 token/marker 数量另存于 `encodedXRanges` 和 X 统计字段中。

该方案不插入软件 padding；剩余零填充只来自一个 512-bit beat 内较短 lane 的物理尾部空槽。可以用
下面的入口生成 A/X 两页 HTML，并在报告中查看 physical row 与原始 row 的对应关系：

`cuperflow` 的 X 使用独立的 FP64 beat：每个 beat 包含 8 个 64-bit IEEE-754 原始位模式，A beat
与 X beat 不混合。65536 个 X 元素对应 8192 个 X beat；每个 4096 元素的 HBM range 对应 512 个
X beat。

```bash
make -C accelerator-sim/spmv cuperflow-encoding-html-test mainargs=n65536
```

`cuper-a-test` 将每个 channel 的 package materialize 到独立的 4 KiB 对齐地址，并逐 beat 校验输入
数据没有改变。未绑定 construction 时使用默认输入布局；绑定 `CONSTRUCTION_PROFILE` 或
`CONSTRUCTION_DIR` 时优先读取 profile 中的 `SPMV_INPUT_*` 字段。HTML 默认生成两页并提供双向导航：

只需要测预处理时间和负载分布时，不生成 HTML：

```bash
make -C accelerator-sim/spmv cuperflow-encode-stats mainargs=mad_low_density_balanced
```

该入口分别输出数据加载、A 编码、X 编码耗时，以及所有 HBM 的 beat 数、实际矩阵 slot 数、PE
slot 数和最差 row batch 的 beat 差值。`hbm_beats`、`hbm_matrix_slots`、`pe_matrix_slots`
三项按 channel/lane 编号顺序输出，适合直接保存为基准测试日志。

```text
build/encoding/cuperflow/<scale>.html    # A：HBM/channel/slot 与 RAW 调度
build/encoding/cuperflow/<scale>-x.html  # X：FP64 beat、batch、HBM range 与 local_X bank 映射
```

### Cuperflow 时序与吞吐报告

可以在同一套 Cuperflow 编码 package 上生成 group-major 的周期模型报告：

```bash
make -C accelerator-sim/spmv cuperflow-timing-html-test mainargs=n65536
```

报告位于 `build/encoding/cuperflow/<scale>-timing.html`。它按固定的
`sliceGroup -> batch` 顺序记录 X preload、8-lane X decoder、packed local-X 写入、全局
`globalXReady` barrier、A beat 和 FP64 FMUL 响应区间；同一 sliceGroup 的后续 batch 会显示为
X reuse。总览同时给出 16 PC × 8 lane 的物理峰值吞吐、X token 吞吐、有效 nnz 利用率和尾部
物理 slot 数。模型把 owner HBM 的 X range 作为并行复制到各 PC local_X 的逻辑源，报告中的
`X source beats` 是源流数量，16 个 decoder 的墙钟吞吐则按并行配置统计。该页面是独立 C++
周期模型，不冒充 `SpmvCuperflowInputTop` 的 Verilator 端口仿真；真实 RTL 接口接入后可以复用
这套 work 级报告字段。

连续满载 X 可以用同一个入口对照：

```bash
CUPERFLOW_ENABLE_FLEX_X=0 make -C accelerator-sim/spmv cuperflow-timing-html-test mainargs=n65536
```

### HiSpMV 负载均衡对照

`hispmv-preprocess-benchmark` 是从 `ResearchProject/02-architecture-papers/HiSpMV/upstream/HiSpMV-16`
host 侧移植的纯 C++ 预处理模型，不依赖 TAPA 或 Vitis。它保留 HiSpMV 的 `16 HBM / 128 PE / 8 PE
per HBM / II_DIST=5 / PADDING=1 / 8192-column window` 参数：每个 tile 内先按 row nnz 做无 row sharing
和 `II_DIST` interleave 负载估计，再按原算法移除重行进行 128-PE row sharing，最后估算 tree-adder 布局。
输出同时调用当前 Cuperflow A encoder，因此可以直接比较逻辑 imbalance、物理 HBM slot 容量和 padding：

```bash
make -C accelerator-sim/spmv hispmv-preprocess-benchmark mainargs=mad_high_density_imbalanced
make -C accelerator-sim/spmv hispmv-preprocess-benchmark mainargs=mad_low_density_balanced
```

`baseline_imbalance` 和 `tree_imbalance` 是 HiSpMV 的逻辑 PE 工作量指标；`run_len_*` 已包含
`II_DIST=5`，代表每个 HBM channel 的物理 beat 数。两者不能混为同一个利用率指标。移植依据为
`helper_functions.cpp` 中的 `balanceWorkload`、`computePEloads1/2`、`prepareAmtx` 和 `tileCSRMatrix`。
