# Payment reconciliation runbook

## Alerts

- Tỷ lệ webhook payOS thất bại hoặc queue lag tăng.
- Log `CHỮ KÝ WEBHOOK payOS KHÔNG KHỚP` ở mức ERROR: hoặc có người giả webhook, hoặc
  `PAYOS_CHECKSUM_KEY` đang lệch và **mọi** khoản tiền vào đang bị từ chối (endpoint trả 401,
  payOS vẫn giao lại). Kiểm khoá trước khi đi tìm chỗ khác.
- Transfer nhận được nhưng không match order.
- Payment đến sau order expiry.

## Procedure

1. Tra `bank_webhook_log` theo `payos_order_code`, `provider_txn_id` (= `data.reference` của payOS),
   `reference` (`NT` + 7 chữ số) hoặc correlation ID; không tìm bằng dữ liệu ngân hàng không mask.
2. Webhook không tới (thường gặp nhất: URL webhook chưa đăng ký, hoặc payOS hết lượt retry) —
   **kéo** trạng thái từ payOS về thay vì sửa SQL tay:
   `POST /internal/payment-intents/{orderId}/reconcile`.
   An toàn gọi lại nhiều lần: nó đi qua đúng `ConfirmTransferHandler` như webhook thật, nên không
   ghi nhận tiền theo luật khác. Gọi hai lần chỉ trả `DUPLICATE`.
2. Kiểm tra signature/auth result, duplicate state, receiver account snapshot, amount và order expiry.
3. Retry consumer chỉ khi transaction đã persist và lỗi sau đó là tạm thời.
4. Với late/invalid/mismatched payment, đặt `MANUAL_REVIEW`; không issue ticket bằng thao tác trực tiếp ở database.
5. Nhân viên tài chính quyết định refund hoặc resolve qua use case có audit log. Nếu resolve thành paid, hệ thống mới issue ticket qua transaction chuẩn.

## Exit criteria

Mỗi giao dịch payOS được classify `CONFIRMED`, `DUPLICATE`, `UNKNOWN_REFERENCE`, `AMOUNT_MISMATCH`,
`NOT_PENDING` hoặc `REJECTED` trong `bank_webhook_log`; không có transaction vô chủ.

Ghi chú: `UNKNOWN_REFERENCE` với `payos_order_code = 123` là **webhook thử của payOS lúc đăng ký URL**,
không phải sự cố.
