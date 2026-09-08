# Web screens — Customer (`web-customer`)

Palette dark theo [design-direction](../design-direction.md). Bottom nav mobile: Khám phá | Vé | Tài khoản — ẩn trên C-SEATS và checkout.

---

## C-HOME — Trang chủ

| | |
| --- | --- |
| **Route** | `/` |
| **Auth** | Public |
| **Milestone** | 02 |

### Mục đích

First viewport = một composition brand: **NexaTicket** hero + 1 CTA vào khám phá. Không list event / stats trên viewport đầu.

### Layout

```text
Desktop                         Mobile
┌─────────────────────────┐     ┌──────────────────┐
│ Top: Logo · Search · Me │     │ Logo      Account│
├─────────────────────────┤     ├──────────────────┤
│                         │     │                  │
│  FULL-BLEED VISUAL      │     │ FULL-BLEED       │
│  NexaTicket             │     │ NexaTicket       │
│  Headline 1 dòng        │     │ Headline         │
│  1 câu phụ              │     │ 1 câu            │
│  [Xem sự kiện] [Đăng nhập]│   │ [Xem sự kiện]    │
│                         │     │                  │
├─────────────────────────┤     ├──────────────────┤
│ (below fold optional)   │     │ Bottom nav       │
│ upcoming strip — 1 hàng │     └──────────────────┘
└─────────────────────────┘
```

### Thành phần

| Element | Spec |
| --- | --- |
| Brand | Display “NexaTicket” — lớn hơn headline |
| Headline | 1 dòng, không dài hơn ~40 ký tự |
| Sub | 1 câu ≤ 90 ký tự |
| CTA primary | “Xem sự kiện” → C-LIST |
| CTA secondary | “Đăng nhập” nếu guest → C-LOGIN |
| Visual | Full-bleed ảnh atmosphere (venue/concert); không card inset |
| Below fold | Optional: 3–6 event sắp tới — **không** trên first viewport |

### States

- Loading ảnh: gradient placeholder cùng composition
- Không empty state (luôn có brand)

### API

- Optional `GET /v1/events?upcoming=1&limit=6` cho below-fold

### Copy

- Headline gợi ý: “Chọn ghế. Thanh toán. Vào cửa.”
- Sub: “Vé sự kiện realtime — giữ ghế rõ ràng, chuyển khoản VietQR.”

### Responsive / a11y

- CTA trong thumb zone mobile
- Alt text ảnh decorative `alt=""` nếu thuần trang trí; nếu có nội dung thì mô tả ngắn
- Focus order: logo → CTA primary → secondary → nav

### Acceptance

- [ ] Bỏ nav vẫn nhận ra NexaTicket trên first viewport
- [ ] Không có stat strip / schedule / card grid trên first viewport
- [ ] CTA primary tới `/events`

---

## C-LIST — Danh sách sự kiện

| | |
| --- | --- |
| **Route** | `/events` |
| **Auth** | Public |
| **Milestone** | 02 |

### Mục đích

Tìm và lọc event `PUBLISHED`.

### Layout

```text
┌────────────────────────────────────────┐
│ Search [____________]  City ▾  Date ▾  │
├────────────────────────────────────────┤
│ [img] Title                            │
│       City · 01/11/2026 · từ 500.000₫  │
│ ─────────────────────────────────────  │
│ [img] ...                              │
└────────────────────────────────────────┘
```

Mobile: filter mở **bottom sheet**; list 1 cột.

### Thành phần

| Element | Spec |
| --- | --- |
| Search | Debounce 300ms; placeholder “Tên sự kiện, nghệ sĩ…” |
| City | Select / combobox |
| Date | from–to hoặc preset “Tuần này” |
| Row/card | Ảnh, title (2 dòng), city, ngày session gần nhất, giá từ |
| Pagination | Infinite scroll hoặc “Xem thêm” |

### Tương tác

- Tap row → C-DETAIL (`/events/{slug}`)
- Xóa filter khi empty

### States

| State | UI |
| --- | --- |
| Loading | Skeleton 6 rows |
| Empty | “Chưa có sự kiện phù hợp” + nút Xóa bộ lọc |
| Error | Banner + Thử lại |

### API

`GET /v1/events?query&city&from&to&page`

### Acceptance

- [ ] Chỉ hiện PUBLISHED
- [ ] Empty + clear filters hoạt động
- [ ] Deep link giữ query params

---

## C-DETAIL — Chi tiết sự kiện

| | |
| --- | --- |
| **Route** | `/events/[slug]` |
| **Auth** | Public |
| **Milestone** | 02 |

### Mục đích

Hiểu event, chọn suất, vào chọn ghế.

### Layout

```text
┌─────────────────────────────────┐
│ ←  (breadcrumb / back)          │
│ [Hero image full width]         │
│ Title (display)                 │
│ Venue · Address · City          │
│                                 │
│ Suất diễn                       │
│ ( ) 01/11 19:00  (•) 02/11 ...  │
│                                 │
│ Hạng vé (tham khảo)             │
│ VIP — 1.500.000₫                │
│ Standard — 500.000₫             │
│                                 │
│ Mô tả (expand)                  │
├─────────────────────────────────┤
│ [        Chọn ghế        ] sticky│
└─────────────────────────────────┘
```

### Thành phần

| Element | Spec |
| --- | --- |
| Session picker | Radio / chips; mặc định suất sắp mở bán gần nhất |
| Tier list | Tên + giá VND; không chọn ghế ở đây |
| Sticky CTA | “Chọn ghế” — cần session đã chọn |
| Share | Should: copy link |

### Tương tác

- CTA → C-SEATS với `sessionId` đã chọn
- Guest: vẫn vào C-SEATS; hold mới bắt login

### States

| State | UI |
| --- | --- |
| 404 | “Không tìm thấy sự kiện” → C-LIST |
| UNPUBLISHED/slug cũ | 404 public |
| Sales chưa mở | CTA disabled + “Mở bán lúc …” |
| Sold out session | CTA disabled + “Hết chỗ” |

### API

`GET /v1/events/{slug}`

### Copy

- CTA: “Chọn ghế”
- Sales closed: “Suất này đã đóng bán”

### Acceptance

- [ ] Đổi session cập nhật tier/giá
- [ ] CTA mang đúng sessionId

---

## C-SEATS — Sơ đồ ghế

| | |
| --- | --- |
| **Route** | `/events/[slug]/sessions/[id]/seats` |
| **Auth** | Public xem; login để hold |
| **Milestone** | 03 |

### Mục đích

Chọn 1..N ghế realtime, giữ ghế (hold 5 phút).

### Layout

```text
┌──────────────────────────────────────────┐
│ ← Event short · Suất 19:00               │
│ Countdown giữ: 04:59 (khi đang hold)     │
│ Banner WS nếu disconnect                 │
├───────────────┬──────────────────────────┤
│ Section chips │                          │
├───────────────┤   Seat canvas            │
│ Legend        │   (pan / zoom mobile)    │
├───────────────┴──────────────────────────┤
│ Đã chọn: A-1-01, A-1-02 · 3.000.000₫     │
│ [Giữ ghế]  hoặc  [Tiếp tục thanh toán]   │
└──────────────────────────────────────────┘
```

Desktop: legend trái hoặc dưới canvas. Mobile: tray bottom; ẩn bottom nav.

### Thành phần

| Element | Spec |
| --- | --- |
| Canvas | Seat theo legend màu; hit area ≥ 44px mobile |
| Section chips | Filter focus section |
| Legend | AVAILABLE / HELD mine / HELD other / RESERVED / SOLD / BLOCKED |
| Selection tray | Labels + tổng tiền + count |
| CTA primary | “Giữ ghế (n)” — n = số đã chọn |
| List mode | Should: toggle danh sách ghế trống (a11y) |
| Max seats | 8 / hold — toast khi vượt |

### Tương tác

1. Tap AVAILABLE → toggle select (optimistic màu)
2. CTA Giữ ghế → nếu guest: C-LOGIN `returnUrl` + persist selection `sessionStorage` ≤ 2 phút
3. `POST /holds` success → countdown; CTA thành “Tiếp tục” → C-HOLD **hoặc** cập nhật tray (chốt: navigate C-HOLD)
4. WS `seat.availability.changed`: update màu; gap version → refetch
5. Tap HELD other / SOLD: toast ngắn, không chọn

### States

| State | UI |
| --- | --- |
| Loading | Skeleton canvas |
| REDIS 503 | Toast + Retry; giữ selection |
| 409 SEAT_UNAVAILABLE | Bỏ ghế lỗi + refetch + toast mã lỗi |
| Hold expired modal | “Hết thời gian giữ ghế” → về selection trống |
| WS disconnect | Banner “Đang cập nhật chậm…” + poll 10s |

### API / realtime

- `GET /v1/sessions/{id}/seats`
- `POST /v1/sessions/{id}/holds` + Idempotency-Key
- `DELETE /v1/holds/{id}` (nút hủy nếu đang hold)
- WS `seat.availability.changed`

### Copy

- “Giữ ghế (2)”
- “Ghế vừa được người khác giữ (SEAT_UNAVAILABLE)”
- “Tối đa 8 ghế mỗi lần giữ”

### Acceptance

- [ ] Legend đủ 6 trạng thái
- [ ] Countdown từ `expiresAt` server
- [ ] 2 trình duyệt: ghế held hiện đúng trên máy kia
- [ ] Không bottom nav mobile

---

## C-HOLD — Xác nhận giữ ghế

| | |
| --- | --- |
| **Route** | `/checkout/hold/[holdId]` |
| **Auth** | Owner |
| **Milestone** | 03 |

### Mục đích

Xác nhận ghế + giá + promo; tạo order (chuyển RESERVED 15 phút).

### Layout

```text
┌────────────────────────────────┐
│ Giữ ghế còn 04:12              │
├────────────────────────────────┤
│ Ghế                            │
│ A-1-01  VIP      1.500.000₫    │
│ A-1-02  VIP      1.500.000₫    │
│                                │
│ Mã giảm giá [________] [Áp dụng]│
│ Tạm tính / Giảm / Tổng         │
├────────────────────────────────┤
│ [  Tiếp tục thanh toán  ]      │
│ [  Hủy giữ ghế          ]      │
└────────────────────────────────┘
```

### Thành phần

| Element | Spec |
| --- | --- |
| Countdown | Warn &lt; 60s |
| Line items | Seat label, tier, price |
| Promo | Optional; lỗi inline |
| CTA primary | Tạo order |
| CTA danger/ghost | Hủy hold |

### Tương tác

- Primary → `POST /orders` → C-PAY
- Hủy → `DELETE /holds/{id}` → C-SEATS
- Hết giờ → modal → C-SEATS

### States

| State | UI |
| --- | --- |
| HOLD_EXPIRED / HOLD_NOT_OWNED | Redirect C-SEATS + toast |
| PROMOTION_INVALID | Inline dưới field |
| Mutation pending | Disable cả hai CTA |

### API

- Load hold thuộc user (từ create response hoặc GET nếu có)
- `POST /v1/orders` `{ holdId, promotionCode? }`
- `DELETE /v1/holds/{id}`

### Acceptance

- [ ] Không tạo order khi countdown = 0
- [ ] Tổng tiền khớp API response

---

## C-PAY — Thanh toán VietQR

| | |
| --- | --- |
| **Route** | `/checkout/orders/[orderId]/pay` |
| **Auth** | Owner |
| **Milestone** | 03 |

### Mục đích

Hiện QR + số tiền + nội dung CK; chờ PAID trong 15 phút.

### Layout

```text
┌────────────────────────────────┐
│ Thanh toán · còn 14:32         │
├────────────────────────────────┤
│ Số tiền   3.000.000₫    [Copy] │
│ Nội dung  NTX9F2K1      [Copy] │
│ NH · ****5678 · Chủ TK         │
├────────────────────────────────┤
│         [VietQR image]         │
│   Quét bằng app ngân hàng      │
├────────────────────────────────┤
│ Trạng thái: Đang chờ chuyển khoản│
│ [Tôi đã chuyển — kiểm tra lại] │
│ Hướng dẫn 3 bước (rút gọn)     │
└────────────────────────────────┘
```

Ẩn top secondary nav / bottom nav. Back: confirm nếu còn AWAITING.

### Thành phần

| Element | Spec |
| --- | --- |
| Countdown | `paymentExpiresAt`; warn &lt; 2p |
| Copy buttons | Amount + reference — toast “Đã sao chép” |
| QR | Rộng ≥ 70% content width mobile |
| Status line | Mapping order status |
| Force poll | Nút “Tôi đã chuyển” |
| Guide | 1) Mở app NH 2) Quét QR 3) Đúng tiền + nội dung |

### Tương tác

- Poll `GET order` 5–8s khi tab visible
- PAID → success flash ngắn → C-TICKET hoặc C-TICKETS
- EXPIRED → state hết hạn + CTA về C-DETAIL
- MANUAL_REVIEW → banner không vé

### States

Xem [states §5](../states.md). Offline: banner; giữ QR trên màn.

### API

- Order detail + vietQr payload từ create hoặc GET
- Poll order status

### Copy

- “Chuyển khoản đúng số tiền và nội dung để tự xác nhận.”
- “Đơn hết hạn, ghế đã mở bán lại.”
- “Đang đối soát thủ công — chưa phát hành vé.”

### Acceptance

- [ ] Copy reference 1 tap
- [ ] QR quét được app NH sandbox
- [ ] Auto navigate khi PAID
- [ ] Hết hạn không còn hiện QR như còn hạn

---

## C-ORDER — Chi tiết đơn

| | |
| --- | --- |
| **Route** | `/checkout/orders/[orderId]` |
| **Auth** | Owner |
| **Milestone** | 03 |

### Mục đích

Xem status đơn; resume thanh toán nếu AWAITING còn hạn.

### Layout

```text
┌────────────────────────────────┐
│ Đơn #NTX… · badge status       │
│ Thời gian tạo                  │
│ Line items ghế + giá           │
│ Tổng                           │
│ [Tiếp tục thanh toán] nếu OK   │
│ [Xem vé] nếu PAID              │
└────────────────────────────────┘
```

### States theo status

| Status | CTA |
| --- | --- |
| AWAITING + còn hạn | → C-PAY |
| AWAITING hết hạn / EXPIRED | → C-DETAIL event |
| PAID | → C-TICKETS |
| MANUAL_REVIEW | Chỉ thông báo |
| REFUNDED / CANCELLED | Badge + không QR |

### API

`GET /v1/orders/{id}` (chốt path OpenAPI)

### Acceptance

- [ ] Resume pay không tạo order mới

---

## C-ORDERS — Lịch sử đơn

| | |
| --- | --- |
| **Route** | `/me/orders` |
| **Auth** | Required |
| **Milestone** | 03 |

### Mục đích

Danh sách đơn của user.

### Thành phần

| Row | Event title, ngày, tổng, status badge |
| Empty | “Chưa có đơn” + CTA C-LIST |
| Tap | C-ORDER |

### API

`GET /v1/me/orders`

---

## C-TICKETS — Vé của tôi

| | |
| --- | --- |
| **Route** | `/me/tickets` |
| **Auth** | Required |
| **Milestone** | 03 |

### Mục đích

Danh sách vé đã phát hành.

### Thành phần

| Segment | Sắp tới / Đã dùng / Tất cả (Should) |
| Row | Event, suất, ghế summary, status |
| Empty | “Bạn chưa có vé” + “Xem sự kiện” |
| Tap | C-TICKET |

### API

`GET /v1/me/tickets`

---

## C-TICKET — Chi tiết vé + QR

| | |
| --- | --- |
| **Route** | `/me/tickets/[id]` |
| **Auth** | Owner |
| **Milestone** | 03 |

### Mục đích

Hiện QR check-in tại cửa.

### Layout

```text
┌────────────────────────────────┐
│ Event title                    │
│ Suất · Ghế A-1-01              │
│         ┌─────────┐            │
│         │   QR    │            │
│         └─────────┘            │
│ Trạng thái: Hợp lệ             │
│ Gợi ý: Tăng độ sáng màn hình   │
└────────────────────────────────┘
```

### States

| Ticket status | UI |
| --- | --- |
| VALID | QR đầy đủ |
| CHECKED_IN | QR + stamp “Đã check-in lúc …” |
| CANCELLED / REFUNDED | Không QR hợp lệ; copy trạng thái |

### API

`GET /v1/tickets/{id}` → qr token string

### Acceptance

- [ ] QR không chứa PII plain
- [ ] Screenshot Should warning (optional)

---

## C-LOGIN — Đăng nhập

| | |
| --- | --- |
| **Route** | `/login` |
| **Auth** | Public |
| **Milestone** | 01 |

### Mục đích

OIDC redirect; giữ `returnUrl`.

### Layout

```text
┌────────────────────────────────┐
│     NexaTicket (brand lớn)     │
│     [Đăng nhập]                │
│     Quay lại trang chủ         │
└────────────────────────────────┘
```

Không form password local. Ngay vào IdP hoặc 1 nút rõ.

### Tương tác

- Success → `returnUrl` hoặc C-HOME
- Fail IdP → message + thử lại

### Acceptance

- [ ] returnUrl sau hold flow hoạt động

---

## C-ACCOUNT — Tài khoản

| | |
| --- | --- |
| **Route** | `/account` |
| **Auth** | Required |
| **Milestone** | 01 |

### Mục đích

Hồ sơ cơ bản + đăng xuất.

### Thành phần

| Field | Email (read-only IdP), họ tên, SĐT optional |
| Links | Điều khoản, Privacy |
| CTA | Đăng xuất |

### API

`GET /v1/me`, `PATCH /v1/me` (nếu cho sửa tên/SĐT)

### Acceptance

- [ ] Logout clear session → C-HOME guest
