# Quyết định sản phẩm đã đóng (trước implementation)

Thay thế mục “Decisions needed” trong blueprint. Mọi thay đổi sau ngày kickoff phải có ADR + cập nhật bảng này.

| ID | Câu hỏi | Quyết định | ADR / doc |
| --- | --- | --- | --- |
| PD-01 | Thị trường & pháp lý | VN; VND; hóa đơn VAT theo organizer (xuất ngoài hoặc tích hợp sau); PII theo PDPA-like baseline | [legal](legal-constraints-vn.md) |
| PD-02 | Payment | VietQR + SePay bank transfer; không Stripe MVP | ADR-0013 |
| PD-03 | Refund & settlement | Refund thủ công có audit; late payment → `MANUAL_REVIEW`; settlement thuộc organizer bank account | ADR-0013, runbook |
| PD-04 | Tenant | Mỗi organizer = 1 organization; platform admin cross-tenant có audit | ADR-0008 |
| PD-05 | Ai tạo venue/event | `ORG_OWNER`, `ORG_ADMIN`, `EVENT_MANAGER` theo RBAC matrix | [rbac](rbac-permission-matrix.md) |
| PD-06 | Peak & latency | 10k concurrent seat-selection; p95 hold ≤ 300 ms | ADR-0011 |
| PD-07 | Client priority | **Mobile web responsive Must**; scanner web Must; **React Native customer** chi tiết ở [ui/mobile](../ui/mobile/README.md); RN không chặn go-live 13 tuần | ADR-0002 |
| PD-08 | Check-in offline | Không hỗ trợ MVP; scanner phải online | ADR-0014 |
| PD-09 | Hold / payment window | Hold ACTIVE TTL 5 phút; order `AWAITING_PAYMENT` giữ ghế RESERVED 15 phút | ADR-0015 |
| PD-10 | Auth IdP | OIDC Keycloak (hoặc managed tương đương); MFA bắt buộc admin | ADR-0016 |
| PD-11 | Ngôn ngữ UI MVP | vi-VN chính; en dự phòng i18n keys | SRS |
| PD-12 | AI 3 tháng | Taxonomy + outbox → analytics stub only | ADR-0012 |

## Invariant không đàm phán

1. Server authoritative cho availability; client không tự claim ghế.
2. Không oversell: tối đa một reservation/sold hợp lệ cho mỗi `(event_session_id, seat_id)`.
3. Tenant scope từ membership đã xác thực; không tin `organization_id` client.
4. Webhook/payment/ticket mutation idempotent.
5. QR không chứa PII; `jti` unique.
6. AI không tham gia checkout decision.
