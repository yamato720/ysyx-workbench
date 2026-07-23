# NPC FPGA 板卡工程

本目录保存板卡物理集成、Xilinx IP 配方、Vivado/Vitis 实现流程和资产校验。处理器与 SoC 的
Chisel shell 位于 `../chisel/fpga-harness`；两层通过按模块拆分的 `rtl/` 目录和稳定顶层
`NpcFpgaTop` 连接。

## 目录边界

| 目录 | 职责 |
| --- | --- |
| `common/` | 实现清单、资产清单和 Release 校验工具 |
| `boards/zcu102/` | ZCU102 wrapper、约束、Vivado Tcl 和 PS 运行时文件 |
| `boards/u55c/` | U55C kernel wrapper、约束、XO/Vitis 链接配置 |
| `ip/` | 整数算术 IP 生成器和稳定协议适配器 |
| `tests/` | Config、RTL、Release 与资产回归 |
| `build/` | 旧生成结果；保留在磁盘，但新系统不迁移、不索引、不复用 |

正式构造写入 `../constructions/<FQCN>/fpga/`，其中 `rtl`、`ip`、`synth`、`link` 和 `artifacts`
相互隔离。Chisel/firtool 按模块输出多个 `.sv`/`.v`，Vivado/Vitis Tcl 递归导入，避免单个巨型
SystemVerilog 文件放大综合内存占用。

## 构建入口

```bash
make -C npc build config=U55cNpcFpgaConfig
make -C npc build config=U55cFullIsa64Npc250MHzFpgaConfig
make -C npc build config=U55cYsyxSocFpgaConfig
make -C npc build config=Zcu102NpcFpgaConfig
make -C npc build config=Zcu102YsyxSocFpgaConfig
```

板卡、NPC/SoC 目标、XLEN、F/M、频率、器件、平台、Vivado/Vitis 版本、Vitis XRT 环境策略、并行度、实现策略、WNS
下限和实现后报告策略全部由终端 Scala Config 固定。`boards/<board>/config.mk` 只保留 Tcl/IP 文件布局、
允许频率、地址和 IP 时序等独立硬件约束；器件与工具链不在其中重复定义。构造时仍交叉验证 catalog、
硬件 CDE 板卡及这些独立约束。公开 Make 不接受结构覆盖变量。

`FpgaToolchainConfig.flow` 将综合/实现 jobs、实现策略和策略搜索固定在 FPGA 终端。前两个值控制宿主
Vivado/Vitis worker jobs；策略搜索开启时，Alveo Vitis 还会并行创建默认策略外的实现 run。
`FpgaToolchainConfig.reports` 同样由终端冻结：默认保留 50 条最差路径、每端点 10 条路径，并输出拥塞、
时钟利用率、控制集、高扇出网络、方法学和 QoR 建议。共享报告位于每个实现 run 的
`npc-implementation-reports/`；U55C 多策略运行互不写入同一目录。时序摘要是必需证据，其他诊断报告
若不受工具版本支持只会将原因写入 `report-errors.log`，不会额外阻断 bitstream。

U55C 的 `FPGA_VITIS_XRT_MODE=unset` 仅为 `v++` 链接子进程移除 `XILINX_XRT`，使 Vitis 2022.2
使用自带的 `xclbinutil`；这避免新版本机 XRT 覆盖后要求不匹配的 Boost 库。它不改变 NEMU/上板运行
时的 XRT 环境，运行时仍由已保存的宿主配置负责。

## Shell 分层

| 路径 | 层级 |
| --- | --- |
| `fpga-harness/src/common/` | AXI、mailbox、运行时和 `FpgaSystemIO` 公共边界 |
| `fpga-harness/src/rv-core/` | 裸 NPC FPGA 系统与 elaborator |
| `fpga-harness/src/ysyxSoC/` | ysyxSoC FPGA 系统与 elaborator |
| 各自的 `fpga/u55c/`、`fpga/zcu102/` | 板卡 shell |

CDE 的 `FpgaBoardKey` 决定 shell，`FpgaClockMHzKey` 决定目标频率；板卡 wrapper、pin/XDC、HBM 或
PS-DDR 连接仍属于 `boards/<board>/`。

## 资产校验

U55C 必须提供带平台名的 `npc-<XRT平台>.xclbin`。ZCU102 必须提供 `npc.bit`、`npc.xsa`、
`system-user.dtsi` 和 `npc-zcu102.env`。两者都必须包含 `artifact-manifest.env` 与 `SHA256SUMS`。

```bash
npc/fpga/common/scripts/artifact-manifest.sh verify \
  --directory /path/to/artifacts \
  --board u55c \
  --platform xilinx_u55c_gen3x16_xdma_3_202210_1 \
  --config-fqcn scpu.fpga.u55c.U55cYsyxSocFpgaConfig \
  --host-abi nemu-construction-v1 \
  --protocol-abi npc-fpga-mailbox-v3
```

运行前还会校验清单自身摘要、资产 SHA、板卡、XRT 平台、终端 Config 和 ABI。源码、Config 或工具
发生变化不会自动重建 FPGA；需要新实现时明确传入 `rebuild=1`。清单或协议不兼容始终失败。
