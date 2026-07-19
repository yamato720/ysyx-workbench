# NPC Config 驱动的构造与运行

NPC 使用命名 Scala Config 固定硬件 ABI、仿真后端和 FPGA 实现策略。Make 不再接受结构参数覆盖，
也不再维护四位快照；一个完整 Config 在 `constructions/<FQCN>/` 中只保留一份成功构造。

## 常用命令

查看、生成和管理构造：

```bash
make -C npc config-list
make -C npc build config=NpcDpiConfig
make -C npc build config=U55cYsyxSocFpgaConfig
make -C npc build config=U55cYsyxSocFpgaConfig rebuild=1

make -C npc version
make -C npc version config=NpcDpiConfig
make -C npc version version=1
make -C npc version delete=1
make -C npc version delete=1 yes=1
```

CPU 测试的正式运行入口位于 `am-kernels/tests/cpu-tests`：

```bash
make -C am-kernels/tests/cpu-tests run ALL=add config=NpcDpiConfig
make -C am-kernels/tests/cpu-tests run-bat ALL="add div" config=YsyxSimulationConfig
make -C am-kernels/tests/cpu-tests run ALL=add version=1
make -C am-kernels/tests/cpu-tests run-bat ALL="add div" \
  version=1,2
```

`run` 只接受一个 Config 或编号；`run-bat` 可对逗号分隔的多个编号和多个 `ALL` 用例执行矩阵。
`config=` 与 `version=` 同时出现时必须指向同一 FQCN。`ARCH` 根据 Config 的 XLEN 推导，显式传入
`ARCH` 只做一致性校验。两者都不传会报错并列出可运行 Config。

## 构造策略

| Config 能力 | 用途 | 缺失时 | 已有构造的更新方式 |
| --- | --- | --- | --- |
| `elaborate-only` | 只生成 RTL | `make -C npc build` 显式生成 | `rebuild=1` 原子重构 |
| `verilator-npc` | NPC Verilator + NEMU host | 首次运行自动生成 | NEMU C/C++ 由 Make 增量刷新；硬件 ABI 用 `rebuild=1` |
| `verilator-soc` | ysyxSoC Verilator + NEMU host | 首次运行自动生成 | NEMU C/C++ 由 Make 增量刷新；硬件 ABI 用 `rebuild=1` |
| `fpga-npc` | 裸 NPC 板卡构造 | 运行需 `build=1` | 仅 `rebuild=1` 重新实现 |
| `fpga-soc` | ysyxSoC 板卡构造 | 运行需 `build=1` | 仅 `rebuild=1` 重新实现 |

FPGA 的 `build=1` 只允许首次构造；`rebuild=1` 强制在临时目录完成实现、校验后原子替换，并隐含
`build=1`。已有 FPGA 构造不会因源码、Config 或工具变化自动重建；需要新硬件时必须显式传入
`rebuild=1`。旧资产的 SHA-256、终端 FQCN、板卡、XRT 平台、host ABI 或 mailbox 协议不兼容时
始终硬失败。

仿真构造在每次运行前执行一次 NEMU 的普通 Make。C/C++ 和头文件由 `.d` 依赖判断，menuconfig
配置文本变化时会使 NEMU 对象整体重编译。Chisel、生成 RTL、Verilator 对象、`npc/csrc` glue 与 FPGA
文件不做自动失效检测，修改它们后使用 `rebuild=1`。

## 构造目录

```text
constructions/
  scpu.NpcDpiConfig/
    construction.env
    profile.env
    abi/{rtl,verilator,nemu,softfloat,glue}/
    logs/
  scpu.fpga.u55c.U55cYsyxSocFpgaConfig/
    construction.env
    profile.env
    abi/{nemu,protocol}/
    fpga/{rtl,ip,synth,link,artifacts}/
    logs/
```

首次成功构造分配从 `1` 开始、单调递增且不重排的版本序号。同一个 Config 重构时保留版本序号和
`CREATED_AT`，更新 `UPDATED_AT`、`REBUILD_COUNT` 和 Config 固定的 ABI。内部时间 ID 仅用于并发安全和
迁移排序，不是 Make 接口。构造在
`.staging-*` 完成；失败日志进入 `.failed/`，旧目录保持不变。

批次日志保存在工作区 `log/constructions/<版本序号>/<测试>/`，每项包含原始输出、状态和
`summary.env`。每次 `run`/`run-bat` 另生成会话汇总，展示 Config、能力、板卡、cycles、commits、
CPI、IPC、MIPS 与结果。

## Config 层级

| 层级 | 目录 | 职责 | 是否可选 |
| --- | --- | --- | --- |
| L1 | `chisel/configs/npc/` | NPC ISA、流水线、旁路、算术、内存和 AXI ABI | 必需 |
| L2 | `chisel/configs/ysyx/` | Rocket/ysyxSoC CDE 图与运行平台 | SoC 才需要 |
| L3 | `chisel/configs/fpga/common/` | NPC/SoC 接入 FPGA 的公共 CDE 键 | FPGA 才需要 |
| L4 | `chisel/configs/fpga/{u55c,zcu102}/` | 板卡、频率、器件和 Vivado/Vitis 策略 | FPGA 必需且二选一 |

Make 每次顶层启动都会由 Scala 重新扫描完整无参 Config，生成派生 TSV。组合片段和检查 Config
不会成为 Make 入口。选中 Config 后，SBT/Mill 反射实例化并生成 `profile.env`；Make、NEMU 和 Tcl
只消费该描述。新增终端 Config 不需要手工登记 CSV。

CDE 的 `++` 从右向左建立基础，左侧值优先。例如板卡 SoC Config 把板卡 NPC 放在
`YsyxSocFpgaConfig` 左侧，就能替换 SoC 默认核心，同时保留 Rocket、外设和平台设置。

完整类和可复制特性见 [Config 文档](chisel/configs/README.md)。FPGA shell、产物拆分与资产格式见
[FPGA 文档](fpga/README.md)。

## 数据通路

`make build/run` 先刷新 Config 目录，再由 SBT 或 Mill 生成规范化 profile。NPC 入口通过
`NpcConfigResolver` 得到 `NpcConstructionConfig`；SoC/FPGA 入口通过 `CdeConfigResolver` 得到 CDE
`Config`，并从 `NpcCoreConfigKey` 取得完成的 L1 `NpcConfig`。Chisel elaboration 生成按模块拆分的
SystemVerilog，Verilator 或 Vivado/Vitis 消费同一份 RTL 与 profile。运行时 AM 只编译测试镜像；仿真
Config 会先用 Make 刷新构造目录中的 NEMU host，FPGA Config 则始终使用冻结的 host、xclbin 或
ZCU102 环境清单。

## 验证

```bash
cd npc
sbt "root/test" "fpga/test"
cd chisel/ysyxSoC && mill -i ysyxsoc.compile

cd npc
scripts/construction-regression.sh "$PWD"
fpga/tests/config-regression.sh "$PWD"
fpga/tests/release-regression.sh "$PWD"
fpga/tests/run-fpga-rtl-test.sh "$PWD"
```

回归使用 dry-run 或 RTL 仿真，不会启动完整 Vivado/Vitis 实现。真实 U55C/ZCU102 资产只有在时序
收敛和实体板验收后才可进入 Release。
