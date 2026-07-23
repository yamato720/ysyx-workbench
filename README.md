# YSYX Workbench

这是"一生一芯"工程工作区的 FPGA/SDB 开发分支。处理器的硬件 ABI、运行路径和 FPGA 实现策略均由
命名的 Scala Config 固定；根目录不再提供隐式的旧式 NPC 构造入口。

当前开发工作位于 `fpga-sdb` 分支。它包含 Config 驱动的 NPC/ysyxSoC 仿真构造、U55C/ZCU102 FPGA
shell、NEMU FPGA mailbox/SDB host 以及相应的回归脚本。

## 获取工作区

```bash
git clone --branch fpga-sdb --recurse-submodules \
  git@github.com:yamato720/ysyx-workbench.git
cd ysyx-workbench
```

若已有工作区但尚未初始化嵌套仓库：

```bash
git submodule update --init --recursive
```

主仓库提交会固定各 submodule 的确切 SHA；不要使用 `git submodule update --remote` 替换这些固定版本。

## 环境

日常仿真至少需要 Git、JDK 11+、sbt、Scala、Verilator、GNU Make、RISC-V 交叉工具链和 NEMU 所需的
主机 C/C++ 开发环境。FPGA 构造还需要终端 Config 所指定版本的 Vivado/Vitis/XRT。可按本机环境设置：

```bash
export NEMU_HOME="$(pwd)/nemu"
export AM_HOME="$(pwd)/abstract-machine"
export NPC_HOME="$(pwd)/npc"
export NVBOARD_HOME="$(pwd)/nvboard"
```

将这些变量写入 shell 启动文件后再开始日常构造。

## Config 驱动的构造

先查看当前 Make 可直接使用的完整 Config。目录由 Scala 在启动时自动扫描生成，短名和 FQCN 都可传给
`config=`：

```bash
make -C npc config-list
make -C npc build config=SimulationConfig
make -C npc build config=YsyxSimulationConfig
```

每个完整 Config 在 `npc/constructions/<FQCN>/` 中只有一份成功构造。首次成功时会分配从 `1` 开始的
版本序号；该编号可在后续运行中代替 Config 名称：

```bash
make -C npc version
make -C npc version config=SimulationConfig
make -C npc version version=1
```

所有由 `config=` 选中的终端都会绑定保存的 NEMU 运行宿主；本地仿真只是其中的本地 Verilator 实现，
`DPI` 仅是其内部 SV/C++ 桥接。未指定 `config=` 时仍保持原有 `ARCH` 驱动的 NEMU 运行方式。`ARCH`
根据 Config 的 XLEN 自动选择，不能用命令行改写硬件结构。

```bash
make -C am-kernels/tests/cpu-tests run ALL=add config=SimulationConfig
make -C am-kernels/tests/cpu-tests run-bat ALL="add div" config=YsyxSimulationConfig
make -C am-kernels/tests/cpu-tests run ALL=add version=1
make -C am-kernels/tests/cpu-tests run-bat ALL="add div" version=1,2
make -C am-kernels/tests/cpu-tests run-bat ALL="forwarding matrix-mul fpu" version=1,2 jobs=2
```

仿真 Config 在首次运行时自动构造。后续运行直接执行保存的 NEMU host，不再启动 NEMU Make；需要按
C/C++/menuconfig 依赖增量刷新 host 时使用 `host-rebuild=1`，或执行
`make -C npc host-build config=<硬件Config>`。变更 Chisel、生成 RTL、Verilator glue 或要更新硬件 ABI
时，使用 `rebuild=1`。

## FPGA 构造

U55C 与 ZCU102 采用同一个 Config 选择接口，板卡、CPU/SoC 目标、频率、平台、工具版本、实现并行度和
策略都由终端 Scala Config 固定：

```bash
make -C npc build config=U55cNpcFpgaConfig
make -C npc build config=U55cYsyxSocFpgaConfig
make -C npc build config=Zcu102NpcFpgaConfig
make -C npc build config=Zcu102YsyxSocFpgaConfig
```

FPGA Config 首次上板运行时必须明确允许构造；已有构造则仅在 `rebuild=1` 时原子替换。普通运行不会因
源码或工具变化自行重跑耗时的 Vivado/Vitis 实现。

```bash
make -C am-kernels/tests/cpu-tests run ALL=add \
  config=U55cYsyxSocFpgaConfig build=1
make -C npc build config=U55cYsyxSocFpgaConfig rebuild=1
```

实现产物会写入 `npc/constructions/<FQCN>/fpga/`。Chisel/firtool 按模块生成多个 SystemVerilog 文件，
避免单个巨型源文件放大 Vivado 内存压力。

## 资产与版本控制

源码、可重建的构造清单和校验规则进入 Git。以下本机或工具生成的内容不进入 Git，也不会由 submodule
自动下载：

- `npc/constructions/` 中的本机构造、NEMU host、日志和临时失败目录；
- Vivado/Vitis 缓存、IP 输出、DCP、XO、XSA、bitstream 与 xclbin；
- 通过实体板验收前的 FPGA 资产。

最终 FPGA 资产必须附带 `artifact-manifest.env` 和 `SHA256SUMS`，并通过板卡、平台、终端 Config、ABI
及摘要校验。当前分支未把 bitstream/xclbin 作为 Git 资产发布；经过时序与实体板验收后才会随正式
Release 追加。

## 文档与回归

- [NPC 构造与运行](npc/README.md)：版本管理、自动构造、批处理与失配策略。
- [Config 层级](npc/chisel/configs/README.md)：L1 NPC、L2 SoC、L3 FPGA、L4 board 的组合规则和完整 Config。
- [FPGA 工程](npc/fpga/README.md)：U55C/ZCU102 shell、IP、资产格式及校验命令。
- [源码 Release 构造说明](npc/fpga/releases/v0.2.0-fpga-sdb/README.md)：Release 固定内容与正式资产准入条件。

常规回归：

```bash
cd npc
sbt "root/test"
cd chisel/ysyxSoC && mill -i ysyxsoc.compile
mill -i ysyxsocTest.test

cd ../..
scripts/construction-regression.sh "$PWD"
fpga/tests/config-regression.sh "$PWD"
fpga/tests/release-regression.sh "$PWD"
fpga/tests/run-fpga-rtl-test.sh "$PWD"
```

这些 FPGA 回归只覆盖配置、RTL 和资产流程的 dry-run，不会启动完整 Vivado/Vitis 实现，也不替代实体板验收。
