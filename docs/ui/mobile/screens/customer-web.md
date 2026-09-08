# Mobile web — Customer (`web-customer` &lt;768px)

Parity nghiệp vụ với [web customer](../../screens/customer.md); layout và chrome tối ưu điện thoại. Bottom nav: **Khám phá | Vé | Tài khoản** — ẩn focus mode (seats, checkout).

---

## C-HOME — Trang chủ (mobile)

| | |
| --- | --- |
| **Route** | `/` |
| **Platform** | Mobile web |
| **Auth** | Public |
| **Milestone** | 02 |

### Mục đích

Brand hero full viewport; 1 CTA vào khám phá. Không list/stats trên first screen.

### Layout (portrait)

```text
┌──────────────────────────┐
│ NexaTicket        Account│  ← top bar mỏng
├──────────────────────────┤
│                          │
│   FULL-BLEED IMAGE       │
│                          │
│   NexaTicket             │  display
│   Chọn ghế. Thanh toán.  │  headline
│   Vé realtime VietQR.    │  sub
│                          │
│   [   Xem sự kiện   ]    │  full width - 16px inset
│   Đăng nhập              │  text link
│                          │
├──────────────────────────┤
│ Khám phá │ Vé │ Tài khoản│  bottom nav
└──────────────────────────┘
```

Below fold (scroll): optional horizontal rail “Sắp diễn ra” — **không** trên first viewport.

### Thành phần

| Element | Spec mobile |
| --- | --- |
| Top bar | Logo trái; icon account phải (guest → login) |
| Hero | min-height ~70vh; ảnh cover |
| CTA | Height 52px; `--nt-accent` |
| Bottom nav | 3 tab; active tab Khám phá |
| Scroll | Không horizontal bounce trên hero |

### Navigation

- CTA → `/events`
- Account icon → `/account` hoặc login
- Tab Vé → `/me/tickets` (login nếu cần)

### States

Loading: skeleton hero gradient. Không empty.

### Platform notes

- iOS Safari: `100vh` dùng `dvh` nếu có; tránh jump khi address bar ẩn.
- Android Chrome: CTA không bị che bởi bottom nav khi scroll cuối.

### Acceptance

- [ ] First viewport không có event cards
- [ ] CTA trong thumb zone
- [ ] Bottom nav hiện

---

## C-LIST — Danh sách sự kiện (mobile)

| | |
| --- | --- |
| **Route** | `/events` |
| **Platform** | Mobile web |
| **Auth** | Public |
| **Milestone** | 02 |

### Layout

```text
┌──────────────────────────┐
│ ←  Sự kiện               │
│ [🔍 Tìm sự kiện...]      │
│ [Lọc ▾]                  │  → bottom sheet
├──────────────────────────┤
│ ┌────┐ Title             │
│ │img │ City · 01/11      │
│ └────┘ từ 500.000₫       │
│ ─────────────────────    │
│ (infinite scroll)        │
├──────────────────────────┤
│ Bottom nav               │
└──────────────────────────┘
```

### Filter bottom sheet

| Field | City select, Từ ngày, Đến ngày |
| CTA | Áp dụng / Xóa lọc |

### Tương tác

- Tap row → C-DETAIL
- Pull-to-refresh Should
- Infinite scroll load thêm

### States

| Empty | “Chưa có sự kiện phù hợp” + Xóa bộ lọc |
| Loading | Skeleton 4 rows |

### API

`GET /v1/events?query&city&from&to&page`

### Acceptance

- [ ] Filter sheet không che toàn màn vĩnh viễn
- [ ] 1 cột, không horizontal scroll list

---

## C-DETAIL — Chi tiết sự kiện (mobile)

| | |
| --- | --- |
| **Route** | `/events/[slug]` |
| **Auth** | Public |
| **Milestone** | 02 |

### Layout

```text
┌──────────────────────────┐
│ ←                        │
│ [Hero image]             │
│ Title                    │
│ Venue · City             │
│                          │
│ Suất diễn                │
│ [01/11 19:00] [02/11]    │  horizontal chips scroll
│                          │
│ Hạng vé                  │
│ VIP — 1.500.000₫         │
│ Standard — 500.000₫      │
│ Mô tả ▾                  │  collapse
├──────────────────────────┤
│ [      Chọn ghế      ]   │  sticky safe-area bottom
└──────────────────────────┘
```

### Thành phần

| Sticky CTA | Full width; disabled nếu chưa chọn session / hết bán |
| Session chips | Snap scroll; 1 selected |
| Share | Should: Web Share API hoặc copy link |

### Navigation

- CTA → C-SEATS với sessionId
- Back → C-LIST hoặc history

### States

Sales chưa mở / sold out: CTA disabled + copy inline.

### Acceptance

- [ ] Sticky CTA không che nội dung cuối khi scroll
- [ ] Session chip scroll ngang mượt

---

## C-SEATS — Sơ đồ ghế (mobile)

| | |
| --- | --- |
| **Route** | `/events/.../sessions/[id]/seats` |
| **Auth** | Public xem; login để hold |
| **Milestone** | 03 |

### Mục đích

Chọn ghế realtime; hold 5 phút. **Ẩn bottom nav.**

### Layout

```text
┌──────────────────────────┐
│ ← Hòa Âm · 19:00         │
│ Giữ ghế: 04:59           │  khi đang hold
│ ⚠ WS chậm (nếu có)       │
├──────────────────────────┤
│ [A][B][C][VIP]           │  section chips
├──────────────────────────┤
│                          │
│    Seat canvas           │
│    pinch / pan           │
│                          │
├──────────────────────────┤
│ ■ Trống ■ Tôi ■ Khác …   │  legend 1 dòng scroll ngang
├──────────────────────────┤
│ 2 ghế · 3.000.000₫       │  selection tray
│ [    Giữ ghế (2)    ]    │
└──────────────────────────┘
```

Chi tiết canvas: [seat-map-ux.md](../seat-map-ux.md).

### Thành phần

| Canvas | Pinch 0.5–3×; seat ≥ 44pt |
| Tray | Fixed bottom; không che legend |
| CTA | “Giữ ghế (n)” |
| List mode | Should: nút “Danh sách ghế” mở sheet |

### Tương tác

1. Tap seat → toggle select (max 8)
2. Giữ ghế → login modal nếu guest → POST holds
3. Success → countdown header; navigate C-HOLD hoặc CTA “Tiếp tục”
4. WS update màu ghế

### States

409, 503, hold expired modal — xem [states.md](../states.md).

### Gestures

- Pinch zoom chỉ canvas
- Edge swipe back: allowed (chưa hold) / confirm nếu đang hold ACTIVE

### Platform notes

- iOS: `touch-action: none` trên canvas tránh scroll page
- Android: overscroll không refresh whole page

### Acceptance

- [ ] Bottom nav ẩn
- [ ] 2 thiết bị thấy HELD other đúng màu
- [ ] Tray + CTA không bị home indicator che

---

## C-HOLD — Xác nhận giữ ghế (mobile)

| | |
| --- | --- |
| **Route** | `/checkout/hold/[holdId]` |
| **Auth** | Owner |
| **Milestone** | 03 |

### Layout

```text
┌──────────────────────────┐
│ ←  Giữ ghế               │
│     04:12 còn lại        │  countdown lớn, warn &lt;60s
├──────────────────────────┤
│ A-1-01  VIP  1.500.000₫  │
│ A-1-02  VIP  1.500.000₫  │
│                          │
│ Mã KM [_______] [Áp dụng]│
│ ─────────────────────    │
│ Tổng      3.000.000₫     │
├──────────────────────────┤
│ [ Tiếp tục thanh toán ]  │
│ [ Hủy giữ ghế         ]  │
└──────────────────────────┘
```

### Navigation

- Primary → C-PAY
- Hủy → DELETE hold → C-SEATS
- Hết giờ → modal → C-SEATS

### Acceptance

- [ ] Countdown readable ngoài trời
- [ ] Keyboard không che promo field (scroll into view)

---

## C-PAY — VietQR (mobile)

| | |
| --- | --- |
| **Route** | `/checkout/orders/[orderId]/pay` |
| **Auth** | Owner |
| **Milestone** | 03 |

### Layout

```text
┌──────────────────────────┐
│ Thanh toán · 14:32       │
├──────────────────────────┤
│ 3.000.000₫        [Copy] │
│ NTX9F2K1          [Copy] │
│ VCB · ****5678           │
│ CONG TY ABC              │
├──────────────────────────┤
│      ┌────────────┐      │
│      │  VietQR    │      │  ≥70% width
│      └────────────┘      │
│ Quét bằng app NH         │
├──────────────────────────┤
│ 1.Mở app NH 2.Quét 3.Đúng│
│ Đang chờ chuyển khoản…   │
│ [Tôi đã chuyển]          │
└──────────────────────────┘
```

Chi tiết: [payment-vietqr.md](../payment-vietqr.md).

### Navigation

- **Ẩn bottom nav**
- Back: confirm dialog nếu AWAITING
- PAID → flash → C-TICKET

### Platform notes

- Copy dùng `navigator.clipboard`; fallback select text iOS cũ
- User chuyển sang app NH: poll khi `visibilitychange` visible

### Acceptance

- [ ] QR quét được từ màn hình (độ sáng đủ)
- [ ] Copy reference 1 tap
- [ ] Resume từ background vẫn đếm đúng TTL

---

## C-ORDER — Chi tiết đơn (mobile)

| | |
| --- | --- |
| **Route** | `/checkout/orders/[orderId]` |
| **Auth** | Owner |
| **Milestone** | 03 |

### Layout

Card stack: status badge, thời gian, line items, tổng, CTA theo status.

| AWAITING + còn hạn | “Tiếp tục thanh toán” full width → C-PAY |
| PAID | “Xem vé” → C-TICKETS |
| EXPIRED | “Chọn ghế lại” → C-DETAIL |

Pull-to-refresh khi AWAITING.

### Acceptance

- [ ] Không tạo order mới khi resume pay

---

## C-ORDERS — Lịch sử đơn (mobile)

| | |
| --- | --- |
| **Route** | `/me/orders` |
| **Auth** | Required |
| **Milestone** | 03 |

### Layout

List 1 cột; badge status màu; tap → C-ORDER. Tab Vé có thể link “Xem đơn” Should.

Empty: “Chưa có đơn” + CTA events.

### Acceptance

- [ ] Login redirect giữ returnUrl

---

## C-TICKETS — Vé của tôi (mobile)

| | |
| --- | --- |
| **Route** | `/me/tickets` |
| **Auth** | Required |
| **Milestone** | 03 |

### Layout

```text
┌──────────────────────────┐
│ Vé của tôi               │
│ [Sắp tới|Đã dùng|Tất cả]  │  segment Should
├──────────────────────────┤
│ Event · 01/11 19:00      │
│ Ghế A-1-01 · Hợp lệ      │
└──────────────────────────┘
│ Bottom nav (tab Vé active)│
```

### Acceptance

- [ ] Tab Vé bottom nav active

---

## C-TICKET — QR vé (mobile)

| | |
| --- | --- |
| **Route** | `/me/tickets/[id]` |
| **Auth** | Owner |
| **Milestone** | 03 |

### Layout

```text
┌──────────────────────────┐
│ ←  Vé của bạn            │
│ Event title              │
│ 01/11 19:00 · A-1-01     │
│                          │
│    ┌──────────────┐      │
│    │     QR       │      │  max width safe
│    └──────────────┘      │
│ Trạng thái: Hợp lệ       │
│ 💡 Tăng độ sáng màn hình │
└──────────────────────────┘
```

### States

CHECKED_IN: stamp “Đã check-in HH:mm”. CANCELLED: không QR.

### Platform notes

- Gợi ý brightness không đổi system setting — chỉ copy hint
- Screen wake lock Should khi hiện QR

### Acceptance

- [ ] QR scannable tại cửa (test scanner web)

---

## C-LOGIN — Đăng nhập (mobile)

| | |
| --- | --- |
| **Route** | `/login?returnUrl=` |
| **Auth** | Public |
| **Milestone** | 01 |

Full screen brand + nút “Đăng nhập”. Redirect IdP; sau callback về `returnUrl`. Giữ selection hold trong `sessionStorage` ≤2 phút.

### Acceptance

- [ ] returnUrl sau hold flow hoạt động trên Safari/Chrome mobile

---

## C-ACCOUNT — Tài khoản (mobile)

| | |
| --- | --- |
| **Route** | `/account` |
| **Auth** | Required |
| **Milestone** | 01 |

Form: email read-only, tên, SĐT. Links Terms/Privacy. Đăng xuất. Tab Tài khoản active.

### Acceptance

- [ ] Logout → guest home
