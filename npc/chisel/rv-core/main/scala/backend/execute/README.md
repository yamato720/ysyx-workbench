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
`NpcExecutionUnit` 把请求交给 I/M/F ALU，再以公共 tag 按程序顺序退休。
