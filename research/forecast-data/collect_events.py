#!/usr/bin/env python3
"""Script 1 — lấy thông tin sự kiện và hạng vé CÔNG KHAI của Ticketbox cho bộ dữ liệu nghiên cứu.

Bộ dữ liệu cho bài "Forecasting Event Ticket Sales with LightGBM Quantile Regression to Estimate
Break-even Probability". Khác dữ liệu demo (scripts/ticketbox): GIỮ NGUYÊN ngày gốc, không dời ngày,
không nhập vào NexaTicket.

Ghi ra:
  data/su_kien.csv            mỗi SUẤT DIỄN một dòng (TB001, hoặc TB001-S1, TB001-S2… khi nhiều suất)
  data/hang_ve.csv            mỗi hạng vé của mỗi suất một dòng
  data/suc_chua_dia_diem.csv  mỗi địa điểm một dòng, cột sức chứa để trống cho người tự tra
  data/trang_thai_ngay.csv    thêm trạng thái hôm nay của từng hạng vé (cùng dữ liệu vừa đọc, không
                              tốn thêm lần gọi nào) — nhờ vậy sự kiện ĐÃ DIỄN cũng có trạng thái cuối
                              của từng hạng.

Danh sách lấy từ API tìm kiếm của trang ticketbox.vn/search, xen kẽ sự kiện sắp diễn và đã diễn để có
cả hai loại. Chạy lại được: suất đã có (theo url + ngày diễn + giờ diễn) được cập nhật, không tạo trùng;
cột người đã điền tay (sức chứa) được giữ. Lỗi từng sự kiện ghi vào data/cao-du-lieu.log, script đi tiếp.

    .venv/bin/python collect_events.py --limit 10
    .venv/bin/python collect_events.py --limit 200 --chi-moi     # chỉ thêm sự kiện chưa có
    .venv/bin/python collect_events.py --loai past --limit 50
"""

from __future__ import annotations

import argparse
import re
import sys
from datetime import datetime
from itertools import zip_longest

from tb_reader import (
    HANG_VE_COLUMNS, HANG_VE_CSV, NGUON, SITE, SU_KIEN_COLUMNS, SU_KIEN_CSV, SUC_CHUA_COLUMNS,
    SUC_CHUA_CSV, TRANG_THAI_COLUMNS, TRANG_THAI_CSV, VN, Ticketbox, append_csv, city_from_address,
    clean, fold, index_rows, log, nhom_phan_tich, read_csv, showing_groups, showing_tickets, snapshot_key, today,
    trang_thai_tong, write_csv,
)

CODE = re.compile(r"^TB(\d+)(?:-S(\d+))?$")


def listing(tb: Ticketbox, kind: str):
    """Từng sự kiện của API tìm kiếm, tải trang sau chỉ khi cần."""
    page = 1
    while True:
        try:
            results, has_more = tb.search(kind, page)
        except Exception as error:  # noqa: BLE001
            log(f"danh sách {kind} trang {page}: {error}", error=True)
            return
        for item in results:
            yield kind, item
        if not has_more or not results:
            return
        page += 1


def candidates(tb: Ticketbox, kind: str):
    """Xen kẽ sắp diễn / đã diễn (1:1) để một lần chạy nhỏ vẫn có cả hai loại."""
    kinds = ["upcoming", "past"] if kind == "all" else [kind]
    seen = set()
    for pair in zip_longest(*(listing(tb, k) for k in kinds)):
        for entry in pair:
            if entry is None:
                continue
            _, item = entry
            if item.get("id") and item.get("url") and item["id"] not in seen:
                seen.add(item["id"])
                yield item


def venue_rows(venues: list, events: list) -> list:
    """Mỗi (địa điểm, thành phố) một dòng; giữ sức chứa / nguồn người đã điền.

    Địa điểm có ít nhất một sự kiện nhóm phân tích CO được xếp lên đầu — đó là những chỗ cần tra sức
    chứa trước; trong mỗi nhóm xếp theo thành phố rồi tên.
    """
    kept = {(fold(row["dia_diem"]), fold(row["thanh_pho"])): row for row in venues}
    for row in events:
        key = (fold(row["dia_diem"]), fold(row["thanh_pho"]))
        if row["dia_diem"] and key not in kept:
            kept[key] = {"dia_diem": row["dia_diem"], "thanh_pho": row["thanh_pho"], "suc_chua": "", "nguon_tra_cuu": ""}
    analysed = {(fold(row["dia_diem"]), fold(row["thanh_pho"])) for row in events
                if (row.get("nhom_phan_tich") or nhom_phan_tich(row)) == "CO"}
    return sorted(kept.values(), key=lambda row: (
        (fold(row["dia_diem"]), fold(row["thanh_pho"])) not in analysed, fold(row["thanh_pho"]), fold(row["dia_diem"])))


class Dataset:
    """Ba bảng CSV trong bộ nhớ, khoá và cách cấp mã sự kiện."""

    def __init__(self) -> None:
        self.events = read_csv(SU_KIEN_CSV)
        self.tickets = read_csv(HANG_VE_CSV)
        self.venues = read_csv(SUC_CHUA_CSV)
        self.snapshot_keys = {snapshot_key(row) for row in read_csv(TRANG_THAI_CSV)}
        self.by_key = index_rows(self.events)
        self.base_by_url: dict = {}
        numbers = [0]
        for row in self.events:
            match = CODE.match(row["ma_su_kien"])
            if match:
                self.base_by_url.setdefault(row["url"], f"TB{match.group(1)}")
                numbers.append(int(match.group(1)))
        self.next_number = max(numbers) + 1

    def base_code(self, url: str) -> str:
        if url not in self.base_by_url:
            self.base_by_url[url] = f"TB{self.next_number:03d}"
            self.next_number += 1
        return self.base_by_url[url]

    def rows_of(self, url: str) -> list:
        return [row for row in self.events if row["url"] == url]

    def code_for(self, url: str, key: tuple, index: int, total: int) -> tuple:
        """(mã, dòng cũ hoặc None). Suất đã có giữ mã cũ; suất mới nhận mã chưa ai dùng."""
        if key in self.by_key:
            return self.by_key[key]["ma_su_kien"], self.by_key[key]
        existing = self.rows_of(url)
        # Sự kiện một suất bị dời ngày: vẫn là sự kiện đó, cập nhật dòng cũ thay vì sinh dòng trùng.
        if total == 1 and len(existing) == 1:
            old = existing[0]
            if (old["ngay_dien"], old["gio_dien"]) != key[1:3]:
                log(f"{old['ma_su_kien']} đổi lịch: {old['ngay_dien']} {old['gio_dien']} → {key[1]} {key[2]}")
            return old["ma_su_kien"], old
        base = self.base_code(url)
        taken = {row["ma_su_kien"] for row in existing}
        if total == 1 and not existing:
            return base, None
        number = index + 1
        while f"{base}-S{number}" in taken:
            number += 1
        if existing:
            log(f"{base}: thêm suất mới {key[1]} {key[2]}")
        return f"{base}-S{number}", None

    def upsert(self, row: dict, old) -> None:
        if old is None:
            self.events.append(row)
        else:
            old.update(row)
        self.by_key = index_rows(self.events)

    def replace_tickets(self, code: str, tickets: list) -> None:
        self.tickets = [row for row in self.tickets if row["ma_su_kien"] != code]
        self.tickets.extend(
            {"ma_su_kien": code, "ten_hang": t["ten_hang"], "gia": t["gia"] if t["gia"] is not None else "", "so_luong": ""}
            for t in tickets)

    def venue_table(self) -> list:
        """Mỗi (địa điểm, thành phố) một dòng; giữ sức chứa / nguồn người đã điền."""
        self.venues = venue_rows(self.venues, self.events)
        return self.venues

    def save(self) -> None:
        for row in self.events:
            row["nhom_phan_tich"] = nhom_phan_tich(row)
        venues = self.venue_table()
        capacity = {(fold(v["dia_diem"]), fold(v["thanh_pho"])): v.get("suc_chua", "") for v in venues}
        for row in self.events:
            # Sức chứa người đã tra ở bảng địa điểm được chép sang sự kiện còn trống ô này.
            if not row.get("suc_chua"):
                row["suc_chua"] = capacity.get((fold(row["dia_diem"]), fold(row["thanh_pho"])), "")

        def order(row):
            match = CODE.match(row["ma_su_kien"]) or CODE.match("TB0")
            return int(match.group(1)), int(match.group(2) or 0)

        self.events.sort(key=order)
        self.tickets.sort(key=lambda row: order(row))
        write_csv(SU_KIEN_CSV, SU_KIEN_COLUMNS, self.events)
        write_csv(HANG_VE_CSV, HANG_VE_COLUMNS, self.tickets)
        write_csv(SUC_CHUA_CSV, SUC_CHUA_COLUMNS, venues)


def build(tb: Ticketbox, data: Dataset, detail: dict, url: str, artists: list) -> list:
    """Ghi mọi suất của một sự kiện vào bộ dữ liệu. Trả về các dòng trạng thái hôm nay để ghi thêm."""
    now = datetime.now(VN)
    day = today().isoformat()
    codes = detail.get("categoriesV2") or []
    category = tb.category_names.get(codes[0], codes[0]) if codes else ""
    showings = showing_groups(detail)
    snapshots = []
    for index, ((ngay_dien, gio_dien), group) in enumerate(showings):
        key = (url, ngay_dien, gio_dien)
        code, old = data.code_for(url, key, index, len(showings))
        tickets = showing_tickets(group)
        prices = [t["gia"] for t in tickets if t["gia"] is not None]
        opens = [t["mo_ban"] for t in tickets if t["mo_ban"]]
        data.upsert({
            "ma_su_kien": code,
            "nguon": NGUON,
            "url": url,
            "ten_su_kien": clean(detail.get("title")),
            "nghe_si": "; ".join(artists),
            "the_loai": category,
            "thanh_pho": city_from_address(detail.get("address")),
            "dia_diem": clean(detail.get("venue")),
            "suc_chua": (old or {}).get("suc_chua", ""),
            # Ticketbox không ghi "ngày mở bán" riêng; mốc sớm nhất mà một hạng vé của suất bắt đầu bán.
            "ngay_mo_ban": min(opens).strftime("%Y-%m-%d") if opens else "",
            "ngay_thay_lan_dau": (old or {}).get("ngay_thay_lan_dau") or day,
            "ngay_dien": ngay_dien,
            "gio_dien": gio_dien,
            "so_hang_ve": len(tickets),
            "gia_min": min(prices) if prices else "",
            "gia_max": max(prices) if prices else "",
            "trang_thai_cuoi": trang_thai_tong(t["trang_thai"] for t in tickets),
            "ngay_cao": day,
        }, old)
        row = data.by_key[key]
        row["nhom_phan_tich"] = nhom_phan_tich(row)
        data.replace_tickets(code, tickets)
        for ticket in tickets:
            row = {
                "ngay_cao": day, "gio_cao": now.strftime("%H:%M"), "ma_su_kien": code,
                "ten_hang": ticket["ten_hang"], "trang_thai": ticket["trang_thai"],
                "so_ve_con": "" if ticket["so_ve_con"] is None else ticket["so_ve_con"],
                "gia": "" if ticket["gia"] is None else ticket["gia"],
            }
            if snapshot_key(row) not in data.snapshot_keys:
                data.snapshot_keys.add(snapshot_key(row))
                snapshots.append(row)
    return snapshots


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--limit", type=int, default=200, help="số sự kiện tối đa xử lý lần này (mặc định 200)")
    parser.add_argument("--loai", choices=("all", "upcoming", "past"), default="all",
                        help="all = xen kẽ sắp diễn và đã diễn (mặc định)")
    parser.add_argument("--chi-moi", action="store_true", help="bỏ qua sự kiện đã có, chỉ thêm sự kiện mới")
    args = parser.parse_args()

    data = Dataset()
    done = failed = 0
    print(f"Đang có {len(data.events)} suất diễn trong {SU_KIEN_CSV.name}.")
    with Ticketbox() as tb:
        for item in candidates(tb, args.loai):
            if done >= args.limit:
                break
            url = f"{SITE}/{item['url']}-{item['id']}"
            if args.chi_moi and url in data.base_by_url:
                continue
            try:
                detail = tb.event(item["id"], url)
                try:
                    artists = tb.lineup(item["id"], url)
                except Exception as error:  # noqa: BLE001 — thiếu nghệ sĩ không phải lý do bỏ sự kiện
                    log(f"sự kiện {item['id']}: không đọc được lineup ({error})", error=True)
                    artists = []
                snapshots = build(tb, data, detail, url, artists)
                # Ghi sau MỖI sự kiện: dừng giữa chừng (Ctrl+C, mất mạng) không mất phần đã cào.
                data.save()
                append_csv(TRANG_THAI_CSV, TRANG_THAI_COLUMNS, snapshots)
                done += 1
                code = data.base_by_url.get(url, "?")
                print(f"  ✓ [{done}/{args.limit}] {code} {clean(detail.get('title'))[:70]}")
            except Exception as error:  # noqa: BLE001 — một sự kiện hỏng không dừng cả script
                failed += 1
                log(f"sự kiện {item.get('id')} ({url}): {error}", error=True)

    print(f"Xong: xử lý {done} sự kiện, lỗi {failed}. Tổng {len(data.events)} suất diễn, "
          f"{len(data.tickets)} hạng vé, {len(data.venues)} địa điểm.")
    return 0 if done or not failed else 1


if __name__ == "__main__":
    sys.exit(main())
