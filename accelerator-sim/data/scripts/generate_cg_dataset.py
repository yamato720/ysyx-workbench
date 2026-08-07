#!/usr/bin/env python3

from __future__ import annotations

import argparse
import html
import math
from pathlib import Path


def choose_grid_shape(size: int, aspect_ratio: float) -> tuple[int, int]:
    cols = max(1, math.ceil(math.sqrt(size * aspect_ratio)))
    rows = max(1, math.ceil(size / cols))
    return rows, cols


def node_position(index: int, cols: int) -> tuple[int, int]:
    return index // cols, index % cols


def add_edge(row_maps: list[dict[int, float]], diag: list[float], lhs: int, rhs: int, weight: float) -> None:
    if lhs == rhs or weight <= 0.0:
        return
    row_maps[lhs][rhs] = row_maps[lhs].get(rhs, 0.0) - weight
    row_maps[rhs][lhs] = row_maps[rhs].get(lhs, 0.0) - weight
    diag[lhs] += weight
    diag[rhs] += weight


def horizontal_weight(row: int, col: int) -> float:
    base = 1.4 + 0.18 * math.sin(0.17 * row) + 0.11 * math.cos(0.09 * col)
    channel = 0.22 if (row // 9) % 2 == 0 else -0.08
    return max(0.35, base + channel)


def vertical_weight(row: int, col: int) -> float:
    base = 0.95 + 0.15 * math.cos(0.13 * row) + 0.14 * math.sin(0.11 * col)
    rib = 0.20 if (col // 11) % 3 == 1 else 0.02
    return max(0.25, base + rib)


def contact_weight(row: int, col: int) -> float:
    return 0.08 + 0.03 * (1.0 + math.sin(0.23 * row + 0.19 * col))


def mass_term(index: int, row: int, col: int) -> float:
    return 0.18 + 0.04 * math.sin(0.07 * index) + 0.03 * math.cos(0.15 * row - 0.08 * col)


def build_sparse_spd_matrix(size: int, aspect_ratio: float) -> tuple[int, int, list[int], list[int], list[float], list[float]]:
    rows, cols = choose_grid_shape(size, aspect_ratio)
    row_maps = [dict() for _ in range(size)]
    diag = [0.0] * size

    for index in range(size):
        row, col = node_position(index, cols)

        right = index + 1
        if col + 1 < cols and right < size:
            add_edge(row_maps, diag, index, right, horizontal_weight(row, col))

        down = index + cols
        if down < size:
            add_edge(row_maps, diag, index, down, vertical_weight(row, col))

        if row % 13 == 5 and col % 7 == 2:
            target = index + 2 * cols + 1
            if col + 1 < cols and target < size:
                add_edge(row_maps, diag, index, target, contact_weight(row, col))

        if row % 10 == 3 and col % 9 == 4:
            target = index + cols - 1
            if col > 0 and target < size:
                add_edge(row_maps, diag, index, target, 0.85 * contact_weight(row, col))

    for index in range(size):
        row, col = node_position(index, cols)
        diag[index] += mass_term(index, row, col)

    row_ptr = [0]
    col_idx: list[int] = []
    values: list[float] = []

    for index in range(size):
        entries = row_maps[index]
        entries[index] = diag[index]
        for col in sorted(entries):
            col_idx.append(col)
            values.append(entries[col])
        row_ptr.append(len(col_idx))

    return rows, cols, row_ptr, col_idx, values, diag


def build_reference_solution(size: int, cols: int) -> list[float]:
    reference = []
    hotspot_center = max(1, size - 1) * 0.63
    hotspot_width = max(8.0, size * 0.07)
    for index in range(size):
        row, col = node_position(index, cols)
        smooth = 0.75 + 0.18 * math.sin(0.06 * row) + 0.12 * math.cos(0.08 * col)
        gradient = 0.0009 * index
        hotspot = 0.28 * math.exp(-((index - hotspot_center) / hotspot_width) ** 2)
        reference.append(smooth + gradient + hotspot)
    return reference


def build_initial_guess(size: int, cols: int) -> list[float]:
    initial = []
    for index in range(size):
        row, col = node_position(index, cols)
        value = 0.09 * math.cos(0.04 * index) - 0.06 * math.sin(0.09 * row) + 0.03 * math.cos(0.21 * col)
        initial.append(value)
    return initial


def csr_spmv(size: int, row_ptr: list[int], col_idx: list[int], values: list[float], vector: list[float]) -> list[float]:
    result = [0.0] * size
    for row in range(size):
        accum = 0.0
        for offset in range(row_ptr[row], row_ptr[row + 1]):
            accum += values[offset] * vector[col_idx[offset]]
        result[row] = accum
    return result


def write_float_array(path: Path, values: list[float]) -> None:
    path.write_text(" ".join(f"{value:.17g}" for value in values) + "\n", encoding="utf-8")


def write_int_array(path: Path, values: list[int]) -> None:
    path.write_text(" ".join(str(value) for value in values) + "\n", encoding="utf-8")


def render_matrix_svg(size: int,
                      row_ptr: list[int],
                      col_idx: list[int],
                      values: list[float],
                      output_path: Path) -> None:
    view_size = 1200
    pad = 40
    inner = view_size - 2 * pad

    max_abs = max(abs(value) for value in values) if values else 1.0
    cells = []
    for row in range(size):
        for offset in range(row_ptr[row], row_ptr[row + 1]):
            col = col_idx[offset]
            value = values[offset]
            x = pad + (col / max(size, 1)) * inner
            y = pad + (row / max(size, 1)) * inner
            w = max(inner / max(size, 1), 1.2)
            h = max(inner / max(size, 1), 1.2)
            strength = min(abs(value) / max_abs, 1.0)
            if value >= 0.0:
                color = f"rgba(15,118,110,{0.20 + 0.75 * strength:.3f})"
            else:
                color = f"rgba(190,24,93,{0.20 + 0.75 * strength:.3f})"
            cells.append(
                f'<rect x="{x:.3f}" y="{y:.3f}" width="{w:.3f}" height="{h:.3f}" fill="{color}" />'
            )

    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="{view_size}" height="{view_size}" viewBox="0 0 {view_size} {view_size}">
  <rect x="0" y="0" width="{view_size}" height="{view_size}" fill="#f6f4ee" />
  <rect x="{pad}" y="{pad}" width="{inner}" height="{inner}" fill="#fffdf8" stroke="#ddd7cc" stroke-width="1.5" />
  <line x1="{pad}" y1="{pad}" x2="{pad}" y2="{pad + inner}" stroke="#1f2937" stroke-width="1.2" />
  <line x1="{pad}" y1="{pad + inner}" x2="{pad + inner}" y2="{pad + inner}" stroke="#1f2937" stroke-width="1.2" />
  {''.join(cells)}
  <text x="{view_size / 2:.1f}" y="22" text-anchor="middle" font-size="20" fill="#1f2937">Sparse Matrix Pattern</text>
  <text x="{view_size / 2:.1f}" y="{view_size - 10}" text-anchor="middle" font-size="14" fill="#6b7280">column index</text>
  <text x="16" y="{view_size / 2:.1f}" text-anchor="middle" font-size="14" fill="#6b7280" transform="rotate(-90 16 {view_size / 2:.1f})">row index</text>
</svg>
"""
    output_path.write_text(svg, encoding="utf-8")


def render_matrix_html(size: int,
                       nnz: int,
                       row_ptr: list[int],
                       col_idx: list[int],
                       values: list[float],
                       output_path: Path,
                       svg_path: Path) -> None:
    max_abs = max(abs(value) for value in values) if values else 1.0
    sample_rows = []
    preview_count = min(24, nnz)
    for idx in range(preview_count):
        row = next((r for r in range(size) if row_ptr[r] <= idx < row_ptr[r + 1]), 0)
        sample_rows.append(
            f"<tr><td>{row}</td><td>{col_idx[idx]}</td><td>{values[idx]:.6g}</td></tr>"
        )

    html_text = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>CG Dataset Matrix View</title>
  <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
  <style>
    :root {{
      --bg: #f6f4ee;
      --panel: #fffdf8;
      --line: #ddd7cc;
      --ink: #1f2937;
      --muted: #6b7280;
    }}
    body {{
      background: linear-gradient(180deg, #f6f4ee 0%, #efebe2 100%);
      color: var(--ink);
      font-family: "Helvetica Neue", Arial, sans-serif;
    }}
    .page {{
      max-width: 1480px;
      margin: 0 auto;
      padding: 24px;
    }}
    .report-card {{
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 18px;
      box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
      height: 100%;
    }}
    .report-card .card-body {{
      padding: 20px 22px;
    }}
    .report-title {{
      font-size: 2rem;
      font-weight: 700;
      letter-spacing: -0.02em;
    }}
    .report-subtitle {{
      color: var(--muted);
    }}
    .kv-grid {{
      display: grid;
      gap: 10px;
    }}
    .kv-row {{
      display: grid;
      grid-template-columns: 150px 1fr;
      gap: 14px;
      align-items: baseline;
      font-size: 0.95rem;
    }}
    .kv-key {{
      color: var(--muted);
    }}
    .kv-value {{
      font-weight: 700;
    }}
    .legend {{
      display: flex;
      gap: 16px;
      flex-wrap: wrap;
      color: var(--muted);
      font-size: 0.9rem;
    }}
    .swatch {{
      display: inline-block;
      width: 14px;
      height: 14px;
      border-radius: 4px;
      margin-right: 6px;
      vertical-align: -2px;
    }}
    .table-wrap {{
      overflow-x: auto;
    }}
  </style>
</head>
<body>
  <div class="page">
    <div class="mb-4">
      <div class="report-title">CG Dataset Matrix View</div>
      <div class="report-subtitle">{html.escape(str(output_path.parent))}</div>
    </div>
    <div class="row g-4 mb-4">
      <div class="col-12 col-xl-4">
        <div class="report-card card"><div class="card-body">
          <div class="kv-grid">
            <div class="kv-row"><div class="kv-key">n</div><div class="kv-value">{size}</div></div>
            <div class="kv-row"><div class="kv-key">nnz</div><div class="kv-value">{nnz}</div></div>
            <div class="kv-row"><div class="kv-key">density</div><div class="kv-value">{nnz / max(size * size, 1):.6f}</div></div>
            <div class="kv-row"><div class="kv-key">max |value|</div><div class="kv-value">{max_abs:.6g}</div></div>
          </div>
          <div class="mt-3 legend">
            <span><span class="swatch" style="background: rgba(15,118,110,0.75);"></span>positive</span>
            <span><span class="swatch" style="background: rgba(190,24,93,0.75);"></span>negative</span>
          </div>
        </div></div>
      </div>
      <div class="col-12 col-xl-8">
        <div class="report-card card"><div class="card-body">
          <img src="{html.escape(svg_path.name)}" alt="matrix svg" style="width:100%; height:auto;" />
        </div></div>
      </div>
    </div>
    <div class="row g-4">
      <div class="col-12">
        <div class="report-card card"><div class="card-body">
          <div class="table-wrap">
            <table class="table table-sm align-middle">
              <thead><tr><th>row</th><th>col</th><th>value</th></tr></thead>
              <tbody>{''.join(sample_rows)}</tbody>
            </table>
          </div>
        </div></div>
      </div>
    </div>
  </div>
</body>
</html>
"""
    output_path.write_text(html_text, encoding="utf-8")


def cleanup_output_dir(output_dir: Path) -> None:
    for file_name in [
        "A.mtx",
        "meta.json",
        "meta.txt",
        "diag.txt",
        "jacobi_diag.txt",
        "rhs.txt",
        "x_ref.txt",
        "x_expected.txt",
        "row_ptr.txt",
        "col_idx.txt",
        "values.txt",
        "b.txt",
        "x0.txt",
        "matrix.svg",
        "matrix.html",
    ]:
        path = output_dir / file_name
        if path.exists():
            path.unlink()


def generate_dataset(size: int, output_dir: Path, aspect_ratio: float) -> None:
    mesh_rows, mesh_cols, row_ptr, col_idx, values, _diag = build_sparse_spd_matrix(size, aspect_ratio)
    x_ref = build_reference_solution(size, mesh_cols)
    x0 = build_initial_guess(size, mesh_cols)
    b = csr_spmv(size, row_ptr, col_idx, values, x_ref)
    nnz = len(col_idx)

    output_dir.mkdir(parents=True, exist_ok=True)
    cleanup_output_dir(output_dir)

    write_int_array(output_dir / "row_ptr.txt", row_ptr)
    write_int_array(output_dir / "col_idx.txt", col_idx)
    write_float_array(output_dir / "values.txt", values)
    write_float_array(output_dir / "b.txt", b)
    write_float_array(output_dir / "x0.txt", x0)
    render_matrix_svg(size, row_ptr, col_idx, values, output_dir / "matrix.svg")
    render_matrix_html(size, nnz, row_ptr, col_idx, values, output_dir / "matrix.html", output_dir / "matrix.svg")

    print(
        "generated cg dataset "
        f"n={size} nnz={nnz} mesh={mesh_rows}x{mesh_cols} -> {output_dir}"
    )
    print("files: row_ptr.txt col_idx.txt values.txt b.txt x0.txt matrix.svg matrix.html")


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(
        description="Generate a deterministic sparse SPD dataset for CG solver validation."
    )
    parser.add_argument("--size", type=int, default=512, help="number of unknowns, default: 512")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=script_dir.parent / "generated" / "cgsolver" / "n512",
        help="output directory for generated files",
    )
    parser.add_argument(
        "--aspect-ratio",
        type=float,
        default=1.6,
        help="approximate mesh width/height ratio, default: 1.6",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    if args.size <= 0:
        raise SystemExit("--size must be positive")
    if args.aspect_ratio <= 0.0:
        raise SystemExit("--aspect-ratio must be positive")

    default_output_dir = Path(__file__).resolve().parent.parent / "generated" / "cgsolver" / f"n{args.size}"
    output_dir = args.output_dir
    if output_dir.name == "n512" and args.size != 512:
        output_dir = default_output_dir

    generate_dataset(
        size=args.size,
        output_dir=output_dir,
        aspect_ratio=args.aspect_ratio,
    )


if __name__ == "__main__":
    main()
