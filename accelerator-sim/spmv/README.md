# SPMV Host、Golden 与 Cuper 编码

本目录保留三类独立能力：CPU golden、Cuper 编码，以及由 `SpmvInputSimulationConfig` 驱动的
Verilator 输入流水。输入流水执行真实 AXI 读事务和消费端校验；Ctrl map 载入后，矩阵按 8192 列
Cuper 窗口依次执行 `X0/X1 -> mulEnable -> A0..A15`，每个窗口的 A 子区间都会驱动 Mixed-V3 FP64
乘法 IP 并对照乘积位型 checksum。暂不涉及浮点加法或 Y 写回。

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

profile 固定 `ACCELERATOR_HOST_ABI=spmv-input-report-v10` 和
`PROTOCOL_ABI=spmv-input-windowed-v9`，对应的 `abi/spmv/host.env` 使用 `HOST_FORMAT=10`。输入布局通过
`SPMV_INPUT_*` 字段传给 host：16 个 A reader、2 个 X reader、1 个 Ctrl reader、16 个消费端、X 双 beat 原子广播、
Ctrl 广播、16 个 A HBM channel、`0x80000000`/128 MiB 窗口、4 KiB channel 对齐、64/512/4 AXI 参数、
2 笔 outstanding burst，以及冻结到 `host.env` 的片上 `local_X` 窗口/副本/bank/元素位宽。
保存构造会把这些输入几何编译进 `spmv-host`；运行时同名 `SPMV_INPUT_*` 环境变量不会覆盖已冻结的 profile。

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

`cuper-a-test` 将每个 channel 的 package materialize 到独立的 4 KiB 对齐地址，并逐 beat 校验输入
数据没有改变。未绑定 construction 时使用默认输入布局；绑定 `CONSTRUCTION_PROFILE` 或
`CONSTRUCTION_DIR` 时优先读取 profile 中的 `SPMV_INPUT_*` 字段。HTML 默认生成两页并提供双向导航：

```text
build/encoding/cuper/<scale>.html    # A：HBM/channel/slot 与 RAW 调度
build/encoding/cuper/<scale>-x.html  # X：FP32 打包、batch、广播与 local_X bank 映射
```
