# Backlog kỹ thuật theo tuần (13 tuần)

Checklist triển khai. Đánh dấu trong PR milestone.

## Tuần 1 — Discovery close

- [ ] Stakeholder sign-off SRS + product decisions
- [ ] Review [UI docs](ui/README.md) + [web screens](ui/screens/README.md) + [mobile screens](ui/mobile/screens/README.md)
- [ ] SePay sandbox + Keycloak hosting chốt
- [ ] Repo trống tạo từ [repo-structure](01-foundation/repo-structure.md)

## Tuần 2 — Foundation A

- [ ] Compose: PG, Redis, RabbitMQ, Keycloak
- [ ] Spring Boot modules skeleton + ArchUnit
- [ ] Flyway Identity + outbox + idempotency + audit
- [ ] JWT resource server
- [ ] Next.js customer/admin login shell + CSS tokens theo [design-direction](ui/design-direction.md)

## Tuần 3 — Foundation B

- [ ] Tenant guard + IDOR sample tests
- [ ] Outbox → RabbitMQ publisher
- [ ] OTel + staging deploy + backup drill
- [ ] Exit gate 01

## Tuần 4 — Catalog A

- [ ] Venue + seat map bulk API/UI
- [ ] Activate seat map

## Tuần 5 — Catalog B

- [ ] Event/session/tier + seat→tier
- [ ] Publish + materialize session_seats
- [ ] Public list/detail

## Tuần 6 — Catalog C

- [ ] Promotions + bank accounts encrypt
- [ ] Audit publish/tier/bank
- [ ] E2E publish gate → Exit 02

## Tuần 7 — Checkout A

- [ ] GET seats + version
- [ ] Redis Lua hold + WS
- [ ] Seat map UI + 5m countdown

## Tuần 8 — Checkout B

- [ ] POST orders + VietQR + 15m window
- [ ] Hold/payment expiry workers
- [ ] OrderCreated email

## Tuần 9 — Checkout C

- [ ] SePay webhook full cases
- [ ] Ticket issue + My Tickets + QR render
- [ ] Analytics events emit

## Tuần 10 — Checkout D

- [ ] Load test 10k + chaos
- [ ] Fix oversell/latency
- [ ] Exit 03 + load report

## Tuần 11 — Ops A

- [ ] Scanner + check-in API
- [ ] Dashboard metrics
- [ ] Notification DLQ

## Tuần 12 — Ops B

- [ ] Refund/manual resolve
- [ ] Event-day + payment drills
- [ ] Security smoke → Exit 04

## Tuần 13 — Buffer / soft launch

- [ ] Bug bash
- [ ] QA mobile web: [ui/mobile/qa-checklist.md](ui/mobile/qa-checklist.md) section A
- [ ] Go/No-go checklist
- [ ] Soft launch 1 event pilot
- [ ] PWA optional; RN theo [ui/mobile/delivery.md](ui/mobile/delivery.md)
