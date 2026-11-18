"""Kiểm thử các hàm đọc giá, ngày giờ tiếng Việt và trạng thái vé — không gọi mạng."""

import urllib.robotparser
from datetime import datetime

import pytest

from tb_reader import (
    VN, allowed, city_from_address, code_order, event_id_from_url, nhan_dien_trang_thai, parse_price,
    parse_vn_datetime, showing_tickets, split_date_time, trang_thai_tong,
)


# --- giá -----------------------------------------------------------------------------------------

@pytest.mark.parametrize("text, expected", [
    ("1.500.000 đ", 1_500_000),
    ("1.500.000đ", 1_500_000),
    ("1,500,000 VND", 1_500_000),
    ("Từ 350.000 đ", 350_000),
    ("1,5 triệu", 1_500_000),
    ("1.5tr", 1_500_000),
    ("2 triệu", 2_000_000),
    ("350k", 350_000),
    ("Miễn phí", 0),
    ("MIỄN PHÍ", 0),
    ("Free", 0),
    (2100000, 2_100_000),
    (0, 0),
])
def test_parse_price(text, expected):
    assert parse_price(text) == expected


@pytest.mark.parametrize("text", [None, "", "Liên hệ", True, -5])
def test_parse_price_khong_doc_duoc(text):
    assert parse_price(text) is None


# --- ngày giờ ------------------------------------------------------------------------------------

@pytest.mark.parametrize("text, expected", [
    ("2026-11-29T11:00:00Z", datetime(2026, 11, 29, 18, 0, tzinfo=VN)),
    ("2026-11-29T18:00:00+07:00", datetime(2026, 11, 29, 18, 0, tzinfo=VN)),
    ("19:30, 15 tháng 11, 2025", datetime(2025, 11, 15, 19, 30, tzinfo=VN)),
    ("18:00 - 20:30, 29 Tháng 11, 2026", datetime(2026, 11, 29, 18, 0, tzinfo=VN)),
    ("T7, 15/11/2025", datetime(2025, 11, 15, 0, 0, tzinfo=VN)),
    ("15/11/2025 19:30", datetime(2025, 11, 15, 19, 30, tzinfo=VN)),
    ("20h00 Thứ Bảy, 05.12.26", datetime(2026, 12, 5, 20, 0, tzinfo=VN)),
])
def test_parse_vn_datetime(text, expected):
    assert parse_vn_datetime(text) == expected


def test_parse_vn_datetime_thieu_nam_dung_nam_mac_dinh():
    assert parse_vn_datetime("20:00 Thứ Bảy 24.10", default_year=2026) == datetime(2026, 10, 24, 20, 0, tzinfo=VN)


@pytest.mark.parametrize("text", [None, "", "sắp công bố", "32/13/2026"])
def test_parse_vn_datetime_khong_doc_duoc(text):
    assert parse_vn_datetime(text) is None


def test_split_date_time_doi_utc_sang_gio_viet_nam():
    # 17:00 UTC là 00:00 hôm sau ở Việt Nam: ngày diễn phải là ngày theo giờ Việt Nam.
    assert split_date_time("2026-11-29T17:30:00Z") == ("2026-11-30", "00:30")
    assert split_date_time(None) == ("", "")


# --- trạng thái vé -------------------------------------------------------------------------------

@pytest.mark.parametrize("status, text, expected", [
    ("book_now", "Book now", ("CON_VE", None)),
    ("sold_out", "Sold out", ("HET_VE", None)),
    ("sale_closed", "Online booking closed", ("NGUNG_BAN", None)),
    ("not_open_for_sale_yet", "Ticket available soon", ("CHUA_MO_BAN", None)),
    ("registration_closed", "Registration closed", ("NGUNG_BAN", None)),
    ("SOLD_OUT", None, ("HET_VE", None)),
    ("book_now", "Sắp hết vé", ("SAP_HET", None)),
    ("book_now", "Còn 12 vé", ("CON_VE", 12)),
])
def test_nhan_dien_theo_ma_json(status, text, expected):
    assert nhan_dien_trang_thai(status, text) == expected


@pytest.mark.parametrize("text, expected", [
    ("Hết vé", ("HET_VE", 0)),
    ("HẾT VÉ", ("HET_VE", 0)),
    ("Sold out", ("HET_VE", 0)),
    ("Sắp hết vé", ("SAP_HET", None)),
    ("Sắp hết - còn 5 vé", ("SAP_HET", 5)),
    ("Còn 1.200 vé", ("CON_VE", 1200)),
    ("Còn lại 3 vé", ("CON_VE", 3)),
    ("Còn 0 vé", ("HET_VE", 0)),
    ("Vé ngừng bán online", ("NGUNG_BAN", None)),
    ("Ngừng bán", ("NGUNG_BAN", None)),
    ("Sắp mở bán", ("CHUA_MO_BAN", None)),
    ("Mua vé ngay", ("CON_VE", None)),
    ("Đăng ký ngay", ("CON_VE", None)),
    ("Registration closed", ("NGUNG_BAN", None)),
    ("", ("KHONG_RO", None)),
    (None, ("KHONG_RO", None)),
    ("abc xyz", ("KHONG_RO", None)),
])
def test_nhan_dien_theo_chu_tren_trang(text, expected):
    assert nhan_dien_trang_thai(None, text) == expected


def test_ma_json_la_ma_la_thi_doc_chu():
    assert nhan_dien_trang_thai("unknown_status", "Hết vé") == ("HET_VE", 0)


@pytest.mark.parametrize("codes, expected", [
    (["CON_VE", "HET_VE"], "CON_VE"),
    (["SAP_HET", "HET_VE"], "SAP_HET"),
    (["SAP_HET", "CON_VE"], "CON_VE"),
    (["HET_VE", "HET_VE"], "HET_VE"),
    (["HET_VE", "NGUNG_BAN"], "NGUNG_BAN"),
    (["CHUA_MO_BAN", "HET_VE"], "CHUA_MO_BAN"),
    (["KHONG_RO", "HET_VE"], "HET_VE"),
    ([], "KHONG_RO"),
    (["KHONG_RO"], "KHONG_RO"),
])
def test_trang_thai_tong(codes, expected):
    assert trang_thai_tong(codes) == expected


def test_showing_tickets_doc_json_ticketbox():
    showing = {"ticketTypes": [
        {"name": "Hạng Sky", "price": 2100000, "isFree": False, "status": "sold_out",
         "statusName": "Sold out", "startTime": "2026-08-19T04:40:00Z"},
        {"name": " Vé miễn phí ", "price": 0, "isFree": True, "status": "book_now", "statusName": "Book now"},
        {"name": "Hạng Sky", "price": 1, "status": "book_now"},  # trùng tên: giữ hạng đầu
        {"name": "", "price": 5},                                # không tên: bỏ
    ]}
    tickets = showing_tickets(showing)
    assert [(t["ten_hang"], t["gia"], t["trang_thai"]) for t in tickets] == [
        ("Hạng Sky", 2_100_000, "HET_VE"), ("Vé miễn phí", 0, "CON_VE")]
    assert tickets[0]["mo_ban"] == datetime(2026, 8, 19, 11, 40, tzinfo=VN)


# --- linh tinh -----------------------------------------------------------------------------------

def test_event_id_from_url():
    assert event_id_from_url("https://ticketbox.vn/live-show-bang-kieu-26523") == 26523
    assert event_id_from_url("https://ticketbox.vn/14-casper-26473?utm_source=x") == 26473
    assert event_id_from_url("https://ticketbox.vn/") is None


def test_city_from_address():
    assert city_from_address("Số 1, Quận 1, Thành Phố Hồ Chí Minh") == "TP. Hồ Chí Minh"
    assert city_from_address("Phố Huế, Thành phố Hà Nội") == "Hà Nội"
    assert city_from_address("Khu du lịch, Tỉnh Lâm Đồng") == "Lâm Đồng"
    assert city_from_address(None) == ""


def test_code_order_tu_nhien():
    codes = ["TB010", "TB002-S2", "TB002", "TB002-S10", "TB1000", "TB002-S1"]
    assert sorted(codes, key=code_order) == ["TB002", "TB002-S1", "TB002-S2", "TB002-S10", "TB010", "TB1000"]


def test_robots_cam_duong_ca_nhan():
    rules = urllib.robotparser.RobotFileParser()
    rules.parse(["User-agent: *", "Disallow: /payment/", "Disallow: /my-tickets/"])
    assert allowed(rules, "https://ticketbox.vn/live-show-bang-kieu-26523")
    assert not allowed(rules, "https://ticketbox.vn/payment/123")
    # Chốt chặn thứ hai: /profile/ bị chặn dù robots.txt (giả lập) không ghi.
    assert not allowed(rules, "https://ticketbox.vn/profile/")


def test_suat_trung_gio_duoc_gop_lam_mot():
    from tb_reader import showing_groups
    detail = {"showings": [
        {"id": 2, "startTime": "2026-02-01T00:00:00+07:00", "ticketTypes": [
            {"name": "Vé ngày", "price": 100, "status": "sold_out", "startTime": "2026-01-10T10:00:00+07:00"}]},
        {"id": 1, "startTime": "2026-02-01T00:00:00+07:00", "ticketTypes": [
            {"name": "Vé ngày", "price": 100, "status": "book_now", "startTime": "2026-01-05T10:00:00+07:00"},
            {"name": "Vé VIP", "price": 300, "status": "sold_out"}]},
        {"id": 3, "startTime": "2026-02-02T19:00:00+07:00", "ticketTypes": []},
    ]}
    groups = showing_groups(detail)
    assert [key for key, _ in groups] == [("2026-02-01", "00:00"), ("2026-02-02", "19:00")]
    tickets = showing_tickets(groups[0][1])
    assert [(t["ten_hang"], t["gia"], t["trang_thai"]) for t in tickets] == [("Vé ngày", 100, "CON_VE"), ("Vé VIP", 300, "HET_VE")]
    # Giờ mở bán của hạng gộp là mốc sớm nhất trong các suất trùng.
    assert tickets[0]["mo_ban"] == datetime(2026, 1, 5, 10, 0, tzinfo=VN)


def test_index_rows_du_lieu_cu_con_trung_lay_ma_nho_nhat():
    from tb_reader import index_rows
    rows = [
        {"ma_su_kien": "TB003-S2", "url": "u", "ngay_dien": "2026-02-01", "gio_dien": "00:00"},
        {"ma_su_kien": "TB003-S1", "url": "u", "ngay_dien": "2026-02-01", "gio_dien": "00:00"},
    ]
    assert index_rows(rows) == {("u", "2026-02-01", "00:00"): rows[1]}


@pytest.mark.parametrize("the_loai, dia_diem, expected", [
    ("Nhạc sống", "Nhà hát Hoà Bình", "CO"),
    ("Sân khấu & Nghệ thuật", "Sân Khấu Thế Giới Trẻ", "CO"),
    ("Thể Thao", "Nhà thi đấu Phú Thọ", "CO"),
    ("Nhạc sống", "Online Event", "KHONG"),
    ("Hội thảo & Workshop", "Lá Nhà Garden", "KHONG"),
    ("Khác", "Sân vận động", "KHONG"),
    ("Tham quan & Trải nghiệm", "KidZania", "KHONG"),
    ("", "", "KHONG"),
])
def test_nhom_phan_tich(the_loai, dia_diem, expected):
    from tb_reader import nhom_phan_tich
    assert nhom_phan_tich({"the_loai": the_loai, "dia_diem": dia_diem}) == expected


def test_dia_diem_nhom_co_len_dau_va_giu_suc_chua_da_dien():
    from collect_events import venue_rows
    events = [
        {"dia_diem": "Lá Nhà Garden", "thanh_pho": "Hà Nội", "the_loai": "Hội thảo & Workshop"},
        {"dia_diem": "Nhà hát Lớn", "thanh_pho": "Hà Nội", "the_loai": "Nhạc sống"},
        {"dia_diem": "Sân khấu A", "thanh_pho": "TP. Hồ Chí Minh", "the_loai": "Sân khấu & Nghệ thuật"},
    ]
    venues = [{"dia_diem": "Nhà hát Lớn", "thanh_pho": "Hà Nội", "suc_chua": "600", "nguon_tra_cuu": "web"}]
    rows = venue_rows(venues, events)
    assert [row["dia_diem"] for row in rows] == ["Nhà hát Lớn", "Sân khấu A", "Lá Nhà Garden"]
    assert rows[0]["suc_chua"] == "600"


# --- so sánh hai lần chụp (sheet 5_so_sanh) ------------------------------------------------------

def _chup(ngay, ma, hang, trang_thai, gia="500000"):
    return {"ngay_cao": ngay, "gio_cao": "09:00", "ma_su_kien": ma, "ten_hang": hang, "trang_thai": trang_thai,
            "so_ve_con": "", "gia": gia}


def test_so_sanh_chi_so_hang_ve_co_o_ca_hai_ngay():
    from export_excel import compare_snapshots
    events = [{"ma_su_kien": "TB001", "ten_su_kien": "Show A", "the_loai": "Nhạc sống", "nhom_phan_tich": "CO",
               "ngay_dien": "2026-12-01"}]
    history = [
        _chup("2026-11-03", "TB001", "VIP", "CON_VE"), _chup("2026-11-18", "TB001", "VIP", "HET_VE"),
        _chup("2026-11-03", "TB001", "GA", "CHUA_MO_BAN"), _chup("2026-11-18", "TB001", "GA", "CON_VE"),
        _chup("2026-11-03", "TB001", "SVIP", "HET_VE"), _chup("2026-11-18", "TB001", "SVIP", "HET_VE"),
        # Suất đã diễn trước ngày sau: không có dòng ngày sau, không được tính là đổi trạng thái.
        _chup("2026-11-03", "TB002", "VIP", "CON_VE"),
        # Lần chụp ở giữa không ảnh hưởng: chỉ so ngày đầu với ngày gần nhất.
        _chup("2026-11-10", "TB001", "VIP", "CON_VE"),
    ]
    result = compare_snapshots(events, history)
    assert (result["ngay_dau"], result["ngay_cuoi"], result["so_ngay_chup"]) == ("2026-11-03", "2026-11-18", 3)
    assert result["ca_hai"] == 3 and result["chi_ngay_dau"] == 1 and result["chi_ngay_cuoi"] == 0
    assert [(row["ten_hang"], row["trang_thai_dau"], row["trang_thai_cuoi"]) for row in result["thay_doi"]] == [
        ("GA", "CHUA_MO_BAN", "CON_VE"), ("VIP", "CON_VE", "HET_VE")]
    assert [row["ten_hang"] for row in result["moi_het_ve"]] == ["VIP"]
    assert result["moi_het_ve"][0]["the_loai"] == "Nhạc sống"
    assert result["con_ve_dau"] == 2


def test_so_sanh_can_it_nhat_hai_ngay():
    from export_excel import compare_snapshots
    assert compare_snapshots([], [_chup("2026-11-03", "TB001", "VIP", "CON_VE")]) is None


@pytest.mark.parametrize("gia, expected", [
    ("0", "Miễn phí"), ("299999", "Dưới 300.000"), ("300000", "300.000 – dưới 1 triệu"),
    ("1000000", "1 – dưới 3 triệu"), ("3000000", "Từ 3 triệu"), ("", "(không rõ giá)"),
])
def test_khoang_gia(gia, expected):
    from export_excel import khoang_gia
    assert khoang_gia(gia) == expected
