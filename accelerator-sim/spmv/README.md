# SPMV Host、Golden 与 Cuper 编码

本目录保留三类独立能力：CPU golden、Cuper 编码，以及由 `SpmvInputSimulationConfig` 驱动的
Verilator 输入流水。输入流水执行真实 AXI 读事务和消费端校验，但暂不执行 CSR5、SpMV 乘加或结果写回。

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
并把数据集 `b.txt` 按每拍 8 个 FP64 打包给一路 X reader。每路 A 连接一个消费端；X beat 只有在
16 个消费端都 ready 时才原子广播。满带宽 HBM 模型令全部端口的 AR ready，并在已接受 burst 后逐拍
连续返回 R；reader 通过 2 笔 outstanding burst 提前跨越 4 KiB 边界。运行时要求全部 A/X 在
cycle 0/1/2 连续完成 Q、AR 和首个 R，并检查每一路 R 到自身末拍都没有空拍，同时校验 burst 连续性、
64-byte 对齐、4 KiB 边界、beat 数和 XOR checksum。消费端当前只记录输入，不做乘加。

```bash
make -C npc build config=SpmvInputSimulationConfig
make -C npc run config=SpmvInputSimulationConfig mainargs=n65536
make -C npc build-host config=SpmvInputSimulationConfig
```

profile 固定 `ACCELERATOR_HOST_ABI=spmv-input-report-v3` 和
`PROTOCOL_ABI=spmv-input-full-bandwidth-v1`，对应的 `abi/spmv/host.env` 使用 `HOST_FORMAT=4`。输入布局通过
`SPMV_INPUT_*` 字段传给 host：16 个 A reader、1 个 X reader、16 个消费端、X 原子广播、16 个 HBM channel、
`0x80000000`/128 MiB 窗口、4 KiB channel 对齐、64/512/4 AXI 参数和 2 笔 outstanding burst。

`SpmvInputReportConfig` 独立控制报告行为：`performanceHtml` 生成报告主页，`pipelineHtml` 生成逐周期
子页且要求主页已开启。公开 Config 使用 `PerformancePipeline` preset，两项 profile 字段分别为
`SPMV_PERFORMANCE_HTML=1` 和 `SPMV_PIPELINE_HTML=1`；冻结 host 严格按这两个字段生成页面。

成功运行后的目录遵循其他 construction 的运行报告布局：

```text
npc/constructions/accelerators.spmv.SpmvInputSimulationConfig/
  runtime/<dataset>/<timestamp>-<pid>/{performance.html,pipeline.html}
```

同级 `<dataset>/latest` 指向最近一次成功写完的报告。`performance.html` 是主页，包含周期、输入吞吐、
A 通道负载和 16 个消费端的计数/checksum；`pipeline.html` 是可搜索、缩放和横向滚动的 17 输入泳道
时间线，展示 request、AXI AR、HBM R/消费和 done。

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

编码器使用 16 路 HBM、每路 8 个 64-bit slot、64 列 slice 和每 batch 128 个 slice（8192 列）。
每个 HBM channel 保留自己的 batch 边界和 beat 长度，slot 中编码 local column、row 与 FP32 value；
padding 与同一累加目标的 RAW gap 也属于 package 数据。

```bash
make -C accelerator-sim/spmv encoding-test
make -C accelerator-sim/spmv cuper-a-test mainargs=n512
make -C accelerator-sim/spmv encode mainargs=n512 ENCODING=cuper
make -C accelerator-sim/spmv encoding-html-test mainargs=n512
```

`cuper-a-test` 将每个 channel 的 package materialize 到独立的 4 KiB 对齐地址，并逐 beat 校验输入
数据没有改变。未绑定 construction 时使用默认输入布局；绑定 `CONSTRUCTION_PROFILE` 或
`CONSTRUCTION_DIR` 时优先读取 profile 中的 `SPMV_INPUT_*` 字段。HTML 报告默认写入
`build/encoding/cuper/<scale>.html`。
