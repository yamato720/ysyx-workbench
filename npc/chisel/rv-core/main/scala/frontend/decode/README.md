# 译码阶段

- `Instructions.scala` 定义 RISC-V 指令的 `BitPat` 模式。
- `ImmediateGenerator.scala` 按 I/S/B/U/J 格式生成并符号扩展立即数。
- `NpcDecode.scala` 将指令匹配为统一控制字和 `DecodedDispatchPayload`。

译码只描述一条指令应由哪个执行单元处理、使用哪些寄存器和产生哪些副作用；不读取寄存器值。
未命中任何表项的指令会被明确标记为非法指令，而不是按 NOP 静默处理。
