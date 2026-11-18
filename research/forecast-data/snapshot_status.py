#!/usr/bin/env python3
"""Script 2 — chạy MỖI NGÀY: chụp trạng thái từng hạng vé của các sự kiện sắp diễn.

Đọc data/su_kien.csv, chỉ lấy suất có `ngay_dien` từ hôm nay trở đi, đọc lại trang chi tiết của từng
sự kiện (mỗi sự kiện MỘT lần dù nhiều suất) và GHI THÊM vào data/trang_thai_ngay.csv — không bao giờ ghi
đè. Một hạng vé chỉ có một dòng mỗi ngày: hôm nay đã chụp rồi thì bỏ qua, nên chạy hai lần trong ngày
không sao, máy tắt bỏ lỡ một ngày cũng không sao (chạy tiếp hôm sau).

Cập nhật thêm cột `trang_thai_cuoi` (và giá min/max, số hạng vé) trong su_kien.csv cho khớp lần chụp
mới nhất; các cột khác giữ nguyên.

    .venv/bin/python snapshot_status.py
"""

from __future__ import annotations

import sys
from collections import defaultdict
from datetime import datetime

from tb_reader import (
    HANG_VE_COLUMNS, HANG_VE_CSV, SU_KIEN_COLUMNS, SU_KIEN_CSV, TRANG_THAI_COLUMNS, TRANG_THAI_CSV, VN,
    Ticketbox, append_csv, code_order, event_id_from_url, index_rows, log, nhom_phan_tich, read_csv, showing_groups,
    showing_tickets, snapshot_key, today, trang_thai_tong, write_csv,
)


def previous_by_code(history: list, day: str) -> dict:
    """{mã suất: {tên hạng: trạng thái}} của lần chụp GẦN NHẤT trước hôm nay."""
    latest: dict = {}
    for row in history:
        if row["ngay_cao"] >= day:
            continue
        code = row["ma_su_kien"]
        if code not in latest or row["ngay_cao"] > latest[code][0]:
            latest[code] = (row["ngay_cao"], {})
        if row["ngay_cao"] == latest[code][0]:
            latest[code][1][row["ten_hang"]] = row["trang_thai"]
    return {code: statuses for code, (_, statuses) in latest.items()}


def main() -> int:
    day = today().isoformat()
    events = read_csv(SU_KIEN_CSV)
    if not events:
        print(f"Chưa có {SU_KIEN_CSV.name}. Chạy collect_events.py trước.", file=sys.stderr)
        return 1
    history = read_csv(TRANG_THAI_CSV)
    done_keys = {snapshot_key(row) for row in history}
    previous = previous_by_code(history, day)
    tickets_table = read_csv(HANG_VE_CSV)

    upcoming = [row for row in events if row["ngay_dien"] and row["ngay_dien"] >= day]
    by_url = defaultdict(list)
    for row in upcoming:
        by_url[row["url"]].append(row)
    print(f"{len(upcoming)} suất sắp diễn thuộc {len(by_url)} sự kiện cần chụp hôm nay ({day}).")

    captured_events = new_rows = sold_out_today = failed = 0
    with Ticketbox() as tb:
        for url, rows in by_url.items():
            event_id = event_id_from_url(url)
            if not event_id:
                log(f"không đọc được id sự kiện từ {url}", error=True)
                failed += 1
                continue
            try:
                detail = tb.event(event_id, url)
            except Exception as error:  # noqa: BLE001 — một sự kiện hỏng không dừng cả lần chụp
                log(f"sự kiện {event_id} ({url}): {error}", error=True)
                failed += 1
                continue
            now = datetime.now(VN)
            showings = dict(showing_groups(detail))
            snapshots = []
            for (_, ngay_dien, gio_dien), row in index_rows(rows).items():
                code = row["ma_su_kien"]
                showing = showings.get((ngay_dien, gio_dien))
                if showing is None:
                    log(f"{code}: không còn suất {row['ngay_dien']} {row['gio_dien']} trên trang (dời lịch "
                        f"hoặc huỷ?) — chạy lại collect_events.py để cập nhật", error=True)
                    continue
                tickets = showing_tickets(showing)
                before = previous.get(code)
                if before is not None:
                    names = {t["ten_hang"] for t in tickets}
                    for name in sorted(names - set(before)):
                        log(f"{code}: hạng vé MỚI xuất hiện: {name}")
                    for name in sorted(set(before) - names):
                        log(f"{code}: hạng vé BIẾN MẤT so với lần chụp trước: {name}")
                for ticket in tickets:
                    snap = {
                        "ngay_cao": day, "gio_cao": now.strftime("%H:%M"), "ma_su_kien": code,
                        "ten_hang": ticket["ten_hang"], "trang_thai": ticket["trang_thai"],
                        "so_ve_con": "" if ticket["so_ve_con"] is None else ticket["so_ve_con"],
                        "gia": "" if ticket["gia"] is None else ticket["gia"],
                    }
                    if snapshot_key(snap) in done_keys:
                        continue
                    done_keys.add(snapshot_key(snap))
                    snapshots.append(snap)
                    was = (before or {}).get(ticket["ten_hang"])
                    if ticket["trang_thai"] == "HET_VE" and was is not None and was != "HET_VE":
                        sold_out_today += 1
                        log(f"{code}: «{ticket['ten_hang']}» chuyển sang HẾT VÉ (trước đó {was})")
                prices = [t["gia"] for t in tickets if t["gia"] is not None]
                row["trang_thai_cuoi"] = trang_thai_tong(t["trang_thai"] for t in tickets)
                row["so_hang_ve"] = len(tickets)
                row["gia_min"] = min(prices) if prices else ""
                row["gia_max"] = max(prices) if prices else ""
                tickets_table = [t for t in tickets_table if t["ma_su_kien"] != code] + [
                    {"ma_su_kien": code, "ten_hang": t["ten_hang"], "gia": "" if t["gia"] is None else t["gia"], "so_luong": ""}
                    for t in tickets]
            # Ghi sau MỖI sự kiện: dừng giữa chừng không mất phần đã chụp.
            append_csv(TRANG_THAI_CSV, TRANG_THAI_COLUMNS, snapshots)
            new_rows += len(snapshots)
            captured_events += 1

    for row in events:
        row["nhom_phan_tich"] = nhom_phan_tich(row)
    write_csv(SU_KIEN_CSV, SU_KIEN_COLUMNS, events)
    write_csv(HANG_VE_CSV, HANG_VE_COLUMNS, sorted(tickets_table, key=lambda t: code_order(t["ma_su_kien"])))
    print()
    print(f"Tóm tắt {day}: đã chụp {captured_events} sự kiện ({new_rows} dòng mới), lỗi {failed}.")
    print(f"Hạng vé mới chuyển sang HET_VE hôm nay: {sold_out_today}.")
    return 0 if captured_events or not by_url else 1


if __name__ == "__main__":
    sys.exit(main())
