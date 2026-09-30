"""Fail if the committed Kotlin golden fixtures differ from freshly generated ones.

Usage: python tools/check_golden_fixtures.py FRESH_DIR COMMITTED_DIR

FRESH_DIR is where `generate_kotlin_core_golden_fixtures.py --out FRESH_DIR`
wrote every golden. Every file must exist on both sides and match byte for
byte, except SQLite databases: the file header records the version of the
SQLite library that wrote it, which differs between a Windows developer
machine and a Linux CI runner, so those are compared by their logical content
(schema and rows via iterdump).
"""

from __future__ import annotations

import sqlite3
import sys
from pathlib import Path


def _dump(path: Path) -> list[str]:
    con = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
    try:
        return list(con.iterdump())
    finally:
        con.close()


def compare(fresh: Path, committed: Path) -> list[str]:
    problems: list[str] = []
    fresh_names = {p.name for p in fresh.iterdir() if p.is_file() and not p.name.endswith(("-wal", "-shm"))}
    committed_names = {p.name for p in committed.iterdir() if p.is_file() and not p.name.endswith(("-wal", "-shm"))}
    for name in sorted(committed_names - fresh_names):
        problems.append(f"{name}: committed but no longer generated (stale golden)")
    for name in sorted(fresh_names - committed_names):
        problems.append(f"{name}: generated but not committed")
    for name in sorted(fresh_names & committed_names):
        a, b = fresh / name, committed / name
        if name.endswith(".db"):
            if _dump(a) != _dump(b):
                problems.append(f"{name}: database content differs")
        elif a.read_bytes() != b.read_bytes():
            problems.append(f"{name}: bytes differ ({a.stat().st_size} generated vs {b.stat().st_size} committed)")
    return problems


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    fresh, committed = Path(argv[1]), Path(argv[2])
    if not fresh.is_dir() or not committed.is_dir():
        print("both arguments must be existing directories")
        return 2
    problems = compare(fresh, committed)
    if problems:
        print("Golden fixtures are out of date with the Python that generates them:")
        for p in problems:
            print("  " + p)
        print("Regenerate: PYTHONPATH=. python tools/generate_kotlin_core_golden_fixtures.py")
        return 1
    print(f"All {len(list(committed.iterdir()))} golden fixtures match a fresh regeneration.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
