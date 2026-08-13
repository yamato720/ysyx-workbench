# 算术执行单元

I、M、F 三类 ISA 执行外壳直接并列在本目录。它们接收已译码的
`NpcAluOp`，完成 ISA 操作选择、请求发射和结果汇聚：

```text
execute/
  IntegerAlu.scala  RV32I/RV64I 的组合 ALU、比较、分支和跳转
  MulDivAlu.scala   选择 M 的乘法或除法算子并汇聚回包
  FloatingAlu.scala 选择 F 的 7 类算子并汇聚回包
```

纯计算模块已移到根目录的 [`compute/`](../../compute/README.md)：其中有可复用的
M/F 算子、固定延迟模型、DPI 壳和外部 adapter 协议。`NpcBackend` 只根据
`NpcExecutionUnit` 把请求交给 I/M/F ALU。M/F 指令严格单在途：请求载荷保存在
执行寄存器中，响应先经过一个弹性流水寄存器后进入 EX/MEM，因此没有 tag 完成队列、
回填仲裁或多项算术 RAW 扫描。算子配置的 II 描述 IP 能力，不表示核心会并发发射。
