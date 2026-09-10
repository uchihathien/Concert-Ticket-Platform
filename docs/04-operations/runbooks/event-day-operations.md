# Event-day operations

## Trước giờ mở cửa (−2h)

1. Health check API, WS, DB, Redis; xác nhận payOS không trong maintenance và URL webhook còn đăng ký.
2. Xác nhận scanner có mạng và login role `CHECKIN_STAFF`.
3. Smoke: quét 1 vé test `VALID` → `CHECKED_IN`; vé test khác rescan → `ALREADY_CHECKED_IN`.
4. Dashboard mở: check-in rate, rejection spike alert.

## Trong giờ

1. Theo dõi rejection rate và API latency.
2. Scan hợp lệ → `CHECKED_IN`; rescan → `ALREADY_CHECKED_IN` (không entry lần hai trừ quy trình ngoại lệ có audit — post-MVP).
3. API/scanner offline → **dừng** check-in tự động; MVP không offline reconciliation; chuyển thủ công giấy theo policy organizer.
4. Nghi QR giả / token invalid / spike → escalate on-call; giữ correlation id.

## Sau sự kiện

1. Export số check-in vs sold.
2. Đóng MANUAL_REVIEW còn mở.
3. Retro 30 phút: ghi action items vào PR docs nếu cần sửa runbook.
