# Catalog & Admin — authoring flows

Nghiệp vụ/API. UI wizard & màn hình: [../ui/flows/organizer-publish.md](../ui/flows/organizer-publish.md).

## Flow A — Tạo venue & seat map

1. `ORG_ADMIN` / `EVENT_MANAGER` tạo venue (tên, city, address).
2. Tạo seat map version `DRAFT`.
3. Thêm seats (bulk CSV hoặc UI grid): section, row, label.
4. Đánh dấu map `ACTIVE` (một ACTIVE / venue tại một thời điểm; map cũ `ARCHIVED`).
5. Audit: `VENUE_CREATED`, `SEAT_MAP_ACTIVATED`.

## Flow B — Tạo và publish event

1. Tạo event `DRAFT` gắn venue + seat map ACTIVE.
2. Tạo `event_session` (starts_at, sales window).
3. Tạo `ticket_tiers` (giá VND).
4. Gán nhóm ghế (section/row range) → tier.
5. Preflight publish:
   - Mọi bán được seat có tier?
   - Org có ≥1 `bank_accounts.active`?
   - Slug unique?
6. Publish: `events.status=PUBLISHED`, materialize `session_seats` snapshot, `published_at=now`.
7. Unpublish: ẩn khỏi catalog; không xóa `session_seats`; ghế đã SOLD giữ nguyên.

## Flow C — Promotion

1. Tạo promotion PERCENT hoặc FIXED_AMOUNT + window + optional code.
2. Áp dụng lúc tạo order (Orders module validate); Catalog chỉ sở hữu định nghĩa.

## Flow D — Bank account

1. `ORG_ADMIN` thêm account (bank_code, number, name).
2. Encrypt number; UI chỉ last4.
3. Chỉ một account “preferred active” dùng khi tạo order (hoặc chọn explicit); snapshot vào order.

## Customer discovery

1. `GET /v1/events` filter published + city/date/query.
2. `GET /v1/events/{slug}` trả sessions, tiers, min price; chưa trả full seat availability (gọi sessions/seats).
