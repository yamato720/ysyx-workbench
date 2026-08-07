# SuiteSparse Schmid Datasets

Source: SuiteSparse Matrix Collection, `Schmid/thermal2`.

Directory layout:

- `raw/`: downloaded `.tar.gz` archive.
- `mtx/`: extracted MatrixMarket files.
- `csr/`: shared CSR text format: `row_ptr.txt`, `col_idx.txt`, `values.txt`, `b.txt`, `x0.txt`.

Datasets:

| Name | n | nnz in CSR | Notes |
|---|---:|---:|---|
| `thermal2` | 1,228,045 | 8,580,313 | Full million-row symmetric positive definite thermal problem, uses packaged `thermal2_b.mtx`. |
| `thermal2_n1024` | 1,024 | 6,362 | Leading principal submatrix used by the default smoke test. |

The full `thermal2` dataset is available for larger-problem work. Hardware capacity and xclbin compatibility remain the responsibility of each accelerator implementation.

See `../SOURCES.md` for source URLs, checksums, and conversion notes.
