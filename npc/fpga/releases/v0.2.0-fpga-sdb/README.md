# v0.2.0-fpga-sdb Release

本源码 Release 的 `constructions.env` 只固定两个可重建的终端 Config FQCN：

- `scpu.fpga.zcu102.Zcu102YsyxSocFpgaConfig`
- `scpu.fpga.u55c.U55cYsyxSocFpgaConfig`

XLEN、ISA、流水线、算术时序、板卡、频率和工具策略由对应 Scala Config 导出，不在 Release 清单
重复维护。构造版本序号属于每台主机的 `npc/constructions/`，不写入源码 Release。

## 源码校验

```bash
make -C npc release-construction-check \
  RELEASE_CONSTRUCTION=Zcu102YsyxSocFpgaConfig
make -C npc release-construction-check \
  RELEASE_CONSTRUCTION=U55cYsyxSocFpgaConfig

git submodule status --recursive
sbt "root/test" "fpga/test"
cd npc/chisel/ysyxSoC && mill -i ysyxsoc.compile
```

子模块从最深层开始提交和推送，顶层 release commit 固定所有 gitlink。`npc/build.sbt` 在 release
commit 中设为 `0.2.0`，创建带注释 tag `v0.2.0-fpga-sdb`；发布后另起提交恢复下一版
`-SNAPSHOT`。

## 板卡资产

当前源码 Release 不携带既有 ZCU102 bitstream，也没有可发布的 U55C xclbin。只有完成正式实现、
WNS 不小于 Config 下限并通过实体板验收后，才能把资产追加到同一 GitHub Release。

ZCU102 包含：`npc.bit`、`npc.xsa`、`system-user.dtsi`、`npc-zcu102.env`、
`artifact-manifest.env` 和 `SHA256SUMS`。U55C 包含平台限定的
`npc-xilinx_u55c_gen3x16_xdma_3_202210_1.xclbin`、清单和校验文件。

离线校验示例：

```bash
npc/fpga/common/scripts/artifact-manifest.sh verify \
  --directory /path/to/artifacts \
  --board u55c \
  --platform xilinx_u55c_gen3x16_xdma_3_202210_1 \
  --config-fqcn scpu.fpga.u55c.U55cYsyxSocFpgaConfig \
  --host-abi nemu-construction-v1 \
  --protocol-abi npc-fpga-mailbox-v2 \
  --release-tag v0.2.0-fpga-sdb --formal --require-timing
```

清单记录 release tag、根仓库与递归 submodule SHA、终端 Config、板卡/XRT 平台、
Vivado/Vitis/XRT 版本、WNS 和每个资产的 SHA-256。正式附加前还需在两块实体板
完成交互 SDB、目标存储读取、watchpoint、浮点回退、`putch` 和 `halt` 验收。
