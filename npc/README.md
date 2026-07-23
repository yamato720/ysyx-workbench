# NPC Config 驱动的构造与运行

NPC 使用命名 Scala Config 固定硬件 ABI、运行宿主和 FPGA 实现策略。Make 不再接受结构参数覆盖，
也不再维护四位快照；一个完整 Config 在 `constructions/<FQCN>/` 中只保留一份成功构造。

`config=` 只选择硬件终端，不选择 NEMU、DPI 或 Verilator 模式。除只供 Scala/RTL 测试使用的
`check-only` Config 外，每个可选择终端都绑定一套保存的 NEMU 运行宿主；本地仿真的 DPI 只是该宿主
连接 Verilator 模型的内部桥接。

## 常用命令

查看、生成和管理构造：

```bash
make -C npc config-list
make -C npc build config=SimulationConfig
make -C npc build config=U55cYsyxSocFpgaConfig
make -C npc build config=U55cYsyxSocFpgaConfig rebuild=1
make -C npc host-config-list
make -C npc host-build config=SimulationConfig
make -C npc host-build all=1 jobs=-1

make -C npc version
make -C npc version config=SimulationConfig
make -C npc version version=1
make -C npc version delete=1
make -C npc version delete=1 yes=1
```

CPU 测试的正式运行入口位于 `am-kernels/tests/cpu-tests`：

```bash
make -C am-kernels/tests/cpu-tests run ALL=add config=SimulationConfig
make -C am-kernels/tests/cpu-tests run-bat ALL="add div" config=YsyxSimulationConfig
make -C am-kernels/tests/cpu-tests run ALL=add version=1
make -C am-kernels/tests/cpu-tests run-bat ALL="add div" \
  version=1,2 jobs=2
make -C am-kernels/tests/cpu-tests run-bat ALL="forwarding matrix-mul fpu" \
  version=1,2,3 host-rebuild=1 jobs=-1
```

`run` 只接受一个 Config 或编号；`run-bat` 可对逗号分隔的多个编号和多个 `ALL` 用例执行矩阵。
`config=` 与 `version=` 同时出现时必须指向同一 FQCN。`ARCH` 根据 Config 的 XLEN 推导，显式传入
`ARCH` 只做一致性校验。两者都不传会报错并列出可运行 Config。

给出实际运行或批量测试命令前，先以 `make -C npc version config=<Config>` 查询目标 Config 是否已有
保存构造。若存在，正式命令应优先使用 `version=<编号>`，以固定硬件 ABI、运行宿主和 FPGA 资产；多个
已保存构造的批测使用 `version=1,2,...`。只有尚无保存构造、用户明确要求按当前 Config 解析，或需要
`build`/`rebuild` 创建或更新构造时，才使用 `config=<Config>` 作为执行选择器。

## 构造策略

| 构造能力 | 由 `scope` 区分的目标 | 缺失时 | 已有构造的更新方式 |
| --- | --- | --- | --- |
| `check-only` | 只做 Scala/RTL 检查 | 不进入公开 Make 构造或运行入口 | 由测试直接调用 |
| `run` | `npc`/`soc` 为本地仿真，`fpga` 为上板运行（由 `TARGET` 选择裸核或 SoC） | NPC/SoC 首次运行自动生成；FPGA 需 `build=1` | `rebuild=1` 原子重构硬件与运行宿主；仅更新 C/C++ 宿主用 `host-rebuild=1` 或 `host-build` |

FPGA 的 `build=1` 只允许首次构造；`rebuild=1` 强制在临时目录完成实现、校验后原子替换，并隐含
`build=1`。已有 FPGA 构造不会因源码、Config 或工具变化自动重建；需要新硬件时必须显式传入
`rebuild=1`。旧资产的 SHA-256、终端 FQCN、板卡、XRT 平台、host ABI 或 mailbox 协议不兼容时
始终硬失败。

普通 `run`/`run-bat` 只验证并直接执行已保存的 `abi/nemu/nemu-exec`，不会启动 NEMU Make。运行宿主的
C/C++ 和 menuconfig 增量依赖只在 `host-build` 或 `host-rebuild=1` 时运行，并原子替换
保存 profile 的 `NEMU_*` 段与 `abi/nemu/`；当前终端的硬件和 `FpgaToolchainConfig` 变化不会被吸收。
Chisel、生成 RTL、Verilator ABI、`npc/csrc` glue 与 FPGA 文件仍只由 `rebuild=1` 更新。

## 构造目录

```text
constructions/
  scpu.SimulationConfig/
    construction.env
    profile.env
    abi/{rtl,verilator,nemu,softfloat,glue}/
    logs/
    runtime/<test>/<timestamp-ns>-<pid>/{performance.html,instructions.html,pipeline.html,wave-*.vcd}
  scpu.fpga.u55c.U55cYsyxSocFpgaConfig/
    construction.env
    profile.env
    abi/{nemu,protocol}/
    fpga/{rtl,ip,synth,link,artifacts}/
    logs/
```

首次成功构造分配从 `1` 开始、单调递增且不重排的版本序号。同一个 Config 重构时保留版本序号和
`CREATED_AT`，更新 `UPDATED_AT`、`REBUILD_COUNT` 和 Config 固定的 ABI。内部时间 ID 仅用于并发安全和
迁移排序，不是 Make 接口。构造在 `.staging-*` 完成；成功后最新一次的 Chisel、SoftFloat、Verilator、
FPGA 和 NEMU host 原始输出分别保存在 `logs/build/<阶段>.log` 与 `logs/host/nemu-host.log`，并随构造
原子发布。每类只保留最新日志。失败构造写入 `.failed/<FQCN>/<build|host>/`，旧目录和其 ABI 保持不变。
所有 Vivado 参与的 FPGA IP、综合和链接阶段仍实时输出；其余历史工具输出只显示阶段进度，并写入对应日志。
交互终端中，实时阶段若连续一秒没有新输出，会显示不写入日志的流水灯，收到下一条工具输出时立即清除。
算术 IP 的 Tcl 还会在 `fpga/ip/logs/npc_int_multiplier_ip.log` 与
`fpga/ip/logs/npc_int_divider_ip.log` 分别保存参数、生成输出和最终属性报告。

构造 staging 目录带 `.incomplete`，host、RTL 和资产校验完成后改写为 `.complete`，随后才原子发布。
`make version` 只读取完成的正式构造快照，不启动 Scala 目录刷新，也不等待正在进行的长构造；FPGA
完成态还要求 U55C 的平台限定 `xclbin` 或 ZCU102 的 `npc.bit` 实际存在且非空。完整 manifest/SHA 校验
仍在构造发布和运行预检执行，避免静态版本查询反复读取大文件。
只有旧构造尚未补齐一次性版本迁移元数据时才等待全局锁。`config-list`、`build` 和按当前 Config 解析的
运行仍会刷新 Scala 目录。

版本主表用 `+`/空白属性位图代替长 Config 名称：XLEN 分为 RV32/RV64，ISA 显示 M/F/Zicsr，流水线
固定显示 Pipe/ID/EX 三格，随后显示 NPC/SoC/FPGA 目标和 U55C/ZCU102 板卡。Config 短名在第二张
“版本 → Config”表中单独列出，便于保持主表对齐并快速比较硬件差异。

NEMU host 的 `performanceHtml` 可选项会在运行结束时写入
`runtime/<test>/<timestamp-ns>-<pid>/performance.html`。它是报告主页，包含总体 CPI/IPC/MIPS、宿主耗时、
流水配置、stall 对比、五阶段平均占比、各 load/store/M 操作的平均与最大延迟、最近分类样本和最后提交；
同一份提交记录还会生成可搜索、分页的 `instructions.html` 逐指令明细。主页以新窗口打开可用的子报告，
子报告均可返回主页。`pipelineHtml` 是 `performanceHtml` 的本地 Verilator 子特征：它复用父功能的提交记录
生成 `pipeline.html`，不再收集第二份轨迹；对应 host 还会同时启用 NEMU 软件逐提交自查。记录只包含已提交
指令，默认保留前 20 万条；流水页提供 PC/反汇编搜索、分页、周期缩放和 IF/ID/EX/MEM/WB 悬浮信息，超限
时继续统计丢弃条数。所有本地 NPC/SoC 仿真终端当前都启用 performance 与 pipeline；标量核心显示顺序阶段时间线，流水线核心
还会显示阶段重叠与停顿。软件自查逐条比较 NPC 与 NEMU 的 GPR、FPR、FCSR 和下一 PC，可直接报告首个
架构状态分歧；主存 store 还会核对对齐地址、总线数据、字节掩码和 beat 宽度，避免共享内存掩盖邻接
lane 破坏。它不隐含启用 VCD 或普通 instruction trace。SDB 的 `start`/`stop` 若已由 NEMU
Config 启用 VCD，则在同一运行目录依次写 `wave-001.vcd`、`wave-002.vcd`；直接运行非 construction host
时回退到当前目录。

`rebuild=1` 发布的是新硬件 ABI，不继承旧构造的 `runtime/`；`host-build` 与 `host-rebuild=1` 只替换 host，
会保留已有运行产物。

批次运行不生成汇总 HTML，仅在会话目录 `log/constructions/runs/<时间>/` 保存最终汇总：`completion.tsv`
按实际完成顺序列出单项性能，`summary.tsv` 按版本、Config 和测试稳定排序用于比较，`details.txt` 保存
每项本次运行的精确性能主页路径；逐指令与流水明细由性能主页进入。并行条目的 AM 构建目录和原始输出只在
执行期间临时存在，完成性能汇总和失败摘录后会清理；HTML/VCD 运行产物独立保存在上述 construction
运行目录，不属于原始运行日志。

## Config 层级

| 层级 | 目录 | 职责 | 是否可选 |
| --- | --- | --- | --- |
| 公共运行宿主 | `chisel/configs/common/`、`chisel/configs/nemu/` | 运行 trait 与内部 NEMU menuconfig 预设 | 运行终端必需 |
| L1 | `chisel/configs/npc/` | 完整 NPC 成品与 Make 反射解析器 | 必需 |
| L2 | `chisel/configs/ysyx/` | Rocket/ysyxSoC CDE 图与运行平台 | SoC 才需要 |
| L3 | `chisel/configs/fpga/common/` | NPC/SoC 接入 FPGA 的公共 CDE 键 | FPGA 才需要 |
| L4 | `chisel/configs/fpga/{u55c,zcu102}/` | 板卡、频率、器件和 Vivado/Vitis 策略 | FPGA 必需且二选一 |

每个领域按 `base -> core -> Configs.scala` 分层：`base/` 放底层键、数据与原子片段，`core/` 形成终端
可直接引用的具名完整组合，根部 `Configs.scala` 只放带 marker 的无参终端。Make 每次顶层启动都会由
Scala 校验该布局并生成派生 TSV；marker 出现在 `base/`、`core/` 或其他文件会直接报错。选中 Config
后，SBT/Mill 反射实例化并生成 `profile.env`；Make、NEMU 和 Tcl 只消费该描述。新增终端 Config 不需要
手工登记 CSV。

CDE 的 `++` 从右向左建立基础，左侧值优先。例如板卡 SoC Config 依次叠加板卡、完整 NPC 与
`YsyxElaborateConfig`，就能替换 SoC 默认核心，同时保留 Rocket 和外设。板卡 CDE 键本身就是
FPGA 分支的唯一来源，无需重复叠加平台标签。

完整类和可复制特性见 [Config 文档](chisel/configs/README.md)。FPGA shell、产物拆分与资产格式见
[FPGA 文档](fpga/README.md)。

## 数据通路

`make build/run` 先刷新 Config 目录，再由 SBT 或 Mill 生成规范化 profile。NPC 入口通过
`ConfigResolver` 得到 `ConstructionConfig`；SoC/FPGA 入口通过 `CdeConfigResolver` 得到 CDE
`Config`，并从 `NpcCoreConfigKey` 取得完成的 L1 `NpcConfig`。每个运行终端还通过
`NemuSimulationConstructionConfig` 或 `FpgaConstructionConfig` 直挂 `NemuHostConfig`；FPGA 终端同时
直挂分组式 `FpgaToolchainConfig`。profile 据此渲染保存的 `host.defconfig` 和现有 `FPGA_*` 字段。Chisel
elaboration 生成按模块拆分的 SystemVerilog，Verilator 或 Vivado/Vitis 消费同一份 RTL 与 profile。
运行时 AM 只编译测试镜像，并直接执行冻结的 host、xclbin 或 ZCU102 环境清单。

## 验证

```bash
cd npc
sbt "root/test"
make -C ../nemu pipeline-html-test
make -C ../nemu performance-html-test
cd chisel/ysyxSoC && mill -i ysyxsoc.compile
mill -i ysyxsocTest.test

cd npc
scripts/construction-regression.sh "$PWD"
fpga/tests/config-regression.sh "$PWD"
fpga/tests/release-regression.sh "$PWD"
fpga/tests/run-fpga-rtl-test.sh "$PWD"
```

回归使用 dry-run 或 RTL 仿真，不会启动完整 Vivado/Vitis 实现。真实 U55C/ZCU102 资产只有在时序
收敛和实体板验收后才可进入 Release。
