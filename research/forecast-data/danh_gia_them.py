#!/usr/bin/env python3
"""Đánh giá bổ sung cho bài báo — chạy SAU train.py, không thay đổi mô hình đã lưu.

    .venv/bin/python danh_gia_them.py

Dùng lại đúng hàm của train.py (nhãn, đặc trưng, chia tập, tham số) nên số liệu khớp với bài. Gồm:
  1. Phễu dữ liệu: từ mọi suất đã thu thập xuống các suất có nhãn.
  2. Độ tin cậy của nhãn: độ trễ gắn nhãn, số hạng vé mỗi suất, trạng thái sau buổi diễn, nhãn thay thế.
  3. So sánh trên tập test: LightGBM và ba mô hình cơ sở, chỉ số 4 chữ số, bootstrap ghép cặp.
  4. Xác suất đạt ngưỡng trên TOÀN tập test (ngưỡng 0,3 / 0,5 / 0,7): Brier, AUC, so với bộ phân loại.
  5. Learning curve, đánh giá rolling-origin, mức quan trọng của đặc trưng và ablation.
Kết quả: models/danh_gia_them.json và các hình PNG trong ../hinh/.
"""

from __future__ import annotations

import json
from collections import Counter, defaultdict
from datetime import date

import lightgbm as lgb
import numpy as np

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

from tb_reader import HERE, SU_KIEN_CSV, SUC_CHUA_CSV, TRANG_THAI_CSV, read_csv  # noqa: E402
from train import (  # noqa: E402
    ALPHAS, LGB_PARAMS, MODEL_DIR, SO_VONG, chia_theo_thoi_gian, chon_dac_trung, chup_theo_suat, danh_gia,
    du_doan, huan_luyen, ma_tran, moc_theo_the_loai, nhan_sau_dien, pinball, tao_bang, tao_bo_ma_hoa,
    ty_le_gia_tri_het, xac_suat_hoa_von,
)

HINH_DIR = HERE.parent / "hinh"
NGUONG = (0.3, 0.5, 0.7)
SO_LAN_BOOTSTRAP = 5000
NHOM_DAC_TRUNG = {
    "giá vé": ["gia_min", "gia_max", "ty_le_gia_max_min", "so_hang_ve"],
    "lịch diễn": ["so_suat_su_kien", "so_ngay_mo_ban_den_dien", "thu_trong_tuan", "gio_dien"],
    "thể loại và thành phố": ["the_loai", "thanh_pho_nhom"],
}
TEN_DAC_TRUNG = {
    "so_hang_ve": "Số hạng vé", "ty_le_gia_max_min": "Tỷ lệ giá cao/thấp", "thu_trong_tuan": "Thứ trong tuần",
    "thanh_pho_nhom": "Nhóm thành phố", "the_loai": "Thể loại", "so_ngay_mo_ban_den_dien": "Số ngày mở bán",
    "gia_min": "Giá thấp nhất", "gio_dien": "Giờ diễn", "gia_max": "Giá cao nhất", "so_suat_su_kien": "Số suất",
}


# --- mô hình cơ sở -------------------------------------------------------------------------------

def _quantile_theo_nhom(train: list, test: list, khoa) -> dict:
    """Quantile của nhãn train theo nhóm khoa(row); nhóm vắng thì lùi về thể loại, rồi toàn bộ train."""
    nhom, the_loai = defaultdict(list), defaultdict(list)
    for row in train:
        nhom[khoa(row)].append(row["nhan"])
        the_loai[row["the_loai"]].append(row["nhan"])
    tat_ca = [row["nhan"] for row in train]
    def chon(row):
        return nhom.get(khoa(row)) or the_loai.get(row["the_loai"]) or tat_ca
    return {a: np.array([float(np.quantile(chon(row), a)) for row in test]) for a in ALPHAS}


def moc_toan_cuc(train: list, test: list) -> dict:
    return _quantile_theo_nhom(train, test, lambda row: "tất cả")


def moc_the_loai_thanh_pho(train: list, test: list) -> dict:
    return _quantile_theo_nhom(train, test, lambda row: (row["the_loai"], row["thanh_pho_nhom"]))


# --- tiện ích ------------------------------------------------------------------------------------

def chay_lightgbm(train: list, test: list, bo_bot: tuple = ()) -> tuple:
    features, _ = chon_dac_trung(train)
    features = [f for f in features if f not in bo_bot]
    encoders = tao_bo_ma_hoa(train, features)
    y = np.array([r["nhan"] for r in train])
    models = huan_luyen(ma_tran(train, features, encoders), y, features, encoders)
    return models, du_doan(models, ma_tran(test, features, encoders)), features, encoders


def auc(y_bin: np.ndarray, p: np.ndarray):
    """AUC theo thống kê Mann–Whitney (tính cả hạng bằng nhau); None nếu chỉ có một lớp."""
    pos, neg = p[y_bin], p[~y_bin]
    if len(pos) == 0 or len(neg) == 0:
        return None
    hon = (pos[:, None] > neg[None, :]).sum() + 0.5 * (pos[:, None] == neg[None, :]).sum()
    return float(hon / (len(pos) * len(neg)))


def bootstrap_auc(thuc: np.ndarray, p_a: np.ndarray, p_b: np.ndarray, seed: int = 42) -> dict:
    """AUC(a) − AUC(b) trên cùng các mẫu bootstrap; bỏ mẫu chỉ có một lớp. Dương là a tốt hơn."""
    rng = np.random.default_rng(seed)
    n, d = len(thuc), []
    for _ in range(SO_LAN_BOOTSTRAP):
        i = rng.integers(0, n, n)
        a, b = auc(thuc[i], p_a[i]), auc(thuc[i], p_b[i])
        if a is not None and b is not None:
            d.append(a - b)
    d = np.array(d)
    return {"chenh_lech": _r(auc(thuc, p_a) - auc(thuc, p_b)),
            "ci95": [_r(np.percentile(d, 2.5)), _r(np.percentile(d, 97.5))],
            "ti_le_mau_khong_tot_hon": _r(np.mean(d <= 0))}


def bootstrap_ghep_cap(sai_so_a: np.ndarray, sai_so_b: np.ndarray, rng) -> dict:
    """Chênh lệch trung bình (b − a; dương là a tốt hơn), khoảng tin cậy 95% và tỷ lệ mẫu ≤ 0."""
    n = len(sai_so_a)
    idx = rng.integers(0, n, size=(SO_LAN_BOOTSTRAP, n))
    d = sai_so_b[idx].mean(axis=1) - sai_so_a[idx].mean(axis=1)
    return {"chenh_lech": float(sai_so_b.mean() - sai_so_a.mean()),
            "ci95": [float(np.percentile(d, 2.5)), float(np.percentile(d, 97.5))],
            "ti_le_mau_khong_tot_hon": float(np.mean(d <= 0))}


def _r(x, k=4):
    return None if x is None else round(float(x), k)


# --- 1. phễu dữ liệu -----------------------------------------------------------------------------

def pheu_du_lieu(events: list, history: list) -> list:
    chup = chup_theo_suat(history)
    ngay_cuoi = max(row["ngay_cao"] for row in history)
    buoc = [("Tất cả suất diễn đã thu thập", len(events))]
    co = [e for e in events if e["nhom_phan_tich"] == "CO"]
    buoc.append(("Thuộc phạm vi nghiên cứu (3 thể loại, không trực tuyến)", len(co)))
    da_dien = [e for e in co if e["ngay_dien"] and e["ngay_dien"] < ngay_cuoi]
    buoc.append((f"Đã diễn trước lần chụp cuối ({ngay_cuoi})", len(da_dien)))
    co_chup = [e for e in da_dien if any(d > e["ngay_dien"] for d in chup.get(e["ma_su_kien"], {}))]
    buoc.append(("Có ít nhất một lần chụp sau ngày diễn", len(co_chup)))
    co_nhan = [e for e in co_chup if nhan_sau_dien(e, chup[e["ma_su_kien"]]) is not None]
    buoc.append(("Có giá vé dương (loại suất miễn phí) → có nhãn", len(co_nhan)))
    return [{"buoc": ten, "so_suat": n} for ten, n in buoc]


# --- 2. độ tin cậy của nhãn ----------------------------------------------------------------------

def kiem_tra_nhan(rows: list, history: list) -> dict:
    chup = chup_theo_suat(history)
    y = np.array([r["nhan"] for r in rows])
    tre = np.array([(date.fromisoformat(r["ngay_nhan"]) - date.fromisoformat(r["ngay_dien"])).days for r in rows])
    theo_tre = []
    for lo, hi, ten in [(0, 30, "≤ 30 ngày"), (31, 90, "31–90 ngày"), (91, 180, "91–180 ngày"),
                        (181, 10_000, "> 180 ngày")]:
        m = (tre >= lo) & (tre <= hi)
        theo_tre.append({"nhom": ten, "so_suat": int(m.sum()), "ti_le_y0": _r(np.mean(y[m] == 0)),
                         "ti_le_y1": _r(np.mean(y[m] == 1)), "y_tb": _r(y[m].mean())})

    so_hang, trang_thai, y_dem = [], Counter(), []
    for r in rows:
        snap = chup[r["ma_su_kien"]][r["ngay_nhan"]]
        so_hang.append(len(snap))
        trang_thai.update(x["trang_thai"] for x in snap)
        y_dem.append(np.mean([x["trang_thai"] == "HET_VE" for x in snap]))
    so_hang, y_dem = np.array(so_hang), np.array(y_dem)
    theo_k = []
    for ten, m in [("1 hạng", so_hang == 1), ("2 hạng", so_hang == 2), ("≥ 3 hạng", so_hang >= 3)]:
        theo_k.append({"nhom": ten, "so_suat": int(m.sum()), "ti_le_y0": _r(np.mean(y[m] == 0)),
                       "ti_le_y1": _r(np.mean(y[m] == 1)), "ti_le_y_giua": _r(np.mean((y[m] > 0) & (y[m] < 1)))})
    tong = sum(trang_thai.values())
    return {
        "do_tre_ngay": {"min": int(tre.min()), "trung_vi": float(np.median(tre)), "max": int(tre.max())},
        "theo_do_tre": theo_tre,
        "theo_so_hang_ve": theo_k,
        "trang_thai_sau_dien": {k: _r(v / tong) for k, v in trang_thai.most_common()},
        "tuong_quan_nhan_gia_tri_va_nhan_dem_hang": _r(np.corrcoef(y, y_dem)[0, 1]),
        "_y": y, "_so_hang": so_hang,
    }


# --- 3–5. đánh giá mô hình -----------------------------------------------------------------------

def so_sanh_tap_test(train: list, test: list, pred: dict, rng) -> dict:
    y = np.array([r["nhan"] for r in test])
    cac_moc = {"the_loai": moc_theo_the_loai(train, test), "toan_cuc": moc_toan_cuc(train, test),
               "the_loai_x_thanh_pho": moc_the_loai_thanh_pho(train, test)}
    chi_so = {"lightgbm": {k: _r(v) for k, v in danh_gia(y, pred).items()}}
    chi_so.update({ten: {k: _r(v) for k, v in danh_gia(y, q).items()} for ten, q in cac_moc.items()})

    moc = cac_moc["the_loai"]
    bootstrap = {"mae_q50": bootstrap_ghep_cap(np.abs(y - pred[0.5]), np.abs(y - moc[0.5]), rng)}
    for a in ALPHAS:
        loss = lambda q: np.maximum(a * (y - q), (a - 1) * (y - q))  # noqa: E731
        bootstrap[f"pinball_q{int(a * 100)}"] = bootstrap_ghep_cap(loss(pred[a]), loss(moc[a]), rng)
    for v in bootstrap.values():
        v.update({k: _r(v[k]) for k in ("chenh_lech", "ti_le_mau_khong_tot_hon")}, ci95=[_r(c) for c in v["ci95"]])

    ngoai = (y < moc[0.1]) | (y > moc[0.9])
    moc_truot = [{"the_loai": test[i]["the_loai"], "q10": _r(moc[0.1][i]), "q90": _r(moc[0.9][i]),
                  "nhan": _r(y[i])} for i in np.where(ngoai)[0]]
    return {"chi_so": chi_so, "bootstrap_so_voi_moc_the_loai": bootstrap, "moc_the_loai_truot_khoang": moc_truot}


def xac_suat_dat_nguong(train: list, test: list, pred: dict) -> tuple:
    """P(y ≥ ngưỡng) từ 3 phân vị (LightGBM và mốc thể loại) so với bộ phân loại LightGBM nhị phân."""
    y = np.array([r["nhan"] for r in test])
    moc = moc_theo_the_loai(train, test)
    features, _ = chon_dac_trung(train)
    encoders = tao_bo_ma_hoa(train, features)
    x_tr, x_te = ma_tran(train, features, encoders), ma_tran(test, features, encoders)
    cat = [f for f in features if f in encoders]
    ket_qua, cho_hinh = [], {}
    for t in NGUONG:
        thuc = y >= t
        y_tr = np.array([r["nhan"] >= t for r in train], dtype=float)
        params = {k: v for k, v in LGB_PARAMS.items() if k != "objective"}
        clf = lgb.train({**params, "objective": "binary"},
                        lgb.Dataset(x_tr, label=y_tr, feature_name=features, categorical_feature=cat),
                        num_boost_round=SO_VONG)
        p = {"lightgbm_phan_vi": np.array([xac_suat_hoa_von(pred[0.1][i], pred[0.5][i], pred[0.9][i], t)
                                           for i in range(len(y))]),
             "moc_the_loai": np.array([xac_suat_hoa_von(moc[0.1][i], moc[0.5][i], moc[0.9][i], t)
                                       for i in range(len(y))]),
             "phan_loai_lightgbm": clf.predict(x_te),
             "ti_le_co_so": np.full(len(y), y_tr.mean())}
        cho_hinh[t] = (thuc, p)
        ket_qua.append({"nguong": t, "ti_le_dat_thuc_te": _r(thuc.mean()),
                        "bootstrap_auc_lightgbm_tru_moc": bootstrap_auc(thuc, p["lightgbm_phan_vi"],
                                                                         p["moc_the_loai"]),
                        **{ten: {"brier": _r(np.mean((pp - thuc) ** 2)), "auc": _r(auc(thuc, pp))}
                           for ten, pp in p.items()}})
    return ket_qua, cho_hinh


def learning_curve(train: list, test: list) -> list:
    """Giữ nguyên tập test; train là phần GẦN NHẤT (theo ngày diễn) của tập train với các tỷ lệ khác nhau."""
    y = np.array([r["nhan"] for r in test])
    ket_qua = []
    for ti_le in (0.15, 0.25, 0.5, 0.75, 1.0):
        con = train[int(len(train) * (1 - ti_le)):]
        _, pred, _, _ = chay_lightgbm(con, test)
        moc = moc_theo_the_loai(con, test)
        ket_qua.append({"ti_le_train": ti_le, "so_suat_train": len(con),
                        "mae_lightgbm": _r(np.mean(np.abs(y - pred[0.5]))),
                        "mae_moc": _r(np.mean(np.abs(y - moc[0.5]))),
                        "do_rong_lightgbm": _r(np.mean(pred[0.9] - pred[0.1])),
                        "phu_lightgbm": _r(np.mean((y >= pred[0.1]) & (y <= pred[0.9])))})
    return ket_qua


def rolling_origin(rows: list) -> list:
    """5 lần chia: train là mọi suất trước ngày chia, test là khối 10% kế tiếp (lần cuối là 10% muộn nhất)."""
    ket_qua = []
    for k, dau in enumerate((0.5, 0.6, 0.7, 0.8, 0.9)):
        ngay_dau = rows[int(len(rows) * dau)]["ngay_dien"]
        ngay_cuoi = rows[int(len(rows) * (dau + 0.1))]["ngay_dien"] if dau < 0.9 else "9999"
        train = [r for r in rows if r["ngay_dien"] < ngay_dau]
        test = [r for r in rows if ngay_dau <= r["ngay_dien"] < ngay_cuoi]
        y = np.array([r["nhan"] for r in test])
        _, pred, _, _ = chay_lightgbm(train, test)
        moc = moc_theo_the_loai(train, test)
        ket_qua.append({"lan": k + 1, "ngay_chia": ngay_dau, "so_train": len(train), "so_test": len(test),
                        "mae_lightgbm": _r(np.mean(np.abs(y - pred[0.5]))),
                        "mae_moc": _r(np.mean(np.abs(y - moc[0.5]))),
                        "phu_lightgbm": _r(np.mean((y >= pred[0.1]) & (y <= pred[0.9]))),
                        "do_rong_lightgbm": _r(np.mean(pred[0.9] - pred[0.1]))})
    return ket_qua


def quan_trong_va_ablation(train: list, test: list, models: dict, features: list) -> tuple:
    gain = models[0.5].feature_importance(importance_type="gain")
    tong = gain.sum() or 1.0
    quan_trong = sorted(({"dac_trung": f, "ti_le_gain": _r(g / tong)} for f, g in zip(features, gain)),
                        key=lambda d: -d["ti_le_gain"])
    y = np.array([r["nhan"] for r in test])
    ablation = []
    for ten, bo in [("đầy đủ", ())] + [(f"bỏ {k}", tuple(v)) for k, v in NHOM_DAC_TRUNG.items()]:
        _, pred, _, _ = chay_lightgbm(train, test, bo)
        ablation.append({"cau_hinh": ten, "mae_q50": _r(np.mean(np.abs(y - pred[0.5]))),
                         "pinball_q50": _r(pinball(y, pred[0.5], 0.5)),
                         "do_rong_q10_q90": _r(np.mean(pred[0.9] - pred[0.1]))})
    return quan_trong, ablation


# --- hình ----------------------------------------------------------------------------------------

def _dau_phay(fig) -> None:
    """Số thập phân trên trục dùng dấu phẩy, thống nhất với văn bản tiếng Việt của bài báo."""
    from matplotlib.ticker import FuncFormatter, ScalarFormatter
    fmt = FuncFormatter(lambda v, _: f"{v:g}".replace(".", ","))
    for ax in fig.axes:
        # Chỉ trục số mặc định; trục mang nhãn chữ (tên đặc trưng, "≥10") giữ nguyên.
        for axis in (ax.xaxis, ax.yaxis):
            if isinstance(axis.get_major_formatter(), ScalarFormatter):
                axis.set_major_formatter(fmt)


def ve_hinh(nhan: dict, quan_trong: list, cho_hinh: dict, y_test, pred) -> list:
    HINH_DIR.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({"font.size": 9, "figure.dpi": 200})
    ten = []

    fig, ax = plt.subplots(1, 2, figsize=(6.4, 2.4))
    ax[0].hist(nhan["_y"], bins=np.linspace(0, 1, 21), color="#4C72B0", edgecolor="white")
    ax[0].set_xlabel("Nhãn y"); ax[0].set_ylabel("Số suất diễn")
    k = np.minimum(nhan["_so_hang"], 10)
    gia_tri, dem = np.unique(k, return_counts=True)
    ax[1].bar(gia_tri, dem, color="#55A868")
    ax[1].set_xticks(gia_tri, [str(v) if v < 10 else "≥10" for v in gia_tri])
    ax[1].set_xlabel("Số hạng vé của suất (K)"); ax[1].set_ylabel("Số suất diễn")
    _dau_phay(fig); fig.tight_layout(); fig.savefig(HINH_DIR / "phan_bo_nhan.png"); plt.close(fig)
    ten.append("phan_bo_nhan.png")

    fig, ax = plt.subplots(figsize=(3.3, 2.4))
    qt = list(reversed(quan_trong))
    ax.barh([TEN_DAC_TRUNG.get(d["dac_trung"], d["dac_trung"]) for d in qt], [d["ti_le_gain"] for d in qt],
            color="#4C72B0")
    ax.set_xlabel("Tỷ lệ gain (mô hình q0,5)")
    _dau_phay(fig); fig.tight_layout(); fig.savefig(HINH_DIR / "muc_quan_trong.png"); plt.close(fig)
    ten.append("muc_quan_trong.png")

    thuc, p = cho_hinh[0.5]
    fig, ax = plt.subplots(figsize=(3.3, 3.0))
    ax.plot([0, 1], [0, 1], ":", color="grey", label="Hiệu chuẩn hoàn hảo")
    for key, nhan_hinh, mau in [("lightgbm_phan_vi", "LightGBM phân vị", "#4C72B0"),
                                ("phan_loai_lightgbm", "LightGBM phân loại", "#C44E52"),
                                ("moc_the_loai", "Mô hình cơ sở", "#8C8C8C")]:
        bins = np.linspace(0, 1, 6)
        idx = np.clip(np.digitize(p[key], bins) - 1, 0, 4)
        xs = [p[key][idx == b].mean() for b in range(5) if (idx == b).sum() >= 3]
        ys = [thuc[idx == b].mean() for b in range(5) if (idx == b).sum() >= 3]
        ax.plot(xs, ys, "o-", color=mau, label=nhan_hinh, markersize=4)
    ax.set_xlabel("Xác suất dự báo P(y ≥ 0,5)"); ax.set_ylabel("Tỷ lệ thực tế")
    ax.legend(fontsize=7); _dau_phay(fig); fig.tight_layout(); fig.savefig(HINH_DIR / "hieu_chuan.png"); plt.close(fig)
    ten.append("hieu_chuan.png")

    fig, ax = plt.subplots(figsize=(3.3, 2.6))
    ax.scatter(pred[0.5], y_test + np.random.default_rng(0).uniform(-0.02, 0.02, len(y_test)),
               s=8, alpha=0.6, color="#4C72B0")
    ax.plot([0, 1], [0, 1], ":", color="grey")
    ax.set_xlabel("Dự báo trung vị q0,5"); ax.set_ylabel("Nhãn thực tế y")
    _dau_phay(fig); fig.tight_layout(); fig.savefig(HINH_DIR / "du_bao_va_thuc_te.png"); plt.close(fig)
    ten.append("du_bao_va_thuc_te.png")
    return ten


# --- chạy ----------------------------------------------------------------------------------------

def main() -> int:
    rng = np.random.default_rng(42)
    events, history = read_csv(SU_KIEN_CSV), read_csv(TRANG_THAI_CSV)
    rows = tao_bang(events, history, read_csv(SUC_CHUA_CSV))
    train, test, ngay_cat = chia_theo_thoi_gian(rows, 0.2)
    models, pred, features, _ = chay_lightgbm(train, test)
    y_test = np.array([r["nhan"] for r in test])

    pheu = pheu_du_lieu(events, history)
    nhan = kiem_tra_nhan(rows, history)
    so_sanh = so_sanh_tap_test(train, test, pred, rng)
    nguong, cho_hinh = xac_suat_dat_nguong(train, test, pred)
    lc = learning_curve(train, test)
    ro = rolling_origin(rows)
    quan_trong, ablation = quan_trong_va_ablation(train, test, models, features)
    hinh = ve_hinh(nhan, quan_trong, cho_hinh, y_test, pred)

    ket_qua = {"ngay_chia": ngay_cat, "so_train": len(train), "so_test": len(test), "pheu_du_lieu": pheu,
               "kiem_tra_nhan": {k: v for k, v in nhan.items() if not k.startswith("_")},
               "so_sanh_tap_test": so_sanh, "xac_suat_dat_nguong": nguong, "learning_curve": lc,
               "rolling_origin": ro, "muc_quan_trong": quan_trong, "ablation": ablation,
               "hinh": [str(HINH_DIR / h) for h in hinh]}
    out = MODEL_DIR / "danh_gia_them.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(ket_qua, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(ket_qua, ensure_ascii=False, indent=1))
    print(f"\nĐã ghi {out} và {len(hinh)} hình trong {HINH_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
