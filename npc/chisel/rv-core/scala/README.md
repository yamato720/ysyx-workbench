# NPC Chisel 架构

本目录按处理器职责划分，而不是按早期单体文件划分：

```text
scala/
  NpcCore.scala       顶层组装
  Config.scala        ISA、流水线和平台构建配置
  cache/              阻塞式 I$/D$、替换策略、instruction buffer 与维护控制
  frontend/           IF、ID 与无操作数的派发边界
  backend/            寄存器读、EX、MEM、提交与架构状态
  compute/            可复用的 M/F 计算算子与时序/IP 适配
  protocol/           流水 payload、调试接口和 AXI 互连协议
  IP-DPI-shell/       Verilator/NEMU 内存 DPI 黑盒声明
```

主数据流为 `NpcFrontend -> NpcBackend`。前端只负责取指和译码；后端拥有架构寄存器、
CSR、执行、访存和按序提交。M/F 的 ISA 外壳位于 `backend/execute`，其纯计算端点位于
`compute`，两者通过明确的请求/响应接口连接。

无缓存 Config 的内存路径保持 `frontend/backend -> NpcMemoryFabric`。显式缓存 Config 改为
`frontend -> I$ -> NpcMemoryFabric.instruction` 和
`backend -> D$ -> NpcMemoryFabric.data`；缓存只处理主存地址，MMIO 原样旁路。缓存控制器保持单项
顺序事务，用已有 AXI-Lite 单拍完成 line refill 与 dirty writeback，不改变外部 AXI4 端口。

`FENCE` 在顶层维护控制器中串行化：等待后端排空、flush D$，然后才派发围栏本身；当前单核实现把
任意 pred/succ 组合保守地作为完整屏障。`FENCE.I` 在此基础上再 invalidate I$。只有 `FENCE.I`
会让 instruction buffer 保留围栏头并丢弃所有年轻条目，未完成的围栏前取指响应也会被杀掉。FPGA
completion 使用同一 D$ drain 路径。各模块与状态机约束见 [cache/README.md](cache/README.md)。
