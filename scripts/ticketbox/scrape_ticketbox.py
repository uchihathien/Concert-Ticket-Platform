#!/usr/bin/env python3
"""Bước 1 — cào sự kiện CÔNG KHAI của Ticketbox về data/events.json (dữ liệu demo cho khoá luận).

CÁCH LẤY
========
Ticketbox là ứng dụng Next.js tải nội dung bằng JavaScript. Script mở trang chủ bằng Chromium
headless (Playwright) — đúng như một người xem — và đọc các phản hồi JSON mà chính trang đó gọi
(danh mục sự kiện theo thể loại, "mới mở bán", "xu hướng"…). Chi tiết từng sự kiện lấy từ API
công khai mà trang sự kiện đang gọi: `api-v2.ticketbox.vn/gin/api/v2/events/{id}`. Đọc JSON ổn
định hơn đọc HTML: đổi giao diện không làm hỏng script.

TUÂN THỦ
========
* robots.txt của ticketbox.vn được đọc lại mỗi lần chạy; đường bị cấm (/payment/, /my-tickets/,
  /profile/, /sign-in/…) không bao giờ được mở. Không đăng nhập.
* Chậm có chủ đích: chờ ngẫu nhiên 2–4 giây giữa hai lần gọi.
* User-Agent ghi rõ mục đích học tập.
* KHÔNG lấy dữ liệu cá nhân: không bình luận, không tên / email / SĐT người dùng. Chỉ thông tin
  sự kiện mà ban tổ chức đã công bố.

CÁCH CHẠY
=========
    .venv/bin/python scrape_ticketbox.py --limit 40
    .venv/bin/python scrape_ticketbox.py --limit 5 --no-images

Chạy lại được: sự kiện đã có trong data/events.json bị bỏ qua; lỗi từng sự kiện ghi vào
data/scrape-errors.log và script đi tiếp.
"""

from __future__ import annotations

import argparse
import html
import random
import re
import sys
import time
import urllib.request
import urllib.robotparser
from datetime import datetime, timedelta, timezone
from itertools import zip_longest

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import sync_playwright

from tb_common import EVENTS_JSON, IMAGES_DIR, SCRAPE_LOG, USER_AGENT, load_json, save_json, slugify

SITE = "https://ticketbox.vn"
API = "https://api-v2.ticketbox.vn/gin/api/v2"
VN = timezone(timedelta(hours=7))

# Những phản hồi JSON của trang chủ có chứa danh sách sự kiện.
LISTING_ENDPOINTS = ("discovery/categories", "discovery/onsale-sections", "discovery/banners")


def robots() -> urllib.robotparser.RobotFileParser:
    """Đọc robots.txt bằng CHÍNH User-Agent của script.

    `RobotFileParser.read()` tải bằng UA mặc định "Python-urllib", bị Ticketbox trả 403 — và gặp
    403 thì thư viện coi như cấm hết. Tải bằng UA thật rồi mới phân tích; tải hỏng thì coi như cấm
    hết (an toàn), không đoán là được phép.
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
    return parser.can_fetch(USER_AGENT, url) and parser.can_fetch("*", url)


def pause() -> None:
    time.sleep(random.uniform(2.0, 4.0))


def log_error(message: str) -> None:
    SCRAPE_LOG.parent.mkdir(parents=True, exist_ok=True)
    with SCRAPE_LOG.open("a", encoding="utf-8") as handle:
        handle.write(f"{datetime.now(VN).isoformat(timespec='seconds')} {message}\n")
    print(f"  ! {message}", file=sys.stderr)


def strip_html(value: str | None) -> str:
    """Bỏ thẻ, ảnh, script; giữ chữ. Xuống dòng ở các khối để câu không dính vào nhau."""
    text = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", " ", value or "")
    text = re.sub(r"(?i)<br\s*/?>|</(p|div|li|h\d)>", "\n", text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = html.unescape(text).replace("\xa0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    return re.sub(r"\n\s*\n+", "\n", text).strip()


def to_vn_iso(value: str | None) -> str | None:
    """`2026-11-29T11:00:00Z` → `2026-11-29T18:00:00+07:00`."""
    if not value:
        return None
    instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return instant.astimezone(VN).isoformat()


def city_from_address(address: str | None) -> str | None:
    """Phần cuối của địa chỉ thường là tỉnh/thành ("…, Thành Phố Hồ Chí Minh"). Chuẩn hoá ở bước 2."""
    if not address:
        return None
    parts = [part.strip() for part in address.split(",") if part.strip()]
    return parts[-1] if parts else None


def collect_candidates(listings: list[dict]) -> list[tuple[int, str | None]]:
    """(id sự kiện, gợi ý thể loại) — xen kẽ giữa các thể loại để lấy đủ loại, không dồn một loại."""
    by_category: dict[str, list[int]] = {}
    extra: list[int] = []
    for body in listings:
        result = (body.get("data") or {}).get("result") or (body.get("data") or {}).get("results")
        if isinstance(result, dict):
            for cate in result.get("bigCates") or []:
                code = str(cate.get("cateId"))
                by_category.setdefault(code, []).extend(e["id"] for e in cate.get("events") or [] if e.get("id"))
            for key, section in result.items():
                if isinstance(section, dict) and isinstance(section.get("events"), list):
                    extra.extend(e["id"] for e in section["events"] if e.get("id"))
                if key == "heroBanner" and isinstance(section, list):
                    extra.extend(e.get("eventId") for e in section if e.get("eventId"))
        elif isinstance(result, list):
            extra.extend(e["id"] for e in result if isinstance(e, dict) and e.get("id"))

    ordered: list[tuple[int, str | None]] = []
    seen: set[int] = set()
    for row in zip_longest(*by_category.values()):
        for cate_id, event_id in zip(by_category.keys(), row):
            if event_id and event_id not in seen:
                seen.add(event_id)
                ordered.append((event_id, cate_id))
    for event_id in extra:
        if event_id and event_id not in seen:
            seen.add(event_id)
            ordered.append((event_id, None))
    return ordered


def parse_event(detail: dict, category_names: dict[str, str]) -> dict:
    """Chỉ những trường ban tổ chức đã công bố về sự kiện. Không có dữ liệu cá nhân nào ở đây."""
    showings = detail.get("showings") or []
    sessions = []
    for showing in showings:
        starts = to_vn_iso(showing.get("startTime"))
        if starts:
            sessions.append({"startsAt": starts, "endsAt": to_vn_iso(showing.get("endTime")), "label": showing.get("showingTime")})
    if not sessions and detail.get("startTime"):
        sessions.append({"startsAt": to_vn_iso(detail["startTime"]), "endsAt": to_vn_iso(detail.get("endTime")), "label": detail.get("showingTime")})

    ticket_types: dict[str, int | None] = {}
    for showing in showings:
        for ticket in showing.get("ticketTypes") or []:
            name = (ticket.get("name") or "").strip()
            if name and name not in ticket_types:
                ticket_types[name] = 0 if ticket.get("isFree") else ticket.get("price")

    codes = detail.get("categoriesV2") or []
    return {
        "sourceId": detail.get("id"),
        "sourceUrl": f"{SITE}/{detail.get('url')}-{detail.get('id')}",
        "title": detail.get("title"),
        "category": category_names.get(codes[0], codes[0]) if codes else None,
        "categoryCode": codes[0] if codes else None,
        "description": strip_html(detail.get("description"))[:1000],
        "imageUrl": detail.get("bannerURL"),
        "venue": {
            "name": detail.get("venue"),
            "address": detail.get("address"),
            "city": city_from_address(detail.get("address")),
        },
        "sessions": sessions,
        "ticketTypes": [{"name": name, "priceVnd": price} for name, price in ticket_types.items()],
        "isFree": bool(detail.get("isFree")),
        "scrapedAt": datetime.now(VN).isoformat(timespec="seconds"),
    }


def download_image(request, url: str | None, slug: str) -> str | None:
    if not url:
        return None
    response = request.get(url, timeout=30_000)
    if not response.ok:
        raise RuntimeError(f"ảnh {url} → HTTP {response.status}")
    content_type = response.headers.get("content-type", "")
    extension = "png" if "png" in content_type else "webp" if "webp" in content_type else "jpg"
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    path = IMAGES_DIR / f"{slug}.{extension}"
    path.write_bytes(response.body())
    return str(path.relative_to(IMAGES_DIR.parent))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--limit", type=int, default=40, help="số sự kiện MỚI tối đa cần cào (mặc định 40)")
    parser.add_argument("--no-images", action="store_true", help="không tải ảnh bìa")
    args = parser.parse_args()

    rules = robots()
    if not allowed(rules, f"{SITE}/"):
        print("robots.txt không cho phép truy cập trang chủ — dừng.", file=sys.stderr)
        return 1

    events: list[dict] = load_json(EVENTS_JSON, [])
    known = {event["sourceUrl"] for event in events}
    known_ids = {event.get("sourceId") for event in events}
    added = 0

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(
            user_agent=USER_AGENT,
            locale="vi-VN",
            extra_http_headers={"Accept-Language": "vi"},
        )
        page = context.new_page()
        listings: list[dict] = []
        category_names: dict[str, str] = {}

        def capture(response):
            url = response.url
            if not url.startswith("https://api-v2.ticketbox.vn/"):
                return
            try:
                if "events/categories" in url:
                    for item in (response.json().get("data") or {}).get("results") or []:
                        category_names[item["code"]] = item["name"]["vi"]
                elif any(endpoint in url for endpoint in LISTING_ENDPOINTS):
                    listings.append(response.json())
            except (PlaywrightError, ValueError, KeyError):
                pass

        page.on("response", capture)
        print("Mở trang chủ Ticketbox…")
        page.goto(f"{SITE}/", wait_until="networkidle", timeout=90_000)
        candidates = collect_candidates(listings)
        print(f"Tìm thấy {len(candidates)} sự kiện trên trang chủ; đã có {len(events)} trong events.json.")

        for event_id, _hint in candidates:
            if added >= args.limit:
                break
            if event_id in known_ids:
                continue
            pause()
            try:
                response = context.request.get(
                    f"{API}/events/{event_id}",
                    headers={"Accept": "application/json", "Referer": f"{SITE}/"},
                    timeout=30_000,
                )
                if not response.ok:
                    raise RuntimeError(f"HTTP {response.status}")
                detail = (response.json().get("data") or {}).get("result")
                if not detail:
                    raise RuntimeError("phản hồi không có dữ liệu sự kiện")
                event = parse_event(detail, category_names)
                if event["sourceUrl"] in known:
                    continue
                if not allowed(rules, event["sourceUrl"]):
                    raise RuntimeError("robots.txt không cho phép trang này")
                if not args.no_images:
                    try:
                        pause()
                        event["imagePath"] = download_image(context.request, event["imageUrl"], slugify(event["title"] or str(event_id)))
                    except Exception as error:  # noqa: BLE001 — thiếu ảnh không phải lý do bỏ sự kiện
                        log_error(f"sự kiện {event_id}: không tải được ảnh ({error})")
                events.append(event)
                known.add(event["sourceUrl"])
                known_ids.add(event_id)
                added += 1
                # Ghi sau MỖI sự kiện: dừng giữa chừng (Ctrl+C, mất mạng) không mất phần đã cào.
                save_json(EVENTS_JSON, events)
                print(f"  ✓ [{added}/{args.limit}] {event['title']} ({event['category']})")
            except Exception as error:  # noqa: BLE001 — một sự kiện hỏng không dừng cả script
                log_error(f"sự kiện {event_id}: {error}")

        browser.close()

    print(f"Xong: thêm {added} sự kiện, tổng {len(events)} trong {EVENTS_JSON}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
