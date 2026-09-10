# NexaTicket documentation

Tài liệu theo delivery milestone. Mỗi milestone là nguồn đầy đủ cho scope, ADR, contract và exit gate của phase đó.

**Kế hoạch thực thi:** [DELIVERY-PLAN-3M.md](DELIVERY-PLAN-3M.md) — MVP full trong **13 tuần**.  
**Brainstorm kỹ thuật:** [BRAINSTORM.md](BRAINSTORM.md) — bài toán lõi, phương án, các chỗ docs còn hở.  
**Plan triển khai:** [plan/README.md](plan/README.md) — [backend](plan/backend-spring-boot.md) · [frontend](plan/frontend-nextjs.md).  
**Kiến trúc v2 (microservices + DDD + giữ tiền):** [architecture-v2/README.md](architecture-v2/README.md) — thay thế ADR-0001, 0013.  
**Plan triển khai v2:** [architecture-v2/plan/README.md](architecture-v2/plan/README.md) — [backend](architecture-v2/plan/backend.md) · [frontend](architecture-v2/plan/frontend.md) · [UI](architecture-v2/ui-direction.md).  
**Checklist tuần:** [WEEKLY-BACKLOG.md](WEEKLY-BACKLOG.md).  
**UI / UX:** [ui/README.md](ui/README.md) — web flows.  
**Web screens (chi tiết):** [ui/screens/README.md](ui/screens/README.md).  
**Mobile:** [ui/mobile/README.md](ui/mobile/README.md) — mobile web, PWA, React Native.  
**Mobile screens (chi tiết):** [ui/mobile/screens/README.md](ui/mobile/screens/README.md).

| Milestone | Scope | Exit gate | Tuần kế hoạch |
| --- | --- | --- | --- |
| [00-discovery](00-discovery/README.md) | MVP VN, tenant, security, SRS, RBAC, UI IA | Sign-off MVP/invariants | 1 |
| [01-foundation](01-foundation/README.md) | Repo, CI/CD, auth, schema, OTel | Deploy staging an toàn | 2–3 |
| [02-catalog-admin](02-catalog-admin/README.md) | Venue, seat map, event, tier, promo | Organizer publish E2E | 4–6 |
| [03-seat-checkout](03-seat-checkout/README.md) | Hold, WS, order, thanh toán payOS, ticket | Không oversell @ 10k | 7–10 |
| [04-operations](04-operations/README.md) | Check-in, dashboard, notify, refund | Event-day drill pass | 11–12 |
| [05-ai-scale](05-ai-scale/README.md) | Event taxonomy + pipeline stub | Instrument only (3 tháng) | song song từ tuần 7 |

## Quyết định MVP hiện hành (đã đóng)

| # | Quyết định | Chốt |
| --- | --- | --- |
| 1 | Thị trường / tiền tệ | Việt Nam, VND; xem [legal](00-discovery/legal-constraints-vn.md) |
| 2 | Thanh toán | payOS tạo link + QR; webhook đã ký xác nhận; 1 cổng (ADR-0016) |
| 3 | Tenant | Organizer = tenant độc lập; RBAC theo [matrix](00-discovery/rbac-permission-matrix.md) |
| 4 | Peak load | 10.000 người đồng thời / 1 event session hot; p95 hold ≤ 300 ms |
| 5 | Client MVP | Mobile web + scanner web Must; RN spec [ui/mobile](ui/mobile/README.md) |
| 6 | Check-in | Online only; một lần thành công / ticket |
| 7 | Hold vs thanh toán | Hold 5 phút; sau `POST /orders` ghế `RESERVED` trong payment window 15 phút |
| 8 | Auth | OIDC (Keycloak self-hosted staging/prod managed tương đương) |
| 9 | Backend | Java 21 + Spring Boot modular monolith |
| 10 | AI trong 3 tháng | Chỉ instrument events; không model trong checkout path |

Chi tiết: [product-decisions.md](00-discovery/product-decisions.md).

## Quy tắc cập nhật

1. Thay đổi dài hạn phải có ADR trong milestone chịu trách nhiệm.
2. Đổi contract cập nhật trong cùng milestone và cùng PR.
3. Mermaid là source-of-truth; không thay source bằng ảnh export.
4. Artifact phase trước là constraint cho phase sau.
5. Cắt scope theo [DELIVERY-PLAN-3M.md](DELIVERY-PLAN-3M.md); không cắt invariant oversell/tenant/idempotency.
