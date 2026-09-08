# Legal & compliance constraints (Việt Nam MVP)

Đây là baseline sản phẩm/kỹ thuật, **không thay tư vấn pháp lý**. Trước soft launch cần review bởi stakeholder/legal.

## 1. Thị trường & tiền tệ

- Chỉ VND; không multi-currency MVP.
- Hiển thị giá đã bao gồm hoặc chưa VAT phải **nhất quán và ghi rõ trên UI event** (config org-level `price_tax_mode`: `INCLUSIVE` | `EXCLUSIVE` | `UNKNOWN` — mặc định `UNKNOWN` + disclaimer).

## 2. Hóa đơn VAT

- MVP **không** tích hợp hóa đơn điện tử tự động.
- Organizer chịu trách nhiệm xuất hóa đơn cho khách theo quy định của họ.
- Hệ thống cung cấp đủ dữ liệu đơn: mã đơn, thời gian PAID, hạng vé, số tiền, mã tham chiếu chuyển khoản để đối soát.

## 3. Thanh toán & sở hữu tiền

- Tiền chuyển vào **tài khoản ngân hàng của organizer** (cấu hình `bank_accounts`), không phải ví trung gian NexaTicket.
- NexaTicket xác nhận giao dịch qua SePay webhook; không giữ card/bank credential khách.
- Refund: thao tác ngoài ngân hàng + đánh dấu trong hệ thống; có audit actor/reason.

## 4. Privacy (PDPA-like)

| Dữ liệu | Quy tắc |
| --- | --- |
| PII (họ tên, email, SĐT) | Lưu Identity; encrypt at rest khi policy yêu cầu; mask log |
| Analytics | Dùng identifier tách (`analytics_subject_id`); không join PII ở warehouse mặc định |
| QR ticket | Không PII; opaque JTI |
| Webhook payload | Hash + retain theo retention; không log full PII |
| Bank account org | Encrypt; chỉ ORG_ADMIN+ |

### Retention (đề xuất MVP)

| Loại | Retention |
| --- | --- |
| Orders / payments / tickets | ≥ 5 năm (đối soát tài chính) hoặc theo legal hold |
| Application logs | 30–90 ngày |
| Idempotency keys | ≥ 24 giờ (kỹ thuật); có thể 7 ngày |
| Analytics raw events | 13 tháng trừ khi user xóa/consent rút |

## 5. Consent & terms

- Đăng ký: chấp nhận Terms + Privacy.
- Marketing email: opt-in riêng (notification transactional luôn gửi cho PAID).

## 6. Security obligations (từ security baseline)

Xem [security/README.md](security/README.md). Bổ sung: không lưu SePay secret / signing key trong repo; rotate định kỳ.
