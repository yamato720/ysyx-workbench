# NPC FPGA 板卡工程

本目录是完整 FPGA 构建与板卡集成根目录。顶层只按所有权分为 `common/`、`u55c/`、`zcu102/`；
厂商 IP 配方独立位于 `../fpga-ip-generator/`，板卡无关的 Chisel IP 接口位于
`../chisel/ip-interface/`。

## 目录边界

| 目录 | 职责 |
| --- | --- |
| `common/scala/` | mailbox、AXI、系统 IO、FPGA runtime 和公共 elaborator |
| `../chisel/configs/fpga/` | FPGA L3/L4 CDE Config、`base`、`core` 和板卡终端 |
| `common/{scripts,tcl,tests}/` | 构建入口、共享 Tcl、清单工具与 dry-run/RTL 回归 |
| `common/{build,releases}/` | 忽略的本地构建缓存与可审查的 Release 合同 |
| `u55c/` | U55C Scala provider、Config、wrapper、约束、XO/Vitis Tcl 与链接配置 |
| `zcu102/` | ZCU102 Scala provider、Config、wrapper、约束、Vivado Tcl 与 PS 运行时文件 |

正式构造写入 `../constructions/<FQCN>/fpga/`，其中 `rtl`、`ip-generated`、`synth`、`link` 和
`artifacts` 相互隔离。Chisel/firtool 输出 `rtl/ip-sources.manifest`；IP 生成后，构造器把生成 RTL、
板卡 wrapper、稳定 adapter 与 `.xci` 合并为 `synthesis-sources.manifest`。Vivado/Vitis Tcl 只导入
该清单，并拒绝 `MODEL=`、DPI 或 NEMU MMIO 内容。

## 构建入口

```bash
make -C npc build config=U55cNpcFpgaConfig
make -C npc build config=U55cRv64Npc300MHzFpgaConfig
make -C npc build config=U55cYsyxSocFpgaConfig
make -C npc build config=Zcu102NpcFpgaConfig
make -C npc build config=Zcu102YsyxSocFpgaConfig
```

板卡、NPC/SoC 目标、XLEN、频率、器件、平台、Vivado/Vitis 版本、并行度、实现策略、WNS 下限和
报告策略全部由终端 Scala Config 固定。`<board>/config.mk` 只保留 Tcl 文件布局、允许频率、地址和
IP 时序等独立硬件约束；构造时会与 catalog、CDE 板卡及 profile 交叉验证。

`FpgaToolchainConfig.flow` 固定综合/实现 jobs、实现策略、策略搜索及 Vitis XRT 环境。
`FpgaToolchainConfig.reports` 固定时序、拥塞、时钟利用率、控制集、高扇出、方法学和 QoR 报告。
U55C 的 `FPGA_VITIS_XRT_MODE=unset` 只影响 `v++` 子进程，不改变保存的运行宿主。

当前 FPGA 构造使用 `npc-fpga-runtime-v5` 作为调试和运行控制 ABI：M 扩展继续由 Xilinx 整数乘除
IP 执行；所有公开 FPGA Config 固定 `F=0`、`D=0`。因此 FPGA 不生成硬件 FPR、本地 FPU、浮点 IP
或 NEMU 指令代执行服务；完整 F 扩展只由本地 Verilator/NEMU 仿真 Config 用于学习。U55C 使用 XRT
轮询，ZCU102 使用 PS/UIO 通知，二者均不接入 RISC-V 外部中断。

## RTL 资源收缩证据

以提交 `5a60b439b26575539a8bb159c6eee5913db59cca` 为改造前基线，对同一
`Zcu102NpcFpgaConfig` 分别执行真实 elaboration（不运行 Vivado 实现）。这里只记录 Chisel 拆分输出
中的唯一 `module`，不把板卡 wrapper 或生成的 Xilinx `.xci` 计入：

| 项目 | 改造前 | runtime v5 |
| --- | ---: | ---: |
| 拆分 RTL 文件 | 38 | 31 |
| 唯一 RTL 模块 | 38 | 31 |
| `0x00..0x38` 低地址寄存器 | 指令请求/响应 | 保留零值 |

`FpgaRuntimeMailbox` 只保留调试、运行控制和输出通知；整数路径的 `MulDivAlu`、
`IntegerMultiplierOperator` 和 `IntegerDividerOperator` 均保留。改造前模块清单为：

```text
AxiLiteArbiter2 AxiLiteCrossbar AxiLiteHostMmioSlave AxiLiteToAxi4Full CPU
CsrExecution CsrFile FloatingAlu FloatingCompareOperator FloatingRegisterFile
ForwardingUnit FpgaDebugController FpgaFallbackMailbox FpgaFloatingDirectOperator
HazardUnit IFetchAXIAdapter IntegerAlu IntegerDividerOperator
IntegerMultiplierOperator LSUAXIAdapter MulDivAlu NpcBackend NpcDecodeUnit NpcFpgaSystem
NpcFpgaTop NpcFrontend NpcMemoryFabric PipelineRegister PipelineRegister_1
PipelineRegister_2 PipelineRegister_3 ProgramCounter Queue4_ArithmeticResponse RRArbiter
RRArbiter_3 RegisterFile ram_4x42
```

runtime v5 模块清单为：

```text
AxiLiteArbiter2 AxiLiteCrossbar AxiLiteHostMmioSlave AxiLiteToAxi4Full CPU
CsrExecution CsrFile ForwardingUnit FpgaDebugController FpgaRuntimeMailbox HazardUnit
IFetchAXIAdapter IntegerAlu IntegerDividerOperator IntegerMultiplierOperator LSUAXIAdapter
MulDivAlu NpcBackend NpcDecodeUnit NpcFpgaSystem NpcFpgaTop NpcFrontend NpcMemoryFabric
PipelineRegister PipelineRegister_1 PipelineRegister_2 PipelineRegister_3 ProgramCounter
RRArbiter RRArbiter_1 RegisterFile
```

因此当前结构证据是净减少 7 个模块，且不存在 `FloatingAlu`、`FloatingRegisterFile` 或
`FpgaFloatingDirectOperator`。真实 LUT/FF/DSP 差异必须在后续完整
Vivado/Vitis 实现后从实现报告中取得，本次 dry-run 不对其作估算。

## Scala 分层

| 路径 | 层级 |
| --- | --- |
| `common/scala/common/` | AXI、mailbox、运行时和 `FpgaSystemIO` 公共边界 |
| `common/scala/{rv-core,ysyxSoC}/` | 裸 NPC 与 ysyxSoC 的公共 FPGA 系统和 elaborator |
| `../chisel/configs/fpga/common/` | FPGA L3 公共 CDE 键与组合协议 |
| `{u55c,zcu102}/scala/` | 板卡 provider 与 shell；L4 终端位于 `../chisel/configs/fpga/` |

CDE 的 `FpgaBoardKey` 决定 shell，`FpgaClockMHzKey` 决定目标频率；板卡 wrapper、XDC、HBM 或
PS-DDR 连接仍属于对应板卡目录。Scala package `npc.fpga` 和 Config FQCN 不随物理路径改变。

## 资产校验

U55C 必须提供带平台名的 `npc-<XRT平台>.xclbin`。ZCU102 必须提供 `npc.bit`、`npc.xsa`、
`system-user.dtsi` 和 `npc-zcu102.env`。两者都必须包含 `artifact-manifest.env` 与 `SHA256SUMS`。

```bash
npc/fpga/common/scripts/artifact-manifest.sh verify \
  --directory /path/to/artifacts \
  --board u55c \
  --platform xilinx_u55c_gen3x16_xdma_3_202210_1 \
  --config-fqcn npc.fpga.u55c.U55cYsyxSocFpgaConfig \
  --host-abi nemu-construction-v1 \
  --protocol-abi npc-fpga-runtime-v5
```

源码、Config 或工具变化不会自动替换已有 FPGA 构造；需要新实现时显式执行 `rebuild`。
ABI v3 及更早构造不能通过 `host-rebuild` 升级，必须使用 `rebuild` 重新生成硬件和运行宿主。
