# Mobile navigation

## A. Mobile web (`web-customer`, &lt;768px)

### Bottom tabs (hiện)

| Tab | Route | Icon ý |
| --- | --- | --- |
| Khám phá | `/` hoặc `/events` | search/compass |
| Vé | `/me/tickets` | ticket |
| Tài khoản | `/account` | user |

### Ẩn bottom tabs (focus mode)

- `/events/.../seats`
- `/checkout/**`
- Full-screen error/success thanh toán ngắn

### Stack hành vi

- List → Detail: push; back hệ thống / nút back top.
- Detail → Seats: push.
- Hold → Pay: replace stack checkout (không back về hold đã convert nếu order đã tạo — back về Detail/Event).

## B. React Native customer

### Root

```text
AuthGate
├── AuthStack          (chưa login: Welcome, Login)
└── MainTabs           (đã login hoặc guest browse)
    ├── DiscoverStack
    ├── TicketsStack
    └── AccountStack
CheckoutModalStack     (overlay từ SeatMap — hide tabs)
```

### DiscoverStack

```text
Home → EventList → EventDetail → SeatMap → (present) CheckoutModal
```

### CheckoutModalStack

```text
HoldSummary → PaymentQr → PaymentSuccess → TicketDetail
```

Dismiss CheckoutModal khi: hết hold chưa order; user hủy; hoặc sau khi vào TicketDetail (optional keep ticket in Tickets tab).

### TicketsStack

```text
TicketList → TicketDetail
OrderList → OrderDetail   (có thể là segment trên cùng tab Vé)
```

### Guest vs logged-in

| Hành động | Guest | Logged-in |
| --- | --- | --- |
| Browse list/detail | Yes | Yes |
| Mở SeatMap | Yes | Yes |
| POST hold | Prompt login (return to SeatMap với selection giữ local nếu còn) | Yes |
| Xem vé | Login required | Yes |

## C. Scanner mobile web

Không bottom tab marketing. Single stack:

```text
Login → SessionPick → ScanCamera
```

Chi tiết: [scanner-mobile.md](scanner-mobile.md).

## D. Gesture rules

| Gesture | Hành vi |
| --- | --- |
| Edge swipe back | Disabled trên PaymentQr và ScanCamera (tránh mất QR / đóng nhầm) |
| Pull-to-refresh | EventList, TicketList, OrderDetail (poll status) |
| Pinch zoom | SeatMap only |
