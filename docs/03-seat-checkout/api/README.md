# API contracts index

HTTP APIs phiên bản `/v1` trừ webhook SePay external.

| Contract | Mục đích |
| --- | --- |
| [hold-order.md](hold-order.md) | Seats, holds, orders, WS, tickets |
| [sepay-webhook.md](sepay-webhook.md) | Xác nhận bank transfer |
| [../../02-catalog-admin/api.md](../../02-catalog-admin/api.md) | Catalog & admin |

Mutation yêu cầu `Idempotency-Key`. Webhook dedupe theo `sepay_transaction_id`.
