# FPGA 集成层

本目录保存裸核 NPC 与 ysyxSoC 共用的可综合 FPGA 集成代码。它只描述逻辑集成，
板卡 wrapper、约束、厂商 IP 和构建流程位于 `../../fpga`。

- `src/common/`：FPGA 平台组件、调试控制、浮点回退、邮箱、运行时协议和
  板卡无关的 `FpgaSystemIO` 边界。
- `src/rv-core/`：裸核生成入口；`fpga/` 保存板卡无关 FPGA 系统与公共 shell，其
  `zcu102/`、`u55c/` 子目录分别提供板卡 shell，生成入口为 `scpu.ElaborateFPGA`。
- `src/ysyxSoC/`：ysyxSoC 生成入口；`fpga/` 保存板卡无关 FPGA 系统与公共 shell，其
  `zcu102/`、`u55c/` 子目录分别提供板卡 shell，生成入口为 `ysyx.ElaborateFPGA`。

普通 NPC 流水线仍位于 `../rv-core/main/scala`。`FpgaCoreComponents` 只提供 FPGA 的
整数 IP 选择、浮点直接/邮箱端点和派发控制；译码、流水线和提交逻辑不复制，也不进入
板级源码。普通 SBT `root` 不编译本目录；`fpga` 子项目额外编入轻量 CDE，使裸核与 SoC
共用同一套 FPGA Config 组合方式。

两个生成入口依据终端 CDE Config 的 `FpgaBoardKey` 实例化相应 shell，但两者都把生成顶层保持为
`NpcFpgaTop`。物理端口适配留在 `../../fpga/boards/<board>/rtl`，从而允许每个 Chisel
shell 独立演进，同时不会改变已存在的板级 Verilog ABI。

`U55cBoardConfig` 或 `Zcu102BoardConfig` 通过 `WithFpgaPlatformConfig`、`WithFpgaToolConfig`、
`WithFpgaBoardConfig` 和 `WithFpgaClockMHzConfig` 把平台地址、IP 时序、工具策略、板卡与频率写入
CDE。`NpcFpgaCdeConfig` 选择固定的 L1 `NpcFpgaConfig`，也可由最左侧的 `WithNpcCoreConfig`
覆盖。shell 从 CDE 读取这些值并验证板卡匹配，不读取结构环境变量。

有效 FPGA 矩阵是两种系统目标乘以两种板卡：

| 系统目标 | ZCU102 | U55C |
| --- | --- | --- |
| 裸 NPC | `Zcu102NpcFpgaConfig` / `Zcu102NpcFpgaShell` | `U55cNpcFpgaConfig` / `U55cNpcFpgaShell` |
| ysyxSoC | `Zcu102YsyxSocFpgaConfig` / `Zcu102YsyxFpgaShell` | `U55cYsyxSocFpgaConfig` / `U55cYsyxFpgaShell` |

`U55cBoardConfig` 与 `Zcu102BoardConfig` 位于 `../configs/fpga`，是裸核和 SoC 共用的无参
板卡策略，并在 Config 中设定频率。`U55cNpcFpgaConfig` 与 `Zcu102NpcFpgaConfig` 选择默认
`NpcFpgaCdeConfig`；`U55cYsyxSocFpgaConfig` 与 `Zcu102YsyxSocFpgaConfig` 位于
`../configs/fpga/{u55c,zcu102}`，使用 `../configs/ysyx/SocConfig.scala` 的通用 SoC 平台，并在
其左侧覆盖默认 NPC。将 `WithNpcCoreConfig(npcConfig)` 放在组合链最左侧即可继续覆盖；两块板
没有不同的 CPU 语义，因此不保留 CPU 专用的板卡别名 Config。
