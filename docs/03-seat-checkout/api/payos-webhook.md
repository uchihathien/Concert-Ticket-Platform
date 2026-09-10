# payOS payment webhook contract

Thay cho `sepay-webhook.md` (ADR-0016). Xem [ADR-0016](../adr/ADR-0016-payos-payment-gateway.md) cho lý do chuyển.

## Endpoint

```
POST /api/billing/bank/webhook/payos
```

Công khai — `SecurityAutoConfiguration` mở sẵn `/api/billing/bank/webhook/**` vì payOS không có JWT của ta. Nghĩa là **toàn bộ** trách nhiệm xác thực nằm ở chính endpoint này, và lớp duy nhất là chữ ký HMAC-SHA256.

Phải đăng ký URL với payOS **một lần cho mỗi môi trường** trước khi có đơn thật:

```
POST /internal/payos/confirm-webhook      # dùng nexaticket.payment.payos.webhook-url
POST /internal/payos/confirm-webhook      # hoặc body {"webhookUrl": "https://..."} để trỏ tạm
```

payOS đòi **HTTPS** và phải **gọi được từ internet**, nên `localhost` không dùng được. Lời gọi này cũng là một phép thử đầu-cuối: payOS gọi thử endpoint ngay trong đó với `orderCode` giả (`123`) và chữ ký thật, nên nó chỉ thành công khi URL gọi được, là HTTPS, và checksum key khớp kênh của họ. Thất bại ở đây là tin tốt — nó nổ trước khoản tiền đầu tiên.

## Payload

```json
{
  "code": "00",
  "desc": "success",
  "success": true,
  "data": {
    "orderCode": 1000001,
    "amount": 3000000,
    "description": "NT1000001",
    "accountNumber": "V3CAS0123456789",
    "reference": "TF230204212323",
    "transactionDateTime": "2026-09-09 10:25:00",
    "currency": "VND",
    "paymentLinkId": "124c33293c43417ab7879e14c8d9eb18",
    "code": "00",
    "desc": "Thành công",
    "counterAccountBankId": null,
    "counterAccountBankName": null,
    "counterAccountName": null,
    "counterAccountNumber": null,
    "virtualAccountName": null,
    "virtualAccountNumber": null
  },
  "signature": "a249d3f2…"
}
```

- `data.orderCode` — **khoá đối soát**. Do ta cấp lúc tạo link, từ `payment_order_code_seq`.
- `data.reference` — mã giao dịch ngân hàng. Thành **khoá chống ghi nhận trùng** (`uq_provider_txn`). Không có thì dùng `paymentLinkId` — nhưng chỉ khi *thật sự* không có: dùng `paymentLinkId` khi đã có `reference` sẽ khiến lần chuyển tiền thứ hai vào cùng link bị coi là trùng lặp.
- `data.amount` — số tiền **đã trả**, không phải số tiền của đơn. Đối chiếu ở domain.

## Kiểm chữ ký

HMAC-SHA256 trên **trường `data`**, không trên cả body — ký cả body thì `signature` phải ký chính nó.

1. Sắp key của `data` theo **alphabet** (không theo thứ tự JSON gửi về).
2. Nối `key1=value1&key2=value2…`.
3. `null` → chuỗi rỗng. Cả chuỗi `"null"`/`"undefined"` cũng quy về rỗng — bản tham chiếu của payOS làm vậy.
4. Số in dạng thường, không phần thập phân thừa: `3000.00` → `3000` (bản tham chiếu viết bằng JavaScript).
5. Mảng → JSON hoá, key từng phần tử đã sắp, **giữ nguyên** thứ tự phần tử.
6. **Không** URL-encode (khác luồng payout của payOS).
7. `HMAC_SHA256(canonical, checksumKey)`, hex thường. So bằng **thời gian hằng số**.

Bản triển khai: `PayosSignature`. Vector vàng: `PayosSignatureTest`.

Thiếu checksum key trong cấu hình ⇒ **từ chối mọi webhook**. Không có chế độ "bỏ qua kiểm".

## Processing contract

1. Kiểm chữ ký. Không qua ⇒ ghi `bank_webhook_log` với `outcome = REJECTED` rồi trả **401**.
2. Chỉ đọc các trường **sau khi** chữ ký khớp. Trước đó chúng là dữ liệu từ internet.
3. `success`/`code` phải đúng ở **cả hai** chỗ: envelope và `data.code`. Chỉ trường trong `data` thuộc phạm vi chữ ký, nên envelope một mình không đủ tin.
4. Tra intent theo `data.orderCode`.
5. Chặn mã giao dịch đã ghi nhận cho **đơn khác** — kiểm *trước* khi ghi, không bắt lỗi trùng khoá sau: một ràng buộc vi phạm làm hỏng cả transaction PostgreSQL và cuốn theo dòng nhật ký cần ghi.
6. Đối chiếu số tiền ở domain: `paidAmount >= amount` mới xác nhận. Trả **thiếu** thì không phát vé; trả **thừa** vẫn ghi nhận và hoàn phần dư ngoài hệ thống.
7. `UPDATE … WHERE status = 'PENDING'` — chặn job quét hết hạn ghi đè một xác nhận vừa tới.
8. Báo sang Ordering **trong cùng transaction**. Gọi hỏng ⇒ ném ⇒ rollback ⇒ 5xx ⇒ payOS giao lại.
9. Ordering trả về **kết quả**, không phải 204 rỗng. `MANUAL_REVIEW` nghĩa là tiền vào một đơn đã
   đóng: intent vẫn CONFIRMED (tiền có thật), nhật ký vẫn `CONFIRMED` nhưng kèm `note`, và ta trả
   **200**. Đây là nhánh duy nhất trong cả luồng cần một con người xử lý — thường là hoàn tiền.

## Responses

| Mã | Khi nào | Vì sao |
|---|---|---|
| **200** | Đã xử lý xong, **kể cả khi từ chối** — `UNKNOWN_REFERENCE`, `AMOUNT_MISMATCH`, `DUPLICATE`, `NOT_PENDING` | Những lý do này không khác đi ở lần giao lại thứ hai mươi. Việc cần làm là một con người nhìn `bank_webhook_log`, không phải một lần retry nữa. |
| **401** | Chữ ký sai, thiếu `data`, body không phải JSON, thiếu checksum key | Trả 2xx ở đây nghĩa là một lần cấu hình lệch checksum key sẽ khiến payOS thôi giao lại và **mọi khoản tiền vào im lặng biến mất**. |
| **5xx** | Ordering không phản hồi | Lỗi **tạm thời** — lúc duy nhất việc giao lại thật sự có ích. |

Webhook thử lúc đăng ký (`orderCode` 123) đi vào nhánh `UNKNOWN_REFERENCE` và nhận **200**. Bắt buộc phải vậy, nếu không việc đăng ký thất bại và sau đó không có webhook thật nào tới.

## Lưới an toàn khi webhook không tới

```
POST /internal/payment-intents/{orderId}/reconcile
```

**Kéo** trạng thái từ payOS về thay vì chờ **đẩy** sang. An toàn gọi bất cứ lúc nào: đi qua đúng `ConfirmTransferHandler` như webhook thật, nên không có đường nào ghi nhận tiền theo luật khác. Gọi hai lần chỉ trả `DUPLICATE`.

Cần ở hai tình huống: máy phát triển (webhook không tới được vì cần HTTPS công khai), và production khi một lần deploy trùng đúng lúc tiền vào hoặc payOS hết lượt retry.

Khác `simulate-transfer` ở một điểm quan trọng: `reconcile` đọc **tiền thật** từ payOS, `simulate-transfer` không có đồng nào vào thật và chỉ chạy khi `nexaticket.payment.sandbox=true`.

## Security and audit

Không log số tài khoản đầy đủ, checksum key, hay PII trong payload. Log correlation ID, mã giao dịch đã mask và result code. `bank_webhook_log` giữ payload nguyên văn theo chính sách tài chính và privacy — gồm cả những lần bị từ chối, vì một chữ ký sai chỉ nhìn ra được qua một chuỗi thời gian.

Payload của những lần **không qua được cửa chữ ký** cố ý **không** vào cột `raw_payload` nguyên văn: cột là `jsonb` và một body không phải JSON sẽ làm cả câu `INSERT` vỡ — tức là xoá luôn dòng nhật ký. Chỉ ghi lý do và độ dài.
