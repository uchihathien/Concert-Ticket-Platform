# SePay bank-transfer webhook contract

## Endpoint

`POST /api/billing/bank/webhook/sepay`

Endpoint chỉ dành cho SePay, không dùng browser session hoặc customer authentication. Adapter phải xác thực request theo credential/signature/IP policy được cấu hình từ SePay; secret không commit vào repository.

## Processing contract

1. Lưu/kiểm tra idempotency bằng `sepay_transaction_id` và raw payload hash.
2. Parse payment reference từ transaction content theo format đã phát hành trong QR.
3. Tìm payment attempt/order đang `AWAITING_PAYMENT` có reference đó.
4. Xác minh receiver bank account snapshot, currency VND và `received_amount >= expected_amount` theo chính sách hiện hành.
5. Trong một database transaction: đánh dấu attempt `CONFIRMED`, order `PAID`, phát hành ticket và ghi outbox events.

Không thỏa một điều kiện thì không phát hành ticket. Ghi payment attempt `REJECTED`, `DUPLICATE` hoặc `MANUAL_REVIEW` cùng reason code để đối soát.

## Responses

- `2xx`: webhook đã được nhận, bao gồm duplicate đã biết.
- `4xx`: payload/authentication không hợp lệ; không retry nội bộ.
- `5xx`: lỗi tạm thời; SePay có thể retry theo cấu hình provider.

## Security and audit

Không log account number, webhook secret hoặc payload PII đầy đủ. Log correlation ID, provider transaction ID đã mask và result code. Retain raw payload/hash theo chính sách tài chính và privacy.
