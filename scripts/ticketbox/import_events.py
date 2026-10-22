#!/usr/bin/env python3
"""Bước 3 — đưa data/normalized.json vào NexaTicket QUA API (không ghi thẳng database).

Đi qua đúng các endpoint mà web quản trị dùng (xem packages/ts-sdk/src/api/catalog.ts, media.ts),
nên sự kiện nhập vào có đủ địa điểm, khu, ảnh bìa, tồn kho (dựng lúc publish) và khách mua được
thật. Script KHÔNG sửa dữ liệu — mọi chuẩn hoá đã xong ở normalize.py.

Với mỗi sự kiện:
  1. địa điểm: tìm theo tên + thành phố, chưa có thì tạo;
  2. khu ĐỨNG cho từng hạng vé: dùng lại khu cùng mã nếu địa điểm đã có, chưa có thì tạo;
  3. ảnh bìa: xin link → PUT ảnh lên kho → dùng publicUrl;
  4. sự kiện (nháp) với mô tả gốc (nguồn ghi trong data/imported.json, không hiện cho khách);
  5. suất diễn, 6. hạng vé theo tên + giá gốc, 7. publish.

    .venv/bin/python import_events.py --dry-run   # chỉ in việc sẽ làm
    .venv/bin/python import_events.py             # nhập thật
    .venv/bin/python import_events.py --clean     # gỡ những sự kiện đã nhập (theo imported.json)

Chạy lại không tạo trùng: sự kiện đã nhập được nhận theo `sourceUrl` trong data/imported.json.
Tài khoản / URL: GATEWAY_URL, KEYCLOAK_URL, NEXA_USERNAME, NEXA_PASSWORD, ORG_ID (xem nexa_api.py).
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime, timedelta, timezone

# nexa_api TRƯỚC requests: nó tắt cảnh báo LibreSSL của urllib3 trước khi requests được nạp.
from nexa_api import ORG_ID, ApiError, NexaApi
import requests  # noqa: E402,I001
from tb_common import DATA_DIR, IMPORTED_JSON, NORMALIZED_JSON, load_json, save_json

VN = timezone(timedelta(hours=7))


def find_venue(venues: list[dict], venue: dict) -> dict | None:
    if venue.get("existingVenueId"):
        match = next((v for v in venues if v["id"] == venue["existingVenueId"]), None)
        if match:
            return match
    from normalize import venue_key

    return next((v for v in venues if venue_key(v["name"], v["city"]) == venue["key"]), None)


def upload_poster(api: NexaApi, item: dict) -> str | None:
    if not item.get("imagePath"):
        return None
    path = DATA_DIR / item["imagePath"]
    if not path.exists():
        print(f"    ! không thấy ảnh {path}, bỏ qua ảnh bìa")
        return None
    ticket = api.call("POST", api.org("/uploads/poster"), {"contentType": item["imageContentType"]})
    if path.stat().st_size > ticket["maxBytes"]:
        print(f"    ! ảnh {path.name} vượt {ticket['maxBytes']} byte, bỏ qua ảnh bìa")
        return None
    response = requests.put(
        ticket["uploadUrl"], data=path.read_bytes(), headers={"Content-Type": ticket["contentType"]}, timeout=120
    )
    if not response.ok:
        print(f"    ! tải ảnh lên thất bại ({response.status_code}), bỏ qua ảnh bìa")
        return None
    return ticket["publicUrl"]


def import_one(api: NexaApi, venues: list[dict], item: dict, now: datetime) -> dict:
    title = item["event"]["title"]

    # Dữ liệu chuẩn hoá có thể đã cũ: suất đã qua thì dừng sự kiện này, KHÔNG tự sửa dữ liệu.
    for session in item["sessions"]:
        if datetime.fromisoformat(session["salesCloseAt"]) <= now:
            raise RuntimeError(
                f"suất {session['startsAt']} đã quá giờ đóng bán — chạy lại normalize.py để dời sang tương lai"
            )

    # 1. địa điểm
    venue = find_venue(venues, item["venue"])
    if venue is None:
        venue = api.call(
            "POST",
            api.org("/venues"),
            {"name": item["venue"]["name"], "city": item["venue"]["city"], "address": item["venue"]["address"]},
            idempotent=True,
        )
        venue.setdefault("zones", [])
        venues.append(venue)
        print(f"    + địa điểm {venue['name']} ({venue['city']})")

    # 2. khu đứng
    zone_ids: dict[str, str] = {}
    for zone in item["zones"]:
        existing = next((z for z in venue.get("zones", []) if z["zoneCode"] == zone["zoneCode"]), None)
        if existing is None:
            existing = api.call(
                "POST",
                api.org(f"/venues/{venue['id']}/zones"),
                {"zoneCode": zone["zoneCode"], "name": zone["name"], "kind": "STANDING", "capacity": zone["capacity"]},
                idempotent=True,
            )
            venue.setdefault("zones", []).append(existing)
            print(f"    + khu {zone['zoneCode']} ({zone['capacity']} chỗ)")
        zone_ids[zone["zoneCode"]] = existing["id"]

    # 3. ảnh bìa
    poster_url = upload_poster(api, item)

    # 4. sự kiện (nháp)
    event = api.call(
        "POST",
        api.org("/events"),
        {
            "venueId": venue["id"],
            "title": title,
            "summary": item["event"]["summary"] or None,
            # Nguồn (sourceUrl) giữ trong data/imported.json, không in vào mô tả cho khách.
            "description": (item["event"].get("description") or "").strip() or None,
            "category": item["event"]["category"],
            "posterUrl": poster_url,
        },
        idempotent=True,
    )
    record = {"eventId": event["id"], "venueId": venue["id"], "title": title, "published": False}
    try:
        # 5. suất + 6. hạng vé
        for session in item["sessions"]:
            detail = api.call(
                "POST",
                api.org(f"/events/{event['id']}/sessions"),
                {
                    "startsAt": session["startsAt"],
                    "endsAt": session["endsAt"],
                    "salesOpenAt": session["salesOpenAt"],
                    "salesCloseAt": session["salesCloseAt"],
                },
                idempotent=True,
            )
            starts = datetime.fromisoformat(session["startsAt"])
            created = next(s for s in detail["sessions"] if datetime.fromisoformat(s["startsAt"].replace("Z", "+00:00")) == starts)
            for zone in item["zones"]:
                api.call(
                    "POST",
                    api.org(f"/events/{event['id']}/sessions/{created['id']}/ticket-types"),
                    {
                        "venueZoneId": zone_ids[zone["zoneCode"]],
                        "name": zone["ticketType"]["name"],
                        "priceVnd": zone["ticketType"]["priceVnd"],
                    },
                    idempotent=True,
                )
        # 7. publish
        api.call("POST", api.org(f"/events/{event['id']}/publish"))
        record["published"] = True
    except Exception:
        # Sự kiện nháp đã tạo vẫn được ghi lại, để --clean dọn được và lần chạy sau không tạo trùng.
        record["error"] = True
        raise
    finally:
        record["importedAt"] = datetime.now(VN).isoformat(timespec="seconds")
        item["_record"] = record
    return record


def run_import(dry_run: bool) -> int:
    items = load_json(NORMALIZED_JSON, [])
    if not items:
        print(f"Chưa có {NORMALIZED_JSON} — chạy normalize.py trước.", file=sys.stderr)
        return 1
    imported: dict = load_json(IMPORTED_JSON, {})
    # Nhập dở lần trước (đã tạo bản nháp rồi hỏng giữa chừng) thì làm lại từ đầu.
    broken = {url for url, record in imported.items() if record.get("error") or not record.get("published")}
    todo = [item for item in items if item["sourceUrl"] not in imported or item["sourceUrl"] in broken]
    print(
        f"{len(items)} sự kiện chuẩn hoá · {len(items) - len(todo)} đã nhập trước đó · {len(todo)} cần nhập"
        f" ({len(broken)} nhập dở cần làm lại) · tổ chức {ORG_ID}"
    )

    api = NexaApi()
    try:
        venues = api.list_venues()
    except Exception as error:  # noqa: BLE001
        if not dry_run:
            print(f"Không gọi được API NexaTicket: {error}", file=sys.stderr)
            return 1
        print(f"(dry-run) không gọi được API ({error}) — in kế hoạch không tra địa điểm đang có")
        venues = []

    if dry_run:
        for item in todo:
            venue = find_venue(venues, item["venue"])
            existing_codes = {z["zoneCode"] for z in (venue or {}).get("zones", [])}
            print(f"\n● {item['event']['title']}  [{item['event']['category']}]")
            print(f"    nguồn: {item['sourceUrl']}")
            print(
                f"    địa điểm: {item['venue']['name']} ({item['venue']['city']}) — "
                + ("DÙNG LẠI" if venue else "TẠO MỚI")
            )
            for zone in item["zones"]:
                action = "dùng lại khu" if zone["zoneCode"] in existing_codes else "tạo khu đứng"
                print(
                    f"    {action} {zone['zoneCode']:<16} {zone['capacity']:>4} chỗ · hạng vé "
                    f"\"{zone['ticketType']['name']}\" {zone['ticketType']['priceVnd']:,}đ".replace(",", ".")
                )
            print(f"    ảnh bìa: {'tải lên ' + item['imagePath'] if item['imagePath'] else 'không có'}")
            for session in item["sessions"]:
                moved = f" (dời +{session['shiftedWeeks']} tuần)" if session["shiftedWeeks"] else ""
                print(f"    suất {session['startsAt']}{moved} · bán {session['salesOpenAt']} → {session['salesCloseAt']}")
            print("    → tạo sự kiện nháp, thêm suất + hạng vé, rồi PUBLISH")
        print(f"\n(dry-run) Không gọi API ghi nào. Sẽ nhập {len(todo)} sự kiện.")
        return 0

    now = datetime.now(VN)
    ok = failed = 0
    for item in todo:
        print(f"● {item['event']['title']}")
        previous = imported.get(item["sourceUrl"])
        if previous and item["sourceUrl"] in broken:
            # Bản nháp còn sót của lần hỏng trước: xoá (API cho xoá nháp chưa từng bán) rồi nhập lại.
            try:
                api.call("DELETE", api.org(f"/events/{previous['eventId']}"))
                print(f"    − xoá bản nháp nhập dở {previous['eventId']}")
            except ApiError as error:
                if error.status != 404:
                    print(f"    ✗ không xoá được bản nháp nhập dở: {error}", file=sys.stderr)
                    failed += 1
                    continue
            imported.pop(item["sourceUrl"], None)
            save_json(IMPORTED_JSON, imported)
        try:
            record = import_one(api, venues, item, now)
            imported[item["sourceUrl"]] = record
            ok += 1
            print(f"    ✓ đã publish (sự kiện {record['eventId']})")
        except Exception as error:  # noqa: BLE001 — một sự kiện hỏng không dừng cả lô
            if item.get("_record"):
                imported[item["sourceUrl"]] = item["_record"]
            failed += 1
            print(f"    ✗ {error}", file=sys.stderr)
        save_json(IMPORTED_JSON, imported)
    print(f"Xong: {ok} sự kiện nhập thành công, {failed} lỗi. Ghi lại trong {IMPORTED_JSON}.")
    return 0 if failed == 0 else 2


def run_clean(dry_run: bool) -> int:
    """Gỡ những sự kiện đã nhập.

    API của catalog chỉ XOÁ được bản nháp chưa từng lên bán (`DELETE /events/{id}`). Sự kiện đã
    publish thì đi đường duy nhất API cho phép: rút xuống (unpublish) rồi HUỶ (cancel — trạng thái
    cuối, không còn hiện cho khách). Địa điểm và khu không có API xoá: giữ lại, lần nhập sau dùng lại.
    """
    imported: dict = load_json(IMPORTED_JSON, {})
    if not imported:
        print("Không có gì để dọn (data/imported.json trống).")
        return 0
    api = NexaApi()
    remaining = dict(imported)
    for source_url, record in imported.items():
        event_id = record["eventId"]
        print(f"● {record['title']} ({event_id})")
        if dry_run:
            print("    (dry-run) sẽ xoá nếu là nháp, nếu không thì rút xuống + huỷ")
            continue
        try:
            api.call("DELETE", api.org(f"/events/{event_id}"))
            print("    ✓ đã xoá bản nháp")
        except ApiError as error:
            if error.status == 404:
                print("    ✓ không còn trên hệ thống")
            else:
                try:
                    api.call("POST", api.org(f"/events/{event_id}/unpublish"))
                except ApiError:
                    pass  # đã ở trạng thái không bán — đi tiếp tới huỷ
                try:
                    api.call("POST", api.org(f"/events/{event_id}/cancel"))
                    print("    ✓ đã rút xuống và huỷ (API không cho xoá sự kiện đã từng bán)")
                except ApiError as cancel_error:
                    print(f"    ✗ {cancel_error}", file=sys.stderr)
                    continue
        remaining.pop(source_url)
    if not dry_run:
        save_json(IMPORTED_JSON, remaining)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="chỉ in những việc sẽ làm, không gọi API ghi")
    parser.add_argument("--clean", action="store_true", help="gỡ các sự kiện đã nhập theo data/imported.json")
    args = parser.parse_args()
    return run_clean(args.dry_run) if args.clean else run_import(args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
