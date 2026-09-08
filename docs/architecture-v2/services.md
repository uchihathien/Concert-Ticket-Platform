# Danh mục service

11 service nghiệp vụ + 1 gateway. Mỗi service = 1 bounded context = 1 database = 1 pipeline.

Quy ước chung:
- Stack: Java 21, Spring Boot 3.5, PostgreSQL riêng, Flyway riêng.
- Đồng bộ: REST qua gateway; nội bộ gọi nhau bằng HTTP + Resilience4j (circuit breaker, retry có jitter, bulkhead, timeout **luôn có**).
- Bất đồng bộ: **RabbitMQ** (quorum queue, publisher confirms), transactional outbox ở mọi service ghi dữ liệu. Outbox giữ vĩnh viễn và là nhật ký sự kiện — [ADR-1009](adr/ADR-1009-rabbitmq-only.md).
- Mọi endpoint mutation: `Idempotency-Key`. Mọi consumer idempotent theo `eventId` và **không phụ thuộc thứ tự message**.
- Mọi service: `/actuator/health`, OTel, structured log có `correlationId` + `causationId`.

---

## Mô hình vai trò

| Vai trò | Phạm vi | Quyền chính |
| --- | --- | --- |
| **`SUPER_ADMIN`** | Nền tảng | **Tạo/khoá tổ chức; toàn quyền trên tiền**: tài khoản ký quỹ, đối soát, chi trả, sổ cái, hoàn tiền |
| `ORG_OWNER` | Tổ chức | Như `ORG_ADMIN` + chuyển quyền sở hữu |
| `ORG_ADMIN` | Tổ chức | Quản lý thành viên, venue, sự kiện, khuyến mãi |
| `EVENT_MANAGER` | Tổ chức | Venue, seat map, sự kiện, suất diễn, hạng vé |
| `CHECKIN_STAFF` | Tổ chức | Chỉ soát vé |
| `CUSTOMER` | Bản thân | Mua vé, xem vé của mình |

`SUPER_ADMIN` là tên gọi mới của `PLATFORM_ADMIN` trong [RBAC matrix v1](../00-discovery/rbac-permission-matrix.md), với quyền hạn mở rộng sang toàn bộ miền tài chính.

**Đường ranh cứng:** không vai trò nào của tổ chức chạm được vào sổ cái, tài khoản ngân hàng, hay chi trả. Tổ chức chỉ thấy **số vé đã bán và số tiền đã bán** ([ADR-1010](adr/ADR-1010-superadmin-tenancy-and-finance-visibility.md)).

---

## 0. api-gateway

| | |
| --- | --- |
| **Công nghệ** | Spring Cloud Gateway |
| **Trách nhiệm** | Route, xác thực JWT (JWKS Keycloak), rate limit, CORS, sinh `correlationId`, tổng hợp OpenAPI |
| **Không làm** | Business logic, tra cứu tenant, biến đổi payload |

Rate limit: public theo IP, authenticated theo `userId + organizationId`, Redis token bucket.

**Gateway không quyết định quyền.** Nó chỉ xác minh token hợp lệ. Việc "user này có phải thành viên org này không" do từng service tự kiểm tra — gateway không được là điểm tin cậy duy nhất.

---

## 1. identity-service

| | |
| --- | --- |
| **Aggregate** | `Organization`, `User`, `Invitation`, `ScannerAccessCode` |
| **Database** | `identity_db` |
| **Ngoài** | Keycloak (OIDC, MFA) |

### Bảng sở hữu

`organizations`, `organization_profiles`, `users`, `organization_members`, `invitations`, `scanner_access_codes`

`organizations.status` chỉ còn **`ACTIVE` | `SUSPENDED`**. Máy trạng thái KYC của ADR-1007 đã bị gỡ — superadmin thẩm định trước khi tạo, nên phần mềm không cần cổng kiểm soát tự động ([ADR-1010 §2](adr/ADR-1010-superadmin-tenancy-and-finance-visibility.md)).

### API — Superadmin tạo tổ chức

```
POST   /v1/platform/organizations          # SUPER_ADMIN — tạo tổ chức + mời chủ sở hữu
GET    /v1/platform/organizations
PATCH  /v1/platform/organizations/{id}
POST   /v1/platform/organizations/{id}/suspend | /activate
```

```
SUPER_ADMIN ──POST /v1/platform/organizations
              { name, slug, ownerEmail, profile: { legalName, taxCode, … } }
          ──> Organization(status = ACTIVE)
            + Invitation(role = ORG_OWNER, email = ownerEmail)
          ──> gửi email mời
```

**Không có endpoint tự tạo tổ chức.** `POST /v1/organizations` không tồn tại.

### API — Trong phạm vi tổ chức

```
GET    /v1/me/organizations                       # các org tôi là thành viên
GET    /v1/organizations/{id}
PATCH  /v1/organizations/{id}                     # ORG_ADMIN+ — chỉ thông tin hiển thị
POST   /v1/organizations/{id}/invitations         # ORG_ADMIN+ — mời thành viên
POST   /v1/invitations/{token}/accept
GET    /v1/organizations/{id}/members
PATCH  /v1/organizations/{id}/members/{userId}    # đổi role
DELETE /v1/organizations/{id}/members/{userId}
GET    /v1/me                                     # + PATCH

# Open Host Service — chỉ service nội bộ
GET    /internal/memberships?userId=&organizationId=
GET    /internal/organizations/{id}/summary
```

Tổ chức **không** sửa được `slug`, `status`, hay hồ sơ pháp nhân — đó là dữ liệu của superadmin.

### Thêm nhân viên soát vé

Hai đường, tổ chức tự chọn — cả hai đều thuộc quyền `ORG_ADMIN`, không cần superadmin:

**A. Mời qua email** — nhân viên dài hạn, dùng lại nhiều sự kiện.
```
ORG_ADMIN ──POST /invitations { email, role: CHECKIN_STAFF } ──> email chứa token
Nhân viên login OIDC ──POST /invitations/{token}/accept ──> Membership
```
Token hết hạn 7 ngày, dùng một lần, lưu dạng hash.

**B. Mã truy cập theo suất diễn** — nhân viên thời vụ ngày sự kiện ([ADR-1008](adr/ADR-1008-scanner-access-codes.md)).
```
POST /v1/organizations/{id}/scanner-codes { sessionId, expiresAt, maxDevices }
  ──> mã 8 ký tự + QR
Nhân viên nhập mã trên web-scanner ──> token phạm vi hẹp:
    scope = check-in, giới hạn đúng 1 suất diễn, hết hạn theo giờ sự kiện
DELETE /v1/organizations/{id}/scanner-codes/{codeId}   # thu hồi tức thì
```

Mỗi thiết bị một token riêng để truy vết ai đã quét vé nào. Mọi lần phát hành/thu hồi ghi audit.

### Event phát ra
`OrganizationCreated`, `OrganizationSuspended`, `OrganizationActivated`, `MemberInvited`, `MemberJoined`, `MemberRoleChanged`, `MemberRemoved`, `ScannerCodeIssued`, `ScannerCodeRevoked`

---

## 2. catalog-service

| | |
| --- | --- |
| **Aggregate** | `Venue` (gồm `VenueLayoutVersion`, `VenueZone`, `VenueFixedSeat`), `EventSeatingPlan`, `Event` (gồm `EventSession`, `TicketTier`), `Promotion` |
| **Database** | `catalog_db` |

Đây là nơi tổ chức làm ba việc chính của mình: **chọn địa điểm, thiết kế khu vực ghế, tạo sự kiện**.

Mô hình địa điểm đầy đủ ở [venue-seating-model.md](venue-seating-model.md). Tóm tắt: địa điểm có `scope = PLATFORM` (superadmin sở hữu, dùng chung) hoặc `ORGANIZATION` (tổ chức tự tạo, riêng). Khu vực `FIXED` có chỗ áp cứng mà `EventSeatingPlan` **không sửa được**; khu vực `FLEXIBLE` để tổ chức **tự thiết kế theo từng sự kiện**. Mỗi khu vực là `SEATED` (ghế đánh số) hoặc `STANDING` (vé đứng), nên một sự kiện có thể toàn ghế ngồi, hỗn hợp, hoặc chỉ đứng.

### API

```
# ===== Superadmin: địa điểm dùng chung + ghế áp cứng =====
POST|GET|PATCH /v1/platform/venues[/{id}]
POST /v1/platform/venues/{id}/layout-versions
POST /v1/platform/layout-versions/{id}/zones          # kind = FIXED | FLEXIBLE
POST /v1/platform/zones/{id}/fixed-seats:bulk         # grid hoặc CSV
POST /v1/platform/layout-versions/{id}/activate
POST /v1/platform/venues/{id}/promote                 # nâng venue riêng lên dùng chung
GET  /v1/platform/venue-conflicts                     # hàng đợi trùng lịch

# ===== Tổ chức: địa điểm (dùng chung để chọn, riêng để tự dựng) =====
GET  /v1/admin/venues?scope=all&city=                 # dùng chung + riêng của mình
GET  /v1/admin/venues/{id}/active-layout              # zone nào FIXED/FLEXIBLE, SEATED/STANDING
POST|GET|PATCH /v1/admin/venues[/{id}]                # tạo/sửa venue scope=ORGANIZATION
POST /v1/admin/venues/{id}/layout-versions            # chỉ trên venue riêng
POST /v1/admin/layout-versions/{id}/zones             # chỉ trên venue riêng
POST /v1/admin/zones/{id}/fixed-seats:bulk            # chỉ trên venue riêng

# ===== Tổ chức: thiết kế chỗ ngồi cho một suất diễn =====
POST /v1/admin/sessions/{id}/seating-plan             # ghim layout version
PUT  /v1/admin/seating-plans/{id}/zone-usages         # bật/tắt zone, hạng vé, standingCapacity

# ===== Trần mua vé (ADR-1014) =====
GET|PUT /v1/platform/purchase-limits                  # SUPER_ADMIN — trần cứng nền tảng
GET|PUT /v1/admin/purchase-limits                     # ORG_ADMIN+ — mặc định của tổ chức
GET|PUT /v1/admin/sessions/{id}/purchase-limits       # EVENT_MANAGER+ — ghi đè theo suất
                                                      # GET trả giá trị hiệu lực + nguồn kế thừa
POST /v1/admin/seating-plans/{id}/blocks              # khối ghế trong zone FLEXIBLE
PUT  /v1/admin/seating-plans/{id}/overrides           # REMOVE | BLOCK | SET_TIER
POST /v1/admin/seating-plans/{id}/validate
GET  /v1/admin/seating-plans/{id}/preview             # xem ghế sẽ sinh ra, chưa ghi DB
POST /v1/admin/seating-plans/{id}/save-as-template
POST /v1/admin/seating-plans/{id}/apply-template/{tid}

# ===== Sự kiện =====
POST|GET|PATCH /v1/admin/events[/{id}]
POST /v1/admin/events/{id}/sessions
POST /v1/admin/sessions/{id}/tiers
POST /v1/admin/events/{id}/publish | /unpublish
POST|GET|PATCH /v1/admin/promotions

# ===== Public =====
GET  /v1/events?query&city&from&to&page
GET  /v1/events/{slug}

# ===== Nội bộ =====
GET  /internal/sessions/{id}/pricing
GET  /internal/promotions/{code}/validate
```

`PUT /sessions/{id}/seat-tiers` của v1 bị thay bằng bộ ba `zone-usages` + `blocks` + `overrides` — gán hạng vé theo khu vực và khối thay vì theo từng ghế, với ghi đè khi cần.

### Publish — preflight 4 mục

| Mục kiểm | Mã lỗi |
| --- | --- |
| Seating plan hợp lệ (sức chứa, trùng mã ghế) | `INVALID_SEATING_PLAN` |
| Có ít nhất một zone được bật | `NO_ZONE_INCLUDED` |
| Mọi chỗ bán được đã có hạng vé | `SEATS_WITHOUT_TIER` |
| Sales window hợp lệ | `INVALID_SALES_WINDOW` |

Mã `NO_ACTIVE_BANK_ACCOUNT` của v1 **bị gỡ bỏ**: tiền vào tài khoản ký quỹ của nền tảng, luôn tồn tại, nên tổ chức không cần cấu hình tài khoản nào để publish ([ADR-1010 §5](adr/ADR-1010-superadmin-tenancy-and-finance-visibility.md)). Trả tất cả lỗi cùng lúc, không dừng ở lỗi đầu tiên.

### Publish là saga, không còn là một transaction

```
catalog:   preflight OK → status = PUBLISHING
           → outbox: EventPublishRequested { seatingPlan đã phẳng hoá thành danh sách ghế }
                                                  ↓ RabbitMQ
inventory: materialize session_seats (INSERT…SELECT, ON CONFLICT DO NOTHING)
                                                  ↓ EventSeatsMaterialized
catalog:   status = PUBLISHED, published_at = now
```

Hệ quả UI: A-PUBLISH phải hiển thị **"Đang xuất bản…"** rồi mới chuyển sang "Đang bán". Với 5.000 ghế, materialize mất vài giây.

### Event phát ra
`EventPublishRequested`, `EventPublished`, `EventUnpublished`, `EventCancelled`, `VenueLayoutActivated`, `SeatingPlanReady`, `TicketTierChanged`, `PromotionChanged`

---

## 3. inventory-service — **Core**

| | |
| --- | --- |
| **Aggregate** | `SessionInventory`, `SeatHold`, `SeatReservation` |
| **Database** | `inventory_db` |
| **Hạ tầng** | Redis (Lua hold) |
| **Scaling** | Nhiều instance, không sticky; điểm nghẽn là DB + hot row version |

### Bảng sở hữu
`session_inventory` (`id`, `event_id`, `organization_id`, `sales_window`, `availability_version`, **trần mua vé đã giải quyết kế thừa**), `session_seats` (+ `holder_user_id`), `seat_holds`, `seat_hold_items`, `seat_reservations`

Inventory **giữ bản sao** `organization_id`, `seat_code`, `zone_code`, `admission_type`, nhãn chỗ, toạ độ và giá — event-carried state transfer, không phải trùng lặp sai. Inventory **không biết** ghế đến từ khu vực áp cứng hay từ thiết kế của tổ chức; sau materialize nó chỉ thấy một danh sách ghế phẳng. Nó cho phép trả seat map mà không gọi Catalog, điều bắt buộc ở 10k đồng thời.

### API

```
GET    /v1/sessions/{id}/seats                  # + ETag, gzip; ghế đánh số + tóm tắt tồn kho vé đứng theo zone
POST   /v1/sessions/{id}/holds                  # Idempotency-Key; { seats[], standing[{zoneCode, quantity}] }
DELETE /v1/holds/{id}
GET    /v1/holds/{id}
POST   /internal/reservations                   # Ordering gọi: hold → reservation
DELETE /internal/reservations/{orderId}         # bù trừ khi saga hỏng
POST   /internal/reservations/{orderId}/settle  # → SOLD sau khi PAID
```

Thuật toán hold cho **vé ngồi** (Redis Lua + unique index + `FOR UPDATE` có sắp thứ tự) giữ **nguyên như v1** — xem [plan/backend-spring-boot.md §7](../plan/backend-spring-boot.md).

**Vé đứng** thêm một đường cấp phát riêng: đơn vị tồn kho ảo, chọn bằng `SELECT … FOR UPDATE SKIP LOCKED` ([ADR-1012](adr/ADR-1012-standing-admission-inventory.md)). Không dùng Redis vì khách không chỉ đích danh chỗ nào, nên không có gì để từ chối nhanh. **Chốt chặn oversell y hệt cho cả hai đường**: unique index trên `seat_hold_items`. Một lần giữ chỗ chứa được cả hai loại.

**Trần mua vé** ([ADR-1014](adr/ADR-1014-configurable-purchase-limits.md)) thực thi tại đây, không gọi service khác:
- Trần mỗi lần giữ chỗ đọc từ `session_inventory` (đã sao lúc materialize).
- Trần cộng dồn mỗi tài khoản đếm bằng `session_seats.holder_user_id` — một câu đếm có index trong cùng bảng.
- `pg_advisory_xact_lock(user, session)` chống người dùng mở hai tab; không tạo tranh chấp giữa các người dùng khác nhau.

Cả hai vẫn nằm gọn trong một service, một database, một transaction. Đó chính là lý do ranh giới này đúng.

### Event phát ra
`SeatsHeld`, `SeatsReleased`, `SeatsReserved`, `SeatsSold`, `EventSeatsMaterialized` — qua outbox.
`AvailabilityChanged` — **không qua outbox**, publish trực tiếp sau commit vào fanout exchange (dữ liệu tạm, tần suất cao, mất được).

---

## 4. realtime-gateway

| | |
| --- | --- |
| **Trách nhiệm** | Giữ WebSocket của khách, đẩy `AvailabilityChanged` |
| **Trạng thái** | Không có DB — chỉ consumer + bản đồ kết nối trong bộ nhớ |
| **Scaling** | Theo **số kết nối**, không theo QPS |

Tách khỏi inventory-service vì hồ sơ tài nguyên khác hẳn: 10.000 kết nối WebSocket cần nhiều RAM và file descriptor nhưng gần như không tốn CPU, trong khi inventory-service cần CPU và kết nối DB. Gộp chung buộc phải scale cả hai theo chiều xấu nhất của cả hai.

### Fan-out bằng RabbitMQ

```
Exchange nexaticket.availability (fanout, durable)
Queue rt-gw.{instanceId}: exclusive, auto-delete,
      x-message-ttl = 30s, x-max-length = 10000, x-overflow = drop-head
```

Mỗi instance khai báo một queue tạm riêng nên **mọi instance đều nhận mọi message** — cần thiết vì client nào nối vào instance nào là ngẫu nhiên. Queue tự xoá khi instance chết.

TTL 30 giây và giới hạn độ dài là cố ý: dữ liệu khả dụng ghế cũ vô giá trị, thà bỏ còn hơn dồn ứ — client phát hiện nhảy version sẽ tự refetch.

Sau khi nhận: gom 200ms mỗi suất diễn thành **một** message rồi mới đẩy xuống WS. Không gửi một message cho mỗi ghế.

---

## 5. ordering-service

| | |
| --- | --- |
| **Aggregate** | `Order` (gồm `OrderItem`), `CheckoutSaga` |
| **Database** | `ordering_db` |

Mỏng về nghiệp vụ, dày về **điều phối** — là saga orchestrator của checkout ([sagas.md](sagas.md)).

### API

```
POST /v1/orders                     # holdId + promotionCode → order + VietQR
GET  /v1/orders/{id}
GET  /v1/me/orders
POST /v1/orders/{id}/cancel
GET  /internal/orders/{id}          # Ledger/Payout tra chứng từ gốc
```

`OrderItem` lưu snapshot: nhãn ghế, tên hạng vé, đơn giá, giảm giá, **và phần hoa hồng nền tảng tính tại thời điểm bán**. Snapshot hoa hồng là bắt buộc — đổi biểu phí sau này không được làm sai sổ sách của đơn cũ.

### Event phát ra
`OrderCreated`, `OrderPaid`, `OrderExpired`, `OrderCancelled`, `OrderRefundRequested`, `OrderRefunded`

---

## 6. payment-service

| | |
| --- | --- |
| **Aggregate** | `PaymentIntent` (gồm `PaymentAttempt`), `WebhookEvent` |
| **Database** | `payment_db` |
| **Ngoài** | SePay (ACL), VietQR (sinh payload nội bộ) |
| **Quyền quản trị** | **Chỉ `SUPER_ADMIN`** |

VietQR trỏ về **tài khoản ký quỹ của NexaTicket**, cấu hình cấp nền tảng do superadmin quản lý. Tổ chức không cấu hình tài khoản nhận tiền — khái niệm đó không còn tồn tại phía tổ chức.

```
PaymentIntent {
  orderId, organizationId,        # chỉ để Ledger phân bổ công nợ
  expectedAmountVnd,
  paymentReference,               # NT + 8 ký tự Crockford Base32
  escrowBankAccountId,            # TK ký quỹ nền tảng
  expiresAt
}
```

### API

```
POST /internal/payment-intents                    # Ordering gọi
GET  /v1/orders/{orderId}/payment                  # khách poll trạng thái đơn của mình
POST /api/billing/bank/webhook/sepay               # SePay — ngoài /v1, không auth người dùng

# SUPER_ADMIN
GET  /v1/platform/escrow-accounts                  # + POST|PATCH
GET  /v1/platform/payments/review                  # hàng đợi MANUAL_REVIEW
POST /v1/platform/payments/{attemptId}/resolve
```

Xử lý webhook, bảng dedupe `webhook_events`, ma trận 8 nhánh phân loại: giữ nguyên thiết kế v1 ([BRAINSTORM §4](../BRAINSTORM.md), [plan/backend §7.4](../plan/backend-spring-boot.md)). Nguyên tắc chốt vẫn là: **`REJECTED` chỉ khi chắc chắn không có tiền vào tài khoản ta; có tiền mà không khớp thì luôn `MANUAL_REVIEW`.**

Khi `CONFIRMED`, payment-service **không** tự phát hành vé — nó chỉ phát `PaymentConfirmed` và để saga chạy tiếp.

### Anti-Corruption Layer

```
infrastructure/sepay/SePayWebhookPayload.java      ← hình dạng của SePay
infrastructure/sepay/SePayPayloadTranslator.java   ← dịch sang domain
domain/BankTransferReceived.java                   ← ngôn ngữ của ta
```

Không một trường nào tên theo SePay được phép đi quá `SePayPayloadTranslator`. Đổi nhà cung cấp = viết translator mới, domain không đổi một dòng.

### Event phát ra
`PaymentIntentCreated`, `PaymentConfirmed`, `PaymentRejected`, `PaymentUnmatched`, `PaymentManualReviewOpened`, `PaymentResolved`

---

## 7. ledger-service — **Core**

| | |
| --- | --- |
| **Aggregate** | `LedgerAccount`, `JournalEntry` (gồm `Posting`) |
| **Database** | `ledger_db` — quyền truy cập hẹp nhất hệ thống |
| **Quyền đọc** | **Chỉ `SUPER_ADMIN`** |

Thiết kế đầy đủ ở [custodial-funds.md](custodial-funds.md). Vai trò:

- Nguồn chân lý duy nhất về tiền. Không service nào khác lưu số dư.
- Append-only ở cấp quyền database: `REVOKE UPDATE, DELETE ON postings`.
- Nhận `PaymentConfirmed` / `OrderRefunded` / `PayoutCompleted` → ghi bút toán.
- Trả lời số dư khả dụng cho payout-service.

### API — toàn bộ là platform

```
GET  /v1/platform/organizations/{id}/statement?from&to    # sao kê chi tiết — SUPER_ADMIN
GET  /v1/platform/trial-balance?asOf                       # bảng cân đối thử
GET  /v1/platform/reconciliation/{date}
GET  /v1/platform/accounts/{id}/postings
POST /internal/journal-entries                             # chỉ service nội bộ, idempotent
GET  /internal/accounts/{ownerType}/{ownerId}/balance
```

**Không có endpoint nào cho tổ chức.** Sao kê sổ cái là dữ liệu của superadmin; tổ chức nhận bảng kê thanh toán qua quy trình đối soát ngoài hệ thống ([ADR-1010](adr/ADR-1010-superadmin-tenancy-and-finance-visibility.md) — hệ quả #2).

**Không có endpoint nào sửa hay xoá bút toán.** Sửa sai = ghi một bút toán đảo có tham chiếu tới bút toán gốc.

### Consumer chạy tuần tự

`ledger.*` cấu hình `concurrency = 1`, `prefetch = 1`. Sổ cái có lưu lượng thấp (theo tốc độ chuyển khoản ngân hàng, không phải tốc độ bấm nút), nên một consumer là quá đủ và đổi lại được tính tất định — bù cho việc RabbitMQ không bảo đảm thứ tự ([ADR-1009 §4](adr/ADR-1009-rabbitmq-only.md)).

---

## 8. payout-service — **công cụ nội bộ của superadmin**

| | |
| --- | --- |
| **Aggregate** | `PayoutAccount`, `PayoutRequest`, `PayoutBatch` |
| **Database** | `payout_db` |
| **Quyền** | **Chỉ `SUPER_ADMIN`** |

Đây là nơi ở mới của `bank_accounts` từ v1, nhưng đổi cả ý nghĩa lẫn chủ sở hữu: **đích chi trả, do superadmin nhập và quản lý**.

### API — không có endpoint nào cho tổ chức

```
POST|GET|PATCH /v1/platform/organizations/{id}/payout-accounts
GET  /v1/platform/organizations/{id}/balance          # khả dụng / đang giữ / dự phòng / đang chuyển
POST /v1/platform/payouts                              # superadmin khởi tạo chi trả
GET  /v1/platform/payouts
POST /v1/platform/payout-batches                       # gom lô
POST /v1/platform/payout-batches/{id}/approve          # nguyên tắc bốn mắt
POST /v1/platform/payout-batches/{id}/mark-completed   # xác nhận đã chuyển khoản
GET  /v1/platform/organizations/{id}/settlement-report?from&to   # bảng kê gửi tổ chức
```

`settlement-report` là điểm bổ sung quan trọng: sinh **từ sổ cái**, gồm sự kiện, số vé, doanh thu gộp, hoa hồng, số thực nhận. Đây là thứ superadmin gửi cho tổ chức thay cho việc mở quyền xem sổ cái. Không có nó, đội vận hành sẽ phải trả lời thủ công từng email hỏi tiền.

### Cổng chặn trước khi chi trả

```
1. Sự kiện đã kết thúc + hold period?      không → HOLD_PERIOD_NOT_ELAPSED
2. Đủ số dư khả dụng (2012)?               không → INSUFFICIENT_BALANCE
3. Đối soát ngân hàng hôm trước đã đóng?   không → RECONCILIATION_PENDING
4. Tổ chức không bị SUSPENDED?             không → ORGANIZATION_SUSPENDED
5. balance(1320) phải thu = 0?             không → OUTSTANDING_RECEIVABLE
6. Tên chủ TK khớp hồ sơ pháp nhân?        không → PAYOUT_ACCOUNT_NAME_MISMATCH
```

Cổng `KYC_REQUIRED` của ADR-1007 đã gỡ — superadmin thẩm định trước khi tạo tổ chức, nên cổng đó giờ là con người, không phải phần mềm.

MVP: chi trả **thủ công có phê duyệt** — payout-service tạo lô, người vận hành chuyển khoản qua ngân hàng, rồi xác nhận vào hệ thống. Tự động hoá bằng API ngân hàng chỉ làm sau khi quy trình thủ công đã chạy ổn định vài tháng.

**Nguyên tắc bốn mắt:** lô vượt ngưỡng (ví dụ 100 triệu) cần hai người duyệt khác nhau; người tạo lô không được là người duyệt.

---

## 9. ticketing-service

| | |
| --- | --- |
| **Aggregate** | `Ticket`, `CheckIn` |
| **Database** | `ticketing_db` |

### API

```
GET  /v1/me/tickets
GET  /v1/tickets/{id}
POST /v1/check-ins                                          # CHECKIN_STAFF+ hoặc scanner code token
GET  /v1/organizations/{id}/sessions/{sid}/check-in-stats   # dữ liệu vận hành, tổ chức xem được
GET  /internal/organizations/{id}/tickets-sold?…            # ordering/reporting gọi
```

Nhận `OrderPaid` → phát hành vé (idempotent theo `orderItemId`), ký QR EdDSA, phát `TicketsIssued`.

Check-in: `UPDATE tickets SET status='CHECKED_IN' WHERE id=? AND status='VALID'` + insert `check_ins` unique — nguyên như v1 (ADR-0014). `updated == 1` là điểm quyết định duy nhất; hai scanner quét cùng lúc thì chỉ một cái được `1`.

**Thực thi quyền soát vé:** ticket mang `organizationId` copy từ sự kiện lúc phát hành. Staff quét vé của tổ chức khác → `WRONG_ORGANIZATION`. Với token từ mã truy cập theo suất, kiểm thêm `sessionId` khớp.

### Event phát ra
`TicketsIssued`, `TicketCheckedIn`, `TicketCancelled`, `TicketRefunded`

---

## 10. reporting — nằm trong ticketing + ordering

Không phải service riêng. Endpoint mà **tổ chức** được phép gọi, và là toàn bộ những gì tổ chức thấy về tiền:

```
GET /v1/organizations/{id}/sales-summary?eventId&sessionId&from&to
```

```json
{
  "ticketsSold": 1240,
  "grossSalesVnd": 1860000000,
  "breakdown": [
    { "eventId": "…", "sessionId": "…", "ticketsSold": 320, "grossSalesVnd": 480000000 }
  ]
}
```

Chỉ tính vé đã phát hành từ đơn `PAID`; vé đã hoàn tiền bị trừ khỏi cả hai con số. **Không** trả hoa hồng, số dư, trạng thái chi trả, hay bất kỳ trường sổ cái nào.

Dữ liệu vận hành tổ chức vẫn được xem (ghế còn trống theo trạng thái, lượt check-in) — không phải thông tin tài chính, và thiếu chúng thì tổ chức không bán vé và soát vé được.

---

## 11. notification-service

Consumer thuần, không API công khai. Nghe `OrderCreated`, `OrderPaid`, `TicketsIssued`, `OrderExpired`, `PaymentManualReviewOpened`, `MemberInvited`, `OrganizationCreated`. Template Thymeleaf, retry qua delayed exchange, DLQ + alert. Không log nội dung có PII.

## 12. analytics-service

Consumer thuần → `analytics_db` theo taxonomy `05-ai-scale/event-taxonomy.md`. Dùng `analyticsSubjectId`, không dùng `userId`. ADR-0012 giữ nguyên: không có đường phụ thuộc đồng bộ nào từ checkout.

---

## Bảng tổng hợp

| Service | Loại | DB | Ai truy cập | Ưu tiên |
| --- | --- | --- | --- | --- |
| api-gateway | Edge | — | tất cả | Cao |
| identity | Generic | `identity_db` | superadmin tạo org; org quản lý thành viên | Cao |
| catalog | Supporting | `catalog_db` | org (venue, ghế, sự kiện) | Trung bình |
| **inventory** | **Core** | `inventory_db` | khách + nội bộ | **Cao nhất** |
| realtime-gateway | Edge | — | khách | Cao |
| ordering | Supporting | `ordering_db` | khách + nội bộ | Cao |
| payment | Generic+ | `payment_db` | **chỉ superadmin** + webhook | Cao |
| **ledger** | **Core** | `ledger_db` | **chỉ superadmin** | **Cao nhất** |
| payout | Supporting | `payout_db` | **chỉ superadmin** | Trung bình |
| ticketing | Supporting | `ticketing_db` | khách + staff org | Cao |
| notification | Generic | `notification_db` | — | Thấp |
| analytics | Generic | `analytics_db` | — | Thấp |

Ba service `payment`, `ledger`, `payout` gộp thành deployable `finance`, tách mạng và tách quyền khỏi phần còn lại. Đây là ranh giới bảo mật quan trọng nhất của hệ thống.
