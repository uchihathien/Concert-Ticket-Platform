# ADR-0016: OIDC Identity Provider

**Status:** Accepted

## Context

Cần OAuth2/OIDC, refresh xoay vòng, MFA admin, không tự xây password store production nếu tránh được.

## Decision

- Dùng **Keycloak** (self-hosted trên staging/prod) hoặc managed OIDC tương đương (cùng protocol).
- Spring Boot: Spring Authorization resource server (JWT validation).
- Next.js: Authorization Code + PKCE; lưu token theo best practice (httpOnly cookie BFF hoặc secure session).
- Realm tách `nexaticket`; clients: `web-customer`, `web-admin`, `web-scanner`.
- MFA required cho realm role / group map tới `ORG_ADMIN`, `ORG_OWNER`, `PLATFORM_ADMIN`.
- Local/dev: Keycloak Docker Compose trong repo Foundation.

## Consequences

- Mapping IdP groups/roles → `organization_members.role` qua first-login provisioning + admin invite flow.
- Không hard-code vendor SDK ngoài OIDC chuẩn.

## Validation

Login/logout/refresh/MFA admin; expired token 401; user không membership bị từ chối org API.
