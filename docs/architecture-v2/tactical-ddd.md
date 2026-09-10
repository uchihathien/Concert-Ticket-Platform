# DDD chiến thuật — Aggregate, Value Object, Domain Event

Cách viết code bên trong mỗi service. Mục tiêu: nghiệp vụ nằm trong `domain`, framework nằm ngoài, và ranh giới transaction trùng với ranh giới aggregate.

---

## 1. Kiến trúc hexagonal trong một service

```text
com.nexaticket.<context>
├── domain/                       ← KHÔNG có import org.springframework / jakarta.persistence
│   ├── model/                    aggregate root, entity, value object
│   ├── event/                    domain event
│   ├── service/                  domain service (logic không thuộc riêng aggregate nào)
│   └── port/                     interface repository + port ra ngoài
├── application/
│   ├── command/                  command + handler, @Transactional ở đây
│   ├── query/                    read model, được phép bỏ qua aggregate và đọc thẳng
│   └── saga/                     orchestrator (chỉ ordering-service)
├── infrastructure/
│   ├── persistence/              JPA entity + mapper + repository adapter
│   ├── messaging/                RabbitMQ producer/consumer, outbox
│   ├── client/                   HTTP client sang service khác
│   └── acl/                      anti-corruption layer (payOS, ngân hàng)
└── interfaces/
    ├── rest/                     controller (implement interface sinh từ OpenAPI)
    ├── event/                    event listener
    └── scheduler/                job
```

### Hai luật ép bằng ArchUnit

```java
@ArchTest static final ArchRule domain_khong_biet_framework =
    noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "com.fasterxml..");

@ArchTest static final ArchRule chieu_phu_thuoc =
    layeredArchitecture().consideringOnlyDependenciesInLayers()
        .layer("domain").definedBy("..domain..")
        .layer("application").definedBy("..application..")
        .layer("infrastructure").definedBy("..infrastructure..")
        .layer("interfaces").definedBy("..interfaces..")
        .whereLayer("interfaces").mayNotBeAccessedByAnyLayer()
        .whereLayer("application").mayOnlyBeAccessedByLayers("interfaces")
        .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "infrastructure");
```

**Cái giá:** aggregate và JPA entity là hai lớp khác nhau, cần mapper. Nhiều người thấy phiền và bỏ qua bằng cách gắn `@Entity` thẳng lên aggregate. Làm vậy thì mô hình dữ liệu sẽ dần lấn át mô hình nghiệp vụ — quan hệ lazy loading, cascade, và cấu trúc bảng bắt đầu quyết định thiết kế domain. Ở hai core subdomain (Inventory, Ledger) **không được thoả hiệp**. Ở các supporting context, cho phép dùng JPA entity trực tiếp nếu logic mỏng — đây là đánh đổi có ý thức, ghi lại trong ADR của từng service.

---

## 2. Bốn luật thiết kế aggregate

1. **Một transaction = một aggregate.** Cần sửa hai aggregate ⇒ dùng domain event + eventual consistency, hoặc xem lại ranh giới.
2. **Tham chiếu aggregate khác bằng ID, không bằng đối tượng.** `Order` giữ `OrganizationId`, không giữ `Organization`.
3. **Aggregate root là cửa duy nhất.** Không ai lấy `OrderItem` ra sửa trực tiếp.
4. **Aggregate nhỏ nhất mà vẫn giữ được bất biến.** Aggregate to = tranh chấp khoá + tải bộ nhớ lớn.

---

## 3. Danh mục aggregate

| Context | Aggregate root | Entity bên trong | Bất biến chính | Ranh giới transaction |
| --- | --- | --- | --- | --- |
| Identity | `Organization` | `Membership` | Luôn còn ≥ 1 `ORG_OWNER`; `slug` unique | 1 org |
| Identity | `Invitation` | — | Token dùng 1 lần, hạn 7 ngày | 1 invitation |
| Catalog | `Venue` | `VenueLayoutVersion`, `VenueZone`, `VenueFixedSeat` | Mỗi venue tối đa 1 layout `ACTIVE`; `seat_code` unique trong version; zone `FLEXIBLE` bắt buộc có `max_capacity` | 1 venue |
| Catalog | `EventSeatingPlan` | `EventZoneUsage`, `EventFlexibleBlock`, `EventSeatOverride` | Tổng chỗ ≤ `max_capacity` mỗi zone; không trùng `seat_code`; **không sửa được zone `FIXED`**; zone `SEATED` không có `standingCapacity` và ngược lại; `FROZEN` khi đã bán | 1 plan |
| Catalog | `Event` | `EventSession`, `TicketTier` | Publish cần ≥ 1 tier + mọi ghế bán được có tier + sales window hợp lệ | 1 event |
| Catalog | `Promotion` | — | `PERCENT` 1–100; `FIXED` > 0; trong cửa sổ hiệu lực | 1 promotion |
| **Inventory** | `SeatHold` | `SeatHoldItem` | Trần ngồi/đứng/tổng theo cấu hình suất diễn; trần cộng dồn mỗi tài khoản; TTL 5 phút; mọi chỗ cùng một suất | 1 hold (**xem §4**) |
| **Inventory** | `SeatReservation` | `ReservationItem` | Gắn đúng 1 order; hạn 15 phút | 1 reservation |
| Ordering | `Order` | `OrderItem` | Tổng = Σ dòng − giảm giá; không đổi dòng sau `AWAITING_PAYMENT` | 1 order |
| Payment | `PaymentIntent` | `PaymentAttempt` | Chỉ 1 attempt `CONFIRMED`; reference unique | 1 intent |
| **Ledger** | `JournalEntry` | `Posting` | **Σ Nợ = Σ Có**; posting bất biến | 1 bút toán |
| Ledger | `LedgerAccount` | — | Không xoá tài khoản còn số dư | 1 account |
| Payout | `PayoutRequest` | — | ≤ số dư khả dụng `2012`; chỉ `SUPER_ADMIN` khởi tạo | 1 request |
| Payout | `PayoutBatch` | `PayoutBatchItem` | Tổng lô = Σ item; cần 2 người duyệt nếu vượt ngưỡng | 1 batch |
| Ticketing | `Ticket` | — | `qr_jti` unique; `VALID → CHECKED_IN` một chiều | 1 ticket |

---

## 4. Bài toán khó nhất: ranh giới aggregate của Inventory

Bất biến "một ghế chỉ có một quyền giữ hợp lệ tại một thời điểm" **vắt ngang nhiều aggregate** — hai `SeatHold` khác nhau có thể cùng nhắm một ghế.

### Ba lựa chọn

| Lựa chọn | Cách | Vấn đề |
| --- | --- | --- |
| A. `SessionInventory` là aggregate root chứa toàn bộ ghế | Bất biến nằm gọn trong 1 aggregate, DDD "sạch" | Nạp 5.000 ghế vào bộ nhớ cho mỗi lần giữ 2 ghế; mọi hold của một suất serialize sau nhau. **Chết ở 10k đồng thời** |
| B. `SeatHold` là root, bất biến ép bằng unique constraint | Aggregate nhỏ, đồng thời cao | Bất biến không nằm trong domain model — người theo trường phái thuần tuý sẽ phản đối |
| C. Mỗi `SessionSeat` là một aggregate | Nhỏ nhất | Giữ 2 ghế = 2 aggregate = 2 transaction, mất tính nguyên tử. **Sai nghiệp vụ** |

### Chốt: **B**

Đây là mẫu Vaughn Vernon gọi là *bất biến giữa các aggregate được đảm bảo bằng ràng buộc unique của storage*. Cụ thể:

```
Redis Lua        → cổng nhanh, chặn ~99% tranh chấp trước khi chạm DB
UNIQUE INDEX     → chốt cuối cùng, đúng tuyệt đối
   ON seat_hold_items (session_seat_id) WHERE status = 'ACTIVE'
```

Domain model vẫn diễn đạt luật này rõ ràng — chỉ là việc **thực thi** nằm ở tầng persistence:

```java
public final class SeatHold {                       // aggregate root
    private final SeatHoldId id;
    private final EventSessionId sessionId;
    private final UserId userId;
    private final List<SeatHoldItem> items;
    private HoldStatus status;
    private final Instant expiresAt;

    public static SeatHold create(EventSessionId s, UserId u,
                                  List<SessionSeatId> seats, Clock clock) {
        if (seats.isEmpty() || seats.size() > limits.maxUnitsPerHold())
            throw new TooManySeatsException(seats.size());   // trần lấy từ SessionInventory
        if (new HashSet<>(seats).size() != seats.size())
            throw new DuplicateSeatException();
        return new SeatHold(SeatHoldId.generate(), s, u, seats,
                            HoldStatus.ACTIVE, clock.instant().plus(HOLD_TTL));
    }

    /** Chuyển thành đặt chỗ khi tạo đơn. Chỉ hold ACTIVE còn hạn mới chuyển được. */
    public SeatReservation convertTo(OrderId orderId, Clock clock) {
        if (status != HoldStatus.ACTIVE)      throw new HoldNotActiveException(id);
        if (clock.instant().isAfter(expiresAt)) throw new HoldExpiredException(id);
        this.status = HoldStatus.CONVERTED;
        return SeatReservation.from(this, orderId, clock);
    }

    public void release() {
        if (status == HoldStatus.CONVERTED) throw new HoldAlreadyConvertedException(id);
        this.status = HoldStatus.RELEASED;
    }
}
```

Ràng buộc DB **không phải chỗ để lách** — nó là chỗ để **chắc chắn**. Nếu nó bắn ra lỗi trùng, application dịch thành `SEAT_UNAVAILABLE`, đúng nghiệp vụ.

---

## 5. Value object

VO là bất biến, so sánh theo giá trị, tự kiểm tra tính hợp lệ trong constructor.

### `Money` — quan trọng nhất

```java
public record Money(long amountVnd) implements Comparable<Money> {
    public static final Money ZERO = new Money(0);

    public Money {
        if (amountVnd < 0) throw new NegativeMoneyException(amountVnd);
    }
    public Money plus(Money o)  { return new Money(Math.addExact(amountVnd, o.amountVnd)); }
    public Money minus(Money o) {
        if (o.amountVnd > amountVnd) throw new InsufficientAmountException(this, o);
        return new Money(amountVnd - o.amountVnd);
    }
    public Money percent(int bps) {                       // basis points
        return new Money(Math.floorDiv(Math.multiplyExact(amountVnd, bps), 10_000));
    }
    public String format() { … }                          // "1.500.000 ₫"
}
```

Ba điểm cố ý:
- **`long`, không `BigDecimal`, không `double`.** VND không có phần thập phân. `double` cho tiền là lỗi nghiêm trọng.
- **`addExact`/`multiplyExact`** — tràn số ném exception thay vì âm thầm cho kết quả sai.
- **Không âm.** Cần biểu diễn chiều thì dùng `Posting.direction`, không dùng số âm. Sổ cái không có tiền âm, chỉ có Nợ và Có.

### VO khác

| VO | Ràng buộc |
| --- | --- |
| `PaymentReference` | `NT` + 8 ký tự Crockford Base32; regex kiểm tra khi tạo |
| `SeatLabel` | `section-row-seat`, chuẩn hoá viết hoa |
| `TimeWindow` | `start < end`; có `contains(Instant)`, `overlaps(TimeWindow)` |
| `QrToken` | Chuỗi JWS đã ký; parse ra `jti` + `exp` |
| `OrganizationId`, `OrderId`, … | Bọc UUID — chặn truyền nhầm ID này sang chỗ khác lúc biên dịch |
| `CommissionRate` | Basis point 0–10.000 |
| `AccountCode` | Khớp danh mục chart of accounts |

ID có kiểu riêng đáng giá hơn vẻ ngoài: `reserve(orderId, sessionSeatId)` gọi nhầm thứ tự thì trình biên dịch chặn, còn nếu cả hai là `UUID` thì lỗi chỉ lộ ra lúc chạy.

---

## 6. Domain event và integration event

**Phân biệt rõ hai loại:**

| | Domain event | Integration event |
| --- | --- | --- |
| Phạm vi | Trong một service | Giữa các service |
| Nơi phát | Aggregate | Outbox → RabbitMQ |
| Hình dạng | Đối tượng Java giàu ngữ nghĩa | Payload JSON có version |
| Đổi tự do? | Có | **Không** — là hợp đồng công khai |

Aggregate phát domain event; application dịch một phần trong số đó thành integration event ghi vào outbox. Không phải domain event nào cũng ra ngoài — phần lớn là chuyện nội bộ.

### Envelope chuẩn cho integration event

```json
{
  "eventId": "01J8XKQ…",
  "eventType": "nexaticket.ordering.order.paid",
  "eventVersion": 1,
  "occurredAt": "2026-11-01T12:00:00Z",
  "producer": "ordering-service",
  "correlationId": "01J8…",
  "causationId": "01J8…",
  "aggregateType": "Order",
  "aggregateId": "uuid",
  "organizationId": "uuid",
  "payload": { }
}
```

- `correlationId` xuyên suốt cả saga; `causationId` trỏ về event sinh ra event này → dựng lại được cây nhân quả khi debug.
- Exchange: `nexaticket.<context>` (topic, durable). Routing key: `<aggregate>.<event>`.
- Không có schema registry (RabbitMQ không kèm sẵn) — thay bằng **JSON Schema trong `packages/api-contracts/events/`** và test hợp đồng trong CI. Tương thích **backward**: chỉ được thêm trường optional. Đổi phá vỡ ⇒ `event_version` mới, publish song song cho tới khi mọi consumer chuyển xong.
- **Consumer không được phụ thuộc thứ tự** — RabbitMQ không bảo đảm thứ tự khi có nhiều consumer. Mọi handler viết theo điều kiện trạng thái ([ADR-1009 §4](adr/ADR-1009-rabbitmq-only.md)).

### Đặt tên

Quá khứ, thuộc ngôn ngữ nghiệp vụ, **không** mô tả kỹ thuật:

✅ `SeatsHeld`, `OrderPaid`, `FundsRecorded`, `PayoutCompleted`, `TicketsIssued`
❌ `SeatTableUpdated`, `OrderStatusChanged`, `LedgerRowInserted`

`OrderStatusChanged` là mùi của mô hình thiếu máu — nó buộc consumer phải đọc trường `status` rồi tự đoán chuyện gì đã xảy ra, thay vì được nói thẳng.

### Bảng integration event chính

| Event | Producer | Consumer |
| --- | --- | --- |
| `OrganizationCreated` | identity | ledger (tạo bộ tài khoản 2011/2012/2013), analytics |
| `OrganizationVerified` | identity | payout, notification |
| `EventPublishRequested` | catalog | inventory |
| `EventSeatsMaterialized` | inventory | catalog |
| `SeatsHeld` / `SeatsReleased` | inventory | analytics |
| `AvailabilityChanged` | inventory | realtime-gateway |
| `OrderCreated` | ordering | notification, analytics |
| `PaymentConfirmed` | payment | ordering, **ledger**, analytics |
| `PaymentUnmatched` | payment | **ledger** (ghi treo), notification |
| `OrderPaid` | ordering | inventory (→ SOLD), ticketing, notification |
| `TicketsIssued` | ticketing | notification, analytics |
| `TicketCheckedIn` | ticketing | analytics |
| `FundsRecorded` | ledger | payout |
| `HoldPeriodElapsed` | ledger | notification |
| `PayoutRequested` / `PayoutCompleted` | payout | ledger, notification |
| `OrderRefunded` | ordering | ledger, ticketing, inventory |

---

## 7. Repository

Interface ở `domain/port`, cài đặt ở `infrastructure/persistence`. Chỉ nhận và trả **aggregate**, không trả DTO.

```java
// domain/port
public interface SeatHoldRepository {
    Optional<SeatHold> findById(SeatHoldId id);
    Optional<SeatHold> findActiveByUserAndIdempotencyKey(UserId u, String key);
    void save(SeatHold hold);                      // ném SeatAlreadyHeldException khi trùng
    List<SeatHold> findExpired(Instant now, int limit);
}
```

**Read model đi đường riêng.** `GET /v1/sessions/{id}/seats` trả 3.000 ghế — nạp qua repository aggregate là lãng phí. Query side dùng `JdbcTemplate` đọc thẳng, trả DTO, bỏ qua domain hoàn toàn. Đây là CQRS ở mức nhẹ: **write đi qua aggregate, read đi thẳng**. Không cần event sourcing, không cần database đọc riêng.

---

## 8. Application layer

```java
@Service
class HoldSeatsHandler {
    @Transactional
    public HoldResult handle(HoldSeatsCommand cmd) {
        var idem = idempotency.begin(cmd.userId(), cmd.idempotencyKey(), cmd.hash());
        if (idem.isReplay()) return idem.storedResult();

        var session = sessions.requireOnSale(cmd.sessionId());          // domain service
        var lock    = redisGate.acquire(cmd.sessionId(), cmd.seatIds()); // ném RedisUnavailable

        try {
            var hold = SeatHold.create(cmd.sessionId(), cmd.userId(), cmd.seatIds(), clock);
            holds.save(hold);                                            // unique index = chốt cuối
            seats.markHeld(cmd.seatIds());
            var version = sessions.bumpAvailabilityVersion(cmd.sessionId());
            outbox.append(new SeatsHeld(hold, version));                 // phát sau commit
            return idem.complete(HoldResult.from(hold, version));
        } catch (RuntimeException e) {
            lock.releaseQuietly();                                        // bù trừ Redis
            throw e;
        }
    }
}
```

Bốn trách nhiệm của tầng application, và **chỉ** bốn:
1. Mở/đóng transaction.
2. Nạp aggregate, gọi phương thức nghiệp vụ, lưu lại.
3. Xử lý idempotency, uỷ quyền, ghi outbox.
4. Bù trừ tài nguyên ngoài transaction (Redis) khi lỗi.

Không có `if` nghiệp vụ nào ở đây. Thấy điều kiện nghiệp vụ trong handler ⇒ nó thuộc về domain.

---

## 9. Anti-pattern cần tránh

| Anti-pattern | Dấu hiệu | Hậu quả |
| --- | --- | --- |
| **Mô hình thiếu máu** | Aggregate chỉ có getter/setter, logic nằm trong `*Service` | Về lại thủ tục; bất biến rải rác không ai giữ |
| **JPA lấn domain** | `@OneToMany(fetch = LAZY)` trên aggregate root | Cấu trúc bảng quyết định thiết kế nghiệp vụ |
| **Aggregate khổng lồ** | `SessionInventory` chứa 5.000 ghế | Tranh chấp khoá, hết bộ nhớ |
| **Thư viện entity dùng chung** | `common-domain` chứa `Order` cho nhiều service | Mất tính độc lập — đây là monolith trá hình |
| **Event kỹ thuật** | `OrderStatusChanged`, `RowUpdated` | Consumer phải đoán nghiệp vụ |
| **Saga rải rác** | Điều phối nằm trong controller | Không ai biết luồng đang ở bước nào khi hỏng |
| **Truy vấn chéo service** | `ordering_db` join `catalog_db` | Xoá sạch lợi ích của việc tách |
| **Tiền kiểu `double`** | `double totalAmount` | Sai số tiền thật |
