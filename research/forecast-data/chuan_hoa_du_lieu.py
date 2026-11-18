#!/usr/bin/env python3
"""Chuẩn hoá bộ dữ liệu ĐÃ CÀO — không gọi mạng, chạy lại bao nhiêu lần cũng được.

1. Gộp suất trùng: nhiều dòng su_kien cùng (url, ngay_dien, gio_dien) thành MỘT dòng — giữ mã nhỏ nhất
   (vd TB034-S1 giữ, TB034-S2 gộp vào). Hạng vé hợp theo tên; dòng trạng thái ngày của mã bị gộp đổi
   sang mã giữ lại, cùng (ngày, mã, hạng) thì gộp trạng thái bằng trang_thai_tong.
2. Tính lại cột nhom_phan_tich (CO / KHONG) cho mọi dòng — không xoá dòng nào.
3. Xếp lại suc_chua_dia_diem.csv: địa điểm của nhóm CO lên đầu; giữ nguyên sức chứa đã điền.

Trước khi ghi, bản cũ của các CSV được chép vào data/sao-luu-<thời điểm>/.

    .venv/bin/python chuan_hoa_du_lieu.py
"""

from __future__ import annotations

import shutil
import sys
from datetime import datetime

from collect_events import venue_rows
from tb_reader import (
    DATA_DIR, HANG_VE_COLUMNS, HANG_VE_CSV, SU_KIEN_COLUMNS, SU_KIEN_CSV, SUC_CHUA_COLUMNS, SUC_CHUA_CSV,
    TRANG_THAI_COLUMNS, TRANG_THAI_CSV, VN, code_order, log, nhom_phan_tich, read_csv, trang_thai_tong,
    write_csv,
)


def backup() -> str:
    folder = DATA_DIR / f"sao-luu-{datetime.now(VN).strftime('%Y%m%d-%H%M%S')}"
    folder.mkdir(parents=True, exist_ok=True)
    for path in (SU_KIEN_CSV, HANG_VE_CSV, TRANG_THAI_CSV, SUC_CHUA_CSV):
        if path.exists():
            shutil.copy2(path, folder / path.name)
    return folder.name


def merge_showings(events: list, tickets: list, history: list) -> tuple:
    """(events, tickets, history, {mã bị gộp: mã giữ lại})."""
    groups: dict = {}
    for row in sorted(events, key=lambda r: code_order(r["ma_su_kien"])):
        groups.setdefault((row["url"], row["ngay_dien"], row["gio_dien"]), []).append(row)
    alias: dict = {}
    for rows in groups.values():
        for extra in rows[1:]:
            alias[extra["ma_su_kien"]] = rows[0]["ma_su_kien"]
    if not alias:
        return events, tickets, history, alias

    def target(code: str) -> str:
        return alias.get(code, code)

    # Hạng vé: hợp theo (mã giữ lại, tên); giữ giá đầu tiên.
    merged_tickets: dict = {}
    for row in tickets:
        key = (target(row["ma_su_kien"]), row["ten_hang"])
        merged_tickets.setdefault(key, {**row, "ma_su_kien": key[0]})

    # Trạng thái ngày: đổi mã, cùng (ngày, mã, hạng) thì gộp — giữ thứ tự xuất hiện đầu tiên.
    merged_history: dict = {}
    statuses: dict = {}
    for row in history:
        key = (row["ngay_cao"], target(row["ma_su_kien"]), row["ten_hang"])
        statuses.setdefault(key, []).append(row["trang_thai"])
        if key not in merged_history:
            merged_history[key] = {**row, "ma_su_kien": key[1]}
        elif row["gio_cao"] < merged_history[key]["gio_cao"]:
            merged_history[key]["gio_cao"] = row["gio_cao"]
    for key, values in statuses.items():
        if len(values) > 1:
            merged_history[key]["trang_thai"] = trang_thai_tong(values)

    # Suất giữ lại: cập nhật số hạng, giá, trạng thái cuối, mốc mở bán / thấy lần đầu sớm nhất.
    tickets_by_code: dict = {}
    for (code, _), row in merged_tickets.items():
        tickets_by_code.setdefault(code, []).append(row)
    latest_day: dict = {}
    for (day, code, _), row in merged_history.items():
        latest_day[code] = max(latest_day.get(code, ""), day)
    kept = []
    for rows in groups.values():
        row = rows[0]
        if len(rows) > 1:
            code = row["ma_su_kien"]
            for field in ("ngay_mo_ban", "ngay_thay_lan_dau"):
                values = [r[field] for r in rows if r[field]]
                row[field] = min(values) if values else ""
            prices = [int(t["gia"]) for t in tickets_by_code.get(code, []) if t["gia"] != ""]
            row["so_hang_ve"] = len(tickets_by_code.get(code, []))
            row["gia_min"] = min(prices) if prices else ""
            row["gia_max"] = max(prices) if prices else ""
            last = [r["trang_thai"] for (day, c, _), r in merged_history.items() if c == code and day == latest_day.get(code)]
            row["trang_thai_cuoi"] = trang_thai_tong(last) if last else row["trang_thai_cuoi"]
            log(f"gộp suất trùng {row['url']} {row['ngay_dien']} {row['gio_dien']}: "
                f"{', '.join(r['ma_su_kien'] for r in rows)} → {code}")
        kept.append(row)
    kept.sort(key=lambda r: code_order(r["ma_su_kien"]))
    tickets_out = sorted(merged_tickets.values(), key=lambda r: code_order(r["ma_su_kien"]))
    return kept, tickets_out, list(merged_history.values()), alias


def main() -> int:
    events = read_csv(SU_KIEN_CSV)
    if not events:
        print(f"Chưa có {SU_KIEN_CSV.name}.", file=sys.stderr)
        return 1
    tickets, history, venues = read_csv(HANG_VE_CSV), read_csv(TRANG_THAI_CSV), read_csv(SUC_CHUA_CSV)
    before = (len(events), len(tickets), len(history))
    folder = backup()

    events, tickets, history, alias = merge_showings(events, tickets, history)
    for row in events:
        row["nhom_phan_tich"] = nhom_phan_tich(row)
    venues = venue_rows(venues, events)

    write_csv(SU_KIEN_CSV, SU_KIEN_COLUMNS, events)
    write_csv(HANG_VE_CSV, HANG_VE_COLUMNS, tickets)
    write_csv(TRANG_THAI_CSV, TRANG_THAI_COLUMNS, history)
    write_csv(SUC_CHUA_CSV, SUC_CHUA_COLUMNS, venues)

    co = sum(row["nhom_phan_tich"] == "CO" for row in events)
    print(f"Sao lưu bản cũ: data/{folder}/")
    print(f"Gộp {len(alias)} suất trùng. su_kien {before[0]} → {len(events)}, hang_ve {before[1]} → {len(tickets)}, "
          f"trang_thai_ngay {before[2]} → {len(history)} dòng.")
    print(f"nhom_phan_tich: CO {co} suất, KHONG {len(events) - co} suất.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
