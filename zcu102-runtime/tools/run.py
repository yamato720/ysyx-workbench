#!/usr/bin/env python3
import argparse
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Task:
    target: str
    title: str
    desc: str
    section: str

    @property
    def make_cmd(self) -> list[str]:
        return ["make", self.target]


SECTIONS = {
    "build": "构造",
    "run": "执行",
}


TASKS: dict[str, Task] = {
    "build-cpp": Task(
        "build-cpp",
        "构造 C/C++",
        "源码编译 PS 侧 NEMU SDB 和 busy-box-nano",
        "build",
    ),
    "build-so": Task(
        "build-so",
        "构造 SO",
        "构建 NEMU reference shared object",
        "build",
    ),
    "build-all": Task(
        "build-all",
        "构造全部",
        "构造当前 C/C++ 程序和 shared object",
        "build",
    ),
    "build-ps": Task(
        "build-ps",
        "构造 PS SDB",
        "源码编译 RV64/RV32 软件 NEMU SDB",
        "build",
    ),
    "build-ps-rv64": Task(
        "build-ps-rv64",
        "构造 RV64 SDB",
        "源码编译 riscv64-nemu PS 侧软件 SDB",
        "build",
    ),
    "build-ps-rv32": Task(
        "build-ps-rv32",
        "构造 RV32 SDB",
        "源码编译 riscv32-nemu PS 侧软件 SDB",
        "build",
    ),
    "build-busy-box-nano": Task(
        "build-busy-box-nano",
        "构造 busy-box-nano",
        "编译 C 版软件选择 shell",
        "build",
    ),
    "nemu-ref": Task(
        "nemu-ref",
        "构造 NEMU .so",
        "构建 ../nemu/build/riscv64-nemu-interpreter-so",
        "build",
    ),
    "chisel": Task(
        "chisel",
        "构造 debugger RTL",
        "生成 ZCU102NPCDebugger Verilog",
        "build",
    ),
    "selftest": Task(
        "selftest",
        "构造 selftest RTL",
        "生成 ZCU102NPCSelfTest Verilog",
        "build",
    ),
    "lint-rtl": Task(
        "lint-rtl",
        "检查 debugger RTL",
        "生成并 Verilator lint ZCU102NPCDebugger",
        "build",
    ),
    "lint-selftest": Task(
        "lint-selftest",
        "检查 selftest RTL",
        "生成并 Verilator lint ZCU102NPCSelfTest",
        "build",
    ),
    "clean-generated": Task(
        "clean-generated",
        "清理生成文件",
        "删除 generated 目录",
        "build",
    ),
    "busy-box-nano": Task(
        "busy-box-nano",
        "打开 busy-box-nano",
        "选择 arch/software 后现场构建并启动 NEMU SDB",
        "run",
    ),
}


ALIASES: dict[str, list[str]] = {
    "sdb": ["busy-box-nano"],
    "nano": ["busy-box-nano"],
    "cpp": ["build-cpp"],
    "so": ["build-so"],
    "all-build": ["build-all"],
}


def section_tasks(section: str) -> list[Task]:
    return [task for task in TASKS.values() if task.section == section]


def print_task_table() -> None:
    print("ZCU102 runtime launcher tasks\n")
    for section, label in SECTIONS.items():
        print(f"{label}:")
        for task in section_tasks(section):
            print(f"  {task.target:<18} {task.title:<18} {task.desc}")
        print()

    print("Aliases:")
    for name, targets in ALIASES.items():
        print(f"  {name:<18} {' -> '.join(targets)}")
    print()
    print("Examples:")
    print("  make launch")
    print("  make launcher")
    print("  make launch TASK=busy-box-nano")
    print("  ./tools/run.py build-busy-box-nano")


def print_sections() -> None:
    print("\nZCU102 runtime launcher")
    print("选择一个分区：")
    for idx, (section, label) in enumerate(SECTIONS.items(), start=1):
        print(f"  {idx}. {label}")
    print("\nCommands: list, help, quit")


def print_section_menu(section: str) -> None:
    tasks = section_tasks(section)
    label = SECTIONS[section]
    print(f"\n{label}:")
    for idx, task in enumerate(tasks, start=1):
        print(f"  {idx}. {task.target:<18} {task.title:<18} {task.desc}")
    print("\nCommands: back, list, help, quit")


def parse_section(raw: str) -> str | None:
    text = raw.strip()
    if text in SECTIONS:
        return text
    if text.isdigit():
        idx = int(text)
        keys = list(SECTIONS)
        if 1 <= idx <= len(keys):
            return keys[idx - 1]
    for section, label in SECTIONS.items():
        if text == label:
            return section
    return None


def parse_task(raw: str, section: str) -> list[str] | None:
    text = raw.strip()
    if not text:
        return None

    tasks = section_tasks(section)
    by_name = {task.target: task.target for task in tasks}

    selected: list[str] = []
    for part in text.replace(",", " ").split():
        if part.isdigit():
            idx = int(part)
            if idx < 1 or idx > len(tasks):
                print(f"无效编号: {part}")
                return None
            selected.append(tasks[idx - 1].target)
        elif part in by_name:
            selected.append(by_name[part])
        elif part in ALIASES:
            selected.extend(ALIASES[part])
        else:
            print(f"未知任务: {part}")
            return None
    return selected


def expand_tasks(names: list[str]) -> list[str]:
    expanded: list[str] = []
    for name in names:
        if name in ALIASES:
            expanded.extend(ALIASES[name])
        else:
            expanded.append(name)
    return expanded


def run_target(target: str, dry_run: bool) -> int:
    task = TASKS.get(target)
    if task is None:
        print(f"Unknown target: {target}", file=sys.stderr)
        return 2

    cmd = task.make_cmd
    print(f"\n==> {task.title}", flush=True)
    print(f"Makefile: {' '.join(cmd)}", flush=True)
    if dry_run:
        return 0

    result = subprocess.run(cmd, cwd=ROOT)
    return result.returncode


def run_many(targets: list[str], dry_run: bool) -> int:
    for target in expand_tasks(targets):
        code = run_target(target, dry_run)
        if code != 0:
            print(f"\nTask failed: {target} (exit {code})", file=sys.stderr)
            return code
    return 0


def interactive(dry_run: bool) -> int:
    print_sections()
    while True:
        try:
            raw = input("\nsection> ")
        except EOFError:
            print()
            return 0

        text = raw.strip()
        if not text:
            continue
        if text in {"q", "quit", "exit"}:
            return 0
        if text in {"h", "help", "?", "list"}:
            print_task_table()
            print_sections()
            continue

        section = parse_section(text)
        if section is None:
            print(f"未知分区: {text}")
            continue

        tasks = section_tasks(section)
        if len(tasks) == 1:
            code = run_many([tasks[0].target], dry_run)
            if code != 0:
                return code
            print_sections()
            continue

        print_section_menu(section)
        while True:
            try:
                raw_task = input(f"{SECTIONS[section]}> ")
            except EOFError:
                print()
                return 0

            task_text = raw_task.strip()
            if not task_text:
                continue
            if task_text in {"b", "back"}:
                print_sections()
                break
            if task_text in {"q", "quit", "exit"}:
                return 0
            if task_text in {"h", "help", "?", "list"}:
                print_section_menu(section)
                continue

            targets = parse_task(task_text, section)
            if targets is None:
                continue
            code = run_many(targets, dry_run)
            if code != 0:
                return code
            print("\nDone.")
            print_section_menu(section)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="ZCU102 runtime launcher",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "tasks",
        nargs="*",
        help="Makefile targets or aliases. Use 'list' to show options.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print Makefile commands without executing them.",
    )
    parser.add_argument(
        "-i",
        "--interactive",
        action="store_true",
        help="Open the interactive launcher.",
    )
    args = parser.parse_args()

    if args.interactive:
        return interactive(args.dry_run)

    if not args.tasks or args.tasks == ["list"]:
        print_task_table()
        return 0

    return run_many(args.tasks, args.dry_run)


if __name__ == "__main__":
    raise SystemExit(main())
