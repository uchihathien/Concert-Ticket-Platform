#!/usr/bin/env python3
"""Bước 2 — chuẩn hoá data/events.json thành data/normalized.json cho khớp ràng buộc của NexaTicket.

Ràng buộc đọc từ code (không đoán):
* AdminCatalogController: VenueRequest(name ≤200, city ≤100, address tự do) · ZoneRequest(zoneCode
  ≤16, name ≤100, kind SEATED|STANDING, capacity > 0) · EventRequest(title ≤200, summary ≤500,
  category ≤50, posterUrl ≤500) · TicketTypeRequest(name ≤100, priceVnd ≥ 0).
* packages/ui/src/categories.ts: nhac-song · san-khau · the-thao · hoi-thao · khac.
* PosterUploadUseCase: image/jpeg | image/png | image/webp, tối đa MEDIA_MAX_BYTES (mặc định 8 MB).
* Event.preflight: phải có suất, mỗi suất có hạng vé, cửa bán mở TRƯỚC giờ diễn.

Đầu ra: data/normalized.json (bước 3 chỉ đọc tệp này) và data/normalization-report.md.

    .venv/bin/python normalize.py
    .venv/bin/python normalize.py --offline     # không hỏi API danh sách địa điểm đang có
"""

from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

from tb_common import DATA_DIR, EVENTS_JSON, NORMALIZED_JSON, REPORT_MD, fold, load_json, save_json, slugify

VN = timezone(timedelta(hours=7))

TITLE_MAX = 200
SUMMARY_MAX = 500
NAME_MAX = 100
VENUE_NAME_MAX = 200
CITY_MAX = 100
ZONE_CODE_MAX = 16
MAX_SESSIONS = 5
MAX_ZONES = 6
IMAGE_MAX_WIDTH = 1200
IMAGE_MAX_BYTES = 8 * 1024 * 1024
NORMALIZED_IMAGES = DATA_DIR / "images-normalized"

CATEGORIES = ("nhac-song", "san-khau", "the-thao", "hoi-thao", "khac")

# Mã thể loại của Ticketbox (categoriesV2) → mã NexaTicket. `None` = phải đoán theo tên.
TICKETBOX_CATEGORY = {
    "music": "nhac-song",
    "theatersandart": "san-khau",
    "sport": "the-thao",
    "seminarsworkshops": "hoi-thao",
    "attractionsexperiences": None,
    "others": None,
}

# Từ khoá (đã bỏ dấu) → thể loại. Thứ tự là thứ tự ưu tiên khi một tên khớp nhiều nhóm.
CATEGORY_KEYWORDS = [
    ("hoi-thao", ["hoi thao", "workshop", "khoa hoc", "seminar", "talkshow", "dao tao", "lop hoc", "conference"]),
    ("the-thao", ["the thao", "marathon", "chay bo", "bong da", "bong ro", "giai dau", "tennis", "pickleball", "run"]),
    ("san-khau", ["kich", "san khau", "trien lam", "nghe thuat", "mua roi", "ballet", "opera", "hai kich", "ca tru", "nha hat"]),
    ("nhac-song", ["am nhac", "live music", "concert", "live show", "liveshow", "show", "fan meeting", "fanmeet", "acoustic", "dj", "festival", "music"]),
]

# Tiền tố tên thành phố trong ngoặc vuông ở đầu tiêu đề: thông tin đã có ở địa điểm.
CITY_TAG = re.compile(r"^\s*[\[(](hcm|tp\.?\s*hcm|tphcm|sg|sai gon|hn|ha noi|dn|da nang|hp|hai phong|hue|ct|can tho)[\])]\s*[-–:|]?\s*", re.I)

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

EMOJI = re.compile(
    "[\U0001F000-\U0001FAFF\U00002600-\U000027BF\U0001F900-\U0001F9FF\U00002190-\U000021FF"
    "\U00002B00-\U00002BFF\U0000FE0F\U0000200D\U0000200B]+"
)


# --- văn bản ------------------------------------------------------------------


def clean_text(value: str | None) -> str:
    text = re.sub(r"(?s)<[^>]+>", " ", value or "")
    text = "".join(ch for ch in text if unicodedata.category(ch)[0] != "C" or ch in "\n")
    return re.sub(r"[ \t]+", " ", text).strip()


def truncate_words(value: str, limit: int) -> str:
    """Cắt theo giới hạn, không cắt giữa từ, thêm "…" nếu bị cắt (dấu … tính vào giới hạn)."""
    value = value.strip()
    if len(value) <= limit:
        return value
    cut = value[: limit - 1]
    if " " in cut:
        cut = cut[: cut.rfind(" ")]
    return cut.rstrip(" ,.;:-–") + "…"


def clean_title(value: str | None) -> str:
    title = EMOJI.sub("", clean_text(value)).replace("\n", " ")
    title = CITY_TAG.sub("", title)
    title = re.sub(r"\s+", " ", title).strip(" -–|")
    return truncate_words(title, TITLE_MAX)


def summarize(description: str | None, max_sentences: int = 3) -> str:
    """1–3 câu đầu của mô tả, tối đa 500 ký tự."""
    text = EMOJI.sub("", clean_text(description))
    pieces = [piece.strip() for piece in re.split(r"(?<=[.!?…])\s+|\n+", text) if len(piece.strip()) > 1]
    summary = " ".join(pieces[:max_sentences])
    return truncate_words(re.sub(r"\s+", " ", summary), SUMMARY_MAX)


# --- giá ------------------------------------------------------------------------


def parse_price(value) -> int | None:
    """Giá về số nguyên VND. "Miễn phí" → 0. Không đọc được → None (hạng vé đó bị bỏ)."""
    if value is None:
        return None
    if isinstance(value, bool):
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


# --- ngày giờ -------------------------------------------------------------------


def parse_vn_datetime(value: str | None, default_year: int | None = None) -> datetime | None:
    """Đọc mọi dạng ngày giờ tiếng Việt Ticketbox dùng, trả về datetime có múi giờ +07:00.

    Hỗ trợ: ISO-8601 (có/không múi giờ), "19:30, 15 tháng 11, 2025", "18:00 - 20:30, 29 Tháng 11,
    2026", "T7, 15/11/2025", "15/11/2025 19:30", "20:00 Thứ Bảy 24.10". Thiếu giờ thì 00:00, thiếu
    năm thì lấy `default_year`.
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


def shift_to_future(moment: datetime, now: datetime, margin: timedelta = timedelta(days=1)) -> tuple[datetime, int]:
    """Ngày đã qua (hoặc sát giờ hơn `margin`) thì cộng thêm số tuần cần thiết — giữ thứ và giờ.

    Trả về (thời điểm mới, số tuần đã cộng).
    """
    if moment > now + margin:
        return moment, 0
    weeks = int((now + margin - moment) / timedelta(weeks=1)) + 1
    return moment + timedelta(weeks=weeks), weeks


# --- thành phố, thể loại -------------------------------------------------------


def _city_key(value: str) -> str:
    key = fold(value).replace(".", " ")
    key = re.sub(r"^(thanh pho|tinh|tp)\s+", "", re.sub(r"\s+", " ", key).strip())
    return re.sub(r"\s+", " ", key).strip()


def normalize_city(value: str | None, known: dict[str, str] | None = None) -> str | None:
    """Đưa tên thành phố về một cách viết. Ưu tiên cách viết đang có trong hệ thống (`known`).

    `known` là {khoá đã bỏ dấu: cách viết} lấy từ API danh sách địa điểm.
    """
    if not value or not value.strip():
        return None
    key = _city_key(value)
    if known and key in known:
        return known[key]
    if key in CITY_CANONICAL:
        canonical = CITY_CANONICAL[key]
        return (known or {}).get(_city_key(canonical), canonical)
    # Tỉnh/thành khác: bỏ tiền tố hành chính, giữ nguyên dấu.
    stripped = re.sub(r"(?i)^\s*(thành phố|tỉnh|tp\.?)\s+", "", value.strip())
    return truncate_words(stripped, CITY_MAX)


def map_category(code: str | None, ticketbox_name: str | None, title: str | None) -> tuple[str, str]:
    """(mã NexaTicket, cách ra kết quả: 'mã' | 'tên thể loại' | 'đoán theo tiêu đề' | 'mặc định')."""
    if code in TICKETBOX_CATEGORY and TICKETBOX_CATEGORY[code]:
        return TICKETBOX_CATEGORY[code], "mã"
    for source, how in ((ticketbox_name, "tên thể loại"), (title, "đoán theo tiêu đề")):
        folded = fold(source or "")
        for category, keywords in CATEGORY_KEYWORDS:
            if any(re.search(rf"\b{re.escape(keyword)}\b", folded) for keyword in keywords):
                return category, how
    return "khac", "mặc định"


# --- khu / hạng vé --------------------------------------------------------------


def make_zone_code(name: str, taken: set[str] | None = None) -> str:
    """"Vé VIP" → VIP · "Hạng Đồng" → HANG-DONG. Chỉ A–Z, 0–9, '-', tối đa 16, không trùng `taken`."""
    folded = fold(name).upper()
    folded = re.sub(r"^VE\s+", "", folded)
    code = re.sub(r"[^A-Z0-9]+", "-", folded).strip("-") or "KHU"
    if len(code) > ZONE_CODE_MAX:
        code = code[:ZONE_CODE_MAX]
        if "-" in code[ZONE_CODE_MAX // 2 :]:
            code = code[: code.rfind("-")]
        code = code.strip("-")
    taken = taken if taken is not None else set()
    candidate, suffix = code, 2
    while candidate in taken:
        tail = f"-{suffix}"
        candidate = code[: ZONE_CODE_MAX - len(tail)].rstrip("-") + tail
        suffix += 1
    taken.add(candidate)
    return candidate


def capacities(count: int, cheapest: int = 500, priciest: int = 50) -> list[int]:
    """Sức chứa theo thứ hạng giá TĂNG dần: rẻ nhất nhiều chỗ, đắt nhất ít chỗ, ở giữa chia đều."""
    if count <= 0:
        return []
    if count == 1:
        return [cheapest]
    step = (cheapest - priciest) / (count - 1)
    return [int(round(cheapest - step * index)) for index in range(count)]


def build_zones(ticket_types: list[dict]) -> list[dict]:
    seen: set[str] = set()
    priced = []
    for ticket in ticket_types:
        name = truncate_words(clean_text(ticket.get("name")).replace("\n", " "), NAME_MAX)
        price = parse_price(ticket.get("priceVnd"))
        if not name or price is None or fold(name) in seen:
            continue
        seen.add(fold(name))
        priced.append({"name": name, "priceVnd": price})
    priced.sort(key=lambda ticket: ticket["priceVnd"])
    priced = priced[:MAX_ZONES]
    taken: set[str] = set()
    sizes = capacities(len(priced))
    return [
        {
            "zoneCode": make_zone_code(ticket["name"], taken),
            "name": ticket["name"],
            "kind": "STANDING",
            "capacity": size,
            "ticketType": ticket,
        }
        for ticket, size in zip(priced, sizes)
    ]


# --- ảnh -------------------------------------------------------------------------


def normalize_image(source: Path, slug: str) -> Path | None:
    """JPEG, rộng tối đa 1200px, dưới trần dung lượng của media service."""
    from PIL import Image

    NORMALIZED_IMAGES.mkdir(parents=True, exist_ok=True)
    target = NORMALIZED_IMAGES / f"{slug}.jpg"
    with Image.open(source) as image:
        image = image.convert("RGB")
        if image.width > IMAGE_MAX_WIDTH:
            image = image.resize((IMAGE_MAX_WIDTH, round(image.height * IMAGE_MAX_WIDTH / image.width)), Image.LANCZOS)
        for quality in (85, 75, 65, 55):
            image.save(target, "JPEG", quality=quality, optimize=True, progressive=True)
            if target.stat().st_size <= IMAGE_MAX_BYTES:
                return target
    return None


# --- chạy -------------------------------------------------------------------------


def known_cities_and_venues(offline: bool) -> tuple[dict[str, str], dict[str, dict], str]:
    """Cách viết thành phố + địa điểm đang có trong hệ thống, từ API listVenues."""
    if offline:
        return {}, {}, "không hỏi API (--offline)"
    try:
        from nexa_api import NexaApi

        venues = NexaApi().list_venues()
    except Exception as error:  # noqa: BLE001
        return {}, {}, f"không hỏi được API ({error}); dùng bảng cách viết có sẵn"
    cities = {_city_key(venue["city"]): venue["city"] for venue in venues}
    by_key = {venue_key(venue["name"], venue["city"]): venue for venue in venues}
    return cities, by_key, f"đọc {len(venues)} địa điểm từ API: {', '.join(sorted(set(cities.values())))}"


def venue_key(name: str, city: str) -> str:
    return re.sub(r"\s+", " ", fold(name)).strip() + "|" + _city_key(city)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--offline", action="store_true", help="không gọi API NexaTicket để lấy địa điểm đang có")
    args = parser.parse_args()

    raw = load_json(EVENTS_JSON, [])
    if not raw:
        print(f"Chưa có {EVENTS_JSON} — chạy scrape_ticketbox.py trước.", file=sys.stderr)
        return 1

    now = datetime.now(VN).replace(second=0, microsecond=0)
    cities, existing_venues, venue_source = known_cities_and_venues(args.offline)

    out: list[dict] = []
    rejected: list[tuple[str, str]] = []
    guessed: list[tuple[str, str, str]] = []
    category_table: Counter = Counter()
    city_table: Counter = Counter()
    shifted: list[tuple[str, int]] = []
    seen_events: set[str] = set()
    venues_planned: dict[str, dict] = {}

    for item in raw:
        label = item.get("title") or item.get("sourceUrl")
        title = clean_title(item.get("title"))
        venue_raw = item.get("venue") or {}
        city = normalize_city(venue_raw.get("city"), cities)
        venue_name = truncate_words(clean_text(venue_raw.get("name")).replace("\n", " "), VENUE_NAME_MAX)

        sessions = []
        event_shift = 0
        for session in item.get("sessions") or []:
            starts = parse_vn_datetime(session.get("startsAt"))
            if not starts:
                continue
            ends = parse_vn_datetime(session.get("endsAt"))
            starts_new, weeks = shift_to_future(starts, now)
            ends_new = ends + timedelta(weeks=weeks) if ends and ends > starts else None
            event_shift = max(event_shift, weeks)
            sessions.append({
                "startsAt": starts_new.isoformat(),
                "endsAt": ends_new.isoformat() if ends_new else None,
                "salesOpenAt": now.isoformat(),
                "salesCloseAt": (starts_new - timedelta(hours=1)).isoformat(),
                "shiftedWeeks": weeks,
            })
        # Trùng giờ (hai suất gốc rơi vào cùng một mốc sau khi dời) thì giữ một.
        unique = {session["startsAt"]: session for session in sessions}
        sessions = sorted(unique.values(), key=lambda session: session["startsAt"])[:MAX_SESSIONS]

        zones = build_zones(item.get("ticketTypes") or [])

        reasons = []
        if not title:
            reasons.append("thiếu tên")
        if not city:
            reasons.append("thiếu thành phố (sự kiện online / không có địa chỉ)")
        if not venue_name:
            reasons.append("thiếu tên địa điểm")
        if not sessions:
            reasons.append("không có suất diễn nào")
        if not zones:
            reasons.append("không có hạng vé nào có giá")
        if reasons:
            rejected.append((label, "; ".join(reasons)))
            continue

        key_event = fold(title) + "|" + venue_key(venue_name, city)
        if key_event in seen_events:
            rejected.append((label, "trùng sự kiện đã giữ (cùng tiêu đề + địa điểm)"))
            continue
        seen_events.add(key_event)
        if event_shift:
            shifted.append((title, event_shift))

        category, how = map_category(item.get("categoryCode"), item.get("category"), title)
        category_table[(item.get("categoryCode") or "—", item.get("category") or "—", category)] += 1
        if how != "mã":
            guessed.append((title, f"{item.get('category')} ({item.get('categoryCode')})", f"{category} — {how}"))
        city_table[(venue_raw.get("city") or "—", city)] += 1

        vkey = venue_key(venue_name, city)
        existing = existing_venues.get(vkey)
        venue = venues_planned.setdefault(vkey, {
            "key": vkey,
            "name": venue_name,
            "city": city,
            "address": clean_text(venue_raw.get("address")).replace("\n", " ") or None,
            "existingVenueId": existing["id"] if existing else None,
        })

        slug = slugify(title)
        image_path = None
        if item.get("imagePath"):
            source = DATA_DIR / item["imagePath"]
            try:
                normalized = normalize_image(source, slug) if source.exists() else None
                image_path = str(normalized.relative_to(DATA_DIR)) if normalized else None
            except Exception as error:  # noqa: BLE001 — thiếu ảnh không loại sự kiện
                print(f"  ! ảnh của {title}: {error}", file=sys.stderr)

        out.append({
            "sourceUrl": item["sourceUrl"],
            "sourceId": item.get("sourceId"),
            "venue": venue,
            "event": {
                "title": title,
                "summary": summarize(item.get("description")),
                "description": clean_text(item.get("description")),
                "category": category,
            },
            "sessions": sessions,
            "zones": zones,
            "imagePath": image_path,
            "imageContentType": "image/jpeg" if image_path else None,
        })

    save_json(NORMALIZED_JSON, out)
    write_report(raw, out, rejected, guessed, category_table, city_table, shifted, venue_source, venues_planned, now)
    print(f"Vào {len(raw)} · ra {len(out)} · loại {len(rejected)}. Báo cáo: {REPORT_MD}")
    return 0


def write_report(raw, out, rejected, guessed, category_table, city_table, shifted, venue_source, venues, now) -> None:
    lines = [
        "# Báo cáo chuẩn hoá dữ liệu Ticketbox",
        "",
        f"Chạy lúc {now.isoformat()} — nguồn: `data/events.json`, đầu ra: `data/normalized.json`.",
        "",
        "Dữ liệu sự kiện công khai từ Ticketbox (ticketbox.vn), chỉ dùng làm dữ liệu demo cho khoá luận.",
        "",
        "## Tổng quan",
        "",
        f"- Sự kiện vào: **{len(raw)}**",
        f"- Sự kiện ra: **{len(out)}**",
        f"- Bị loại: **{len(rejected)}**",
        f"- Địa điểm: **{len(venues)}** ({sum(1 for v in venues.values() if v['existingVenueId'])} dùng lại địa điểm đã có)",
        f"- Suất diễn: **{sum(len(e['sessions']) for e in out)}** · hạng vé (= khu đứng): **{sum(len(e['zones']) for e in out)}**",
        f"- Có ảnh bìa: **{sum(1 for e in out if e['imagePath'])}** / {len(out)}",
        f"- Địa điểm đang có trong hệ thống: {venue_source}",
        "",
        "## Sự kiện bị loại",
        "",
    ]
    lines += [f"- {title} — {reason}" for title, reason in rejected] or ["Không có."]
    lines += ["", "## Ánh xạ thể loại đã áp dụng", "", "| Mã Ticketbox | Tên Ticketbox | → NexaTicket | Số sự kiện |", "|---|---|---|---|"]
    lines += [f"| {code} | {name} | `{target}` | {count} |" for (code, name, target), count in sorted(category_table.items())]
    lines += ["", "## Các trường hợp phải đoán thể loại", ""]
    lines += [f"- {title} — Ticketbox: {source} → `{result}`" for title, source, result in guessed] or ["Không có."]
    lines += ["", "## Chuẩn hoá thành phố", "", "| Ticketbox | → NexaTicket | Số sự kiện |", "|---|---|---|"]
    lines += [f"| {source} | {target} | {count} |" for (source, target), count in sorted(city_table.items())]
    lines += ["", "## Suất diễn đã dời sang tương lai", "", "Ngày gốc đã qua (hoặc sát giờ) được cộng thêm số tuần, giữ nguyên thứ và giờ.", ""]
    lines += [f"- {title}: +{weeks} tuần" for title, weeks in shifted] or ["Không có."]
    lines += ["", "## Sự kiện sẽ nhập", "", "| Sự kiện | Thể loại | Thành phố | Suất | Hạng vé (giá thấp → cao) |", "|---|---|---|---|---|"]
    for event in out:
        prices = ", ".join(f"{z['ticketType']['name']} {z['ticketType']['priceVnd']:,}đ".replace(",", ".") for z in event["zones"])
        lines.append(f"| {event['event']['title']} | {event['event']['category']} | {event['venue']['city']} | {len(event['sessions'])} | {prices} |")
    REPORT_MD.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
