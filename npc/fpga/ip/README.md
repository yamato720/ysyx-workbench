# FPGA 厂商 IP

源码库只保存稳定适配器和生成配方，不保存 Vivado 生成的 `.xci`、示例工程或综合网表。
这些产物统一写入 `../build/<board>/<variant>/<work-or-version>/ip/`。

当前生成器只创建数据通路实际使用的 `npc_int_multiplier_ip` 和
`npc_int_divider_ip`。浮点 mailbox 回退不依赖厂商浮点 IP。

整数乘法器固定为无符号 XLEN x XLEN。Xilinx `mult_gen` 的单输入最大宽度为
64 位；适配器以无符号乘积修正 `MULH` 与 `MULHSU` 的高半部，因此 RV64 不会生成
不受支持的 65 位 IP，同时保留全部 RV64M 乘法语义。
