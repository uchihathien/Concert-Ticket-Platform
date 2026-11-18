"""Kiểm thử các phần của train.py không cần huấn luyện: nhãn, chống rò rỉ, chia tập, đánh giá, hòa vốn."""

import numpy as np
import pytest

from train import (
    chia_theo_thoi_gian, nguong_tu_chi_phi, nguong_tu_so_ve, nhan_sau_dien, pinball, sap_xep_quantile,
    tin_hieu_som, ty_le_gia_tri_het, xac_suat_hoa_von,
)


def _hang(trang_thai, gia):
    return {"trang_thai": trang_thai, "gia": str(gia)}


def test_nhan_la_ty_le_gia_tri_hang_het_ve():
    rows = [_hang("HET_VE", 300), _hang("CON_VE", 100), _hang("NGUNG_BAN", 600)]
    assert ty_le_gia_tri_het(rows) == pytest.approx(0.3)
    # Suất miễn phí hoàn toàn: không định nghĩa được nhãn.
    assert ty_le_gia_tri_het([_hang("HET_VE", 0)]) is None


def test_nhan_lay_lan_chup_dau_tien_sau_ngay_dien():
    event = {"ngay_dien": "2026-11-01"}
    chup = {
        "2026-11-01": [_hang("CON_VE", 100)],              # chính ngày diễn: chưa phải sau buổi diễn
        "2026-11-03": [_hang("HET_VE", 100)],
        "2026-11-18": [_hang("CON_VE", 100)],
    }
    assert nhan_sau_dien(event, chup) == ("2026-11-03", 1.0)
    assert nhan_sau_dien({"ngay_dien": "2026-11-20"}, chup) is None


def test_tin_hieu_som_khong_dung_lan_chup_sau_7_ngay():
    event = {"ngay_mo_ban": "2026-10-01", "ngay_dien": "2026-10-30"}
    chup = {
        "2026-10-05": [_hang("CON_VE", 100), _hang("HET_VE", 100)],
        "2026-10-20": [_hang("HET_VE", 100), _hang("HET_VE", 100)],   # tương lai so với mốc 7 ngày
    }
    assert tin_hieu_som(event, chup) == pytest.approx(0.5)
    assert tin_hieu_som(event, {"2026-10-20": chup["2026-10-20"]}) is None


def test_chia_theo_thoi_gian_khong_chia_doi_mot_ngay():
    rows = [{"ngay_dien": f"2026-10-{d:02d}"} for d in (1, 2, 3, 4, 5, 6, 7, 8, 8, 8, 9, 10)]
    train, test, ngay_cat = chia_theo_thoi_gian(rows, 0.25)
    assert max(r["ngay_dien"] for r in train) < ngay_cat <= min(r["ngay_dien"] for r in test)
    assert len(train) + len(test) == len(rows)


def test_pinball_loss():
    y = np.array([1.0, 0.0])
    assert pinball(y, np.array([0.0, 0.0]), 0.9) == pytest.approx(0.45)
    assert pinball(y, np.array([1.0, 1.0]), 0.9) == pytest.approx(0.05)


def test_sap_xep_quantile_bat_cheo_va_cat_trong_0_1():
    pred = sap_xep_quantile({0.1: np.array([0.6]), 0.5: np.array([0.4]), 0.9: np.array([1.3])})
    assert [float(pred[a][0]) for a in (0.1, 0.5, 0.9)] == [0.4, 0.6, 1.0]


def test_xac_suat_hoa_von_dung_tai_cac_quantile_va_giam_dan():
    assert xac_suat_hoa_von(0.2, 0.5, 0.8, 0.5) == pytest.approx(0.5)
    assert xac_suat_hoa_von(0.2, 0.5, 0.8, 0.2) == pytest.approx(0.9)
    assert xac_suat_hoa_von(0.2, 0.5, 0.8, 0.8) == pytest.approx(0.1)
    assert xac_suat_hoa_von(0.2, 0.5, 0.8, 0.0) == 1.0
    assert xac_suat_hoa_von(0.2, 0.5, 0.8, 1.01) == 0.0
    values = [xac_suat_hoa_von(0.1, 0.3, 0.9, t) for t in np.linspace(0, 1, 21)]
    assert all(a >= b for a, b in zip(values, values[1:]))


def test_xac_suat_hoa_von_khi_quantile_trung_nhau():
    # q10 = q50 = 0: một nửa xác suất dồn ở 0, nên P(nhãn > 0 một chút) ≈ 0,5.
    assert xac_suat_hoa_von(0.0, 0.0, 1.0, 1e-9) == pytest.approx(0.5)
    assert xac_suat_hoa_von(0.0, 0.0, 1.0, 0.6) == pytest.approx(0.26)


def test_nguong_hoa_von():
    assert nguong_tu_so_ve(600, 1000) == pytest.approx(0.6)
    assert nguong_tu_chi_phi(400, 1000) == pytest.approx(0.4)
    assert nguong_tu_so_ve(1500, 1000) == 1.0
    with pytest.raises(ValueError):
        nguong_tu_so_ve(10, 0)
