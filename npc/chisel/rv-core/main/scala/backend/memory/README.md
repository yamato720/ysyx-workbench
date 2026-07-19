# 访存阶段

本目录对应五级流水线的 MEM 阶段：

- `LoadStore.scala`：把 LSU 请求转换为 AXI4-Lite 读写事务；一次只保留一笔按序访问。
- `LoadDataFormatter`：从总线读拍中按地址和 `funct3` 提取字节、半字、字或双字，并完成符号/零扩展。
- `StoreDataFormatter`：生成写数据的 byte lane 和 `strb`。

地址计算不在此处完成，而由 EX 的整数 ALU 产生 `aluResult`。本模块只处理传输、对齐和
返回数据格式，避免把 ISA 地址计算和总线协议混在一起。
