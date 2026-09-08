# Milestone 01 — Foundation (tuần 2–3)

## Mục tiêu

Deployable modular monolith + web shells, data/async baseline, auth tenant, idempotency, observability.

## Artifacts

| Artifact | File |
| --- | --- |
| Repo layout | [repo-structure.md](repo-structure.md) |
| Data model | [data-model.md](data-model.md) |
| Auth | [auth-oidc.md](auth-oidc.md), [ADR-0016](adr/ADR-0016-oidc-keycloak.md) |
| CI/CD | [ci-cd.md](ci-cd.md) |
| ADR-0003 | PostgreSQL SoR |
| ADR-0005 | Outbox |
| ADR-0006 | RabbitMQ |
| ADR-0007 | Idempotency |
| ADR-0011 | OTel / SLO |
| Diagrams | container, domain-modules |

## Sprint plan

### Tuần 2

- Monorepo skeleton (`apps/*`, `services/api`, `deploy/compose`).
- Flyway/Liquibase: Identity + outbox + idempotency + audit tables.
- Keycloak realm + Spring resource server.
- ArchUnit module boundaries.
- Customer/admin Next.js login shell.

### Tuần 3

- Tenant guard filter + IDOR tests mẫu.
- Outbox publisher + RabbitMQ consumer heartbeat.
- OTel + health + staging deploy.
- Restore drill.

## Exit gate

- [ ] Staging deploy hoạt động.
- [ ] Migration + tenant-guard + trace + health qua CI.
- [ ] Không secret trong git.
- [ ] Sign-off vào Catalog.
