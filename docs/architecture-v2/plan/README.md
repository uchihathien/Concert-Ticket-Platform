# Kế hoạch triển khai — Kiến trúc v2

Đi từ `docs/` (hiện chỉ có tài liệu) đến MVP production theo [kiến trúc v2](../README.md).

| Tài liệu | Nội dung |
| --- | --- |
| Tài liệu này | Monorepo, thư viện dùng chung, RabbitMQ topology, môi trường, CI/CD, branching |
| [backend.md](backend.md) | 11 service Spring Boot + gateway |
| [frontend.md](frontend.md) | 4 app Next.js + packages |

> Thay thế [`docs/plan/`](../../plan/README.md) — bộ đó viết cho modular monolith v1, không còn dùng.

**Mốc:** 24–30 tuần, 4 BE + 2–3 FE + 1–1.5 DevOps. Theo **lộ trình B**: thiết kế đủ 11 service, triển khai 4 deployable trước ([kiến trúc §6](../README.md#6-cái-giá-phải-trả--cần-đọc-trước-khi-cam-kết-tiến-độ)).

---

## 1. Monorepo

```text
NexaTicket/
  backend/                     # build độc lập: Maven, JDK 21
    pom.xml  mvnw  .mvn/
    platform/                  # thư viện dùng chung — xem §2
      shared-kernel/  starter-web/  starter-security/
      starter-outbox/  starter-idempotency/  starter-saga/  starter-test/
    services/
      _template/               # khuôn tạo service mới
      api-gateway/  identity-service/  catalog-service/
      inventory-service/  realtime-gateway/  ordering-service/
      payment-service/  ledger-service/  payout-service/
      ticketing-service/  notification-service/  analytics-service/

  frontend/                    # build độc lập: pnpm + Turborepo, Node 20+
    package.json  pnpm-workspace.yaml  turbo.json
    apps/
      web-customer/            # :3000
      web-admin/               # :3001  tổ chức
      web-scanner/             # :3002
      web-platform/            # :3003  superadmin  ← MỚI ở v2
    packages/
      api-contracts/
        openapi/<service>.yaml # HTTP contract, mỗi service một file
        events/<event>.schema.json
      ts-sdk/  ui/  seatmap/  tokens/  auth/  config/

  deploy/                      # dùng chung
    compose/                   # infra + 4 deployable cho local
    k8s/                       # staging, production
    rabbitmq/topology.yaml
    keycloak/nexaticket-realm.json
  scripts/                     # dùng chung
  load/                        # k6
  docs/
  .github/workflows/           # backend.yml, frontend.yml, e2e.yml
```

**Backend và frontend tách hẳn**, mỗi bên một hệ build và một pipeline CI chạy theo path filter — sửa frontend không kích hoạt build backend và ngược lại. `deploy/` và `scripts/` dùng chung vì cả hai bên đều cần Keycloak và hạ tầng local.

**Vì sao vẫn monorepo dù đã tách service:** một PR đổi contract sửa được cả producer, consumer và frontend trong cùng một commit. Với 11 service ở 11 repo, mỗi thay đổi contract thành một vũ điệu 4 PR. Đổi lại phải giữ kỷ luật: **cùng repo không có nghĩa là được import chéo** — `ArchitectureRules.noCrossContextImports` cấm ở tầng test.

## 2. Thư viện dùng chung — ranh giới cứng

Đây là chỗ dễ giết chết microservices nhất. Một thư viện `common` chứa entity nghiệp vụ sẽ biến 11 service thành một monolith phân tán.

### Được phép

| Thư viện | Nội dung | Vì sao an toàn |
| --- | --- | --- |
| `starter-web` | Model lỗi RFC 7807 + `code`, `CorrelationIdFilter`, OTel, health, JSON config | Kỹ thuật thuần, không nghiệp vụ |
| `starter-security` | Resource server JWT, `TenantContext`, Hibernate tenant filter, `@PublicEndpoint`, `@CrossTenantQuery` | Cơ chế, không luật nghiệp vụ |
| `starter-outbox` | Bảng `outbox` (migration), `OutboxWriter`, publisher, cấu hình RabbitMQ | Hạ tầng |
| `starter-idempotency` | Bảng `idempotency_records` + `processed_events` (migration), filter, consumer guard | Hạ tầng |
| `starter-saga` | Bảng `saga_instances` (migration), runtime, job dọn saga kẹt | Hạ tầng |
| `shared-kernel` | `Money`, `TenantId`, `CorrelationId`, `TimeWindow` — record bất biến | VO kỹ thuật, đổi phải có đồng thuận |

Các starter **mang theo migration của mình** (Flyway multi-location: `classpath:db/migration/platform` + `classpath:db/migration/<service>`). Nhờ vậy 8 service không phải copy-paste bảng `outbox`.

### Bị cấm

- Entity hoặc aggregate nghiệp vụ dùng chung (`Order`, `Seat`, `Ticket`).
- DTO dùng chung giữa hai service. Service A gọi service B thì **sinh client từ OpenAPI của B**, không import class của B.
- Enum nghiệp vụ dùng chung. `OrderStatus` của Ordering không phải `OrderStatus` của Ledger.

### Phiên bản

Starter đánh version độc lập; service nâng cấp theo nhịp riêng. **Một thay đổi phá vỡ ở starter không được buộc cả 11 service nâng cùng lúc** — nếu điều đó xảy ra thì starter đó đang chứa thứ không nên chứa.

## 3. Contract-first

```
packages/api-contracts/openapi/<service>.yaml
   ├── openapi-generator (spring)  → service đó: interface controller + DTO
   ├── openapi-generator (java)    → service GỌI nó: client
   └── orval / openapi-typescript  → packages/ts-sdk

packages/api-contracts/events/<event>.schema.json
   ├── jsonschema2pojo             → producer + mọi consumer
   └── json-schema-to-typescript   → ts-sdk (cho analytics/debug)
```

RabbitMQ không có schema registry ([ADR-1009](../adr/ADR-1009-rabbitmq-only.md)), nên JSON Schema trong repo **là** registry. Job CI `contract-check`:

1. Sinh lại toàn bộ, fail nếu khác code đang có.
2. So schema event với bản trên `main`: chỉ cho thêm trường optional. Thay đổi phá vỡ ⇒ bắt buộc tăng `eventVersion` và giữ song song.

## 4. RabbitMQ topology

`deploy/rabbitmq/topology.yaml` là nguồn chân lý, áp bằng một job khi deploy — **không** để mỗi service tự khai báo tuỳ ý, vì sau vài tháng sẽ trôi.

```yaml
exchanges:
  - { name: nexaticket.identity,   type: topic,  durable: true }
  - { name: nexaticket.catalog,    type: topic,  durable: true }
  - { name: nexaticket.inventory,  type: topic,  durable: true }
  - { name: nexaticket.ordering,   type: topic,  durable: true }
  - { name: nexaticket.payment,    type: topic,  durable: true }
  - { name: nexaticket.ledger,     type: topic,  durable: true }
  - { name: nexaticket.payout,     type: topic,  durable: true }
  - { name: nexaticket.ticketing,  type: topic,  durable: true }
  - { name: nexaticket.availability, type: fanout, durable: true }
  - { name: nexaticket.dlx,        type: topic,  durable: true }
  - { name: nexaticket.retry,      type: x-delayed-message, durable: true }

queues:
  - name: ledger.payment.confirmed
    type: quorum
    bind: { exchange: nexaticket.payment, key: payment.confirmed }
    consumers: 1            # tuần tự — ADR-1009 §4
    dlx: nexaticket.dlx
  - name: ordering.payment.confirmed
    bind: { exchange: nexaticket.payment, key: payment.confirmed }
  - name: inventory.ordering.paid
    bind: { exchange: nexaticket.ordering, key: order.paid }
  - name: ticketing.ordering.paid
    bind: { exchange: nexaticket.ordering, key: order.paid }
  # …
```

Quy ước bắt buộc: quorum queue, publisher confirms, manual ack, mọi queue có DLX. Retry qua `nexaticket.retry` với backoff 5s/30s/2m/10m/1h, tối đa 5 lần.

`realtime-gateway` là ngoại lệ: mỗi instance tự khai một queue `rt-gw.{instanceId}` exclusive + auto-delete, không nằm trong topology tĩnh.

## 5. Chạy local — vấn đề thật của 11 service

11 JVM Spring Boot ≈ 6–8 GB RAM. Không lập trình viên nào chạy nổi cả hệ trên laptop.

**Cách làm:**

```bash
# Luôn chạy: hạ tầng
docker compose -f deploy/compose/infra.yml up -d     # PG, Redis, RabbitMQ, Keycloak, Mailpit

# Mặc định: 4 deployable của lộ trình B
docker compose -f deploy/compose/apps.yml up -d      # edge, commerce, finance, workers

# Đang sửa inventory: tắt nó trong compose, chạy từ IDE
docker compose stop commerce
cd backend && ./mvnw -pl services/inventory-service spring-boot:run
```

Đây là lập luận thực dụng mạnh nhất cho **lộ trình B**: 4 deployable chạy được trên laptop, 11 thì không. Khi thật sự cần tách, lúc đó đã có hạ tầng dev tốt hơn để đỡ.

Seed dữ liệu: `deploy/compose/seed/` tạo sẵn 1 superadmin, 2 tổ chức, 1 địa điểm dùng chung có khán đài cố định + sân linh hoạt, 1 sự kiện hỗn hợp (ngồi + đứng) đã publish. **Có seed ngay từ tuần 1** — không có nó thì mỗi người tự bấm tay 20 phút mỗi lần muốn thử.

## 6. Môi trường

| Env | Hạ tầng | Ghi chú |
| --- | --- | --- |
| local | Compose | 4 deployable |
| dev | K8s nhỏ | Nơi đầu tiên chạy đủ 11 service tách rời |
| staging | Giống prod thu nhỏ; SePay sandbox; Redis có replica | Nơi load test và diễn tập đối soát |
| production | Soft launch | `ledger_db` tách instance vật lý |

**Secrets** không bao giờ trong git: `DB_PASSWORD_*` (11 bộ), `REDIS_PASSWORD`, `RABBITMQ_PASSWORD`, `KEYCLOAK_CLIENT_SECRET_*` (4 client), `SEPAY_WEBHOOK_SECRET`, `TICKET_QR_SIGNING_KEY`, `BANK_ACCOUNT_ENCRYPTION_KEY`, `NEXTAUTH_SECRET_*`.

**Quyền database:** mỗi service một user riêng chỉ thấy schema của mình ([ADR-1002](../adr/ADR-1002-database-per-service.md)). `ledger_db` thêm `REVOKE UPDATE, DELETE ON postings, journal_entries`.

## 7. Branching & PR

| Nhánh | Vai trò |
| --- | --- |
| `main` | Luôn deploy được lên dev; protected, PR + CI xanh + ≥1 review |
| `feat/<service>-<slug>` | `feat/inventory-standing-allocation` |
| `fix/<slug>`, `chore/<slug>`, `docs/<slug>` | |
| `release/v2-mvp` | Cắt ở giai đoạn G7 |

Trunk-based, nhánh sống ≤ 3 ngày, squash merge. Conventional Commits với scope là tên service: `feat(ledger): posting for payment confirmed`.

**Quy tắc PR bắt buộc:**

- Đổi contract HTTP hoặc event ⇒ kèm diff trong `packages/api-contracts`.
- Đổi schema ⇒ migration Flyway **mới** trong service đó; không sửa migration đã merge.
- Đụng ghế / tiền / vé ⇒ có integration test kèm; không test thì không merge.
- Consumer mới ⇒ có test **thứ tự đảo** ([ADR-1009 §4](../adr/ADR-1009-rabbitmq-only.md)).
- Đụng `ledger-service` ⇒ **2 người review**.

## 8. CI/CD

CI phát hiện service nào đổi (path filter) và chỉ chạy job của service đó — 11 service mà build hết mỗi PR thì CI mất 40 phút.

| Job | Chạy khi | Chặn merge |
| --- | --- | --- |
| `lint` | luôn | ✅ |
| `contract-check` | `packages/api-contracts` đổi | ✅ |
| `<service>:test` | service đó đổi | ✅ |
| `<service>:integration` | service đó đổi — Testcontainers PG + Redis + RabbitMQ | ✅ |
| `arch-test` | service đó đổi — ArchUnit | ✅ |
| `web-build`, `web-unit` | `apps/` hoặc `packages/` đổi | ✅ |
| `secret-scan` | luôn — gitleaks | ✅ |
| `e2e` | nightly + trước release — Playwright trên compose | ❌ (nightly) |
| `ledger-invariants` | nightly — 8 bất biến sổ cái | ❌ (nightly, có alert) |

Deploy: push `main` → build image các service đã đổi → deploy dev → smoke. Staging và production cần duyệt tay.

**Smoke test sau deploy:** health mọi service, đăng nhập OIDC, Flyway version khớp, RabbitMQ topology khớp `topology.yaml`, một `GET /v1/events` trả 200.

## 9. Chiến lược test theo tầng

```
      E2E Playwright  — ít, chỉ 5 luồng nghiệp vụ chính
   Contract + Saga component test  — WireMock các service khác
 Integration (Testcontainers)  — TRỌNG TÂM: đồng thời, idempotency, tenant, sổ cái
 Unit  — pricing, promo, state machine, VietQR CRC, QR token, materialize
```

Ba nhóm test **đặc thù v2** không có ở v1, đều bắt buộc:

1. **Thứ tự đảo** — gửi event của một đơn theo thứ tự ngẫu nhiên, trạng thái cuối phải giống nhau mọi lần.
2. **Bù trừ saga** — giết một service giữa chừng, kiểm tra không kẹt ghế và sổ cái vẫn cân.
3. **Bất biến sổ cái** — 8 truy vấn SQL chạy sau mỗi lần load test và hằng đêm trên staging.

## 10. Definition of Done

- [ ] Code + test cùng PR; integration test cho mọi luồng đụng ghế/tiền/vé.
- [ ] OpenAPI / event schema cập nhật nếu đổi contract.
- [ ] Migration Flyway mới; chạy được trên DB rỗng lẫn DB có dữ liệu.
- [ ] Consumer idempotent và không phụ thuộc thứ tự message.
- [ ] Endpoint org-scoped có IDOR test; endpoint platform có test 403 cho role tổ chức.
- [ ] Mutation có `Idempotency-Key`.
- [ ] Log có `correlationId`; không log PII, số tài khoản, nội dung email.
- [ ] Không secret trong diff.
- [ ] Copy UI tiếng Việt; mã lỗi khớp bảng lỗi.
