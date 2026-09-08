# Auth & OIDC (Foundation)

Xem ADR-0016. Tóm tắt triển khai tuần 2–3.

## Flows

1. User login qua Keycloak (Authorization Code + PKCE).
2. Backend validate JWT (`iss`, `aud`, `exp`, signature JWKS).
3. First request: upsert `users` by `idp_subject`; load `organization_members`.
4. Request context: `UserId`, `tenantId` (nếu route org-scoped), roles.
5. Admin MFA enforced ở IdP.

## Invite member (org)

1. ORG_ADMIN tạo invite (email + role).
2. User login/register → accept invite → membership row.
3. Audit `MEMBER_INVITED`, `MEMBER_JOINED`.

## Token rules

| Token | Lifetime gợi ý |
| --- | --- |
| Access | 5–15 phút |
| Refresh | 7–30 ngày, rotate, reuse detection |

## Test matrix (CI)

| Case | Expect |
| --- | --- |
| No token | 401 |
| Valid customer hits org admin API | 403 |
| User org A đọc venue org B | 404/403 (IDOR) |
| Expired access | 401 |
| Platform admin support write | 200 + audit row |
