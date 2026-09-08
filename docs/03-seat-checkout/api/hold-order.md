# Hold, seats, order API

## `GET /v1/sessions/{id}/seats`

Auth: optional (public khi event published).

Response:

```json
{
  "sessionId": "uuid",
  "version": 1842,
  "seats": [
    {
      "sessionSeatId": "uuid",
      "section": "A",
      "row": "1",
      "label": "01",
      "status": "AVAILABLE",
      "tierId": "uuid",
      "unitPriceVnd": 1500000
    }
  ]
}
```

## `POST /v1/sessions/{id}/holds`

Auth: customer. Headers: `Idempotency-Key`.

```json
{ "sessionSeatIds": ["uuid1", "uuid2"] }
```

Response `201`:

```json
{
  "holdId": "uuid",
  "expiresAt": "2026-08-05T10:05:00+07:00",
  "seats": ["uuid1", "uuid2"],
  "version": 1843
}
```

Errors: `SEAT_UNAVAILABLE` (409), `SESSION_NOT_ON_SALE` (409), `REDIS_UNAVAILABLE` (503 retryable).

## `DELETE /v1/holds/{id}`

Auth: owner. Release seats → AVAILABLE; bump version; WS event.

## `POST /v1/orders`

Auth: customer. Headers: `Idempotency-Key`.

```json
{
  "holdId": "uuid",
  "promotionCode": "SUMMER10"
}
```

Response `201`:

```json
{
  "orderId": "uuid",
  "status": "AWAITING_PAYMENT",
  "totalVnd": 2700000,
  "paymentExpiresAt": "2026-08-05T10:20:00+07:00",
  "paymentReference": "NTX9F2K1",
  "vietQr": {
    "payload": "000201...",
    "bankCode": "VCB",
    "accountName": "CONG TY ABC",
    "accountNumberMasked": "****5678"
  }
}
```

Errors: `HOLD_EXPIRED`, `HOLD_NOT_OWNED`, `PROMOTION_INVALID`, `NO_BANK_ACCOUNT`.

## WebSocket `seat.availability.changed`

```json
{
  "sessionId": "uuid",
  "version": 1844,
  "changes": [
    { "sessionSeatId": "uuid", "status": "HELD" }
  ]
}
```

Client nếu `version > local+1` → refetch full seats.

## `GET /v1/tickets/{id}`

Auth: owner (hoặc org staff theo policy). Trả status + QR token string để render.

## Timing constants (MVP)

| Constant | Value |
| --- | --- |
| `HOLD_TTL` | 5 minutes |
| `PAYMENT_WINDOW` | 15 minutes |
| `IDEMPOTENCY_TTL` | 24 hours |
