# Milestone 03 — Seat & Checkout (tuần 7–10)

## Mục tiêu

Chọn ghế realtime, hold TTL, order + VietQR/SePay, phát hành ticket; gate load 10k.

## Artifacts

| Artifact | File |
| --- | --- |
| Hold/payment windows | [ADR-0015](adr/ADR-0015-hold-and-payment-windows.md) |
| Hold consistency | [ADR-0004](adr/ADR-0004-seat-hold-consistency.md) |
| Realtime versioning | [ADR-0010](adr/ADR-0010-realtime-seat-versioning.md) |
| VietQR/SePay | [ADR-0013](adr/ADR-0013-vietqr-sepay-bank-transfer.md) |
| SePay webhook | [api/sepay-webhook.md](api/sepay-webhook.md) |
| Hold/Order API | [api/hold-order.md](api/hold-order.md) |
| State machines | [state-machines.md](state-machines.md) |
| Sequences | diagrams/*.mmd |
| Customer UI flow | [../ui/flows/customer-purchase.md](../ui/flows/customer-purchase.md) |
| UI states | [../ui/states.md](../ui/states.md) |

## Sprint plan

### Tuần 7

- `GET sessions/{id}/seats` + version.
- Redis Lua hold + persist + WS broadcast.
- Client seat map UI + countdown 5m.

### Tuần 8

- `POST /orders`: RESERVED 15m, VietQR, payment_reference.
- Expiry workers (hold + payment).
- Idempotency E2E.

### Tuần 9

- SePay webhook adapter đầy đủ (duplicate, wrong amount, late).
- Issue tickets + My Tickets UI.
- Notification email PAID.

### Tuần 10

- Load test 10k; chaos Redis restart; fix bottlenecks.
- Contract tests webhook + WS.

## Exit gate

- [ ] 0 oversell dưới load đã định.
- [ ] p95 hold ≤ 300 ms (env test).
- [ ] Webhook retry không vé trùng.
- [ ] Late payment → MANUAL_REVIEW.
