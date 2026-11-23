#!/usr/bin/env python3
"""Công cụ dự báo cho ban tổ chức — dịch vụ độc lập (chưa gắn vào NexaTicket), chỉ dùng thư viện chuẩn.

    .venv/bin/python du_bao_api.py            # mở http://localhost:8765
    .venv/bin/python du_bao_api.py --cong 9000

Nạp mô hình train.py đã lưu trong models/, nhận thông tin một suất diễn SẮP mở bán và trả về:
  - ba phân vị q̂0,1 / q̂0,5 / q̂0,9 của nhãn y (tỷ lệ giá trị các hạng vé bán hết);
  - ngưỡng y_BE = chi phí / doanh thu tối đa và xác suất P(y ≥ y_BE).
Xác suất này là "xác suất đạt ngưỡng bán"; coi nó là xác suất hòa vốn chỉ đúng dưới GIẢ ĐỊNH y xấp xỉ tỷ lệ
doanh thu thực bán (xem bài báo). Đặc trưng được tính bằng chính hàm dac_trung() của train.py nên khớp lúc huấn luyện.

API:
  GET  /                 trang nhập liệu
  GET  /api/thong-tin    thể loại, thành phố mô hình đã gặp và thông tin mô hình
  POST /api/du-bao       JSON vào/ra, xem du_bao()
"""

from __future__ import annotations

import argparse
import json
import time
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from train import ALPHAS, MODEL_DIR, THANH_PHO_RIENG, dac_trung, du_doan, ma_tran, mo_lai, nguong_tu_chi_phi, \
    xac_suat_hoa_von

TRUONG_BAT_BUOC = ("the_loai", "thanh_pho", "gia_min", "gia_max", "so_hang_ve", "ngay_mo_ban", "ngay_dien",
                   "chi_phi", "doanh_thu_toi_da")


class LoiDuLieu(ValueError):
    """Dữ liệu nhập không hợp lệ — trả về 400 kèm thông báo tiếng Việt."""


class MoHinh:
    def __init__(self, thu_muc: Path = MODEL_DIR):
        self.models, self.features, self.encoders = mo_lai(thu_muc)
        bao_cao = thu_muc / "bao_cao.json"
        self.meta = json.loads(bao_cao.read_text(encoding="utf-8")) if bao_cao.exists() else {}
        self.tham_chieu = doc_tham_chieu(thu_muc / "du_doan_test.csv")

    def thong_tin(self) -> dict:
        return {"the_loai": self.encoders.get("the_loai", []),
                "thanh_pho": list(THANH_PHO_RIENG) + ["Khác"],
                "dac_trung": self.features,
                "ngay_chia": self.meta.get("ngay_cat"),
                "so_suat_train": (self.meta.get("so_dong") or {}).get("train")}

    def du_bao(self, vao: dict) -> dict:
        su_kien, chi_phi, doanh_thu = kiem_tra_dau_vao(vao)
        row = dac_trung(su_kien, {}, int(vao.get("so_suat_su_kien") or 1), None)
        pred = du_doan(self.models, ma_tran([row], self.features, self.encoders))
        q10, q50, q90 = (float(pred[a][0]) for a in ALPHAS)
        nguong = nguong_tu_chi_phi(chi_phi, doanh_thu)
        canh_bao = []
        if su_kien["the_loai"] not in self.encoders.get("the_loai", []):
            canh_bao.append("Thể loại chưa có trong dữ liệu huấn luyện; mô hình coi như thiếu thông tin.")
        if chi_phi > doanh_thu:
            canh_bao.append("Chi phí lớn hơn doanh thu tối đa: không thể hòa vốn kể cả khi bán hết.")
        xac_suat = 0.0 if chi_phi > doanh_thu else xac_suat_hoa_von(q10, q50, q90, nguong)
        return {"q10": round(q10, 4), "q50": round(q50, 4), "q90": round(q90, 4),
                "nguong": round(nguong, 4),
                "xac_suat_dat_nguong": round(xac_suat, 4),
                "xep_hang": None if not self.tham_chieu else round(xep_hang(xac_suat, nguong, self.tham_chieu), 4),
                "so_tham_chieu": len(self.tham_chieu),
                "canh_bao": canh_bao}


def doc_tham_chieu(path: Path) -> list:
    """Ba phân vị dự báo NGOÀI MẪU của các suất trong tập kiểm tra (train.py ghi ra du_doan_test.csv).

    Xác suất từ phân vị đang cao hơn thực tế (bài báo, Hình 4), nên giao diện không đọc nó như một con số
    tuyệt đối mà so với các suất tham chiếu này ở CÙNG ngưỡng. Thiếu tệp thì trả về rỗng, không xếp hạng.
    """
    if not path.exists():
        return []
    import csv
    with path.open(encoding="utf-8-sig") as f:
        return [(float(r["q10"]), float(r["q50"]), float(r["q90"])) for r in csv.DictReader(f)]


def xep_hang(xac_suat: float, nguong: float, tham_chieu: list) -> float:
    """Tỷ lệ suất tham chiếu có xác suất đạt cùng ngưỡng THẤP HƠN suất này (bằng nhau tính một nửa)."""
    p = [xac_suat_hoa_von(q10, q50, q90, nguong) for q10, q50, q90 in tham_chieu]
    thap_hon = sum(1 for x in p if x < xac_suat - 1e-9)
    bang = sum(1 for x in p if abs(x - xac_suat) <= 1e-9)
    return (thap_hon + 0.5 * bang) / len(p)


def _so(vao: dict, ten: str, toi_thieu: float = 0.0) -> float:
    try:
        value = float(vao[ten])
    except (TypeError, ValueError):
        raise LoiDuLieu(f"'{ten}' phải là số") from None
    if value < toi_thieu:
        raise LoiDuLieu(f"'{ten}' phải ≥ {toi_thieu:g}")
    return value


def _ngay(vao: dict, ten: str) -> date:
    try:
        return date.fromisoformat(str(vao[ten]))
    except ValueError:
        raise LoiDuLieu(f"'{ten}' phải có dạng YYYY-MM-DD") from None


def kiem_tra_dau_vao(vao: dict) -> tuple:
    """(sự kiện theo định dạng su_kien.csv, chi phí, doanh thu tối đa) hoặc LoiDuLieu."""
    thieu = [ten for ten in TRUONG_BAT_BUOC if vao.get(ten) in (None, "")]
    if thieu:
        raise LoiDuLieu("Thiếu trường: " + ", ".join(thieu))
    gia_min, gia_max = _so(vao, "gia_min", 1), _so(vao, "gia_max", 1)
    if gia_min > gia_max:
        raise LoiDuLieu("'gia_min' không được lớn hơn 'gia_max'")
    so_hang = _so(vao, "so_hang_ve", 1)
    mo_ban, dien = _ngay(vao, "ngay_mo_ban"), _ngay(vao, "ngay_dien")
    if mo_ban > dien:
        raise LoiDuLieu("Ngày mở bán phải trước hoặc bằng ngày diễn")
    gio = str(vao.get("gio_dien") or "")
    if gio and (len(gio) < 5 or gio[2] != ":" or not (gio[:2] + gio[3:5]).isdigit()):
        raise LoiDuLieu("'gio_dien' phải có dạng HH:MM")
    chi_phi, doanh_thu = _so(vao, "chi_phi"), _so(vao, "doanh_thu_toi_da")
    if doanh_thu <= 0:
        raise LoiDuLieu("'doanh_thu_toi_da' phải lớn hơn 0")
    su_kien = {"the_loai": str(vao["the_loai"]), "thanh_pho": str(vao["thanh_pho"]), "gia_min": gia_min,
               "gia_max": gia_max, "so_hang_ve": so_hang, "ngay_mo_ban": mo_ban.isoformat(),
               "ngay_dien": dien.isoformat(), "gio_dien": gio}
    return su_kien, chi_phi, doanh_thu


def tao_handler(mo_hinh: MoHinh):
    class Handler(BaseHTTPRequestHandler):
        def _gui(self, ma: int, body: bytes, kieu: str, bat_dau: float) -> None:
            self.send_response(ma)
            self.send_header("Content-Type", kieu)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("X-Thoi-Gian-Xu-Ly-Ms", f"{(time.perf_counter() - bat_dau) * 1000:.1f}")
            self.end_headers()
            self.wfile.write(body)

        def _json(self, ma: int, data: dict, bat_dau: float) -> None:
            self._gui(ma, json.dumps(data, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8",
                      bat_dau)

        def do_GET(self):  # noqa: N802
            bat_dau = time.perf_counter()
            if self.path in ("/", "/index.html"):
                self._gui(200, TRANG.encode("utf-8"), "text/html; charset=utf-8", bat_dau)
            elif self.path == "/api/thong-tin":
                self._json(200, mo_hinh.thong_tin(), bat_dau)
            else:
                self._json(404, {"loi": "Không tìm thấy"}, bat_dau)

        def do_POST(self):  # noqa: N802
            bat_dau = time.perf_counter()
            if self.path != "/api/du-bao":
                return self._json(404, {"loi": "Không tìm thấy"}, bat_dau)
            try:
                do_dai = int(self.headers.get("Content-Length") or 0)
                if do_dai > 10_000:
                    raise LoiDuLieu("Dữ liệu gửi lên quá lớn")
                vao = json.loads(self.rfile.read(do_dai) or b"{}")
                if not isinstance(vao, dict):
                    raise LoiDuLieu("Dữ liệu phải là một đối tượng JSON")
                self._json(200, mo_hinh.du_bao(vao), bat_dau)
            except json.JSONDecodeError:
                self._json(400, {"loi": "JSON không hợp lệ"}, bat_dau)
            except LoiDuLieu as exc:
                self._json(400, {"loi": str(exc)}, bat_dau)

        def log_message(self, fmt, *args):  # gọn log: chỉ dòng yêu cầu
            print(f"{self.address_string()} {fmt % args}")

    return Handler


TRANG = """<!doctype html>
<html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Dự báo bán vé</title>
<style>
:root{--bg:#121912;--surface:#20291c;--surface2:#273228;--text:#eef3e6;--muted:#a3ae9a;--primary:#d5ff66;
--ink:#17210d;--line:#34422f;--warn:#ffb86b}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,sans-serif}
main{max-width:920px;margin:0 auto;padding:24px 16px}h1{font-size:22px;margin:0 0 4px}p.sub{color:var(--muted);margin:0 0 20px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:16px;margin-bottom:16px}
label{display:block;font-size:13px;color:var(--muted)}input,select{width:100%;margin-top:4px;padding:8px 10px;
background:var(--surface2);color:var(--text);border:1px solid var(--line);border-radius:8px;font:inherit}
button{background:var(--primary);color:var(--ink);border:0;border-radius:8px;padding:10px 18px;font-weight:600;cursor:pointer}
.kq{display:none}.so{font-size:28px;font-weight:700;color:var(--primary)}.nho{font-size:13px;color:var(--muted)}
.bar{position:relative;height:14px;background:var(--surface2);border-radius:7px;margin:28px 0 8px}
.dai{position:absolute;top:0;bottom:0;background:#d5ff6655;border-radius:7px}
.diem{position:absolute;top:-4px;width:3px;height:22px;background:var(--primary)}
.nguong{position:absolute;top:-10px;width:2px;height:34px;background:var(--warn)}
.loi{color:var(--warn)}h2{font-size:16px;margin:0 0 12px}
</style></head><body><main>
<h1>Dự báo mức độ bán hết vé</h1>
<p class="sub">Công cụ thử nghiệm cho ban tổ chức: nhập thông tin suất diễn, nhận khoảng dự báo và xác suất đạt ngưỡng bán.</p>
<form id="f" class="card"><h2>Thông tin suất diễn</h2><div class="grid">
<label>Thể loại<select name="the_loai" id="the_loai"></select></label>
<label>Thành phố<select name="thanh_pho" id="thanh_pho"></select></label>
<label>Giá vé thấp nhất (đ)<input name="gia_min" type="number" min="1" value="500000" required></label>
<label>Giá vé cao nhất (đ)<input name="gia_max" type="number" min="1" value="2000000" required></label>
<label>Số hạng vé<input name="so_hang_ve" type="number" min="1" value="4" required></label>
<label>Số suất của sự kiện<input name="so_suat_su_kien" type="number" min="1" value="1"></label>
<label>Ngày mở bán<input name="ngay_mo_ban" type="date" required></label>
<label>Ngày diễn<input name="ngay_dien" type="date" required></label>
<label>Giờ diễn<input name="gio_dien" type="time" value="19:30"></label>
</div><h2 style="margin-top:16px">Chi phí và doanh thu</h2><div class="grid">
<label>Tổng chi phí C (đ)<input name="chi_phi" type="number" min="0" value="600000000" required></label>
<label>Doanh thu tối đa V khi bán hết (đ)<input name="doanh_thu_toi_da" type="number" min="1" value="1000000000" required></label>
</div><p style="margin:16px 0 0"><button>Dự báo</button> <span id="loi" class="loi"></span></p></form>
<section id="kq" class="card kq"><h2>Kết quả</h2><div class="grid">
<div><div class="nho">Xác suất đạt ngưỡng y ≥ <span id="ng"></span></div><div class="so" id="p"></div></div>
<div><div class="nho">Dự báo trung vị q̂0,5</div><div class="so" id="q50"></div></div>
<div><div class="nho">Khoảng 80% (q̂0,1 – q̂0,9)</div><div class="so" id="kh"></div></div></div>
<div class="bar"><div class="dai" id="dai"></div><div class="diem" id="diem"></div><div class="nguong" id="vach"></div></div>
<div class="nho">Thang 0 → 1: tỷ lệ giá trị các hạng vé bán hết. Vạch cam là ngưỡng C/V; dải xanh là khoảng 80%.</div>
<p class="nho" id="cb"></p>
<p class="nho">Lưu ý: mô hình dự báo tỷ lệ giá trị các hạng vé bán hết (dữ liệu Ticketbox), không phải số vé bán.
Xác suất trên chỉ là xác suất hòa vốn khi giả định tỷ lệ này xấp xỉ tỷ lệ doanh thu thực bán.</p></section>
</main><script>
const f=document.getElementById('f'),$=id=>document.getElementById(id),pct=x=>(x*100).toFixed(0)+'%';
const hom=new Date(),dd=d=>d.toISOString().slice(0,10);
f.ngay_mo_ban.value=dd(hom);f.ngay_dien.value=dd(new Date(hom.getTime()+45*864e5));
fetch('/api/thong-tin').then(r=>r.json()).then(t=>{
 for(const [id,ds] of [['the_loai',t.the_loai],['thanh_pho',t.thanh_pho]])$(id).innerHTML=ds.map(v=>`<option>${v}</option>`).join('');});
f.addEventListener('submit',async e=>{e.preventDefault();$('loi').textContent='';
 const body=Object.fromEntries(new FormData(f));
 const r=await fetch('/api/du-bao',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
 const k=await r.json();if(!r.ok){$('loi').textContent=k.loi;return;}
 $('kq').style.display='block';$('ng').textContent=k.nguong.toFixed(2).replace('.',',');$('p').textContent=pct(k.xac_suat_dat_nguong);
 $('q50').textContent=k.q50.toFixed(2).replace('.',',');$('kh').textContent=(k.q10.toFixed(2)+' – '+k.q90.toFixed(2)).replaceAll('.',',');
 $('dai').style.left=pct(k.q10);$('dai').style.width=pct(k.q90-k.q10);$('diem').style.left=`calc(${pct(k.q50)} - 1px)`;
 $('vach').style.left=pct(k.nguong);$('cb').textContent=k.canh_bao.join(' ');});
</script></body></html>
"""


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Công cụ dự báo bán vé cho ban tổ chức.")
    parser.add_argument("--cong", type=int, default=8765)
    parser.add_argument("--thu-muc", type=Path, default=MODEL_DIR)
    args = parser.parse_args(argv)
    server = ThreadingHTTPServer(("127.0.0.1", args.cong), tao_handler(MoHinh(args.thu_muc)))
    print(f"Công cụ dự báo đang chạy: http://localhost:{args.cong}  (Ctrl+C để dừng)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
