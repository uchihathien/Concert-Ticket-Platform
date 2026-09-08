> ⚠️ **Tài liệu này viết cho kiến trúc v1 (modular monolith) và đã được thay thế.**
> Kế hoạch hiện hành: [../architecture-v2/plan/README.md](../architecture-v2/plan/README.md).
> Giữ lại để tham chiếu các quyết định vẫn còn đúng (contract-first, Definition of Done, quy tắc PR).

# Kế hoạch triển khai NexaTicket

Kế hoạch kỹ thuật để đi từ `docs/` (hiện tại chỉ có tài liệu) đến MVP production trong 13 tuần.

| Tài liệu | Nội dung |
| --- | --- |
| [../BRAINSTORM.md](../BRAINSTORM.md) | Phân tích bài toán, phương án, các chỗ docs còn hở |
| [backend-spring-boot.md](backend-spring-boot.md) | Java 21 + Spring Boot modular monolith |
| [frontend-nextjs.md](frontend-nextjs.md) | 3 app Next.js + packages dùng chung |
| Tài liệu này | Monorepo, branching, CI/CD, quy ước dùng chung |

Nguồn ràng buộc: [DELIVERY-PLAN-3M.md](../DELIVERY-PLAN-3M.md), [WEEKLY-BACKLOG.md](../WEEKLY-BACKLOG.md), [repo-structure.md](../01-foundation/repo-structure.md).

---

## 1. Monorepo

Theo [repo-structure.md](../01-foundation/repo-structure.md), cụ thể hoá:

```text
NexaTicket/
  apps/
    web-customer/            # Next.js 15 App Router — :3000
    web-admin/               # Next.js 15 App Router — :3001
    web-scanner/             # Next.js 15 App Router — :3002
    mobile/                  # React Native — chỉ mở sau tuần 13
  services/
    api/                     # Spring Boot 3.5 (Gradle)
  packages/
    api-contracts/           # OpenAPI YAML — source of truth
    ts-sdk/                  # TS client sinh từ OpenAPI
    ui/                      # React component dùng chung 3 app
    tokens/                  # CSS variables từ design-direction.md
    auth/                    # Auth.js config + helper dùng chung
    eslint-config/  tsconfig/ # config chung
  deploy/
    docker-compose.yml       # pg, redis, rabbitmq, keycloak, mailhog
    keycloak/realm-export.json
    k8s/                     # staging/prod overlay
  load/                      # k6 scripts
  docs/
  .github/workflows/
```

**Công cụ:** pnpm workspaces + Turborepo cho JS; Gradle (Kotlin DSL) cho `services/api`. Hai hệ build tách biệt, nối bằng CI và bằng `packages/api-contracts`.

> Vì sao Gradle chứ không Maven: build cache và incremental test giúp CI nhanh hơn rõ rệt khi có Testcontainers. Nếu đội quen Maven hơn thì đổi — không ảnh hưởng kiến trúc, chỉ cần thống nhất từ tuần 2.

## 2. Contract-first

`packages/api-contracts/openapi.yaml` là **nguồn chân lý duy nhất** của HTTP contract (ADR-0002).

```
openapi.yaml
   ├── openapi-generator (spring)     → services/api: interface controller + DTO
   └── openapi-typescript / orval     → packages/ts-sdk: client + type + TanStack Query hooks
```

Quy tắc:
1. Đổi API ⇒ sửa YAML trước, sinh lại cả hai phía, **trong cùng một PR** (quy tắc #2 của `docs/README.md`).
2. Code sinh ra **không commit** — sinh lúc build. Trừ `ts-sdk` có thể commit để IDE dễ chịu; chọn một cách và giữ nguyên.
3. CI có job `contract-check`: sinh lại và fail nếu khác với code đang có.

## 3. Branching & quy trình PR

Mở rộng phần "Branching" của `repo-structure.md`.

### Nhánh

| Nhánh | Vai trò | Bảo vệ |
| --- | --- | --- |
| `main` | Luôn deploy được lên staging | Protected: PR bắt buộc, CI xanh, ≥1 review, không force-push |
| `feat/<milestone>-<slug>` | Việc thường, ví dụ `feat/03-redis-hold-lua` | — |
| `fix/<slug>` | Sửa lỗi | — |
| `docs/<slug>` | Chỉ tài liệu | — |
| `chore/<slug>` | Build, CI, deps | — |
| `release/mvp-3m` | Cắt ra ở tuần 13 cho soft launch | Protected; chỉ cherry-pick fix |

**Trunk-based:** nhánh sống ≤ 3 ngày, merge bằng squash. Không có `develop`. Tính năng chưa xong che bằng feature flag, không bằng nhánh dài.

### Tag & milestone

Mỗi exit gate merge xong → tag `v0.1.0` (M01), `v0.2.0` (M02), `v0.3.0` (M03), `v0.4.0` (M04), `v1.0.0-rc.1` (tuần 13).

### Commit

Conventional Commits, scope theo module: `feat(inventory): redis lua hold script`, `fix(payments): dedupe concurrent sepay webhook`.

### Quy tắc PR

- PR đụng contract phải kèm diff `openapi.yaml`.
- PR đụng schema phải kèm migration Flyway **mới** (không sửa migration đã merge).
- PR đụng luồng tiền/ghế/vé bắt buộc có integration test kèm theo — không có test thì không merge.
- Checklist milestone (`WEEKLY-BACKLOG.md`) tick trong PR tương ứng.

## 4. Môi trường & cấu hình

| Env | Hạ tầng | Ghi chú |
| --- | --- | --- |
| local | `docker compose up` — PG, Redis, RabbitMQ, Keycloak, Mailhog | Realm import sẵn, 3 client, user seed |
| staging | Managed PG + Redis (có replica) + RabbitMQ; SePay **sandbox** | Nơi chạy load test |
| production | Như staging, sizing lớn hơn | Soft launch tuần 13 |

**Secrets:** không bao giờ trong git. `.env.example` chỉ placeholder. Staging/prod dùng secret manager của nền tảng. Danh sách secret: `DB_PASSWORD`, `REDIS_PASSWORD`, `RABBITMQ_PASSWORD`, `KEYCLOAK_CLIENT_SECRET_*`, `SEPAY_WEBHOOK_SECRET`, `TICKET_QR_SIGNING_KEY`, `BANK_ACCOUNT_ENCRYPTION_KEY`, `NEXTAUTH_SECRET`.

**Hằng số nghiệp vụ** (ADR-0015) khai ở một chỗ duy nhất phía backend, expose cho FE qua API config nếu cần:

| Hằng số | Giá trị MVP |
| --- | --- |
| `HOLD_TTL` | 5 phút |
| `PAYMENT_WINDOW` | 15 phút |
| `IDEMPOTENCY_TTL` | 24 giờ |
| `MAX_SEATS_PER_HOLD` | 8 |
| `WS_COALESCE_WINDOW` | 200 ms |

## 5. CI/CD

`.github/workflows/ci.yml` — chạy trên mọi PR:

| Job | Nội dung | Chặn merge |
| --- | --- | --- |
| `lint` | Spotless (Java), ESLint + Prettier (TS) | Có |
| `contract-check` | Sinh lại từ OpenAPI, so khác biệt | Có |
| `api-unit` | `./gradlew test` | Có |
| `api-integration` | Testcontainers PG + Redis | Có |
| `api-arch` | ArchUnit module boundaries | Có |
| `web-build` | `turbo build` 3 app | Có |
| `web-unit` | Vitest + Testing Library | Có |
| `e2e` | Playwright trên compose (chỉ trên `main` + nightly) | Không (nightly) |
| `security` | Secret scan (gitleaks) + dependency scan | Có (secret scan) |

`deploy-staging.yml` chạy trên push `main`: build image → push registry → deploy → smoke test (`/actuator/health`, login OIDC, kiểm tra Flyway version).

**Chiến lược test theo tầng** (chi tiết trong 2 plan con):

```
        E2E Playwright (ít, chỉ 4 luồng acceptance của SRS §6)
      Contract + Integration (Testcontainers — nơi đặt trọng tâm)
   Unit (pricing, promo, state machine, webhook parser, QR token)
```

## 6. Bản đồ 13 tuần — ai làm gì

Đọc cùng [DELIVERY-PLAN-3M.md](../DELIVERY-PLAN-3M.md). Giả định 2 BE + 2 FE + 0.5 DevOps.

| Tuần | Backend | Frontend | Chung |
| --- | --- | --- | --- |
| 1 | Chốt H1–H12 của brainstorm; dựng repo | Chốt token, dựng 3 app skeleton | Sign-off SRS; xin SePay sandbox |
| 2 | Compose, module skeleton, Flyway V1–V4, resource server | Auth shell 3 app, `packages/tokens` + `ui` | OpenAPI v0 |
| 3 | Tenant guard, outbox, OTel + **spike hold** (§10 brainstorm) | Layout shell, `ts-sdk`, MSW | Staging deploy, restore drill |
| 4 | Venue + seat map API | A-VENUE-LIST, A-VENUE, A-SEATMAP | — |
| 5 | Event/session/tier, publish + materialize | A-EVENT wizard, C-LIST, C-DETAIL | — |
| 6 | Promotion, bank account (encrypt), audit | A-PUBLISH, A-PROMO, A-BANK, C-HOME | **Exit gate 02** |
| 7 | `GET seats`, Redis Lua hold, WS + coalescing | C-SEATS (seat map + WS + countdown) | Micro-benchmark p95 |
| 8 | `POST /orders`, VietQR, expiry workers | C-HOLD, C-PAY | — |
| 9 | Webhook SePay đủ nhánh, issue ticket | C-TICKETS, C-TICKET, C-ORDER(S) | Analytics events |
| 10 | Tối ưu theo kết quả load test | Đánh bóng luồng mua, xử lý lỗi | **Load test 10k → Exit 03** |
| 11 | `POST /check-ins`, metrics API, notification | S-LOGIN, S-HOME, S-RESULT, A-DASH | — |
| 12 | Refund/resolve + audit | A-REVIEW, A-REFUND, P-TENANTS, P-AUDIT | Drills, security smoke → **Exit 04** |
| 13 | Sửa lỗi bug bash | QA mobile web, sửa lỗi | Go/No-go, soft launch |

## 7. Definition of Done (áp cho mọi task)

- [ ] Code + test cùng PR; integration test cho mọi luồng đụng ghế/tiền/vé.
- [ ] OpenAPI cập nhật nếu đổi contract.
- [ ] Migration Flyway mới nếu đổi schema; chạy được cả trên DB rỗng lẫn DB có dữ liệu.
- [ ] Không secret trong diff (gitleaks xanh).
- [ ] Log có `correlation_id`, không log PII/số tài khoản.
- [ ] Endpoint org-scoped có IDOR test.
- [ ] Mutation có `Idempotency-Key`.
- [ ] Copy UI tiếng Việt, mã lỗi khớp bảng lỗi.
