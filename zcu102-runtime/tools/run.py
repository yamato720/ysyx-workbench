#!/usr/bin/env python3
import argparse
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


TASKS = {
    "chisel": {
        "cmd": ["make", "chisel"],
        "desc": "Generate Chisel ZCU102NPCDebugger Verilog",
    },
    "selftest": {
        "cmd": ["make", "selftest"],
        "desc": "Generate Chisel PS+PL self-test Verilog",
    },
    "lint-sw": {
        "cmd": ["make", "lint-sw"],
        "desc": "Compile-check PS-side C helper and self-test example",
    },
    "lint-rtl": {
        "cmd": ["make", "lint-rtl"],
        "desc": "Generate and Verilator-lint ZCU102NPCDebugger",
    },
    "lint-selftest": {
        "cmd": ["make", "lint-selftest"],
        "desc": "Generate and Verilator-lint ZCU102NPCSelfTest",
    },
    "check": {
        "cmd": ["make", "check"],
        "desc": "Run PS C compile check and debugger RTL lint",
    },
    "check-selftest": {
        "cmd": ["make", "check-selftest"],
        "desc": "Run PS C compile check and self-test RTL lint",
    },
    "clean": {
        "cmd": ["make", "clean-generated"],
        "desc": "Remove generated Verilog/FIRRTL outputs",
    },
}


ALIASES = {
    "all": ["check", "check-selftest"],
    "quick": ["lint-sw"],
    "rtl": ["lint-rtl", "lint-selftest"],
}


def print_tasks() -> None:
    print("ZCU102 runtime tasks:\n")
    width = max(len(name) for name in list(TASKS) + list(ALIASES))
    for name, task in TASKS.items():
        print(f"  {name:<{width}}  {task['desc']}")
    print("\nAliases:\n")
    for name, targets in ALIASES.items():
        print(f"  {name:<{width}}  {' -> '.join(targets)}")
    print("\nExamples:")
    print("  ./tools/run.py list")
    print("  ./tools/run.py check-selftest")
    print("  ./tools/run.py all")


def expand_tasks(names: list[str]) -> list[str]:
    expanded: list[str] = []
    for name in names:
        if name in ALIASES:
            expanded.extend(ALIASES[name])
        else:
            expanded.append(name)
    return expanded


def run_task(name: str, dry_run: bool) -> int:
    task = TASKS.get(name)
    if task is None:
        print(f"Unknown task: {name}", file=sys.stderr)
        print("Run './tools/run.py list' to see available tasks.", file=sys.stderr)
        return 2

    cmd = task["cmd"]
    printable = " ".join(cmd)
    print(f"\n==> {name}: {task['desc']}")
    print(f"    {printable}")

    if dry_run:
        return 0

    result = subprocess.run(cmd, cwd=ROOT)
    return result.returncode


def main() -> int:
    parser = argparse.ArgumentParser(
        description="ZCU102 runtime task launcher",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "tasks",
        nargs="*",
        help="Task names or aliases. Use 'list' to show options.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print commands without executing them.",
    )
    args = parser.parse_args()

    if not args.tasks or args.tasks == ["list"]:
        print_tasks()
        return 0

    tasks = expand_tasks(args.tasks)
    for task in tasks:
        code = run_task(task, args.dry_run)
        if code != 0:
            print(f"\nTask failed: {task} (exit {code})", file=sys.stderr)
            return code

    print("\nAll requested tasks completed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
