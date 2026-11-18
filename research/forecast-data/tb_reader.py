"""Phần dùng chung của ba script nghiên cứu: đọc Ticketbox, chuẩn hoá giá / ngày giờ / trạng thái, CSV.

NGUỒN GỐC
=========
Phần đọc trang (robots.txt bằng chính User-Agent, chờ 2–4 giây, đọc JSON mà trang Ticketbox đang gọi)
và các hàm `fold`, `parse_price`, `parse_vn_datetime`, `normalize_city`, `city_from_address` lấy từ bộ
cào dữ liệu demo `scripts/ticketbox/` (commit d4cd45a trên nhánh main). Nhánh hiện tại chưa có các
file đó nên chép sang đây thay vì import; logic giữ nguyên, chỉ bỏ phần phục vụ nhập NexaTicket.

DỮ LIỆU TICKETBOX ĐỌC TỪ ĐÂU (đã xem trực tiếp, 03/11/2026)
============================================================
* Danh sách: `api-v2.ticketbox.vn/search/v3/events?filter=upcoming|past&page=N&limit=20` — chính API
  của trang ticketbox.vn/search. `past` vẫn trả sự kiện đã diễn (kèm badge "Đã diễn ra").
* Chi tiết: `api-v2.ticketbox.vn/gin/api/v2/events/{id}` — gọi bởi trang sự kiện. Mỗi suất
  (`showings[]`) có `ticketTypes[]`, mỗi hạng vé có `price`, `isFree`, `startTime`/`endTime` (khung
  giờ bán của hạng vé) và `status`.
* Thể loại: `gin/api/v2/events/categories` (mã → tên tiếng Việt).
* Nghệ sĩ: `artisthub/api/v1/creators/lineup/{id}` — đa số sự kiện để trống.
"""

from __future__ import annotations

import csv
import random
import re
import sys
import time
import unicodedata
import urllib.request
import urllib.robotparser
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable, Optional

HERE = Path(__file__).resolve().parent
DATA_DIR = HERE / "data"
SU_KIEN_CSV = DATA_DIR / "su_kien.csv"
HANG_VE_CSV = DATA_DIR / "hang_ve.csv"
TRANG_THAI_CSV = DATA_DIR / "trang_thai_ngay.csv"
SUC_CHUA_CSV = DATA_DIR / "suc_chua_dia_diem.csv"
EXCEL_PATH = DATA_DIR / "bo-du-lieu-nghien-cuu.xlsx"
LOG_PATH = DATA_DIR / "cao-du-lieu.log"

SITE = "https://ticketbox.vn"
API = "https://api-v2.ticketbox.vn"
NGUON = "ticketbox.vn"
VN = timezone(timedelta(hours=7))

# User-Agent nói rõ ai đang truy cập và để làm gì — không giả làm trình duyệt người dùng.
USER_AGENT = "NexaTicket-Research/1.0 (student thesis, IUH)"

# Đường robots.txt cấm (03/11/2026). Kiểm bằng robots.txt mỗi lần chạy; danh sách này là chốt chặn thứ
# hai, phòng khi robots.txt đổi mà parser đọc sai.
CAM_TRUY_CAP = ("/payment/", "/my-tickets/", "/my-ticket/", "/profile/", "/sign-in/", "/sign-up/",
                "/verify-email/", "/reset-password/")

SU_KIEN_COLUMNS = [
    "ma_su_kien", "nguon", "url", "ten_su_kien", "nghe_si", "the_loai", "thanh_pho", "dia_diem",
    "suc_chua", "ngay_mo_ban", "ngay_thay_lan_dau", "ngay_dien", "gio_dien", "so_hang_ve", "gia_min",
    "gia_max", "trang_thai_cuoi", "ngay_cao", "nhom_phan_tich",
]
HANG_VE_COLUMNS = ["ma_su_kien", "ten_hang", "gia", "so_luong"]
TRANG_THAI_COLUMNS = ["ngay_cao", "gio_cao", "ma_su_kien", "ten_hang", "trang_thai", "so_ve_con", "gia"]
SUC_CHUA_COLUMNS = ["dia_diem", "thanh_pho", "suc_chua", "nguon_tra_cuu"]

# Mã trạng thái của bộ dữ liệu. CHUA_MO_BAN không có trong danh sách ban đầu nhưng Ticketbox có trạng
# thái riêng cho nó (`not_open_for_sale_yet`); gộp vào KHONG_RO thì mất thông tin "mở bán lúc nào".
TRANG_THAI = ("CON_VE", "SAP_HET", "HET_VE", "NGUNG_BAN", "CHUA_MO_BAN", "KHONG_RO")

# `status` của hạng vé trong JSON → mã. Đã gặp (03/11/2026): book_now, sold_out, sale_closed,
# not_open_for_sale_yet, registration_closed (sự kiện đăng ký miễn phí đã đóng đăng ký).
STATUS_CODE = {
    "book_now": "CON_VE",
    "sold_out": "HET_VE",
    "sale_closed": "NGUNG_BAN",
    "registration_closed": "NGUNG_BAN",
    "not_open_for_sale_yet": "CHUA_MO_BAN",
}


def today() -> date:
    return datetime.now(VN).date()


# --- văn bản -------------------------------------------------------------------------------------


def fold(value: Optional[str]) -> str:
    """Thường hoá + bỏ dấu tiếng Việt (đ → d). Dùng làm khoá so khớp, không để hiển thị."""
    lowered = (value or "").lower().replace("đ", "d")
    decomposed = unicodedata.normalize("NFD", lowered)
    return "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")


def clean(value: Optional[str]) -> str:
    """Một dòng, bỏ ký tự điều khiển và khoảng trắng thừa — để CSV không bị vỡ dòng."""
    text = "".join(ch if unicodedata.category(ch)[0] != "C" else " " for ch in (value or ""))
    return re.sub(r"\s+", " ", text).strip()


# --- giá -----------------------------------------------------------------------------------------


def parse_price(value) -> Optional[int]:
    """Giá về số nguyên VND. "Miễn phí" → 0. Không đọc được → None.

    Nhận số (JSON của Ticketbox) và chữ: "1.500.000 đ", "1,500,000", "1,5 triệu", "2tr", "350k".
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return int(round(value)) if value >= 0 else None
    text = fold(str(value)).strip()
    if not text:
        return None
    if "mien phi" in text or text in {"free", "0"}:
        return 0
    number = re.search(r"\d+(?:[.,]\d+)*", text)
    if not number:
        return None
    raw = number.group(0)
    multiplier = 1
    # "1,5 triệu", "2tr", "1.5tr": đơn vị có thể dính liền ngay sau số.
    if re.search(r"(\d\s*|\b)(trieu|tr)\b", text):
        multiplier = 1_000_000
    elif re.search(r"\d\s*(k|nghin|ngan)\b", text):
        multiplier = 1_000
    if multiplier > 1:
        # "1,5 triệu" / "1.5tr": dấu phẩy hoặc chấm là phần thập phân.
        amount = float(raw.replace(",", "."))
    else:
        # "1.500.000 đ" / "1,500,000": dấu là phân cách hàng nghìn.
        amount = float(re.sub(r"[.,]", "", raw))
    return int(round(amount * multiplier))


# --- ngày giờ ------------------------------------------------------------------------------------


def parse_vn_datetime(value: Optional[str], default_year: Optional[int] = None) -> Optional[datetime]:
    """Đọc mọi dạng ngày giờ Ticketbox dùng, trả về datetime theo giờ Việt Nam (+07:00).

    Hỗ trợ: ISO-8601 (có/không múi giờ, `Z` là UTC), "19:30, 15 tháng 11, 2025", "18:00 - 20:30,
    29 Tháng 11, 2026", "T7, 15/11/2025", "15/11/2025 19:30", "20:00 Thứ Bảy 24.10". Thiếu giờ thì
    00:00, thiếu năm thì lấy `default_year` (mặc định năm nay).
    """
    if not value:
        return None
    text = value.strip()
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        return parsed.astimezone(VN) if parsed.tzinfo else parsed.replace(tzinfo=VN)
    except ValueError:
        pass

    folded = fold(text)
    time_match = re.search(r"(\d{1,2})[:h](\d{2})", folded)
    hour, minute = (int(time_match.group(1)), int(time_match.group(2))) if time_match else (0, 0)
    # Bỏ phần giờ ("18:00 - 20:30") để số giờ không bị đọc nhầm thành ngày.
    without_time = re.sub(r"\d{1,2}[:h]\d{2}", " ", folded)

    words = re.search(r"(\d{1,2})\s*thang\s*(\d{1,2})(?:\D+(\d{4}))?", without_time)
    numeric = re.search(r"(\d{1,2})[/.\-](\d{1,2})(?:[/.\-](\d{2,4}))?", without_time)
    match = words or numeric
    if not match:
        return None
    day, month = int(match.group(1)), int(match.group(2))
    year_text = match.group(3)
    if year_text:
        year = int(year_text) + (2000 if len(year_text) == 2 else 0)
    else:
        year = default_year or datetime.now(VN).year
    try:
        return datetime(year, month, day, hour, minute, tzinfo=VN)
    except ValueError:
        return None


def split_date_time(value: Optional[str]) -> tuple:
    """ISO hoặc chữ → ("YYYY-MM-DD", "HH:MM") theo giờ Việt Nam; không đọc được → ("", "")."""
    moment = parse_vn_datetime(value)
    if not moment:
        return "", ""
    return moment.strftime("%Y-%m-%d"), moment.strftime("%H:%M")


# --- trạng thái vé -------------------------------------------------------------------------------


def nhan_dien_trang_thai(status: Optional[str] = None, text: Optional[str] = None) -> tuple:
    """(mã trạng thái, số vé còn hoặc None) từ mã JSON và/hoặc chữ hiển thị trên trang.

    Mã JSON (`status` của hạng vé) đáng tin hơn chữ nên được xét trước. Chữ dùng cho các cách hiển thị
    "Hết vé", "Sắp hết vé", "Còn 12 vé", "Vé ngừng bán online", "Mua vé ngay", và bản tiếng Anh mà
    API trả trong `statusName` ("Sold out", "Book now", "Online booking closed", "Ticket available soon").
    Ticketbox hiện không công bố số vé còn lại; hàm vẫn đọc "Còn X vé" phòng khi trang hiện.
    """
    code = STATUS_CODE.get((status or "").strip().lower())
    folded = fold(text)
    remaining_match = re.search(r"\bcon\s*(?:lai\s*)?(\d[\d.,]*)\s*ve\b", folded) or re.search(
        r"\b(\d[\d.,]*)\s*(?:tickets?|ve)\s*(?:left|con lai)\b", folded)
    remaining = int(re.sub(r"[.,]", "", remaining_match.group(1))) if remaining_match else None

    if code:
        if code == "CON_VE" and _sap_het(folded):
            return "SAP_HET", remaining
        return code, remaining
    if not folded.strip():
        return "KHONG_RO", None
    # "Sắp hết vé" chứa "hết vé": phải xét "sắp hết" trước.
    if _sap_het(folded):
        return "SAP_HET", remaining
    if re.search(r"\bhet ve\b|sold ?out|\bhet hang\b", folded):
        return "HET_VE", 0
    if remaining is not None:
        return ("HET_VE", 0) if remaining == 0 else ("CON_VE", remaining)
    if re.search(r"ngung ban|dung ban|booking closed|sale closed|registration closed|da dong", folded):
        return "NGUNG_BAN", None
    if re.search(r"sap mo ban|chua mo ban|available soon|coming soon|mo ban luc", folded):
        return "CHUA_MO_BAN", None
    if re.search(r"mua ve|dat ve|dang ky ngay|book now|register now|con ve|buy", folded):
        return "CON_VE", None
    return "KHONG_RO", None


def _sap_het(folded: str) -> bool:
    return bool(re.search(r"sap het|gan het|selling fast|almost sold out|few left", folded))


def trang_thai_tong(codes: Iterable[str]) -> str:
    """Trạng thái của cả suất, gộp từ trạng thái các hạng vé.

    * còn hạng đang bán → CON_VE (SAP_HET nếu mọi hạng đang bán đều sắp hết);
    * mọi hạng đều hết → HET_VE;
    * không hạng nào đang bán, còn hạng chưa mở bán → CHUA_MO_BAN;
    * còn lại (ví dụ vài hạng hết, vài hạng đóng bán vì suất đã diễn) → NGUNG_BAN.

    Không đoán "hết vé" theo tỷ lệ: trạng thái từng hạng vẫn có trong trang_thai_ngay.csv để tự tính.
    """
    codes = [code for code in codes if code and code != "KHONG_RO"]
    if not codes:
        return "KHONG_RO"
    selling = [code for code in codes if code in ("CON_VE", "SAP_HET")]
    if selling:
        return "SAP_HET" if all(code == "SAP_HET" for code in selling) else "CON_VE"
    if all(code == "HET_VE" for code in codes):
        return "HET_VE"
    if "CHUA_MO_BAN" in codes:
        return "CHUA_MO_BAN"
    return "NGUNG_BAN"


# --- thành phố -----------------------------------------------------------------------------------

# Cách viết thống nhất. Khoá là tên đã bỏ dấu, bỏ tiền tố hành chính.
CITY_CANONICAL = {
    "ho chi minh": "TP. Hồ Chí Minh",
    "hcm": "TP. Hồ Chí Minh",
    "tp hcm": "TP. Hồ Chí Minh",
    "tphcm": "TP. Hồ Chí Minh",
    "sai gon": "TP. Hồ Chí Minh",
    "ha noi": "Hà Nội",
    "hn": "Hà Nội",
    "da nang": "Đà Nẵng",
    "hai phong": "Hải Phòng",
    "hue": "Huế",
    "thua thien hue": "Huế",
    "can tho": "Cần Thơ",
}


def _city_key(value: str) -> str:
    key = fold(value).replace(".", " ")
    key = re.sub(r"^(thanh pho|tinh|tp)\s+", "", re.sub(r"\s+", " ", key).strip())
    return re.sub(r"\s+", " ", key).strip()


def normalize_city(value: Optional[str]) -> str:
    """Đưa tên thành phố về một cách viết ("Thành Phố Hồ Chí Minh" → "TP. Hồ Chí Minh")."""
    if not value or not value.strip():
        return ""
    key = _city_key(value)
    if key in CITY_CANONICAL:
        return CITY_CANONICAL[key]
    # Tỉnh/thành khác: bỏ tiền tố hành chính, giữ nguyên dấu.
    return re.sub(r"(?i)^\s*(thành phố|tỉnh|tp\.?)\s+", "", value.strip())


def city_from_address(address: Optional[str]) -> str:
    """Phần cuối của địa chỉ thường là tỉnh/thành ("…, Thành Phố Hồ Chí Minh")."""
    parts = [part.strip() for part in (address or "").split(",") if part.strip()]
    return normalize_city(parts[-1]) if parts else ""


def event_id_from_url(url: str) -> Optional[int]:
    """`https://ticketbox.vn/live-show-bang-kieu-26523` → 26523."""
    match = re.search(r"-(\d+)/?(?:\?.*)?$", url or "")
    return int(match.group(1)) if match else None


# --- nhật ký, CSV --------------------------------------------------------------------------------


def log(message: str, error: bool = False) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with LOG_PATH.open("a", encoding="utf-8") as handle:
        handle.write(f"{datetime.now(VN).isoformat(timespec='seconds')} {'LỖI ' if error else ''}{message}\n")
    print(f"  {'!' if error else '·'} {message}", file=sys.stderr if error else sys.stdout)


def read_csv(path: Path) -> list:
    if not path.exists():
        return []
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def write_csv(path: Path, columns: list, rows: Iterable[dict]) -> None:
    """Ghi tệp tạm rồi đổi tên: dừng giữa chừng không để lại một CSV hỏng.

    `utf-8-sig` (có BOM) để Excel trên Windows mở đúng tiếng Việt khi nhấp đúp vào CSV.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({column: row.get(column, "") for column in columns})
    tmp.replace(path)


def append_csv(path: Path, columns: list, rows: list) -> None:
    """Ghi THÊM vào cuối — dữ liệu chụp hằng ngày không bao giờ bị ghi đè."""
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    new_file = not path.exists() or path.stat().st_size == 0
    with path.open("a", encoding="utf-8-sig" if new_file else "utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, extrasaction="ignore")
        if new_file:
            writer.writeheader()
        for row in rows:
            writer.writerow({column: row.get(column, "") for column in columns})


# --- truy cập Ticketbox --------------------------------------------------------------------------


def robots() -> urllib.robotparser.RobotFileParser:
    """Đọc robots.txt bằng CHÍNH User-Agent của script.

    `RobotFileParser.read()` tải bằng UA mặc định "Python-urllib", bị Ticketbox trả 403 — và gặp 403
    thì thư viện coi như cấm hết. Tải bằng UA thật rồi mới phân tích; tải hỏng thì coi như cấm hết
    (an toàn), không đoán là được phép.
    """
    parser = urllib.robotparser.RobotFileParser(f"{SITE}/robots.txt")
    request = urllib.request.Request(f"{SITE}/robots.txt", headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            parser.parse(response.read().decode("utf-8", "replace").splitlines())
    except Exception as error:  # noqa: BLE001
        print(f"Không đọc được robots.txt ({error}) — coi như cấm hết.", file=sys.stderr)
        parser.disallow_all = True
    return parser


def allowed(parser: urllib.robotparser.RobotFileParser, url: str) -> bool:
    path = re.sub(r"^https?://[^/]+", "", url)
    if any(path.startswith(blocked) for blocked in CAM_TRUY_CAP):
        return False
    return parser.can_fetch(USER_AGENT, url) and parser.can_fetch("*", url)


def pause() -> None:
    """Chậm có chủ đích: 2–4 giây giữa hai lần gọi bất kỳ tới Ticketbox."""
    time.sleep(random.uniform(2.0, 4.0))


class Ticketbox:
    """Chromium headless mở trang ticketbox.vn như một người xem, rồi đọc đúng các API JSON trang đó gọi.

    Mọi lần gọi đều chờ 2–4 giây trước, đều mang User-Agent nghiên cứu, không đăng nhập, không gửi
    cookie tài khoản. Dùng trong `with Ticketbox() as tb:`.
    """

    def __init__(self) -> None:
        self.rules = robots()
        self.category_names: dict = {}
        self._playwright = None
        self._browser = None
        self._context = None

    def __enter__(self) -> "Ticketbox":
        if not allowed(self.rules, f"{SITE}/"):
            raise SystemExit("robots.txt không cho phép truy cập trang chủ Ticketbox — dừng.")
        from playwright.sync_api import sync_playwright

        self._playwright = sync_playwright().start()
        self._browser = self._playwright.chromium.launch(headless=True)
        self._context = self._browser.new_context(
            user_agent=USER_AGENT, locale="vi-VN", extra_http_headers={"Accept-Language": "vi"})
        page = self._context.new_page()

        def capture(response) -> None:
            if "gin/api/v2/events/categories" not in response.url:
                return
            try:
                for item in (response.json().get("data") or {}).get("results") or []:
                    self.category_names[item["code"]] = item["name"]["vi"]
            except Exception:  # noqa: BLE001 — thiếu tên thể loại thì dùng mã
                pass

        page.on("response", capture)
        # Mở trang chủ đúng một lần: lấy bảng tên thể loại mà trang tự gọi, như một người xem.
        page.goto(f"{SITE}/", wait_until="networkidle", timeout=90_000)
        page.close()
        return self

    def __exit__(self, *_exc) -> None:
        if self._browser:
            self._browser.close()
        if self._playwright:
            self._playwright.stop()

    def _get(self, url: str, referer: str = f"{SITE}/") -> dict:
        pause()
        response = self._context.request.get(
            url, headers={"Accept": "application/json", "Referer": referer}, timeout=30_000)
        if not response.ok:
            raise RuntimeError(f"HTTP {response.status} khi gọi {url}")
        return response.json()

    def search(self, kind: str, page: int, limit: int = 20) -> tuple:
        """(danh sách sự kiện rút gọn, còn trang sau không). `kind` = "upcoming" | "past"."""
        body = self._get(f"{API}/search/v3/events?filter={kind}&page={page}&limit={limit}",
                         referer=f"{SITE}/search?q=")
        data = body.get("data") or {}
        return data.get("results") or [], bool((data.get("pagination") or {}).get("hasMore"))

    def event(self, event_id: int, page_url: str) -> dict:
        if not allowed(self.rules, page_url):
            raise RuntimeError(f"robots.txt không cho phép {page_url}")
        detail = (self._get(f"{API}/gin/api/v2/events/{event_id}", referer=page_url).get("data") or {}).get("result")
        if not detail:
            raise RuntimeError("phản hồi không có dữ liệu sự kiện")
        return detail

    def lineup(self, event_id: int, page_url: str) -> list:
        """Tên nghệ sĩ trong mục Lineup của trang; không có thì danh sách rỗng."""
        body = self._get(f"{API}/artisthub/api/v1/creators/lineup/{event_id}", referer=page_url)
        results = (body.get("data") or {}).get("results") or []
        return [clean(item.get("name")) for item in results if isinstance(item, dict) and item.get("name")]


# --- đọc một suất diễn ---------------------------------------------------------------------------


def showings_of(detail: dict) -> list:
    """Các suất theo thứ tự giờ diễn. Sự kiện không khai suất thì coi cả sự kiện là một suất."""
    showings = [showing for showing in detail.get("showings") or [] if showing.get("startTime")]
    if not showings and detail.get("startTime"):
        showings = [{"startTime": detail["startTime"], "endTime": detail.get("endTime"), "ticketTypes": []}]
    # Cùng giờ bắt đầu (vé tham quan nhiều "suất" giống hệt nhau): xếp tiếp theo id cho ổn định.
    return sorted(showings, key=lambda showing: (showing["startTime"], str(showing.get("id", ""))))


def showing_groups(detail: dict) -> list:
    """[(("YYYY-MM-DD", "HH:MM"), [suất…])] — các suất TRÙNG ngày giờ bắt đầu được gộp làm một.

    Ticketbox đôi khi khai hai suất cùng giờ bắt đầu (vé tham quan, workshop mở nhiều "suất" giống
    hệt nhau). Với bộ dữ liệu, một (url, ngày diễn, giờ diễn) là MỘT suất; hạng vé của các suất trùng
    được hợp lại ở `showing_tickets`.
    """
    groups: dict = {}
    for showing in showings_of(detail):
        groups.setdefault(split_date_time(showing.get("startTime")), []).append(showing)
    return list(groups.items())


def index_rows(rows: list) -> dict:
    """{(url, ngày diễn, giờ diễn): dòng}. Dữ liệu cũ còn dòng trùng thì lấy dòng có mã nhỏ nhất."""
    index: dict = {}
    for row in sorted(rows, key=lambda r: code_order(r["ma_su_kien"])):
        index.setdefault((row["url"], row["ngay_dien"], row["gio_dien"]), row)
    return index


def showing_tickets(showings) -> list:
    """Hạng vé của một suất (hoặc nhóm suất trùng giờ): [{ten_hang, gia, trang_thai, so_ve_con, mo_ban}].

    Trong một suất, tên trùng thì giữ hạng đầu. Cùng tên ở các suất trùng giờ thì gộp thành một: giữ giá đầu tiên, trạng
    thái gộp bằng `trang_thai_tong` (còn bán ở một suất là còn bán), giờ mở bán lấy sớm nhất.
    `mo_ban` là giờ bắt đầu bán của hạng vé (`startTime` của ticketType), theo giờ Việt Nam.
    """
    if isinstance(showings, dict):
        showings = [showings]
    merged: dict = {}
    statuses: dict = {}
    for showing in showings:
        seen_here = set()
        for ticket in showing.get("ticketTypes") or []:
            name = clean(ticket.get("name"))
            # Trùng tên TRONG một suất: giữ hạng đầu (như trước). Trùng tên GIỮA các suất trùng giờ: gộp.
            if not name or name in seen_here:
                continue
            seen_here.add(name)
            status, remaining = nhan_dien_trang_thai(ticket.get("status"), ticket.get("statusName"))
            opens = parse_vn_datetime(ticket.get("startTime"))
            statuses.setdefault(name, []).append(status)
            if name not in merged:
                merged[name] = {
                    "ten_hang": name,
                    "gia": 0 if ticket.get("isFree") else parse_price(ticket.get("price")),
                    "trang_thai": status,
                    "so_ve_con": remaining,
                    "mo_ban": opens,
                }
                continue
            item = merged[name]
            if opens and (item["mo_ban"] is None or opens < item["mo_ban"]):
                item["mo_ban"] = opens
            if remaining is not None:
                item["so_ve_con"] = (item["so_ve_con"] or 0) + remaining
    for name, item in merged.items():
        if len(statuses[name]) > 1:
            item["trang_thai"] = trang_thai_tong(statuses[name])
    return list(merged.values())


# Nhóm dùng để phân tích: biểu diễn trực tiếp có địa điểm thật. So khớp sau khi bỏ dấu.
NHOM_THE_LOAI = ("nhac song", "san khau & nghe thuat", "the thao")


def nhom_phan_tich(row: dict) -> str:
    """CO nếu thể loại là Nhạc sống, Sân khấu & Nghệ thuật hoặc Thể Thao VÀ địa điểm không phải Online."""
    category = re.sub(r"\s+", " ", fold(row.get("the_loai"))).strip()
    online = "online" in fold(row.get("dia_diem"))
    return "CO" if category in NHOM_THE_LOAI and not online else "KHONG"


def code_order(code: str) -> tuple:
    """Thứ tự tự nhiên của mã: TB2 < TB10, TB001 < TB001-S1 < TB001-S2 < TB002."""
    numbers = [int(part) for part in re.findall(r"\d+", code or "")]
    return tuple(numbers) or (0,)


def snapshot_key(row: dict) -> tuple:
    return row.get("ngay_cao", ""), row.get("ma_su_kien", ""), row.get("ten_hang", "")
