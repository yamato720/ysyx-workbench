# Constraints 目录

这里放 ZCU102 runtime 的 XDC 约束。

不要凭记忆手写 ZCU102 管脚。正确流程是：

1. 从 AMD/Vivado 提供的 ZCU102 board files 或官方 master XDC 获取管脚定义；
2. 只启用本设计实际使用的接口；
3. 每个接口标注来源、bank、电压和约束原因；
4. 未验证的 pin 保持注释。

MVP 需要约束：

```text
clock/reset: usually from PS-generated PL clock/reset in block design
UART: only if using PL-routed UART pins
debug GPIO/LED: optional
ILA: no board pins
```

如果 UART 走 PS 控制台，则 PL 不需要单独约束 UART pins。
