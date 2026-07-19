# 取指阶段

- `ProgramCounter.scala` 保存架构 PC，并在顺序取指与后端重定向之间选择下一 PC。
- `InstructionFetch.scala` 通过 AXI4-Lite 发起单笔指令读取，带一个响应缓冲槽。

分支、异常和 MRET 的目标地址由后端在执行或提交点产生，并通过 `redirectValid`/`redirectTarget`
反馈到本目录。取指模块不解析指令语义。
