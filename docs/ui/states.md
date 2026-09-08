# UI states (toàn cục)

Mọi màn Must cover các state dưới đây khi áp dụng.

## 1. Async

| State | UI |
| --- | --- |
| Loading lần đầu | Skeleton cùng layout (list/detail/seats); không spinner full-page trừ auth |
| Refresh nền | Indicator nhỏ; giữ data cũ (stale-while-revalidate) |
| Mutation pending | Disable CTA chính; giữ selection |

## 2. Empty

| Màn | Empty copy |
| --- | --- |
| C-LIST | “Chưa có sự kiện phù hợp” + xóa bộ lọc |
| C-TICKETS | “Bạn chưa có vé” + CTA khám phá |
| A-EVENT-LIST | “Tạo sự kiện đầu tiên” |
| A-REVIEW | “Không có giao dịch cần xử lý” |
| S-HOME chưa chọn session | “Chọn suất diễn để bắt đầu quét” |

## 3. Error

| Loại | UI |
| --- | --- |
| 401 | Redirect login, giữ `returnUrl` |
| 403 | “Bạn không có quyền” |
| 404 | “Không tìm thấy” + về list |
| 409 business | Toast/banner với mã lỗi SRS (SEAT_UNAVAILABLE, HOLD_EXPIRED, …) |
| 503 REDIS_UNAVAILABLE | “Hệ thống đang bận, thử lại” + retry |
| Network offline | Banner cố định “Mất kết nối” — scanner **dừng** quét |

## 4. Countdown

### Hold (5 phút) — C-SEATS, C-HOLD

- Hiển thị `mm:ss` từ `expiresAt` server; sync lại mỗi focus/visibility.
- &lt; 60s: `--nt-warn`.
- = 0: modal “Hết thời gian giữ ghế” → ghế release → về C-SEATS.
- Gia hạn: **không** có nút gia hạn MVP; user chọn lại.

### Payment (15 phút) — C-PAY

- Countdown từ `paymentExpiresAt`.
- &lt; 2 phút: warn.
- Hết hạn + chưa PAID: state `EXPIRED` — copy “Đơn hết hạn, ghế đã mở bán lại” + CTA về event.
- Không cho “gia hạn thanh toán” tự phục vụ MVP.

## 5. Payment status (C-PAY)

| Status | UI |
| --- | --- |
| AWAITING_PAYMENT | QR + hướng dẫn; poll `GET order` mỗi 5–8s hoặc nút “Tôi đã chuyển” |
| PAID | Success → auto navigate C-TICKET / C-TICKETS |
| MANUAL_REVIEW | “Đang đối soát thủ công” — không hiện vé |
| EXPIRED / CANCELLED | Như trên |
| REFUNDED | Badge hoàn tiền |

## 6. Seat map sync

- Badge `version` ẩn hoặc debug only.
- WS disconnect: banner “Đang cập nhật chậm…” + refetch interval 10s.
- Optimistic select: rollback nếu API 409.

## 7. Check-in flash (S-RESULT)

| Result | Màu / copy |
| --- | --- |
| CHECKED_IN | Success xanh lớn + seat label |
| ALREADY_CHECKED_IN | Warn + thời điểm check-in trước |
| INVALID_* | Danger + lý do ngắn |

Tự dismiss overlay sau 2–3s hoặc tap để quét tiếp.
