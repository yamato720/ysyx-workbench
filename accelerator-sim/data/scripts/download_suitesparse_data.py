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


def display_path(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path.resolve())


def write_metadata(output_dir: Path, spec: DatasetSpec, matrix_path: Path, n: int, m: int, nnz: int, info: dict) -> None:
    meta = {
        "name": spec.key,
        "source": spec.source,
        "shape": [n, m],
        "nnz_csr": nnz,
        "matrix_market_path": display_path(matrix_path),
        "b_source": spec.b_source,
        "x0": "zeros",
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
        f"n={n}",
        f"m={m}",
        f"nnz={nnz}",
        f"b_source={spec.b_source}",
        "x0=zeros",
    ]
    if spec.description:
        lines.append(f"description={spec.description}")
    if spec.subset_n is not None:
        lines.append("subset_type=leading_principal_submatrix")
        lines.append(f"subset_n={spec.subset_n}")
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
    write_metadata(output_dir, spec, matrix_path, n, m, len(col_idx), info)
    print(f"[done] {spec.key}: n={n} nnz={len(col_idx)} -> {output_dir}")


def parse_dataset_selection(raw_items: list[str]) -> list[DatasetSpec]:
    selected: list[str] = []
    for item in raw_items:
        selected.extend(part.strip() for part in item.split(",") if part.strip())
    if not selected:
        selected = ["thermal2_n1024"]
    if selected == ["all"]:
        return list(DATASETS.values())

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
        help="dataset keys, comma-separated or space-separated; use 'all' for every known dataset",
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
        for key, spec in DATASETS.items():
            suffix = f" ({spec.description})" if spec.description else ""
            print(f"{key}: {spec.source}{suffix}")
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
