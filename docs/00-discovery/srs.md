# SRS — NexaTicket MVP (Software Requirements Specification)

**Version:** 1.0  
**Horizon:** 3 tháng / 13 tuần  
**Market:** Việt Nam (VND)

## 1. Mục tiêu sản phẩm

NexaTicket cho phép ban tổ chức bán vé có chọn ghế realtime, khách thanh toán chuyển khoản VietQR, nhận vé QR và nhân viên soát vé online.

## 2. Personas & mục tiêu

| Persona | Mục tiêu đo được |
| --- | --- |
| Customer | Tìm event, giữ ghế, thanh toán, xem vé trong ≤ 10 phút happy path |
| Organizer | Publish event có seat map trong 1 buổi làm việc |
| Check-in staff | Quét QR và nhận kết quả trong ≤ 2 giây online |
| Platform admin | Tạo/khóa tenant; xem audit financial |

## 3. Functional requirements

### 3.1 Identity & tenant

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-ID-01 | Đăng ký/đăng nhập OIDC; access token ngắn hạn + refresh xoay vòng | Must |
| FR-ID-02 | User thuộc ≥0 organization qua `organization_members` | Must |
| FR-ID-03 | RBAC theo [permission matrix](rbac-permission-matrix.md) | Must |
| FR-ID-04 | MFA bắt buộc role admin (platform + org owner/admin) | Must |
| FR-ID-05 | Platform admin thao tác cross-tenant có audit | Must |

### 3.2 Catalog & admin

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-CA-01 | CRUD venue thuộc tenant | Must |
| FR-CA-02 | Author seat map (rows/sections/seats) và version publishable | Must |
| FR-CA-03 | CRUD event, event_session, ticket_tier, gắn seat ↔ tier | Must |
| FR-CA-04 | Publish/unpublish event; customer chỉ thấy `PUBLISHED` | Must |
| FR-CA-05 | Promotion: % hoặc số tiền cố định, thời hạn, mã optional | Should |
| FR-CA-06 | Cấu hình `bank_accounts` nhận tiền (encrypt at rest) | Must |
| FR-CA-07 | Audit mọi đổi event/tier/promo/bank account | Must |

### 3.3 Discovery (customer)

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-DI-01 | Search event theo query, city, khoảng ngày | Must |
| FR-DI-02 | Trang chi tiết theo slug: mô tả, session, tier, giá | Must |
| FR-DI-03 | Xem seat map + availability version | Must |

### 3.4 Inventory & hold

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-IN-01 | Hold 1..N ghế atomic (Redis Lua) + persist `seat_holds` | Must |
| FR-IN-02 | Hold TTL 5 phút; client countdown từ server `expires_at` | Must |
| FR-IN-03 | Release hold chủ động hoặc hết hạn worker | Must |
| FR-IN-04 | WebSocket `seat.availability.changed` có version | Must |
| FR-IN-05 | Redis fail → retryable error; không fallback DB lỏng | Must |

### 3.5 Order & payment

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-OR-01 | `POST /orders` từ holds thuộc user; snapshot giá/promo | Must |
| FR-OR-02 | Ghế chuyển `RESERVED`; payment window 15 phút | Must |
| FR-OR-03 | Sinh `payment_reference` unique + VietQR instruction | Must |
| FR-OR-04 | SePay webhook verify + idempotent → `PAID` + issue tickets | Must |
| FR-OR-05 | Sai amount/reference/account → không issue; ghi reason | Must |
| FR-OR-06 | Late payment sau expiry → `MANUAL_REVIEW` | Must |
| FR-OR-07 | Refund thủ công có audit; release ghế khi refund trước check-in | Should |

### 3.6 Tickets & check-in

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-TK-01 | Issue ticket/order_item với signed QR (`jti`) | Must |
| FR-TK-02 | Customer xem vé đã mua (authenticated) | Must |
| FR-TK-03 | Check-in online: VALID→CHECKED_IN atomic | Must |
| FR-TK-04 | Rescan → `ALREADY_CHECKED_IN` | Must |

### 3.7 Operations

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-OP-01 | Dashboard: GMV, số đơn, tỷ lệ thanh toán, ghế sold/held/available | Must |
| FR-OP-02 | Email thông báo order PAID + ticket link (tối thiểu) | Must |
| FR-OP-03 | Rate limit public theo IP; authenticated theo user+tenant | Must |
| FR-OP-04 | Observability OTel + alerts theo ADR-0011 | Must |

## 4. Non-functional requirements

| ID | Requirement |
| --- | --- |
| NFR-01 | API availability target 99.9% (monthly, staging/prod SLO) |
| NFR-02 | p95 `POST .../holds` ≤ 300 ms (env test đã chốt) |
| NFR-03 | RPO ≤ 5 phút; restore drill mỗi milestone 01+ |
| NFR-04 | Mọi mutation có `Idempotency-Key` (TTL response ≥ 24h) |
| NFR-05 | Secrets/keys không trong git; encrypt bank account fields |
| NFR-06 | Load test gate: 10k concurrent / hot session, 0 oversell |
| NFR-07 | UI primary vi-VN |

## 5. Out of scope (MVP 3 tháng)

- Marketplace bên thứ ba, dynamic pricing, đa payment provider.
- Offline check-in / store-and-forward.
- Recommendation/forecast production.
- Native mobile feature-complete (chỉ optional buffer).
- Xuất hóa đơn điện tử tích hợp (organizer tự xuất từ dữ liệu đơn).

## 6. User flows chính (acceptance)

1. **Publish:** Org admin tạo venue → seat map → event/session/tiers → gắn ghế → chọn bank account → publish.
2. **Purchase:** Customer search → detail → chọn ghế → hold → order + QR → chuyển khoản → webhook → vé trong “My tickets”.
3. **Event day:** Staff login → quét QR → CHECKED_IN; quét lại ALREADY_CHECKED_IN.
4. **Exception:** Webhook muộn / sai tiền → MANUAL_REVIEW → finance resolve có audit.

UI chi tiết (sitemap, screen inventory, wireframe-level flows, states): [../ui/README.md](../ui/README.md).

## 7. Traceability

| Artifact | Mapping |
| --- | --- |
| Architecture | [architecture-blueprint.md](architecture-blueprint.md) |
| Metrics | [success-metrics.md](success-metrics.md) |
| Legal | [legal-constraints-vn.md](legal-constraints-vn.md) |
| RBAC | [rbac-permission-matrix.md](rbac-permission-matrix.md) |
| Data | [../01-foundation/data-model.md](../01-foundation/data-model.md) |
| Plan | [../DELIVERY-PLAN-3M.md](../DELIVERY-PLAN-3M.md) |
