# Milestone 04 — Operations (tuần 11–12)

## Mục tiêu

Check-in online, dashboard, notification/retry, refund/reconcile, event-day drill.

## Artifacts

| Artifact | File |
| --- | --- |
| Check-in ADR | [ADR-0014](adr/ADR-0014-online-single-checkin.md) |
| Check-in API | [check-in-api.md](check-in-api.md) |
| Dashboard | [dashboard.md](dashboard.md) |
| Notifications | [notifications.md](notifications.md) |
| Refund policy | [refunds.md](refunds.md) |
| Runbooks | [runbooks/](runbooks/) |
| Sequence | [diagrams/check-in-sequence.mmd](diagrams/check-in-sequence.mmd) |
| Scanner UI | [../ui/flows/check-in.md](../ui/flows/check-in.md) |
| Ops UI | [../ui/flows/ops-resolve.md](../ui/flows/ops-resolve.md) |

## Sprint plan

### Tuần 11

- Scanner web + `POST /check-ins`.
- Dashboard 4 metrics + filter theo event/session.
- Email retry/dead-letter.

### Tuần 12

- Refund/manual resolve UI (ORG_ADMIN) + audit.
- Event-day drill + payment reconciliation drill.
- Security smoke (IDOR, webhook replay, rate limit).

## Exit gate

- [ ] First scan CHECKED_IN; rescan ALREADY_CHECKED_IN.
- [ ] MANUAL_REVIEW resolve có audit; không edit DB tay.
- [ ] Runbook drill có biên bản.
- [ ] Go/No-go list sẵn cho tuần 13.
