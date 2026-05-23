# PS + PL 联合自测

本自测用于在真实 NPC 接入前验证 ZCU102 的 PS/PL 通路。它不依赖 CPU pipeline，也不依赖 guest memory。

## 1. 自测目标

验证：

- PS 可以通过 AXI-Lite 读写 PL 调试寄存器；
- PL clock/reset 正常；
- PL 可以锁存 commit/putch/halt/exit 状态；
- PS 可以轮询 `STATUS`、读取 `PUTCH_DATA`、读取 `EXIT_CODE`；
- 可选 `irq_to_ps` 能通知 PS；
- Vivado block design 的 ZynqMP PS、SmartConnect、reset IP、ILA 接线正常。

## 2. PL 侧模块

```text
zcu102-runtime/chisel/src/main/scala/ZCU102NPCSelfTest.scala
```

结构：

```text
ZCU102NPCSelfTest
  - instantiates ZCU102NPCDebugger
  - waits for CONTROL.run
  - emits three fake commits
  - emits putch sequence "OK\n"
  - sets exit_code = 0
  - sets halt
```

生成：

```bash
make -C zcu102-runtime selftest
make -C zcu102-runtime check-selftest
```

输出：

```text
zcu102-runtime/generated/ZCU102NPCSelfTest.v
```

## 3. Vivado block design

第一版只需要：

```text
Zynq UltraScale+ MPSoC
  M_AXI_HPM -> AXI SmartConnect -> ZCU102NPCSelfTest.axil
  FCLK_CLK0 -> ZCU102NPCSelfTest.clock
  Processor System Reset -> ZCU102NPCSelfTest.reset

ZCU102NPCSelfTest.irqToPs -> optional PS IRQ_F2P
ZCU102NPCSelfTest.debugState -> optional ILA/VIO
```

这版不需要 AXI BRAM Controller，也不需要 DDR/MIG。

## 4. PS 侧程序

```text
zcu102-runtime/sw/examples/selftest.c
```

流程：

```text
1. write CONTROL.reset_cpu
2. write BOOT_PC = 0x80000000
3. write CONTROL.run
4. poll STATUS.halted
5. drain PUTCH_DATA, expected "OK\n"
6. read EXIT_CODE, expected 0
7. read INSTRET_LOW, expected >= 3
```

## 5. 通过标准

PS console 应看到：

```text
OK
selftest exit=0 instret=3 last_pc=0x80000008
```

如果看不到 `OK`：

- 查 PS AXI-Lite 地址映射；
- 查 `PUTCH_STATUS` 是否置位；
- 查 reset 极性；
- 用 ILA 看 `CONTROL.run` 是否到达 PL。

如果 `STATUS.halted` 不置位：

- 查 PL clock；
- 查 `CONTROL.reset_cpu` 是否一直为 1；
- 查 `debugState` 是否从 idle 跳到 emit/halt。
