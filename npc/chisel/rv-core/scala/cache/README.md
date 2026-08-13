# NPC 阻塞式缓存

本目录实现显式 Config 才会插入的单核缓存层级：

- `CacheTypes.scala` 定义 line、tag/meta、统计和板级维护端口。
- `CacheReplacement.scala` 实现 direct/组相联/全相联共用的 LRU、Tree-PLRU、FIFO 和确定性 Random 状态。
- `CacheArray.scala` 保存 valid/tag/dirty 与 line data；`Auto` 推导同步存储，`Registers` 使用寄存器，
  `Uram` 为 FPGA 数据阵列生成 `ram_style = "ultra"` 属性。
- `CacheController.scala` 是单 MSHR 阻塞状态机，负责 hit、旁路、refill、dirty eviction 和全阵列维护。
- `InstructionCache.scala`、`DataCache.scala` 提供 I$/D$ 角色接口。
- `InstructionBuffer.scala` 保存有限顺序取指，并在 redirect 或 `FENCE.I` 时丢弃推测条目。
- `CacheMaintenanceController.scala` 串行化 `FENCE`、`FENCE.I` 与 FPGA completion drain。

缓存命中不产生下游事务。未命中优先选择 invalid way，否则使用配置的替换策略；refill 与 writeback
都按一个 XLEN beat 一笔 AXI-Lite 事务执行。write-back、write-through、write-allocate、
no-write-allocate、read-allocate 和 read-bypass 均由 `CachePolicy` 固定。write-through 只有在下游 B
响应成功后才更新 cache line；dirty drain 写回失败不会报告完成。

只有 `mainMemoryBase .. mainMemoryBase + mainMemorySize` 可缓存，MMIO 直接旁路。部分访问的数据对齐、
符号扩展和 WSTRB 仍由现有 IF/LSU 适配器负责，cache line 按总线 strobe 合并字节。I$ 不保存 dirty
状态；D$ valid、dirty、tag 与替换状态不包含多核 coherence 或 ECC。

教学预设为 4 KiB、16-byte line、2-way Tree-PLRU I$/D$，D$ write-back + write-allocate，
instruction buffer 4 项。参数入口位于 `configs/npc/base/CacheConfigs.scala`，完整成品位于
`configs/npc/core/CacheCore.scala`。所有旧终端默认 cache disabled。

普通 `FENCE` 等待旧事务并写回所有 dirty D$ line，随后允许后续指令派发；它不失效 I$ 或丢弃取指。
`FENCE.I` 使用相同的 D$ drain，随后 invalidate I$ 并丢弃年轻取指。当前单核实现不区分 FENCE 的
pred/succ 位，而是将每种合法组合保守地作为完整屏障执行。
