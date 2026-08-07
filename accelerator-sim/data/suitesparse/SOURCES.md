# SuiteSparse Dataset Sources

All matrices in this directory are downloaded from the SuiteSparse Matrix Collection in MatrixMarket format and converted to shared CSR text files.

Conversion output format:

- `row_ptr.txt`: CSR row pointer, zero-based.
- `col_idx.txt`: CSR column indices, zero-based.
- `values.txt`: matrix values, double precision text.
- `b.txt`: right-hand side vector.
- `x0.txt`: initial guess, currently all zeros.
- `meta.txt` / `meta.json`: local metadata.

## Dataset Inventory

| Case | Official Page | Download URL | Local CSR Path | n | CSR nnz | Notes |
|---|---|---|---|---:|---:|---|
| `Nasa/nasa2910` | https://sparse.tamu.edu/Nasa/nasa2910 | https://sparse.tamu.edu/MM/Nasa/nasa2910.tar.gz | `suitesparse/Nasa/csr/nasa2910` | 2,910 | 174,296 | NASA structural SPD problem; uses packaged `nasa2910_b.mtx`. |
| `Nasa/nasa4704` | https://sparse.tamu.edu/Nasa/nasa4704 | https://sparse.tamu.edu/MM/Nasa/nasa4704.tar.gz | `suitesparse/Nasa/csr/nasa4704` | 4,704 | 104,756 | NASA structural SPD problem; uses packaged `nasa4704_b.mtx`. |
| `Nasa/nasasrb` | https://sparse.tamu.edu/Nasa/nasasrb | https://sparse.tamu.edu/MM/Nasa/nasasrb.tar.gz | `suitesparse/Nasa/csr/nasasrb` | 54,870 | 2,677,324 | Larger NASA shuttle rocket booster SPD problem; uses packaged `nasasrb_b.mtx`. |
| `Nasa/pwt` | https://sparse.tamu.edu/Nasa/pwt | https://sparse.tamu.edu/MM/Nasa/pwt.tar.gz | `suitesparse/Nasa/csr/pwt` | 36,519 | 326,107 | NASA structural matrix; `b` generated locally as `A * x_ref`; verify PCG suitability before use. |
| `Schmid/thermal2` | https://sparse.tamu.edu/Schmid/thermal2 | https://sparse.tamu.edu/MM/Schmid/thermal2.tar.gz | `suitesparse/Schmid/csr/thermal2` | 1,228,045 | 8,580,313 | Full million-row symmetric positive definite thermal problem; uses packaged `thermal2_b.mtx`. |
| `Schmid/thermal2_n1024` | derived from `Schmid/thermal2` | derived locally | `suitesparse/Schmid/csr/thermal2_n1024` | 1,024 | 6,362 | Leading 1024x1024 principal submatrix for the default smoke test; `b` generated locally as `A_sub * x_ref`. |

Any `Schmid/thermal2_n<N>` dataset can be generated locally with:

```bash
make -C accelerator-sim/data download DATASETS=thermal2_n<N>
```

This creates the leading `N x N` principal submatrix of full `thermal2` and writes it to `suitesparse/Schmid/csr/thermal2_n<N>`.
For an SPD matrix, every principal submatrix is also SPD, so these derived cases remain mathematically valid PCG inputs.
Practical convergence still depends on conditioning, floating-point behavior, and each consumer's numerical and capacity limits.

## Raw Archive Checksums

| Archive | SHA256 |
|---|---|
| `suitesparse/Nasa/raw/nasa2910.tar.gz` | `457b4d58b008f691e2c3541225f217c145f9c1495394d2ca048e364c3dd4672b` |
| `suitesparse/Nasa/raw/nasa4704.tar.gz` | `f4170dd3d10a7ae027c17373cb09d8bf44e674f11ba2455b7272c22c93f5b7a4` |
| `suitesparse/Nasa/raw/nasasrb.tar.gz` | `ca86c95c0b8ed085e0251b3b162cb43b6b3b006bde58e37b54dba135a2dd8c9c` |
| `suitesparse/Nasa/raw/pwt.tar.gz` | `9a1419820b3696743ef6d05856b505d62d6859bc08465fe0aea4aaa6d58ec7ca` |
| `suitesparse/Schmid/raw/thermal2.tar.gz` | `02934a4b642b6829c33517e0b801b60ea894a6552c6cd7e3db6c709c776434ce` |

## Consumer Limits

This inventory records datasets and provenance, not a guarantee that every accelerator can hold or process every case. Each hardware or software consumer must enforce its own matrix-size, memory-capacity, and ABI limits.
