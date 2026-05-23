# PS Runtime 软件规划

ZCU102 的 PS 侧软件承担 U55C host 程序的角色。

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
