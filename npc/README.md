# NPC Config 驱动的构造与运行

NPC 使用命名 Scala Config 固定硬件 ABI、运行宿主和 FPGA 实现策略。Make 不再接受结构参数覆盖，
也不再维护四位快照；一个完整 Config 在 `constructions/<FQCN>/` 中只保留一份成功构造。

稳定 IP 契约、厂商无关逻辑和仿真模型位于独立的 `chisel/ip-interface/` SBT/Mill 模块。该模块只依赖
Chisel；rv-core 保留 ISA 译码与操作码映射，ysyxSoC 保留 Diplomacy node 和地址映射，`fpga/` 只绑定
板卡 provider 与物理工程，`fpga-ip-generator/` 保存厂商 IP 配方。

`config=` 只选择硬件终端，不选择 NEMU、DPI 或 Verilator 模式。除只供 Scala/RTL 测试使用的
`check-only` Config 外，每个可选择终端都绑定一套保存的 NEMU 运行宿主；本地仿真的 DPI 只是该宿主
连接 Verilator 模型的内部桥接。

## 常用命令

查看、生成和管理构造：

```bash
make -C npc config-list
make -C npc build config=SimulationConfig
make -C npc build config=U55cYsyxSocFpgaConfig
make -C npc rebuild config=U55cYsyxSocFpgaConfig
make -C npc host-config-list
make -C npc host-build config=U55cRv64Npc300MHzFpgaConfig
make -C npc host-build all=1 jobs=-1

make -C npc version
make -C npc version config=SimulationConfig
make -C npc version version=1
make -C npc version D=1
make -C npc version delete=1
```

CPU 测试的正式运行入口位于 `am-kernels/tests/cpu-tests`：

```bash
make -C am-kernels/tests/cpu-tests run ALL=add config=SimulationConfig
make -C am-kernels/tests/cpu-tests run-bat ALL="add div" config=YsyxSimulationConfig
make -C am-kernels/tests/cpu-tests run ALL=add version=1
make -C am-kernels/tests/cpu-tests run-bat ALL="add div" \
  version=1,2 jobs=2
make -C am-kernels/tests/cpu-tests run-bat ALL=add version=2 reset=1
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

`make version` 只读取构造目录中的 `version.tag` 和 `version.info`，不会刷新或解析 Scala catalog。
它显示已有的 `constructions/<FQCN>/` 构造，不罗列尚未构造的 Config。属性位图固定为
`Version RV32 RV64 M F Zicsr Pipe ID EX valid?`；`Arch` 以 `NPC`/`SoC` 显示，`RunningTime` 以
`SIM`/`FPGA` 显示，最右侧 `Config` 为对应保存构造的短名。`valid?` 为 `+` 时该正式构造当前可运行；进行中的构造和缺少必要资产的构造保留显示
但为空。`D=<序号>` 与 `delete=<序号>` 都会删除对应构造并紧凑重映射后续序号；同时给出时必须相同。

## 构造策略

| 构造能力 | 由 `scope` 区分的目标 | 缺失时 | 已有构造的更新方式 |
| --- | --- | --- | --- |
| `check-only` | 只做 Scala/RTL 检查 | 不进入公开 Make 构造或运行入口 | 由测试直接调用 |
| `run` | `npc`/`soc` 为本地仿真，`fpga` 为上板运行（由 `TARGET` 选择裸核或 SoC） | NPC/SoC 首次运行自动生成；FPGA 需 `build` | `rebuild` 在同一 FQCN 目录重构硬件与运行宿主；仅更新 C/C++ 宿主用 `host-build` |

FPGA 的首次构造需显式执行 `build`；已有 FPGA 构造不会因源码、Config 或工具变化自动重建，需要新硬件时
必须显式执行 `rebuild`。`build` 和 `rebuild` 都直接使用稳定的 FQCN 目录，开始时会清理该目录中旧 ABI、
RTL、FPGA 资产和运行产物；中断或失败后目录会保留为无效状态，可直接重试 `build` 或 `rebuild`。旧资产的 SHA-256、
终端 FQCN、板卡、XRT 平台、host ABI 或 mailbox 协议不兼容时
始终硬失败。

`make build config=<Config>` 只允许首次构造，或修复 `valid?` 为空的 `building`、`interrupted`、`failed`
和缺资产构造；它会沿用同一个版本号和 FQCN 目录。`valid?=+` 的构造会被 `build` 明确拒绝，必须使用
`make rebuild config=<Config>` 替换硬件 ABI。

普通 `run`/`run-bat` 只验证并直接执行已保存的 `abi/nemu/nemu-exec`，不会启动 NEMU Make。运行宿主的
C/C++ 和 menuconfig 增量依赖只在 `host-build` 或运行入口的 `host-rebuild=1` 时运行，并原子替换
保存 profile 的 `NEMU_*` 段与 `abi/nemu/`；当前终端的硬件和 `FpgaToolchainConfig` 变化不会被吸收。
Chisel、生成 RTL、Verilator ABI、`npc/csrc` glue 与 FPGA 文件仍只由 `rebuild` 更新。

若 FPGA 构造已经完成链接并生成完整 manifest/SHA-256 资产，却只在末尾的 NEMU host 阶段失败或中断，
`host-build config=<Config>` 会校验保留资产、只重试 host，并在 host 与资产复验通过后发布原版本。缺少
`nemu-host` 失败证据、manifest/SHA-256 校验失败或硬件中间阶段失败的构造仍必须使用 `build`/`rebuild`，不会被
`host-build` 提前发布。

对于另一套流程已经生成的 xclbin，可直接执行
`make -C npc host-build config=U55cRv64Npc300MHzFpgaConfig`，只生成匹配的 U55C NEMU host，输出在
`constructions/.hosts/npc.fpga.u55c.U55cRv64Npc300MHzFpgaConfig/abi/nemu/nemu-exec`。该目录保存当前
Config profile 和 host，但不含 RTL、FPGA 资产或版本标签；它不会被 `version`、`run` 或 `run-bat` 当作正式
构造。直接运行时由调用者提供外部 xclbin，例如通过 `NEMU_FPGA_XCLBIN=/path/to/design.xclbin`。
每次 elaboration 同时生成 `ip-sources.manifest`。其中 `RTL=` 是工具实际编译的源，`MODEL=` 记录已嵌入
生成 RTL 的仿真模型；FPGA 另生成 `synthesis-sources.manifest`，只允许 `RTL=` 和 `XCI=`。构造冻结按该
清单复制源文件，不再递归保存整个 `ysyxSoC/perip/`。

## 构造目录

```text
constructions/
  npc.SimulationConfig/
    construction.env
    profile.env
    version.tag
    version.info
    abi/{rtl,verilator,nemu,softfloat,glue}/
    logs/
    runtime/<test>/<timestamp-ns>-<pid>/{performance.html,instructions.html,pipeline.html,wave-*.vcd}
  npc.fpga.u55c.U55cYsyxSocFpgaConfig/
    construction.env
    profile.env
    version.tag
    version.info
    abi/{nemu,protocol}/
    fpga/{rtl,ip-generated,synth,link,artifacts}/
    logs/
```

首次构造开始时分配从 `1` 开始的连续版本序号，并以 `version.tag` 的 `building` 状态立即可见。同一个
Config 重构时保留版本序号和
`CREATED_AT`，更新 `UPDATED_AT`、`REBUILD_COUNT` 和 Config 固定的 ABI；`make version D=<序号>` 删除后会将
后续版本紧凑重映射。内部时间 ID 仅用于并发安全和
迁移排序，不是 Make 接口。从创建到完成，构造始终直接使用 `constructions/<FQCN>/`；目录名不会因
`build`、`rebuild`、完成或中断而变化。最新一次的 Chisel、SoftFloat、Verilator、FPGA 和 NEMU host 原始输出
分别保存在 `logs/build/<阶段>.log` 与 `logs/host/nemu-host.log`。每类只保留最新日志；失败的关键证据另存
在 `.failed/<FQCN>/<build|host>/`，同名构造目录保留失败状态和已生成的 RTL/FPGA 中间产物。
所有 Vivado 参与的 FPGA IP、综合和链接阶段仍实时输出；其余历史工具输出只显示阶段进度，并写入对应日志。
交互终端中，实时阶段若连续一秒没有新输出，会显示不写入日志的流水灯，收到下一条工具输出时立即清除。
算术 IP 的 Tcl 还会在 `fpga/ip-generated/logs/npc_int_multiplier_ip.log` 与
`fpga/ip-generated/logs/npc_int_divider_ip.log` 分别保存参数、复用/生成动作和最终属性报告。

构造目录带 `.incomplete`，并在启动时立即写入 `version.info` 与状态为 `building` 的 `version.tag`；这两个
索引文件与生成 RTL 位于同一稳定目录。host、RTL 和资产校验完成后标签改为 `complete`；中断或失败则改为
`interrupted` 或 `failed`。`make version` 不等待正在进行的长构造；它扫描这些索引文件，并以轻量检查将缺少
U55C 平台限定 `xclbin`、ZCU102 `npc.bit` 或保存 host 的构造显示为无效。完整 manifest/SHA 校验仍在运行预检
执行。只有旧构造尚未补齐索引文件时才等待全局锁并从保存 metadata 迁移，迁移不调用 Scala。`config-list`、
`build` 和按当前 Config 解析的运行仍会刷新 Scala 目录。

版本主表用 `+`/空白属性位图代替长 Config 名称：XLEN 分为 RV32/RV64，ISA 显示 M/F/Zicsr，流水线
固定显示 Pipe/ID/EX 三格，随后显示 `valid?`。`Arch` 和 `RunningTime` 使用文本，分别表达 NPC/SoC 与
SIM/FPGA；最右侧 `Config` 显示保存的 Config 短名，不再输出额外名称映射或可构造 Config 表。

NEMU host 的 `performanceHtml` 可选项会在运行结束时写入
`runtime/<test>/<timestamp-ns>-<pid>/performance.html`。它是报告主页，包含总体 CPI/IPC/MIPS、宿主耗时、
流水配置、stall 对比、五阶段平均占比、各 load/store/M 操作的平均与最大延迟、最近分类样本和最后提交；
同一份提交记录还会生成可搜索、分页的 `instructions.html` 逐指令明细。主页以新窗口打开可用的子报告，
子报告均可返回主页。`pipelineHtml` 是 `performanceHtml` 的提交记录子特征：本地 Verilator 复用软件记录，
U55C v12 Debug xclbin 从 HBM 回放同一格式的硬件记录；两者都不收集第二份轨迹。对应本地 host 还会同时启用 NEMU 软件逐提交自查。记录只包含已提交
指令，默认保留前 20 万条；流水页提供 PC/反汇编搜索、分页、周期缩放和 IF/ID/EX/MEM/WB 悬浮信息，超限
时继续统计丢弃条数。所有本地 NPC/SoC 仿真终端当前都启用 performance 与 pipeline；标量核心显示顺序阶段时间线，流水线核心
还会显示阶段重叠与停顿。软件自查逐条比较 NPC 与 NEMU 的 GPR、FPR、FCSR 和下一 PC，可直接报告首个
架构状态分歧；主存 store 还会核对对齐地址、总线数据、字节掩码和 beat 宽度，避免共享内存掩盖邻接
lane 破坏。它不隐含启用 VCD 或普通 instruction trace。SDB 的 `start`/`stop` 若已由 NEMU
Config 启用 VCD，则在同一运行目录依次写 `wave-001.vcd`、`wave-002.vcd`；直接运行非 construction host
时回退到当前目录。

`rebuild` 发布的是新硬件 ABI，不继承旧构造的 `runtime/`；`host-build` 与运行入口的 `host-rebuild=1` 只替换 host，
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

所有配置按 `base -> core -> 根部终端文件` 分层：`base/` 放底层键、数据、原子片段和不可直挂的
底层 trait，`core/` 形成可复用的具名完整组合，终端级内容直接放在领域根部。公共终端协议位于
`common/TerminalTraits.scala`；并列的 `common/IpTerminalTraits.scala` 只保留 FPGA 与 NEMU 两种
计算单元终端，其共享合同位于 `common/base/IpComputeSelectionTraits.scala`，并由运行 Config 显式混入。
最终无参终端位于
各领域根部 `Configs.scala`。每个终端只挂载一个 terminal 层 trait，不能直接混入 base trait。Make 每次顶层启动都会由 Scala 校验该
布局并生成派生 TSV；终端 trait 出现在领域内其他文件或终端直接混入 base trait 都会报错。选中 Config
后，SBT/Mill 反射实例化并生成 `profile.env`；Make、NEMU 和 Tcl 只消费该描述。新增终端 Config 不需要
手工登记 CSV。

根部终端文件只声明终端可直接使用的 trait。终端直接需要的子项及其集群放入 `core/`，仅各子项的
基础依赖、数据模型和原子片段放入 `base/`；终端不直接拼接多个 base trait。

CDE 的 `++` 从右向左建立基础，左侧值优先。例如板卡 SoC Config 依次叠加板卡、完整 NPC 与
`YsyxElaborateConfig`，就能替换 SoC 默认核心，同时保留 Rocket 和外设。板卡 CDE 键本身就是
FPGA 分支的唯一来源，无需重复叠加平台标签。

完整类和可复制特性见 [Config 文档](chisel/configs/README.md)。FPGA shell、产物拆分与资产格式见
[FPGA 文档](fpga/README.md)。

## 数据通路

`make build/run` 先刷新 Config 目录，再由 SBT 或 Mill 生成规范化 profile。NPC 入口通过
`ConfigResolver` 得到 `ConstructionConfig`；SoC/FPGA 入口通过 `CdeConfigResolver` 得到 CDE
`Config`，并从 `NpcCoreConfigKey` 取得完成的 L1 `NpcConfig`。每个运行终端只挂载一个与本地/板卡及
NPC/SoC 目标精确匹配的 `LocalNpcTerminal`、`LocalSocTerminal`、`U55cNpcTerminal`、
`U55cSocTerminal`、`Zcu102NpcTerminal` 或 `Zcu102SocTerminal`。这些预设已经提供完整
`NemuHostConfig` 默认值，FPGA 预设同时提供分组式 `FpgaToolchainConfig` 默认值；当前内置
`Configs.scala` 均一步挂载，不重复展开配方。每个 Config 显式混入一个计算 IP terminal，绝不通过
Config 构造参数或 CDE `++` 链单独选择。显式自定义终端仍可在保持 scope、target 与板卡匹配的
前提下重载配方。profile 据此渲染保存的 `host.defconfig` 和现有
`FPGA_*` 字段。Chisel
elaboration 生成按模块拆分的 SystemVerilog 和显式 IP source manifest；Verilator 或 Vivado/Vitis 只消费
清单列出的 RTL/XCI 与同一份 profile。综合清单会硬拒绝 DPI、NEMU MMIO 和其他仅仿真模型。
运行时 AM 只编译测试镜像，并直接执行冻结的 host、xclbin 或 ZCU102 环境清单。`reset=1` 是仅用于 FPGA
Config 的 NEMU 运行参数：U55C 会在每次 `nemu-exec` 装载 xclbin 前执行非交互的 `xbutil --batch --force reset --type user`，清除
core/mailbox reset 无法清除的 HBM/AXI 未完成事务；未传该参数时不自动 reset。U55C 的 `run-bat` 仍必须使用
`jobs=1`。默认 XRT device 0 会自动发现第一张卡的
BDF；选择其他 device 时需要同时设置 `NEMU_FPGA_XRT_BDF=<dddd:bb:dd.f>`，也可用
`NEMU_FPGA_XBUTIL` 指定 `xbutil` 路径。

普通 U55C profile 固定 `npc-fpga-runtime-v11` 调试和运行控制 ABI。`U55cRv64Npc300MHzDebugFpgaConfig`
使用独立的 `npc-fpga-runtime-v12`：除相同的 `ip_c`、`ap_ctrl_hs`、4 KiB 控制窗口和 XLEN AXI 数据宽度外，
它额外把 `m_axi_trace` 固定连接到 HBM[1]，分配 16 MiB BO，记录前 200000 条提交。片上 FIFO 为 URAM，
深度由 Config 的 `FpgaRuntimeTraceConfig.cacheRecords` 固定，默认 4096。普通 v11 xclbin 仍支持 SDB `si`、`c`、
寄存器和内存调试，但性能主页会明确标注硬件未启用监测，host-only 构造不能把 v11 变成 v12。ZCU102 使用
`npc-fpga-runtime-v7`。FPGA AM 用非标准机器 CSR `mtestexit`（`0x7c0`）报告结束，EBREAK 仍保留 breakpoint trap 语义。M 由 Xilinx 整数乘除 IP 执行，
公开 FPGA Config 固定 `F=0`、`D=0`。因此 FPGA 不生成硬件 FPR、本地 FPU、浮点 IP 或 NEMU 指令
代执行服务；本地 Verilator 构造仍保留既有浮点模型和 FPR，供学习完整 F 扩展。此 ABI 或 FPGA 配置
变化必须用 `rebuild` 更新，不能只刷新 host。

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
fpga/common/tests/config-regression.sh "$PWD"
fpga/common/tests/release-regression.sh "$PWD"
fpga/common/tests/run-fpga-rtl-test.sh "$PWD"
```

回归使用 dry-run 或 RTL 仿真，不会启动完整 Vivado/Vitis 实现。真实 U55C/ZCU102 资产只有在时序
收敛和实体板验收后才可进入 Release。
