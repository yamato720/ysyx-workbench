# AXI 协议与内存互连

`AxiInterconnect.scala` 集中定义 AXI4-Lite/AXI4-Full payload、主从端口、Lite 到 Full 桥接、
双客户端仲裁器、DPI RAM/MMIO 从设备和地址译码交叉开关。

`NpcMemoryFabric.scala` 是 NPC 的内存归属边界：前端 IF 与后端 LSU 保持为两个客户端；SoC
模式下经 Lite 仲裁和 Full 桥接共用外部主端口，独立仿真模式下保持 RAM/MMIO 的 DPI 拓扑。

取指 AXI 适配器位于 `frontend/fetch`，LSU AXI 适配器位于 `backend/memory`；本目录只保存二者
共享的传输协议和互连逻辑。
