# PS Runtime 软件规划

ZCU102 的 PS 侧软件承担 U55C host 程序的角色。

PS ARM 的定位是控制器和调试器，不是 PL CPU 的逐周期执行引擎。`npc/chisel` 在仿真中可以被打包成 `.so` 给 NEMU 推动；上板后，PL 里的 CPU 应该通过硬件 AXI 自己取指和访存，PS ARM 负责加载、启动、读取状态、导出 trace，并可选运行 NEMU 做参考比对。

## MVP bare-metal 程序

职责：

```text
1. assert CPU reset
2. copy image.bin to guest BRAM window
3. write BOOT_PC
4. clear status
5. deassert CPU reset / set run
6. poll HALT
7. drain PUTCH FIFO
8. print EXIT_CODE
```

## Linux 用户态程序

后续可在 PetaLinux/Linux 下通过 UIO、VFIO 或自定义驱动访问 AXI-Lite control registers 和 reserved DDR window。

需要处理：

- device tree reserved-memory；
- AXI-Lite register mapping；
- cache flush/invalidate；
- trace buffer mmap；
- permission and reset control。

## 寄存器访问建议

```c
volatile uint32_t *regs = map_runtime_regs();
regs[CONTROL] = RESET_CPU;
copy_image(guest_mem, image, image_size);
regs[BOOT_PC] = 0x80000000;
regs[CONTROL] = RUN;
while ((regs[HALT] & 1) == 0) {
  drain_putch();
}
printf("exit=%u\n", regs[EXIT_CODE]);
```

第一版可以用 Vitis bare-metal 写死地址，先证明硬件路径正确。

## NPC 调试器第一版

配合 `docs/npc-zcu102-debugger.md`，PS 侧最小程序只需要支持：

```text
write_image()
write_boot_pc()
reset_cpu()
run_cpu()
poll_status()
drain_putch()
dump_trace()
```

不要在第一版 PS 软件中实现普通访存代理。NPC 的取指和 load/store 应该直接访问 PL BRAM、PS DDR 或 PL DDR4。

## NEMU 协作

PS Linux 下可以编译 aarch64 版 NEMU，或把 trace 拷回 host PC 运行 NEMU。推荐优先做离线 DiffTest：

```text
1. PS loads image and starts PL CPU
2. PL CPU writes commit trace to ring buffer
3. PS dumps trace
4. NEMU runs same image
5. compare pc / inst / rd / rd_wdata
```

实时逐条 DiffTest 只建议作为 debug 模式：

```text
single_step PL CPU
PS reads one commit
NEMU executes one instruction
compare
```

这个模式很慢，但适合定位最早几条指令的问题。
