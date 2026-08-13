#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import shutil
import tarfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATA_ROOT = ROOT / "suitesparse"
THERMAL2_SIZE = 1_228_045


@dataclass(frozen=True)
class DatasetSpec:
    key: str
    group: str
    archive_name: str
    url: str
    sha256: str
    matrix_member: str
    b_member: str | None
    source: str
    b_source: str
    subset_n: int | None = None
    description: str = ""
    official_page: str | None = None
    category: str | None = None
    transform: str = "none"
    pe_count: int = 128


DATASETS = {
    "nasa2910": DatasetSpec(
        key="nasa2910",
        group="Nasa",
        archive_name="nasa2910.tar.gz",
        url="https://sparse.tamu.edu/MM/Nasa/nasa2910.tar.gz",
        sha256="457b4d58b008f691e2c3541225f217c145f9c1495394d2ca048e364c3dd4672b",
        matrix_member="nasa2910/nasa2910.mtx",
        b_member="nasa2910/nasa2910_b.mtx",
        source="SuiteSparse Matrix Collection, Nasa/nasa2910",
        b_source="nasa2910_b.mtx",
    ),
    "nasa4704": DatasetSpec(
        key="nasa4704",
        group="Nasa",
        archive_name="nasa4704.tar.gz",
        url="https://sparse.tamu.edu/MM/Nasa/nasa4704.tar.gz",
        sha256="f4170dd3d10a7ae027c17373cb09d8bf44e674f11ba2455b7272c22c93f5b7a4",
        matrix_member="nasa4704/nasa4704.mtx",
        b_member="nasa4704/nasa4704_b.mtx",
        source="SuiteSparse Matrix Collection, Nasa/nasa4704",
        b_source="nasa4704_b.mtx",
    ),
    "nasasrb": DatasetSpec(
        key="nasasrb",
        group="Nasa",
        archive_name="nasasrb.tar.gz",
        url="https://sparse.tamu.edu/MM/Nasa/nasasrb.tar.gz",
        sha256="ca86c95c0b8ed085e0251b3b162cb43b6b3b006bde58e37b54dba135a2dd8c9c",
        matrix_member="nasasrb/nasasrb.mtx",
        b_member="nasasrb/nasasrb_b.mtx",
        source="SuiteSparse Matrix Collection, Nasa/nasasrb",
        b_source="nasasrb_b.mtx",
    ),
    "pwt": DatasetSpec(
        key="pwt",
        group="Nasa",
        archive_name="pwt.tar.gz",
        url="https://sparse.tamu.edu/MM/Nasa/pwt.tar.gz",
        sha256="9a1419820b3696743ef6d05856b505d62d6859bc08465fe0aea4aaa6d58ec7ca",
        matrix_member="pwt/pwt.mtx",
        b_member=None,
        source="SuiteSparse Matrix Collection, Nasa/pwt",
        b_source="A * deterministic smooth x_ref",
    ),
    "thermal2": DatasetSpec(
        key="thermal2",
        group="Schmid",
        archive_name="thermal2.tar.gz",
        url="https://sparse.tamu.edu/MM/Schmid/thermal2.tar.gz",
        sha256="02934a4b642b6829c33517e0b801b60ea894a6552c6cd7e3db6c709c776434ce",
        matrix_member="thermal2/thermal2.mtx",
        b_member="thermal2/thermal2_b.mtx",
        source="SuiteSparse Matrix Collection, Schmid/thermal2",
        b_source="thermal2_b.mtx",
        description="Full million-row SPD thermal problem.",
    ),
    "mad_low_density_balanced": DatasetSpec(
        key="mad_low_density_balanced",
        group="Schmid",
        archive_name="thermal2.tar.gz",
        url="https://sparse.tamu.edu/MM/Schmid/thermal2.tar.gz",
        sha256="02934a4b642b6829c33517e0b801b60ea894a6552c6cd7e3db6c709c776434ce",
        matrix_member="thermal2/thermal2.mtx",
        b_member="thermal2/thermal2_b.mtx",
        source="SuiteSparse Matrix Collection, Schmid/thermal2",
        b_source="thermal2_b.mtx",
        official_page="https://sparse.tamu.edu/Schmid/thermal2",
        category="low_density_balanced",
        description=(
            "Canonical low-density, 128-PE cyclic-balanced label for the original "
            "thermal2 benchmark; thermal2 remains a compatibility key."
        ),
    ),
    "thermal2_n1024": DatasetSpec(
        key="thermal2_n1024",
        group="Schmid",
        archive_name="thermal2.tar.gz",
        url="https://sparse.tamu.edu/MM/Schmid/thermal2.tar.gz",
        sha256="02934a4b642b6829c33517e0b801b60ea894a6552c6cd7e3db6c709c776434ce",
        matrix_member="thermal2/thermal2.mtx",
        b_member=None,
        source="SuiteSparse Matrix Collection, Schmid/thermal2",
        b_source="A * deterministic smooth x_ref",
        subset_n=1024,
        description="Leading 1024x1024 principal submatrix for default accelerator smoke tests.",
    ),
    "mad_high_density_balanced": DatasetSpec(
        key="mad_high_density_balanced",
        group="Boeing",
        archive_name="bcsstk36.tar.gz",
        url="https://sparse.tamu.edu/MM/Boeing/bcsstk36.tar.gz",
        sha256="5604590a92eb3f02acec595c144429a20efbd943ab4eaa18bfc11beb91dc3da2",
        matrix_member="bcsstk36/bcsstk36.mtx",
        b_member=None,
        source="SuiteSparse Matrix Collection, Boeing/bcsstk36",
        b_source="A * deterministic smooth x_ref",
        official_page="https://sparse.tamu.edu/Boeing/bcsstk36",
        category="high_density_balanced",
        description=(
            "Official symmetric positive definite matrix used as the high-density, "
            "128-PE cyclic-balanced reference case."
        ),
    ),
    "mad_high_density_imbalanced": DatasetSpec(
        key="mad_high_density_imbalanced",
        group="Boeing",
        archive_name="msc10848.tar.gz",
        url="https://sparse.tamu.edu/MM/Boeing/msc10848.tar.gz",
        sha256="d16462c5afd43ab447c01734ac165156efd2d94434872566d82961f76e8a4d3f",
        matrix_member="msc10848/msc10848.mtx",
        b_member=None,
        source="SuiteSparse Matrix Collection, Boeing/msc10848",
        b_source="A * deterministic smooth x_ref",
        official_page="https://sparse.tamu.edu/Boeing/msc10848",
        category="high_density_imbalanced",
        transform="cluster_heavy_rows_mod_128",
        description=(
            "Official symmetric positive definite matrix with a deterministic P*A*P^T "
            "row/column permutation that exposes cyclic PE imbalance."
        ),
    ),
    "mad_low_density_imbalanced": DatasetSpec(
        key="mad_low_density_imbalanced",
        group="FlowIPM22",
        archive_name="Spielman_k100.tar.gz",
        url="https://sparse.tamu.edu/MM/FlowIPM22/Spielman_k100.tar.gz",
        sha256="ac31a4ef540c40c16ac42a2f9e6ba7783a75d6892d6415a3ed0919b987ceb4ce",
        matrix_member="Spielman_k100/Spielman_k100.mtx",
        b_member=None,
        source="SuiteSparse Matrix Collection, FlowIPM22/Spielman_k100",
        b_source="A * deterministic smooth x_ref",
        official_page="https://sparse.tamu.edu/FlowIPM22/Spielman_k100",
        category="low_density_imbalanced",
        transform="cluster_heavy_rows_mod_128",
        description=(
            "Official symmetric positive definite matrix whose density is close to "
            "thermal2; a deterministic P*A*P^T permutation makes the cyclic PE load "
            "imbalance observable."
        ),
    ),
}


DATASET_ALIASES = {
    "mad": (
        "mad_low_density_balanced",
        "mad_high_density_balanced",
        "mad_high_density_imbalanced",
        "mad_low_density_imbalanced",
    ),
}


def make_thermal2_subset_spec(size: int) -> DatasetSpec:
    if size <= 0:
        raise SystemExit("thermal2 subset size must be positive")
    if size > THERMAL2_SIZE:
        raise SystemExit(f"thermal2 subset size must be <= {THERMAL2_SIZE}")
    return DatasetSpec(
        key=f"thermal2_n{size}",
        group="Schmid",
        archive_name="thermal2.tar.gz",
        url="https://sparse.tamu.edu/MM/Schmid/thermal2.tar.gz",
        sha256="02934a4b642b6829c33517e0b801b60ea894a6552c6cd7e3db6c709c776434ce",
        matrix_member="thermal2/thermal2.mtx",
        b_member=None,
        source="SuiteSparse Matrix Collection, Schmid/thermal2",
        b_source="A * deterministic smooth x_ref",
        subset_n=size,
        description=(
            f"Leading {size}x{size} principal submatrix derived from the full thermal2 SPD matrix."
        ),
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_archive(spec: DatasetSpec, data_root: Path, force: bool) -> Path:
    raw_dir = data_root / spec.group / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    archive_path = raw_dir / spec.archive_name

    if archive_path.is_file() and not force:
        actual = sha256_file(archive_path)
        if actual == spec.sha256:
            print(f"[skip] archive already present: {archive_path}")
            return archive_path
        print(f"[warn] checksum mismatch for {archive_path}; downloading again")

    tmp_path = archive_path.with_suffix(archive_path.suffix + ".tmp")
    print(f"[download] {spec.url}")
    with urllib.request.urlopen(spec.url) as response, tmp_path.open("wb") as output:
        shutil.copyfileobj(response, output)

    actual = sha256_file(tmp_path)
    if actual != spec.sha256:
        tmp_path.unlink(missing_ok=True)
        raise RuntimeError(
            f"checksum mismatch for {spec.archive_name}: expected {spec.sha256}, got {actual}"
        )

    tmp_path.replace(archive_path)
    return archive_path


def extract_members(archive_path: Path, data_root: Path, spec: DatasetSpec) -> None:
    target_dir = data_root / spec.group / "mtx"
    target_dir.mkdir(parents=True, exist_ok=True)
    requested = {spec.matrix_member}
    if spec.b_member:
        requested.add(spec.b_member)

    with tarfile.open(archive_path, "r:gz") as archive:
        members_by_name = {member.name: member for member in archive.getmembers()}
        for member_name in sorted(requested):
            member = members_by_name.get(member_name)
            if member is None:
                raise RuntimeError(f"{member_name} not found in {archive_path}")
            if member.isdir():
                continue
            destination = (target_dir / member.name).resolve()
            if not str(destination).startswith(str(target_dir.resolve())):
                raise RuntimeError(f"unsafe archive path: {member.name}")
            if destination.is_file():
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            source = archive.extractfile(member)
            if source is None:
                raise RuntimeError(f"could not extract {member.name}")
            with source, destination.open("wb") as output:
                shutil.copyfileobj(source, output)
            print(f"[extract] {destination}")


def next_data_line(handle) -> str:
    for raw_line in handle:
        line = raw_line.strip()
        if line and not line.startswith("%"):
            return line
    raise RuntimeError("unexpected end of MatrixMarket file")


def read_mm_header(path: Path) -> tuple[list[str], str, object]:
    handle = path.open("r", encoding="utf-8", errors="ignore")
    header = handle.readline().strip().split()
    if len(header) < 5 or header[0] != "%%MatrixMarket" or header[1] != "matrix":
        handle.close()
        raise RuntimeError(f"unsupported MatrixMarket header in {path}")
    return header, next_data_line(handle), handle


def add_entry(rows: list[dict[int, float] | None], row: int, col: int, value: float) -> None:
    if value == 0.0:
        return
    row_map = rows[row]
    if row_map is None:
        row_map = {}
        rows[row] = row_map
    row_map[col] = row_map.get(col, 0.0) + value


def parse_coordinate_matrix(path: Path, subset_n: int | None) -> tuple[int, int, list[int], list[int], list[float], dict]:
    header, dims, handle = read_mm_header(path)
    storage = header[2].lower()
    field = header[3].lower()
    symmetry = header[4].lower()
    if storage != "coordinate":
        handle.close()
        raise RuntimeError(f"expected coordinate MatrixMarket matrix: {path}")
    if field not in {"real", "integer", "pattern"}:
        handle.close()
        raise RuntimeError(f"unsupported MatrixMarket field '{field}' in {path}")
    if symmetry not in {"general", "symmetric"}:
        handle.close()
        raise RuntimeError(f"unsupported MatrixMarket symmetry '{symmetry}' in {path}")

    nrows, ncols, _nnz_reported = (int(token) for token in dims.split()[:3])
    out_rows = min(nrows, subset_n) if subset_n else nrows
    out_cols = min(ncols, subset_n) if subset_n else ncols
    rows: list[dict[int, float] | None] = [None] * out_rows

    with handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line or line.startswith("%"):
                continue
            parts = line.split()
            row = int(parts[0]) - 1
            col = int(parts[1]) - 1
            if field == "pattern":
                value = 1.0
            else:
                value = float(parts[2])

            if row < out_rows and col < out_cols:
                add_entry(rows, row, col, value)
            if symmetry == "symmetric" and row != col and col < out_rows and row < out_cols:
                add_entry(rows, col, row, value)

    row_ptr = [0]
    col_idx: list[int] = []
    values: list[float] = []
    diag_abs = []
    for row in range(out_rows):
        row_map = rows[row] or {}
        for col in sorted(row_map):
            value = row_map[col]
            if value == 0.0:
                continue
            col_idx.append(col)
            values.append(value)
            if row == col:
                diag_abs.append(abs(value))
        row_ptr.append(len(col_idx))

    info = {
        "matrix_market_storage": storage,
        "matrix_market_field": field,
        "matrix_market_symmetry": symmetry,
        "original_shape": [nrows, ncols],
        "diag_min_abs": min(diag_abs) if diag_abs else None,
        "has_nonzero_diag_entries": bool(diag_abs),
    }
    return out_rows, out_cols, row_ptr, col_idx, values, info


def parse_vector(path: Path, expected_n: int, subset_n: int | None) -> list[float]:
    header, dims, handle = read_mm_header(path)
    storage = header[2].lower()
    field = header[3].lower()
    if field not in {"real", "integer", "pattern"}:
        handle.close()
        raise RuntimeError(f"unsupported vector field '{field}' in {path}")

    limit = min(expected_n, subset_n) if subset_n else expected_n
    vector = [0.0] * limit
    with handle:
        if storage == "array":
            nrows, ncols = (int(token) for token in dims.split()[:2])
            if nrows < limit or ncols < 1:
                raise RuntimeError(f"vector dimensions do not match dataset: {path}")
            index = 0
            for raw_line in handle:
                if index >= limit:
                    break
                line = raw_line.strip()
                if not line or line.startswith("%"):
                    continue
                for token in line.split():
                    if index >= limit:
                        break
                    vector[index] = float(token)
                    index += 1
            if index < limit:
                raise RuntimeError(f"not enough vector entries in {path}")
            return vector

        if storage == "coordinate":
            nrows, ncols, _nnz = (int(token) for token in dims.split()[:3])
            if nrows < limit or ncols < 1:
                raise RuntimeError(f"vector dimensions do not match dataset: {path}")
            for raw_line in handle:
                line = raw_line.strip()
                if not line or line.startswith("%"):
                    continue
                parts = line.split()
                row = int(parts[0]) - 1
                col = int(parts[1]) - 1
                if row >= limit or col != 0:
                    continue
                vector[row] = 1.0 if field == "pattern" else float(parts[2])
            return vector

    raise RuntimeError(f"unsupported vector MatrixMarket storage '{storage}' in {path}")


def reference_solution(size: int) -> list[float]:
    center = max(1, size - 1) * 0.63
    width = max(8.0, size * 0.07)
    return [
        0.75
        + 0.18 * math.sin(0.013 * index)
        + 0.12 * math.cos(0.017 * index)
        + 0.0000009 * index
        + 0.28 * math.exp(-((index - center) / width) ** 2)
        for index in range(size)
    ]


def csr_spmv(row_ptr: list[int], col_idx: list[int], values: list[float], vector: list[float]) -> list[float]:
    result = [0.0] * (len(row_ptr) - 1)
    for row in range(len(result)):
        accum = 0.0
        for offset in range(row_ptr[row], row_ptr[row + 1]):
            accum += values[offset] * vector[col_idx[offset]]
        result[row] = accum
    return result


def permute_csr_cluster_heavy_rows(
    row_ptr: list[int], col_idx: list[int], values: list[float], pe_count: int
) -> tuple[list[int], list[int], list[float], dict]:
    """按确定性对称置换聚集宽行，同时保留矩阵的 SPD 性质。"""
    if pe_count <= 0:
        raise ValueError("pe_count must be positive")

    n = len(row_ptr) - 1
    row_nnz = [row_ptr[row + 1] - row_ptr[row] for row in range(n)]
    heavy_rows = sorted(range(n), key=lambda row: (-row_nnz[row], row))

    new_to_old = [-1] * n
    heavy_index = 0
    for new_row in range(n):
        if new_row % pe_count == 0:
            new_to_old[new_row] = heavy_rows[heavy_index]
            heavy_index += 1
    for new_row in range(n):
        if new_to_old[new_row] < 0:
            new_to_old[new_row] = heavy_rows[heavy_index]
            heavy_index += 1
    if heavy_index != n:
        raise AssertionError("invalid deterministic row permutation")

    old_to_new = [0] * n
    for new_row, old_row in enumerate(new_to_old):
        old_to_new[old_row] = new_row

    new_row_ptr = [0]
    new_col_idx: list[int] = []
    new_values: list[float] = []
    for new_row, old_row in enumerate(new_to_old):
        entries = [
            (old_to_new[col_idx[offset]], values[offset])
            for offset in range(row_ptr[old_row], row_ptr[old_row + 1])
        ]
        entries.sort(key=lambda entry: entry[0])
        for column, value in entries:
            new_col_idx.append(column)
            new_values.append(value)
        new_row_ptr.append(len(new_col_idx))

    return (
        new_row_ptr,
        new_col_idx,
        new_values,
        {
            "applied_transform": "P*A*P^T",
            "transform_rule": (
                f"sort rows by descending CSR row nnz; assign widest rows to new_row mod {pe_count} == 0"
            ),
            "transform_pe_count": pe_count,
            "positive_definite_preserved": True,
        },
    )


def csr_load_metrics(row_ptr: list[int], pe_count: int) -> dict:
    row_nnz = [row_ptr[row + 1] - row_ptr[row] for row in range(len(row_ptr) - 1)]
    n = len(row_nnz)
    nnz = row_ptr[-1] if row_ptr else 0
    pe_loads = [0] * pe_count
    for row, count in enumerate(row_nnz):
        pe_loads[row % pe_count] += count
    ideal_load = nnz / pe_count if pe_count else 0.0
    return {
        "density": nnz / max(n * n, 1),
        "row_nnz_min": min(row_nnz) if row_nnz else 0,
        "row_nnz_max": max(row_nnz) if row_nnz else 0,
        "row_nnz_avg": nnz / n if n else 0.0,
        "row_nnz_max_over_avg": (max(row_nnz) / (nnz / n)) if row_nnz and nnz else 0.0,
        "pe_count": pe_count,
        "pe_cyclic_load_min": min(pe_loads) if pe_loads else 0,
        "pe_cyclic_load_max": max(pe_loads) if pe_loads else 0,
        "pe_cyclic_load_ideal": ideal_load,
        "pe_cyclic_delta": (max(pe_loads) / ideal_load) if ideal_load else 0.0,
    }


def write_array(path: Path, values, formatter) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        first = True
        for value in values:
            if not first:
                handle.write(" ")
            handle.write(formatter(value))
            first = False
        handle.write("\n")


def write_symmetric_matrix_market(
    path: Path, n: int, row_ptr: list[int], col_idx: list[int], values: list[float]
) -> None:
    """为置换后的 CSR 写出可供 MAD host 使用的对称 MatrixMarket 文件。"""
    upper_nnz = sum(
        1
        for row in range(n)
        for offset in range(row_ptr[row], row_ptr[row + 1])
        if col_idx[offset] >= row
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        handle.write("%%MatrixMarket matrix coordinate real symmetric\n")
        handle.write(f"{n} {n} {upper_nnz}\n")
        for row in range(n):
            for offset in range(row_ptr[row], row_ptr[row + 1]):
                column = col_idx[offset]
                if column >= row:
                    handle.write(f"{row + 1} {column + 1} {values[offset]:.17g}\n")


def display_path(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path.resolve())


def write_metadata(output_dir: Path, spec: DatasetSpec, matrix_path: Path, n: int, m: int, nnz: int, info: dict) -> None:
    meta = {
        "name": spec.key,
        "source": spec.source,
        "official_page": spec.official_page,
        "download_url": spec.url,
        "archive_sha256": spec.sha256,
        "shape": [n, m],
        "nnz_csr": nnz,
        "matrix_market_path": display_path(matrix_path),
        "b_source": spec.b_source,
        "x0": "zeros",
        "suite_sparse_symmetric": True,
        "suite_sparse_positive_definite": True,
        "category": spec.category,
        "transform": spec.transform,
    }
    if spec.description:
        meta["description"] = spec.description
    for key, value in info.items():
        if value is not None:
            meta[key] = value
    if spec.subset_n is not None:
        meta["subset_type"] = "leading_principal_submatrix"
        meta["subset_n"] = spec.subset_n

    (output_dir / "meta.json").write_text(json.dumps(meta, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    lines = [
        f"name={spec.key}",
        f"source={spec.source}",
        f"official_page={spec.official_page or ''}",
        f"download_url={spec.url}",
        f"archive_sha256={spec.sha256}",
        f"n={n}",
        f"m={m}",
        f"nnz={nnz}",
        f"b_source={spec.b_source}",
        "x0=zeros",
        "suite_sparse_symmetric=true",
        "suite_sparse_positive_definite=true",
        f"category={spec.category or ''}",
        f"transform={spec.transform}",
    ]
    if spec.description:
        lines.append(f"description={spec.description}")
    if spec.subset_n is not None:
        lines.append("subset_type=leading_principal_submatrix")
        lines.append(f"subset_n={spec.subset_n}")
    for key in (
        "density",
        "row_nnz_min",
        "row_nnz_max",
        "row_nnz_avg",
        "row_nnz_max_over_avg",
        "pe_count",
        "pe_cyclic_load_min",
        "pe_cyclic_load_max",
        "pe_cyclic_load_ideal",
        "pe_cyclic_delta",
        "applied_transform",
        "transform_rule",
        "transform_pe_count",
        "positive_definite_preserved",
        "source_matrix_market_path",
        "derived_matrix_market_path",
    ):
        if key in info:
            lines.append(f"{key}={info[key]}")
    (output_dir / "meta.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def convert_dataset(spec: DatasetSpec, data_root: Path, force_download: bool) -> None:
    archive_path = download_archive(spec, data_root, force_download)
    extract_members(archive_path, data_root, spec)

    matrix_path = data_root / spec.group / "mtx" / spec.matrix_member
    b_path = data_root / spec.group / "mtx" / spec.b_member if spec.b_member else None
    output_dir = data_root / spec.group / "csr" / spec.key
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"[convert] {spec.key}: {matrix_path}")
    n, m, row_ptr, col_idx, values, info = parse_coordinate_matrix(matrix_path, spec.subset_n)
    if n != m:
        raise RuntimeError(f"{spec.key} is not square after conversion: {n}x{m}")

    if spec.transform == "cluster_heavy_rows_mod_128":
        row_ptr, col_idx, values, transform_info = permute_csr_cluster_heavy_rows(
            row_ptr, col_idx, values, spec.pe_count
        )
        info.update(transform_info)
    elif spec.transform != "none":
        raise RuntimeError(f"unsupported dataset transform: {spec.transform}")

    info.update(csr_load_metrics(row_ptr, spec.pe_count))

    metadata_matrix_path = matrix_path
    if spec.transform != "none":
        metadata_matrix_path = (
            data_root / spec.group / "mtx" / spec.key / f"{spec.key}.mtx"
        )
        write_symmetric_matrix_market(metadata_matrix_path, n, row_ptr, col_idx, values)
        info["source_matrix_market_path"] = display_path(matrix_path)
        info["derived_matrix_market_path"] = display_path(metadata_matrix_path)

    if b_path is not None:
        b = parse_vector(b_path, n, spec.subset_n)
    else:
        b = csr_spmv(row_ptr, col_idx, values, reference_solution(n))
    x0 = [0.0] * n

    write_array(output_dir / "row_ptr.txt", row_ptr, lambda value: str(int(value)))
    write_array(output_dir / "col_idx.txt", col_idx, lambda value: str(int(value)))
    write_array(output_dir / "values.txt", values, lambda value: f"{value:.17g}")
    write_array(output_dir / "b.txt", b, lambda value: f"{value:.17g}")
    write_array(output_dir / "x0.txt", x0, lambda value: f"{value:.17g}")
    write_metadata(output_dir, spec, metadata_matrix_path, n, m, len(col_idx), info)
    print(f"[done] {spec.key}: n={n} nnz={len(col_idx)} -> {output_dir}")


def parse_dataset_selection(raw_items: list[str]) -> list[DatasetSpec]:
    selected: list[str] = []
    for item in raw_items:
        selected.extend(part.strip() for part in item.split(",") if part.strip())
    if not selected:
        selected = ["thermal2_n1024"]
    if selected == ["all"]:
        return list(DATASETS.values())

    expanded: list[str] = []
    for key in selected:
        expanded.extend(DATASET_ALIASES.get(key, (key,)))
    selected = expanded

    specs = []
    unknown = []
    for key in selected:
        if key in DATASETS:
            specs.append(DATASETS[key])
            continue
        match = re.fullmatch(r"thermal2_n(\d+)", key)
        if match:
            specs.append(make_thermal2_subset_spec(int(match.group(1))))
            continue
        unknown.append(key)
    if unknown:
        raise SystemExit(f"unknown dataset(s): {', '.join(sorted(set(unknown)))}")
    return specs


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Download SuiteSparse matrices and convert them to accelerator-sim shared CSR text datasets."
    )
    parser.add_argument(
        "--datasets",
        nargs="*",
        default=["thermal2_n1024"],
        help="dataset keys, comma-separated or space-separated; use 'mad' for the four MAD cases or 'all' for every known dataset",
    )
    parser.add_argument(
        "--data-root",
        type=Path,
        default=DEFAULT_DATA_ROOT,
        help=f"output root, default: {DEFAULT_DATA_ROOT}",
    )
    parser.add_argument("--force-download", action="store_true", help="download archives again even if present")
    parser.add_argument("--list", action="store_true", help="list available dataset keys and exit")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.list:
        for alias, keys in DATASET_ALIASES.items():
            print(f"{alias}: {', '.join(keys)}")
        for key, spec in DATASETS.items():
            suffix = f" ({spec.description})" if spec.description else ""
            category = f" [{spec.category}]" if spec.category else ""
            print(f"{key}{category}: {spec.source}{suffix}")
        print("thermal2_n<N>: leading NxN principal submatrix of Schmid/thermal2")
        return 0

    data_root = args.data_root.resolve()
    selected_specs = parse_dataset_selection(args.datasets)
    print(f"data_root: {data_root}")
    print(f"datasets: {', '.join(spec.key for spec in selected_specs)}")
    for spec in selected_specs:
        convert_dataset(spec, data_root, args.force_download)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
