# 流水线 Payload

`PipelineStages.scala` 定义单项 `ready/valid` 弹性流水寄存器和各阶段携带的 payload：

```text
IF/ID -> DecodedDispatch -> ID/EX -> EX/MEM -> MEM/WB
```

每个 payload 显式携带后续阶段所需的控制位、数据、异常、CSR 和性能信息。`flush` 只清除
有效位，不要求清零数据位；无效 payload 不能被后级消费。
