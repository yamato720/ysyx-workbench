# 核心平台组件

`rv-core` 只保存一份 ISA 译码、前后端流水线、提交逻辑和公共协议。

- `SimulationCoreComponents`：普通 Verilator/NEMU 构建的默认组件，使用本目录原有的
  模型和 DPI 浮点端点。
- `NpcCoreComponents`：可选组件的中立工厂接口。它只返回公共算术端口，不引入 FPGA、
  XRT、Vivado 或 Rocket Chip 类型。

`protocol/PlatformPorts.scala` 同时定义了平台算术辅助、派发控制和 RoCC-like 协处理器
端口。协处理器端口目前仅是稳定协议定义；custom-0..3 的译码、发射和退休语义会在
后续真正加入协处理器功能时实现。
