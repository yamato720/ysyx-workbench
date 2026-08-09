# SPMV Host、Golden 与 Cuper 编码

本目录保留三类独立能力：CPU golden、Cuper 编码/输入 smoke，以及供 FPGA resource-probe 使用的
纯软件 golden。当前本地 `SpmvInputSimulationConfig` 只构造输入顶层结构，不执行 CSR5、SpMV 计算、
HBM DPI 或性能报告。

共享 CSR 数据位于 `accelerator-sim/data`。首次使用可执行：

```bash
make -C accelerator-sim/data
```

## 输入 smoke

正式 Config 的构造流程是：

```text
elaborate -> verilator -> accelerator-host
```

它生成 `SpmvInputTop.sv` 和 `VSpmvInputTop`，host 复位后检查 16 路 A reader、1 路 X reader 的静态
接口状态：所有 reader idle，没有 AR 发射、output 或 error。该 smoke 不验证实际 HBM 读写和数值结果。

```bash
make -C npc build config=SpmvInputSimulationConfig
make -C npc run config=SpmvInputSimulationConfig
make -C npc build-host config=SpmvInputSimulationConfig
```

profile 固定 `ACCELERATOR_HOST_ABI=spmv-input-smoke-v1` 和 `PROTOCOL_ABI=spmv-input-v1`，输入布局
通过 `SPMV_INPUT_*` 字段传给 host：16 个 A reader、1 个 X reader、16 个 HBM channel、
`0x80000000`/128 MiB 窗口、4 KiB channel 对齐和 64/512/4 AXI 参数。

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
