# 前端

前端实现 IF 和 ID，并在读取架构寄存器之前建立前后端边界：

```text
frontend/
  fetch/    程序计数器与 AXI 取指
  decode/   指令模式、立即数生成和控制字译码
```

`NpcFrontend.scala` 输出 `DecodedDispatchPayload`。其中保留源寄存器编号、目标寄存器、
立即数和执行控制，但不携带源操作数值；GPR/FPR 的读取、旁路和冒险检测归后端所有。
