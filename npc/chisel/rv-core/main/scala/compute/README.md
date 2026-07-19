# 计算模块

本目录不属于 frontend 或 backend，放置可在多个硬件单元复用的计算实现：

```text
compute/
  ArithmeticOperator.scala  算子请求/响应协议、tag、固定时序模型和外部 adapter 契约
  ComputeBackend.scala      模型、DPI、IP 后端的通用配置
  IntegerOperators.scala    RV32M/RV64M 乘法、除法和余数的计算端点
  FloatingOperators.scala   标量 binary32 的 7 类计算端点
  FloatingDpi.scala         Berkeley SoftFloat 的 Verilator DPI 黑盒声明
```

这些模块只在请求握手后计算并按既定时序返回，不知道取指、流水级、寄存器文件、
提交顺序或 `NpcAluOp`。`backend/execute/MulDivAlu.scala` 和 `FloatingAlu.scala`
才负责把 ISA `aluOp` 映射成算子自己的 `operation`，选择具体算子，并把多个可能
同时完成的响应汇聚为一条 ALU 输出。
