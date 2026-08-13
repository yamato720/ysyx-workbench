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
| `mad_low_density_balanced` | https://sparse.tamu.edu/Schmid/thermal2 | https://sparse.tamu.edu/MM/Schmid/thermal2.tar.gz | `suitesparse/Schmid/csr/mad_low_density_balanced` | 1,228,045 | 8,580,313 | Canonical low-density balanced label for the original `thermal2`; `thermal2` remains a compatibility key. |
| `mad_high_density_balanced` | https://sparse.tamu.edu/Boeing/bcsstk36 | https://sparse.tamu.edu/MM/Boeing/bcsstk36.tar.gz | `suitesparse/Boeing/csr/mad_high_density_balanced` | 23,052 | 1,143,140 | Official `Symmetric=Yes`, `Positive Definite=yes`; density `2.1512e-3`, 128 PE cyclic `delta=1.0684`. |
| `mad_high_density_imbalanced` | https://sparse.tamu.edu/Boeing/msc10848 | https://sparse.tamu.edu/MM/Boeing/msc10848.tar.gz | `suitesparse/Boeing/csr/mad_high_density_imbalanced` | 10,848 | 1,229,776 | Official SPD source; deterministic `P*A*P^T` permutation; density `1.0450e-2`, 128 PE cyclic `delta=3.2142`. |
| `mad_low_density_imbalanced` | https://sparse.tamu.edu/FlowIPM22/Spielman_k100 | https://sparse.tamu.edu/MM/FlowIPM22/Spielman_k100.tar.gz | `suitesparse/FlowIPM22/csr/mad_low_density_imbalanced` | 338,402 | 1,025,404 | Official SPD source; deterministic `P*A*P^T` permutation; density `8.9542e-6` versus `thermal2` `5.6895e-6`, 128 PE cyclic `delta=2.2631`. |

Any `Schmid/thermal2_n<N>` dataset can be generated locally with:

```bash
make -C accelerator-sim/data download DATASETS=thermal2_n<N>
```

This creates the leading `N x N` principal submatrix of full `thermal2` and writes it to `suitesparse/Schmid/csr/thermal2_n<N>`.
For an SPD matrix, every principal submatrix is also SPD, so these derived cases remain mathematically valid PCG inputs.
Practical convergence still depends on conditioning, floating-point behavior, and each consumer's numerical and capacity limits.

## MAD Test Set Contract

The four `mad_*` cases are downloaded from the official SuiteSparse Matrix Collection. The source pages mark all four matrices as numerically symmetric and positive definite. `mad_low_density_balanced` is the renamed benchmark label for the original `thermal2`; the `thermal2` key remains available for compatibility. `bcsstk36` is kept in its original row order. The other two cases use the same deterministic permutation:

```text
sort source rows by descending CSR row nnz
assign the widest rows to new rows 0, 128, 256, ...
apply the matching column permutation: A_new = P * A_source * P^T
```

This is a congruence by a permutation matrix, so it preserves symmetry, eigenvalues, and positive definiteness while changing the stored row order. The reported `delta` is the maximum 128-PE cyclic row-NNZ load divided by the ideal load `nnz / 128`, matching the definition used in the MAD paper. Row width statistics are stored beside the CSR data so consumers can distinguish row-density variation from PE cyclic load imbalance.

For the two permuted cases, the converter also writes a symmetric MatrixMarket file under `suitesparse/<group>/mtx/<dataset>/<dataset>.mtx`. That file and the CSR files describe the same post-permutation matrix and can be used by the MAD host and Cuper host respectively.

## Raw Archive Checksums

| Archive | SHA256 |
|---|---|
| `suitesparse/Nasa/raw/nasa2910.tar.gz` | `457b4d58b008f691e2c3541225f217c145f9c1495394d2ca048e364c3dd4672b` |
| `suitesparse/Nasa/raw/nasa4704.tar.gz` | `f4170dd3d10a7ae027c17373cb09d8bf44e674f11ba2455b7272c22c93f5b7a4` |
| `suitesparse/Nasa/raw/nasasrb.tar.gz` | `ca86c95c0b8ed085e0251b3b162cb43b6b3b006bde58e37b54dba135a2dd8c9c` |
| `suitesparse/Nasa/raw/pwt.tar.gz` | `9a1419820b3696743ef6d05856b505d62d6859bc08465fe0aea4aaa6d58ec7ca` |
| `suitesparse/Schmid/raw/thermal2.tar.gz` | `02934a4b642b6829c33517e0b801b60ea894a6552c6cd7e3db6c709c776434ce` |
| `suitesparse/Boeing/raw/bcsstk36.tar.gz` | `5604590a92eb3f02acec595c144429a20efbd943ab4eaa18bfc11beb91dc3da2` |
| `suitesparse/Boeing/raw/msc10848.tar.gz` | `d16462c5afd43ab447c01734ac165156efd2d94434872566d82961f76e8a4d3f` |
| `suitesparse/FlowIPM22/raw/Spielman_k100.tar.gz` | `ac31a4ef540c40c16ac42a2f9e6ba7783a75d6892d6415a3ed0919b987ceb4ce` |

## Consumer Limits

This inventory records datasets and provenance, not a guarantee that every accelerator can hold or process every case. Each hardware or software consumer must enforce its own matrix-size, memory-capacity, and ABI limits.
