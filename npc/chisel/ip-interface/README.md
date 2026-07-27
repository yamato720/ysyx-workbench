# Chisel IP Interface

本模块只保存厂商无关的 Chisel IP 合同、参考模型和仿真资源；它不依赖 NPC core、CDE、Rocket 或
FPGA harness。

- `scala/`：IP 的 Chisel 接口、算术参考模型、存储与外设合同；文件直接位于此目录，包名仍为 `npc.ip`。
- `resources/`：由 BlackBox 嵌入的 Verilog/SystemVerilog 仿真资源，保持 `/npc/ip/...` classpath。
- `test/`：独立 IP 合同测试；文件直接位于此目录。
- `sources/`：构造 source manifest 使用的仿真模型清单。

目录不使用 `src/` 或 `main/` 包装层。SBT 的 `ip` 项目和 ysyxSoC 的 Mill `npcIp` 模块都显式使用
这些源码与资源根。
