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


def menu_items() -> list[tuple[str, str, str]]:
    items: list[tuple[str, str, str]] = []
    for name, task in TASKS.items():
        items.append((name, task["desc"], "task"))
    for name, targets in ALIASES.items():
        items.append((name, " -> ".join(targets), "alias"))
    return items


def print_menu(items: list[tuple[str, str, str]]) -> None:
    print("\nZCU102 runtime launcher")
    print("Select one or more tasks by number or name.")
    print("Examples: 1, 2 5, check-selftest, all, dry all")
    print("Commands: list, help, quit\n")
    for idx, (name, desc, kind) in enumerate(items, start=1):
        label = f"{idx:2d}. {name}"
        print(f"  {label:<22} [{kind}] {desc}")


def parse_selection(raw: str, items: list[tuple[str, str, str]]) -> tuple[list[str], bool] | None:
    text = raw.strip()
    if not text:
        return None
    if text in {"q", "quit", "exit"}:
        return (["__quit__"], False)
    if text in {"h", "help", "?", "list"}:
        return (["__help__"], False)

    dry_run = False
    if text.startswith("dry "):
        dry_run = True
        text = text[4:].strip()

    selected: list[str] = []
    parts = text.replace(",", " ").split()
    for part in parts:
        if part.isdigit():
            idx = int(part)
            if idx < 1 or idx > len(items):
                print(f"Invalid selection number: {part}")
                return None
            selected.append(items[idx - 1][0])
        else:
            names = set(TASKS) | set(ALIASES)
            if part not in names:
                print(f"Unknown task or alias: {part}")
                return None
            selected.append(part)
    return (selected, dry_run)


def interactive() -> int:
    items = menu_items()
    print_menu(items)
    while True:
        try:
            raw = input("\nselect> ")
        except EOFError:
            print()
            return 0

        parsed = parse_selection(raw, items)
        if parsed is None:
            continue

        selected, dry_run = parsed
        if selected == ["__quit__"]:
            return 0
        if selected == ["__help__"]:
            print_menu(items)
            continue

        tasks = expand_tasks(selected)
        for task in tasks:
            code = run_task(task, dry_run)
            if code != 0:
                print(f"\nTask failed: {task} (exit {code})", file=sys.stderr)
                return code
        print("\nDone.")


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
    parser.add_argument(
        "-i",
        "--interactive",
        action="store_true",
        help="Open an interactive task menu.",
    )
    args = parser.parse_args()

    if args.interactive:
        return interactive()

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
