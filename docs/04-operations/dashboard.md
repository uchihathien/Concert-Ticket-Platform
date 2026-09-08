# Operations dashboard

## Scope MVP

Organizer dashboard (tenant-scoped) cho 1 event hoặc toàn org:

| Metric | Định nghĩa |
| --- | --- |
| GMV | Sum `orders.total` where `PAID` (+ optionally REFUNDED net) |
| Orders | Count orders by status |
| Payment conversion | `PAID / (PAID + EXPIRED + AWAITING + …)` trong cửa sổ |
| Seats | counts by `session_seats.status` (AVAILABLE/HELD/RESERVED/SOLD) |

## API

`GET /v1/admin/metrics?eventId&sessionId&from&to`

Response sketch:

```json
{
  "gmvVnd": 120000000,
  "orders": { "paid": 80, "awaiting": 3, "expired": 20, "manualReview": 1 },
  "seats": { "available": 100, "held": 5, "reserved": 3, "sold": 92 }
}
```

## Non-goals MVP

Realtime BI, cohort, funnel visualization nâng cao, CSV phức tạp (CSV export đơn giản = Should tuần 12 nếu kịp).
