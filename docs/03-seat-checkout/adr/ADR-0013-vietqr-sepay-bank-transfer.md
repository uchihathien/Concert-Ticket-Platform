# ADR-0013: VietQR và SePay cho bank transfer

**Status:** Superseded by [ADR-0016](./ADR-0016-payos-payment-gateway.md)

> **Không triển khai theo ADR này nữa.** Luồng thu tiền đã chuyển sang payOS. Giữ lại làm hồ sơ quyết
> định: phần *Consequences* bên dưới mô tả chính những chi phí đã dẫn tới việc thay nhà cung cấp —
> đáng nhất là "khách gõ sai nội dung chuyển khoản thì tiền mồ côi và phải đối soát tay".
>
> Hợp đồng đang có hiệu lực: [api/payos-webhook.md](../api/payos-webhook.md).

## Context

MVP ở Việt Nam dùng chuyển khoản ngân hàng, không dùng Stripe hay virtual account. Cần xác nhận payment tự động mà không hard-code ngân hàng nhận tiền.

## Decision

- Tạo QR bằng VietQR từ một `bank_accounts` active, được Admin cấu hình động.
- Order sinh `payment_reference` duy nhất; reference là khóa đối soát chính, không dùng số tiền lẻ.
- SePay gọi `POST /api/billing/bank/webhook/sepay`; adapter xác thực request theo cơ chế SePay đã cấu hình, lưu payload hash và deduplicate bằng `sepay_transaction_id`.
- Chỉ khi reference, expected amount, receiver account và order state hợp lệ thì transaction chuyển order sang `PAID`, issue tickets và ghi outbox event.

## Data ownership

`Payments` sở hữu `bank_accounts` và `payment_attempts`. `bank_accounts` gồm bank code, account number được mã hóa, account name, active/effective period và audit fields. `payment_attempts` gồm expected/received amount, reference, SePay transaction ID, status, payload hash, received time.

## Consequences

Order hết hạn không được auto-paid nếu webhook đến muộn: chuyển `MANUAL_REVIEW` hoặc refund theo runbook. Thay đổi bank account không ảnh hưởng QR/payment instruction đã snapshot vào order cũ. SePay nằm sau `PaymentProvider` interface.

## Validation

Integration tests cover duplicate webhook, wrong reference, wrong amount, late payment, inactive receiver account và concurrent delivery. Admin bank-account changes phải có audit record.
