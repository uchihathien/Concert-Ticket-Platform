# ADR-0015: Hold TTL và payment reservation window

**Status:** Accepted

## Context

Khách cần vài phút để chọn ghế (hold ngắn), nhưng chuyển khoản VietQR thường cần cửa sổ dài hơn. Nếu giữ hold Redis 5 phút xuyên suốt đến lúc chuyển khoản xong, tỷ lệ hết hạn cao và trải nghiệm kém. Nếu giữ hold Redis dài, inventory bị khóa quá mức.

## Decision

Hai giai đoạn rõ:

| Giai đoạn | State ghế | TTL | Cơ chế |
| --- | --- | --- | --- |
| Chọn ghế | `HELD` | **5 phút** | Redis key + `seat_holds.expires_at` |
| Chờ thanh toán | `RESERVED` | **15 phút** từ `orders.payment_expires_at` | DB reservation; Redis hold key xóa/đánh dấu converted |

Khi `POST /v1/orders` thành công:

1. Revalidate hold còn ACTIVE và thuộc user.
2. Transaction: tạo order `AWAITING_PAYMENT`, set `payment_expires_at = now + 15m`, chuyển `session_seats` → `RESERVED`, seat_holds → `CONVERTED`.
3. Sinh VietQR + `payment_reference`.
4. Expiry worker: hết `payment_expires_at` mà chưa PAID → order `EXPIRED`, seats `AVAILABLE`, emit availability changed.

Late SePay webhook sau EXPIRED: không auto-PAID; `MANUAL_REVIEW` theo ADR-0013.

## Consequences

- Countdown UI: hold countdown rồi payment countdown riêng.
- Load test phải cover cả hai cửa sổ và race hết hạn vs webhook.
- Có thể cấu hình org-level sau MVP; MVP hard-code 5m / 15m (constants + config override chỉ ops).

## Validation

Integration: hold expire trước order; order expire trước webhook; webhook đúng hạn → ticket; webhook muộn → MANUAL_REVIEW.
