# Security documentation

MVP bảo vệ tenant isolation, payment webhook, QR token và quyền admin. Không lưu bank credential, SePay secret hay token signing key trong repository.

## Baseline controls

- OIDC (Keycloak), short-lived access tokens, rotating refresh tokens và MFA cho admin roles.
- Organization scope lấy từ membership đã xác thực; RBAC theo [rbac-permission-matrix.md](../rbac-permission-matrix.md).
- Webhook authentication, replay/deduplication và payload hash persistence.
- QR ticket signed opaque JTI; không PII.
- Audit mọi thay đổi admin/financial state với actor, tenant, before/after, correlation ID.
- Encrypt sensitive bank account fields at rest và mask trong logs/UI.
- Rate limit: public IP; authenticated user+tenant.
- Dependency scanning trong CI; không chạy production với default passwords.

## Milestone security tests

| Milestone | Bắt buộc |
| --- | --- |
| 01 | IDOR mẫu, secret scan |
| 02 | Catalog IDOR, publish authz |
| 03 | Webhook replay, hold authz, idempotency abuse |
| 04 | Check-in org boundary, refund authz |

Xem thêm [legal-constraints-vn.md](../legal-constraints-vn.md).
