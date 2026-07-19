# 公共协议

本目录定义不属于单一流水级的结构化接口：

```text
protocol/
  pipeline/  IF/ID、派发、ID/EX、EX/MEM、MEM/WB payload 与弹性流水寄存器
  axi/       AXI4-Lite、AXI4-Full、仲裁、桥接和内存交叉开关
  DebugBundles.scala  顶层调试可见状态
```

跨模块状态通过 Bundle 显式传递，不依赖隐式的全局信号或模块层级路径。这使串行与弹性流水
配置使用同一份架构状态和调试协议。
