# 后端

后端从前端接收已译码但尚未读取操作数的派发请求，负责寄存器读取、RAW 冒险处理、
执行、访存、异常和按序提交。入口是 `NpcBackend.scala`。

```text
backend/
  execute/    EX：整数 ALU、M/F ISA 外壳、旁路与冒险检测
  memory/     MEM：加载/存储数据整理与 AXI 主机
  writeback/  WB/Commit：整数与浮点架构寄存器文件
  csr/        EX 的 CSR 计算与 Commit 时的 CSR 状态更新
```

后端用 `protocol/pipeline` 的 payload 把阶段间信息显式携带到提交点。任何改变架构状态的
操作，例如 GPR/FPR 写回、CSR 更新、`fflags` 更新和 trap 重定向，都以提交边界为准。
