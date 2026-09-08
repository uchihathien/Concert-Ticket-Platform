# Payment reconciliation runbook

## Alerts

- SePay webhook failure rate hoặc queue lag tăng.
- Transfer nhận được nhưng không match order.
- Payment đến sau order expiry.

## Procedure

1. Tra theo `sepay_transaction_id`, `payment_reference` và correlation ID; không tìm bằng dữ liệu thẻ/ngân hàng không mask.
2. Kiểm tra signature/auth result, duplicate state, receiver account snapshot, amount và order expiry.
3. Retry consumer chỉ khi transaction đã persist và lỗi sau đó là tạm thời.
4. Với late/invalid/mismatched payment, đặt `MANUAL_REVIEW`; không issue ticket bằng thao tác trực tiếp ở database.
5. Nhân viên tài chính quyết định refund hoặc resolve qua use case có audit log. Nếu resolve thành paid, hệ thống mới issue ticket qua transaction chuẩn.

## Exit criteria

Mỗi giao dịch SePay được classify `CONFIRMED`, `DUPLICATE`, `REJECTED`, `MANUAL_REVIEW` hoặc `REFUNDED`; không có transaction vô chủ.
