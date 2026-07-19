# NPC 与 NEMU 的统一构造集成

NPC/ysyxSoC 不作为运行时动态库临时拼接。每个可运行 Scala Config 会在
`npc/constructions/<FQCN>/` 冻结一套相互匹配的 RTL、Verilator 对象、glue、SoftFloat 和 NEMU
host；FPGA Config 还冻结 host、协议清单与板卡资产。

## 构造链

1. Make 通过 Scala 自动目录解析 `config=` 或从 `1` 开始的 `version=`。
2. SBT/Mill 反射实例化完整 Config，生成规范化 `profile.env`。
3. NPC 或 ysyxSoC elaborator 生成按模块拆分的 RTL。
4. 仿真 Config 使用 Verilator 生成对象，并与对应 NEMU 配置链接为构造专用 host。
5. FPGA Config 生成 Vivado/Vitis 资产，再链接板卡后端 NEMU host。
6. 构造成功后从 staging 原子替换；AM 运行只编译测试镜像并启动保存的 host。

这种方式保证生成代码、Verilator 头文件、NEMU 配置、SoftFloat 和 FPGA 协议不会跨构造混用。
`construction.env` 记录稳定版本序号、终端 Config、ABI、板卡和构造时间；资产本身的完整性由
`artifact-manifest.env` 与 `SHA256SUMS` 校验。

## 使用

```bash
make -C npc build config=NpcDpiConfig
make -C am-kernels/tests/cpu-tests run-bat ALL="add div" config=NpcDpiConfig

make -C npc build config=YsyxSimulationConfig
make -C am-kernels/tests/cpu-tests run ALL=add config=YsyxSimulationConfig
```

仿真构造缺失时自动原子构造；已有构造运行前只用 NEMU Make 刷新其 C/C++、头文件和 menuconfig
依赖。Chisel、RTL、Verilator 对象和 glue 变更需要 `rebuild=1`。失败时旧构造保持可用，失败日志写入
`npc/constructions/.failed/`。FPGA 不做自动重建，只有 `rebuild=1` 才重新实现。
