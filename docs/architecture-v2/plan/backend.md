# Plan Backend — 11 service Spring Boot

Triển khai theo [kiến trúc v2](../README.md). Đọc cùng [plan/README.md](README.md) (monorepo, starter, RabbitMQ, CI).

---

## 1. Stack

| Hạng mục | Chọn | Ghi chú |
| --- | --- | --- |
| JDK | Java 21 | Virtual threads cho request thường; **không** dùng ở đoạn giữ lock DB |
| Framework | Spring Boot 3.5.x, Spring MVC | Không WebFlux |
| Build | Maven multi-module + wrapper | Wrapper dạng script: không ai phải cài Maven, không có jar binary trong repo |
| Persistence | Spring Data JPA + `JdbcTemplate` cho đường nóng | Materialize và `GET seats` dùng JDBC |
| Migration | Flyway, multi-location (`platform` + `<service>`) | Starter mang migration của mình |
| Message | Spring AMQP, quorum queue, publisher confirms | [ADR-1009](../adr/ADR-1009-rabbitmq-only.md) |
| Cache/lock | Redis (Lettuce) + Lua | Chỉ cho vé ngồi |
| Auth | Spring Security OAuth2 Resource Server (JWT) | Keycloak |
| Gateway | Spring Cloud Gateway | |
| Resilience | Resilience4j | Timeout **luôn có**, circuit breaker, retry có jitter |
| WS | Spring WebSocket handler thuần (không STOMP) | `realtime-gateway` |
| Test | JUnit 5, Testcontainers, ArchUnit, WireMock, Awaitility | |
| Observability | OTel Java agent + Micrometer | |

**Không dùng:** Kafka, WebFlux, distributed transaction, service mesh (ở MVP), Lombok trên aggregate.

## 2. Khuôn service

Với 11 service, **bắt buộc** có khuôn — nếu không sẽ có 11 hình dạng khác nhau và không ai đọc nổi service của người khác.

```text
services/<name>-service/
├── pom.xml                             # kế thừa services/pom.xml
├── Dockerfile
├── src/main/java/com/nexaticket/<ctx>/
│   ├── domain/{model,event,service,port}
│   ├── application/{command,query,saga}
│   ├── infrastructure/{persistence,messaging,client,acl}
│   └── interfaces/{rest,event,scheduler}
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/<ctx>/V1__init.sql
└── src/test/java/…/{ArchitectureTest, …}
```

`scripts/new-service.sh <name>` sinh từ `services/_template`. POM cha `backend/services/pom.xml` gắn sẵn Spotless, surefire/failsafe, Spring Boot BOM, ArchUnit, Testcontainers, các starter — service mới chỉ khai vài dependency riêng của mình.

Luật ArchUnit (giống nhau ở mọi service, nằm trong `starter-test`):

```java
@ArchTest static final ArchRule domain_khong_biet_framework =
    noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "jakarta.persistence..");

@ArchTest static final ArchRule khong_import_cheo_service =
    noClasses().should().dependOnClassesThat()
        .resideInAPackage("com.nexaticket.(*)..")     // context khác
        .andShould().notBe(sharedKernel());
```

Luật thứ hai là thứ giữ cho monorepo không thoái hoá thành monolith.

## 3. Cross-cutting — làm ở G0, dùng cả dự án

### 3.1 `starter-security` — tenant

```java
public record TenantScope(UUID userId, UUID organizationId, Set<Role> roles) {}
```

Filter dựng scope từ JWT `sub` → gọi `identity-service` `/internal/memberships` (cache 60s). Route có `{orgId}` phải verify nằm trong membership, không thì **404** — không phải 403, để không tiết lộ tài nguyên của tổ chức khác tồn tại.

Ba tầng như v1 giữ nguyên: filter → Hibernate `@Filter` tự động trên entity có `organization_id` → bộ IDOR test tự sinh cho mọi route org-scoped.

Hai annotation ngoại lệ, cả hai đều ghi audit:
- `@PublicEndpoint` — catalog công khai, webhook payOS.
- `@CrossTenantQuery(reason = …)` — **chỉ một chỗ dùng**: phát hiện trùng lịch địa điểm ([ADR-1013](../adr/ADR-1013-venue-schedule-conflict-warning.md)).

### 3.2 `starter-idempotency`

Filter bọc mọi `POST`/`PATCH`/`PUT` dưới `/v1/**`: thiếu `Idempotency-Key` → `400`. `INSERT … ON CONFLICT DO NOTHING` vào `idempotency_records`; conflict + hash khớp → trả response đã lưu; hash khác → `409 IDEMPOTENCY_KEY_REUSED`; chưa có response → `409 REQUEST_IN_PROGRESS`.

Consumer guard dùng `processed_events (consumer_queue, event_id)`, `INSERT … ON CONFLICT DO NOTHING` **trong cùng transaction** với việc xử lý.

### 3.3 `starter-outbox`

```java
@Transactional
public Order create(...) {
    Order o = ...;
    outbox.append("Order", o.id(), "order.created", payload);   // cùng transaction
    return o;
}
```

Publisher `@Scheduled(fixedDelay = 500ms)`: `SELECT … WHERE published_at IS NULL ORDER BY seq LIMIT 200 FOR UPDATE SKIP LOCKED` → publish → chờ publisher confirm → `UPDATE published_at`. **Không xoá dòng** — outbox là nhật ký sự kiện, giữ vĩnh viễn ([ADR-1009](../adr/ADR-1009-rabbitmq-only.md)).

Kèm công cụ replay: `POST /internal/outbox/replay?from=<seq>&to=<seq>&type=<eventType>` (chỉ bật ở dev/staging, và ở prod cần token riêng).

Alert: có dòng `published_at IS NULL` quá 60 giây.

### 3.4 `starter-saga`

Bảng `saga_instances` + `SagaRuntime` + job dọn 30 giây ([sagas.md §7](../sagas.md)). Service nào điều phối saga thì thêm starter này: `ordering-service`, `payout-service`.

### 3.5 Model lỗi

```json
{ "type": "…", "title": "…", "status": 409, "code": "SEAT_UNAVAILABLE",
  "detail": "…", "correlationId": "01J8…", "meta": { } }
```

`code` là hợp đồng ổn định mà frontend dựa vào; `detail` tiếng Anh cho log; frontend tự dịch sang tiếng Việt.

## 4. Migration theo service

Mỗi service một `flyway_schema_history` riêng. Bảng nền tảng (`outbox`, `idempotency_records`, `processed_events`, `saga_instances`, `audit_logs`) đến từ starter.

| Service | Migration chính | Giai đoạn |
| --- | --- | --- |
| identity | `organizations`, `organization_profiles`, `users`, `organization_members`, `invitations`, `scanner_access_codes`, `organization_purchase_limits` | G0 |
| catalog | `venues`, `venue_layout_versions`, `venue_zones`, `venue_fixed_seats`, `events`, `event_sessions`, `ticket_tiers`, `promotions`, `event_seating_plans`, `event_zone_usages`, `event_flexible_blocks`, `event_seat_overrides`, `seating_plan_templates`, `venue_schedule_conflicts`, `platform_purchase_limits` | G1 |
| inventory | `session_inventory`, `session_seats`, `seat_holds`, `seat_hold_items`, `seat_reservations` | G2 |
| ordering | `orders`, `order_items` | G3 |
| payment | `payment_intents`, `payment_attempts`, `webhook_events`, `escrow_bank_accounts` | G3 |
| ledger | `ledger_accounts`, `journal_entries`, `postings`, `account_balance_snapshots`, `bank_statement_lines` | G4 |
| payout | `payout_accounts`, `payout_requests`, `payout_batches`, `payout_batch_items` | G5 |
| ticketing | `tickets`, `check_ins` | G6 |
| analytics | `analytics_events` | G6 |

**Quy ước tiền:** cột VND là `BIGINT`, tên kết thúc `_vnd`. Không có `_cents` ở bất cứ đâu — sai lầm đặt tên của v1 không mang sang.

---

## 5. Giai đoạn G0 — Nền (tuần 1–4)

| Việc | Kết quả |
| --- | --- |
| POM cha + `_template` + `new-service.sh` | Tạo service mới trong 1 phút |
| 6 starter ở `platform/` + migration đi kèm | Service mới có sẵn outbox/idempotency/tenant |
| Compose: PG, Redis, RabbitMQ (delayed plugin), Keycloak, Mailpit | `docker compose up` chạy |
| Keycloak realm `nexaticket`: 4 client, MFA cho group admin | Import tự động |
| `deploy/rabbitmq/topology.yaml` + job áp | Topology khớp CI check |
| `api-gateway`: route, JWT, rate limit Redis, correlation id | 401 khi thiếu token |
| `identity-service`: **superadmin tạo tổ chức**, mời thành viên, `/internal/memberships` | Luồng tạo tổ chức E2E |
| Tenant guard + IDOR test tự sinh | Bảng test của `auth-oidc.md` xanh |
| OTel xuyên 2 service + trace HTTP→AMQP→DB | 1 trace đi qua 2 service |
| Seed dữ liệu dev | 1 superadmin, 2 tổ chức, 1 địa điểm, 1 sự kiện |
| Deploy dev + restore drill | Biên bản RPO |

**Spike bắt buộc trong G0** (rút kinh nghiệm từ v1: đừng dồn rủi ro về cuối):

1. **Spike hold, 3 ngày.** Redis Lua + `session_seats` seed tay + endpoint hold + k6 500 VU. Chỉ để đo p95 và chứng minh không oversell. Không auth, không UI.
2. **Spike sổ cái, 2 ngày.** `journal_entries` + `postings` + constraint trigger cân + 1.000 bút toán đồng thời. Xác nhận trigger `DEFERRABLE` hoạt động như kỳ vọng.
3. **Spike webhook, 1 ngày.** Nhận payload payOS mẫu, kiểm chữ ký HMAC-SHA256 trên trường `data`, ghi `bank_webhook_log`. Xác nhận cách canonicalize của payOS là thứ ta hiểu đúng — sai một chi tiết ở đó thì hệ thống từ chối 100% webhook thật, và triệu chứng giống hệt sai checksum key.

**Exit gate:** deploy dev xanh; tạo tổ chức E2E; trace xuyên service; không secret trong git; ba spike có báo cáo.

## 6. Giai đoạn G1 — Catalog & địa điểm (tuần 5–9)

Giai đoạn dài nhất ngoài checkout, vì mô hình địa điểm ([venue-seating-model](../venue-seating-model.md)) là phần mới hoàn toàn.

### Tuần 5–6 — Địa điểm

- `venues` (`scope = PLATFORM | ORGANIZATION`), `venue_layout_versions`, `venue_zones`, `venue_fixed_seats`.
- Bulk nhập ghế cố định: `JdbcTemplate.batchUpdate` chunk 1.000, validate trùng nhãn **trước** khi ghi, trả lỗi kèm **số dòng**.
- Activate layout version + partial unique index `WHERE status='ACTIVE'`.
- `promote` venue riêng → dùng chung.

### Tuần 7 — Thiết kế chỗ ngồi

- `event_seating_plans`, `event_zone_usages`, `event_flexible_blocks`, `event_seat_overrides`.
- `POST /validate`: sức chứa, trùng `seat_code`, zone thiếu hạng vé, `SEATED` vs `STANDING` lẫn lộn.
- `GET /preview`: sinh danh sách chỗ **không ghi DB** — dùng lại đúng hàm mà materialize dùng, khác mỗi chỗ ghi.
- `seating_plan_templates`: lưu và áp mẫu.

### Tuần 8 — Sự kiện & publish

- `events`, `event_sessions`, `ticket_tiers`, `promotions`.
- Preflight 4 mục, trả **tất cả** lỗi cùng lúc.
- Publish saga: `PUBLISHING` → `EventPublishRequested` → inventory materialize → `EventSeatsMaterialized` → `PUBLISHED`. Job quét `PUBLISHING` quá 5 phút.
- Trần mua vé ba tầng ([ADR-1014](../adr/ADR-1014-configurable-purchase-limits.md)): kiểm trần cứng **lúc ghi cấu hình**; giá trị hiệu lực `LEAST()`-clamp và sao vào `session_inventory` lúc materialize.

### Tuần 9 — Public + trùng lịch

- `GET /v1/events` (search, filter), `GET /v1/events/{slug}`.
- Phát hiện trùng lịch + `venue_schedule_conflicts` + `@CrossTenantQuery` với 4 ràng buộc; **test rò rỉ** là bắt buộc.
- Audit mọi thay đổi admin.

**Exit gate:** tổ chức publish E2E một sự kiện hỗn hợp (khán đài cố định + sân đứng); tổ chức khác không thấy draft; IDOR catalog xanh; test rò rỉ trùng lịch xanh.

## 7. Giai đoạn G2 — Inventory (tuần 10–13)

Core subdomain thứ nhất.

### `GET /v1/sessions/{id}/seats`

Trả ghế đánh số + **tóm tắt tồn kho theo zone** cho vé đứng (không trả đơn vị ảo). Gzip + `ETag`. Đọc `availability_version` và danh sách chỗ trong cùng snapshot.

Khi đã đăng nhập, thêm `purchaseAllowance: { limit, used, remaining }`.

### `POST /v1/sessions/{id}/holds`

```java
@Transactional
public HoldResult handle(HoldCommand cmd) {
    var inv = sessions.requireOnSale(cmd.sessionId());          // trần đã sao sẵn ở đây

    // 1. Trần mỗi lần giữ chỗ — rẻ, làm trước
    inv.limits().validate(cmd.seatedCount(), cmd.standingCount());   // TOO_MANY_SEATS

    // 2. Trần cộng dồn mỗi tài khoản — khoá theo (user, session)
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(? , 0))",
               cmd.userId() + ":" + cmd.sessionId());
    int used = seats.countHeldBy(cmd.sessionId(), cmd.userId());
    if (used + cmd.totalUnits() > inv.limits().maxTicketsPerCustomer())
        throw new PurchaseLimitExceededException(used, inv.limits());

    // 3. Vé ngồi: cổng Redis Lua rồi mới chạm DB
    var lock = redisGate.acquire(cmd.sessionId(), cmd.seatIds());
    try {
        int n = seats.markHeld(cmd.seatIds(), cmd.userId());     // WHERE status='AVAILABLE'
        if (n != cmd.seatIds().size()) throw new SeatUnavailableException();

        // 4. Vé đứng: SKIP LOCKED, không qua Redis
        for (var req : cmd.standing()) {
            var picked = seats.allocateStanding(cmd.sessionId(), req.zoneCode(), req.quantity());
            if (picked.size() < req.quantity()) throw new ZoneSoldOutException(req.zoneCode());
            seats.markHeld(picked, cmd.userId());
        }

        var hold = SeatHold.create(...);            // aggregate ép bất biến
        holds.save(hold);                            // unique index = chốt cuối
        long v = sessions.nextAvailabilityVersion(cmd.sessionId());
        outbox.append("SeatHold", hold.id(), "seat.held", ...);
        availability.publishAfterCommit(cmd.sessionId(), v, changes);
        return HoldResult.from(hold, v);
    } catch (RuntimeException e) {
        lock.releaseQuietly();                       // bù trừ Redis ngoài transaction
        throw e;
    }
}
```

Bốn điểm dễ sai, đều phải có test riêng:

1. `markHeld` phải ghi cả `status='HELD'` **và** `holder_user_id` — quên cái thứ hai thì trần cộng dồn sai.
2. `FOR UPDATE` trên ghế đánh số phải `ORDER BY id` chống deadlock.
3. Redis lỗi → `503 REDIS_UNAVAILABLE` + `Retry-After`, **không** fallback DB.
4. Bù trừ Redis nằm ngoài transaction, best-effort; key tự hết hạn 5 phút nếu bù trừ hỏng.

### Nhả chỗ — bốn đường, cùng một hàm

```java
void release(List<UUID> seatIds) {
    // status → AVAILABLE, holder_user_id → NULL, bump version, broadcast
}
```

Bốn đường gọi: hết hạn giữ chỗ (worker), đơn hết hạn (worker), huỷ đơn, hoàn tiền. **Một hàm duy nhất** — nếu viết bốn lần thì sẽ có một lần quên xoá `holder_user_id` và khách bị khoá hạn mức oan ([ADR-1014](../adr/ADR-1014-configurable-purchase-limits.md)).

### `realtime-gateway`

Fanout exchange + queue `rt-gw.{instanceId}` exclusive/auto-delete/TTL 30s/max-length 10000 drop-head. Gom 200ms mỗi suất thành một message. Backpressure: buffer đầy → đóng kết nối, client tự reconnect + refetch.

### Ngân sách p95 300ms

| Chặng | Ngân sách |
| --- | --- |
| Gateway + auth + tenant + idempotency | 20 ms |
| Trần + advisory lock | 5 ms |
| Lua script | 5 ms |
| DB transaction | 50 ms |
| Broadcast (sau commit, async) | 0 ms |

Đo từ **tuần 10**, không đợi G7. Nếu DB transaction > 100ms thì nghi ngờ bộ đếm version — chuyển sang `nextval` sequence.

**Exit gate:** load test 2.000 VU không oversell; p95 hold ≤ 300ms; test đồng thời vé đứng (500 luồng / 200 chỗ → đúng 200).

## 8. Giai đoạn G3 — Checkout (tuần 14–17)

### `ordering-service` — saga orchestrator

Checkout saga đồng bộ + bù trừ ([sagas.md §2](../sagas.md)). Timeout 2 giây mỗi lời gọi nội bộ, không retry trong luồng đồng bộ. `CheckoutSaga(COMPENSATION_PENDING)` + job 30 giây làm lưới an toàn thứ hai; `payment_expires_at` 15 phút là lưới thứ ba.

`order_items` snapshot: nhãn chỗ, tên hạng vé, đơn giá, giảm giá, **và tỷ lệ hoa hồng tại thời điểm bán**.

### `payment-service`

- `escrow_bank_accounts` cấp nền tảng, chỉ `SUPER_ADMIN`.
- VietQR: dựng TLV theo EMVCo + CRC-16/CCITT-FALSE, trả **chuỗi payload**, frontend tự render QR.
- `payment_reference`: `NT` + 8 ký tự Crockford Base32 (bỏ I, L, O, U), unique index.
- Webhook: `webhook_events` dedupe trước, `SELECT … FOR UPDATE` trên order, phân loại 8 nhánh. Nguyên tắc: **`REJECTED` chỉ khi chắc chắn không có tiền vào tài khoản ta; có tiền mà không khớp thì luôn `MANUAL_REVIEW`.**
- ACL: không trường nào tên theo payOS đi quá `infrastructure/payos`.

Test bắt buộc: duplicate tuần tự, duplicate **song song**, sai reference, thiếu tiền, thừa tiền, sai tài khoản nhận, đến sau expiry, **đến đúng lúc expiry worker chạy**, payload rác, auth sai.

### Worker

| Worker | Chu kỳ | Điều kiện quan trọng |
| --- | --- | --- |
| Hold expiry | 10s | → gọi `release()` chung |
| Payment expiry | 15s | `WHERE status='AWAITING_PAYMENT'` — không được ghi đè đơn đã `PAID` |
| Outbox publisher | 500ms | `FOR UPDATE SKIP LOCKED` |
| Saga sweeper | 30s | |
| Invariant check | 5 phút (staging) | Oversell = 0 |

**Exit gate:** E2E search → hold (ngồi + đứng) → order → VietQR → webhook sandbox → PAID; webhook trùng không sinh vé đôi; late payment → `MANUAL_REVIEW`.

## 9. Giai đoạn G4 — Sổ cái (tuần 18–21)

Core subdomain thứ hai. **Phải xong trước khi bán thật** — không thể bán vé rồi mới xây kế toán.

- Chart of accounts + tự tạo bộ `2011/2012/2013` khi nhận `OrganizationCreated`.
- `CONSTRAINT TRIGGER … DEFERRABLE INITIALLY DEFERRED` ép Σ Nợ = Σ Có.
- `REVOKE UPDATE, DELETE ON postings, journal_entries` — append-only ở cấp quyền DB.
- Consumer `payment.confirmed` → bút toán N1, idempotent theo `paymentAttemptId`, `concurrency = 1`.
- Job hết kỳ giữ tiền → N3. Job snapshot số dư 5 phút.
- Nhập sao kê + đối soát hằng ngày; ngày chưa đối soát xong thì khoá chi trả.
- **8 job bất biến + alert** ([custodial-funds §5](../custodial-funds.md#5-bất-biến--kiểm-tra-tự-động-có-alert)). Bất biến #6 (tổng nghĩa vụ ≤ tiền thực có) là alert mức cao nhất, vi phạm thì dừng chi trả tự động.

**Exit gate:** diễn tập huỷ sự kiện đã bán 500 vé, hoàn tiền toàn bộ, sổ cái vẫn cân; replay từ `outbox` của payment dựng lại sổ cái khớp từng đồng.

## 10. Giai đoạn G5 — Chi trả (tuần 22–24)

- `payout_accounts` do superadmin nhập; khớp tên chủ TK với hồ sơ pháp nhân, không khớp thì chặn.
- 6 cổng chặn ([custodial-funds §7](../custodial-funds.md#7-kỳ-giữ-tiền-dự-phòng-và-chi-trả)).
- Giữ chỗ số dư (`2012` → `2040`) **ngay lúc tạo lệnh**, không đợi chuyển tiền xong.
- Lô chi trả + duyệt bốn mắt; người tạo lô không được là người duyệt.
- `settlement-report` sinh **từ sổ cái** — bảng kê gửi tổ chức.

**Exit gate:** chi trả E2E có 2 người duyệt; chuyển khoản thất bại → bút toán đảo đúng; báo cáo khớp sổ cái.

## 11. Giai đoạn G6 — Vận hành (tuần 25–27)

- `ticketing-service`: consumer `order.paid` → phát hành vé idempotent theo `orderItemId`; QR JWS EdDSA, payload chỉ `{jti, exp}`, `kid` để xoay khoá.
- Check-in: `UPDATE … WHERE id=? AND status='VALID'`, `updated == 1` là điểm quyết định duy nhất. Kiểm `organizationId`; với token từ mã truy cập theo suất, kiểm thêm `sessionId`.
- `sales-summary` cho tổ chức: **chỉ** số vé + số tiền đã bán, trừ vé hoàn.
- `notification-service`: 8 template, retry qua delayed exchange, DLQ + alert.
- `analytics-service`: taxonomy, dùng `analyticsSubjectId`.
- Hoàn tiền saga (superadmin khởi tạo): ghi sổ **trước**, huỷ vé sau.

## 12. Giai đoạn G7 — Cứng hoá (tuần 28–30)

| Việc | Tiêu chí |
| --- | --- |
| Load test 10k VU, 1 hot session hỗn hợp | 0 oversell, p95 hold ≤ 300ms |
| Chaos: giết inventory giữa checkout | Không kẹt chỗ > 15 phút, sổ cái cân |
| Chaos: giết RabbitMQ giữa payment | Không mất event, không vé trùng |
| **Test thứ tự đảo** | 100 hoán vị → trạng thái cuối giống nhau |
| **Test replay** | Xoá `processed_events` của ledger, replay, sổ cái khớp |
| Security smoke | IDOR toàn route, webhook replay, rate limit, rò rỉ trùng lịch |
| Diễn tập đối soát + event-day | Có biên bản |
| Restore drill | RPO ≤ 5 phút |

## 13. Test

| Loại | Cover |
| --- | --- |
| Unit | Pricing, promo, state machine, CRC VietQR, QR token, `payment_reference`, materialize (thuần hàm) |
| Integration (Testcontainers) | **Trọng tâm.** Hold, order, webhook 10 nhánh, idempotency, outbox, tenant/IDOR, worker, check-in, bút toán |
| Đồng thời | 100 luồng cùng ghế → 1 thắng; 500 luồng / 200 vé đứng → đúng 200; 20 request cùng user với trần 10 → đúng 10; 100 webhook song song → 1 tác dụng; 2 scanner cùng vé → 1 `CHECKED_IN` |
| Thứ tự đảo | Mọi consumer |
| Saga component | Từng bước hỏng → bù trừ đúng |
| Contract | Spring Cloud Contract cho mọi cặp `/internal/*` |
| Architecture | ArchUnit ở mọi service |
| Load | k6, kịch bản concert hỗn hợp |

Nhóm **đồng thời** là nhóm duy nhất chứng minh được bất biến không oversell. Viết ở G2, không đợi G7.

## 14. Cấu hình & vận hành

```yaml
nexaticket:
  hold:    { ttl: 5m }
  payment: { window: 15m }
  idempotency: { ttl: 24h, retention: 7d }
  ws: { coalesce-window: 200ms, max-sessions-per-node: 15000 }
  venue: { default-setup-minutes: 240, default-teardown-minutes: 180 }
  limits: { seated-per-hold: 8, standing-per-hold: 10, units-per-hold: 10,
            tickets-per-customer: 10 }          # trần cứng nền tảng
  ledger: { hold-period-days: 3, refund-reserve-bps: 500, reserve-days: 30 }
  payos:  { client-id: ${PAYOS_CLIENT_ID}, api-key: ${PAYOS_API_KEY},
            checksum-key: ${PAYOS_CHECKSUM_KEY} }   # checksum-key KHÔNG gửi đi đâu
  qr:     { signing-key: ${TICKET_QR_SIGNING_KEY}, kid: v1 }
```

**Connection pool:** HikariCP 30–50 mỗi service. To hơn **không** giúp — nó chỉ chuyển tranh chấp vào trong database.

**Alert:** hold error rate, webhook 5xx, consumer lag, outbox tồn > 60s, saga kẹt > 5 phút, oversell ≠ 0, **bất biến sổ cái #6 vi phạm** (mức cao nhất), `MANUAL_REVIEW` mở > ngưỡng, `1320` phải thu ≠ 0.

## 15. Thứ tự ưu tiên nếu phải cắt

1. Tenant guard + idempotency + outbox — không có thì mọi thứ trên nó đều sai.
2. Hold atomic + unique index chống oversell (ngồi + đứng).
3. Order + VietQR + webhook 8 nhánh.
4. **Sổ cái** — giữ tiền mà không có sổ là không được phép vận hành.
5. Phát hành vé + check-in.
6. Địa điểm + thiết kế chỗ ngồi + publish.
7. Chi trả + đối soát.
8. Dashboard, notification, analytics.

Mục 1–5 là sản phẩm. Mục 6 có thể seed bằng SQL trong thời gian ngắn nếu buộc phải hoãn. Mục 7 làm tay được vài tuần đầu (nhưng **phải** có sổ cái đúng để làm tay). Mục 8 cắt theo thứ tự.
