#!/usr/bin/env python3
"""Huấn luyện THỬ LightGBM quantile regression cho đề tài dự báo sức mua vé và xác suất hòa vốn.

Đây là bản kiểm tra quy trình, chưa phải kết quả cuối. Có thêm dữ liệu thì chỉ cần chạy lại:

    .venv/bin/python train.py

Đơn vị dữ liệu: một SUẤT nhóm phân tích CO đã diễn và đã được chụp trạng thái SAU buổi diễn.

Nhãn (biến mục tiêu) — "tỷ lệ giá trị vé hết":
    tổng giá các hạng HET_VE / tổng giá mọi hạng của suất, ở lần chụp đầu tiên sau ngày diễn.
Ticketbox không công khai số vé bán ra, nên nhãn này là ĐẠI LƯỢNG THAY THẾ cho sức mua: 0 là không
hạng nào hết, 1 là mọi hạng đều hết; hạng đắt có trọng số lớn hơn nên gần với ý nghĩa doanh thu.

Chống rò rỉ dữ liệu: đặc trưng chỉ dùng thông tin có trước hoặc ngay khi mở bán (thể loại, thành
phố, sức chứa, giá, số hạng vé, lịch diễn) và tín hiệu sớm trong 7 ngày đầu mở bán. KHÔNG dùng
trang_thai_cuoi, ngay_thay_lan_dau (với suất đã diễn đó là ngày chụp sau buổi diễn), hay bất kỳ lần
chụp nào sau mốc 7 ngày. Đặc trưng có quá ít giá trị trong tập train (ví dụ sức chứa khi chưa tra)
được bỏ tự động và ghi vào features.json, để khi dữ liệu đủ thì tự được dùng lại.

Kết quả ghi ở models/: model_q10.txt, model_q50.txt, model_q90.txt, features.json, encoders.json,
du_doan_test.csv và bao_cao.json.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

import lightgbm as lgb
import numpy as np

from tb_reader import HERE, SU_KIEN_CSV, SUC_CHUA_CSV, TRANG_THAI_CSV, fold, read_csv

MODEL_DIR = HERE / "models"
ALPHAS = (0.1, 0.5, 0.9)
NGAY_TIN_HIEU_SOM = 7
# Đặc trưng có giá trị ở ít hơn tỷ lệ này của tập train thì bỏ (tránh học từ vài dòng lẻ).
DO_PHU_TOI_THIEU = 0.3
THANH_PHO_RIENG = ("TP. Hồ Chí Minh", "Hà Nội")

DAC_TRUNG_SO = [
    "suc_chua", "gia_min", "gia_max", "ty_le_gia_max_min", "so_hang_ve", "so_suat_su_kien",
    "so_ngay_mo_ban_den_dien", "thu_trong_tuan", "gio_dien", "ty_le_het_7_ngay",
]
DAC_TRUNG_PHAN_LOAI = ["the_loai", "thanh_pho_nhom"]

# Ít dữ liệu (vài chục dòng) nên cây nông, lá lớn; một luồng + deterministic để chạy lại ra y hệt.
LGB_PARAMS = {
    "objective": "quantile", "learning_rate": 0.05, "num_leaves": 7, "max_depth": 3,
    "min_data_in_leaf": 5, "min_data_in_bin": 3, "min_data_per_group": 5, "cat_smooth": 5,
    "feature_pre_filter": False, "deterministic": True, "num_threads": 1, "seed": 42, "verbose": -1,
}
SO_VONG = 200


# --- nhãn và đặc trưng ---------------------------------------------------------------------------

def _ngay(value: str):
    try:
        return date.fromisoformat(value)
    except (TypeError, ValueError):
        return None


def _so(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def ty_le_gia_tri_het(rows: list):
    """Tổng giá hạng HET_VE / tổng giá mọi hạng của một lần chụp. None khi suất miễn phí hoàn toàn."""
    tong = het = 0.0
    for row in rows:
        gia = _so(row.get("gia")) or 0.0
        tong += gia
        if row.get("trang_thai") == "HET_VE":
            het += gia
    return het / tong if tong > 0 else None


def chup_theo_suat(history: list) -> dict:
    """{mã suất: {ngày chụp: [dòng]}}"""
    result: dict = defaultdict(lambda: defaultdict(list))
    for row in history:
        result[row["ma_su_kien"]][row["ngay_cao"]].append(row)
    return result


def nhan_sau_dien(event: dict, chup: dict):
    """(ngày chụp, nhãn) ở lần chụp ĐẦU TIÊN sau ngày diễn; None nếu chưa có lần chụp như vậy."""
    sau = sorted(day for day in chup if day > event["ngay_dien"])
    if not sau:
        return None
    value = ty_le_gia_tri_het(chup[sau[0]])
    return None if value is None else (sau[0], value)


def tin_hieu_som(event: dict, chup: dict):
    """Nhãn tính ở lần chụp muộn nhất trong 7 ngày đầu mở bán (và trước ngày diễn); None nếu không có."""
    mo_ban, dien = _ngay(event.get("ngay_mo_ban")), _ngay(event.get("ngay_dien"))
    if mo_ban is None or dien is None:
        return None
    han = min(mo_ban + timedelta(days=NGAY_TIN_HIEU_SOM), dien - timedelta(days=1)).isoformat()
    trong_han = sorted(day for day in chup if mo_ban.isoformat() <= day <= han)
    return ty_le_gia_tri_het(chup[trong_han[-1]]) if trong_han else None


def dac_trung(event: dict, chup: dict, so_suat: int, suc_chua) -> dict:
    gia_min, gia_max = _so(event.get("gia_min")), _so(event.get("gia_max"))
    mo_ban, dien = _ngay(event.get("ngay_mo_ban")), _ngay(event.get("ngay_dien"))
    gio = event.get("gio_dien") or ""
    try:
        gio_dien = int(gio[:2]) + int(gio[3:5]) / 60
    except ValueError:
        gio_dien = None
    return {
        "suc_chua": suc_chua,
        "gia_min": gia_min,
        "gia_max": gia_max,
        "ty_le_gia_max_min": gia_max / gia_min if gia_min and gia_max else None,
        "so_hang_ve": _so(event.get("so_hang_ve")),
        "so_suat_su_kien": float(so_suat),
        "so_ngay_mo_ban_den_dien": float((dien - mo_ban).days) if mo_ban and dien else None,
        "thu_trong_tuan": float(dien.weekday()) if dien else None,
        "gio_dien": gio_dien,
        "ty_le_het_7_ngay": tin_hieu_som(event, chup),
        "the_loai": event.get("the_loai") or "(không rõ)",
        "thanh_pho_nhom": event.get("thanh_pho") if event.get("thanh_pho") in THANH_PHO_RIENG else "Khác",
    }


def tao_bang(events: list, history: list, venues: list) -> list:
    """Mỗi dòng: một suất nhóm CO đã có nhãn, gồm mã, ngày diễn, đặc trưng và nhãn."""
    chup = chup_theo_suat(history)
    so_suat = defaultdict(int)
    for event in events:
        so_suat[event["url"]] += 1
    suc_chua = {(fold(v["dia_diem"]), fold(v["thanh_pho"])): _so(v.get("suc_chua")) for v in venues}
    rows = []
    for event in events:
        if event.get("nhom_phan_tich") != "CO":
            continue
        nhan = nhan_sau_dien(event, chup.get(event["ma_su_kien"], {}))
        if nhan is None:
            continue
        cap = _so(event.get("suc_chua")) or suc_chua.get((fold(event["dia_diem"]), fold(event["thanh_pho"])))
        rows.append({"ma_su_kien": event["ma_su_kien"], "ten_su_kien": event["ten_su_kien"],
                     "ngay_dien": event["ngay_dien"], "ngay_nhan": nhan[0], "nhan": nhan[1],
                     **dac_trung(event, chup.get(event["ma_su_kien"], {}), so_suat[event["url"]], cap)})
    return sorted(rows, key=lambda row: (row["ngay_dien"], row["ma_su_kien"]))


# --- chia tập, mã hoá ----------------------------------------------------------------------------

def chia_theo_thoi_gian(rows: list, ti_le_test: float) -> tuple:
    """Suất diễn sớm để train, muộn để test. Cắt theo NGÀY nên một ngày không bị chia đôi."""
    if len(rows) < 10:
        raise ValueError(f"Chỉ có {len(rows)} suất có nhãn — quá ít để chia train/test.")
    ngay_cat = rows[int(len(rows) * (1 - ti_le_test))]["ngay_dien"]
    train = [row for row in rows if row["ngay_dien"] < ngay_cat]
    test = [row for row in rows if row["ngay_dien"] >= ngay_cat]
    return train, test, ngay_cat


def chon_dac_trung(train: list) -> tuple:
    """(đặc trưng dùng, {đặc trưng bỏ: lý do}) theo độ phủ trong tập train."""
    dung, bo = [], {}
    for name in DAC_TRUNG_SO + DAC_TRUNG_PHAN_LOAI:
        phu = sum(row[name] is not None for row in train) / len(train)
        if phu < DO_PHU_TOI_THIEU:
            bo[name] = f"chỉ {phu:.0%} dòng train có giá trị (cần ≥ {DO_PHU_TOI_THIEU:.0%})"
        else:
            dung.append(name)
    return dung, bo


def tao_bo_ma_hoa(train: list, features: list) -> dict:
    """{đặc trưng phân loại: [giá trị đã gặp trong train]} — chỉ số trong danh sách là mã."""
    return {name: sorted({row[name] for row in train}) for name in features if name in DAC_TRUNG_PHAN_LOAI}


def ma_tran(rows: list, features: list, encoders: dict) -> np.ndarray:
    """Giá trị phân loại chưa gặp khi train thành NaN — LightGBM xử lý như thiếu."""
    data = np.full((len(rows), len(features)), np.nan)
    for i, row in enumerate(rows):
        for j, name in enumerate(features):
            value = row[name]
            if name in encoders:
                data[i, j] = encoders[name].index(value) if value in encoders[name] else np.nan
            elif value is not None:
                data[i, j] = value
    return data


# --- mô hình, mốc so sánh, đánh giá --------------------------------------------------------------

def huan_luyen(x: np.ndarray, y: np.ndarray, features: list, encoders: dict) -> dict:
    categorical = [name for name in features if name in encoders]
    models = {}
    for alpha in ALPHAS:
        dataset = lgb.Dataset(x, label=y, feature_name=features, categorical_feature=categorical, free_raw_data=False)
        models[alpha] = lgb.train({**LGB_PARAMS, "alpha": alpha}, dataset, num_boost_round=SO_VONG)
    return models


def sap_xep_quantile(pred: dict) -> dict:
    """Ép q10 ≤ q50 ≤ q90 và nằm trong [0, 1]: ba mô hình độc lập có thể cho quantile 'bắt chéo'."""
    stacked = np.clip(np.sort(np.vstack([pred[a] for a in ALPHAS]), axis=0), 0.0, 1.0)
    return {alpha: stacked[i] for i, alpha in enumerate(ALPHAS)}


def du_doan(models: dict, x: np.ndarray) -> dict:
    return sap_xep_quantile({alpha: model.predict(x) for alpha, model in models.items()})


def moc_theo_the_loai(train: list, test: list) -> dict:
    """Mốc so sánh: quantile của nhãn trong train theo thể loại (thiếu thể loại thì dùng toàn bộ train)."""
    nhom = defaultdict(list)
    for row in train:
        nhom[row["the_loai"]].append(row["nhan"])
    tat_ca = [row["nhan"] for row in train]
    return {alpha: np.array([float(np.quantile(nhom.get(row["the_loai"]) or tat_ca, alpha)) for row in test])
            for alpha in ALPHAS}


def pinball(y: np.ndarray, q: np.ndarray, alpha: float) -> float:
    diff = y - q
    return float(np.mean(np.maximum(alpha * diff, (alpha - 1) * diff)))


def danh_gia(y: np.ndarray, pred: dict) -> dict:
    return {
        "pinball_q10": pinball(y, pred[0.1], 0.1),
        "pinball_q50": pinball(y, pred[0.5], 0.5),
        "pinball_q90": pinball(y, pred[0.9], 0.9),
        "mae_q50": float(np.mean(np.abs(y - pred[0.5]))),
        "ty_le_trong_q10_q90": float(np.mean((y >= pred[0.1]) & (y <= pred[0.9]))),
        "do_rong_tb_q10_q90": float(np.mean(pred[0.9] - pred[0.1])),
    }


# --- xác suất hòa vốn ----------------------------------------------------------------------------

def nguong_tu_so_ve(so_ve_hoa_von: float, tong_so_ve: float) -> float:
    """Ngưỡng hòa vốn theo tỷ lệ: số vé cần bán để hòa vốn / tổng số vé (sức chứa đem bán).

    Nhãn là tỷ lệ GIÁ TRỊ vé hết nên phép quy đổi này chỉ gần đúng khi các hạng vé có số lượng tương
    đương; khi biết chi phí và doanh thu tối đa thì dùng nguong_tu_chi_phi chính xác hơn.
    """
    if tong_so_ve <= 0:
        raise ValueError("tong_so_ve phải lớn hơn 0")
    return min(max(so_ve_hoa_von / tong_so_ve, 0.0), 1.0)


def nguong_tu_chi_phi(chi_phi: float, doanh_thu_toi_da: float) -> float:
    """Ngưỡng hòa vốn = chi phí / doanh thu nếu bán hết mọi hạng."""
    if doanh_thu_toi_da <= 0:
        raise ValueError("doanh_thu_toi_da phải lớn hơn 0")
    return min(max(chi_phi / doanh_thu_toi_da, 0.0), 1.0)


def xac_suat_hoa_von(q10: float, q50: float, q90: float, nguong: float) -> float:
    """P(nhãn ≥ ngưỡng) từ ba quantile.

    Dựng hàm phân phối tích luỹ tuyến tính từng đoạn qua các điểm (0; 0), (q10; 0,1), (q50; 0,5),
    (q90; 0,9), (1; 1) — nhãn nằm trong [0, 1] nên hai đầu đuôi chặn ở 0 và 1. Hai quantile trùng nhau
    (ví dụ q10 = q50 = 0) nghĩa là có một khối xác suất dồn tại điểm đó, và được tính đúng như vậy.
    """
    q10, q50, q90 = sorted(min(max(v, 0.0), 1.0) for v in (q10, q50, q90))
    if nguong <= 0:
        return 1.0
    if nguong > 1:
        return 0.0
    xs, ps = (0.0, q10, q50, q90, 1.0), (0.0, 0.1, 0.5, 0.9, 1.0)
    # F(ngưỡng⁻): xác suất nhãn NHỎ HƠN ngưỡng, lấy theo đoạn có độ dài dương chứa ngưỡng.
    duoi = 0.0
    for i in range(4):
        x0, x1 = xs[i], xs[i + 1]
        if x1 > x0 and x0 < nguong <= x1:
            duoi = ps[i] + (ps[i + 1] - ps[i]) * (nguong - x0) / (x1 - x0)
            break
    return float(min(max(1.0 - duoi, 0.0), 1.0))


# --- lưu và mở lại -------------------------------------------------------------------------------

def luu(models: dict, features: list, encoders: dict, meta: dict, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    for alpha, model in models.items():
        model.save_model(str(out / f"model_q{int(alpha * 100)}.txt"))
    (out / "features.json").write_text(json.dumps({"dac_trung": features, **meta}, ensure_ascii=False, indent=2),
                                       encoding="utf-8")
    (out / "encoders.json").write_text(json.dumps(encoders, ensure_ascii=False, indent=2), encoding="utf-8")


def mo_lai(out: Path) -> tuple:
    features = json.loads((out / "features.json").read_text(encoding="utf-8"))["dac_trung"]
    encoders = json.loads((out / "encoders.json").read_text(encoding="utf-8"))
    models = {alpha: lgb.Booster(model_file=str(out / f"model_q{int(alpha * 100)}.txt")) for alpha in ALPHAS}
    return models, features, encoders


# --- chạy ----------------------------------------------------------------------------------------

def _f(value: float) -> str:
    return f"{value:.3f}"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Huấn luyện thử LightGBM quantile (q10, q50, q90).")
    parser.add_argument("--ti-le-test", type=float, default=0.2, help="tỷ lệ suất muộn nhất dùng làm test")
    parser.add_argument("--thu-muc", type=Path, default=MODEL_DIR, help="nơi lưu mô hình")
    args = parser.parse_args(argv)

    rows = tao_bang(read_csv(SU_KIEN_CSV), read_csv(TRANG_THAI_CSV), read_csv(SUC_CHUA_CSV))
    train, test, ngay_cat = chia_theo_thoi_gian(rows, args.ti_le_test)
    features, bo = chon_dac_trung(train)
    encoders = tao_bo_ma_hoa(train, features)
    print(f"Suất nhóm CO có nhãn: {len(rows)} — train {len(train)} (diễn trước {ngay_cat}), "
          f"test {len(test)} (diễn từ {ngay_cat}).")
    print(f"Đặc trưng dùng ({len(features)}): {', '.join(features)}")
    for name, reason in bo.items():
        print(f"  bỏ {name}: {reason}")

    x_train, x_test = ma_tran(train, features, encoders), ma_tran(test, features, encoders)
    y_train, y_test = np.array([r["nhan"] for r in train]), np.array([r["nhan"] for r in test])
    models = huan_luyen(x_train, y_train, features, encoders)
    pred = du_doan(models, x_test)
    moc = moc_theo_the_loai(train, test)
    chi_so = {"lightgbm": danh_gia(y_test, pred), "moc_theo_the_loai": danh_gia(y_test, moc)}

    print("\nĐánh giá trên tập test (nhỏ hơn là tốt hơn, trừ tỷ lệ trong khoảng — lý tưởng ≈ 0,8):")
    print(f"  {'chỉ số':<22}{'LightGBM':>10}{'Mốc':>10}")
    for key in chi_so["lightgbm"]:
        print(f"  {key:<22}{_f(chi_so['lightgbm'][key]):>10}{_f(chi_so['moc_theo_the_loai'][key]):>10}")

    # Ba suất mẫu trong tập test: nhãn thấp nhất, giữa và cao nhất, với hai cách nhập ngưỡng hòa vốn.
    order = np.argsort(y_test, kind="stable")
    mau = [int(order[0]), int(order[len(order) // 2]), int(order[-1])]
    # Ví dụ giả định để chạy thử: hòa vốn khi bán 600/1.000 vé, hoặc chi phí bằng 40% doanh thu tối đa.
    nguong_ve, nguong_cp = nguong_tu_so_ve(600, 1000), nguong_tu_chi_phi(400_000_000, 1_000_000_000)
    vi_du = []
    print(f"\nXác suất hòa vốn — ví dụ giả định: ngưỡng {nguong_ve:.0%} (600/1.000 vé) và {nguong_cp:.0%} "
          "(chi phí 400 triệu / doanh thu tối đa 1 tỷ):")
    for i in mau:
        q = [float(pred[a][i]) for a in ALPHAS]
        p_ve, p_cp = xac_suat_hoa_von(*q, nguong_ve), xac_suat_hoa_von(*q, nguong_cp)
        vi_du.append({"ma_su_kien": test[i]["ma_su_kien"], "ten_su_kien": test[i]["ten_su_kien"],
                      "q10": q[0], "q50": q[1], "q90": q[2], "nhan_that": float(y_test[i]),
                      "p_hoa_von_60": p_ve, "p_hoa_von_40": p_cp})
        print(f"  {test[i]['ma_su_kien']:<10} {test[i]['ten_su_kien'][:44]:<44} q10/q50/q90 = "
              f"{q[0]:.2f}/{q[1]:.2f}/{q[2]:.2f}  thật {y_test[i]:.2f}  P(≥60%) {p_ve:.0%}  P(≥40%) {p_cp:.0%}")

    meta = {
        "dac_trung_phan_loai": [name for name in features if name in encoders],
        "dac_trung_bo_qua": bo,
        "nhan": "tổng giá hạng HET_VE / tổng giá mọi hạng, ở lần chụp đầu tiên sau ngày diễn (nhóm CO)",
        "alpha": list(ALPHAS), "tham_so": {**LGB_PARAMS, "so_vong": SO_VONG},
        "ngay_cat": ngay_cat, "so_dong": {"train": len(train), "test": len(test)},
    }
    luu(models, features, encoders, meta, args.thu_muc)
    with (args.thu_muc / "du_doan_test.csv").open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.writer(handle)
        writer.writerow(["ma_su_kien", "ten_su_kien", "ngay_dien", "nhan_that", "q10", "q50", "q90",
                         "moc_q10", "moc_q50", "moc_q90"])
        for i, row in enumerate(test):
            writer.writerow([row["ma_su_kien"], row["ten_su_kien"], row["ngay_dien"], round(row["nhan"], 4),
                             *(round(float(pred[a][i]), 4) for a in ALPHAS), *(round(float(moc[a][i]), 4) for a in ALPHAS)])

    # Mở lại từ đĩa, dựng lại ma trận bằng bộ mã hoá đã lưu, so với dự đoán vừa tính.
    models2, features2, encoders2 = mo_lai(args.thu_muc)
    pred2 = du_doan(models2, ma_tran(test, features2, encoders2))
    lech = max(float(np.max(np.abs(pred[a] - pred2[a]))) for a in ALPHAS)
    giong = math.isclose(lech, 0.0, abs_tol=1e-12)
    print(f"\nĐã lưu mô hình vào {args.thu_muc}. Mở lại và dự đoán lại: lệch lớn nhất {lech:.2e} → "
          f"{'GIỐNG NHAU' if giong else 'KHÁC NHAU'}.")

    (args.thu_muc / "bao_cao.json").write_text(json.dumps(
        {**meta, "chi_so": chi_so, "vi_du_hoa_von": vi_du, "mo_lai_giong_nhau": giong, "lech_toi_da": lech},
        ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if giong else 1


if __name__ == "__main__":
    sys.exit(main())
