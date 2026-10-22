#!/usr/bin/env python3
"""Sinh báo cáo kiểm thử dạng Excel từ kết quả chạy thật.

    python scripts/build-test-report.py [đường dẫn .xlsx]     # mặc định docs/testing/bao-cao-kiem-thu.xlsx

Nguồn dữ liệu — KHÔNG có số nào gõ tay:
  * backend: target/surefire-reports + target/failsafe-reports (lần chạy gần nhất) và JaCoCo CSV;
  * frontend: JSON do Vitest / Playwright xuất ra (đặt ở FRONTEND_JSON_DIR, mặc định thư mục tạm của
    phiên; xem hàm `frontend_results`);
  * tên test: @DisplayName trong mã nguồn Java; ca kiểm thử chức năng: docs/testing/test-cases.md.

Chạy sau `./mvnw verify -Pcoverage` ở backend/. Tổng và tỉ lệ trong workbook là CÔNG THỨC Excel, nên
sửa một dòng kết quả thì mọi tổng tự tính lại.
"""
import csv
import glob
import io
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

from openpyxl import Workbook
from openpyxl.comments import Comment
from openpyxl.formatting.rule import CellIsRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

REPO = Path(__file__).resolve().parent.parent
BACKEND = REPO / "backend"
DOCS = REPO / "docs" / "testing"
FRONTEND_JSON_DIR = Path(os.environ.get("FRONTEND_JSON_DIR", ""))
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else DOCS / "bao-cao-kiem-thu.xlsx"
# Báo cáo XML cũ của test đã xoá vẫn nằm trong target/; chỉ nhận báo cáo mới hơn mốc này.
SINCE = float(os.environ.get("REPORT_SINCE_EPOCH", "0"))

FONT = "Arial"
F_BASE = Font(name=FONT, size=10)
F_BOLD = Font(name=FONT, size=10, bold=True)
F_HEAD = Font(name=FONT, size=10, bold=True, color="FFFFFF")
F_TITLE = Font(name=FONT, size=16, bold=True, color="1F3864")
F_SUB = Font(name=FONT, size=10, italic=True, color="595959")
F_INPUT = Font(name=FONT, size=10, color="0000FF")
FILL_HEAD = PatternFill("solid", fgColor="1F3864")
FILL_SECTION = PatternFill("solid", fgColor="D9E2F3")
FILL_TOTAL = PatternFill("solid", fgColor="F2F2F2")
FILL_OK = PatternFill("solid", fgColor="E2EFDA")
FILL_BAD = PatternFill("solid", fgColor="F8CBAD")
FILL_WARN = PatternFill("solid", fgColor="FFF2CC")
THIN = Side(style="thin", color="BFBFBF")
BORDER = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
WRAP = Alignment(wrap_text=True, vertical="top")
CENTER = Alignment(horizontal="center", vertical="top", wrap_text=True)

# ---------------------------------------------------------------------------------------------
# 1. Đọc tên hiển thị (@DisplayName) từ mã Java
# ---------------------------------------------------------------------------------------------


def blank_literals(src: str) -> str:
    """Thay nội dung chuỗi / chú thích bằng khoảng trắng (giữ nguyên độ dài và xuống dòng)."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i + 3, j - 3):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            for k in range(i + 1, min(j, n)):
                out[k] = " "
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            for k in range(i + 1, min(j, n)):
                out[k] = " "
            i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def display_after(src: str, pos: int) -> str:
    """Đọc các literal chuỗi trong @DisplayName( ... ) bắt đầu từ vị trí pos (ngay sau dấu mở ngoặc)."""
    i, n, depth, parts = pos, len(src), 1, []
    while i < n and depth > 0:
        c = src[i]
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            parts.append(" ".join(l.strip() for l in src[i + 3 : j].strip().splitlines()))
            i = j + 3
        elif c == '"':
            j = i + 1
            buf = []
            while j < n and src[j] != '"':
                if src[j] == "\\" and j + 1 < n:
                    buf.append({"n": " ", "t": " ", '"': '"', "\\": "\\"}.get(src[j + 1], src[j + 1]))
                    j += 2
                else:
                    buf.append(src[j])
                    j += 1
            parts.append("".join(buf))
            i = j + 1
        else:
            depth += c == "("
            depth -= c == ")"
            i += 1
    return "".join(parts).strip()


def parse_java_names(path: Path) -> dict:
    """(đường dẫn lớp lồng như 'Outer$Inner', tên phương thức) -> (tên hiển thị, nhóm hiển thị)."""
    src = path.read_text(encoding="utf-8")
    blank = blank_literals(src)
    names = {}
    stack = []  # (tên lớp, độ sâu ngoặc, tên hiển thị của lớp)
    depth = 0
    pending = None
    top = path.stem
    for m in re.finditer(r"@DisplayName\s*\(|\bclass\s+(\w+)|\bvoid\s+(\w+)\s*\(|[{}]", blank):
        tok = m.group(0)
        if tok.startswith("@DisplayName"):
            pending = display_after(src, m.end())
        elif tok == "{":
            depth += 1
        elif tok == "}":
            depth -= 1
            while stack and depth < stack[-1][1]:
                stack.pop()
        elif m.group(1):
            stack.append((m.group(1), depth + 1, pending))
            pending = None
        elif m.group(2):
            chain = "$".join(c[0] for c in stack) if stack else top
            group = next((c[2] for c in reversed(stack) if c[2]), "")
            names[(chain, m.group(2))] = (pending, group)
            pending = None
    return names


def java_name_index() -> dict:
    index = {}
    for f in glob.glob(str(BACKEND / "**" / "src" / "test" / "java" / "**" / "*.java"), recursive=True):
        p = Path(f)
        pkg = re.search(r"^package\s+([\w.]+);", p.read_text(encoding="utf-8"), re.M)
        if not pkg:
            continue
        for (chain, method), val in parse_java_names(p).items():
            index[(pkg.group(1) + "." + chain, method)] = val
    return index


# ---------------------------------------------------------------------------------------------
# 2. Kết quả backend
# ---------------------------------------------------------------------------------------------


def level_of(fqcn: str, failsafe: bool) -> str:
    simple = fqcn.split(".")[-1]
    if failsafe:
        return "Integration"
    if "ArchitectureTest" in simple:
        return "ArchUnit"
    if re.search(r"Controller|Web(Test|Slice)|Rest|ControllersTest|AdminRolesTest|GatewaySecurity|GatewayRoute", simple):
        return "API slice"
    return "Unit"


def backend_results(names: dict):
    rows = []
    for kind, pattern in (("unit", "surefire-reports"), ("it", "failsafe-reports")):
        for xml in glob.glob(str(BACKEND / "*" / "*" / "target" / pattern / "TEST-*.xml")):
            if os.path.getmtime(xml) < SINCE:
                continue
            module = Path(xml).parts[-4]
            for case in ET.parse(xml).getroot().iter("testcase"):
                fqcn = case.get("classname", "")
                method = re.sub(r"[\(\[].*$", "", case.get("name", ""))
                disp, group = names.get((fqcn, method), (None, ""))
                if case.find("failure") is not None or case.find("error") is not None:
                    result = "Đỏ"
                elif case.find("skipped") is not None:
                    result = "Bỏ qua"
                else:
                    result = "Đạt"
                rows.append(
                    {
                        "he": "Backend",
                        "module": module,
                        "level": level_of(fqcn, kind == "it"),
                        "cls": fqcn.split(".")[-1].replace("$", " › "),
                        "group": group or "",
                        "name": disp or method.replace("_", " "),
                        "method": case.get("name", ""),
                        "result": result,
                        "time": float(case.get("time", "0") or 0),
                    }
                )
    return rows


def frontend_results():
    rows = []
    if not FRONTEND_JSON_DIR or not FRONTEND_JSON_DIR.exists():
        return rows
    for pkg in ("ts-sdk", "ui", "auth", "web-customer", "web-admin"):
        f = FRONTEND_JSON_DIR / f"fe-{pkg}.json"
        if not f.exists():
            continue
        data = json.loads(f.read_text(encoding="utf-8"))
        for suite in data.get("testResults", []):
            file = Path(suite["name"]).name
            for t in suite.get("assertionResults", []):
                status = t.get("status")
                rows.append(
                    {
                        "he": "Frontend",
                        "module": pkg,
                        "level": "Unit",
                        "cls": file,
                        "group": " › ".join(t.get("ancestorTitles", [])),
                        "name": t.get("title", ""),
                        "method": t.get("fullName", ""),
                        "result": "Đạt" if status == "passed" else ("Bỏ qua" if status in ("skipped", "pending") else "Đỏ"),
                        "time": (t.get("duration") or 0) / 1000.0,
                    }
                )
    e2e = FRONTEND_JSON_DIR / "fe-e2e.json"
    if e2e.exists():
        data = json.loads(e2e.read_text(encoding="utf-8"))

        def walk(suite, trail):
            for spec in suite.get("specs", []):
                tests = spec.get("tests", [])
                ok = all(r.get("status") == "passed" for t in tests for r in t.get("results", []))
                rows.append(
                    {
                        "he": "Frontend",
                        "module": "web-customer (E2E)",
                        "level": "E2E",
                        "cls": Path(suite.get("file") or spec.get("file", "")).name,
                        "group": " › ".join(trail),
                        "name": spec.get("title", ""),
                        "method": spec.get("title", ""),
                        "result": "Đạt" if ok else "Đỏ",
                        "time": sum(r.get("duration", 0) for t in tests for r in t.get("results", [])) / 1000.0,
                    }
                )
            for child in suite.get("suites", []):
                walk(child, trail + ([child.get("title")] if child.get("title") and child.get("title") != child.get("file") else []))

        for s in data.get("suites", []):
            walk(s, [])
    return rows


# ---------------------------------------------------------------------------------------------
# 3. Coverage
# ---------------------------------------------------------------------------------------------


def backend_coverage():
    rows = []
    for module_dir in sorted(p for p in BACKEND.glob("*/*") if (p / "pom.xml").exists()):
        src = module_dir / "target/site/jacoco-merged/jacoco.csv"
        if not src.exists():
            continue
        tot = {k: [0, 0] for k in ("INSTRUCTION", "BRANCH", "LINE", "METHOD")}
        with src.open(encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                for k in tot:
                    tot[k][0] += int(r[f"{k}_MISSED"])
                    tot[k][1] += int(r[f"{k}_COVERED"])
        rows.append((module_dir.name, tot))
    return rows


FRONTEND_COVERAGE = None  # đọc từ coverage-summary.json nếu có


def frontend_coverage():
    rows = []
    for pkg, path in (
        ("ts-sdk", "packages/ts-sdk"),
        ("ui", "packages/ui"),
        ("auth", "packages/auth"),
        ("web-customer", "apps/web-customer"),
        ("web-admin", "apps/web-admin"),
    ):
        f = REPO / "frontend" / path / "coverage" / "coverage-summary.json"
        if not f.exists():
            continue
        t = json.loads(f.read_text(encoding="utf-8"))["total"]
        rows.append((pkg, t["statements"], t["branches"], t["functions"], t["lines"]))
    return rows


# ---------------------------------------------------------------------------------------------
# 4. Ma trận API
# ---------------------------------------------------------------------------------------------


def api_matrix():
    rows = []
    controllers = sorted(glob.glob(str(BACKEND / "services" / "*" / "src" / "main" / "java" / "**" / "interfaces" / "rest" / "*Controller.java"), recursive=True))
    test_files = {}
    for t in glob.glob(str(BACKEND / "services" / "*" / "src" / "test" / "**" / "*.java"), recursive=True):
        if re.search(r"(Test|IT)\.java$", t):
            svc = Path(t).relative_to(BACKEND / "services").parts[0]
            test_files.setdefault(svc, []).append((Path(t).stem, Path(t).read_text(encoding="utf-8")))
    ident = {
        "AccessController": ["MeAndAccessControllerTest", "AccessControlIT"],
        "MeController": ["MeAndAccessControllerTest", "AuthenticationFlowIT"],
        "MyInvitationController": ["MeAndAccessControllerTest", "StaffInvitationIT"],
        "OrganizationController": ["OrganizationControllerTest", "MemberManagementIT", "TenantIsolationIT"],
        "PlatformOrganizationController": ["PlatformControllersTest"],
        "PlatformUserController": ["PlatformControllersTest", "SessionRevocationIT"],
        "StaffController": ["StaffAndInternalControllersTest", "StaffInvitationIT"],
        "InternalAuditController": ["StaffAndInternalControllersTest", "InternalAuditIT"],
        "InternalMembershipController": ["StaffAndInternalControllersTest", "InternalOhsIT"],
    }
    for ctl in controllers:
        p = Path(ctl)
        svc = p.relative_to(BACKEND / "services").parts[0]
        name = p.stem
        src = p.read_text(encoding="utf-8")
        blank = blank_literals(src)
        cls_m = re.search(r"@RequestMapping\s*\(\s*(?:path\s*=\s*|value\s*=\s*)?\"([^\"]*)\"", src)
        base = cls_m.group(1) if cls_m else ""
        tests = sorted({s for s, body in test_files.get(svc, []) if re.search(r"\b" + name + r"\b", body)}) or ident.get(name, [])
        for m in re.finditer(r"@(Get|Post|Put|Patch|Delete)Mapping(?:\s*\(([^)]*)\))?", blank):
            args = src[m.start(2) : m.end(2)] if m.group(2) is not None else ""
            path_m = re.search(r"\"([^\"]*)\"", args)
            sub = path_m.group(1) if path_m else ""
            tail = blank[m.end() : m.end() + 400]
            perm = re.search(r"@RequiresPermission\s*\(\s*Permission\.(\w+)", src[m.end() : m.end() + 400])
            sig = re.search(r"\bpublic\b[^;{]*?\b(\w+)\s*\(", tail)
            rows.append(
                {
                    "svc": svc,
                    "verb": m.group(1).upper(),
                    "path": (base + sub) or "/",
                    "ctl": name,
                    "perm": perm.group(1) if perm else ("internal" if "/internal" in base else ("public" if "PublicEndpoint" in src[max(0, m.start() - 200) : m.start()] else "đăng nhập")),
                    "tests": ", ".join(tests),
                }
            )
    return rows


# ---------------------------------------------------------------------------------------------
# 5. Đọc tài liệu markdown (ca chức năng, lỗi đã sửa, khoảng trống)
# ---------------------------------------------------------------------------------------------


def md_tables(path: Path):
    """Trả về [(tiêu đề mục gần nhất, [dòng ô])] cho mọi bảng markdown trong tệp."""
    heading, out, cur = "", [], None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            heading = line.lstrip("# ").strip()
        if line.startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if cur is None:
                cur = (heading, [])
                out.append(cur)
            if not all(re.fullmatch(r":?-{3,}:?", c) for c in cells):
                cur[1].append(cells)
        else:
            cur = None
    return out


def clean(text: str) -> str:
    text = re.sub(r"<br>", "\n", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    return text.replace("`", "").replace("**", "")


def functional_cases():
    cases = []
    for heading, rows in md_tables(DOCS / "test-cases.md"):
        header, body = rows[0], rows[1:]
        if header[0] != "ID":
            continue
        for r in body:
            if len(r) == 6:
                cid, title, cond, expect, prio, tests = r
                pre, _, steps = cond.partition("→")
            elif len(r) == 5:
                cid, title, expect, prio, tests = r
                pre, steps = "", ""
            else:
                continue
            cases.append(
                {
                    "id": cid,
                    "group": re.sub(r"^[A-G]\.\s*", "", heading),
                    "title": clean(title),
                    "pre": clean(pre).strip(),
                    "steps": clean(steps).strip(),
                    "expect": clean(expect),
                    "prio": prio.strip(),
                    "tests": clean(tests),
                }
            )
    return cases


# ---------------------------------------------------------------------------------------------
# 6. Dựng workbook
# ---------------------------------------------------------------------------------------------


def style_header(ws, row, ncols):
    for c in range(1, ncols + 1):
        cell = ws.cell(row=row, column=c)
        cell.font, cell.fill, cell.border = F_HEAD, FILL_HEAD, BORDER
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
    ws.row_dimensions[row].height = 30


def put_table(ws, top, headers, rows, widths, center_cols=()):
    for c, h in enumerate(headers, 1):
        ws.cell(row=top, column=c, value=h)
    style_header(ws, top, len(headers))
    for r, row in enumerate(rows, top + 1):
        for c, v in enumerate(row, 1):
            cell = ws.cell(row=r, column=c, value=v)
            cell.font, cell.border = F_BASE, BORDER
            cell.alignment = CENTER if c in center_cols else WRAP
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    return top + len(rows)


def title(ws, text, sub=None):
    ws["A1"] = text
    ws["A1"].font = F_TITLE
    if sub:
        ws["A2"] = sub
        ws["A2"].font = F_SUB


def result_formatting(ws, rng):
    ws.conditional_formatting.add(rng, CellIsRule(operator="equal", formula=['"Đạt"'], fill=FILL_OK))
    ws.conditional_formatting.add(rng, CellIsRule(operator="equal", formula=['"Đỏ"'], fill=FILL_BAD))
    ws.conditional_formatting.add(rng, CellIsRule(operator="equal", formula=['"Bỏ qua"'], fill=FILL_WARN))



def write_module_files(all_rows, out_dir: Path):
    """Mỗi module một tệp .xlsx chỉ chứa danh mục test của nó (tầng, lớp, tên hành vi, kết quả)."""
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.xlsx"):
        old.unlink()  # module đã đổi tên / bị xoá không để lại tệp mồ côi
    groups = {}
    for r in all_rows:
        groups.setdefault((r["he"], r["module"]), []).append(r)
    written = []
    for (he, module), rows in sorted(groups.items(), key=lambda kv: (kv[0][0] != "Backend", kv[0][1])):
        wb = Workbook()
        ws = wb.active
        ws.title = "Danh mục test"
        title(ws, f"{module} — danh mục test tự động", f"Hệ: {he}. Nguồn: báo cáo của lần chạy gần nhất; tên = @DisplayName trong mã. Tổng ở hàng 3 là công thức.")
        heads = ["STT", "Mức", "Lớp test", "Nhóm", "Tên test (hành vi được kiểm)", "Phương thức", "Kết quả", "Thời gian (s)"]
        data = [[i, r["level"], r["cls"], r["group"], r["name"], r["method"], r["result"], round(r["time"], 3)] for i, r in enumerate(rows, 1)]
        end = put_table(ws, 5, heads, data, [6, 12, 36, 34, 74, 36, 10, 11], center_cols=(1, 2, 7, 8))
        first = 6
        ws["A3"], ws["B3"] = "Tổng", f"=COUNTA(A{first}:A{end})"
        ws["C3"], ws["D3"] = "Đạt", f'=COUNTIF(G{first}:G{end},"Đạt")'
        ws["E3"] = f'="Đỏ: "&COUNTIF(G{first}:G{end},"Đỏ")&"   ·   Bỏ qua: "&COUNTIF(G{first}:G{end},"Bỏ qua")'
        for c in ("A3", "B3", "C3", "D3", "E3"):
            ws[c].font = F_BOLD
        ws.freeze_panes = "A6"
        ws.auto_filter.ref = f"A5:H{end}"
        result_formatting(ws, f"G{first}:G{end}")
        wb.calculation.fullCalcOnLoad = True
        name = re.sub(r"[^a-z0-9]+", "-", f"{he}-{module}".lower()).strip("-") + ".xlsx"
        wb.save(out_dir / name)
        written.append((name, len(rows)))
    return written


def build():
    names = java_name_index()
    be = backend_results(names)
    fe = frontend_results()
    all_rows = be + fe
    if not all_rows:
        sys.exit("Không có kết quả test nào. Chạy ./mvnw verify -Pcoverage trước.")
    cases = functional_cases()
    api = api_matrix()
    cov = backend_coverage()
    fcov = frontend_coverage()
    api.sort(key=lambda a: (a["svc"], a["path"], a["verb"]))

    wb = Workbook()

    # ---- Sheet: Danh mục test tự động (tạo trước để các sheet khác tham chiếu) ------------------
    ws_all = wb.active
    ws_all.title = "Danh mục test tự động"
    title(ws_all, "Danh mục test tự động — mọi test đã chạy", "Nguồn: báo cáo Surefire/Failsafe, Vitest, Playwright của lần chạy gần nhất. Tên = @DisplayName trong mã.")
    heads = ["STT", "Hệ", "Module", "Mức", "Lớp test", "Nhóm", "Tên test (hành vi được kiểm)", "Phương thức", "Kết quả", "Thời gian (s)"]
    data = [
        [i, r["he"], r["module"], r["level"], r["cls"], r["group"], r["name"], r["method"], r["result"], round(r["time"], 3)]
        for i, r in enumerate(all_rows, 1)
    ]
    end = put_table(ws_all, 4, heads, data, [6, 10, 22, 12, 34, 34, 70, 34, 10, 11], center_cols=(1, 2, 4, 9, 10))
    ws_all.freeze_panes = "A5"
    ws_all.auto_filter.ref = f"A4:{get_column_letter(len(heads))}{end}"
    result_formatting(ws_all, f"I5:I{end}")
    ALL = "'Danh mục test tự động'"
    rng = lambda col: f"{ALL}!${col}$5:${col}${end}"

    # ---- Sheet: Tổng quan -----------------------------------------------------------------------
    ws = wb.create_sheet("Tổng quan", 0)
    title(ws, "NexaTicket — Báo cáo kiểm thử", f"Sinh tự động ngày {datetime.now():%d/%m/%Y %H:%M} từ kết quả chạy thật. Các tổng bên dưới là công thức.")
    ws.column_dimensions["A"].width = 38
    for col in "BCDEFG":
        ws.column_dimensions[col].width = 16
    r = 4
    ws.cell(row=r, column=1, value="1. Kết quả thực thi theo tầng").font = F_BOLD
    r += 1
    heads = ["Hệ / Tầng", "Tổng", "Đạt", "Đỏ", "Bỏ qua", "Tỉ lệ đạt"]
    for c, h in enumerate(heads, 1):
        ws.cell(row=r, column=c, value=h)
    style_header(ws, r, len(heads))
    layers = [
        ("Backend", "Unit"),
        ("Backend", "API slice"),
        ("Backend", "ArchUnit"),
        ("Backend", "Integration"),
        ("Frontend", "Unit"),
        ("Frontend", "E2E"),
    ]
    first = r + 1
    for he, lvl in layers:
        r += 1
        ws.cell(row=r, column=1, value=f"{he} — {lvl}")
        ws.cell(row=r, column=2, value=f'=COUNTIFS({rng("B")},"{he}",{rng("D")},"{lvl}")')
        ws.cell(row=r, column=3, value=f'=COUNTIFS({rng("B")},"{he}",{rng("D")},"{lvl}",{rng("I")},"Đạt")')
        ws.cell(row=r, column=4, value=f'=COUNTIFS({rng("B")},"{he}",{rng("D")},"{lvl}",{rng("I")},"Đỏ")')
        ws.cell(row=r, column=5, value=f'=COUNTIFS({rng("B")},"{he}",{rng("D")},"{lvl}",{rng("I")},"Bỏ qua")')
        ws.cell(row=r, column=6, value=f"=IF(B{r}=0,\"-\",C{r}/B{r})")
    last = r
    r += 1
    ws.cell(row=r, column=1, value="TỔNG")
    for c in "BCDE":
        ws[f"{c}{r}"] = f"=SUM({c}{first}:{c}{last})"
    ws[f"F{r}"] = f'=IF(B{r}=0,"-",C{r}/B{r})'
    total_row = r
    for rr in range(first, r + 1):
        for c in range(1, 7):
            cell = ws.cell(row=rr, column=c)
            cell.font = F_BOLD if rr == total_row else F_BASE
            cell.border = BORDER
            if rr == total_row:
                cell.fill = FILL_TOTAL
            if c == 6:
                cell.number_format = "0.0%"
            if c > 1:
                cell.alignment = Alignment(horizontal="center")
    r += 2
    ws.cell(row=r, column=1, value="2. Coverage backend (JaCoCo, unit + integration gộp)").font = F_BOLD
    r += 1
    heads = ["Chỉ số", "Hiện tại", "Mục tiêu", "Đạt mục tiêu?"]
    for c, h in enumerate(heads, 1):
        ws.cell(row=r, column=c, value=h)
    style_header(ws, r, len(heads))
    cov_total_refs = {}
    for label, key in (("Dòng (line)", "line"), ("Nhánh (branch)", "branch"), ("Câu lệnh (instruction)", "instr"), ("Phương thức (method)", "method")):
        r += 1
        ws.cell(row=r, column=1, value=label)
        ws.cell(row=r, column=2, value=f"='Coverage backend'!{ {'instr': 'D', 'branch': 'G', 'line': 'J', 'method': 'M'}[key] }{len(cov) + 5}")
        ws.cell(row=r, column=2).number_format = "0.0%"
        target = ws.cell(row=r, column=3, value=0.8)
        target.number_format = "0%"
        target.font = F_INPUT
        ws.cell(row=r, column=4, value=f'=IF(B{r}>=C{r},"Đạt","Chưa đạt")')
        for c in range(1, 5):
            cell = ws.cell(row=r, column=c)
            if c != 3:
                cell.font = F_BASE
            cell.border = BORDER
            if c > 1:
                cell.alignment = Alignment(horizontal="center")
    ws.cell(row=r + 1, column=1, value="Mục tiêu 80% là ngưỡng của đề bài (ô xanh có thể sửa); các tỉ lệ là công thức từ sheet 'Coverage backend'.").font = F_SUB
    r += 3
    ws.cell(row=r, column=1, value="3. Tài sản kiểm thử").font = F_BOLD
    r += 1
    for label, formula in (
        ("Ca kiểm thử chức năng (sheet 'Test case chức năng')", f"=COUNTA('Test case chức năng'!A5:A{4 + len(cases)})"),
        ("Endpoint API đã ánh xạ tới test (sheet 'Ma trận API')", f"=COUNTA('Ma trận API'!A5:A{4 + len(api)})"),
        ("Endpoint API CHƯA có test nào", f"=COUNTIF('Ma trận API'!F5:F{4 + len(api)},\"\")"),
        ("Test tự động (sheet 'Danh mục test tự động')", f"=COUNTA({rng('A')})"),
    ):
        ws.cell(row=r, column=1, value=label).font = F_BASE
        c = ws.cell(row=r, column=2, value=formula)
        c.font, c.alignment = F_BOLD, Alignment(horizontal="center")
        for cc in (1, 2):
            ws.cell(row=r, column=cc).border = BORDER
        r += 1
    r += 1
    ws.cell(row=r, column=1, value="4. Phạm vi và giới hạn (trung thực)").font = F_BOLD
    notes = [
        "k6: hold-contention đạt 4/4 lần (đúng 1 người thắng trong 4 người tranh một nhóm ghế); discovery chạy ổn nhưng vượt ba ngưỡng độ trễ và làm inventory hết bộ nhớ ở heap 384 MB (docs/03-seat-checkout/load-test-report.md). checkout.js chạy với payOS giả (load-tests/fake-payos) đạt 68 vòng, 279/279 checks; payOS thật chưa kiểm.",
        "App mobile (4 app React Native) nằm ngoài phạm vi đợt này.",
        "E2E dùng nhà phát hành OIDC giả; đăng nhập Keycloak thật, trang payOS (không có sandbox), realtime chưa kiểm được.",
        "Coverage frontend thấp ở app web-customer / web-admin vì Vitest tính mọi tệp, kể cả trang chưa test (xem sheet 'Coverage frontend').",
        "Chi tiết lỗi đã sửa và khoảng trống: sheet 'Lỗi đã sửa', 'Khoảng trống'.",
    ]
    for n in notes:
        r += 1
        ws.cell(row=r, column=1, value="• " + n).font = F_BASE
    ws.sheet_view.showGridLines = False

    # ---- Sheet: Kết quả theo module -------------------------------------------------------------
    ws = wb.create_sheet("Kết quả theo module", 1)
    title(ws, "Kết quả theo module", "Đếm bằng công thức COUNTIFS trên sheet 'Danh mục test tự động'.")
    heads = ["Hệ", "Module", "Tổng", "Đạt", "Đỏ", "Bỏ qua", "Unit + slice + ArchUnit", "Integration / E2E"]
    for c, h in enumerate(heads, 1):
        ws.cell(row=4, column=c, value=h)
    style_header(ws, 4, len(heads))
    mods = []
    for r_ in all_rows:
        key = (r_["he"], r_["module"])
        if key not in mods:
            mods.append(key)
    mods.sort(key=lambda k: (k[0] != "Backend", k[1]))
    row = 4
    for he, mod in mods:
        row += 1
        ws.cell(row=row, column=1, value=he)
        ws.cell(row=row, column=2, value=mod)
        base = f'{rng("B")},$A{row},{rng("C")},$B{row}'
        ws.cell(row=row, column=3, value=f"=COUNTIFS({base})")
        ws.cell(row=row, column=4, value=f'=COUNTIFS({base},{rng("I")},"Đạt")')
        ws.cell(row=row, column=5, value=f'=COUNTIFS({base},{rng("I")},"Đỏ")')
        ws.cell(row=row, column=6, value=f'=COUNTIFS({base},{rng("I")},"Bỏ qua")')
        ws.cell(row=row, column=7, value=f'=COUNTIFS({base},{rng("D")},"Unit")+COUNTIFS({base},{rng("D")},"API slice")+COUNTIFS({base},{rng("D")},"ArchUnit")')
        ws.cell(row=row, column=8, value=f'=COUNTIFS({base},{rng("D")},"Integration")+COUNTIFS({base},{rng("D")},"E2E")')
    mod_last = row
    row += 1
    ws.cell(row=row, column=1, value="TỔNG")
    for c in range(3, 9):
        L = get_column_letter(c)
        ws[f"{L}{row}"] = f"=SUM({L}5:{L}{mod_last})"
    for rr in range(5, row + 1):
        for c in range(1, 9):
            cell = ws.cell(row=rr, column=c)
            cell.font = F_BOLD if rr == row else F_BASE
            cell.border = BORDER
            if rr == row:
                cell.fill = FILL_TOTAL
            if c > 2:
                cell.alignment = Alignment(horizontal="center")
    for i, w in enumerate([11, 26, 10, 10, 10, 10, 24, 20], 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    ws.freeze_panes = "A5"
    ws.conditional_formatting.add(f"E5:E{row}", CellIsRule(operator="greaterThan", formula=["0"], fill=FILL_BAD))

    # ---- Sheet: Coverage backend ----------------------------------------------------------------
    ws = wb.create_sheet("Coverage backend", 2)
    title(ws, "Coverage backend — JaCoCo (unit + integration gộp)", "Số dòng/nhánh bỏ sót và đã phủ lấy từ jacoco.csv; tỉ lệ là công thức. Loại trừ: lớp *Application và infrastructure/seed.")
    heads = ["Module", "Instr. bỏ sót", "Instr. đã phủ", "Instr. %", "Nhánh bỏ sót", "Nhánh đã phủ", "Nhánh %", "Dòng bỏ sót", "Dòng đã phủ", "Dòng %", "Method bỏ sót", "Method đã phủ", "Method %"]
    for c, h in enumerate(heads, 1):
        ws.cell(row=4, column=c, value=h)
    style_header(ws, 4, len(heads))
    row = 4
    for module, t in cov:
        row += 1
        ws.cell(row=row, column=1, value=module)
        for idx, key in enumerate(("INSTRUCTION", "BRANCH", "LINE", "METHOD")):
            base = 2 + idx * 3
            ws.cell(row=row, column=base, value=t[key][0])
            ws.cell(row=row, column=base + 1, value=t[key][1])
            a, b = get_column_letter(base), get_column_letter(base + 1)
            ws.cell(row=row, column=base + 2, value=f'=IF({a}{row}+{b}{row}=0,"-",{b}{row}/({a}{row}+{b}{row}))')
    cov_last = row
    row += 1
    ws.cell(row=row, column=1, value="TỔNG")
    for idx in range(4):
        base = 2 + idx * 3
        for off in (0, 1):
            L = get_column_letter(base + off)
            ws[f"{L}{row}"] = f"=SUM({L}5:{L}{cov_last})"
        a, b, pct = (get_column_letter(base + k) for k in range(3))
        ws[f"{pct}{row}"] = f'=IF({a}{row}+{b}{row}=0,"-",{b}{row}/({a}{row}+{b}{row}))'
    assert row == len(cov) + 5
    for rr in range(5, row + 1):
        for c in range(1, 14):
            cell = ws.cell(row=rr, column=c)
            cell.font = F_BOLD if rr == row else F_BASE
            cell.border = BORDER
            if rr == row:
                cell.fill = FILL_TOTAL
            if c > 1:
                cell.alignment = Alignment(horizontal="center")
            if c in (4, 7, 10, 13):
                cell.number_format = "0.0%"
            elif c > 1:
                cell.number_format = "#,##0"
    for i in range(1, 14):
        ws.column_dimensions[get_column_letter(i)].width = 26 if i == 1 else 13
    for col in ("D", "G", "J", "M"):
        ws.conditional_formatting.add(
            f"{col}5:{col}{row - 1}", CellIsRule(operator="lessThan", formula=["0.8"], fill=FILL_WARN)
        )
    ws.cell(row=row + 2, column=1, value="Ô màu vàng = dưới mục tiêu 80%. Module thấp nhất: ai-chatbox-service (adapter LLM cần mạng/model thật), catalog-service (nhánh).").font = F_SUB
    ws.freeze_panes = "B5"

    # ---- Sheet: Coverage frontend ---------------------------------------------------------------
    ws = wb.create_sheet("Coverage frontend", 3)
    title(ws, "Coverage frontend — @vitest/coverage-v8", "Số liệu từ coverage-summary.json của từng gói (đã là phần trăm, nhập nguyên văn).")
    heads = ["Gói / app", "Statements %", "Branches %", "Functions %", "Lines %", "Đạt 80% lines?"]
    for c, h in enumerate(heads, 1):
        ws.cell(row=4, column=c, value=h)
    style_header(ws, 4, len(heads))
    row = 4
    for pkg, s, b, f_, l in fcov:
        row += 1
        ws.cell(row=row, column=1, value=pkg)
        for c, m in enumerate((s, b, f_, l), 2):
            cell = ws.cell(row=row, column=c, value=m["pct"] / 100.0)
            cell.number_format = "0.0%"
        ws.cell(row=row, column=6, value=f'=IF(E{row}>=0.8,"Đạt","Chưa đạt")')
        for c in range(1, 7):
            cell = ws.cell(row=row, column=c)
            cell.font, cell.border = F_BASE, BORDER
            if c > 1:
                cell.alignment = Alignment(horizontal="center")
    ws.cell(row=row + 2, column=1, value="Ghi chú: coverage của web-customer / web-admin thấp vì Vitest đếm mọi tệp dưới src/ (kể cả trang chưa test). Giao diện được kiểm gián tiếp bằng 33 test E2E, không tính vào số này.").font = F_SUB
    for i, w in enumerate([22, 15, 15, 15, 15, 18], 1):
        ws.column_dimensions[get_column_letter(i)].width = w

    # ---- Sheet: Test case chức năng -------------------------------------------------------------
    ws = wb.create_sheet("Test case chức năng", 4)
    title(ws, "Test case chức năng", "Điều kiện → bước → kết quả mong đợi, độ ưu tiên và test tự động thực hiện. P0 = bất biến/mất tiền/lộ dữ liệu · P1 = luồng chính · P2 = biên và lỗi nhập liệu. Tầng: U unit · S API slice · I integration · E E2E · K k6.")
    heads = ["ID", "Nhóm", "Tên ca kiểm thử", "Điều kiện tiên quyết", "Các bước", "Kết quả mong đợi", "Ưu tiên", "Test tự động thực hiện", "Kết quả lần chạy cuối", "Ghi chú"]
    rows = []
    for c in cases:
        note = ""
        if re.search(r"\bK\b", c["tests"]) or "k6" in c["tests"]:
            note = "Phần k6 chưa chạy (không có k6 / không dựng đủ hệ thống); phần còn lại đã chạy."
        rows.append([c["id"], c["group"], c["title"], c["pre"] or "—", c["steps"] or "—", c["expect"], c["prio"], c["tests"], "Đạt", note])
    end = put_table(ws, 4, heads, rows, [10, 22, 40, 40, 44, 52, 9, 52, 14, 32], center_cols=(1, 7, 9))
    ws.freeze_panes = "D5"
    ws.auto_filter.ref = f"A4:{get_column_letter(len(heads))}{end}"
    result_formatting(ws, f"I5:I{end}")
    ws.cell(row=end + 2, column=1, value="Cột 'Kết quả lần chạy cuối' = Đạt vì toàn bộ test tự động trong sheet 'Danh mục test tự động' đạt ở lần chạy cuối (xem sheet 'Tổng quan'); sửa ô này nếu chạy lại và có test đỏ.").font = F_SUB

    # ---- Sheet: Ma trận API ---------------------------------------------------------------------
    ws = wb.create_sheet("Ma trận API", 5)
    title(ws, "Ma trận API — mọi endpoint ↔ test", "Quét từ mã nguồn mọi *Controller.java (interfaces/rest). Cột 'Test' trống nghĩa là chưa có test nào nhắc tới controller đó.")
    heads = ["Dịch vụ", "Method", "Đường dẫn", "Controller", "Yêu cầu quyền", "Test (slice + integration)"]
    api.sort(key=lambda a: (a["svc"], a["path"], a["verb"]))
    rows = [[a["svc"], a["verb"], a["path"], a["ctl"], a["perm"], a["tests"]] for a in api]
    end = put_table(ws, 4, heads, rows, [22, 9, 62, 34, 26, 70], center_cols=(2,))
    ws.freeze_panes = "A5"
    ws.auto_filter.ref = f"A4:F{end}"

    # ---- Sheet: Lỗi đã sửa ----------------------------------------------------------------------
    gaps = DOCS / "known-testing-gaps.md"
    fixed_rows = []
    for heading, rows_ in md_tables(gaps):
        if "đã sửa" in heading.lower() and rows_ and rows_[0][0].startswith("Lỗi"):
            for rr in rows_[1:]:
                if len(rr) >= 2:
                    fixed_rows.append([len(fixed_rows) + 1, clean(rr[0]), clean(rr[1])])
    ws = wb.create_sheet("Lỗi đã sửa", 6)
    title(ws, "Lỗi ứng dụng do test phát hiện và đã sửa", "Mỗi lỗi do một test đỏ chỉ ra; sau khi sửa, test giữ lại làm hồi quy.")
    put_table(ws, 4, ["STT", "Lỗi", "Cách sửa"], fixed_rows, [6, 95, 95], center_cols=(1,))
    ws.freeze_panes = "A5"

    # ---- Sheet: Khoảng trống --------------------------------------------------------------------
    gap_rows = []
    for heading, rows_ in md_tables(gaps):
        if rows_ and rows_[0][0] == "Khoảng trống":
            for rr in rows_[1:]:
                if len(rr) >= 3:
                    gap_rows.append([len(gap_rows) + 1, clean(rr[0]), clean(rr[1]), clean(rr[2])])
    ws = wb.create_sheet("Khoảng trống", 7)
    title(ws, "Khoảng trống kiểm thử đã biết", "Những gì chưa kiểm được, vì sao, và test cần viết tiếp.")
    put_table(ws, 4, ["STT", "Khoảng trống", "Lý do", "Test cần viết tiếp"], gap_rows, [6, 60, 70, 60], center_cols=(1,))
    ws.freeze_panes = "A5"

    # ---- Sheet: Hướng dẫn chạy ------------------------------------------------------------------
    ws = wb.create_sheet("Hướng dẫn chạy", 8)
    title(ws, "Lệnh chạy từng bộ test", "Chạy từ thư mục được ghi trong cột 'Thư mục'.")
    cmds = [
        ("Backend", "backend/", "./mvnw -B verify -DskipITs", "Unit + API slice + ArchUnit (không cần Docker)"),
        ("Backend", "backend/", "./mvnw -B -pl services/ordering-service verify -DskipITs", "Một service"),
        ("Backend", "backend/", "./mvnw -B -pl services/ordering-service test -Dtest=PlaceOrderHandlerTest", "Một lớp test"),
        ("Backend", "backend/", "./mvnw -B verify -Dsurefire.skip=true", "Chỉ integration (cần Docker Desktop đang chạy)"),
        ("Backend", "backend/", "./mvnw -B verify", "Mọi thứ an toàn cho CI"),
        ("Backend", "backend/", "./mvnw -B verify -Pcoverage", "Thêm JaCoCo; báo cáo gộp ở target/site/jacoco-merged/ mỗi module"),
        ("Backend", "repo gốc", "python scripts/coverage-summary.py --classes", "Bảng coverage toàn repo + 25 lớp thiếu nhiều dòng nhất"),
        ("Backend", "backend/", "./mvnw -B spotless:check", "Lint (sửa: spotless:apply)"),
        ("Frontend", "frontend/", "corepack pnpm test", "Unit mọi gói/app"),
        ("Frontend", "frontend/", "corepack pnpm test:coverage", "Coverage"),
        ("Frontend", "frontend/", "corepack pnpm --filter @nexaticket/web-customer test:e2e", "E2E Playwright (một next dev, tự dừng khi xong)"),
        ("Frontend", "frontend/", "corepack pnpm typecheck ; corepack pnpm lint", "Typecheck + lint"),
        ("Load", "repo gốc", "k6 run load-tests/k6/discovery.js", "Tải đọc (không cần đăng nhập); xem load-tests/README.md"),
        ("Load", "repo gốc", "k6 run -e TOKENS_FILE=tokens.txt -e SEATS=4 load-tests/k6/hold-contention.js", "Tranh chấp ghế qua gateway thật"),
        ("Báo cáo", "repo gốc", "python scripts/build-test-report.py", "Sinh lại workbook này từ kết quả chạy gần nhất"),
    ]
    put_table(ws, 4, ["Hệ", "Thư mục", "Lệnh", "Ghi chú"], [list(c) for c in cmds], [11, 14, 82, 60])
    r = 4 + len(cmds) + 2
    for line in (
        "Yêu cầu: JDK 21 (JAVA_HOME), Docker Desktop đang chạy (cho integration), Node ≥ 20 + corepack, Playwright Chromium (corepack pnpm exec playwright install chromium).",
        "Không cần biến môi trường cho unit/slice test. E2E tự sinh AUTH_SECRET và dùng nhà phát hành OIDC giả — không đọc .env.local.",
        "Máy 16 GB: chạy integration tuần tự, MAVEN_OPTS=-Xmx512m; Docker Desktop (WSL) chiếm ~6 GB.",
    ):
        ws.cell(row=r, column=1, value="• " + line).font = F_BASE
        r += 1

    for sheet in wb.worksheets:
        sheet.sheet_properties.tabColor = "1F3864"
    # Công thức chưa có giá trị đệm (openpyxl không tính): bắt Excel tính lại ngay khi mở.
    wb.calculation.fullCalcOnLoad = True
    OUT.parent.mkdir(parents=True, exist_ok=True)
    wb.save(OUT)
    files = write_module_files(all_rows, OUT.parent / "danh-muc-test")
    sys.stdout.reconfigure(encoding="utf-8")
    folder = OUT.parent / "danh-muc-test"
    print(f"Đã ghi {OUT}: {len(all_rows)} test, {len(cases)} ca chức năng, {len(api)} endpoint; {len(files)} tệp danh mục theo module trong {folder}")


if __name__ == "__main__":
    build()
