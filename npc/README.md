# NPC Config 驱动的构造与运行

NPC 使用命名 Scala Config 固定硬件 ABI、运行宿主和 FPGA 实现策略。Make 不再接受结构参数覆盖，
也不再维护四位快照；一个完整 Config 在 `constructions/<FQCN>/` 中只保留一份成功构造。

稳定 IP 契约、厂商无关逻辑和仿真模型位于独立的 `chisel/ip-interface/` SBT/Mill 模块。该模块只依赖
Chisel；rv-core 保留 ISA 译码与操作码映射，ysyxSoC 保留 Diplomacy node 和地址映射，`fpga/` 只绑定
板卡 provider 与物理工程，`fpga-ip-generator/` 保存厂商 IP 配方。

`config=` 只选择硬件终端，不选择 NEMU、DPI 或 Verilator 模式。NPC/SoC 的 `run`、`batch` 终端绑定
一套保存的 NEMU 运行宿主；本地仿真的 DPI 只是该宿主连接 Verilator 模型的内部桥接。
SPMV 的 `synthesize-only` 和 `bitstream-only` 只描述 FPGA 资产阶段；终端另以
`AcceleratorHostConstruction` 挂载纯软件 golden host，不构造或运行 NEMU/XRT host，也不进入 Chisel RTL 仿真。
本地唯一的 `scope=spmv` 运行端点是 `SpmvInputSimulationConfig`，只构造输入顶层并执行结构 smoke，
不包含 CSR5、计算 RTL、DPI 或 SoftFloat。

## 常用命令

查看、生成和管理构造：

```bash
# 这些目标也可从工作区根目录发起；rebuild/host-build 可用 version=<编号>选择保存构造。
make -C npc config-list
make -C npc build config=SimulationConfig
make -C npc build config=CacheSimulationConfig
make -C npc build config=HbmJitterCacheSimulationConfig
make -C npc build config=HbmJitterL2CacheSimulationConfig
make -C npc build config=HbmJitterCacheVcdSimulationConfig
make -C npc build config=PipelinedTwoCycleWideL2SimulationConfig
make -C npc build config=PipelinedTwoCycleWideL2NoCompletionForwardingSimulationConfig
make -C npc build config=U55cYsyxSocFpgaConfig
make -C npc rebuild config=U55cYsyxSocFpgaConfig
make -C npc build config=U55cSpmv32PcFp32X8192UramResourceProbeConfig
make -C npc rebuild config=U55cSpmv32PcFp32X8192UramResourceProbeConfig
make -C npc build config=U55cSpmv32PcFp64X8192UramBitstreamConfig
make -C npc rebuild config=U55cSpmv32PcFp64X8192UramBitstreamConfig
make -C npc build config=SpmvInputSimulationConfig
make -C npc build-host config=SpmvInputSimulationConfig
make -C npc run config=SpmvInputSimulationConfig
make -C npc rebuild version=1
make -C npc resume-post-link config=U55cRv64CacheNpc150MHzPerformanceMonitorFpgaConfig
make -C npc host-config-list
make -C npc host-build config=U55cRv64Npc300MHzFpgaConfig
make -C npc build-host config=U55cRv64Npc300MHzFpgaConfig
make -C npc rebuild-host version=1
make -C npc host-build version=1
make -C npc host-build all=1 jobs=-1
make -C npc build-host config=U55cSpmv32PcFp64X8192UramBitstreamConfig
make -C npc run config=U55cSpmv32PcFp64X8192UramBitstreamConfig mainargs=n512

make -C npc version
make -C npc version config=SimulationConfig
make -C npc version version=1
make -C npc version D=1
make -C npc version delete=1
make -C npc version D=1-2-3
make -C npc version delete=1,2,3
```

CPU 测试的正式运行入口位于 `am-kernels/tests/cpu-tests`：

```bash
make -C am-kernels/tests/cpu-tests run ALL=add config=SimulationConfig
make -C am-kernels/tests/cpu-tests run-bat ALL="add div" config=YsyxSimulationConfig
make -C am-kernels/tests/cpu-tests run-bat ALL="add div fence fence-i" config=CacheSimulationConfig
make -C am-kernels/tests/cpu-tests run-bat ALL="add div if-else" config=HbmJitterCacheSimulationConfig
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
`Version RV32 RV64 M F Zicsr Pipe ID EX valid?`；`Arch` 以 `NPC`/`SoC`/`SpMV` 显示，
`RunningTime` 以 `SIM`/`FPGA`/`SYNTH` 显示，最右侧 `Config` 为对应保存构造的短名。
`valid?` 为 `+` 时该正式构造当前资产完整；进行中的构造和缺少必要资产的构造保留显示
但为空。`D=<序号列表>` 与 `delete=<序号列表>` 都会删除对应构造并紧凑重映射后续序号。列表可用逗号或
连字符分隔，例如 `D=1,2,3` 与 `D=1-2-3`；所有目标都按删除前的同一张版本表先解析，最后才统一删除和
重编号，不需要根据中途变化的编号重复执行。两个别名同时给出时必须表示同一集合，顺序与分隔符可以不同。

## 独立 SPMV 输入 smoke

`SpmvInputSimulationConfig` 固定 `SpmvInputConfig.Cuper16Hbm`：16 个独立 A reader、1 个 X reader、
16 个 HBM channel，地址窗口为 `0x80000000` 起始的 128 MiB，channel 基地址按 4 KiB 对齐，AXI
参数为 64-bit 地址、512-bit 数据和 4-bit ID。`SpmvInputTop` 只展开 reader 与 HBM Bundle，暂不连接
计算、CSR5、结果输出或实际存储模型。

正式构造严格只有 `elaborate -> verilator -> accelerator-host` 三阶段，资产位于 `abi/rtl`、
`abi/verilator` 和 `abi/spmv`，不会创建 `abi/nemu`、`abi/softfloat` 或 FPGA 目录。profile 固定
`ACCELERATOR_HOST_ABI=spmv-input-smoke-v1`、`PROTOCOL_ABI=spmv-input-v1`，只包含 `SPMV_INPUT_*`
布局字段和通用构造字段。host 复位顶层后检查 16 路 A 与 1 路 X 的 idle、无 AR、无 output、无 error
状态；它不验证 HBM 读写或 SpMV 数值结果。

独立的 `make -C accelerator-sim/spmv encoding-test`、`cuper-a-test` 和 CPU golden 仍可单独使用，
它们不依赖该 smoke RTL。输入布局通过构造 profile 传给 Cuper A 编码 smoke；CPU golden 仍读取
`accelerator-sim/data`，由 `ACCELERATOR_DATA_ROOT` 覆盖数据目录。

## U55C SPMV 资源探针

`U55cSpmv32PcFp32X8192UramResourceProbeConfig` 是 `TARGET=SPMV`、
`CAPABILITY=synthesize-only` 的正式构造。它在 U55C 300 MHz `ap_clk` 上暴露
`m_axi_pc00` 至 `m_axi_pc31` 共 32 个 64-bit 地址、512-bit 数据、4-bit ID 的只读 AXI4 master；
每路读取自己的 32 KiB X 区，共 8 个 64-beat burst。每个返回 beat 拆成 16 个 FP32 bit pattern，
写入独立 `8192 x 32` UltraRAM，再按 1 element/cycle 扫描 XOR。该构造只执行
`elaborate -> ooc-synth`，发布 XO、DCP、两份 utilization report、timing summary 和 SHA-256。

`U55cSpmv32PcFp64X8192UramBitstreamConfig` 是 `CAPABILITY=bitstream-only` 的进一步压力探针：
每个 PC 存放 8192 个 64-bit X（每路 64 KiB，32 路合计 2 MiB），拆成四个独立的
`2048 x 64` 双端口 UltraRAM bank。加载一个 512-bit beat 时，四个 bank 的 A/B 端口同时写入
8 个 FP64；加载完成后每拍从每个 bank 读两个地址，八个 X 同时进入 XOR 汇总。因而这里的“8 路”
是缓存读写带宽，不是八个浮点运算单元；探针仍不实例化 FP add、mul 或 FMA。

该构造的阶段为 `elaborate -> ooc-synth -> Vitis link`，只发布 XO、DCP、普通/层次化 utilization、
timing summary、xclbin 和 SHA-256，不生成 NEMU/XRT host。AXI-Lite 仍使用 `ap_ctrl_hs`，32 个基地址
位于 `0x010 + 8*i`，聚合 checksum、done mask 和 error mask 位于 `0x110`、`0x114`、`0x118`；
两种 Config 都可通过 `build-host` 构建 `accelerator-sim/spmv` 的纯 CPU host，并通过全局
`run mainargs=<规模>` 读取 `accelerator-sim/data` 的共享 CSR 数据、计算 FP64 `Y=A*X` golden。
数据由独立的 `make -C accelerator-sim/data` 下载或生成，也可用 `ACCELERATOR_DATA_ROOT` 覆盖。
该入口不要求先完成
FPGA 构造，不加载 xclbin，也不启动 Chisel RTL 仿真；`run-bat` 仍只服务 NPC/SoC AM 用例。

2026-08-04 在 `xcu55c-fsvh2892-2L-e`、Vivado/Vitis 2022.2 上对 FP64/8-lane 版本的压力结果如下。
OOC 综合得到 128 个 URAM288、15619 LUT、22279 FF、288 个 CARRY8、0 DSP；`ap_clk=225 MHz`
的 OOC setup WNS 为 +1.705 ns，但 hold WHS 为 -0.074 ns。接入 U55C 平台并完成 synthesis、placement、
routing 后，kernel 本身使用 128/960 URAM（13.33%）、15622 LUT（1.47%）和 22279 FF（0.98%）；
全设计包含平台互连后为 254284 LUT（19.51%）、350924 FF（13.46%）、200 个 Block RAM Tile（9.92%）
和 128 URAM（13.33%），32 路 HBM 互连另占 197 个 RAMB36/FIFO。URAM 集中在单个 SLR 时该 SLR
达到 40%，这是比总量更值得关注的放置压力。

该次 225 MHz routed timing 的最差 setup 为 `clk_out1_ulp_clk_wiz_0 -> ap_clk` 的 -1.516 ns；
`ap_clk` 内部高扇出控制寄存器路径为 -0.928 ns，反向时钟路径为 -1.242 ns，HBM 互连自身的
`hbm_aclk` 最差为 -0.130 ns。因此 Vitis 完成布局布线但没有接受最终 xclbin；这组数据回答的是
32-PC/8-lane 设计的资源和实现压力，不宣称 225 MHz 已经实现 bitstream closure。

## 可配置缓存

缓存必须由独立终端显式启用；`SimulationConfig`、`YsyxSimulationConfig` 以及普通 U55C/ZCU102
终端仍保持原来的无缓存 RTL 和外部端口。公开缓存终端为 `CacheSimulationConfig`、
`HbmJitterCacheSimulationConfig`、`HbmJitterCacheVcdSimulationConfig`、
`PipelinedTwoCycleWideL2SimulationConfig`、
`PipelinedTwoCycleWideL2NoCompletionForwardingSimulationConfig`、
`CacheYsyxSimulationConfig`、`U55cCacheNpcFpgaConfig`、`U55cRv64CacheNpc300MHzFpgaConfig` 和
`U55cCacheYsyxSocFpgaConfig`。`U55cRv64CacheNpc{150,300}MHzPerformanceMonitorFpgaConfig` 是
缓存版 U55C batch-only 性能监测终端；宽 HBM 的 L1 基线与 L1+L2 终端分别为
`U55cRv64Hbm512CacheNpc150MHzPerformanceMonitorFpgaConfig` 和
`U55cRv64Hbm512L2CacheNpc150MHzPerformanceMonitorFpgaConfig`。

教学预设固定为 4 KiB、16-byte line、2-way Tree-PLRU 的 I$/D$，D$ 使用 write-back 与
write-allocate，顺序 instruction buffer 为 4 项。`CacheGeometry` 自动从容量、line 和映射方式推导
set、way、tag/index/offset；替换、分配、写策略和 `Auto`/`Registers`/`Uram` 存储风格可由
`npc/base/CacheConfigs.scala` 的片段覆盖。`Uram` 只允许 FPGA 构造。

I$/D$ 位于前后端与 `NpcMemoryFabric` 之间，只缓存主存范围；MMIO 保持单拍 AXI-Lite 旁路。教学预设的
line refill 和 dirty writeback 逐个 XLEN beat 完成，不改变 AXI4/HBM 物理接口。基础 ISA 的任意 `FENCE`
pred/succ 组合按保守完整屏障执行：等待旧事务、drain D$ 后才放行后续指令，但不失效 I$。`FENCE.I`
会在同样的 D$ drain 后 invalidate I$，并丢弃 instruction buffer 与未完成取指中的年轻内容。FPGA
`mtestexit` 同样先等待 D$ drain；mailbox 在复位核心前锁存 I$/D$ 配置和统计，供性能页读取该次运行的
完成态计数。提交级 store 调试事件保持 self-difftest
看到的是架构 store，而不是延后的物理 writeback。

缓存会改变硬件 ABI。首次 FPGA 构造使用 `build`；已有同名缓存 FPGA 构造必须执行例如
`make -C npc rebuild config=U55cCacheNpcFpgaConfig`，`host-build` 不能把旧 xclbin 变成缓存硬件。

`U55cRv64Hbm512CacheNpc150MHzPerformanceMonitorFpgaConfig` 是独立的宽 HBM ABI：CPU 与
MMIO 仍使用 RV64 的 64-bit Lite 请求，但 I$/D$ 均为 4 KiB、64-byte line、2-way Tree-PLRU，
`m_axi_gmem` 为 512 bit。一次 line refill 或 D$ dirty writeback 恰好是一笔完整的 512-bit AXI
访问；它不能与既有 16-byte cache xclbin 互换，必须单独 `rebuild`。

`U55cRv64Hbm512L2CacheNpc150MHzPerformanceMonitorFpgaConfig` 在这个宽 L1 ABI 之后增加
一个统一的 256 KiB、64-byte line、8-way Tree-PLRU L2，采用 write-back + write-allocate。拓扑是
`I$/D$ -> NpcMemoryFabric arbiter -> unified L2 -> 512-bit AXI4-Full -> HBM`；MMIO 在进入仲裁器前
已经由 host-MMIO slave 消费，所以不会进入 L2。它保留 AXI4-Full 作为下游可寻址内存协议，不能使用
AXI-Stream：后者没有 load/store 所需的地址、独立读响应与错误语义。FENCE、FENCE.I 和 `mtestexit`
drain 的顺序均为 D$ -> L2 -> HBM。该 L2 终端是新的 FPGA ABI，必须单独执行
`make -C npc rebuild config=U55cRv64Hbm512L2CacheNpc150MHzPerformanceMonitorFpgaConfig`。

第一版的 v13 mailbox/cache HTML 保持原有 I$/D$ 寄存器布局；L2 统计已在核心 debug bundle 中导出，
但尚未扩展到运行时 mailbox 页面。

`HbmJitterCacheSimulationConfig` 是本地 L1-only 的宽 HBM 对比端点：它使用与 150 MHz 宽 L1
FPGA 核一致的 RV64 流水配置、4 KiB/2-way/64-byte 的 I$/D$，并将一个 512-bit cache-line
transaction 的主存响应固定为可复现的 73--81 cycles 抖动（seed=`0x13579bdf`）。它不包含 L2，
MMIO 不加延迟；其 `performance.html`、`instructions.html`、`pipeline.html` 与 `cache.html` 会反映
由 L1 miss 引起的实际 RTL 停顿。该端点是校准后的功能时序模型，尚不模拟 U55C 的异步 FIFO、HBM
bank/queue 竞争或多请求仲裁，因此不能宣称逐条指令 cycle-identical；应以 FPGA 实测的 miss 分布继续
调整这个 73--81 区间。

`HbmJitterL2CacheSimulationConfig` 在相同 CPU、64-byte L1、512-bit DPI 和 73--81 cycle 主存模型上
增加统一 256 KiB、8-way、64-byte、Tree-PLRU、write-back/write-allocate L2。它使用本地统一
IF/LSU 主存端口，因此可以直接和上面的 L1-only 端点做周期、CPI/IPC 及 L2 hit/miss 对比。

`PipelinedTwoCycleWideL2SimulationConfig` 是独立的本地 RV64IM 两拍缓存端点：I$/D$ 均为
4 KiB/64-byte/2-way，统一 L2 为 256 KiB/64-byte/8-way，主存端口为 512-bit DPI。缓存命中在
请求握手后的第 N+2 拍按序返回，流水填满后每拍可处理一笔；模式冻结四项 request/response/fetch/memory
FIFO 深度为 4，instruction buffer 为 8 项。L1 miss、MMIO、dirty writeback、FENCE、FENCE.I 和外部
drain 会先关闭入口并排空流水/队列，再以 D$ -> L2 -> I$ 的顺序完成维护，因此不承诺固定两拍延迟。
该 Config 只使用本地 NEMU/Verilator DPI Fabric，不改变 FPGA、SoC 或外部 AXI ABI。
`PipelinedTwoCycleWideL2NoCompletionForwardingSimulationConfig` 保持完全相同的缓存、DPI 和
流水配置，但关闭完成表结果前递，可作为 `make config=` 的 A/B 对照端点。

`HbmJitterCacheVcdSimulationConfig` 保持这套 L1-only 宽 HBM DPI 硬件配置，但将本地 Verilator
ABI 显式构造成支持 VCD 的版本。它必须完整 `build` 或 `rebuild`，不能只执行 `host-build`，因为
`--trace`、`verilated_vcd_c.o` 和 `npc_start_trace`/`npc_stop_trace` 都属于冻结的仿真 ABI。运行时在
SDB 中执行 `start`、若干 `si`、`stop`；文件会写到该运行目录的 `wave-001.vcd`，例如：

```bash
printf 'start\nsi 40\nstop\nc\n' | \
  make -C am-kernels/tests/cpu-tests run ALL=add \
  config=HbmJitterCacheVcdSimulationConfig
```

## 构造策略

| 构造能力 | 由 `scope` 区分的目标 | 缺失时 | 已有构造的更新方式 |
| --- | --- | --- | --- |
| `check-only` | 只做 Scala/RTL 检查 | 不进入公开 Make 构造或运行入口 | 由测试直接调用 |
| `run` | `npc`/`soc` 为 CPU 本地仿真，`spmv` 为独立加速器仿真，`fpga` 为上板运行 | NPC/SoC/SPMV 首次运行自动生成；FPGA 需 `build` | `rebuild` 在同一 FQCN 目录重构硬件与运行宿主；仅更新 C/C++ 宿主用 `host-build` |
| `batch` | 只允许批处理的 FPGA 运行终端 | FPGA 需 `build` | 与 `run` 相同；`run` 会被拒绝 |
| `synthesize-only` | `fpga` 下不含 CPU/NEMU 的加速器资源探针 | FPGA 资产需显式 `build`；软件 host 可独立构建 | `rebuild` 替换 XO、DCP 和报告；SPMV 可通过独立 accelerator host 执行 golden |
| `bitstream-only` | `fpga` 下不含 CPU/NEMU 的加速器 bitstream 压力探针 | FPGA 资产需显式 `build`；软件 host 可独立构建 | `rebuild` 替换 XO、DCP、报告和 xclbin；SPMV 可通过独立 accelerator host 执行 golden |

FPGA 的首次构造需显式执行 `build`；已有 FPGA 构造不会因源码、Config 或工具变化自动重建，需要新硬件时
必须显式执行 `rebuild`。`build` 和 `rebuild` 都直接使用稳定的 FQCN 目录，开始时会清理该目录中旧 ABI、
RTL、FPGA 资产和运行产物；中断或失败后目录会保留为无效状态，可直接重试 `build` 或 `rebuild`。旧资产的 SHA-256、
终端 FQCN、板卡、XRT 平台、host ABI 或 mailbox 协议不兼容时
始终硬失败。

`make build config=<Config>` 只允许首次构造，或修复 `valid?` 为空的 `building`、`interrupted`、`failed`
和缺资产构造；它会沿用同一个版本号和 FQCN 目录。`valid?=+` 的构造会被 `build` 明确拒绝，必须使用
`make rebuild config=<Config>` 替换硬件 ABI。已有版本也可执行 `make rebuild version=<编号>`：它只从
保存的版本信息取得 FQCN，再用当前源码中的同名 Config 做完整重构，因此既可修复失败构造，也可主动全局
更新有效构造；构造目录和版本编号保持不变。

不同 FQCN 的 `build`/`rebuild` 可并行：全局锁只短暂保护 Config profile、稳定目录和版本号分配，随后
Chisel/Verilator/Vivado/Vitis 长流程由 `constructions/.locks/<FQCN>.lock` 单独保护。因此同一 Config 的
重复构造会立即拒绝，而不是与已有构造争用目录；`D=`/`delete=` 会在任一受影响构造仍在运行时整体拒绝，
避免重编号覆盖运行中构造保存的版本号。本地 NPC/SoC 将可再生 RTL、Verilator、SoftFloat 和 SoC
模拟工作目录放在各自构造的 `.work/` 下，成功后才冻结 ABI，因此不会共用 `generated*`、`intermediate`
或 `build-sim`。已启动的旧 manager 进程仍按它启动时的脚本锁策略运行。

普通 `run`/`run-bat` 只验证并直接执行已保存的 `abi/nemu/nemu-exec`，不会启动 NEMU Make。运行宿主的
C/C++ 和 menuconfig 增量依赖只在 `host-build` 或运行入口的 `host-rebuild=1` 时运行，并原子替换
保存 profile 的 `NEMU_*` 段与 `abi/nemu/`；当前终端的硬件和 `FpgaToolchainConfig` 变化不会被吸收。对 FPGA
构造，host 会从已冻结的 `FPGA_CLOCK_MHZ` 编译性能报告换算值，并在 `abi/nemu/host.env` 记录
`CORE_CLOCK_MHZ`；它必须与保存硬件 profile 一致，旧的 300 MHz host 在 150 MHz xclbin 上需要一次
`host-build`，但不需要重建 bitstream。
Chisel、生成 RTL、Verilator ABI、`npc/csrc` glue 与 FPGA 文件仍只由 `rebuild` 更新。

若 FPGA 构造已经完成链接并生成完整 manifest/SHA-256 资产，却只在末尾的 NEMU host 阶段失败或中断，
`host-build config=<Config>` 会校验保留资产、只重试 host，并在 host 与资产复验通过后发布原版本；
`host-build version=<编号>` 直接选择已保存的正式构造，适合 profile 中 FPGA 时钟或 NEMU host 元数据
失配时修复该版本。版本选择不会先因旧 host 与 profile 失配而拒绝，但发布前仍会完整复验资产。缺少
`nemu-host` 失败证据、manifest/SHA-256 校验失败或硬件中间阶段失败的构造仍必须使用 `build`/`rebuild`，不会被
`host-build` 提前发布。

若 U55C 的 Vitis link 已成功生成预期 xclbin，却在 manifest、平台 `DATA_CLK`、WNS 或 host 之前的
post-link 校验失败，可执行 `make -C npc resume-post-link config=<Config>` 补走最后的校验和发布步骤。该入口只接受
保留的 profile、synthesis stamp、Vitis 成功日志和未校验 xclbin 都完整的失败/中断构造；它先比较当前 Config 的
所有非 `NEMU_*` 硬件字段，再实际校验 `DATA_CLK`、WNS 和 artifact manifest/SHA-256，最后构造 host。任一证据
缺失或硬件字段变化都会拒绝恢复，仍必须执行 `rebuild`；该命令不会重新运行 Vivado/Vitis，也不能用于替换硬件 ABI。

对于另一套流程已经生成的 FPGA bitstream/xclbin，可直接执行
`make -C npc build-host config=U55cRv64Npc300MHzFpgaConfig`（`rebuild-host` 是同一操作的对称别名），
然后把对应资产放入
`constructions/.compatible/npc.fpga.u55c.U55cRv64Npc300MHzFpgaConfig/fpga/artifacts/`：U55C 使用
`npc-<FPGA_PLATFORM>.xclbin`，ZCU102 使用 `npc.bit` 和 `npc-zcu102.env`。兼容目录会保存匹配的
Config profile、host 和 `compatibility.env`，不生成 RTL、Vivado/Vitis 产物或正式版本标签；资产出现后，
`config=`/`version=` 运行预检会自动选择它，且优先级高于同一 Config 正式构造下的 `fpga/artifacts/`。
U55C 若本机有 `xclbinutil`，运行前还会校验 xclbin 的 `DATA_CLK` 与 profile；不匹配会拒绝运行。
该机制不要求外部平台生成本仓库的正式 manifest，但资产必须与记录的板卡、平台、host ABI 和协议 ABI 匹配。
每次 elaboration 同时生成 `ip-sources.manifest`。其中 `RTL=` 是工具实际编译的源，`MODEL=` 记录已嵌入
生成 RTL 的仿真模型；FPGA 另生成 `synthesis-sources.manifest`，只允许 `RTL=` 和 `XCI=`。构造冻结按该
清单复制源文件，不再递归保存整个 `ysyxSoC/perip/`。

## 构造目录

```text
constructions/
  .compatible/<FQCN>/
    profile.env
    construction.env
    compatibility.env
    abi/nemu/
    fpga/artifacts/        # 外部 xclbin/bitstream，优先于正式构造资产
  npc.SimulationConfig/
    construction.env
    profile.env
    version.tag
    version.info
    abi/{rtl,verilator,nemu,softfloat,glue}/
    logs/
    runtime/<test>/<timestamp-ns>-<pid>/{performance.html,instructions.html,cache.html,pipeline.html,wave-*.vcd}
  npc.fpga.u55c.U55cYsyxSocFpgaConfig/
    construction.env
    profile.env
    version.tag
    version.info
    abi/{nemu,protocol}/
    fpga/{rtl,ip-generated,synth,link,artifacts}/
    logs/
  npc.fpga.u55c.U55cSpmv32PcFp32X8192UramResourceProbeConfig/
    construction.env
    profile.env
    version.tag
    version.info
    fpga/{rtl,synth,artifacts}/
    logs/build/{elaborate.log,ooc-synth.log}
```

首次构造开始时分配从 `1` 开始的连续版本序号，并以 `version.tag` 的 `building` 状态立即可见。同一个
Config 重构时保留版本序号和
`CREATED_AT`，更新 `UPDATED_AT`、`REBUILD_COUNT` 和 Config 固定的 ABI；`make version D=<序号列表>` 删除后会将
未删除的后续版本紧凑重映射。内部时间 ID 仅用于并发安全和
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
固定显示 Pipe/ID/EX 三格，随后显示 `valid?`。`Arch` 和 `RunningTime` 使用文本，分别表达
NPC/SoC/SpMV 与 SIM/FPGA/SYNTH；最右侧 `Config` 显示保存的 Config 短名，不再输出额外名称映射或
可构造 Config 表。

NEMU host 的 `performanceHtml` 可选项会在运行结束时写入
`runtime/<test>/<timestamp-ns>-<pid>/performance.html`。它是报告主页，包含总体 CPI/IPC/MIPS、宿主耗时、
流水配置、stall 对比、五阶段平均占比、各 load/store/M 操作的平均与最大延迟、最近分类样本和最后提交；
同一份提交记录还会生成可搜索、分页的 `instructions.html` 逐指令明细。主页以新窗口打开可用的子报告，
子报告均可返回主页。`cacheHtml` 是依赖 `performanceHtml` 的独立开关：只有硬件 I$ 或 D$ 实际启用时才生成
`cache.html`，单独展示几何、策略、instruction buffer 与命中率/事件计数；无缓存运行不会留下空页面。
`LocalPipelineTrace` 和 `U55cPerformanceMonitor` 默认启用，可通过 `.copy(cacheHtml = false)` 覆盖。
`pipelineHtml` 是 `performanceHtml` 的提交记录子特征：本地 Verilator 复用软件记录，
不会收集第二份轨迹。对应本地 host 还会同时启用 NEMU 软件逐提交自查。记录只包含已提交
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
| 加速器参数 | `chisel/configs/spmv/`、`chisel/accelerators/spmv/` | SPMV 参数/profile 与独立资源探针 RTL | SPMV 才需要 |
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
`U55cSocTerminal`、`Zcu102NpcTerminal` 或 `Zcu102SocTerminal`。这些运行预设已经提供完整
`NemuHostConfig` 默认值，FPGA 预设同时提供分组式 `FpgaToolchainConfig` 默认值；当前内置
`Configs.scala` 均一步挂载，不重复展开配方。每个 CPU Config 显式混入一个计算 IP terminal，绝不通过
Config 构造参数或 CDE `++` 链单独选择。显式自定义终端仍可在保持 scope、target 与板卡匹配的
前提下重载配方。SPMV 是不带 CPU 算子 terminal 的独立 `U55cSpmvSynthesisTerminal` 与
`U55cSpmvBitstreamTerminal`，只消费 U55C FPGA 工具链。profile 据此渲染保存的 `host.defconfig` 和现有
`FPGA_*` 字段。Chisel
elaboration 生成按模块拆分的 SystemVerilog 和显式 IP source manifest；Verilator 或 Vivado/Vitis 只消费
清单列出的 RTL/XCI 与同一份 profile。综合清单会硬拒绝 DPI、NEMU MMIO 和其他仅仿真模型。
运行时 AM 只编译测试镜像，并直接执行冻结的 host、xclbin 或 ZCU102 环境清单。`reset=1` 是仅用于 FPGA
Config 的 NEMU 运行参数：U55C 会在每次 `nemu-exec` 装载 xclbin 前执行非交互的 `xbutil --batch --force reset --type user`，清除
core/mailbox reset 无法清除的 HBM/AXI 未完成事务；未传该参数时不自动 reset。U55C 的 `run-bat` 仍必须使用
`jobs=1`。默认 XRT device 0 会自动发现第一张卡的
BDF；选择其他 device 时需要同时设置 `NEMU_FPGA_XRT_BDF=<dddd:bb:dd.f>`，也可用
`NEMU_FPGA_XBUTIL` 指定 `xbutil` 路径。

普通 U55C profile 固定 `npc-fpga-runtime-v11` 调试和运行控制 ABI；它不会生成
`m_axi_trace`、HBM trace BO 或 URAM FIFO，且 host 不生成性能、逐指令或流水 HTML。v11 xclbin 仍支持 SDB `si`、`c`、
寄存器和内存调试。`U55cRv64Npc{100,125,150,200,250,300}MHzPerformanceMonitorFpgaConfig` 与
`U55cRv64CacheNpc{150,300}MHzPerformanceMonitorFpgaConfig` 是独立的
`npc-fpga-runtime-v13-performance-monitor` ABI：仅支持 `run-bat`，将 32-byte 提交记录经 256-bit
`m_axi_trace` 写入 HBM[1]，并用 2048-record URAM FIFO 吸收突发。普通 monitor 支持后缀所示的
100/125/150/200/250/300 MHz；缓存 monitor 当前提供 150 与 300 MHz 核心时钟，且均移除 SDB halt/step、CSR 和完整 GPR 快照硬件。U55C 标准平台将 HBM-connected RTL kernel 的 `DATA_CLK`
固定为 300 MHz；构造从 xclbin 校验这一平台频率。低频核心由 wrapper MMCM 生成，并以逐 AXI 通道异步 FIFO 跨回平台域。每个频点必须完整 `rebuild`；对 v11
外部 xclbin 执行 host-only 构造不会凭空提供监测数据。缓存版在 `mtestexit` drain 后、core reset 前将状态快照到
mailbox 的只读区，再读取
I$/D$ 的实际几何、策略、instruction buffer 深度、命中/未命中、填充、写回和替换计数；它不改变 v13 HBM trace
record。ZCU102 使用
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
