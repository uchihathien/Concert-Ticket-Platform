# Seat, hold, order, payment state machines

## session_seats.status

```text
AVAILABLE --hold--> HELD
HELD --release/expire--> AVAILABLE
HELD --create order--> RESERVED
RESERVED --paid--> SOLD
RESERVED --order expire/cancel--> AVAILABLE
SOLD --refund before check-in--> AVAILABLE   (Should; MVP may BLOCKED + manual)
SOLD --terminal if checked-in--> (stay SOLD; ticket CANCELLED/REFUNDED separately)
* --admin--> BLOCKED
BLOCKED --admin--> AVAILABLE
```

## seat_holds.status

```text
ACTIVE --> RELEASED | EXPIRED | CONVERTED
```

## orders.status

```text
AWAITING_PAYMENT --> PAID
AWAITING_PAYMENT --> EXPIRED
AWAITING_PAYMENT --> CANCELLED
EXPIRED | CANCELLED --> MANUAL_REVIEW   (tiền vào sau khi đơn đã đóng)
PAID --> REFUNDED
MANUAL_REVIEW --> REFUNDED | CANCELLED  (xử lý tay; KHÔNG quay lại PAID)
```

`MANUAL_REVIEW` vào từ đơn **đã đóng**, không từ `AWAITING_PAYMENT`: khách chuyển khoản ở phút chót
và webhook tới sau khi `ExpireOrdersJob` (15 giây một nhịp) vừa đóng đơn và nhả ghế.

Không có đường `MANUAL_REVIEW → PAID`. Ghế đã nhả lúc đóng đơn và có thể đã bán cho người khác;
chuyển sang PAID sẽ phát vé cho một chỗ đã có chủ. Muốn giữ khách thì đặt lại một đơn mới, còn đơn
này đi đường hoàn tiền.

Đường vào nằm ở `ConfirmPaymentHandler`: payment-service gọi `POST /internal/orders/{id}/confirm-payment`,
nhận về `{"outcome":"MANUAL_REVIEW"}` và **200** — không phải 500. Trả lỗi ở đó khiến payOS giao lại
mãi, và transaction rollback bên payment sẽ xoá luôn dòng `bank_webhook_log` của một khoản tiền có
thật.

## payment_intents.status

Tên bảng thật là `payment_intents` (ADR-0016); `payment_attempts` là tên cũ thời SePay.

```text
PENDING --> CONFIRMED           (webhook/đối soát/sandbox, số tiền đủ)
PENDING --> CANCELLED           (đơn đóng: Ordering gọi DELETE /internal/payment-intents/{orderId})
PENDING --> EXPIRED             (ExpireIntentsJob, lưới dọn khi lời gọi trên thất lạc)
```

`CONFIRMED` là trạng thái cuối của intent. Kết quả **đối chiếu** một lần báo có — `DUPLICATE`,
`AMOUNT_MISMATCH`, `NOT_PENDING`, `UNKNOWN_REFERENCE`, `REJECTED` — không phải trạng thái của
intent; chúng nằm ở cột `outcome` của `bank_webhook_log`.

## tickets.status

```text
VALID --> CHECKED_IN
VALID --> CANCELLED | REFUNDED
CHECKED_IN --> (no reopen MVP)
```

## Worker responsibilities

| Worker | Trigger | Action |
| --- | --- | --- |
| Hold expiry | `seat_holds.expires_at` + Redis TTL | ACTIVE→EXPIRED; seats AVAILABLE; WS |
| Payment expiry | `orders.payment_expires_at` | AWAITING→EXPIRED; đóng link payOS; seats AVAILABLE; WS |
| Intent expiry | `payment_intents.expires_at` | PENDING→EXPIRED (lưới dọn, không gọi payOS) |
| Outbox publisher | new outbox rows | publish RabbitMQ |

## Consumer của sự kiện đơn hàng

Bốn consumer độc lập trên cùng `order.paid`, và một trên đường đóng đơn. Mỗi cái idempotent và
không phụ thuộc thứ tự (ADR-1009).

| Queue | Sự kiện | Việc |
| --- | --- | --- |
| `inventory.ordering.paid` | `order.paid` | RESERVED → SOLD |
| `ticketing.ordering.paid` | `order.paid` | phát vé |
| `ledger.ordering.paid` | `order.paid` | bút toán N1 (tuần tự) |
| `notification.ordering.paid` | `order.paid` | email "đã thanh toán" |
| `inventory.ordering.closed` | `order.expired`, `order.cancelled` | nhả ghế về AVAILABLE |
| `notification.ordering.expired` | `order.expired` | email "đơn hết hạn" |
| `analytics.ordering.all` | `#` | read model doanh thu |

Lời gọi HTTP thẳng từ Ordering sang Inventory và Payment lúc đóng đơn là đường **nhanh**, không
phải đường chắc chắn: nó "cố gắng hết sức" và hỏng thì thôi. Đường chắc chắn là các queue trên.
