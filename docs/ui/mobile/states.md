# Mobile UI states

Kế thừa [../states.md](../states.md); bổ sung đặc thù điện thoại.

## Network

| State | UI |
| --- | --- |
| Offline | Banner top đỏ/cam “Mất kết nối”; disable hold/pay/check-in submit |
| Flaky | Retry button trên ErrorView; không infinite spinner |
| Resume online | Auto refetch seats/order nếu đang ở màn đó |

## App lifecycle (RN)

| Event | Behavior |
| --- | --- |
| Background | Disconnect WS optional; schedule reconnect on foreground |
| Foreground | Revalidate hold/order expiry; refresh seats version; refresh token nếu gần hết |
| Kill & reopen | Cold start → restore auth; deep link nếu có; không restore optimistic seat selection cũ &gt; 2p |

## Countdown

- Luôn derive từ server timestamp + device now; resync mỗi `visibilitychange` / `AppState=active`.
- Không dùng `setInterval` chỉ đếm local không chỉnh khi clock skew lớn — nếu skew &gt; 30s, show “Đồng bộ thời gian…” và refetch `expiresAt`.

## Push (out v1)

Không có push status thanh toán trong RN v1 — dựa email + poll khi mở app.

## Permission denials

| Permission | Màn | Fallback |
| --- | --- | --- |
| Camera (scanner web) | S-SCAN | Input dán token thủ công |
| Photos (save QR) | PaymentQr | Ẩn nút Lưu |
| Notifications | — | N/A v1 |

## Empty / error copy (vi)

Giống web; thêm nền tảng: “Thử lại trên Wi‑Fi nếu mạng yếu khi giữ ghế.”
