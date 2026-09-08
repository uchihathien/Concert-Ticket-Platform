# Mô hình địa điểm và thiết kế chỗ ngồi

Một địa điểm dùng chung cho nhiều tổ chức. Mỗi tổ chức **tự thiết kế khu vực ghế cho từng sự kiện** của mình, nhưng có những **khu vực ghế áp cứng không thay đổi được**.

Thay thế `venue_seat_maps` / `seat_map_seats` của [data-model.md v1](../01-foundation/data-model.md). ADR: [ADR-1011](adr/ADR-1011-shared-venue-fixed-and-flexible-zones.md).

---

## 1. Bài toán

Một nhà thi đấu thật có hai loại không gian hoàn toàn khác nhau:

| Loại | Ví dụ | Ai quyết định | Đổi theo sự kiện? |
| --- | --- | --- | --- |
| **Cố định** | Khán đài A/B/C — ghế bắt vít vào bậc bê tông, có số sẵn | Chủ địa điểm | **Không bao giờ** |
| **Linh hoạt** | Sân trung tâm — hôm nay là sân bóng rổ, mai kê 2.000 ghế cho concert, ngày kia để trống cho khu đứng | Ban tổ chức từng sự kiện | **Mỗi sự kiện một khác** |

Mô hình v1 chỉ có một khái niệm `seat_map` thuộc sở hữu tổ chức — không diễn đạt được sự khác biệt này, và cũng không cho hai tổ chức dùng chung một địa điểm.

## 2. Mô hình

```text
Venue  (địa điểm — superadmin sở hữu, nhiều tổ chức dùng chung)
 └── VenueLayoutVersion  (phiên bản mặt bằng; một ACTIVE tại một thời điểm)
      └── VenueZone[]  (khu vực)
           ├── kind = FIXED     → VenueFixedSeat[]   ghế áp cứng, bất biến
           └── kind = FLEXIBLE  → chỉ có ranh giới + sức chứa tối đa, KHÔNG có ghế sẵn

EventSeatingPlan  (thiết kế chỗ ngồi cho MỘT suất diễn — tổ chức sở hữu)
 ├── ghim venueLayoutVersionId
 ├── EventZoneUsage[]       dùng / không dùng từng zone, gán hạng vé
 ├── EventFlexibleBlock[]   thiết kế của tổ chức trong zone FLEXIBLE
 └── EventSeatOverride[]    bỏ ghế (lối đi), chặn ghế, đổi hạng vé từng ghế
```

### Ranh giới quyền — đây là phần trả lời "áp cứng, không thay đổi được"

| Đối tượng | Superadmin | Tổ chức |
| --- | :---: | :---: |
| Tạo địa điểm, địa chỉ | ✅ | ✅ — địa điểm riêng ([§7](#7-địa-điểm-riêng-của-tổ-chức)) |
| Định nghĩa zone và loại zone | ✅ | ✅ *chỉ trên địa điểm riêng của mình* |
| Định nghĩa ghế trong zone `FIXED` | ✅ | ✅ *chỉ trên địa điểm riêng*; **không bao giờ** qua `EventSeatingPlan` |
| Sức chứa tối đa của zone | ✅ | ✅ *chỉ trên địa điểm riêng* |
| Chọn dùng / không dùng một zone cho sự kiện | ✅ | ✅ |
| Thiết kế hàng ghế trong zone `FLEXIBLE` | ✅ | ✅ |
| Gán hạng vé cho zone / khối / từng ghế | ✅ | ✅ |
| Chặn (`BLOCK`) hoặc loại khỏi bán (`REMOVE`) từng ghế | ✅ | ✅ |

Ranh giới thật nằm ở **hai thao tác khác nhau**, không ở chỗ ai sở hữu:

| Thao tác | Là gì | Ai được làm |
| --- | --- | --- |
| **Sửa mặt bằng** | Tạo `VenueLayoutVersion` mới: thêm/bớt zone, định nghĩa lại ghế cố định | **Chủ địa điểm** — superadmin với địa điểm dùng chung, chính tổ chức với địa điểm riêng |
| **Thiết kế chỗ ngồi cho sự kiện** | `EventSeatingPlan`: bật/tắt zone, kê ghế trong zone linh hoạt, gán giá, chặn ghế | Ban tổ chức sự kiện |

**Qua `EventSeatingPlan` thì không ai — kể cả chủ địa điểm — sửa được ghế của zone `FIXED`.** Muốn đổi ghế áp cứng thì phải tạo phiên bản mặt bằng mới, và sự kiện đã bán vé vẫn ghim phiên bản cũ.

Nói ngắn: kết cấu vật lý thuộc chủ địa điểm, quyết định thương mại thuộc ban tổ chức. Với địa điểm dùng chung đó là hai bên khác nhau; với địa điểm riêng đó là cùng một bên nhưng vẫn là hai thao tác tách bạch.

### Vì sao ghim `venueLayoutVersionId`

Nhà thi đấu cải tạo khán đài → superadmin tạo `VenueLayoutVersion` mới. Sự kiện đã bán vé vẫn ghim version cũ, nên **vé đã bán không bao giờ trỏ vào ghế không còn tồn tại**. Đây là cùng một nguyên tắc snapshot mà v1 áp dụng cho giá (`price_snapshot`) và tài khoản ngân hàng (`bank_snapshot`).

## 3. Lược đồ dữ liệu

Tất cả thuộc `catalog_db`.

```sql
-- ============ Superadmin sở hữu ============

venues (
  id UUID PRIMARY KEY,
  scope TEXT NOT NULL,                   -- PLATFORM | ORGANIZATION  (xem §7)
  organization_id UUID,                  -- chỉ khi scope = ORGANIZATION
  name TEXT NOT NULL,                    -- 'Nhà thi đấu Phú Thọ'
  address TEXT, city TEXT,
  site_code TEXT,                        -- gom nhiều hall cùng một địa chỉ
  setup_minutes INT NOT NULL DEFAULT 240,    -- đệm dựng; superadmin chỉnh theo từng địa điểm
  teardown_minutes INT NOT NULL DEFAULT 180, -- đệm tháo dỡ
  status TEXT NOT NULL,                  -- ACTIVE | ARCHIVED
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

venue_layout_versions (
  id UUID PRIMARY KEY,
  venue_id UUID NOT NULL REFERENCES venues(id),
  version INT NOT NULL,
  status TEXT NOT NULL,                  -- DRAFT | ACTIVE | ARCHIVED
  note TEXT,
  activated_at TIMESTAMPTZ,
  UNIQUE (venue_id, version)
);
CREATE UNIQUE INDEX uq_one_active_layout
  ON venue_layout_versions (venue_id) WHERE status = 'ACTIVE';

venue_zones (
  id UUID PRIMARY KEY,
  layout_version_id UUID NOT NULL REFERENCES venue_layout_versions(id),
  code TEXT NOT NULL,                    -- 'KHAN_DAI_A', 'SAN'
  name TEXT NOT NULL,                    -- 'Khán đài A', 'Sân trung tâm'
  kind TEXT NOT NULL,                    -- FIXED | FLEXIBLE
  admission_type TEXT NOT NULL,          -- SEATED | STANDING   (xem ma trận §8)
  max_capacity INT,                      -- BẮT BUỘC khi kind=FLEXIBLE hoặc admission_type=STANDING
  boundary JSONB,                        -- polygon để vẽ và làm khung hướng dẫn
  display_order INT NOT NULL DEFAULT 0,
  UNIQUE (layout_version_id, code)
);

venue_fixed_seats (                      -- BẤT BIẾN với tổ chức
  id UUID PRIMARY KEY,
  zone_id UUID NOT NULL REFERENCES venue_zones(id),
  section TEXT NOT NULL, row_label TEXT NOT NULL, seat_label TEXT NOT NULL,
  pos_x NUMERIC, pos_y NUMERIC,
  external_code TEXT,
  UNIQUE (zone_id, section, row_label, seat_label)
);

-- ============ Tổ chức sở hữu, theo từng suất diễn ============

event_seating_plans (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  event_session_id UUID NOT NULL UNIQUE,
  venue_id UUID NOT NULL,
  layout_version_id UUID NOT NULL,       -- GHIM
  status TEXT NOT NULL,                  -- DRAFT | READY | MATERIALIZED | FROZEN
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

event_zone_usages (
  id UUID PRIMARY KEY,
  seating_plan_id UUID NOT NULL REFERENCES event_seating_plans(id),
  zone_id UUID NOT NULL,
  included BOOLEAN NOT NULL DEFAULT TRUE,
  ticket_tier_id UUID,                   -- hạng vé mặc định cho cả zone
  standing_capacity INT,                 -- chỉ khi admission_type = STANDING
  UNIQUE (seating_plan_id, zone_id)
);

event_flexible_blocks (                  -- thiết kế của tổ chức trong zone FLEXIBLE
  id UUID PRIMARY KEY,
  seating_plan_id UUID NOT NULL REFERENCES event_seating_plans(id),
  zone_id UUID NOT NULL,
  section_label TEXT NOT NULL,           -- 'FLOOR-A'
  row_count INT NOT NULL CHECK (row_count > 0),
  seats_per_row INT NOT NULL CHECK (seats_per_row > 0),
  row_labeling TEXT NOT NULL,            -- ALPHA (A,B,C) | NUMERIC (1,2,3)
  seat_numbering TEXT NOT NULL,          -- SEQUENTIAL | ODD_EVEN
  origin_x NUMERIC NOT NULL, origin_y NUMERIC NOT NULL,
  rotation_deg NUMERIC NOT NULL DEFAULT 0,
  seat_spacing NUMERIC NOT NULL, row_spacing NUMERIC NOT NULL,
  ticket_tier_id UUID,
  UNIQUE (seating_plan_id, zone_id, section_label)
);

event_seat_overrides (
  id UUID PRIMARY KEY,
  seating_plan_id UUID NOT NULL REFERENCES event_seating_plans(id),
  seat_code TEXT NOT NULL,               -- khoá ổn định, xem §4
  action TEXT NOT NULL,                  -- REMOVE (lối đi, không tồn tại) | BLOCK (giữ chỗ) | SET_TIER
  ticket_tier_id UUID,
  reason TEXT,
  UNIQUE (seating_plan_id, seat_code)
);

seating_plan_templates (                 -- tái sử dụng thiết kế
  id UUID PRIMARY KEY,
  owner_type TEXT NOT NULL,              -- PLATFORM | ORGANIZATION
  owner_id UUID,
  venue_id UUID NOT NULL,
  layout_version_id UUID NOT NULL,
  name TEXT NOT NULL,                    -- 'Concert 5.000 chỗ'
  definition JSONB NOT NULL,             -- snapshot zone usages + blocks + overrides
  created_at TIMESTAMPTZ NOT NULL
);
```

## 4. `seat_code` — khoá ổn định thay cho `seat_map_seat_id`

Ở v1, `session_seats` unique theo `(event_session_id, seat_map_seat_id)`. Giờ ghế đến từ hai nguồn khác nhau (ghế cố định có sẵn ID, ghế linh hoạt được sinh ra lúc materialize), nên cần một khoá thống nhất.

```
seat_code = "{zoneCode}-{section}-{rowLabel}-{seatLabel}"
ví dụ:      "KHAN_DAI_A-A-12-07"   (ghế cố định)
            "SAN-FLOOR_A-C-15"     (ghế do tổ chức thiết kế)
```

```sql
ALTER TABLE session_seats
  ADD COLUMN seat_code TEXT NOT NULL,
  ADD COLUMN zone_code TEXT NOT NULL,
  ADD COLUMN admission_type TEXT NOT NULL, -- SEATED | STANDING
  ADD COLUMN source_kind TEXT NOT NULL,    -- FIXED | FLEXIBLE
  ADD COLUMN source_ref UUID;              -- venue_fixed_seats.id hoặc event_flexible_blocks.id

CREATE INDEX idx_standing_alloc ON session_seats (event_session_id, zone_code, status)
  WHERE admission_type = 'STANDING';       -- phục vụ cấp phát vé đứng, xem §9

CREATE UNIQUE INDEX uq_seat_per_session
  ON session_seats (event_session_id, seat_code);
```

**Bất biến chống oversell không đổi:** vẫn là một unique index cấp database trên `(suất diễn, ghế)`. Chỉ đổi cách định danh ghế, không đổi cơ chế bảo vệ. Toàn bộ thuật toán hold Redis Lua, `FOR UPDATE` có sắp thứ tự và `seat_hold_items` unique index giữ nguyên.

## 5. Materialize khi publish

Toàn bộ độ phức tạp của mô hình này **kết thúc tại đây**. Sau khi materialize, `session_seats` chỉ là một danh sách ghế phẳng — Inventory không biết và không cần biết ghế đến từ khán đài cố định hay từ thiết kế của tổ chức.

```
Nguồn ghế = (ghế cố định của các zone FIXED được included)
          ∪ (ghế sinh ra từ event_flexible_blocks)
          − (các seat_code có override REMOVE)

Hạng vé  = override SET_TIER  >  block.ticket_tier_id  >  zone_usage.ticket_tier_id
Trạng thái = BLOCKED nếu có override BLOCK, ngược lại AVAILABLE
Toạ độ   = ghế cố định: pos_x/pos_y
           ghế linh hoạt: tính từ origin + rotation + spacing × (rowIndex, seatIndex)
```

```sql
-- Phần ghế cố định: một câu INSERT…SELECT, không vòng lặp
INSERT INTO session_seats (id, event_session_id, seat_code, zone_code, source_kind, source_ref,
                           section, row_label, seat_label, ticket_tier_id, status,
                           price_vnd_snapshot, pos_x, pos_y)
SELECT gen_random_uuid(), :sessionId,
       z.code || '-' || s.section || '-' || s.row_label || '-' || s.seat_label,
       z.code, 'FIXED', s.id,
       s.section, s.row_label, s.seat_label,
       COALESCE(o.ticket_tier_id, u.ticket_tier_id),
       CASE WHEN o.action = 'BLOCK' THEN 'BLOCKED' ELSE 'AVAILABLE' END,
       t.unit_price_vnd, s.pos_x, s.pos_y
FROM venue_fixed_seats s
JOIN venue_zones z        ON z.id = s.zone_id AND z.kind = 'FIXED'
JOIN event_zone_usages u  ON u.zone_id = z.id AND u.seating_plan_id = :planId AND u.included
LEFT JOIN event_seat_overrides o
       ON o.seating_plan_id = :planId
      AND o.seat_code = z.code || '-' || s.section || '-' || s.row_label || '-' || s.seat_label
LEFT JOIN ticket_tiers t  ON t.id = COALESCE(o.ticket_tier_id, u.ticket_tier_id)
WHERE z.layout_version_id = :layoutVersionId
  AND COALESCE(o.action, '') <> 'REMOVE'
ON CONFLICT (event_session_id, seat_code) DO NOTHING;
```

Phần ghế linh hoạt sinh bằng `generate_series(0, row_count-1) × generate_series(0, seats_per_row-1)` theo cùng khuôn mẫu.

`ON CONFLICT DO NOTHING` giữ nguyên ý nghĩa như v1: re-publish hoặc mở rộng sơ đồ không đụng ghế đã bán.

## 6. Sửa sơ đồ sau khi đã bán vé

Đây là tình huống thật và thường xuyên: bán hết 100 ghế sân, nhu cầu cao hơn dự kiến, tổ chức muốn kê thêm 200 ghế.

| Thao tác | Trước khi bán | Đã bán vé | Cách làm |
| --- | :---: | :---: | --- |
| Thêm khối ghế mới | ✅ | ✅ | Thêm block → re-materialize; `ON CONFLICT DO NOTHING` giữ nguyên ghế cũ |
| Chặn ghế chưa bán | ✅ | ✅ | Override `BLOCK` → `session_seats.status = BLOCKED` nếu đang `AVAILABLE` |
| Đổi hạng vé ghế chưa bán | ✅ | ✅ | Override `SET_TIER`; ghế đã bán giữ giá snapshot |
| Bỏ ghế chưa bán | ✅ | ✅ | Override `REMOVE`, chỉ khi ghế đang `AVAILABLE` |
| Bỏ / dời ghế **đã bán** | ✅ | ❌ | **Chặn cứng** — trả `SEAT_ALREADY_SOLD` |
| Đổi `layout_version_id` | ✅ | ❌ | **Chặn cứng** — trả `PLAN_FROZEN` |
| Bỏ nguyên một zone đã có ghế bán | ✅ | ❌ | **Chặn cứng** |

Trạng thái `FROZEN` bật khi suất diễn có ít nhất một ghế `RESERVED` hoặc `SOLD`. Từ lúc đó chỉ còn các thao tác cộng thêm và thao tác trên ghế chưa bán.

**Nguyên tắc:** đã bán thì bất biến. Ghế khách đã mua phải tồn tại đúng như lúc mua cho tới khi họ vào cửa.

## 7. Địa điểm riêng của tổ chức

**Chốt: có.** Không phải sự kiện nào cũng ở nhà thi đấu có sẵn — một tổ chức làm show ở quán cà phê 80 chỗ không thể chờ superadmin.

| `venues.scope` | Ai tạo và sửa mặt bằng | Ai thấy | Có zone `FIXED`? |
| --- | --- | --- | --- |
| `PLATFORM` | Superadmin | Mọi tổ chức | ✅ |
| `ORGANIZATION` | Chính tổ chức đó (`ORG_ADMIN`+) | Chỉ tổ chức đó | ✅ |

Địa điểm riêng **vẫn có zone `FIXED`** — và đó là điều đúng: một nhà hát mà tổ chức thuê dài hạn có ghế bắt vít thật, khai báo một lần rồi dùng cho hàng chục sự kiện. Điều "áp cứng, không đổi được" vẫn giữ nguyên vì nó được ép ở tầng `EventSeatingPlan`, không phải ở tầng ai sở hữu (xem §2).

Quy tắc kèm theo:

- Địa điểm riêng chỉ hiện trong danh sách của tổ chức đó; không lộ sang tổ chức khác.
- Superadmin **nâng cấp** được một địa điểm riêng lên `PLATFORM` sau khi kiểm tra — từ đó quyền sửa mặt bằng chuyển về superadmin, tổ chức chỉ còn quyền thiết kế chỗ ngồi.
- Không áp dụng cảnh báo trùng lịch (§11) cho địa điểm riêng, vì chỉ một tổ chức dùng.
- Chấp nhận việc nhiều tổ chức tạo trùng địa điểm riêng cho cùng một nơi thật. Superadmin dọn bằng cách nâng một cái lên `PLATFORM` và khuyến khích chuyển sang dùng chung.

## 8. Ba kiểu concert — ghế ngồi, hỗn hợp, chỉ đứng

Đây là yêu cầu chính thức, không phải mở rộng tuỳ chọn. Mô hình hoá bằng hai chiều độc lập:

| `kind` | `admission_type` | Ai định nghĩa chỗ | Tổ chức làm được gì trong `EventSeatingPlan` |
| --- | --- | --- | --- |
| `FIXED` | `SEATED` | Chủ địa điểm định nghĩa **từng ghế** | Bật/tắt zone, chặn ghế, gán hạng vé |
| `FIXED` | `STANDING` | Chủ địa điểm định nghĩa **sức chứa** | Bật/tắt zone, đặt sức chứa ≤ `max_capacity`, gán hạng vé |
| `FLEXIBLE` | `SEATED` | **Tổ chức kê khối ghế** | Thiết kế tự do trong `max_capacity` |
| `FLEXIBLE` | `STANDING` | **Tổ chức đặt sức chứa** | Đặt sức chứa ≤ `max_capacity`, gán hạng vé |

Ba kiểu concert đều là tổ hợp của bảng trên, không cần khái niệm mới:

**A. Concert toàn ghế ngồi** — nhà hát, sự kiện trang trọng.

```text
Khán đài A/B/C   FIXED    + SEATED     →  ghế cố định có sẵn
Sân trung tâm    FLEXIBLE + SEATED     →  tổ chức kê 40 hàng × 50 ghế
```

**B. Concert vừa ngồi vừa đứng** — phổ biến nhất với concert lớn.

```text
Khán đài A/B/C   FIXED    + SEATED     →  vé ngồi, giá theo khán đài
Sân trung tâm    FLEXIBLE + STANDING   →  2.000 vé đứng, một giá
Khu VIP sát sân  FLEXIBLE + SEATED     →  tổ chức kê 10 hàng ghế, giá cao nhất
```

**C. Concert chỉ đứng** — nhạc điện tử, festival.

```text
Sân trung tâm    FLEXIBLE + STANDING   →  3.000 vé đứng
Khán đài         tắt (included = false), hoặc FIXED + STANDING nếu chỗ đó cũng cho đứng
```

Tổ chức chuyển giữa ba kiểu chỉ bằng cách đổi `EventSeatingPlan` — **cùng một địa điểm, cùng một mặt bằng**. Đây chính là điều mô hình này sinh ra để làm.

Ràng buộc: zone `SEATED` phải có chỗ (ghế cố định hoặc khối do tổ chức kê); zone `STANDING` phải có `standing_capacity > 0`. Một zone không thể vừa ngồi vừa đứng — muốn vậy thì chủ địa điểm chia thành hai zone.

## 9. Tồn kho vé đứng

### Vấn đề

Khách mua vé đứng chọn **số lượng**, không chọn vị trí. Nhưng mỗi người vào cửa vẫn cần **một vé QR riêng** để soát. Nghĩa là vẫn cần một đơn vị tồn kho cho mỗi người — chỉ là danh tính đơn vị đó không có ý nghĩa với khách.

### Chốt: đơn vị tồn kho ảo, cấp phát bằng `FOR UPDATE SKIP LOCKED`

Lúc materialize, sinh đúng `standing_capacity` hàng trong `session_seats`:

```sql
INSERT INTO session_seats (id, event_session_id, seat_code, zone_code, admission_type,
                           source_kind, section, ticket_tier_id, status, price_vnd_snapshot)
SELECT gen_random_uuid(), :sessionId,
       z.code || '-GA-' || lpad(g::text, 6, '0'),      -- 'SAN-GA-000137'
       z.code, 'STANDING',
       CASE WHEN z.kind = 'FIXED' THEN 'FIXED' ELSE 'FLEXIBLE' END,
       z.name,                                          -- nhãn hiển thị: 'Sân trung tâm'
       u.ticket_tier_id, 'AVAILABLE', t.unit_price_vnd
FROM event_zone_usages u
JOIN venue_zones z   ON z.id = u.zone_id AND z.admission_type = 'STANDING'
JOIN ticket_tiers t  ON t.id = u.ticket_tier_id
CROSS JOIN generate_series(1, u.standing_capacity) g
WHERE u.seating_plan_id = :planId AND u.included
ON CONFLICT (event_session_id, seat_code) DO NOTHING;
```

Khi giữ chỗ, server tự chọn N đơn vị còn trống:

```sql
SELECT id FROM session_seats
 WHERE event_session_id = :sid AND zone_code = :zone
   AND admission_type = 'STANDING' AND status = 'AVAILABLE'
 ORDER BY id
 LIMIT :qty
 FOR UPDATE SKIP LOCKED;
-- trả về ít hơn :qty  →  ZONE_SOLD_OUT, rollback toàn bộ
UPDATE session_seats SET status = 'HELD' WHERE id = ANY(:picked);
INSERT INTO seat_hold_items (hold_id, session_seat_id, status) VALUES …;
```

### Vì sao không dùng Redis cho vé đứng

Redis Lua tồn tại cho vé ngồi vì **khách chỉ đích danh từng ghế** — ta muốn từ chối thật nhanh trước khi chạm database. Với vé đứng không có gì để từ chối nhanh: khách không chỉ đích danh gì cả, và `FOR UPDATE SKIP LOCKED` đã cấp phát đúng trong một câu lệnh.

Thêm một cơ chế Redis thứ hai ở đây **không tăng độ an toàn** mà chỉ thêm một chỗ để sai. Quan trọng nhất: **chốt chặn oversell vẫn y hệt cho cả hai đường** — unique index trên `seat_hold_items (session_seat_id) WHERE status='ACTIVE'`. Chỉ khác cách *chọn* đơn vị, không khác cách *bảo vệ*.

### Về hiệu năng

Lo ngại hợp lý: `ORDER BY id` khiến mọi request cùng quét từ một đầu và phải bỏ qua các hàng đang bị khoá.

Con số thực tế: số hàng bị khoá **đồng thời** bị chặn bởi kích thước connection pool (30–50), không phải bởi số người dùng đang online. Với tối đa 10 đơn vị mỗi lần giữ chỗ, mỗi truy vấn bỏ qua nhiều nhất vài trăm hàng — không đáng kể.

Nếu đo ở tuần load test thấy vẫn là điểm nghẽn, phương án dự phòng: thêm cột `alloc_bucket SMALLINT` gán ngẫu nhiên lúc materialize, mỗi request bắt đầu từ một bucket ngẫu nhiên. Chỉ làm khi có số đo, không làm trước.

### Vé đứng trong đơn hàng và trên vé

| | Vé ngồi | Vé đứng |
| --- | --- | --- |
| `seat_label_snapshot` | `A-12-07` | `Sân trung tâm` |
| Hiện trên sơ đồ | ✅ ô ghế riêng lẻ | ❌ vùng tô màu + số chỗ còn lại |
| QR / `jti` | Duy nhất mỗi vé | **Duy nhất mỗi vé** — không đổi |
| Check-in | Không đổi | Không đổi |

Ticketing và Ordering không phân biệt hai loại: chúng chỉ thấy `order_items` trỏ tới `session_seats`. Toàn bộ khác biệt dừng ở Inventory.

## 10. API

```
# ===== Superadmin: địa điểm dùng chung =====
POST|GET|PATCH /v1/platform/venues[/{id}]
POST /v1/platform/venues/{id}/layout-versions
POST /v1/platform/layout-versions/{id}/zones          # kind + admission_type
POST /v1/platform/zones/{id}/fixed-seats:bulk         # grid hoặc CSV
POST /v1/platform/layout-versions/{id}/activate
POST /v1/platform/venues/{id}/promote                 # nâng venue riêng lên dùng chung
GET  /v1/platform/venue-conflicts                     # hàng đợi trùng lịch, xem §11

# ===== Tổ chức: địa điểm =====
GET  /v1/admin/venues?scope=all&city=                 # dùng chung + riêng của mình
GET  /v1/admin/venues/{id}/active-layout
POST|GET|PATCH /v1/admin/venues[/{id}]                # tạo/sửa venue scope=ORGANIZATION
POST /v1/admin/venues/{id}/layout-versions            # chỉ trên venue riêng
POST /v1/admin/layout-versions/{id}/zones             # chỉ trên venue riêng
POST /v1/admin/zones/{id}/fixed-seats:bulk            # chỉ trên venue riêng
POST /v1/admin/layout-versions/{id}/activate

# ===== Tổ chức: thiết kế chỗ ngồi cho một suất diễn =====
POST   /v1/admin/sessions/{id}/seating-plan           # ghim layout version
PUT    /v1/admin/seating-plans/{id}/zone-usages       # bật/tắt zone, hạng vé, standingCapacity
POST   /v1/admin/seating-plans/{id}/blocks            # khối ghế trong zone FLEXIBLE + SEATED
PATCH|DELETE /v1/admin/seating-plans/{id}/blocks/{bid}
PUT    /v1/admin/seating-plans/{id}/overrides         # REMOVE | BLOCK | SET_TIER
POST   /v1/admin/seating-plans/{id}/validate
GET    /v1/admin/seating-plans/{id}/preview           # chỗ sẽ sinh ra, chưa ghi DB
POST   /v1/admin/seating-plans/{id}/save-as-template
POST   /v1/admin/seating-plans/{id}/apply-template/{tid}
```

### Giữ chỗ — hỗ trợ hỗn hợp trong một lần

```jsonc
POST /v1/sessions/{id}/holds        // Idempotency-Key
{
  "seats": ["uuid-ghe-1", "uuid-ghe-2"],               // vé ngồi: chỉ đích danh
  "standing": [ { "zoneCode": "SAN", "quantity": 2 } ]  // vé đứng: chỉ số lượng
}
```

```jsonc
201
{
  "holdId": "…", "expiresAt": "…", "version": 1843,
  "items": [
    { "sessionSeatId": "…", "admissionType": "SEATED",   "label": "A-12-07" },
    { "sessionSeatId": "…", "admissionType": "STANDING", "label": "Sân trung tâm" }
  ]
}
```

Một lần giữ chỗ chứa được cả hai loại — nhóm bạn mua 2 vé ngồi cho người lớn tuổi và 2 vé đứng cho người trẻ là tình huống thật.

**Trần một lần giữ chỗ** do tổ chức cấu hình trong giới hạn nền tảng ([ADR-1014](adr/ADR-1014-configurable-purchase-limits.md)); mặc định ngồi ≤ 8, đứng ≤ 10, tổng ≤ 10. Vé đứng nới hơn vì nhóm đi vé đứng thường đông hơn, và bắt nhóm 12 người tách làm hai đơn nghĩa là hai mã tham chiếu, hai lần chuyển khoản, hai đồng hồ đếm ngược — ma sát đủ để mất khách. Vượt trần trả `TOO_MANY_SEATS`.

Ngoài ra còn **trần cộng dồn mỗi tài khoản trên mỗi suất diễn** (mặc định 10), tính các chỗ đang `HELD`/`RESERVED`/`SOLD`. Vượt trả `PURCHASE_LIMIT_EXCEEDED`.

Thứ tự trong một transaction: cổng Redis cho phần ghế ngồi trước, rồi cấp phát vé đứng bằng `SKIP LOCKED`, rồi ghi `seat_holds` + `seat_hold_items`. Hỏng ở bất kỳ bước nào thì rollback toàn bộ và `DEL` các key Redis đã đặt.

### Mã lỗi mới

| Mã | Nghĩa |
| --- | --- |
| `ZONE_CAPACITY_EXCEEDED` | Thiết kế vượt `max_capacity` của zone |
| `FIXED_ZONE_NOT_EDITABLE` | Cố sửa ghế zone `FIXED` qua `EventSeatingPlan` |
| `DUPLICATE_SEAT_CODE` | Hai khối sinh ra cùng một `seat_code` |
| `ZONE_WITHOUT_TIER` | Zone được bật nhưng chưa gán hạng vé |
| `ZONE_SOLD_OUT` | Không đủ vé đứng còn trống trong khu vực |
| `TOO_MANY_SEATS` | Vượt trần một lần giữ chỗ (ngồi / đứng / tổng) |
| `PURCHASE_LIMIT_EXCEEDED` | Vượt trần cộng dồn của tài khoản trên suất diễn này |
| `LIMIT_EXCEEDS_PLATFORM_CEILING` | Tổ chức cấu hình trần vượt trần cứng của nền tảng |
| `MIXED_ADMISSION_IN_ZONE` | Zone `SEATED` mà đặt `standingCapacity`, hoặc ngược lại |
| `PLAN_FROZEN` | Sửa cấu trúc sau khi đã có chỗ bán |
| `SEAT_ALREADY_SOLD` | Cố bỏ/dời một chỗ đã bán |
| `LAYOUT_VERSION_ARCHIVED` | Ghim vào version đã lưu trữ |
| `VENUE_NOT_EDITABLE` | Tổ chức cố sửa mặt bằng của venue `PLATFORM` |

Preflight publish gồm 4 mục: seating plan hợp lệ, có ít nhất một zone được bật, mọi chỗ bán được có hạng vé, sales window hợp lệ.

## 11. Cảnh báo trùng lịch địa điểm

**Chốt: cảnh báo, không chặn.** NexaTicket **không** quản lý việc thuê địa điểm — việc đó diễn ra ngoài hệ thống, nên hệ thống không thể biết tổ chức nào đã thuê. Nó chỉ có thể phát hiện hai suất diễn cùng địa điểm trùng khung giờ và báo cho những người cần biết.

### Cách phát hiện

Khoảng chiếm dụng của một suất diễn tính cả thời gian dựng và tháo:

```text
[ starts_at − venue.setup_minutes , ends_at + venue.teardown_minutes ]
```

Hai suất trùng nhau khi hai khoảng này giao nhau, trên cùng một `venue_id` có `scope = PLATFORM`. Kiểm tra chạy khi: tạo suất diễn, đổi giờ, và khi publish.

Mặc định 240 phút dựng / 180 phút tháo, superadmin chỉnh theo từng địa điểm khi tạo. Gợi ý theo quy mô: quán cà phê hoặc sân khấu nhỏ 60–120 phút; nhà hát 500–2.000 chỗ 240 phút; sân vận động 1–2 ngày. Đặt quá lớn thì cảnh báo suốt và người dùng quen bỏ qua; đặt quá nhỏ thì bỏ sót đụng độ thật — nên hỏi chủ địa điểm chứ đừng đoán.

### Ai được biết gì — phần này phải cẩn thận

Tiết lộ rằng tổ chức B có sự kiện **chưa công bố** tại địa điểm đó vào ngày đó là **rò rỉ thông tin cạnh tranh**. Quy tắc:

| Suất diễn kia đang | Tổ chức đang thao tác thấy | Superadmin thấy |
| --- | --- | --- |
| `PUBLISHED` (đã công khai) | Tên sự kiện + tên tổ chức + khung giờ | Đầy đủ |
| `DRAFT` / chưa công bố | Chỉ *"Địa điểm này đã có lịch khác trong khung giờ. Vui lòng xác nhận với chủ địa điểm."* | Đầy đủ |

Thông báo gửi đi:

| Người nhận | Khi nào | Kênh |
| --- | --- | --- |
| Tổ chức đang tạo/publish | Ngay lúc thao tác | Cảnh báo inline trong response (`warnings[]`, **không** phải lỗi) |
| Superadmin | Mọi lần phát hiện | Hàng đợi `/v1/platform/venue-conflicts` + email nếu cả hai đã `PUBLISHED` |
| Tổ chức của suất diễn có trước | Chỉ khi **cả hai** đã `PUBLISHED` | Email |

### Truy vấn xuyên tenant — ngoại lệ có kiểm soát

Việc phát hiện này bắt buộc phải đọc dữ liệu của tổ chức khác, tức là một ngoại lệ với ADR-0008. Bốn ràng buộc để nó không thành lỗ hổng:

1. Một truy vấn duy nhất, đánh dấu tường minh `@CrossTenantQuery(reason = "venue schedule conflict")`, không đi qua Hibernate tenant filter.
2. Chỉ trả về đúng bốn trường: `sessionId`, `starts_at`, `ends_at`, `status`. Tên sự kiện và tên tổ chức chỉ được ghép vào **sau khi** kiểm tra `status = PUBLISHED`.
3. Ghi audit mỗi lần chạy.
4. Có integration test khẳng định không trường nào khác rò ra khi suất diễn kia chưa công bố.

### Lưu vết

```sql
venue_schedule_conflicts (
  id UUID PRIMARY KEY,
  venue_id UUID NOT NULL,
  session_a_id UUID NOT NULL, organization_a_id UUID NOT NULL,
  session_b_id UUID NOT NULL, organization_b_id UUID NOT NULL,
  overlap_from TIMESTAMPTZ NOT NULL, overlap_to TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL,                  -- OPEN | ACKNOWLEDGED | RESOLVED
  detected_at TIMESTAMPTZ NOT NULL,
  resolved_by UUID, resolved_at TIMESTAMPTZ, note TEXT,
  UNIQUE (session_a_id, session_b_id)
);
```

Event: `VenueScheduleConflictDetected`.

**Không thuộc phạm vi:** đặt/giữ chỗ địa điểm, hợp đồng thuê, lịch khả dụng. Nếu sau này nền tảng thật sự nắm việc cho thuê, thêm `venue_bookings` do superadmin quản lý và nâng cảnh báo thành chặn cứng.

## 12. Ảnh hưởng lên UI

Màn `A-SEATMAP` của v1 tách làm bốn:

| Màn | Ai dùng | Nội dung |
| --- | --- | --- |
| **P-VENUE** *(mới)* | Superadmin | Tạo địa điểm dùng chung, vẽ zone, nhập ghế cố định (grid + CSV), kích hoạt phiên bản, nâng cấp venue riêng |
| **P-CONFLICTS** *(mới)* | Superadmin | Hàng đợi trùng lịch, đánh dấu đã xử lý |
| **A-VENUE** *(sửa)* | Tổ chức | Danh sách địa điểm dùng chung + riêng; tạo và sửa mặt bằng **địa điểm riêng** |
| **A-SEATING-PLAN** *(thay A-SEATMAP)* | Tổ chức | Bật/tắt zone, kê khối ghế, đặt sức chứa vé đứng, gán hạng vé, chặn ghế, xem trước |

Màn khách `C-SEATS` với sự kiện hỗn hợp:

```text
┌──────────────────────────────────────────┐
│ ← Sự kiện · Suất 19:00 · [04:59]         │
├───────────────┬──────────────────────────┤
│ Zone chips    │   ┌────────────────┐     │
│ [Khán đài A]  │   │  KHÁN ĐÀI A    │     │  ← ô ghế riêng lẻ
│ [Khán đài B]  │   │  ▪▪▪▪▪▪▪▪▪▪    │     │
│ [Sân đứng]    │   ├────────────────┤     │
│ [VIP]         │   │ SÂN TRUNG TÂM  │     │  ← vùng tô màu
│               │   │ Vé đứng        │     │
│ Legend        │   │ còn 340/2.000  │     │
│               │   │ [−]  2  [+]    │     │  ← chọn số lượng
├───────────────┴──────────────────────────┤
│ Đã chọn: A-12-07, A-12-08 + 2 vé đứng     │
│ Tổng 4.400.000₫       [ Giữ chỗ (4) ]     │
└──────────────────────────────────────────┘
```

Legend thêm một trạng thái cho khu vực đứng. Zone `SEATED` vẽ theo `pos_x/pos_y`; zone `STANDING` vẽ theo `boundary` polygon kèm số chỗ còn lại.

Cả hai vẫn tới frontend qua **cùng một** endpoint `GET /v1/sessions/{id}/seats`, chỉ thêm `zoneCode` và `admissionType`, cộng một khối tóm tắt tồn kho theo zone cho vé đứng — **không** trả 2.000 đơn vị ảo xuống client.

Giữ nguyên quyết định v1: **không làm WYSIWYG kéo thả**. Công cụ là khối tham số + CSV + mẫu có sẵn.

## 13. Ánh xạ từ v1

| v1 | v2 |
| --- | --- |
| `venues` (thuộc tổ chức) | `venues` với `scope = PLATFORM` hoặc `ORGANIZATION` |
| `venue_seat_maps` | `venue_layout_versions` |
| `seat_map_seats` | `venue_fixed_seats`, hoặc sinh từ `event_flexible_blocks`, hoặc đơn vị ảo vé đứng |
| Gán ghế → hạng vé (`PUT /seat-tiers`) | `event_zone_usages` + `event_flexible_blocks` + `event_seat_overrides` |
| `session_seats.seat_map_seat_id` | `session_seats.seat_code` + `zone_code` + `admission_type` + `source_kind`/`source_ref` |
| `UNIQUE (event_session_id, seat_map_seat_id)` | `UNIQUE (event_session_id, seat_code)` |
| — | `venue_schedule_conflicts` (mới) |

## 14. Điều không thay đổi

Đáng nói vì nó xác nhận ranh giới bounded context đặt đúng chỗ:

- **Chốt chặn oversell**: unique index trên `seat_hold_items` — **giống hệt cho cả vé ngồi và vé đứng**.
- **Inventory**: thuật toán Redis Lua cho vé ngồi, `FOR UPDATE` có sắp thứ tự, bộ đếm `availability_version`, WebSocket — không đổi. Chỉ **thêm** đường cấp phát vé đứng bằng `SKIP LOCKED`.
- **Ordering, Payment, Ledger, Payout, Ticketing**: không biết mô hình địa điểm tồn tại, cũng không phân biệt vé ngồi với vé đứng.
- Hợp đồng `GET /v1/sessions/{id}/seats` và `POST /holds` chỉ **thêm** trường, không phá vỡ cái cũ.

Toàn bộ độ phức tạp của địa điểm dừng lại ở bước materialize; toàn bộ độ phức tạp của vé đứng dừng lại ở bước cấp phát trong Inventory.
