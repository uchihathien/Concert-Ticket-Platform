"""Phần dùng chung của ba script Ticketbox: đường dẫn dữ liệu, bỏ dấu, slug."""

from __future__ import annotations

import json
import re
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA_DIR = HERE / "data"
IMAGES_DIR = DATA_DIR / "images"
EVENTS_JSON = DATA_DIR / "events.json"
NORMALIZED_JSON = DATA_DIR / "normalized.json"
REPORT_MD = DATA_DIR / "normalization-report.md"
IMPORTED_JSON = DATA_DIR / "imported.json"
SCRAPE_LOG = DATA_DIR / "scrape-errors.log"

# User-Agent nói rõ ai đang truy cập và để làm gì — không giả làm trình duyệt người dùng.
USER_AGENT = (
    "Mozilla/5.0 (compatible; NexaTicket-KLTN/1.0; "
    "+thu thap du lieu demo cho khoa luan tot nghiep, khong thuong mai)"
)


def fold(value: str) -> str:
    """Thường hoá + bỏ dấu tiếng Việt (đ → d). Dùng làm khoá so khớp, không để hiển thị."""
    lowered = (value or "").lower().replace("đ", "d")
    decomposed = unicodedata.normalize("NFD", lowered)
    return "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")


def slugify(value: str, max_length: int = 80) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", fold(value)).strip("-")
    return slug[:max_length].rstrip("-") or "su-kien"


def load_json(path: Path, default):
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def save_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    # Ghi tệp tạm rồi đổi tên: dừng giữa chừng không để lại một JSON hỏng.
    tmp.replace(path)
