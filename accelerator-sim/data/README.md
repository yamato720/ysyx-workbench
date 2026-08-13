# Accelerator 共享数据

该目录保存 accelerator host 共用的数据获取与转换入口。Git 只跟踪 Makefile、脚本和来源清单；
下载的归档、MatrixMarket 文件、转换后的 CSR 文本以及本地生成数据均不会提交。

生成 `n512`，同时下载并转换默认的 `thermal2_n1024`：

```bash
make -C accelerator-sim/data
```

只生成确定性 SPD 数据：

```bash
make -C accelerator-sim/data generate
make -C accelerator-sim/data generate SIZE=4096
```

只下载 SuiteSparse 数据；归档下载后会校验 SHA-256，并转换为 host 使用的 CSR 文本格式：

```bash
make -C accelerator-sim/data download
make -C accelerator-sim/data download DATASETS="nasa2910 thermal2_n4096"
make -C accelerator-sim/data download DATASETS=all
```

`thermal2_n<N>` 表示从完整 `thermal2` 取前 `N x N` 主子矩阵。查看全部登记项：

```bash
make -C accelerator-sim/data list
```

MAD-HiSpMV 的四类 SPD 测试数据可以一次下载并转换（其中低密度均衡项就是原
`thermal2` 的规范标签）：

```bash
make -C accelerator-sim/data download-mad
```

也可以从任意工作目录调用脚本；输出根目录可以显式指定到共享磁盘：

```bash
python3 accelerator-sim/data/scripts/download_suitesparse_data.py \
  --data-root /path/to/shared/suitesparse --datasets mad
```

这四个登记项及其分类如下：

| 数据集 | 目的 | 官方 SPD 源 | 128 PE cyclic `delta` |
|---|---|---|---:|
| `mad_low_density_balanced` | 原 `thermal2` 的低密度、均衡标签 | `Schmid/thermal2` | 1.0000 |
| `mad_high_density_balanced` | 高密度、均衡基线 | `Boeing/bcsstk36` | 1.0684 |
| `mad_high_density_imbalanced` | 高密度、不均衡 | `Boeing/msc10848` | 3.2142 |
| `mad_low_density_imbalanced` | 接近 `thermal2` 密度、不均衡 | `FlowIPM22/Spielman_k100` | 2.2631 |

两个不均衡数据集在转换阶段应用确定性的对称置换 `P*A*P^T`：按 CSR 行非零元数降序排列，并把最宽的行放到 `new_row % 128 == 0` 的位置。这个变换保持对称正定，只改变存储顺序，使论文定义的 128 PE cyclic 负载不均衡可重复出现；置换后的样本同时写出派生 MatrixMarket 文件供 MAD host 使用。原始 URL、SHA-256、转换规则和实际测得的 density、行宽及 PE 负载会写入每个 CSR 目录的 `meta.json` 与 `meta.txt`。

常用入口：

```bash
make -C accelerator-sim/data download-mad
make -C accelerator-sim/data download-all
make -C accelerator-sim/data download DATASETS="mad_low_density_balanced mad_low_density_imbalanced"
make -C accelerator-sim/data download DATASETS=thermal2_n4096
```

下载器会对归档做 SHA-256 校验；换机器时只需要 Python 3 标准库和网络访问，不依赖本机已有的 MatrixMarket/CSR 工具。

输出布局：

```text
generated/cgsolver/n<N>/
suitesparse/<group>/raw/
suitesparse/<group>/mtx/
suitesparse/<group>/csr/<dataset>/
```

每份 CSR 数据至少包含 `row_ptr.txt`、`col_idx.txt`、`values.txt` 和 `b.txt`。
SuiteSparse URL、尺寸、归档 checksum 和 MAD 三类数据的派生规则见 [suitesparse/SOURCES.md](suitesparse/SOURCES.md)。
