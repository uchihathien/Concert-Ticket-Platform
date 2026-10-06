#!/usr/bin/env python3
"""Dựng sơ đồ nhà thi đấu cho trận Chung kết Bóng rổ VBA.

ĐỌC TỪ ĐÂU
==========
Toàn bộ danh sách khu dưới đây lấy từ sơ đồ chỗ ngồi của TD Garden (Boston) mà người dùng cung
cấp. Chỉ những nhãn ĐỌC ĐƯỢC trên ảnh mới có mặt ở đây:

  * vành xanh lá phía trong (sát sàn, hai đầu sân): trái 5, 6, 7, 8 — phải 16, 17, 18, 19
  * vành xanh lá phía ngoài (loge): 1 … 22, trong đó 9 tách thành 9A và 9B
  * vành hồng/tím: CHỈ SỐ LẺ 101, 103, … 159 — trên ảnh không có số chẵn nào
  * vành xanh dương ngoài cùng: 301 … 330, đủ cả chẵn lẻ
  * giữa sân: sân bóng rổ

Ba khối màu đỏ/vàng/hồng không tồn tại trong ảnh này (đó là sơ đồ khác). Không khu nào được bịa
thêm; khu nào ảnh không ghi tên thì không có trong danh sách.

VÌ SAO KHÔNG VIẾT MỘT "MODULE GHẾ" MỚI
======================================
Hệ thống đã có sẵn ba thứ mà đề bài hỏi, và viết lại chúng là tạo ra bản sao thứ hai sẽ lệch:

  * sơ đồ khu + hình học: `PUT /v1/platform/concert-templates/{id}/zones` (khung dùng lại được
    cho mọi tổ chức) rồi `POST /events/from-template` áp khung thành sự kiện thật;
  * sinh từng ghế: Catalog tính toạ độ từ hình học khu, Inventory dựng đúng ngần ấy dòng lúc
    publish — không ai phải liệt kê 16.000 mã ghế bằng tay;
  * trạng thái ghế: `GET /v1/sessions/{id}/seats` là nguồn sự thật duy nhất, kèm cả trần mua.

Nên script này làm hai việc: DỰNG sơ đồ (phần hệ thống chưa có dữ liệu), và ĐỌC trạng thái ghế
theo khu (phần hệ thống đã có, chỉ cần gộp lại cho người đọc).

CÁCH CHẠY
=========
    python scripts/vba-arena.py build          # dựng khung + tạo sự kiện (chưa publish)
    python scripts/vba-arena.py build --publish
    python scripts/vba-arena.py status <eventSessionId>
    python scripts/vba-arena.py build --scale 0.4    # bớt số hàng, cho máy yếu

`--scale` chỉ đổi SỐ HÀNG mỗi khu, không đổi số khu: sơ đồ vẫn đúng hình, chỉ ít ghế hơn. Sức
chứa thật của bản đầy đủ là ~16.800 chỗ — đúng tầm một nhà thi đấu NBA, và cũng là ngần ấy dòng
tồn kho sinh ra lúc publish.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timedelta, timezone

GATEWAY = os.environ.get("GATEWAY_URL", "http://localhost:8080")
KEYCLOAK = os.environ.get("KEYCLOAK_URL", "http://localhost:8081")
REALM = os.environ.get("KEYCLOAK_REALM", "nexaticket")
ORG_ID = os.environ.get("ORG_ID", "00000000-0000-4000-a000-000000000001")

# Tài khoản dev. Khung thuộc nền tảng nên phải là superadmin; sự kiện thuộc tổ chức nên phải là
# người của tổ chức. Hai vai, hai token — không gộp được.
PLATFORM_USER = (os.environ.get("PLATFORM_USER", "superadmin"), os.environ.get("PLATFORM_PASS", "superadmin"))
ORG_USER = (os.environ.get("ORG_USER", "organizer"), os.environ.get("ORG_PASS", "organizer"))
CLIENTS = {"platform": ("web-platform", "dev-secret-platform"), "admin": ("web-admin", "dev-secret-admin")}

TEMPLATE_CODE = "VBA_ARENA"

# --- Sơ đồ đọc từ ảnh --------------------------------------------------------

#: Khu sát sàn, hai đầu sân. Trái nhìn từ trên xuống là phía tây.
FLOOR_LEFT = ["5", "6", "7", "8"]
FLOOR_RIGHT = ["16", "17", "18", "19"]

#: Vành loge. Số 9 tách đôi thành 9A/9B đúng như trên ảnh.
LOGE = [str(n) for n in range(1, 9)] + ["9A", "9B"] + [str(n) for n in range(10, 23)]

#: Vành trong, CHỈ SỐ LẺ — ảnh không có 102, 104…
LOWER_BOWL = [str(n) for n in range(101, 160, 2)]

#: Vành ngoài cùng, đủ chẵn lẻ.
UPPER_BOWL = [str(n) for n in range(301, 331)]

#: Ba hạng vé theo yêu cầu, kèm giá gợi ý của khung (tổ chức sửa được lúc áp khung).
TIERS = {
    "VIP": {"label": "VIP / Courtside", "price": 2_500_000},
    "STANDARD": {"label": "Standard / Khán đài A", "price": 900_000},
    "ECONOMY": {"label": "Economy / Khán đài B", "price": 450_000},
}

#: Sân bóng rổ: 28 × 15 m theo luật FIBA. Đây là "sân khấu" của hệ thống — cùng một khái niệm
#: "thứ mọi khu hướng về", nên không cần kiểu hình học riêng cho thể thao.
COURT = {"shape": "RECTANGLE", "x": 0.0, "y": 0.0, "width": 28.0, "height": 15.0}

#: Bán kính trong và số hàng của từng vành. Khoảng cách hàng là 1,4 m (ZoneLayout.ROW_PITCH), nên
#: vành sau phải bắt đầu sau khi vành trước đã trải hết: 22 + 9×1,4 = 34,6 < 36.
RINGS = {
    "FLOOR": {"inner": 16.0, "rows": 4, "seats": 8},
    "LOGE": {"inner": 22.0, "rows": 10, "seats": 14},
    "LOWER": {"inner": 36.0, "rows": 12, "seats": 16},
    "UPPER": {"inner": 53.0, "rows": 14, "seats": 18},
}

#: Khe hở giữa hai khu, tính bằng độ. Trên ảnh các khu cách nhau bằng lối đi trắng; không chừa khe
#: thì đường bao hai khu dính nhau và bản đồ mất hết lối đi.
GAP_DEG = 1.6


def arc_sections(codes, ring, start_deg, end_deg, tier, name_fn, sort_base):
    """Rải một danh sách khu đều theo cung, mỗi khu một lát.

    Trả về danh sách zone theo đúng hình dạng `ZoneRequest` của backend.
    """
    spec = RINGS[ring]
    span = (end_deg - start_deg) / len(codes)
    zones = []
    for index, code in enumerate(codes):
        lo = start_deg + index * span + GAP_DEG / 2
        hi = start_deg + (index + 1) * span - GAP_DEG / 2
        zones.append(
            {
                "zoneCode": name_fn(code)[0],
                "name": name_fn(code)[1],
                "kind": "SEATED",
                "rowCount": spec["rows"],
                "seatsPerRow": spec["seats"],
                "sortOrder": sort_base + index,
                "suggestedPriceVnd": TIERS[tier]["price"],
                "layoutShape": "ARC",
                "originX": 0.0,
                "originY": 0.0,
                "innerRadius": spec["inner"],
                "startAngleDeg": round(lo, 3),
                "endAngleDeg": round(hi, 3),
            }
        )
    return zones


def build_zones(scale: float = 1.0):
    """Toàn bộ 91 khu của nhà thi đấu.

    Góc 0° là phía phải sân (sau rổ bên phải trên ảnh), tăng ngược chiều kim đồng hồ.
    """
    zones = []

    # Sát sàn: hai cụm sau hai rổ, không phải một vành kín — đúng như ảnh.
    zones += arc_sections(
        FLOOR_RIGHT, "FLOOR", -38, 38, "VIP",
        lambda c: (f"FLR-{c}", f"Sát sàn {c} · Courtside"), 0,
    )
    zones += arc_sections(
        FLOOR_LEFT, "FLOOR", 142, 218, "VIP",
        lambda c: (f"FLR-{c}", f"Sát sàn {c} · Courtside"), 10,
    )

    # Ba vành kín. Bắt đầu từ -90° để khu số 1 nằm ở giữa cạnh dưới, khớp với ảnh.
    zones += arc_sections(
        LOGE, "LOGE", -90, 270, "VIP",
        lambda c: (f"LOGE-{c}", f"Loge {c} · VIP"), 100,
    )
    zones += arc_sections(
        LOWER_BOWL, "LOWER", -90, 270, "STANDARD",
        lambda c: (f"LB-{c}", f"Khán đài A · {c}"), 200,
    )
    zones += arc_sections(
        UPPER_BOWL, "UPPER", -90, 270, "ECONOMY",
        lambda c: (f"UB-{c}", f"Khán đài B · {c}"), 300,
    )

    if scale != 1.0:
        for zone in zones:
            zone["rowCount"] = max(1, round(zone["rowCount"] * scale))

    return zones


def capacity_of(zones):
    return sum(z["rowCount"] * z["seatsPerRow"] for z in zones)


# --- Gọi API -----------------------------------------------------------------


def token(role: str, user_pass) -> str:
    client_id, secret = CLIENTS[role]
    body = urllib.parse.urlencode(
        {
            "grant_type": "password",
            "client_id": client_id,
            "client_secret": secret,
            "username": user_pass[0],
            "password": user_pass[1],
        }
    ).encode()
    url = f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token"
    with urllib.request.urlopen(urllib.request.Request(url, data=body), timeout=30) as response:
        return json.loads(response.read())["access_token"]


def call(method: str, path: str, bearer: str, body=None):
    request = urllib.request.Request(
        GATEWAY + path,
        method=method,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None,
        headers={"Authorization": f"Bearer {bearer}", "Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            raw = response.read()
            return json.loads(raw.decode("utf-8")) if raw else None
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        raise SystemExit(f"{method} {path} → {error.code}\n{detail}") from error


# --- Lệnh build --------------------------------------------------------------


def build(args):
    zones = build_zones(args.scale)
    print(f"Sơ đồ: {len(zones)} khu · {capacity_of(zones):,} chỗ".replace(",", "."))

    platform = token("platform", PLATFORM_USER)

    # Khung idempotent theo mã: chạy lại script không sinh khung thứ hai.
    existing = [t for t in call("GET", "/v1/platform/concert-templates", platform) if t["code"] == TEMPLATE_CODE]
    if existing:
        template_id = existing[0]["id"]
        print(f"Dùng lại khung {TEMPLATE_CODE} ({template_id})")
    else:
        created = call(
            "POST",
            "/v1/platform/concert-templates",
            platform,
            {
                "code": TEMPLATE_CODE,
                "name": "Nhà thi đấu VBA · 91 khu",
                "category": "the-thao",
                "description": "Sơ đồ nhà thi đấu bóng rổ: sát sàn, loge 1–22, khán đài A (lẻ 101–159), khán đài B (301–330).",
            },
        )
        template_id = created["id"]
        print(f"Đã tạo khung {TEMPLATE_CODE} ({template_id})")

    call("PUT", f"/v1/platform/concert-templates/{template_id}/zones", platform, {"stage": COURT, "zones": zones})
    call("POST", f"/v1/platform/concert-templates/{template_id}/activate", platform)
    print("Đã nạp sơ đồ vào khung và kích hoạt")

    organizer = token("admin", ORG_USER)
    starts = datetime.now(timezone.utc) + timedelta(days=30)
    event = call(
        "POST",
        f"/v1/organizations/{ORG_ID}/events/from-template",
        organizer,
        {
            "templateId": template_id,
            "title": "Chung kết Bóng rổ VBA 2026",
            "slug": args.slug,
            "summary": "Trận chung kết giải bóng rổ chuyên nghiệp Việt Nam.",
            "category": "the-thao",
            "venueName": "Nhà thi đấu VBA Arena",
            "city": "Đà Nẵng",
            "startsAt": starts.isoformat().replace("+00:00", "Z"),
            "salesOpenAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "salesCloseAt": starts.isoformat().replace("+00:00", "Z"),
            "maxSeatedPerHold": 8,
            "maxTicketsPerCustomer": 8,
        },
    )
    print(f"Đã tạo sự kiện {event['slug']} ({event['id']}) — {len(event['sessions'])} suất")

    if args.publish:
        call("POST", f"/v1/organizations/{ORG_ID}/events/{event['id']}/publish", organizer)
        session_id = event["sessions"][0]["id"]
        print(f"Đã publish. Tồn kho đang dựng cho suất {session_id}")
        print(f"Kiểm trạng thái ghế: python scripts/vba-arena.py status {session_id}")
    else:
        print("Chưa publish. Thêm --publish để dựng tồn kho và bán được vé.")


# --- Lệnh status -------------------------------------------------------------

#: Nhãn tiếng Việt cho trạng thái backend trả về. HELD và RESERVED khác nhau thật: một bên là
#: khách đang giữ chỗ chưa trả tiền, một bên là đơn đã tạo và đang chờ thanh toán.
STATUS_LABEL = {
    "AVAILABLE": "Trống",
    "HELD": "Đang giữ chỗ",
    "RESERVED": "Đã đặt",
    "SOLD": "Đã bán",
    "BLOCKED": "Khoá",
}

TIER_OF_PREFIX = {"FLR": "VIP", "LOGE": "VIP", "LB": "STANDARD", "UB": "ECONOMY"}


def status(args):
    """Trạng thái ghế theo khu, gộp từ nguồn sự thật duy nhất là sơ đồ tồn kho."""
    bearer = token("admin", ORG_USER)
    seat_map = call("GET", f"/v1/sessions/{args.session_id}/seats", bearer)

    by_zone = defaultdict(lambda: defaultdict(int))
    for seat in seat_map["seats"]:
        by_zone[seat["zoneCode"]][seat["status"]] += 1

    by_tier = defaultdict(lambda: defaultdict(int))
    for zone_code, counts in by_zone.items():
        tier = TIER_OF_PREFIX.get(zone_code.split("-")[0], "?")
        for state, n in counts.items():
            by_tier[tier][state] += n

    order = ["AVAILABLE", "HELD", "RESERVED", "SOLD", "BLOCKED"]
    print(f"{'Hạng vé':<26}" + "".join(f"{STATUS_LABEL[s]:>14}" for s in order) + f"{'Tổng':>10}")
    for tier in ("VIP", "STANDARD", "ECONOMY"):
        counts = by_tier.get(tier)
        if not counts:
            continue
        total = sum(counts.values())
        print(f"{TIERS[tier]['label']:<26}" + "".join(f"{counts.get(s, 0):>14,}" for s in order) + f"{total:>10,}")

    if args.zones:
        print("\nTheo từng khu:")
        for zone_code in sorted(by_zone, key=lambda c: (c.split("-")[0], c)):
            counts = by_zone[zone_code]
            line = "  ".join(f"{STATUS_LABEL[s]} {counts.get(s, 0)}" for s in order if counts.get(s))
            print(f"  {zone_code:<10} {line}")

    allowance = seat_map.get("purchaseAllowance")
    if allowance:
        print(f"\nTrần mua của tài khoản này: còn {allowance['remaining']}/{allowance['limit']} vé")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    build_cmd = sub.add_parser("build", help="dựng khung sơ đồ và tạo sự kiện")
    build_cmd.add_argument("--publish", action="store_true")
    build_cmd.add_argument("--scale", type=float, default=1.0, help="hệ số số hàng mỗi khu")
    build_cmd.add_argument("--slug", default="chung-ket-vba-2026")
    build_cmd.set_defaults(func=build)

    status_cmd = sub.add_parser("status", help="trạng thái ghế theo hạng vé")
    status_cmd.add_argument("session_id")
    status_cmd.add_argument("--zones", action="store_true", help="liệt kê từng khu")
    status_cmd.set_defaults(func=status)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    sys.exit(main())
