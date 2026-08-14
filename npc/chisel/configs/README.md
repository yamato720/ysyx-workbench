# Chisel Config 层级与组合

完整 Scala Config 是硬件 ABI、运行形态和 FPGA 实现策略的唯一来源。Make 每次顶层启动会调用
Scala 检查分层并只扫描各终端领域根部的 `Configs.scala`；组合片段和 `check-only` 检查构造均不进入
公开目录。

`config=` 不提供 NEMU、DPI 或 Verilator 的可选模式。NPC/SoC 终端以 `run` 或 `batch` 能力绑定保存的
NEMU 运行宿主；本地仿真的 DPI 只是该宿主连接 Verilator 的内部实现。`synthesize-only`/`bitstream-only`
只描述加速器 FPGA 资产阶段；SPMV 终端另挂载独立软件 golden host，仍不投影 CPU/NEMU 字段。
未传 `config=` 的 AM 命令保持原有 `ARCH` 驱动的普通 NEMU 路径。

## 层级

| 层级 | 目录 | 依赖方向 | 是否可选 | 完整成品文件 |
| --- | --- | --- | --- | --- |
| 公共底层 | `common/base/` | 通用 IP 数据、计算单元选择、FPGA IP attachment、工具链字段模型和不可由终端直挂的构造接口 | 始终编译，不独立生成 | `OperatorIpConfigs.scala`、`IpComputeSelectionTraits.scala`、`FpgaIpAttachmentTraits.scala`、`FpgaToolchainConfigModels.scala`、`ConstructionTraits.scala` |
| 构造配方 | `common/core/`、`nemu/core/` | 终端直接子项集群、检查 trait、NEMU host 与 FPGA 工具链 case class；不定义硬件 ABI | 所有 Make 终端必需 | `TerminalCoreTraits.scala`、`IpTerminalCoreTraits.scala`、`CheckTraits.scala`、`NemuHostConfig.scala`、`FpgaToolchainConfig.scala` |
| 终端 trait | `common/TerminalTraits.scala`、`common/IpTerminalTraits.scala` | 前者提供通用 NPC/SoC 的 NEMU/FPGA 默认配方、目录身份、scope 和 target；后者只提供 FPGA/NEMU 两种计算单元终端 | CPU Make 终端必需；后者不进入 Make 目录 | 根部直挂文件 |
| SPMV 加速器 | `accelerators/spmv/`、`../accelerators/spmv/` | 独立参数键、host/terminal preset、CPU-free profile、reader/IP 壳与 Cuper 输入层 | SPMV 才需要 | `base/SpmvInputConfig.scala`、`base/SpmvConstructionTraits.scala`、`core/SpmvInputSimulationProfile.scala`、`TerminalTraits.scala` |
| L1 NPC | `npc/` | 仅依赖 CDE 参数库，不依赖 Rocket/板卡 | 必需 | `core/` 成品与终端 `Configs.scala` |
| L2 SoC | `ysyx/` | 依赖 L1 与 Rocket CDE | SoC 才需要 | `core/YsyxCore.scala` 与终端 `Configs.scala` |
| L3 FPGA | `npc/chisel/configs/fpga/base/` | 中性 `fpga` package，把 L1/L2 接入 FPGA CDE | FPGA 才需要 | `base/` 与 `fpga/CdeConfigResolver.scala` |
| L4 Board | `npc/chisel/configs/fpga/u55c/`、`npc/chisel/configs/fpga/zcu102/` | 依赖 L3，固定物理板卡并按产品目标拆分终端 | FPGA 必需且二选一 | `core/*BoardConfig.scala`、`{npc,ysyx,spmv}/Configs.scala` |

## 统一文件协议

所有配置都遵守 `base -> core -> 根部终端文件` 的分层：

- `base/` 只定义参数键、普通数据模型、组合协议和 `With...Config` 原子片段。它不提供终端目录身份，
  不引用 `core/`，也不直接表达某个可运行目标。
- `core/` 调用 `base/`，把 ISA、流水线、接口、内存、SoC 或板卡策略组合成名称直观、含义完整的
  成品。终端必须直接引用这些成品，不能在终端文件里重新展开底层片段。
- 终端级文件与 `base/`、`core/` 文件夹分离，直接位于领域根部。通用 NPC/SoC Make 终端预设 trait
  位于 `common/TerminalTraits.scala`；SPMV 预设位于 `accelerators/spmv/TerminalTraits.scala`；
  `common/IpTerminalTraits.scala` 只定义非 Make 的
  `FpgaIpTerminal` 与 `NemuSimulationIpTerminal`。通用计算单元合同位于
  `common/base/IpComputeSelectionTraits.scala`，让两种终端消费同一组时序属性。SPMV 的 construction、
  host preset 和 terminal trait 全部位于 `accelerators/spmv/`。每个 CPU 运行 terminal
  Config 必须显式混入对应 IP terminal；`U55cSpmvSynthesisTerminal` 与
  `U55cSpmvBitstreamTerminal` 都不挂载 CPU 算子 terminal。
  不得把 IP 作为 `ConstructionConfig`/SoC Config 的构造参数，或在 CDE `++` 链中重新选择后端。
- 根部终端文件只能声明可直接挂载的终端 trait。终端直接包含的子项和子项集群放入 `core/`，每个子项
  的基础依赖、数据模型和原子片段放入 `base/`；终端不能直接拼接多个 base trait。
- 领域根部的 `Configs.scala` 是唯一终端 Config 文件，只包含公共无参终端类。每个类只挂载一个 terminal 层
  预设，由它完整提供运行构造、作用域、目标和 NEMU/FPGA 默认配方。内置 Config 与普通示例应保持
  一步挂载；显式自定义终端可重载配方，但不得直接混入 base trait。检查构造和可复用成品也不得放入此文件。
- `common/`、`nemu/` 没有硬件终端 Config，因此不创建空的 `Configs.scala`；公共运行预设与计算单元 IP
  trait 分别放在 `common/TerminalTraits.scala` 和 `common/IpTerminalTraits.scala`。FPGA 板卡共享
  `fpga/base/`，各自在 `fpga/<board>/core/` 形成物理策略，并由板卡下
  `{npc,ysyx,spmv}/Configs.scala` 生成最终终端。

目录生成器会拒绝缺少根部 `Configs.scala` 的终端领域、出现在其他文件中的终端 trait、
`Configs.scala` 中未挂载 terminal 层 trait 的 Config 类，以及终端对 base 构造 trait 的直接混入。
板卡目录会递归发现 `npc`、`ysyx`、`spmv` 终端，并校验目录对应的 `TARGET` 与 package 所有权。
本次领域重构后的公开 FQCN 直接使用新 namespace，不提供旧 package 别名；Config 短名保持不变。

`configs/resources/` 不属于 Config 分层：它只保存自动生成的 `npc-config-catalog.tsv`，供 Scala
classpath、Make 和 construction manager 在启动前解析公开终端。该目录必须保留，不能手工编辑 catalog；
`configs/platform/` 没有实现或引用，已删除。

## 可直接选择的 Config

下表所有 Config 都可以传给 `make ... config=<名称>`，能力可以是 `run`、`batch` 或
`synthesize-only` 或 `bitstream-only`。短名和 FQCN 均可用；每个类
必须有公共无参构造器，并分别满足 `ConstructionConfig` 或 CDE `Config` 类型约束。

| 名称 | 作用域 | 用途 |
| --- | --- | --- |
| `StandaloneConfig` | NPC | 最小 RV64I_Zicsr 本地仿真 |
| `SimulationConfig` | NPC | 默认 RV64IM_Zicsr 本地仿真 |
| `CacheSimulationConfig` | NPC | 显式启用教学 I$/D$、Zifencei 和 4 项 instruction buffer 的 RV64IM 本地仿真 |
| `HbmJitterCacheSimulationConfig` | NPC | L1-only 宽 512-bit DPI、64-byte I$/D$ 和固定种子 73--81 cycle 主存响应的 U55C 宽 L1 功能时序对比 |
| `HbmJitterL2CacheSimulationConfig` | NPC | 上述宽 L1 模型加统一 256 KiB、8-way、64-byte L2 的本地 DPI 时序对比 |
| `HbmJitterCacheVcdSimulationConfig` | NPC | 上述宽 L1 本地时序模型，加可由 SDB `start`/`stop` 控制的 Verilator VCD；需完整构造 |
| `PipelinedTwoCycleWideL2SimulationConfig` | NPC | 仅本地仿真的 RV64IM 两拍 I$/D$/L2，512-bit DPI Fabric；命中 N+2 返回，四项 FIFO 深度和 8 项 instruction buffer 固化 |
| `PipelinedTwoCycleWideL2NoCompletionForwardingSimulationConfig` | NPC | 与两拍 I$/D$/L2 端点相同，但关闭完成表结果前递，供本地 A/B 对照 |
| `PipelineSimulationConfig` | NPC | 启用 ID/EX 前递的流水 NPC 本地仿真 |
| `FullIsa64NoPipelineSimulationConfig` | NPC | RV64IMF_Zicsr 无流水线性能基线 |
| `FullIsa64PipelineNoForwardingSimulationConfig` | NPC | RV64IMF_Zicsr 流水线无 ID/EX 前递 |
| `FullIsa64PipelineDualForwardingSimulationConfig` | NPC | RV64IMF_Zicsr 流水线双路径前递 |
| `Zcu102Rv32OperatorSimulationConfig` | NPC | ZCU102 RV32 M/F IP 时序的本地周期模型 |
| `U55cRv32OperatorSimulationConfig` | NPC | U55C RV32 M/F IP 时序的本地周期模型 |
| `U55cRv64OperatorSimulationConfig` | NPC | U55C RV64 M/F IP 时序的本地周期模型，覆盖 W 指令 |
| `YsyxSimulationConfig` | SoC | 默认 ysyxSoC 本地仿真 |
| `CacheYsyxSimulationConfig` | SoC | 显式启用教学缓存层级的 ysyxSoC 本地仿真 |
| `U55cNpcFpgaConfig` | FPGA（`TARGET=NPC`） | U55C 裸 NPC 上板运行 |
| `U55cCacheNpcFpgaConfig` | FPGA（`TARGET=NPC`） | U55C RV32 教学缓存裸 NPC；需要独立构造 |
| `U55cRv64NpcFpgaConfig` | FPGA（`TARGET=NPC`） | U55C RV64IM_Zicsr 裸 NPC 上板运行 |
| `U55cRv64Npc300MHzFpgaConfig` | FPGA（`TARGET=NPC`） | U55C RV64IM_Zicsr 300 MHz 单实现时序实验 |
| `U55cRv64CacheNpc300MHzFpgaConfig` | FPGA（`TARGET=NPC`） | 上述 RV64 300 MHz 核心的教学缓存版本 |
| `U55cRv64Npc{100,125,150,200,250,300}MHzPerformanceMonitorFpgaConfig` | FPGA（`TARGET=NPC`） | U55C RV64IM 批处理性能监测；核心按后缀运行 |
| `U55cRv64CacheNpc150MHzPerformanceMonitorFpgaConfig` | FPGA（`TARGET=NPC`） | U55C RV64 150 MHz 教学缓存批处理性能监测；核心通过 MMCM/FIFO 接入固定 300 MHz 平台，报告读取硬件 cache mailbox 状态 |
| `U55cRv64CacheNpc300MHzPerformanceMonitorFpgaConfig` | FPGA（`TARGET=NPC`） | U55C RV64 300 MHz 教学缓存批处理性能监测；报告读取硬件 cache mailbox 状态 |
| `U55cRv64Hbm512CacheNpc150MHzPerformanceMonitorFpgaConfig` | FPGA（`TARGET=NPC`） | RV64 CPU/64-bit MMIO 加 512-bit HBM、64-byte L1 line 的 batch 性能监测基线 |
| `U55cRv64Hbm512L2CacheNpc150MHzPerformanceMonitorFpgaConfig` | FPGA（`TARGET=NPC`） | 上述宽 HBM L1 基线加统一 256 KiB/8-way/64-byte write-back L2；需独立 rebuild |
| `U55cYsyxSocFpgaConfig` | FPGA（`TARGET=SOC`） | U55C ysyxSoC 上板运行 |
| `U55cCacheYsyxSocFpgaConfig` | FPGA（`TARGET=SOC`） | U55C ysyxSoC 教学缓存版本 |
| `U55cSpmv32PcFp32X8192UramResourceProbeConfig` | FPGA（`TARGET=SPMV`） | 32 路 HBM、每路 8192 项 FP32 UltraRAM X cache 的只综合资源探针 |
| `U55cSpmv32PcFp64X8192UramBitstreamConfig` | FPGA（`TARGET=SPMV`） | 32 路 HBM、每路四 bank 双端口 FP64 X cache 的 bitstream 压力探针 |
| `SpmvInputSimulationConfig` | SPMV | 16 路 A、2 路 X 条带输入顶层的 Verilator 结构 smoke |
| `Zcu102NpcFpgaConfig` | FPGA（`TARGET=NPC`） | ZCU102 裸 NPC 上板运行 |
| `Zcu102YsyxSocFpgaConfig` | FPGA（`TARGET=SOC`） | ZCU102 ysyxSoC 上板运行 |

## 可复用的组合与检查

这些类只能在 Scala `++` 链中使用，不能传给 `make config=`。它们不会独立产生没有运行宿主的
“生成型构造”。

| 名称 | 层级 | 用途 |
| --- | --- | --- |
| `FpgaConfig` | L1 | 默认 FPGA 裸 NPC 核心成品 |
| `CacheFpgaConfig` | L1 | 显式教学缓存的 RV32 FPGA 裸 NPC 核心成品 |
| `CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig` | L1 | U55C RV64 300 MHz 时序核心的缓存成品 |
| `WideHbmCacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig` | L1 | U55C 512-bit HBM、64-byte L1 line 的 RV64 缓存成品 |
| `WideHbmL2CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig` | L1 | 上述宽 HBM 成品加 Fabric 内统一 L2；其下游保持 AXI4-Full |
| `Rv64PipelineDualForwardingFpgaConfig` | L1 | RV64IM_Zicsr FPGA 核心成品；F/D 仅由本地仿真 Config 提供 |
| `ExternalAxiConfig` | L1 | 供 SoC/外部系统集成的 AXI NPC 成品 |
| `CacheExternalAxiConfig` | L1 | 供 SoC/外部系统集成的教学缓存 AXI NPC 成品 |
| `YsyxSocConfig` | L2 | ysyxSoC 的默认组合图 |
| `YsyxElaborateConfig` | L2 | 供板卡或直接 Scala elaboration 叠加的 ysyxSoC 图 |
| `PipelineCheckConfig`、`FloatingCheckConfig`、`MulDivCheckConfig` | L1 | 仅供 Scala/RTL 检查的 `check-only` 构造 |
| `SpmvAcceleratorConfig`、`WithSpmvAcceleratorConfig` | SPMV | 可支持 32/64-bit element 的参数模型与 CDE 绑定片段，供 FP32/FP64 终端使用 |

运行终端同样继承 CDE `Config`，因此类型上也能放入更高层 `++` 链；但这不是默认复用方式。它会完整
带入自己的 AXI、主存和算子 ABI，只有这些接口与目标系统兼容时才可覆盖上层默认核。常规 SoC/FPGA
组合应优先使用上表的 L1 核或 SoC 图，而不是复用本地仿真终端。

## 组合规则

CDE 和本项目 L1 Config 的 `++` 都是左侧优先：右侧先提供基础值，左侧覆盖。推荐每层一行：

```scala
class U55cYsyxSocFpgaConfig extends CDEConfig(
  new _root_.fpga.u55c.U55cBoardConfig ++
    new FpgaConfig ++
    new YsyxElaborateConfig
) with _root_.npc.U55cSocTerminal with _root_.npc.FpgaIpTerminal
```

这里 `YsyxElaborateConfig` 提供 Rocket/SoC 和默认外部 AXI NPC；完整 L1 `FpgaConfig` 自身就是
`CDEConfig`，在其左侧直接覆盖核心。`U55cBoardConfig` 的 `FpgaBoardKey` 同时选择物理板卡和 FPGA
硬件分支。

所有本地 NPC/SoC 仿真终端都通过 `LocalNpcTerminal` 或 `LocalSocTerminal` 取得本地构造行为与默认的
`NemuHostConfig.LocalPipelineTrace`，启用已提交指令的
性能主页、逐指令明细和 IF/ID/EX/MEM/WB HTML。流水线构造会显示阶段重叠和停顿；标量构造使用同一组提交级驻留计数，显示
顺序执行时间线；同一 host preset 还会逐提交比较 NPC 与 NEMU 的 GPR、FPR、FCSR、下一 PC 和主存
store 总线副作用。该 preset 的 `cacheHtml` 也是显式开关：缓存 Config 的运行会额外生成 `cache.html`，性能主页
链接到该页；无缓存 Config 不生成空报告。自定义终端可用
`NemuHostConfig.LocalPipelineTrace.copy(cacheHtml = false)` 关闭它。
FPGA 上板和 check-only Config 保持关闭。

`HbmJitterCacheSimulationConfig` 使用 local DPI RAM，而非 FPGA AXI/HBM：一个 512-bit RAM 事务并行
展开为八个稳定 64-bit DPI ABI lane，并在 AXI-Lite 读/写响应前插入固定种子 73--81 cycle 等待。
这使一次 64-byte L1 refill 保持为一次有延迟的 line transaction；MMIO 仍收窄为即时 32/64-bit DPI
访问。该模型适合对比 L1 hit/miss 停顿和本地 HTML 轨迹，不代表 U55C HBM 控制器的逐周期仿真。

`HbmJitterL2CacheSimulationConfig` 复用同一 DPI 抖动和宽 L1，仅把 NpcMemoryFabric 的本地主存路径
改为 IF/LSU 仲裁器 -> 统一 L2 -> 512-bit DPI RAM；MMIO 仍在 L2 外部旁路。它是本地功能时序模型，
用于比较 L2 命中收益，不等同于 FPGA HBM bank/queue 的完整时序。

`PipelinedTwoCycleWideL2SimulationConfig` 选择 `CacheAccessMode.PipelinedTwoCycle`，并将
`CACHE_REQUEST_QUEUE_DEPTH`、`CACHE_RESPONSE_QUEUE_DEPTH`、`CACHE_FETCH_QUEUE_DEPTH` 与
`CACHE_MEMORY_QUEUE_DEPTH` 全部冻结为 4。I$/D$/L2 使用同步 `CacheStorage.Auto` 阵列：S0 锁存并发起
读，S1 比较 tag，命中响应经有序 FIFO 在 N+2 可见。local DPI 分支以轮转 I$/D$ 仲裁和返回路由 FIFO
服务单端口 L2；MMIO 保持阻塞、非缓存、按序。redirect/FENCE.I 通过取指 epoch 丢弃旧响应，维护操作在
入口、流水和 FIFO 为空后执行。该端点不能用于 FPGA/SoC/外部 AXI 构建。
同层级的 `PipelinedTwoCycleWideL2NoCompletionForwardingSimulationConfig` 只关闭完成表
前递开关，保留相同的缓存层次、DPI 和队列深度，适合以 `make config=` 比较该旁路的周期收益。

`HbmJitterCacheVcdSimulationConfig` 只覆盖运行 host 为 `NemuHostConfig.LocalVcdTrace`，其 L1 和
DPI 时序参数与前者一致。由于 VCD 要求 Verilator 在生成时带 `--trace`，它是单独的仿真 ABI：新建时使用
`make -C npc build config=HbmJitterCacheVcdSimulationConfig`，已有构造更新时使用 `rebuild`，不能以
`host-build` 把无 VCD 的 Verilator 库升级为可追踪版本。运行目录中的 VCD 由 SDB `start`/`stop` 分段产生。

`HostConstruction`、`AcceleratorHostConstruction`、`NemuSimulationConstruction`、`FpgaConstruction` 和 `MakeTerminal` 是
`common/base/ConstructionTraits.scala` 中的底层接口，只供 terminal 层组合，终端不能直接混入。
`LocalNpcTerminal`、`LocalSocTerminal`、`U55cNpcTerminal`、`U55cSocTerminal`、`Zcu102NpcTerminal`、
`Zcu102SocTerminal` 是 `common/TerminalTraits.scala` 中的通用 Make 终端预设；每个终端只挂载其中一个。
工具链按 `device`、`flow`、`reports`、`runtime` 分组。显式自定义终端
可通过嵌套 `copy(...)` 局部重载 NEMU/FPGA 配方；重复使用或需要进入普通示例的配方应提升为 `core/`
中的具名完整 preset，必要时再增加根部 terminal trait。`CheckOnlyConstruction` 是检查构造直接挂载的
core trait，不进入 Make 目录。公共构造 trait 名称不使用 `Trait` 后缀，承载这些 trait 的文件统一使用
`*Traits.scala`。自动目录不再从类名后缀猜测目标；所有硬件参数应在可追踪的 Scala 组合中固定。

`IpTerminalTraits.scala` 的 `FpgaIpTerminal` 和 `NemuSimulationIpTerminal` 分别将同一份算子时序映射为
FPGA M backend 与本地 M/F 功能模型。通用合同和 FPGA 非终端选择留在
`common/base/IpComputeSelectionTraits.scala`；`FpgaIpAttachment` 同样只依赖该 base 合同，仍是不可变的
IP provider、M 路由和时序合同。L4 板卡 Config 选择 attachment，NPC 与 SoC FPGA 顶层从同一 CDE 键读取。
`IpConstruction` 与 `HostConstruction` 平行：运行 terminal Config 显式混入前者，`ConstructionConfig` 与
`WithTerminalIpCoreConfig` 只读取它。`FpgaPlatformSettings` 只保存物理地址和时钟，避免与 IP 合同重复。

各目录 README 提供可直接复制到 `++` 链的完整特性表。

## Make 与 profile

```bash
make -C npc config-list
make -C npc build config=SimulationConfig
make -C npc build config=U55cYsyxSocFpgaConfig
make -C npc build config=U55cSpmv32PcFp32X8192UramResourceProbeConfig
make -C npc build config=U55cSpmv32PcFp64X8192UramBitstreamConfig
make -C npc build-host config=U55cSpmv32PcFp64X8192UramBitstreamConfig
make -C npc run config=U55cSpmv32PcFp64X8192UramBitstreamConfig mainargs=n512
make -C npc build config=SpmvInputSimulationConfig
make -C npc run config=SpmvInputSimulationConfig
make -C npc host-build config=U55cRv64Npc300MHzFpgaConfig
make -C npc host-build version=1
make -C npc rebuild config=U55cRv64Npc300MHzFpgaConfig
make -C npc rebuild version=1
```

自动 TSV 只在 Make 启动 JVM 前提供短名、FQCN、作用域、板卡和目标。选中后由 Scala 反射实例化，
生成包含 XLEN、ISA、流水线、缓存几何/策略/存储风格、算术时序、内存、板卡、工具策略、运行宿主 ABI 和协议 ABI 的
`profile.env`。NEMU 配方以稳定的 `NEMU_PRESET` 和完整 `NEMU_*` 字段记录；FPGA 工具链继续渲染为
现有 `FPGA_*` 字段。其中 `NEMU_PERFORMANCE_HTML` 与 `NEMU_PIPELINE_HTML` 是 NEMU 层行为；后者只能在前者之上
启用。普通 U55C v11 终端固定全部 trace 字段和两项 HTML 开关为关闭；
`U55cRv64Npc{100,125,150,200,250,300}MHzPerformanceMonitorFpgaConfig` 是例外，它们以 `batch` capability 固定
HBM[1]、8 MiB、200000 条 32-byte 记录、2048-record URAM FIFO、256-bit trace AXI 和 16-record burst，
并启用两项 HTML。U55C 标准平台的 HBM data-kernel 时钟为 300 MHz；链接后会从 xclbin 的 `DATA_CLK`
校验这一接口频率。profile 另以 `FPGA_CLOCK_MHZ` 冻结核心目标频率，低于 300 MHz 时 wrapper 使用 MMCM 和
逐通道异步 FIFO，确保核心不超过该值。每个频点必须完整 `rebuild`，host-only 构造不能为外部 v11 xclbin 提供硬件记录。
Make/Tcl 只做映射与一致性检查，不能覆盖这些值。
SPMV profile 以 `HOST_ABI=none` 表示正式 FPGA 资产不含 NEMU/XRT runtime host，并独立记录
`ACCELERATOR_HOST_KIND=spmv`、`ACCELERATOR_HOST_ABI=spmv-golden-v1`、32 路 HBM AXI 几何、X cache 容量/存储风格、burst/outstanding
和并行读写带宽，不生成 XLEN、ISA、流水线或 CPU cache 层级字段。FP32 终端使用
`PROTOCOL_ABI=spmv-resource-probe-v1`，只有 elaboration 与 OOC synthesis 两阶段；FP64 终端使用
`spmv-resource-probe-v2`，增加 Vitis link 并发布 xclbin。两者都不构造 NEMU/XRT host；`build-host`
分发到 `accelerator-sim/spmv`，全局 `run mainargs=<规模>` 读取 `accelerator-sim/data` 并只执行
CPU golden，不要求 RTL/xclbin。共享数据由 `make -C accelerator-sim/data` 下载或生成，
`ACCELERATOR_DATA_ROOT` 可覆盖默认目录。

`SpmvInputSimulationConfig` 使用独立 `scope=spmv` 和 `LocalSpmvInputTerminal`，不继承 NPC/SoC/NEMU
或 FPGA 构造。`SpmvInputConfig.Cuper16Hbm` 描述一个 16-HBM A 输入封装、一个 2-HBM X 输入封装和
一路控制面 HBM，合计 19 个只读 HBM master；其中 16 个 HBM channel 是 Cuper A 编码几何，
不包含两路 X 和一路控制 HBM。
`ip-interface` 的 `npc.ip.axi` 提供 NPC 与加速器共同使用的唯一 AXI4 只读、只写和读写契约；HBM
只描述这些端口的物理用途。`SpmvAInput`/`SpmvXInput`/`SpmvCtrlInput` 继承
`accelerators.common.IndependentAxiReadPorts`，以具体 reader 工厂和
`hbmCount` 参数选择 A/X/控制面输入；每个 HBM 保留独立 request、AXI 和 output stream，公共基础模块不负责
仲裁或数据拼接。对应的 `IndependentAxiWritePorts`、`IndependentAxiReadWritePorts` 也位于
`accelerators.common`，控制面之后若要给 JPCG 写回可换到读写封装。地址窗口为
`0x80000000`/128 MiB，4 KiB channel 对齐，
AXI 参数为 64/512/4，reader 支持 2 笔 outstanding burst。profile 固定
`ACCELERATOR_HOST_ABI=spmv-input-report-v10`、`PROTOCOL_ABI=spmv-input-windowed-v9`，输出输入布局、
16 个消费端、两路 X 条带原子广播和一路控制面广播。独立的 `SpmvInputReportConfig` 输出 `SPMV_PERFORMANCE_HTML` 与
`SPMV_PIPELINE_HTML`；后者要求前者开启，公开 Config 使用 `PerformancePipeline` preset。不输出
`SPMV_X_MODE`、CSR5、NEMU、CPU 或 FPGA
字段。正式构造严格执行 `elaborate -> verilator -> accelerator-host`，并只发布到
`abi/{rtl,verilator,spmv}`。

transaction host 先广播 Cuper 控制 map，再把 FP64 `b.txt` 的 512-bit beat 按全局偶/奇序号条带化到
X0/X1；两路 X 可同周期向全部消费端原子广播两个 beat，奇数总 beat 数时由 X0 单独交付尾 beat。
X 载入完成后 host 拉高 `mulEnable`，`mulReady` 立即拉高后驱动 16 路 A。唯一一次 A 读取同时
送入消费端做 checksum，并由 Mixed-V3 引擎通过公共算术 IP `req/resp` 接口完成 FP64 乘法验证。
模拟 AR ready 恒高；每路 reader 用 2 笔 outstanding burst 隐藏 4 KiB 边界 AR 延迟。Ctrl/X 的 R
逐拍连续，A 由每拍一个 A beat、下一拍 8-lane FP64 发射的引擎背压。host 校验阶段顺序、burst、beat 数、checksum 和错误状态，
并按 `runtime/<dataset>/<run>/` 写入 `performance.html` 主页和可选的 `input-pipeline.html`、
`timing-pipeline.html` 子页。主页沿用
construction 报告的指标、统计 band 和表格布局；`timing-pipeline.html` 区分 A beat 前端连续性与实际
FMUL 活跃拍，并把全 padding beat 标为数据空窗而不是控制气泡；流水页沿用全屏搜索、缩放与横向时间线布局。
矩阵能放入 8192 列窗口时会对照编码 slot 的 FP64 乘积 checksum；更大的矩阵仍按相同 `Ctrl -> X -> A`
单遍输入顺序校验，但跳过乘法。
缓存 profile 还会写入 `CACHE_ACCESS_MODE` 与四项 `CACHE_*_QUEUE_DEPTH`。缓存 FPGA 终端会把相同的
`ICACHE_*`、`DCACHE_*`、`L2CACHE_*`、`INSTRUCTION_BUFFER_*` 和 `NPC_ZIFENCEI`
写入 elaboration manifest。任何几何、策略或存储风格变化都必须完整 `rebuild`；普通无缓存终端的字段
保持 disabled，外部 AXI/HBM 端口不变。
`host-build` 在没有正式构造时，对本地 NPC/SoC 仍创建 `constructions/.hosts/<FQCN>/`；对 FPGA 则创建
`constructions/.compatible/<FQCN>/`，并预先建立 `fpga/artifacts/`。外部平台生成的 U55C
`npc-<FPGA_PLATFORM>.xclbin` 或 ZCU102 `npc.bit`/`npc-zcu102.env` 放入该目录后，兼容 host 可直接运行。
同一 Config 的兼容资产优先于正式 Vivado/Vitis `fpga/artifacts/`，但不进入 `make version`，也不会生成
RTL 或正式版本标签。`build-host` 与 `rebuild-host` 都是 `host-build` 的公开别名；`rebuild` 才会生成并替换
完整硬件 ABI 和 FPGA 资产。
