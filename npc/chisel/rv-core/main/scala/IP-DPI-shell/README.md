# DPI 集成外壳

本目录声明独立仿真模式使用的 Chisel BlackBox：

- `DPIUnit.scala`：NEMU 支持的 RAM 与 MMIO 访问外壳。

它们只负责把硬件端口接到 Verilator DPI-C 函数；AXI 请求的发起、仲裁和地址译码仍由
`protocol/axi` 负责。浮点 SoftFloat DPI 壳属于通用计算模块，位于 `compute/FloatingDpi.scala`。
