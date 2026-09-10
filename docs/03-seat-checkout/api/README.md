# API contracts index

HTTP APIs phiên bản `/v1` trừ webhook payOS external.

| Contract | Mục đích |
| --- | --- |
| [hold-order.md](hold-order.md) | Seats, holds, orders, WS, tickets |
| [payos-webhook.md](payos-webhook.md) | Xác nhận thanh toán payOS (ADR-0016) |
| [../../02-catalog-admin/api.md](../../02-catalog-admin/api.md) | Catalog & admin |

Mutation yêu cầu `Idempotency-Key`. Webhook dedupe theo `provider` + mã giao dịch ngân hàng
(`data.reference` của payOS), chốt bằng unique index `uq_provider_txn`.
