# Repo structure (monorepo)

## Layout đề xuất

```text
NexaTicket/
  apps/
    web-customer/          # Next.js — discovery, checkout, my tickets (mobile browser = primary mobile)
    web-admin/             # Next.js — organizer + platform admin (web only)
    web-scanner/           # Next.js — check-in on phone browser / PWA
    mobile/                # React Native customer — xem docs/ui/mobile/
  services/
    api/                   # Spring Boot modular monolith
      src/main/java/com/nexaticket/
        identity/
        catalog/
        inventory/
        orders/
        payments/
        tickets/
        notifications/
        analytics/
        shared/            # outbox, idempotency, audit, web config
  packages/
    api-contracts/         # OpenAPI YAML source of truth
    ts-sdk/                # generated or hand-maintained client
  deploy/
    docker-compose.yml     # local: api, pg, redis, rabbitmq, keycloak
    k8s/ or compose.prod/  # staging overlays
  docs/                    # this documentation
  .github/workflows/       # CI
```

## Module packaging (Spring)

- Mỗi domain: `api` (controllers), `application` (use cases), `domain`, `infrastructure` (JPA/Redis).
- Dependency chỉ inward tới `shared`; **architecture test** ArchUnit cấm `inventory` → `payments` trực tiếp nếu không qua application facade đã whitelist.
- Một deployable JAR + workers cùng process MVP (scheduled outbox/expiry) hoặc profile `worker`.

## Branching

- `main` protected; PR + CI xanh.
- `release/mvp-3m` optional; tag `v0.x` mỗi milestone exit.

## Local run (Foundation exit)

```bash
docker compose -f deploy/docker-compose.yml up -d
# api on :8080, web-customer :3000, keycloak :8081
```
