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

输出布局：

```text
generated/cgsolver/n<N>/
suitesparse/<group>/raw/
suitesparse/<group>/mtx/
suitesparse/<group>/csr/<dataset>/
```

每份 CSR 数据至少包含 `row_ptr.txt`、`col_idx.txt`、`values.txt` 和 `b.txt`。
SuiteSparse URL、尺寸和归档 checksum 见 [suitesparse/SOURCES.md](suitesparse/SOURCES.md)。
