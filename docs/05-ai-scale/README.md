# Milestone 05 — AI & Scale (trong / sau 3 tháng)

## Trong 3 tháng (bắt buộc nhẹ)

Chỉ **instrumentation** — không model ảnh hưởng checkout.

| Deliverable | Tuần | Done when |
| --- | --- | --- |
| Event taxonomy documented | 7 | [event-taxonomy.md](event-taxonomy.md) |
| Emit analytics events qua outbox | 8–10 | events trong RabbitMQ/analytics queue |
| Warehouse stub / log sink | 11 | events land ở sink (S3/PG table `analytics_events`) |

## Post-MVP (tháng 4+)

1. Content-based recommendation + fallback popular/upcoming.
2. Offline recall@K; online conversion uplift.
3. Demand forecast baseline (MAE/bias).
4. Capacity hardening theo production evidence; xét Kafka.

## Artifacts

- [ADR-0012](adr/ADR-0012-ai-async-only.md)
- [event-taxonomy.md](event-taxonomy.md)

## Exit gate (3 tháng)

- [ ] Taxonomy đủ: impression → check-in.
- [ ] AI không có dependency sync trên hold/order/payment.
- [ ] Consent/retention note trong legal doc.
