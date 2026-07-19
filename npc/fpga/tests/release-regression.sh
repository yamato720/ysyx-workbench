#!/usr/bin/env bash
set -euo pipefail

npc_root=${1:?用法：release-regression.sh <npc-root>}
npc_root=$(realpath "$npc_root")
source_root=$(realpath "$npc_root/..")
artifact_tool="$npc_root/fpga/common/scripts/artifact-manifest.sh"
construction_tool="$npc_root/fpga/common/scripts/release-construction.sh"
constructions="$npc_root/fpga/releases/v0.2.0-fpga-sdb/constructions.env"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

fail() {
  printf 'Release 回归失败：%s\n' "$*" >&2
  exit 1
}

"$npc_root/scripts/generate-config-catalog.sh" "$npc_root"
export SCPU_CONFIG_CATALOG_READY=1
"$construction_tool" verify "$constructions" "$npc_root" Zcu102YsyxSocFpgaConfig
"$construction_tool" verify "$constructions" "$npc_root" U55cYsyxSocFpgaConfig
if "$construction_tool" verify "$constructions" "$npc_root" U55cNpcFpgaConfig >/dev/null 2>&1; then
  fail 'Release 清单接受了未登记的裸 NPC Config'
fi

artifact_dir="$work/artifact"
mkdir -p "$artifact_dir"
u55c_platform=xilinx_u55c_gen3x16_xdma_3_202210_1
fqcn=scpu.fpga.u55c.U55cYsyxSocFpgaConfig
host_abi=nemu-construction-v1
protocol_abi=npc-fpga-mailbox-v2
printf 'xclbin\n' > "$artifact_dir/npc-$u55c_platform.xclbin"
"$artifact_tool" write --directory "$artifact_dir" --source-root "$source_root" \
  --release-tag v0.2.0-fpga-sdb --board u55c --variant "$fqcn" --type alveo \
  --platform "$u55c_platform" --config-fqcn "$fqcn" \
  --host-abi "$host_abi" --protocol-abi "$protocol_abi" \
  --timing-wns 0.001 --asset "npc-$u55c_platform.xclbin"
"$artifact_tool" verify --directory "$artifact_dir" --board u55c \
  --platform "$u55c_platform" --config-fqcn "$fqcn" --host-abi "$host_abi" \
  --protocol-abi "$protocol_abi" --release-tag v0.2.0-fpga-sdb --require-timing
if "$artifact_tool" verify --directory "$artifact_dir" --board zcu102 --platform none >/dev/null 2>&1; then
  fail '资产校验器接受了错误板卡'
fi
if "$artifact_tool" verify --directory "$artifact_dir" --board u55c --platform wrong-platform >/dev/null 2>&1; then
  fail '资产校验器接受了错误 XRT 平台'
fi
if "$artifact_tool" verify --directory "$artifact_dir" --board u55c --platform "$u55c_platform" \
  --config-fqcn wrong.Config >/dev/null 2>&1; then
  fail '资产校验器接受了错误终端 Config'
fi
if "$artifact_tool" verify --directory "$artifact_dir" --board u55c --platform "$u55c_platform" \
  --host-abi wrong-abi >/dev/null 2>&1; then
  fail '资产校验器接受了错误 host ABI'
fi
if "$artifact_tool" verify --directory "$artifact_dir" --board u55c --platform "$u55c_platform" \
  --protocol-abi wrong-abi >/dev/null 2>&1; then
  fail '资产校验器接受了错误协议 ABI'
fi
printf 'tampered\n' > "$artifact_dir/npc-$u55c_platform.xclbin"
if "$artifact_tool" verify --directory "$artifact_dir" --board u55c \
  --platform "$u55c_platform" >/dev/null 2>&1; then
  fail '资产校验器接受了校验和损坏'
fi

printf 'FPGA Release 回归通过\n'
