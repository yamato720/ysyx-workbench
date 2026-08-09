# SpMV Host 与 Golden

这里保留唯一的 SpMV `main()`，并支持两种由 Config 选择的 ABI：U55C 资源探针使用独立 FP64
CPU golden；两个 `SpmvOneHbmCsr5Mul*SimulationConfig` 使用 CSR5、共享 512-bit HBM DPI、
八路 FP32 multiplier 和 Verilator。两者都读取 `accelerator-sim/data` 中的 `row_ptr.txt`、`col_idx.txt`、
`values.txt` 和 `b.txt`，输入 X 固定使用 `b.txt`。

首次使用时生成 `n512` 并下载默认 SuiteSparse 数据：

```bash
make -C accelerator-sim/data
```

构建：

```bash
make -C accelerator-sim/spmv build-host
```

同一个软件 host 已挂载到两个公开 SPMV Config；从工作区根目录可使用统一入口：

```bash
make build-host config=U55cSpmv32PcFp64X8192UramBitstreamConfig
make run config=U55cSpmv32PcFp64X8192UramBitstreamConfig mainargs=n512
```

该 `run` 只执行本目录的 CPU golden，不连接 Chisel RTL，也不要求 XO 或 xclbin 已生成。

独立 CSR5 仿真使用正式保存构造：

```bash
make build config=SpmvOneHbmCsr5MulSimulationConfig
make run config=SpmvOneHbmCsr5MulSimulationConfig mainargs=n512
make build-host config=SpmvOneHbmCsr5MulSimulationConfig
make build config=SpmvOneHbmCsr5MulCachedXSimulationConfig
make run config=SpmvOneHbmCsr5MulCachedXSimulationConfig mainargs=n512
```

它在 host 内维护 `0x80000000..0x87ffffff` 的 128 MiB HBM byte array。每次
`spmv_hbm_read512` 读取一个完整且 64-byte 对齐的 beat，`data[63:0]` 对应最低八个地址字节。
host 自动把大矩阵切成不超过 `8192 x 8192` 的二维块，按列块分组；同组非空行块共用一次 X slice
并合成一条 CSR5 stream。v3 在同一 128 MiB backing memory 内为 A/X 分配互不重叠且 4 KiB 对齐的
区域，配置握手后由显式 `start` 同时启动两路请求。共享调度器以 AXI ID 0/1 区分 A/X，按 burst
公平使用全局两个 outstanding credit；`SPMV_HBM_OUTSTANDING=1|2` 可把本次运行上限降为 1。

原 `SpmvOneHbmCsr5MulSimulationConfig` 是 paired 构造：每个 X beat 的低/高 256 bit 依次对应两个
ProductBeat group，不实例化 X cache。`SpmvOneHbmCsr5MulCachedXSimulationConfig` 是全速 cache 对照：
每个 X beat 装入连续 16 个 FP32，四份 `512 x 512-bit` 双端口存储支持八路任意列读取；A 可以提前进入
FIFO，但 decoder 等 X 全部加载并完成 CRC 后启动。两种构造随后都施加随机 ProductBeat 反压，并按
全局 NNZ 下标恢复原 CSR 顺序。乘积、sideband、FP flags 和最终逐行 FP32 结果均按位校验。
`build-host` 只重编译本目录源码并链接保存的模型，不重新 elaboration，也不构建 SoftFloat。

`SPMV_DISABLE_OUTPUT_STALLS=1` 关闭输出随机反压，用于比较纯 HBM/计算周期。v1/v2 保存构造仍可用
各自冻结的 host 直接运行，但不能由当前 `build-host` 刷新；升级到 v3 必须完整 `rebuild version=<编号>`。
`SPMV_HBM_NO_JITTER=1` 将每个已接收 AR 的首个 beat 延迟固定为 77 拍；首 beat 开始后 burst 仍保持
每拍一个 512-bit beat。它是确定性 Verilator HBM shell 对照，用于隔离首拍延迟抖动，不代表 FPGA 实测。
host 分别报告 A/X/总 beat 与 burst、PC data/idle cycles、利用率、join 等待、cache load、首个 product
latency、products、FP flags、总 cycles 和结果 hash，并要求 DPI 读取总数等于 A/X 硬件 beat 之和。
生成的 `n8192` 固定为 A=5436 beats；paired-X=2558、总计7994，cached-X=512、总计5948。

性能监测 Config 生成的 `performance.html` 和 `pipeline.html` 只统计 RTL 区间：从 HBM beat 到
ProductBeat 握手，包含 decode、X join、四级 MUL 和 Product FIFO。host 侧行归约只用于最终结果校验，
不进入 RTL 周期或流水线阶段；MUL 前缺少硬件握手观测的间隔在流水线页标为 `UNOBSERVED / 未观测`。

不传 `mainargs` 时只列出当前实际存在的可选规模，然后正常退出：

```bash
make -C accelerator-sim/spmv run
```

选择一个规模运行：

```bash
make -C accelerator-sim/spmv run mainargs=n512
make -C accelerator-sim/spmv run mainargs=n65536
make -C accelerator-sim/spmv run mainargs=thermal2_n1024
```

完整 golden 向量写入 `accelerator-sim/spmv/build/golden/<scale>.txt`。默认数据根目录是
同级共享目录 `accelerator-sim/data`，可在命令行覆盖：

```bash
make -C accelerator-sim/spmv run mainargs=n512 \
  ACCELERATOR_DATA_ROOT=/path/to/shared-data
```
