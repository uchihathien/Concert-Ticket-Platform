# Mobile screen inventory

**Spec chi tiết từng màn:** [screens/README.md](screens/README.md)

## 1. Mobile web — Customer

| ID | Route | Milestone | Spec |
| --- | --- | --- | --- |
| C-HOME | `/` | 02 | [customer-web](screens/customer-web.md#c-home--trang-chủ-mobile) |
| C-LIST | `/events` | 02 | [customer-web](screens/customer-web.md#c-list--danh-sách-sự-kiện-mobile) |
| C-DETAIL | `/events/[slug]` | 02 | [customer-web](screens/customer-web.md#c-detail--chi-tiết-sự-kiện-mobile) |
| C-SEATS | `.../seats` | 03 | [customer-web](screens/customer-web.md#c-seats--sơ-đồ-ghế-mobile) |
| C-HOLD | `/checkout/hold/[id]` | 03 | [customer-web](screens/customer-web.md#c-hold--xác-nhận-giữ-ghế-mobile) |
| C-PAY | `.../pay` | 03 | [customer-web](screens/customer-web.md#c-pay--vietqr-mobile) |
| C-ORDER | `/checkout/orders/[id]` | 03 | [customer-web](screens/customer-web.md#c-order--chi-tiết-đơn-mobile) |
| C-ORDERS | `/me/orders` | 03 | [customer-web](screens/customer-web.md#c-orders--lịch-sử-đơn-mobile) |
| C-TICKETS | `/me/tickets` | 03 | [customer-web](screens/customer-web.md#c-tickets--vé-của-tôi-mobile) |
| C-TICKET | `/me/tickets/[id]` | 03 | [customer-web](screens/customer-web.md#c-ticket--qr-vé-mobile) |
| C-LOGIN | `/login` | 01 | [customer-web](screens/customer-web.md#c-login--đăng-nhập-mobile) |
| C-ACCOUNT | `/account` | 01 | [customer-web](screens/customer-web.md#c-account--tài-khoản-mobile) |

Web desktop spec: [../screens/customer.md](../screens/customer.md).

## 2. Mobile web — Scanner

| ID | Route | Milestone | Spec |
| --- | --- | --- | --- |
| S-LOGIN | `/login` | 04 | [scanner-web](screens/scanner-web.md#s-login--đăng-nhập-scanner-mobile) |
| S-SESSION | `/` | 04 | [scanner-web](screens/scanner-web.md#s-session--chọn-sự-kiện--suất) |
| S-SCAN | `/scan` | 04 | [scanner-web](screens/scanner-web.md#s-scan--camera-quét-qr) |
| S-RESULT | overlay | 04 | [scanner-web](screens/scanner-web.md#s-result--overlay-kết-quả-mobile) |

## 3. React Native — Customer v1

| ID | Screen | Milestone | Spec |
| --- | --- | --- | --- |
| M-WELCOME | `Welcome` | RN v1 | [customer-rn](screens/customer-rn.md#m-welcome--chào-mừng) |
| M-LOGIN | `Login` | RN v1 | [customer-rn](screens/customer-rn.md#m-login--đăng-nhập) |
| M-HOME | `Home` | RN v1 | [customer-rn](screens/customer-rn.md#m-home--khám-phá-tab-root) |
| M-LIST | `EventList` | RN v1 | [customer-rn](screens/customer-rn.md#m-list--danh-sách-sự-kiện) |
| M-DETAIL | `EventDetail` | RN v1 | [customer-rn](screens/customer-rn.md#m-detail--chi-tiết-sự-kiện) |
| M-SEATS | `SeatMap` | RN v1 | [customer-rn](screens/customer-rn.md#m-seats--sơ-đồ-ghế) |
| M-HOLD | `HoldSummary` | RN v1 | [customer-rn](screens/customer-rn.md#m-hold--tóm-tắt-giữ-ghế) |
| M-PAY | `PaymentQr` | RN v1 | [customer-rn](screens/customer-rn.md#m-pay--thanh-toán-vietqr) |
| M-PAY-OK | `PaymentSuccess` | RN v1 | [customer-rn](screens/customer-rn.md#m-pay-ok--thanh-toán-thành-công) |
| M-ORDERS | `OrderList` | RN v1 | [customer-rn](screens/customer-rn.md#m-orders--lịch-sử-đơn) |
| M-ORDER | `OrderDetail` | RN v1 | [customer-rn](screens/customer-rn.md#m-order--chi-tiết-đơn) |
| M-TICKETS | `TicketList` | RN v1 | [customer-rn](screens/customer-rn.md#m-tickets--danh-sách-vé) |
| M-TICKET | `TicketDetail` | RN v1 | [customer-rn](screens/customer-rn.md#m-ticket--chi-tiết-vé--qr) |
| M-ACCOUNT | `Account` | RN v1 | [customer-rn](screens/customer-rn.md#m-account--tài-khoản) |
| M-NET | `OfflineBanner` | RN v1 | [customer-rn](screens/customer-rn.md#m-net--offline-banner-global) |
| M-FORCE | `ForceUpdate` | RN v1 | [customer-rn](screens/customer-rn.md#m-force--bắt-buộc-cập-nhật) |

## 4. Out of scope mobile

| Screen | Lý do |
| --- | --- |
| Admin mobile | Web admin desktop/tablet only |
| Native scanner app | Scanner web trước |
| Favorites / map | Post v1 |

## 5. Shared RN components

| Component | Dùng ở |
| --- | --- |
| `CountdownTimer` | Hold, Pay |
| `SeatCanvas` | SeatMap |
| `SelectionTray` | SeatMap |
| `VietQrCard` | PaymentQr |
| `CopyableField` | PaymentQr |
| `TicketQr` | TicketDetail |
| `StatusBadge` | Orders, Tickets |
| `EmptyState` | Lists |
| `ErrorView` | Retry screens |
| `OrgSafeArea` | All screens |
