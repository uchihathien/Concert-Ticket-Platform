#!/usr/bin/env python3
"""Tổng hợp coverage JaCoCo của mọi module backend thành một bảng.

Chạy sau `./mvnw -B verify -Pcoverage` (trong backend/):

    python scripts/coverage-summary.py            # bảng theo module + tổng
    python scripts/coverage-summary.py --classes  # thêm 25 lớp thiếu dòng nhiều nhất
    python scripts/coverage-summary.py --fail-under-line 85   # thoát mã 1 nếu tổng dòng < 85% (cho CI)
    python scripts/coverage-summary.py --markdown             # bảng Markdown, dán vào $GITHUB_STEP_SUMMARY

Đọc target/site/jacoco-merged/jacoco.csv (unit + integration gộp). Module chưa có báo cáo gộp
thì lùi về target/site/jacoco/jacoco.csv (chỉ unit) và đánh dấu `unit-only` — để con số không
bao giờ trông đầy đủ hơn thực tế.
"""
import csv
import io
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "backend"
COLS = ("INSTRUCTION", "BRANCH", "LINE", "METHOD")


def pct(missed: int, covered: int) -> str:
    total = missed + covered
    return "   -  " if total == 0 else f"{100.0 * covered / total:5.1f}%"


def main() -> int:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
    rows = []
    classes = []
    for module_dir in sorted(p for p in ROOT.glob("*/*") if (p / "pom.xml").exists()):
        merged = module_dir / "target/site/jacoco-merged/jacoco.csv"
        unit = module_dir / "target/site/jacoco/jacoco.csv"
        source, kind = (merged, "merged") if merged.exists() else (unit, "unit-only")
        if not source.exists():
            continue
        totals = {c: [0, 0] for c in COLS}
        with source.open(encoding="utf-8") as f:
            for r in csv.DictReader(f):
                for c in COLS:
                    totals[c][0] += int(r[f"{c}_MISSED"])
                    totals[c][1] += int(r[f"{c}_COVERED"])
                classes.append((int(r["LINE_MISSED"]), module_dir.name, f"{r['PACKAGE']}.{r['CLASS']}"))
        rows.append((module_dir.name, kind, totals))

    if not rows:
        print("Chưa có báo cáo nào. Chạy `./mvnw -B verify -Pcoverage` trong backend/ trước.")
        return 1

    grand = {c: [0, 0] for c in COLS}
    md = "--markdown" in sys.argv
    if md:
        print("| module | nguồn | instr | branch | line | method |")
        print("| --- | --- | ---: | ---: | ---: | ---: |")
    else:
        print(f"{'module':28} {'nguồn':10} {'instr':>7} {'branch':>7} {'line':>7} {'method':>7}")
    for name, kind, t in rows:
        for c in COLS:
            grand[c][0] += t[c][0]
            grand[c][1] += t[c][1]
        cells = [pct(*t[c]).strip() for c in COLS]
        print(f"| {name} | {kind} | " + " | ".join(cells) + " |" if md else f"{name:28} {kind:10} " + " ".join(f"{x:>7}" for x in cells))
    cells = [pct(*grand[c]).strip() for c in COLS]
    print("| **TỔNG** | | " + " | ".join(f"**{x}**" for x in cells) + " |" if md else f"{'TỔNG':28} {'':10} " + " ".join(f"{x:>7}" for x in cells))
    print(f"\nDòng: {grand['LINE'][1]}/{sum(grand['LINE'])}   Nhánh: {grand['BRANCH'][1]}/{sum(grand['BRANCH'])}")

    if "--fail-under-line" in sys.argv:
        floor = float(sys.argv[sys.argv.index("--fail-under-line") + 1])
        actual = 100.0 * grand["LINE"][1] / max(1, sum(grand["LINE"]))
        if actual < floor:
            print(f"\nTHẤT BẠI: coverage dòng {actual:.1f}% < ngưỡng {floor:.1f}%", file=sys.stderr)
            return 1
        print(f"\nĐạt ngưỡng coverage dòng: {actual:.1f}% >= {floor:.1f}%")

    if "--classes" in sys.argv:
        print("\n25 lớp còn thiếu nhiều dòng nhất:")
        for missed, module, cls in sorted(classes, reverse=True)[:25]:
            print(f"  {missed:5} {module:24} {cls}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
