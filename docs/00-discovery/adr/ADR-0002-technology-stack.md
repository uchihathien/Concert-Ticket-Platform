# ADR-0002: Technology stack MVP

**Status:** Accepted

## Decision

- Backend: Java 21 / Spring Boot modular monolith.
- Customer web, admin web, scanner web: Next.js + TypeScript (**mobile browser = kênh mobile chính trong 3 tháng**).
- Mobile native: React Native + TypeScript cho **customer only** — **không chặn go-live**; spec đầy đủ [ui/mobile](../../ui/mobile/README.md).
- Admin và platform: web only (không RN).
- Database/coordination/async: PostgreSQL, Redis và RabbitMQ.
- Auth: OIDC Keycloak (ADR-0016).

## Consequences

QA go-live phải cover Safari iOS + Chrome Android cho purchase và check-in web. RN tái sử dụng OpenAPI/`ts-sdk` và design tokens; không fork nghiệp vụ hold/payment.

## Validation

CI build/test web + API; OpenAPI là nguồn contract. Mobile RN job CI chỉ bật khi mở `apps/mobile`.
