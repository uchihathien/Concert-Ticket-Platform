# Web screens — Admin & Platform (`web-admin`)

Admin: desktop-first, sidebar. Có thể dùng nền sáng đọc bảng lâu **hoặc** dark cùng brand — **chốt một mode**, không toggle. RBAC: ẩn nav không đủ quyền ([rbac](../../00-discovery/rbac-permission-matrix.md)).

Chrome chung:

```text
┌──────────┬─────────────────────────────────┐
│ NexaTicket│ Org switcher        User ▾     │
│ Admin     ├─────────────────────────────────┤
│ Tổng quan │                                 │
│ Sự kiện   │         Content                 │
│ Địa điểm  │                                 │
│ KM        │                                 │
│ TK ngân hàng│                               │
│ Thành viên│                                 │
│ Đối soát  │                                 │
└──────────┴─────────────────────────────────┘
```

---

## A-LOGIN — Đăng nhập admin

| | |
| --- | --- |
| **Route** | `/login` |
| **Auth** | Public |
| **Milestone** | 01 |

### Mục đích

OIDC; MFA bắt buộc ORG_ADMIN / ORG_OWNER / PLATFORM_ADMIN.

### Layout

Brand “NexaTicket Admin” + nút “Đăng nhập”. Copy: “Tài khoản quản trị yêu cầu xác thực hai bước.”

### Tương tác

- Không membership → màn “Chưa thuộc tổ chức — liên hệ admin.”
- Multi-org → chọn org rồi A-DASH
- Platform role → có thêm mục Platform

### Acceptance

- [ ] User CUSTOMER thuần không vào được shell admin (403 / màn từ chối)

---

## A-DASH — Tổng quan

| | |
| --- | --- |
| **Route** | `/org/[orgId]/dashboard` |
| **Auth** | EVENT_MANAGER+ |
| **Milestone** | 04 |

### Mục đích

4 metric vận hành; không card-soup.

### Layout

```text
┌────────────────────────────────────────────┐
│ Filter: Event ▾  Session ▾  From–To        │
├──────────────┬──────────────┬──────────────┤
│ GMV          │ Đơn          │ Ghế          │
│ 120.000.000₫ │ paid 80      │ sold 92      │
│              │ awaiting 3   │ held 5       │
│              │ expired 20   │ reserved 3   │
│              │ review 1     │ available 100│
├──────────────┴──────────────┴──────────────┤
│ [Xem đối soát] nếu review > 0              │
└────────────────────────────────────────────┘
```

### Thành phần

| Block | GMV lớn; breakdown orders; seat counts; filter |
| CTA | Deep link A-REVIEW khi có MANUAL_REVIEW |

### API

`GET /v1/admin/metrics?eventId&sessionId&from&to`

### States

Loading skeleton metrics; empty event list → CTA tạo event.

### Acceptance

- [ ] Đổi filter refetch
- [ ] Tenant scope: không thấy org khác

---

## A-VENUE-LIST — Danh sách địa điểm

| | |
| --- | --- |
| **Route** | `/org/[orgId]/venues` |
| **Auth** | EVENT_MANAGER+ |
| **Milestone** | 02 |

### Mục đích

CRUD list venues.

### Thành phần

| Table/list | Name, city, số seat map, updated |
| CTA | “Tạo địa điểm” → drawer/page form |
| Empty | “Tạo địa điểm đầu tiên” |
| Row tap | A-VENUE |

### Form tạo (drawer)

Fields: name*, city, address. Validate name required.

### API

`GET/POST /v1/admin/venues`

### Acceptance

- [ ] Audit không bắt buộc trên UI nhưng tạo success toast

---

## A-VENUE — Chi tiết địa điểm

| | |
| --- | --- |
| **Route** | `/org/[orgId]/venues/[id]` |
| **Auth** | EVENT_MANAGER+ |
| **Milestone** | 02 |

### Mục đích

Sửa venue; quản lý seat maps.

### Layout

```text
┌────────────────────────────────────────────┐
│ Venue name          [Lưu]                  │
│ City / Address form                        │
├────────────────────────────────────────────┤
│ Seat maps                                  │
│ v3 ACTIVE   120 seats  [Mở]                │
│ v2 ARCHIVED 120 seats  [Xem]               │
│ [Tạo seat map mới]                         │
└────────────────────────────────────────────┘
```

### Tương tác

- Tạo map → A-SEATMAP draft
- Mở ACTIVE/DRAFT → A-SEATMAP

### API

`GET/PATCH venue`, list seat maps

---

## A-SEATMAP — Author sơ đồ ghế

| | |
| --- | --- |
| **Route** | `/org/[orgId]/venues/[venueId]/seat-maps/[mapId]` |
| **Auth** | EVENT_MANAGER+ |
| **Milestone** | 02 |

### Mục đích

Thêm ghế (grid/CSV); activate map.

### Layout

```text
┌────────────────────────────────────────────┐
│ Seat map name · status DRAFT|ACTIVE        │
│ Tabs: [Grid] [CSV]                         │
├────────────────────────────────────────────┤
│ Grid: Section | Row | From | To | [Thêm]   │
│ Table seats: section, row, label, code     │
│ CSV: download template · upload            │
├────────────────────────────────────────────┤
│ Tổng ghế: 120                              │
│ [Lưu nháp]  [Kích hoạt map]                │
└────────────────────────────────────────────┘
```

### Tương tác

- Activate: confirm “Map ACTIVE cũ sẽ ARCHIVED”
- CSV lỗi: hiện dòng lỗi (row number + message)
- ACTIVE map: edit ghế hạn chế (MVP: cho thêm? chốt **DRAFT mới** nếu đổi lớn — map ACTIVE read-only seats, chỉ xem)

**Chốt MVP:** ACTIVE = seats read-only; sửa = tạo version DRAFT mới từ copy (Should) hoặc chỉ edit DRAFT. Đơn giản: chỉ edit khi DRAFT; ACTIVE chỉ xem + dùng cho event.

### States

| State | UI |
| --- | --- |
| Empty seats | CTA thêm ghế trước activate |
| Activate fail | Toast lý do |

### API

`POST seats:bulk`, `POST .../activate`, `GET seat-map`

### Acceptance

- [ ] Activate đúng 1 ACTIVE / venue
- [ ] CSV template tải được

---

## A-EVENT-LIST — Danh sách sự kiện

| | |
| --- | --- |
| **Route** | `/org/[orgId]/events` |
| **Auth** | EVENT_MANAGER+ |
| **Milestone** | 02 |

### Thành phần

| Filter | status DRAFT/PUBLISHED/UNPUBLISHED |
| Row | Title, status badge, published_at, sessions count |
| CTA | “Tạo sự kiện” |
| Empty | “Tạo sự kiện đầu tiên” |
| Tap | A-EVENT |

### API

`GET /v1/admin/events`

---

## A-EVENT — Wizard sự kiện

| | |
| --- | --- |
| **Route** | `/org/[orgId]/events/[id]` |
| **Auth** | EVENT_MANAGER+ |
| **Milestone** | 02 |

### Mục đích

Soạn event qua 5 bước; save draft mỗi bước.

### Stepper

```text
(1) Thông tin → (2) Suất → (3) Hạng vé → (4) Gán ghế → (5) Xem lại
```

### Bước 1 — Thông tin

| Field | title*, slug*, description, image upload, venue*, seat map ACTIVE* |
| Slug | Auto từ title; editable; unique check blur |

### Bước 2 — Suất diễn

| Field | starts_at*, ends_at, sales_opens_at, sales_closes_at |
| Actions | Thêm suất (MVP 1 suất đủ; multi Should) |

### Bước 3 — Hạng vé

| Field | name*, unitPriceVnd* |
| Actions | Thêm/xóa tier; ≥1 tier |

### Bước 4 — Gán ghế

| UI | Chọn section/row range → gán tier; hoặc multi-select seats |
| Validate | Highlight ghế chưa có tier |

### Bước 5 — Xem lại

Summary read-only + CTA “Tiếp tục publish” → A-PUBLISH

### States

| Save pending | Spinner trên “Lưu” |
| Validation | Inline per step; không next nếu lỗi |

### API

CRUD event/session/tier + `PUT seat-tiers`

### Acceptance

- [ ] Refresh trang giữ draft
- [ ] Không publish từ đây nếu thiếu bank (chặn ở A-PUBLISH)

---

## A-PUBLISH — Preflight & publish

| | |
| --- | --- |
| **Route** | `/org/[orgId]/events/[id]/publish` |
| **Auth** | EVENT_MANAGER+ |
| **Milestone** | 02 |

### Mục đích

Checklist bắt buộc trước publish.

### Layout

```text
┌────────────────────────────────────────────┐
│ Xuất bản sự kiện                           │
│ ☑ Seat map ACTIVE                          │
│ ☑ Mọi ghế bán được đã có tier              │
│ ☐ Có tài khoản ngân hàng active  → [Thêm]  │
│ ☑ Sales window hợp lệ                      │
│                                            │
│ [Hủy]              [Xuất bản] (disabled)   │
└────────────────────────────────────────────┘
```

### Tương tác

- Xuất bản chỉ enable khi all green
- Success → toast + link mở C-DETAIL public + status PUBLISHED trên A-EVENT-LIST
- Unpublish: nút riêng trên event đã published (confirm)

### API errors → checklist

| Code | Item đỏ |
| --- | --- |
| `NO_ACTIVE_BANK_ACCOUNT` | Bank |
| `SEATS_WITHOUT_TIER` | Tier mapping |
| `INVALID_SALES_WINDOW` | Sales window |

### API

`POST /v1/admin/events/{id}/publish` · `unpublish`

### Acceptance

- [ ] Materialize session_seats phía server; UI chỉ chờ success
- [ ] Customer thấy event sau publish (manual check)

---

## A-PROMO — Khuyến mãi

| | |
| --- | --- |
| **Route** | `/org/[orgId]/promotions` |
| **Auth** | EVENT_MANAGER+ |
| **Milestone** | 02 |

### Mục đích

CRUD promotion PERCENT / FIXED_AMOUNT.

### Thành phần

| Table | code, type, value, window, active, redemptions |
| Drawer form | type, percent hoặc amount, code optional, starts/ends, max_redemptions, active |
| Validate | PERCENT 1–100; FIXED &gt; 0 |

### API

`POST/GET/PATCH /v1/admin/promotions`

### Acceptance

- [ ] Promo inactive không apply được lúc order (test E2E)

---

## A-BANK — Tài khoản nhận tiền

| | |
| --- | --- |
| **Route** | `/org/[orgId]/bank-accounts` |
| **Auth** | ORG_ADMIN+ |
| **Milestone** | 02 |

### Mục đích

Cấu hình TK VietQR; mask số.

### Thành phần

| Table | bank name/code, ****last4, account name, active, preferred |
| Form | bank_code select (NH VN), account_number (full lúc tạo), account_name |
| After save | Chỉ last4 |
| Toggle | active / preferred |

### Copy cảnh báo

“Tiền chuyển thẳng vào tài khoản tổ chức. NexaTicket không giữ tiền.”

### API

`POST/GET/PATCH /v1/admin/bank-accounts`

### Acceptance

- [ ] EVENT_MANAGER không thấy nav / 403
- [ ] Số TK không hiện full sau save
- [ ] Audit khi đổi (toast “Đã ghi audit” không bắt buộc hiện)

---

## A-MEMBERS — Thành viên

| | |
| --- | --- |
| **Route** | `/org/[orgId]/members` |
| **Auth** | ORG_ADMIN+ |
| **Milestone** | 01–02 |

### Mục đích

Invite và đổi role.

### Thành phần

| Table | email, name, role badge, joined |
| Invite | email + role select + gửi |
| Actions | Đổi role, remove (confirm) |
| Roles | CHECKIN_STAFF, EVENT_MANAGER, ORG_ADMIN (OWNER chỉ transfer — Should) |

### API

invite/list/patch/remove membership endpoints

### Acceptance

- [ ] Không tự xóa OWNER cuối cùng (error)

---

## A-REVIEW — Hàng đợi đối soát

| | |
| --- | --- |
| **Route** | `/org/[orgId]/payments/review` |
| **Auth** | ORG_ADMIN+ |
| **Milestone** | 04 |

### Mục đích

Xử lý MANUAL_REVIEW / mismatch.

### Layout

```text
┌────────────────────────────────────────────┐
│ Thời gian | Reference | Expected | Received│
│ Reason    | Order link| [Xử lý]            │
└────────────────────────────────────────────┘
         ↓ drawer A-REFUND actions
```

### Empty

“Không có giao dịch cần xử lý”

### API

List payment attempts status MANUAL_REVIEW (+ filters)

### Acceptance

- [ ] Tap mở drawer đủ context order

---

## A-REFUND — Drawer resolve / hoàn tiền

| | |
| --- | --- |
| **Surface** | Drawer trên A-REVIEW hoặc Order admin |
| **Auth** | ORG_ADMIN+ |
| **Milestone** | 04 |

### Mục đích

Confirm paid / mark refunded / reject — có reason + confirm modal.

### Thành phần

| Info | reference, amounts, order seats, timestamps |
| Actions | CONFIRM_PAID · MARK_REFUNDED · REJECT |
| Reason | Bắt buộc text |
| Confirm | Modal “Phát hành vé?” / “Đánh dấu hoàn tiền (chuyển khoản ngoài hệ thống)?” |

### API

`POST /admin/payments/{attemptId}/resolve`  
`POST /admin/orders/{id}/refund`

### Copy

“Hoàn tiền ngân hàng thực hiện ngoài NexaTicket. Hệ thống chỉ cập nhật trạng thái.”

### Acceptance

- [ ] Success → row biến mất khỏi review / status đổi
- [ ] Không cho confirm nếu thiếu reason

---

## P-TENANTS — Platform tenants

| | |
| --- | --- |
| **Route** | `/platform/tenants` |
| **Auth** | PLATFORM_ADMIN |
| **Milestone** | 01+ |

### Mục đích

Tạo / tạm khóa organization.

### Thành phần

| Table | name, slug, status ACTIVE/SUSPENDED, created |
| Actions | Create, Suspend/Activate (confirm + reason) |
| Badge | “Support mode” khi đang xem cross-tenant |

### API

Platform tenant admin endpoints

### Acceptance

- [ ] Mọi suspend ghi audit
- [ ] Org thường không thấy route này

---

## P-AUDIT — Audit log

| | |
| --- | --- |
| **Route** | `/platform/audit` |
| **Auth** | PLATFORM_ADMIN (org admin xem audit org — Should cùng UI filter tenant) |
| **Milestone** | 04 |

### Mục đích

Tra cứu thay đổi admin/financial.

### Thành phần

| Filters | org, actor, entity_type, from–to, correlation_id |
| Table | time, actor, action, entity, link expand before/after JSON |
| PII | Mask account numbers trong after/before |

### API

`GET /v1/admin/audit-logs` hoặc platform equivalent

### Acceptance

- [ ] Expand before/after đọc được
- [ ] Không lộ secrets
