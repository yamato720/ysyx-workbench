# NPC Chisel 架构

本目录按处理器职责划分，而不是按早期单体文件划分：

```text
scala/
  NpcCore.scala       顶层组装
  Config.scala        ISA、流水线和平台构建配置
  frontend/           IF、ID 与无操作数的派发边界
  backend/            寄存器读、EX、MEM、提交与架构状态
  compute/            可复用的 M/F 计算算子与时序/IP 适配
  protocol/           流水 payload、调试接口和 AXI 互连协议
  IP-DPI-shell/       Verilator/NEMU 内存 DPI 黑盒声明
```

主数据流为 `NpcFrontend -> NpcBackend`。前端只负责取指和译码；后端拥有架构寄存器、
CSR、执行、访存和按序提交。M/F 的 ISA 外壳位于 `backend/execute`，其纯计算端点位于
`compute`，两者通过明确的请求/响应接口连接。
