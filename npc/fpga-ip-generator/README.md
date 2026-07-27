# FPGA 厂商 IP 生成器

本目录只保存厂商 IP 的稳定适配器、wrapper 与生成配方，不属于板卡工程，也不保存 Vivado
生成的 `.xci`、综合网表或临时工程。Scala 侧的板卡无关接口与周期模型位于
`../chisel/ip-interface/`，构造产生的实际 IP 位于
`../constructions/<FQCN>/fpga/ip-generated/`。

顶层按复用范围分为 `common/`、`u55c/` 和 `zcu102/`；每层再按 `compute/`、`memory/`、
`ports/` 区分功能。每个功能目录采用以下固定结构：

```text
source/{scala,sv,v,tcl}/
generated/{sv,v}/
```

`source/` 保存可审查输入，`generated/` 只作为可重建文本 RTL 的落点；二进制结果、XCI 与 Vivado
工程始终留在 construction，不提交到源码树。只有出现板卡专属参数、wrapper 或 Tcl 时，才向对应
板卡目录增加文件。

当前通用 compute 生成器直接解析完整 `profile.env`，校验 `FPGA_BOARD`、`FPGA_PART`、XLEN、
乘除 latency/II、Divider 的 `FPGA_DIVIDER_NON_BLOCKING` 和所有 `OPERATOR_ROUTE_M_*` 合同。它读取已有 XCI 的实际属性：一致时复用，
不一致时只重新生成对应的 `npc_int_multiplier_ip` 或 `npc_int_divider_ip`。

整数乘法器固定为无符号 XLEN x XLEN。Xilinx `mult_gen` 的单输入最大宽度为 64 位；适配器以
无符号乘积修正 `MULH` 与 `MULHSU` 的高半部，因此 RV64 不会生成 65 位 IP。
