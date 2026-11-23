#!/usr/bin/env python3
"""Chọn mẫu suất diễn để đối chiếu nhãn bằng tay, xuất ra Excel cho người kiểm tra điền.

    .venv/bin/python chon_mau_doi_chieu.py            # 20 suất, seed 42
    .venv/bin/python chon_mau_doi_chieu.py --so-suat 30

Mẫu phân tầng theo nhãn để mỗi loại đều được kiểm: khoảng 1/3 suất y = 0, 1/3 suất y = 1, còn lại
y nằm giữa. Nhãn được lấy y hệt train.py (lần chụp đầu tiên sau ngày diễn), kèm trạng thái từng hạng
vé đã dùng để tính nhãn — người kiểm tra so với trang Ticketbox hiện tại và với nguồn bên ngoài
(báo, fanpage ban tổ chức) rồi điền kết luận.

Không đổi dữ liệu nào; chỉ ghi ../doi_chieu_nhan_thu_cong.xlsx.
"""

from __future__ import annotations

import argparse
import random

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.worksheet.datavalidation import DataValidation

from tb_reader import HERE, SU_KIEN_CSV, SUC_CHUA_CSV, TRANG_THAI_CSV, read_csv
from train import chup_theo_suat, tao_bang

OUT = HERE.parent / "doi_chieu_nhan_thu_cong.xlsx"
TEN_TRANG_THAI = {"HET_VE": "hết vé", "NGUNG_BAN": "ngừng bán", "CON_VE": "còn vé", "SAP_HET": "sắp hết",
                  "CHUA_MO_BAN": "chưa mở bán", "KHONG_RO": "không rõ"}
COT_DIEN = ["Trang Ticketbox hiện tại khớp? (Có / Không / Trang đã gỡ)",
            "Nguồn bên ngoài (link báo, fanpage)",
            "Nguồn bên ngoài nói gì",
            "Kết luận: nhãn đúng? (Đúng / Sai / Không rõ)",
            "Ghi chú"]


def y_noi_gi(y: float) -> str:
    if y == 0:
        return "Không hạng vé nào hết vé"
    if y == 1:
        return "Mọi hạng vé đều hết vé"
    return f"Một phần hết vé ({y:.0%} giá trị vé thuộc hạng đã hết)"


def chon_mau(rows: list, so_suat: int, seed: int) -> list:
    rng = random.Random(seed)
    nhom = {"y=0": [r for r in rows if r["nhan"] == 0], "y=1": [r for r in rows if r["nhan"] == 1],
            "giữa": [r for r in rows if 0 < r["nhan"] < 1]}
    phan = {"y=0": so_suat // 3 + (so_suat % 3 > 0), "y=1": so_suat // 3 + (so_suat % 3 > 1)}
    phan["giữa"] = so_suat - phan["y=0"] - phan["y=1"]
    mau = []
    for ten, ds in nhom.items():
        mau += [(ten, r) for r in rng.sample(ds, min(phan[ten], len(ds)))]
    return sorted(mau, key=lambda x: (x[1]["ngay_dien"], x[1]["ma_su_kien"]))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--so-suat", type=int, default=20)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)

    events = read_csv(SU_KIEN_CSV)
    history = read_csv(TRANG_THAI_CSV)
    url = {e["ma_su_kien"]: e["url"] for e in events}
    thanh_pho = {e["ma_su_kien"]: e["thanh_pho"] for e in events}
    chup = chup_theo_suat(history)
    rows = tao_bang(events, history, read_csv(SUC_CHUA_CSV))
    mau = chon_mau(rows, args.so_suat, args.seed)

    wb = Workbook()
    ws = wb.active
    ws.title = "Đối chiếu"
    head = ["STT", "Nhóm nhãn", "Mã suất", "Sự kiện", "Ngày diễn", "Thành phố", "Link Ticketbox",
            "Ngày ghi nhận nhãn", "Trạng thái các hạng vé lúc ghi nhận", "Nhãn y", "Nhãn nói gì"] + COT_DIEN
    ws.append(head)
    for i, (nhom, r) in enumerate(mau, 1):
        snap = chup[r["ma_su_kien"]][r["ngay_nhan"]]
        hang = "\n".join(f"• {x['ten_hang']} ({int(float(x['gia'] or 0)):,}đ): "
                         f"{TEN_TRANG_THAI.get(x['trang_thai'], x['trang_thai'])}".replace(",", ".")
                         for x in snap)
        ws.append([i, nhom, r["ma_su_kien"], r["ten_su_kien"], r["ngay_dien"], thanh_pho[r["ma_su_kien"]],
                   url[r["ma_su_kien"]], r["ngay_nhan"], hang, round(r["nhan"], 3), y_noi_gi(r["nhan"])]
                  + [""] * len(COT_DIEN))
        ws.cell(row=i + 1, column=7).hyperlink = url[r["ma_su_kien"]]
        ws.cell(row=i + 1, column=7).font = Font(color="0563C1", underline="single")

    do_rong = [5, 9, 9, 40, 11, 14, 30, 12, 55, 7, 30, 22, 30, 30, 18, 25]
    for col, w in enumerate(do_rong, 1):
        ws.column_dimensions[ws.cell(row=1, column=col).column_letter].width = w
    for cell in ws[1]:
        cell.font = Font(bold=True)
        cell.alignment = Alignment(wrap_text=True, vertical="top")
    vang = PatternFill("solid", fgColor="FFF2CC")
    for row in ws.iter_rows(min_row=2):
        for cell in row:
            cell.alignment = Alignment(wrap_text=True, vertical="top")
        for cell in row[-len(COT_DIEN):]:
            cell.fill = vang
    ws.freeze_panes = "D2"

    n = len(mau) + 1
    for col, chon in [("L", '"Có,Không,Trang đã gỡ"'), ("O", '"Đúng,Sai,Không rõ"')]:
        dv = DataValidation(type="list", formula1=chon, allow_blank=True)
        ws.add_data_validation(dv)
        dv.add(f"{col}2:{col}{n}")

    hd = wb.create_sheet("Hướng dẫn")
    for dong in [
        "MỤC ĐÍCH: ước lượng tỷ lệ nhãn đúng (bài báo, mục Hạn chế và nhận xét 2.3).",
        "Mỗi dòng là một suất diễn chọn ngẫu nhiên (seed 42), chia đều theo nhãn: y = 0, y = 1 và y nằm giữa.",
        "",
        "Với mỗi dòng (các ô màu vàng):",
        "1. Mở Link Ticketbox. So trạng thái từng hạng vé trên trang với cột 'Trạng thái các hạng vé lúc ghi nhận'.",
        "   Ghi Có / Không / Trang đã gỡ.",
        "2. Tìm nguồn bên ngoài về việc bán vé của suất đó: báo, fanpage ban tổ chức hoặc nghệ sĩ",
        "   (ví dụ thông báo 'sold out', 'cháy vé', 'mở bán thêm', 'còn vé'). Dán link và tóm tắt.",
        "3. Kết luận nhãn có đúng không:",
        "   - y = 1 đúng nếu có nguồn cho biết đã bán hết / cháy vé;",
        "   - y = 0 đúng nếu có nguồn cho biết vé không bán hết (mở bán đến sát ngày, giảm giá, tặng vé...);",
        "   - không tìm được nguồn thì ghi 'Không rõ' — KHÔNG đoán.",
        "",
        "Khi xong, gửi lại file. Tỷ lệ nhãn đúng = số 'Đúng' / (số 'Đúng' + số 'Sai'); bài báo sẽ báo cả số 'Không rõ'.",
    ]:
        hd.append([dong])
    hd.column_dimensions["A"].width = 120

    wb.save(OUT)
    dem = {k: sum(1 for n_, _ in mau if n_ == k) for k in ("y=0", "y=1", "giữa")}
    print(f"Đã ghi {OUT} — {len(mau)} suất: {dem}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
