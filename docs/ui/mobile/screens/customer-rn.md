# React Native — Customer (`apps/mobile`)

Dark theme theo [design-native.md](../design-native.md). Portrait lock checkout + ticket QR. Tab bar ẩn trong CheckoutModal.

---

## M-WELCOME — Chào mừng

| | |
| --- | --- |
| **Screen** | `Welcome` |
| **Stack** | AuthStack (first launch / logged out cold start) |
| **Auth** | Public |
| **Milestone** | RN v1 |

### Mục đích

Brand entry trước login; cho phép guest browse.

### Layout

```text
┌──────────────────────────┐
│                          │
│      NexaTicket          │  display, centered
│   Chọn ghế. Thanh toán.  │
│                          │
│   [    Đăng nhập    ]    │
│   [  Tiếp tục xem    ]    │  ghost → MainTabs guest
│                          │
└──────────────────────────┘
```

### Tương tác

- Đăng nhập → M-LOGIN
- Tiếp tục xem → MainTabs Discover (guest)

### States

Đã có valid refresh token → skip Welcome → MainTabs.

### Acceptance

- [ ] Không bắt login để vào EventList

---

## M-LOGIN — Đăng nhập

| | |
| --- | --- |
| **Screen** | `Login` |
| **Stack** | AuthStack |
| **Auth** | Public |
| **Milestone** | RN v1 |

### Layout

Brand nhỏ + “Đăng nhập an toàn với NexaTicket” + CTA primary.

### Tương tác

1. Tap → `expo-auth-session` / ASWebAuthSession
2. PKCE → Keycloak
3. Store tokens SecureStore
4. Upsert `/me` → pop AuthStack → return route hoặc MainTabs

### States

| Fail | “Không đăng nhập được. Thử lại.” |
| Cancel | Quay Welcome |

### Platform

- iOS: SFAuthenticationSession
- Android: Chrome Custom Tabs

### Acceptance

- [ ] Token không trong AsyncStorage plain

---

## M-HOME — Khám phá (tab root)

| | |
| --- | --- |
| **Screen** | `Home` |
| **Stack** | DiscoverStack / Tab Khám phá |
| **Auth** | Guest + logged-in |
| **Milestone** | RN v1 |

### Layout

```text
┌──────────────────────────┐
│ NexaTicket        🔍     │  search → M-LIST filtered
├──────────────────────────┤
│ [brand strip / tagline]  │
│ Sắp diễn ra →            │
│ ┌────┐ ┌────┐ ┌────┐     │  horizontal FlatList
│ │ev1 │ │ev2 │ │ev3 │     │
│ └────┘ └────┘ └────┘     │
│ [ Xem tất cả sự kiện ]   │
├──────────────────────────┤
│ Khám phá │ Vé │ Tài khoản│
└──────────────────────────┘
```

### Thành phần

| Search icon | Navigate M-LIST với focus keyboard |
| Rail | 3–6 events; card width ~72% screen |
| CTA | M-LIST full |

### API

`GET /events?upcoming&limit=6`

### Acceptance

- [ ] Rail scroll ngang; không auto-play carousel video

---

## M-LIST — Danh sách sự kiện

| | |
| --- | --- |
| **Screen** | `EventList` |
| **Stack** | DiscoverStack |
| **Milestone** | RN v1 |

### Layout

Search bar sticky; filter icon → bottom sheet (Modal); FlatList infinite.

### Filter sheet

City, date range, Áp dụng / Xóa.

### Tương tác

- Tap → M-DETAIL
- Pull-to-refresh
- onEndReached load page

### States

Empty, loading skeleton, error + retry.

### Acceptance

- [ ] Debounce search 300ms

---

## M-DETAIL — Chi tiết sự kiện

| | |
| --- | --- |
| **Screen** | `EventDetail` |
| **Params** | `slug` |
| **Milestone** | RN v1 |

### Layout

ScrollView + sticky footer CTA (absolute bottom safe area).

| Hero | Image header parallax nhẹ Should — không nặng |
| Sessions | Horizontal ScrollView chips |
| Tiers | List giá |
| CTA | “Chọn ghế” → M-SEATS |

### Share

Share API native: link `https://nexaticket.vn/events/{slug}`

### Acceptance

- [ ] Footer CTA luôn visible khi scroll xong

---

## M-SEATS — Sơ đồ ghế

| | |
| --- | --- |
| **Screen** | `SeatMap` |
| **Params** | `sessionId`, `slug` |
| **Milestone** | RN v1 |

### Mục đích

Realtime seat selection + hold. **Hide tab bar.**

### Layout

Giống C-SEATS mobile web; implement `SeatCanvas` + `SelectionTray`.

### Components

| SeatCanvas | Skia/SVG; pan pinch |
| SelectionTray | Bottom sheet snap 15% / 40% |
| CountdownTimer | Header khi hold active |
| SeatLegend | Collapsible |

### Tương tác

1. Select seats → Giữ ghế
2. Guest → M-LOGIN → return với selection restore
3. Hold OK → present CheckoutModal → M-HOLD

### WS

Connect on focus; disconnect on blur; version gap refetch.

### Haptics

Light impact on select (iOS).

### Acceptance

- [ ] Tab bar hidden
- [ ] Pinch không crash trên 500 seats
- [ ] 409 rollback selection

---

## M-HOLD — Tóm tắt giữ ghế

| | |
| --- | --- |
| **Screen** | `HoldSummary` |
| **Stack** | CheckoutModalStack |
| **Milestone** | RN v1 |

### Layout

Countdown prominent; FlatList line items; promo TextInput; tổng; 2 CTA.

### Navigation

- Tiếp tục → M-PAY
- Hủy → dismiss modal + release hold API

### Keyboard

`KeyboardAvoidingView` iOS padding / Android height.

### Acceptance

- [ ] Promo invalid inline error

---

## M-PAY — Thanh toán VietQR

| | |
| --- | --- |
| **Screen** | `PaymentQr` |
| **Stack** | CheckoutModalStack |
| **Milestone** | RN v1 |

### Layout

`VietQrCard` centered; `CopyableField` x2; bank info; status; poll button.

### Tương tác

- Poll interval 5–8s when AppState active
- AppState background → pause poll; foreground → immediate poll
- Back gesture disabled; hardware back Android → confirm dialog
- PAID → M-PAY-OK

### RN extras

- Save QR to photos (Should) + permission
- Không embed WebView ngân hàng

Chi tiết: [payment-vietqr.md](../payment-vietqr.md).

### Acceptance

- [ ] Confirm dialog on back
- [ ] Copy toast “Đã sao chép”

---

## M-PAY-OK — Thanh toán thành công

| | |
| --- | --- |
| **Screen** | `PaymentSuccess` |
| **Stack** | CheckoutModalStack |
| **Milestone** | RN v1 |

### Layout

```text
┌──────────────────────────┐
│         ✓                │
│   Thanh toán thành công    │
│   Vé đã sẵn sàng           │
│   [      Xem vé      ]     │
│   [  Về trang chủ   ]      │
└──────────────────────────┘
```

Auto navigate M-TICKET sau 3s Should (có cancel tap).

### Acceptance

- [ ] Dismiss checkout modal sau xem vé

---

## M-ORDERS — Lịch sử đơn

| | |
| --- | --- |
| **Screen** | `OrderList` |
| **Stack** | TicketsStack (segment) hoặc AccountStack link |
| **Milestone** | RN v1 |

Segment trên tab **Vé**: [Vé | Đơn hàng].

Row: event, date, total, StatusBadge.

Tap → M-ORDER.

### Acceptance

- [ ] AWAITING orders show countdown badge

---

## M-ORDER — Chi tiết đơn

| | |
| --- | --- |
| **Screen** | `OrderDetail` |
| **Params** | `orderId` |
| **Milestone** | RN v1 |

Resume pay CTA → M-PAY (same order). Pull-to-refresh poll status.

Deep link: `/checkout/orders/{id}`.

### Acceptance

- [ ] Deep link cold start opens this screen

---

## M-TICKETS — Danh sách vé

| | |
| --- | --- |
| **Screen** | `TicketList` |
| **Tab** | Vé |
| **Milestone** | RN v1 |

Segment Sắp tới / Đã dùng. Empty → CTA M-LIST.

Login required — redirect M-LOGIN.

### Acceptance

- [ ] Tab Vé badge count optional (số vé sắp tới)

---

## M-TICKET — Chi tiết vé + QR

| | |
| --- | --- |
| **Screen** | `TicketDetail` |
| **Params** | `ticketId` |
| **Milestone** | RN v1 |

### Layout

`TicketQr` component lớn; event meta; status; hint brightness.

### RN extras

- `FLAG_SECURE` Android Should (chống screenshot casual)
- Keep awake Should khi screen focused

Deep link email ticket.

### Acceptance

- [ ] CHECKED_IN stamp visible
- [ ] Scanner web reads QR

---

## M-ACCOUNT — Tài khoản

| | |
| --- | --- |
| **Screen** | `Account` |
| **Tab** | Tài khoản |
| **Milestone** | RN v1 |

Profile fields; link Orders; Terms/Privacy (in-app browser); Logout.

Logout → clear SecureStore → Welcome.

### Acceptance

- [ ] Logout revokes session client-side

---

## M-NET — Offline banner (global)

| | |
| --- | --- |
| **Component** | `OfflineBanner` |
| **Not a screen** | — |

Banner top khi NetInfo offline. Disable hold/pay/check-in actions.

### Acceptance

- [ ] Reconnect auto dismiss + refetch active screen

---

## M-FORCE — Bắt buộc cập nhật

| | |
| --- | --- |
| **Screen** | `ForceUpdate` |
| **Milestone** | RN v1 optional |

Full screen khi API/config `minAppVersion` &gt; current. CTA mở Store.

No skip button.

### Acceptance

- [ ] Blocks MainTabs when triggered
