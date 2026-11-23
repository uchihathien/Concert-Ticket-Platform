"""Kiểm thử công cụ dự báo: kiểm tra đầu vào, kết quả dự báo và API HTTP (kể cả thời gian phản hồi).

Mô hình được huấn luyện nhỏ trên dữ liệu giả lập trong thư mục tạm, nên test không phụ thuộc models/ thật.
"""

import json
import threading
import time
import urllib.error
import urllib.request
from datetime import date, timedelta
from http.server import ThreadingHTTPServer

import numpy as np
import pytest

from du_bao_api import LoiDuLieu, MoHinh, kiem_tra_dau_vao, tao_handler
from train import chon_dac_trung, dac_trung, huan_luyen, luu, ma_tran, tao_bo_ma_hoa

HOP_LE = {"the_loai": "Nhạc sống", "thanh_pho": "Hà Nội", "gia_min": 500000, "gia_max": 2000000,
          "so_hang_ve": 4, "so_suat_su_kien": 1, "ngay_mo_ban": "2026-12-01", "ngay_dien": "2027-01-15",
          "gio_dien": "19:30", "chi_phi": 600_000_000, "doanh_thu_toi_da": 1_000_000_000}


@pytest.fixture(scope="module")
def mo_hinh(tmp_path_factory):
    rng = np.random.default_rng(0)
    rows = []
    for i in range(80):
        mo_ban = date(2026, 1, 1) + timedelta(days=int(i))
        su_kien = {"the_loai": ["Nhạc sống", "Thể Thao"][i % 2], "thanh_pho": ["Hà Nội", "Đà Nẵng"][i % 3 == 0],
                   "gia_min": 100000 * (1 + i % 5), "gia_max": 500000 * (1 + i % 4), "so_hang_ve": 1 + i % 6,
                   "ngay_mo_ban": mo_ban.isoformat(), "ngay_dien": (mo_ban + timedelta(days=30)).isoformat(),
                   "gio_dien": "20:00"}
        row = dac_trung(su_kien, {}, 1, None)
        row["nhan"] = float(rng.choice([0.0, 0.4, 1.0]))
        rows.append(row)
    features, _ = chon_dac_trung(rows)
    encoders = tao_bo_ma_hoa(rows, features)
    models = huan_luyen(ma_tran(rows, features, encoders), np.array([r["nhan"] for r in rows]), features, encoders)
    out = tmp_path_factory.mktemp("models")
    luu(models, features, encoders, {}, out)
    return MoHinh(out)


@pytest.mark.parametrize("sua, thong_bao", [
    ({"gia_min": None}, "Thiếu trường"),
    ({"gia_min": "abc"}, "phải là số"),
    ({"gia_min": 3_000_000}, "không được lớn hơn"),
    ({"ngay_mo_ban": "2027-02-01"}, "trước hoặc bằng ngày diễn"),
    ({"ngay_dien": "15/01/2027"}, "YYYY-MM-DD"),
    ({"gio_dien": "7h"}, "HH:MM"),
    ({"doanh_thu_toi_da": 0}, "lớn hơn 0"),
])
def test_tu_choi_dau_vao_sai(sua, thong_bao):
    with pytest.raises(LoiDuLieu, match=thong_bao):
        kiem_tra_dau_vao({**HOP_LE, **sua})


def test_du_bao_tra_ve_phan_vi_co_thu_tu_va_xac_suat_hop_le(mo_hinh):
    kq = mo_hinh.du_bao(HOP_LE)
    assert 0 <= kq["q10"] <= kq["q50"] <= kq["q90"] <= 1
    assert kq["nguong"] == pytest.approx(0.6)
    assert 0 <= kq["xac_suat_dat_nguong"] <= 1
    assert kq["canh_bao"] == []


def test_chi_phi_lon_hon_doanh_thu_thi_xac_suat_bang_0(mo_hinh):
    kq = mo_hinh.du_bao({**HOP_LE, "chi_phi": 2_000_000_000})
    assert kq["xac_suat_dat_nguong"] == 0.0 and kq["canh_bao"]


def test_the_loai_moi_van_du_bao_kem_canh_bao(mo_hinh):
    kq = mo_hinh.du_bao({**HOP_LE, "the_loai": "Hội chợ"})
    assert 0 <= kq["q50"] <= 1 and "chưa có" in kq["canh_bao"][0]


def test_xac_suat_giam_khi_nguong_tang(mo_hinh):
    p = [mo_hinh.du_bao({**HOP_LE, "chi_phi": c})["xac_suat_dat_nguong"] for c in (2e8, 5e8, 8e8)]
    assert p[0] >= p[1] >= p[2]


@pytest.fixture(scope="module")
def may_chu(mo_hinh):
    server = ThreadingHTTPServer(("127.0.0.1", 0), tao_handler(mo_hinh))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


def _post(url, body: bytes):
    req = urllib.request.Request(url + "/api/du-bao", data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read())


def test_api_trang_chu_va_thong_tin(may_chu):
    with urllib.request.urlopen(may_chu + "/", timeout=5) as resp:
        assert resp.status == 200 and "Dự báo" in resp.read().decode("utf-8")
    with urllib.request.urlopen(may_chu + "/api/thong-tin", timeout=5) as resp:
        info = json.loads(resp.read())
    assert "Nhạc sống" in info["the_loai"] and "Hà Nội" in info["thanh_pho"]


def test_api_du_bao_va_loi(may_chu):
    status, kq = _post(may_chu, json.dumps(HOP_LE).encode())
    assert status == 200 and 0 <= kq["xac_suat_dat_nguong"] <= 1
    assert _post(may_chu, b"{khong-phai-json")[0] == 400
    status, kq = _post(may_chu, json.dumps({**HOP_LE, "gia_min": None}).encode())
    assert status == 400 and "Thiếu" in kq["loi"]


def test_api_thoi_gian_phan_hoi_duoi_200ms(may_chu):
    body = json.dumps(HOP_LE).encode()
    _post(may_chu, body)  # lần đầu khởi động
    thoi_gian = []
    for _ in range(50):
        bat_dau = time.perf_counter()
        assert _post(may_chu, body)[0] == 200
        thoi_gian.append(time.perf_counter() - bat_dau)
    assert float(np.percentile(thoi_gian, 95)) < 0.2


def test_xep_hang_so_voi_tham_chieu_o_cung_nguong():
    from du_bao_api import xep_hang
    tham_chieu = [(0.0, 0.0, 0.2), (0.0, 0.5, 1.0), (0.5, 0.9, 1.0)]
    # Mọi suất tham chiếu đều có xác suất dương ở ngưỡng 0,6 (nội suy cho phần đuôi một ít xác suất).
    assert xep_hang(0.0, 0.6, tham_chieu) == pytest.approx(0.0)
    assert xep_hang(1.0, 0.6, tham_chieu) == pytest.approx(1.0)
    # Bằng đúng xác suất của suất thứ hai: một suất thấp hơn, một suất bằng (tính một nửa).
    from train import xac_suat_hoa_von
    giua = xac_suat_hoa_von(0.0, 0.5, 1.0, 0.6)
    assert xep_hang(giua, 0.6, tham_chieu) == pytest.approx(1.5 / 3)


def test_du_bao_tra_xep_hang_khi_co_tham_chieu(mo_hinh):
    mo_hinh.tham_chieu = [(0.0, 0.0, 0.2), (0.0, 0.5, 1.0), (0.5, 0.9, 1.0)]
    kq = mo_hinh.du_bao(HOP_LE)
    assert 0 <= kq["xep_hang"] <= 1 and kq["so_tham_chieu"] == 3
    mo_hinh.tham_chieu = []
    assert mo_hinh.du_bao(HOP_LE)["xep_hang"] is None
