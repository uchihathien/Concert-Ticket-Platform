# Plan — Backend (Java 21 + Spring Boot modular monolith)

Triển khai `services/api` theo ADR-0001 (modular monolith), ADR-0003 (PostgreSQL SoR), ADR-0004 (Redis Lua hold), ADR-0005 (outbox), ADR-0007 (idempotency), ADR-0013 (VietQR/SePay), ADR-0015 (hold/payment window), ADR-0016 (OIDC).

Đọc trước: [../BRAINSTORM.md](../BRAINSTORM.md) — plan này thực thi các quyết định đã chốt ở đó.

---

## 1. Stack chốt

| Hạng mục | Chọn | Ghi chú |
| --- | --- | --- |
| JDK | Java 21 (LTS) | Virtual threads bật cho request thường; **không** dùng ở đoạn giữ lock DB |
| Framework | Spring Boot 3.5.x | Spring MVC (blocking), không WebFlux |
| Build | Gradle Kotlin DSL | Xem lý do ở [README.md §1](README.md#1-monorepo) |
| Persistence | Spring Data JPA + JdbcTemplate cho query nóng | Seat map/materialize dùng JDBC batch, không JPA |
| Migration | Flyway | Chỉ forward; không sửa migration đã merge |
| Cache/lock | Redis (Lettuce) + Lua | ADR-0004 |
| Async | RabbitMQ (Spring AMQP) | ADR-0006 |
| Auth | Spring Security OAuth2 Resource Server (JWT) | ADR-0016 |
| WS | Spring WebSocket, handler thuần (không STOMP) | Xem brainstorm §6.2 |
| API contract | openapi-generator từ `packages/api-contracts` | Contract-first |
| Test | JUnit 5, Testcontainers (PG + Redis + RabbitMQ), ArchUnit, WireMock | |
| Observability | OpenTelemetry Java agent + Micrometer | ADR-0011 |
| Rate limit | Bucket4j + Redis | FR-OP-03 |
| Mapping | MapStruct | Tránh mapper viết tay |

**Không dùng:** Lombok trên entity nghiệp vụ (dùng record/Java 21 nơi hợp lý), MongoDB, Kafka, distributed transaction.

## 2. Cấu trúc module

```text
com.nexaticket
├── NexaTicketApplication.java
├── shared/
│   ├── tenant/          TenantContext, TenantFilter, TenantAwareRepository
│   ├── idempotency/     IdempotencyFilter, IdempotencyRecordStore
│   ├── outbox/          OutboxEvent, OutboxWriter, OutboxPublisher
│   ├── audit/           AuditLogger, @Audited
│   ├── error/           ApiError, GlobalExceptionHandler (RFC 7807 + code)
│   ├── crypto/          FieldEncryptor (bank account), QrTokenSigner
│   ├── web/             SecurityConfig, RateLimitFilter, CorrelationIdFilter
│   └── time/            Clock bean (test dễ tua thời gian)
├── identity/
├── catalog/
├── inventory/
├── orders/
├── payments/
├── tickets/
├── notifications/
└── analytics/
```

Mỗi module domain có 4 lớp:

```text
<module>/
├── api/              @RestController, request/response DTO (implement interface sinh từ OpenAPI)
├── application/      use case, @Transactional, facade cho module khác gọi vào
├── domain/           entity, value object, state machine, business rule thuần
└── infrastructure/   JPA repository, Redis, adapter ngoài
```

### Luật phụ thuộc (ArchUnit ép)

1. `api` → `application` → `domain`. Không có chiều ngược.
2. `infrastructure` chỉ được gọi từ `application`; `domain` không biết JPA.
3. Module chỉ được gọi module khác qua `application.<X>Facade` — **cấm** truy cập `infrastructure` hoặc `domain` của module khác.
4. Chiều gọi được phép, theo `domain-modules.mmd`:
   `identity → catalog → inventory → orders → payments → tickets`, cộng `* → analytics/notifications` **chỉ qua outbox**, không gọi trực tiếp.
5. Mọi entity có cột `organization_id` bắt buộc khai `@Filter("tenantFilter")`.

Test này viết ở **tuần 2**, không để sau — sau khi có 8 module thì không ai dọn được nữa.

## 3. Cross-cutting — làm ở Foundation, dùng cả dự án

### 3.1 Tenant guard (3 tầng, xem brainstorm §5.1)

```java
// Tầng 1: dựng context từ JWT
public final class TenantContext {
    private static final ScopedValue<TenantScope> SCOPE = ScopedValue.newInstance();
    public record TenantScope(UUID userId, UUID organizationId, Set<Role> roles) {}
}
```

`TenantFilter` chạy sau authentication: đọc `sub` → `users.idp_subject` → nạp `organization_members`. Nếu route có `{orgId}`, verify `orgId` nằm trong membership, nếu không → **404** (không phải 403 — không tiết lộ sự tồn tại của tài nguyên org khác).

```java
// Tầng 2: Hibernate filter tự động
@FilterDef(name = "tenantFilter",
    parameters = @ParamDef(name = "orgId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "organization_id = :orgId")
@Entity public class Venue { … }
```

Bật filter trong một `@Aspect` quanh `@Transactional` của tầng application; endpoint public đánh dấu `@PublicEndpoint` để bỏ qua có chủ đích.

```java
// Tầng 3: IDOR test tự sinh cho mọi route org-scoped
@ParameterizedTest @MethodSource("allOrgScopedRoutes")
void userOfOrgA_cannotTouch_resourceOfOrgB(RouteSpec route) { … }
```

### 3.2 Idempotency (ADR-0007)

`IdempotencyFilter` bọc mọi `POST`/`PATCH`/`PUT` dưới `/v1/**`:

1. Thiếu header `Idempotency-Key` → `400 IDEMPOTENCY_KEY_REQUIRED`.
2. `INSERT INTO idempotency_records (user_id, key, request_hash) ON CONFLICT DO NOTHING`.
   - Insert được → chạy tiếp; ghi `response_status`/`response_body` sau khi handler xong.
   - Conflict + `request_hash` khớp → **trả lại response đã lưu**.
   - Conflict + `request_hash` khác → `409 IDEMPOTENCY_KEY_REUSED`.
   - Conflict + response chưa có (request trước còn đang chạy) → `409 REQUEST_IN_PROGRESS`, client retry.
3. Job dọn record > 24h (giữ 7 ngày theo `legal-constraints-vn.md`).

Webhook SePay **không** đi đường này — nó dùng `webhook_events` riêng (§7.3).

### 3.3 Outbox (ADR-0005)

```java
@Transactional
public Order createOrder(...) {
    Order order = ...;                              // ghi nghiệp vụ
    outboxWriter.write("Order", order.getId(), "OrderCreated", payload);  // cùng transaction
    return order;                                   // commit chung
}
```

`OutboxPublisher` chạy `@Scheduled(fixedDelay = 500ms)`:
`SELECT … WHERE published_at IS NULL ORDER BY created_at LIMIT 200 FOR UPDATE SKIP LOCKED` → publish RabbitMQ → `UPDATE published_at = now()`. `SKIP LOCKED` cho phép chạy nhiều instance mà không giẫm chân nhau.

### 3.4 Model lỗi

Một hình dạng lỗi cho toàn API, để FE map sang copy tiếng Việt (xem [frontend](frontend-nextjs.md#7-bảng-lỗi)):

```json
{
  "type": "https://nexaticket.vn/errors/seat-unavailable",
  "title": "Seat unavailable",
  "status": 409,
  "code": "SEAT_UNAVAILABLE",
  "detail": "One or more seats are no longer available",
  "correlationId": "01J8…",
  "meta": { "unavailableSeatIds": ["uuid1"] }
}
```

`code` là hợp đồng ổn định (FE dựa vào nó), `detail` là tiếng Anh cho log, FE tự dịch.

### 3.5 Audit

`@Audited(action = "BANK_ACCOUNT_UPDATED", entity = "bank_account")` trên method application; aspect ghi `audit_logs` với actor, tenant, before/after JSON (đã mask số tài khoản), `correlation_id` — **trong cùng transaction** với thay đổi.

---

## 4. Migration Flyway — thứ tự & nội dung

| Version | Tuần | Nội dung |
| --- | --- | --- |
| `V1__identity.sql` | 2 | `organizations`, `users`, `organization_members` + seed roles |
| `V2__platform.sql` | 2 | `outbox`, `idempotency_records`, `audit_logs`, **`webhook_events`** (bổ sung, brainstorm §4.3) |
| `V3__catalog.sql` | 4 | `venues`, `venue_seat_maps`, `seat_map_seats` |
| `V4__events.sql` | 5 | `events`, `event_sessions` (+ `availability_version`), `ticket_tiers`, `session_seats`, `promotions` |
| `V5__inventory.sql` | 7 | `seat_holds`, `seat_hold_items` |
| `V6__orders.sql` | 8 | `orders`, `order_items` |
| `V7__payments.sql` | 8 | `bank_accounts`, `payment_attempts` |
| `V8__tickets.sql` | 9 | `tickets`, `check_ins` |
| `V9__analytics.sql` | 10 | `analytics_events` sink |
| `V10__indexes.sql` | 10 | Index bổ sung sau khi có kết quả load test |

### Khác biệt so với `data-model.md` (đã quyết ở brainstorm)

1. **`unit_price_cents` → `unit_price_vnd`**, `subtotal_cents` → `subtotal_vnd`, v.v. VND là số nguyên đồng, không có "cents". Sửa ngay ở V4/V6, không mang nợ tên gọi (H1).
2. **`event_sessions.availability_version BIGINT NOT NULL DEFAULT 0`** — counter cấp suất cho WS. `session_seats.availability_version` đổi tên thành `row_version` (optimistic lock) hoặc bỏ (H2).
3. **Bảng `webhook_events`** để dedupe webhook (H5).
4. Ràng buộc bổ sung:

```sql
-- Không hai hold ACTIVE chồng ghế (guard cuối cùng cấp DB)
CREATE UNIQUE INDEX uq_active_hold_per_seat
  ON seat_hold_items (session_seat_id)
  WHERE hold_id IN (SELECT id FROM seat_holds WHERE status = 'ACTIVE');
-- Postgres không cho subquery trong partial index → thay bằng:
ALTER TABLE seat_hold_items ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE';
CREATE UNIQUE INDEX uq_active_hold_per_seat
  ON seat_hold_items (session_seat_id) WHERE status = 'ACTIVE';
```

Đây là **cái chốt an toàn cuối cùng chống oversell ở tầng database** — kể cả khi Redis và application logic đều sai, DB vẫn từ chối. Đáng giá hơn mọi test.

---

## 5. Milestone 01 — Foundation (tuần 2–3)

### Tuần 2

| Task | Kết quả |
| --- | --- |
| Gradle multi-source-set, Spotless, ArchUnit skeleton | `./gradlew check` xanh |
| `deploy/docker-compose.yml`: PG 16, Redis 7, RabbitMQ 3.13, Keycloak 25, Mailhog | `docker compose up` chạy được |
| Keycloak realm `nexaticket` export sẵn: 3 client, MFA cho group admin | Import tự động lúc start |
| Flyway V1, V2 | `flyway:info` đúng |
| `SecurityConfig`: resource server JWT, JWKS từ Keycloak | 401 khi thiếu token |
| `identity`: upsert user theo `idp_subject` lúc first request | `GET /v1/me` trả user |
| `CorrelationIdFilter` + structured JSON logging | Log có `correlation_id` |

### Tuần 3

| Task | Kết quả |
| --- | --- |
| `TenantFilter` + Hibernate filter + IDOR test tự sinh | Bảng test matrix của `auth-oidc.md` xanh |
| `IdempotencyFilter` + test replay song song | 100 request cùng key → 1 tác dụng |
| `OutboxWriter` + `OutboxPublisher` + consumer heartbeat | Event tới RabbitMQ |
| OTel agent + `/actuator/health` + trace HTTP→DB | 1 trace có span DB |
| **Spike hold** (brainstorm §10) | Báo cáo p95 sơ bộ, có/không oversell |
| **Spike webhook** | Xác nhận cơ chế auth của SePay |
| Deploy staging + restore drill | Biên bản RPO |

**Exit gate 01:** staging xanh, migration + tenant guard + trace qua CI, không secret trong git.

---

## 6. Milestone 02 — Catalog & Admin (tuần 4–6)

### 6.1 Endpoint (theo `02-catalog-admin/api.md`)

Admin (tenant-scoped, `EVENT_MANAGER+` trừ khi ghi khác):

```
POST   /v1/admin/venues                         GET /v1/admin/venues        GET|PATCH /v1/admin/venues/{id}
POST   /v1/admin/venues/{id}/seat-maps
POST   /v1/admin/seat-maps/{id}/seats:bulk
POST   /v1/admin/seat-maps/{id}/activate        GET /v1/admin/seat-maps/{id}
POST   /v1/admin/events                         GET /v1/admin/events        PATCH /v1/admin/events/{id}
POST   /v1/admin/events/{id}/sessions
POST   /v1/admin/sessions/{id}/tiers
PUT    /v1/admin/sessions/{id}/seat-tiers
POST   /v1/admin/events/{id}/publish | /unpublish
POST|GET|PATCH /v1/admin/promotions
POST|GET|PATCH /v1/admin/bank-accounts          (ORG_ADMIN+)
POST|GET|PATCH|DELETE /v1/admin/members         (ORG_ADMIN+)
```

Public:

```
GET /v1/events?query&city&from&to&page
GET /v1/events/{slug}
```

### 6.2 Điểm kỹ thuật đáng lưu

**Bulk seats.** `seats:bulk` có thể nhận vài nghìn dòng. Dùng `JdbcTemplate.batchUpdate` chunk 1.000, không JPA. Validate trùng `(section, row_label, seat_label)` trước khi ghi, trả lỗi kèm **số dòng** để UI hiện đúng chỗ (A-SEATMAP yêu cầu).

**Activate seat map.** Trong một transaction: `UPDATE … SET status='ARCHIVED' WHERE venue_id=? AND status='ACTIVE'` rồi set map mới `ACTIVE`. Thêm partial unique index để DB tự ép:

```sql
CREATE UNIQUE INDEX uq_one_active_map_per_venue
  ON venue_seat_maps (venue_id) WHERE status = 'ACTIVE';
```

**Publish + materialize** — đây là thao tác nặng nhất của milestone này:

```java
@Transactional
public void publish(UUID eventId) {
    var event = load(eventId);
    preflight(event);          // ném lỗi có code khớp A-PUBLISH checklist
    for (var session : event.sessions()) {
        materializeSeats(session);   // INSERT … SELECT, một câu lệnh
    }
    event.publish(clock.now());
    audit.log("EVENT_PUBLISHED", …);
    outbox.write("Event", eventId, "EventPublished", …);
}
```

Materialize làm bằng **một câu `INSERT … SELECT`**, không vòng lặp:

```sql
INSERT INTO session_seats (id, event_session_id, seat_map_seat_id, ticket_tier_id,
                           status, price_vnd_snapshot)
SELECT gen_random_uuid(), :sessionId, s.id, m.ticket_tier_id, 'AVAILABLE', t.unit_price_vnd
FROM seat_map_seats s
JOIN seat_tier_mapping m ON m.seat_map_seat_id = s.id AND m.event_session_id = :sessionId
JOIN ticket_tiers t ON t.id = m.ticket_tier_id
WHERE s.seat_map_id = :seatMapId
ON CONFLICT (event_session_id, seat_map_seat_id) DO NOTHING;
```

`DO NOTHING` cho phép re-publish sau unpublish mà không đụng ghế đã bán — đúng quy tắc trong `state-machines.md`.

**Preflight** phải trả đúng 3 mã lỗi mà UI A-PUBLISH map thành checklist đỏ: `NO_ACTIVE_BANK_ACCOUNT`, `SEATS_WITHOUT_TIER`, `INVALID_SALES_WINDOW`. Trả **tất cả** lỗi cùng lúc (mảng), không dừng ở lỗi đầu tiên — nếu không, organizer phải publish 3 lần mới biết hết vấn đề.

**Bank account.** `account_number` mã hoá bằng AES-GCM, khoá từ secret manager, lưu `BYTEA`; `account_number_last4` lưu rõ để hiển thị. Không bao giờ trả số đầy đủ qua API sau khi tạo. Mọi thay đổi `@Audited`.

**Exit gate 02:** organizer publish E2E, customer chỉ thấy `PUBLISHED`, IDOR catalog xanh, có audit row.

---

## 7. Milestone 03 — Seat & Checkout (tuần 7–10) — phần khó nhất

### 7.1 `GET /v1/sessions/{id}/seats`

Trả toàn bộ ghế + `version` cấp suất. Với suất 3.000 ghế, payload JSON ~400KB → **bắt buộc**:
- Gzip/Brotli (Spring `server.compression.enabled`).
- Rút gọn field: `{"i":"…","s":"A","r":"1","l":"01","st":"A","t":"…","p":1500000}` hoặc giữ tên dài nhưng bật nén — chọn **giữ tên dài + nén**, dễ debug hơn và nén xoá gần hết chênh lệch.
- `ETag` + `Cache-Control: no-cache` → client gửi `If-None-Match`, đa số lần trả `304`.

Đọc `version` và danh sách ghế trong **cùng một snapshot** (`REPEATABLE READ` hoặc đọc version trước rồi ghế), nếu không client có thể nhận version cũ hơn dữ liệu → bỏ mất delta.

### 7.2 `POST /v1/sessions/{id}/holds` — trái tim hệ thống

**Bước 1 — validate rẻ trước:** session đang mở bán, `sessionSeatIds` ≤ `MAX_SEATS_PER_HOLD` (8, enforce ở BE — H10), không trùng lặp, thuộc đúng session.

**Bước 2 — Lua script atomic:**

```lua
-- KEYS = hold:{sessionId}:seatId ...   ARGV = holdId, userId, ttlSeconds
for i = 1, #KEYS do
  if redis.call('EXISTS', KEYS[i]) == 1 then
    return {err = 'SEAT_UNAVAILABLE:' .. KEYS[i]}
  end
end
for i = 1, #KEYS do
  redis.call('SET', KEYS[i], ARGV[1] .. ':' .. ARGV[2], 'EX', tonumber(ARGV[3]))
end
return 1
```

Kiểm tra hết rồi mới ghi — không có trạng thái nửa vời. Cặp `{}` trong tên key là hash tag Redis Cluster, **không được bỏ**.

**Bước 3 — persist DB, cùng transaction:**

```sql
UPDATE session_seats SET status = 'HELD'
 WHERE id = ANY(:seatIds) AND status = 'AVAILABLE';   -- affected phải = N
INSERT INTO seat_holds (…) VALUES (…);
INSERT INTO seat_hold_items (…) VALUES (…);           -- unique index chặn oversell
SELECT nextval('seat_version_seq');                    -- version mới cho suất
```

`affected != N` → rollback + `DEL` các key Redis + trả `409 SEAT_UNAVAILABLE`. Đây là trường hợp Redis và DB lệch nhau (hiếm, do key hết hạn giữa chừng) — DB thắng.

**Bước 4 — broadcast** sau commit (`TransactionSynchronization.afterCommit`), không trước.

**Xử lý lỗi Redis:** `RedisConnectionFailureException` → `503 REDIS_UNAVAILABLE`, `Retry-After: 1`. **Không** fallback DB-only (ADR-0004). Redis phải có replica + failover (H9).

**Ngân sách p95 300ms:**

| Chặng | Ngân sách |
| --- | --- |
| Auth + tenant + idempotency | 15 ms |
| Lua script | 5 ms |
| DB transaction | 40 ms |
| Broadcast (async, sau commit) | 0 ms |
| Đệm | phần còn lại |

Nếu tuần 7 đo ra DB transaction > 100ms thì vấn đề nằm ở hot row version — chuyển sang `nextval` sequence (brainstorm §6.1 phương án C).

### 7.3 `POST /v1/orders`

```java
@Transactional
public OrderResponse create(CreateOrderCommand cmd) {
    var hold = holds.findActiveOwnedBy(cmd.holdId(), currentUser());   // HOLD_NOT_OWNED / HOLD_EXPIRED
    var seats = seatRepo.lockAllOrderedById(hold.seatIds());           // FOR UPDATE, ORDER BY id — chống deadlock
    require(seats.allHeldBy(hold), "SEAT_UNAVAILABLE");

    var promo = promotions.validateAndReserve(cmd.promotionCode());    // PROMOTION_INVALID
    var bank  = bankAccounts.preferredActive(hold.orgId());            // NO_BANK_ACCOUNT
    var ref   = paymentReference.generate();                           // NT + 8 ký tự Crockford Base32

    var order = Order.awaitingPayment(seats, promo, bank, ref,
                                      clock.now().plus(PAYMENT_WINDOW));
    seats.reserve();                     // HELD → RESERVED
    hold.convert();                      // ACTIVE → CONVERTED
    holdItems.markConverted();           // nhả unique index để hold sau dùng lại ghế nếu order expire
    payments.openAttempt(order);         // payment_attempts PENDING
    outbox.write("Order", order.id(), "OrderCreated", …);
    bumpSessionVersion(hold.sessionId());
    return toResponse(order, vietQr.build(bank, order.totalVnd(), ref));
}
```

Sau commit: `DEL` các key Redis hold (đã chuyển sang DB reservation, ADR-0015).

**VietQR payload** sinh phía server theo chuẩn EMVCo: dựng chuỗi TLV (`00`, `01`, `38` chứa GUID `A000000727` + bank BIN + số TK, `53`=`704` VND, `54`=số tiền, `58`=`VN`, `62`+`08`=nội dung chuyển khoản) rồi tính CRC-16/CCITT-FALSE 4 ký tự cuối. Trả **chuỗi payload**, FE tự render QR — nhẹ hơn trả ảnh và không phụ thuộc dịch vụ ngoài.

**`payment_reference`** (H8): `NT` + 8 ký tự Crockford Base32 (không có I, L, O, U để tránh đọc nhầm), sinh từ random 40 bit, unique index. Chỉ chữ hoa + số — sống sót qua ô "nội dung" của mọi app ngân hàng VN.

### 7.4 Webhook SePay — `POST /api/billing/bank/webhook/sepay`

Đường riêng, **không** qua `IdempotencyFilter`, **không** qua `TenantFilter`, security config riêng (không session, không CSRF).

```java
public ResponseEntity<Void> handle(String rawBody, HttpHeaders headers) {
    if (!sepayAuth.verify(rawBody, headers)) return status(401);      // 4xx: không retry

    var dto = parse(rawBody);
    boolean fresh = webhookEvents.recordIfNew("SEPAY", dto.transactionId(), sha256(rawBody));
    if (!fresh) return ok();                                          // DUPLICATE — luôn 2xx

    try   { processor.process(dto); return ok(); }                    // xem bảng ma trận dưới
    catch (TransientException e) { return status(503); }              // SePay retry
}
```

`processor.process` chạy trong transaction, `SELECT … FOR UPDATE` trên `orders` (chống race với expiry worker — brainstorm §4.4) và phân loại theo **ma trận ở brainstorm §4.2**. Nhắc lại nguyên tắc chốt:

> `REJECTED` chỉ khi chắc chắn không có tiền vào tài khoản ta. Có tiền mà không khớp ⇒ luôn `MANUAL_REVIEW`.

Khi `CONFIRMED`, cùng transaction: attempt → `CONFIRMED`, order → `PAID`, `session_seats` → `SOLD`, issue `tickets` cho từng `order_item`, ghi outbox `OrderPaid` + `TicketIssued`. `tickets.order_item_id UNIQUE` là chốt an toàn cuối cùng chống phát hành trùng.

**Test bắt buộc (tuần 9):** duplicate tuần tự, duplicate **song song**, sai reference, thiếu tiền, thừa tiền, sai tài khoản nhận, đến sau expiry, đến đúng lúc expiry worker chạy, payload rác, auth sai.

### 7.5 Phát hành ticket & QR (ADR-0009)

Token = JWS compact, thuật toán **EdDSA (Ed25519)** — chữ ký 64 byte, token ~180 ký tự, QR vẫn quét nhanh trong điều kiện ánh sáng kém ở cửa.

```json
{ "jti": "550e8400-…", "exp": 1793000000 }
```

**Chỉ có vậy** — không userId, không email, không seat label, không ticketId đoán được (H7). Scanner gửi token, server verify chữ ký + `exp`, tra `tickets` theo `jti`, kiểm tra org của staff. `kid` trong header cho phép xoay khoá mà không vô hiệu vé đã phát hành.

### 7.6 Worker

| Worker | Chu kỳ | Hành động |
| --- | --- | --- |
| Hold expiry | 10s | `seat_holds` ACTIVE quá hạn → `EXPIRED`, ghế → `AVAILABLE`, `DEL` Redis, bump version, broadcast |
| Payment expiry | 15s | `orders` `AWAITING_PAYMENT` quá `payment_expires_at` → `EXPIRED` (**`WHERE status='AWAITING_PAYMENT'`**), ghế → `AVAILABLE`, bump version, broadcast, outbox `OrderExpired` |
| Outbox publisher | 500ms | Publish RabbitMQ, `FOR UPDATE SKIP LOCKED` |
| Notification | tiêu thụ queue | Render + gửi email, retry mũ, N lần → DLQ + alert |
| Invariant check | 5 phút (staging) | Chạy query oversell §3.3, alert nếu ≠ 0 |

Tất cả chạy cùng process với profile `worker` (MVP), `SKIP LOCKED` để an toàn khi scale nhiều instance.

### 7.7 WebSocket

Endpoint `/ws/sessions/{sessionId}` — xác thực bằng access token trong query param hoặc `Sec-WebSocket-Protocol` (browser không gửi được custom header).

Kiến trúc fan-out (brainstorm §6.3):

```
commit DB → publish Redis channel seat.session.{id}
          → mọi API instance nhận → gom 200ms → đẩy xuống WS local
```

`SeatBroadcaster` giữ `Map<sessionId, Set<WebSocketSession>>` và một buffer per-session, flush mỗi 200ms thành **một** message chứa mảng `changes`. Không gửi một message cho mỗi ghế.

Backpressure: session nào có send buffer đầy → đóng kết nối (client tự reconnect + refetch), không để buffer nuốt hết heap.

### 7.8 Load test (tuần 10)

Script k6 trong `load/`, theo `03-seat-checkout/load-test-plan.md`: 10.000 VU, 1 hot session, mix 70/20/10. Chạy được từ **tuần 7** ở quy mô nhỏ (500 VU) để bắt regression sớm.

Sau mỗi lần chạy: query invariant §3.3, ghi kết quả vào `docs/03-seat-checkout/load-test-report.md`.

**Exit gate 03:** 0 oversell, p95 hold ≤ 300ms, webhook retry không sinh vé trùng, late payment → `MANUAL_REVIEW`.

---

## 8. Milestone 04 — Operations (tuần 11–12)

### 8.1 Check-in (ADR-0014)

```java
@Transactional
public CheckInResult checkIn(String qrToken) {
    var jti = qrSigner.verifyAndExtract(qrToken);                 // INVALID_TOKEN 400
    var ticket = tickets.findByJti(jti).orElseThrow(…);
    require(ticket.orgId().equals(currentTenant()), WRONG_ORGANIZATION);   // 403

    int updated = tickets.markCheckedIn(ticket.id());
    // UPDATE tickets SET status='CHECKED_IN' WHERE id=? AND status='VALID'
    if (updated == 1) {
        checkIns.insert(ticket.id(), currentUser(), clock.now());  // UNIQUE(ticket_id)
        outbox.write("Ticket", ticket.id(), "TicketCheckedIn", …);
        return CHECKED_IN;
    }
    return switch (ticket.status()) {
        case CHECKED_IN -> ALREADY_CHECKED_IN(checkIns.findBy(ticket.id()));
        default         -> throw new TicketNotValidException();     // 409
    };
}
```

`updated == 1` là điểm quyết định duy nhất — hai scanner quét cùng lúc thì chỉ một cái được `1`. Không cần lock riêng.

### 8.2 Metrics dashboard

`GET /v1/admin/metrics?eventId&sessionId&from&to` → GMV, orders theo status, seats theo status.

Cẩn thận: đây là aggregate trên bảng nóng. Với dữ liệu MVP (vài nghìn đơn) `COUNT`/`SUM` trực tiếp là đủ, nhưng phải có index `(organization_id, event_session_id, status)` và **giới hạn khoảng thời gian tối đa** (ví dụ 1 năm) để không ai quét toàn bảng.

### 8.3 Refund / resolve

```
POST /v1/admin/payments/{attemptId}/resolve   { decision: CONFIRM_PAID | MARK_REFUNDED | REJECT, reason }
POST /v1/admin/orders/{id}/refund             { reason }
```

`ORG_ADMIN+`, `reason` bắt buộc, `@Audited` before/after. `CONFIRM_PAID` đi qua **cùng use case** với webhook confirm — không viết đường code thứ hai để phát hành vé (nguyên tắc của `refunds.md`: không sửa DB tay, không đường tắt).

Refund sau `PAID` chưa check-in → order `REFUNDED`, tickets `REFUNDED`, ghế → `AVAILABLE` (hoặc `BLOCKED` theo policy org). Refund sau check-in → chỉ đánh dấu tài chính, ghế giữ nguyên.

### 8.4 Notification

Consumer RabbitMQ cho `OrderCreated` (hướng dẫn chuyển khoản + reference + hạn 15 phút), `OrderPaid`/`TicketIssued` (link my-tickets), `OrderExpired`, `ManualReviewOpened`. Template Thymeleaf, gửi qua SMTP/SES. Retry mũ 3 lần → DLQ + alert. Không log nội dung email.

**Exit gate 04:** first scan `CHECKED_IN` / rescan `ALREADY_CHECKED_IN`, resolve có audit, runbook drill có biên bản.

---

## 9. Milestone 05 — Analytics (song song từ tuần 7)

Consumer đọc outbox events → chuẩn hoá theo `05-ai-scale/event-taxonomy.md` → ghi bảng `analytics_events`.

Bắt buộc: dùng `analytics_subject_id` (không phải `user_id`), payload **không** chứa email/phone/tên. ADR-0012: không có đường phụ thuộc đồng bộ nào từ checkout sang analytics — nếu consumer chết, bán vé vẫn chạy.

---

## 10. Test strategy

| Tầng | Công cụ | Cover cái gì |
| --- | --- | --- |
| Unit | JUnit 5 | Pricing/promo, state machine (seat/hold/order/payment/ticket), parser webhook, CRC VietQR, sinh & verify QR token, `payment_reference` |
| Integration | Testcontainers PG + Redis + RabbitMQ | **Trọng tâm.** Hold atomic, order transaction, webhook đủ nhánh, idempotency, outbox, tenant/IDOR, expiry worker, check-in |
| Concurrency | JUnit + `ExecutorService` | 100 thread hold cùng ghế → đúng 1 thắng; 100 webhook song song → 1 tác dụng; 2 scanner cùng vé → 1 `CHECKED_IN` |
| Architecture | ArchUnit | 5 luật ở §2 |
| Contract | openapi-generator + WireMock | Response khớp OpenAPI; SePay adapter khớp payload mẫu |
| Load | k6 | §7.8 |
| Security | Test tự sinh + gitleaks + OWASP dep-check | IDOR toàn route, webhook replay, rate limit, không secret |

Nhóm test concurrency là nhóm **duy nhất** chứng minh được invariant "không oversell". Viết nó ở tuần 7, không phải tuần 10.

## 11. Cấu hình & vận hành

```yaml
# application.yml (trích)
nexaticket:
  hold:    { ttl: 5m, max-seats: 8 }
  payment: { window: 15m }
  idempotency: { ttl: 24h, retention: 7d }
  ws: { coalesce-window: 200ms, max-sessions-per-node: 15000 }
  sepay: { webhook-secret: ${SEPAY_WEBHOOK_SECRET}, allowed-ips: ${SEPAY_IPS:} }
  qr: { signing-key: ${TICKET_QR_SIGNING_KEY}, kid: v1 }
```

**Profile:** `local`, `staging`, `prod`, `worker`. Worker bật/tắt bằng profile để sau này tách process mà không đổi code.

**Connection pool:** HikariCP, `maximum-pool-size` = `(số core × 2) + số disk` — với 10k VU, pool 30–50 là đủ; pool to hơn **không** giúp mà làm tệ hơn (DB context switch).

**Alert (ADR-0011):** hold error rate, webhook 5xx, consumer lag, oversell invariant ≠ 0, check-in rejection spike, `MANUAL_REVIEW` đang mở > ngưỡng.

## 12. Thứ tự làm — nếu chỉ được chọn một đường

Nếu tiến độ căng, đây là thứ tự ưu tiên tuyệt đối (bỏ từ dưới lên):

1. Tenant guard + idempotency + outbox (không có thì mọi thứ trên nó đều sai).
2. Hold atomic + unique index chống oversell.
3. Order + VietQR + webhook đủ 8 nhánh.
4. Issue ticket + check-in.
5. Publish/materialize + catalog public.
6. Admin CRUD (venue, seat map, event wizard).
7. Dashboard, refund UI, notification.
8. Analytics.

Mục 1–4 là sản phẩm. Mục 5–6 có thể làm tay bằng SQL seed trong thời gian ngắn nếu buộc phải hoãn. Mục 7–8 cắt được theo đúng thứ tự cắt scope của `DELIVERY-PLAN-3M.md`.
