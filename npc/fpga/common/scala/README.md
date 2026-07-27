# FPGA 集成层

本目录保存裸核 NPC 与 ysyxSoC 共用的可综合 FPGA 集成代码。它只描述逻辑集成，
板卡 wrapper、约束与板卡构建流程位于 `../../u55c`、`../../zcu102`；厂商 IP 生成器位于
`../../../fpga-ip-generator`。

- `common/`：FPGA 平台组件、调试控制、浮点回退、邮箱、运行时协议和
  板卡无关的 `FpgaSystemIO` 边界。
- `rv-core/`：裸核生成入口与板卡无关系统，生成入口为 `npc.ElaborateFPGA`。
- `ysyxSoC/`：ysyxSoC 生成入口与板卡无关系统，生成入口为 `ysyx.ElaborateFPGA`。
- FPGA 公共 CDE 键与组合协议位于 `../../../chisel/configs/fpga/common/`；板卡终端 Config 位于
  `../../../chisel/configs/fpga/{u55c,zcu102}/`。

普通 NPC 流水线仍位于 `../../../chisel/rv-core/main/scala`。`FpgaCoreComponents` 只提供 FPGA 的
整数 IP 选择、浮点直接/邮箱端点和派发控制；译码、流水线和提交逻辑不复制，也不进入
板级源码。SBT `root` 只额外编入轻量 CDE 参数库；FPGA 终端统一由 ysyxSoC 的 Mill 编译边界编入，
使裸核与 SoC 共用同一份板卡 Config 源码与组合方式。

两个生成入口依据终端 CDE Config 的 `FpgaBoardKey` 实例化相应 shell，但两者都把生成顶层保持为
`NpcFpgaTop`。物理端口适配留在 `../../<board>/rtl`，从而允许每个 Chisel
shell 独立演进，同时不会改变已存在的板级 Verilog ABI。

`U55cBoardConfig` 或 `Zcu102BoardConfig` 通过 `WithFpgaPlatformConfig`、`WithFpgaBoardConfig` 和
`WithFpgaClockMHzConfig` 把平台地址、IP 时序、板卡与频率写入 CDE。完整 L1 `FpgaConfig` 自身提供
CDE 核心键。器件、Vivado/Vitis flow、报告和运行 ABI 来自终端直挂的 `FpgaToolchainConfig`；
elaborator 会交叉验证目录板卡、工具链板卡与硬件 CDE 板卡。

有效 FPGA 矩阵是两种系统目标乘以两种板卡：

| 系统目标 | ZCU102 | U55C |
| --- | --- | --- |
| 裸 NPC | `Zcu102NpcFpgaConfig` / `Zcu102NpcFpgaShell` | `U55cNpcFpgaConfig` / `U55cNpcFpgaShell` |
| ysyxSoC | `Zcu102YsyxSocFpgaConfig` / `Zcu102YsyxFpgaShell` | `U55cYsyxSocFpgaConfig` / `U55cYsyxFpgaShell` |

`U55cBoardConfig` 与 `Zcu102BoardConfig` 位于 `../../../chisel/configs/fpga/<board>/core/`，是裸核和 SoC 共用的
板卡策略，并在 Config 中设定频率。完整 L1 `FpgaConfig` 自身提供 CDE 核心键；
`U55cNpcFpgaConfig` 与 `Zcu102NpcFpgaConfig` 直接叠加它。`U55cYsyxSocFpgaConfig` 与
`Zcu102YsyxSocFpgaConfig` 位于对应板卡 `../../../chisel/configs/fpga/<board>/`，使用
`../../../chisel/configs/ysyx/Configs.scala` 的通用 SoC 平台，并在其左侧直接叠加完整 NPC。两块板没有不同的
CPU 语义，因此不保留 CPU 专用的板卡别名 Config。
