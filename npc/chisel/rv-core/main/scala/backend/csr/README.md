# CSR 执行与流水线组织

本目录实现 NPC 的机器态 CSR 子系统。CSR 是架构状态的一部分，不是一个只属于某一级流水线的普通组合单元：一条 CSR 指令需要在执行阶段读取旧值并计算新值，但只能在提交点修改架构状态。

当前实现把这两件事拆开：

```text
IF                 ID                    EX                    MEM                 WB / Commit
取指       译码、读 rs1 或 zimm   读取旧 CSR、计算新值   携带 CSR 操作结果   修改 CSR、写 rd、重定向
                                      CsrExecution                              CsrFile
```

这不是让 CSR "放在 WB"。`CsrExecution` 位于执行路径，`CsrFile` 的状态更新则由提交边界拥有。分离的目标是使 CSR 副作用、普通寄存器写回、浮点异常标志和异常重定向具有同一个程序顺序。

## 模块职责

| 文件 | 职责 |
| --- | --- |
| `CsrDefinitions.scala` | 已实现 CSR 地址和 `mie`/`mip` 位位置。 |
| `CsrCause.scala` | 同步异常写入 `mcause` 的原因码。 |
| `CsrAccess.scala` | 浮点 CSR 地址识别与非法舍入模式判断。 |
| `CsrExecution.scala` | 锁存 EX 请求、读取旧 CSR 值、完成 CSR 读改写计算、产生 trap/MRET 请求。 |
| `CsrFile.scala` | 保存架构 CSR，并在提交点写入状态。 |

`NpcBackend` 负责把这些模块连接到流水线，而不是把 CSR 逻辑塞进整数 ALU 或写回模块。

## CSR 指令的实际流向

以 `CSRRW rd, csr, rs1` 为例：

1. **ID**：译码得到 `csrEnable`、`csrOperation`、`csrAddress` 和 `csrReadWritebackEnable`。整数寄存器读端口提供 `rs1` 值；立即数形式使用 `zimm`。
2. **EX**：CSR 指令走串行执行路径。`serialExecuteAccept` 拉高时，`CsrExecution.capture` 锁存请求和由 `CsrFile.readData` 提供的旧值。`CsrExecution` 按 `write`、`set`、`clear` 计算 `csrWriteData`，并输出 `csrWriteEnable`、`csrAddress`、`csrAccessAllowed` 与旧值 `readData`。
3. **MEM**：CSR 控制信息随 `ExecuteMemoryPayload` 和 `MemoryWritebackPayload` 前进；它不访问数据存储器。
4. **WB/Commit**：仅当 `commitFire` 为真时，`CsrFile.writeEnable` 才会生效。旧 CSR 值同时通过普通整数写回路径写入 `rd`。

因此，一条 CSR 指令的读值来自执行时锁存的旧状态，而写入在提交时发生。`CSRRW x0, csr, rs1` 仍可更新 CSR，只是 `x0` 丢弃旧值。

## 为什么状态更新在提交点

把 CSR 写入放在 EX 看起来更短，但会让暂时执行过的指令提前改变 `mstatus`、`mtvec`、`fcsr` 等架构状态。若该指令随后被 flush、因异常取消，或被更老的异常截断，错误副作用很难撤销。

当前设计只在 `commitFire` 时更新 `CsrFile`，因此：

- 更老指令先提交，CSR 修改与程序顺序一致。
- 被 flush 的年轻指令不会修改 CSR。
- `rd` 写回、CSR 写入、浮点 `fflags` 更新和 trap 重定向共享提交边界。
- 对 CSR 的后续相关指令不会看到尚未提交的架构状态；当前 CSR 指令走串行路径，避免了这类 RAW 冒险。

在更激进的乱序或高吞吐流水线中，也可以在 EX 提前计算或暂存 CSR 写入，但仍应在 ROB/commit 阶段让它成为可见的架构状态。

## 异常、MRET 与重定向

`CsrExecution` 同时携带同步 trap 和 MRET 请求：

- 译码产生的 `ECALL`、`EBREAK`、非法指令，以及浮点扩展未启用或非法舍入模式，都会形成 `trapEnable`、`trapCause`、`trapEpc`。
- 到提交点后，`CsrFile` 以最高优先级写入 `mcause` 与 `mepc`；后端把下一 PC 重定向到 `mtvec`。
- `MRET` 不写入 `CsrFile`，而是在提交点读取 `mepc` 并将下一 PC 重定向到该值。

当前 `CsrFile` 内部状态更新优先级为：`trapEnable`、浮点提交、普通 CSR 写入。这样一条发生同步异常的指令不会同时提交普通 CSR 副作用。

`externalInterrupt` 目前只被合成为 `mip.MEIP` 的外部输入。中断优先级判定、`mie/mstatus` 使能检查以及自动进入 trap 尚未实现；因此它不是当前执行路径中的异步陷入源。

## 浮点 CSR 与 FS 状态

`CsrFile` 保存 `fflags`、`frm` 和 `fcsr`，并导出 `frmOut` 给浮点运算选择动态舍入模式。浮点指令在提交时通过 `floatingCommit` 将异常标志 OR 入 `fflags`，使其保持 sticky 语义。

访问 `fflags`、`frm`、`fcsr`，或提交浮点指令时，会把 `mstatus.FS` 置为 Dirty；`fEnabled` 在 `FS != Off` 且配置启用 F 扩展时为真。执行阶段使用这个信号拒绝未启用的浮点操作。

## 读写时序的说明

这里的“执行时读、提交时写”描述的是流水线可见性，不依赖“时钟前半拍读、后半拍写”这一实现假设。`CsrFile.readData` 是组合读；`CsrFile` 中的寄存器只在时钟边沿且提交条件满足时更新。因而同周期的可见性由显式的 `capture`、payload 和 `commitFire` 定义，而不是由半周期约定隐式决定。
