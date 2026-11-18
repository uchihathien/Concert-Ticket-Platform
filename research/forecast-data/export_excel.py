#!/usr/bin/env python3
"""Script 3 — gộp các CSV thành data/bo-du-lieu-nghien-cuu.xlsx.

Sheet: 1_su_kien, 2_hang_ve, 3_trang_thai_ngay (cùng tên cột như CSV), 4_suc_chua_dia_diem,
5_so_sanh (lần chụp đầu với lần chụp gần nhất, xem compare_snapshots), và thong_ke (tổng số sự kiện, số theo thể loại, theo thành phố, số suất sắp diễn đang theo dõi, số hạng vé
đã hết ở lần chụp gần nhất, số sự kiện nhóm phân tích CO và địa điểm CO còn thiếu sức chứa).

Cột số (giá, số hạng vé, sức chứa, số vé còn) được ghi thành SỐ trong Excel để lọc/tính được ngay.

    .venv/bin/python export_excel.py
"""

from __future__ import annotations

import sys
from collections import Counter

from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill
from openpyxl.utils import get_column_letter

from tb_reader import (
    EXCEL_PATH, HANG_VE_COLUMNS, HANG_VE_CSV, SU_KIEN_COLUMNS, SU_KIEN_CSV, SUC_CHUA_COLUMNS, SUC_CHUA_CSV,
    TRANG_THAI, TRANG_THAI_COLUMNS, TRANG_THAI_CSV, code_order, fold, read_csv, today,
)

NUMERIC = {"suc_chua", "so_hang_ve", "gia_min", "gia_max", "gia", "so_luong", "so_ve_con"}
HEADER_FONT = Font(bold=True)
HEADER_FILL = PatternFill("solid", fgColor="DDEBF7")


def cell_value(column: str, value: str):
    if column in NUMERIC and value not in ("", None):
        try:
            return int(value)
        except ValueError:
            return value
    return value


def add_sheet(book: Workbook, title: str, columns: list, rows: list) -> None:
    sheet = book.create_sheet(title)
    sheet.append(columns)
    for cell in sheet[1]:
        cell.font, cell.fill = HEADER_FONT, HEADER_FILL
    for row in rows:
        sheet.append([cell_value(column, row.get(column, "")) for column in columns])
    sheet.freeze_panes = "A2"
    if rows:
        sheet.auto_filter.ref = sheet.dimensions
    for index, column in enumerate(columns, start=1):
        width = max([len(column)] + [len(str(row.get(column, ""))) for row in rows[:500]])
        sheet.column_dimensions[get_column_letter(index)].width = min(max(width + 2, 8), 60)


def latest_statuses(history: list) -> dict:
    """{(mã suất, tên hạng): trạng thái} của lần chụp gần nhất mỗi hạng vé."""
    latest: dict = {}
    for row in history:
        key = (row["ma_su_kien"], row["ten_hang"])
        if key not in latest or row["ngay_cao"] >= latest[key][0]:
            latest[key] = (row["ngay_cao"], row["trang_thai"])
    return {key: status for key, (_, status) in latest.items()}


# Khoảng giá để xem hạng vé hết theo mức giá. Mốc trên không tính vào khoảng (300.000 thuộc khoảng 2).
KHOANG_GIA = ((0, "Miễn phí"), (300_000, "Dưới 300.000"), (1_000_000, "300.000 – dưới 1 triệu"),
              (3_000_000, "1 – dưới 3 triệu"), (None, "Từ 3 triệu"))
SO_SANH_COLUMNS = ["ma_su_kien", "ten_su_kien", "the_loai", "nhom_phan_tich", "ngay_dien", "ten_hang", "gia",
                   "trang_thai_dau", "trang_thai_cuoi"]


def khoang_gia(price: str) -> str:
    try:
        value = int(price)
    except (TypeError, ValueError):
        return "(không rõ giá)"
    if value <= 0:
        return KHOANG_GIA[0][1]
    for limit, name in KHOANG_GIA[1:]:
        if limit is None or value < limit:
            return name
    return KHOANG_GIA[-1][1]


def compare_snapshots(events: list, history: list) -> dict | None:
    """So sánh lần chụp ĐẦU với lần chụp GẦN NHẤT, theo từng hạng vé (mã suất, tên hạng).

    Chỉ hạng vé có mặt ở cả hai ngày mới so được: snapshot_status.py chỉ chụp suất chưa diễn, nên suất
    đã diễn giữa hai ngày không có dòng ở ngày sau — đếm riêng, không coi là "đổi trạng thái".
    Trả None khi mới có một ngày chụp.
    """
    days = sorted({row["ngay_cao"] for row in history})
    if len(days) < 2:
        return None
    first, last = days[0], days[-1]
    status = {first: {}, last: {}}
    for row in history:
        if row["ngay_cao"] in status:
            status[row["ngay_cao"]][(row["ma_su_kien"], row["ten_hang"])] = row
    both = sorted(set(status[first]) & set(status[last]), key=lambda key: (code_order(key[0]), key[1]))
    showing = {row["ma_su_kien"]: row for row in events}
    changed, transitions, sold_out = [], Counter(), []
    for key in both:
        before, after = status[first][key]["trang_thai"], status[last][key]["trang_thai"]
        transitions[(before, after)] += 1
        if before == after:
            continue
        info = showing.get(key[0], {})
        row = {"ma_su_kien": key[0], "ten_su_kien": info.get("ten_su_kien", ""), "the_loai": info.get("the_loai", ""),
               "nhom_phan_tich": info.get("nhom_phan_tich", ""), "ngay_dien": info.get("ngay_dien", ""),
               "ten_hang": key[1], "gia": status[last][key]["gia"] or status[first][key]["gia"],
               "trang_thai_dau": before, "trang_thai_cuoi": after}
        changed.append(row)
        if after == "HET_VE":
            sold_out.append(row)
    return {
        "ngay_dau": first, "ngay_cuoi": last, "so_ngay_chup": len(days),
        "chi_ngay_dau": len(set(status[first]) - set(status[last])),
        "chi_ngay_cuoi": len(set(status[last]) - set(status[first])),
        "ca_hai": len(both), "chuyen_trang_thai": transitions, "thay_doi": changed, "moi_het_ve": sold_out,
        "con_ve_dau": sum(1 for key in both if status[first][key]["trang_thai"] != "HET_VE"),
    }


def comparison_rows(result: dict) -> list:
    """Các dòng tóm tắt đặt trên đầu sheet 5_so_sanh."""
    sold_out = result["moi_het_ve"]
    rows = [
        (f"SO SÁNH {result['ngay_dau']} → {result['ngay_cuoi']}", "", ""),
        ("Số ngày đã chụp", result["so_ngay_chup"], "chỉ so ngày đầu với ngày gần nhất"),
        ("Hạng vé có ở cả hai ngày", result["ca_hai"], "chỉ những hạng này được so"),
        ("Chỉ có ở ngày đầu", result["chi_ngay_dau"], "suất đã diễn, bị huỷ hoặc dời lịch"),
        ("Chỉ có ở ngày gần nhất", result["chi_ngay_cuoi"], "hạng vé hoặc suất mới thêm"),
        ("Hạng vé đổi trạng thái", len(result["thay_doi"]), ""),
        ("Hạng vé mới chuyển sang HET_VE", len(sold_out),
         f"trên {result['con_ve_dau']} hạng chưa hết ở ngày đầu"),
        ("", "", ""),
        ("CHUYỂN TRẠNG THÁI (đầu → cuối)", "Số hạng vé", ""),
    ]
    order = {code: index for index, code in enumerate(TRANG_THAI)}
    for (before, after), count in sorted(result["chuyen_trang_thai"].items(),
                                         key=lambda item: (order.get(item[0][0], 99), order.get(item[0][1], 99))):
        rows.append((f"{before} → {after}", count, "không đổi" if before == after else ""))
    for title, field in (("MỚI HẾT VÉ THEO THỂ LOẠI", "the_loai"), ("MỚI HẾT VÉ THEO NHÓM PHÂN TÍCH", "nhom_phan_tich")):
        rows += [("", "", ""), (title, "Số hạng vé", "")]
        rows += [(name or "(không rõ)", count, "") for name, count in Counter(row[field] for row in sold_out).most_common()]
    rows += [("", "", ""), ("MỚI HẾT VÉ THEO KHOẢNG GIÁ", "Số hạng vé", "")]
    by_price = Counter(khoang_gia(row["gia"]) for row in sold_out)
    rows += [(name, by_price[name], "") for _, name in KHOANG_GIA if by_price[name]]
    rows += [(name, count, "") for name, count in by_price.items() if name not in {n for _, n in KHOANG_GIA}]
    return rows


def add_comparison_sheet(book: Workbook, result: dict | None) -> None:
    sheet = book.create_sheet("5_so_sanh")
    if result is None:
        sheet.append(["Mới có một ngày chụp trạng thái — cần ít nhất hai ngày để so sánh."])
        return
    for row in comparison_rows(result):
        sheet.append(list(row))
        if row[0] and row[0].isupper():
            sheet.cell(sheet.max_row, 1).font = HEADER_FONT
    sheet.append([])
    sheet.append(["CHI TIẾT HẠNG VÉ ĐỔI TRẠNG THÁI"])
    sheet.cell(sheet.max_row, 1).font = HEADER_FONT
    sheet.append(SO_SANH_COLUMNS)
    for cell in sheet[sheet.max_row]:
        cell.font, cell.fill = HEADER_FONT, HEADER_FILL
    for row in result["thay_doi"]:
        sheet.append([cell_value(column, row[column]) for column in SO_SANH_COLUMNS])
    for letter, width in zip("ABCDEFGHI", (36, 40, 22, 14, 12, 30, 12, 16, 16)):
        sheet.column_dimensions[letter].width = width


def statistics(events: list, tickets: list, history: list, venues: list) -> list:
    day = today().isoformat()
    base_events = {row["url"] for row in events}
    upcoming = [row for row in events if row["ngay_dien"] >= day]
    latest = latest_statuses(history)
    co_venues = {(fold(row["dia_diem"]), fold(row["thanh_pho"])) for row in events if row.get("nhom_phan_tich") == "CO"}
    rows = [
        ("TỔNG QUAN", "", ""),
        ("Ngày xuất", day, ""),
        ("Tổng số sự kiện (theo url)", len(base_events), ""),
        ("Tổng số suất diễn (dòng su_kien)", len(events), ""),
        ("Tổng số hạng vé (dòng hang_ve)", len(tickets), ""),
        ("Số suất sắp diễn đang theo dõi", len(upcoming), "ngay_dien từ hôm nay trở đi"),
        ("Số sự kiện sắp diễn đang theo dõi", len({row["url"] for row in upcoming}), ""),
        ("Số hạng vé đã hết (lần chụp gần nhất)", sum(status == "HET_VE" for status in latest.values()),
         f"trên {len(latest)} hạng vé đã từng chụp"),
        ("Số ngày đã chụp trạng thái", len({row["ngay_cao"] for row in history}), ""),
        ("", "", ""),
        ("NHÓM PHÂN TÍCH CO", "", "cột nhom_phan_tich"),
        ("Số sự kiện nhóm CO", len({row["url"] for row in events if row.get("nhom_phan_tich") == "CO"}),
         "Nhạc sống, Sân khấu & Nghệ thuật, Thể Thao; không Online"),
        ("Số suất nhóm CO", sum(row.get("nhom_phan_tich") == "CO" for row in events), ""),
        ("Địa điểm nhóm CO chưa có sức chứa", sum(1 for v in venues if (fold(v["dia_diem"]), fold(v["thanh_pho"])) in co_venues
                                                 and not v.get("suc_chua")), f"trên {len(co_venues)} địa điểm nhóm CO"),
        ("", "", ""),
        ("TRẠNG THÁI CUỐI CỦA SUẤT", "Số suất", ""),
    ]
    status_count = Counter(row["trang_thai_cuoi"] for row in events)
    rows += [(status, status_count[status], "") for status in TRANG_THAI if status_count[status]]
    for title, field in (("THEO THỂ LOẠI", "the_loai"), ("THEO THÀNH PHỐ", "thanh_pho")):
        # Đếm theo SỰ KIỆN (url), không theo suất: một show 5 đêm không được tính 5 lần.
        per_event = {row["url"]: row[field] or "(không rõ)" for row in events}
        rows += [("", "", ""), (title, "Số sự kiện", "")]
        rows += [(name, count, "") for name, count in Counter(per_event.values()).most_common()]
    return rows


def main() -> int:
    events = read_csv(SU_KIEN_CSV)
    if not events:
        print(f"Chưa có {SU_KIEN_CSV.name}. Chạy collect_events.py trước.", file=sys.stderr)
        return 1
    tickets = read_csv(HANG_VE_CSV)
    history = read_csv(TRANG_THAI_CSV)
    venues = read_csv(SUC_CHUA_CSV)

    book = Workbook()
    book.remove(book.active)
    add_sheet(book, "1_su_kien", SU_KIEN_COLUMNS, events)
    add_sheet(book, "2_hang_ve", HANG_VE_COLUMNS, tickets)
    add_sheet(book, "3_trang_thai_ngay", TRANG_THAI_COLUMNS, history)
    add_sheet(book, "4_suc_chua_dia_diem", SUC_CHUA_COLUMNS, venues)
    add_comparison_sheet(book, compare_snapshots(events, history))

    sheet = book.create_sheet("thong_ke")
    for row in statistics(events, tickets, history, venues):
        sheet.append(list(row))
        if row[0] and row[0].isupper():
            sheet.cell(sheet.max_row, 1).font = HEADER_FONT
    sheet.column_dimensions["A"].width = 42
    sheet.column_dimensions["B"].width = 14
    sheet.column_dimensions["C"].width = 40

    EXCEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    book.save(EXCEL_PATH)
    print(f"Đã ghi {EXCEL_PATH} — {len(events)} suất, {len(tickets)} hạng vé, {len(history)} dòng trạng thái.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
