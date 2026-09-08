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
AWAITING_PAYMENT --> MANUAL_REVIEW   (late/mismatched path may land here via payment)
PAID --> REFUNDED
MANUAL_REVIEW --> PAID | REFUNDED | CANCELLED
```

## payment_attempts.status

```text
PENDING --> CONFIRMED | REJECTED | DUPLICATE | MANUAL_REVIEW
CONFIRMED --> REFUNDED
```

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
| Payment expiry | `orders.payment_expires_at` | AWAITING→EXPIRED; seats AVAILABLE; WS |
| Outbox publisher | new outbox rows | publish RabbitMQ |
| Notification | `OrderPaid` / `TicketIssued` | email |
