"""Kiểm thử các hàm chuẩn hoá của normalize.py — chạy: .venv/bin/python -m pytest tests"""

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normalize import (  # noqa: E402
    capacities,
    clean_title,
    make_zone_code,
    map_category,
    normalize_city,
    parse_price,
    parse_vn_datetime,
    shift_to_future,
    summarize,
    truncate_words,
)

VN = timezone(timedelta(hours=7))


# --- giá -----------------------------------------------------------------------


@pytest.mark.parametrize(
    "value, expected",
    [
        ("1.500.000 đ", 1_500_000),
        ("1.500.000đ", 1_500_000),
        ("1,500,000 VND", 1_500_000),
        ("1,5 triệu", 1_500_000),
        ("2 triệu", 2_000_000),
        ("1.5tr", 1_500_000),
        ("500k", 500_000),
        ("Miễn phí", 0),
        ("miễn phí vào cửa", 0),
        ("Free", 0),
        (2_100_000, 2_100_000),
        (0, 0),
        (None, None),
        ("Liên hệ", None),
        ("", None),
        (-5, None),
    ],
)
def test_parse_price(value, expected):
    assert parse_price(value) == expected


# --- ngày giờ -------------------------------------------------------------------


@pytest.mark.parametrize(
    "value, expected",
    [
        ("19:30, 15 tháng 11, 2025", datetime(2025, 11, 15, 19, 30, tzinfo=VN)),
        ("18:00 - 20:30, 29 Tháng 11, 2026", datetime(2026, 11, 29, 18, 0, tzinfo=VN)),
        ("T7, 15/11/2025", datetime(2025, 11, 15, 0, 0, tzinfo=VN)),
        ("15/11/2025 19:30", datetime(2025, 11, 15, 19, 30, tzinfo=VN)),
        ("2026-11-29T11:00:00Z", datetime(2026, 11, 29, 18, 0, tzinfo=VN)),
        ("2026-10-24T17:00:00+07:00", datetime(2026, 10, 24, 17, 0, tzinfo=VN)),
    ],
)
def test_parse_vn_datetime(value, expected):
    assert parse_vn_datetime(value) == expected


def test_parse_vn_datetime_without_year_uses_default():
    assert parse_vn_datetime("20:00 Thứ Bảy 24.10", default_year=2026) == datetime(2026, 10, 24, 20, 0, tzinfo=VN)


def test_parse_vn_datetime_rejects_garbage():
    assert parse_vn_datetime("sắp công bố") is None
    assert parse_vn_datetime("31/02/2026") is None


def test_shift_to_future_keeps_weekday_and_time():
    now = datetime(2026, 10, 22, 12, 0, tzinfo=VN)
    past = datetime(2025, 11, 15, 19, 30, tzinfo=VN)  # thứ Bảy
    moved, weeks = shift_to_future(past, now)
    assert moved > now + timedelta(days=1)
    assert moved.weekday() == past.weekday()
    assert (moved.hour, moved.minute) == (19, 30)
    assert moved - past == timedelta(weeks=weeks)
    assert moved - timedelta(weeks=1) <= now + timedelta(days=1)  # đúng số tuần tối thiểu


def test_shift_to_future_leaves_future_alone():
    now = datetime(2026, 10, 22, 12, 0, tzinfo=VN)
    future = datetime(2026, 12, 26, 19, 0, tzinfo=VN)
    assert shift_to_future(future, now) == (future, 0)


# --- thành phố ---------------------------------------------------------------------


@pytest.mark.parametrize(
    "value",
    ["Hồ Chí Minh", "TP.HCM", "HCM", "Sài Gòn", "Thành phố Hồ Chí Minh", "TP. Hồ Chí Minh", "tp hcm"],
)
def test_normalize_city_hcm(value):
    assert normalize_city(value) == "TP. Hồ Chí Minh"


@pytest.mark.parametrize(
    "value, expected",
    [
        ("Thành phố Hà Nội", "Hà Nội"),
        ("Ha Noi", "Hà Nội"),
        ("Thành phố Đà Nẵng", "Đà Nẵng"),
        ("Thừa Thiên Huế", "Huế"),
        ("Tỉnh Hưng Yên", "Hưng Yên"),
        ("", None),
        (None, None),
    ],
)
def test_normalize_city_others(value, expected):
    assert normalize_city(value) == expected


def test_normalize_city_prefers_spelling_already_in_system():
    known = {"ho chi minh": "Hồ Chí Minh (HCM)"}
    assert normalize_city("Sài Gòn", known) == "Hồ Chí Minh (HCM)"


# --- thể loại ------------------------------------------------------------------------


@pytest.mark.parametrize(
    "code, name, title, expected",
    [
        ("music", "Nhạc sống", "Đêm nhạc", ("nhac-song", "mã")),
        ("theatersandart", None, "", ("san-khau", "mã")),
        ("sport", None, "", ("the-thao", "mã")),
        ("seminarsworkshops", None, "", ("hoi-thao", "mã")),
        ("others", "Khác", "[LEMLAB] Mini Workshop VẼ MÓC KHÓA", ("hoi-thao", "đoán theo tiêu đề")),
        ("attractionsexperiences", "Tham quan & Trải nghiệm", "Vé Trải Nghiệm GenZania 2 tiếng", ("khac", "mặc định")),
        (None, "Live music", "", ("nhac-song", "tên thể loại")),
        (None, None, "Giải chạy Marathon Đà Lạt", ("the-thao", "đoán theo tiêu đề")),
        (None, None, "Triển lãm tranh", ("san-khau", "đoán theo tiêu đề")),
    ],
)
def test_map_category(code, name, title, expected):
    assert map_category(code, name, title) == expected


# --- zoneCode ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "name, expected",
    [
        ("Vé VIP", "VIP"),
        ("Hạng Đồng", "HANG-DONG"),
        ("SUPER VIP", "SUPER-VIP"),
        ("Zone 1", "ZONE-1"),
        ("Đứng – GA (early bird)", "DUNG-GA-EARLY"),
        ("!!!", "KHU"),
    ],
)
def test_make_zone_code(name, expected):
    code = make_zone_code(name, set())
    assert code == expected
    assert len(code) <= 16


def test_make_zone_code_unique_within_event():
    taken: set[str] = set()
    codes = [make_zone_code("VIP", taken), make_zone_code("Vé VIP", taken), make_zone_code("vip", taken)]
    assert codes == ["VIP", "VIP-2", "VIP-3"]


def test_make_zone_code_long_name_stays_within_16():
    code = make_zone_code("Hạng Ghế Ngồi Khán Đài Phía Đông Tầng Hai", set())
    assert len(code) <= 16 and not code.endswith("-")


# --- phụ trợ ---------------------------------------------------------------------------


def test_capacities_cheapest_most_seats():
    assert capacities(1) == [500]
    assert capacities(2) == [500, 50]
    sizes = capacities(4)
    assert sizes[0] == 500 and sizes[-1] == 50 and sizes == sorted(sizes, reverse=True)


def test_clean_title_drops_city_tag_and_emoji():
    assert clean_title("[HCM] 🎤 Đêm nhạc   Mùa Thu ✨") == "Đêm nhạc Mùa Thu"
    assert clean_title("[CAT&MOUSE] THANH DUY") == "[CAT&MOUSE] THANH DUY"


def test_truncate_words_never_cuts_mid_word():
    text = "một hai ba bốn năm sáu bảy"
    cut = truncate_words(text, 12)
    assert cut.endswith("…") and len(cut) <= 12 and cut[:-1] in text and not cut[:-1].endswith(" ")


def test_summarize_takes_first_sentences():
    summary = summarize("<p>Câu một.</p><p>Câu hai! Câu ba? Câu bốn.</p>")
    assert summary == "Câu một. Câu hai! Câu ba?"
