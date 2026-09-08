# Milestone 02 — Catalog & Admin (tuần 4–6)

## Mục tiêu

Organizer tạo venue, seat map, event session, ticket tier, promotion, bank account; publish event; customer discovery.

## Artifacts

- [Authoring flows](flows.md)
- [API contract](api.md)
- [State machines](state-machines.md)
- [Organizer UI flow](../ui/flows/organizer-publish.md)
- RBAC: [../00-discovery/rbac-permission-matrix.md](../00-discovery/rbac-permission-matrix.md)
- Data ownership: [../01-foundation/data-model.md](../01-foundation/data-model.md)

## Sprint plan

### Tuần 4

- Venue + seat map authoring API/UI (grid đơn giản: section/row/seat).
- Materialize preview chưa publish.

### Tuần 5

- Event, session, tier, map seat→tier.
- Publish → materialize `session_seats`.
- Customer `GET /events` + detail slug.

### Tuần 6

- Promotion cơ bản + bank account CRUD (encrypt).
- Audit admin changes.
- E2E: org A publish; org B không thấy draft; customer thấy published.

## Exit gate

- [ ] Organizer tenant publish event end-to-end.
- [ ] Customer chỉ thấy `PUBLISHED`.
- [ ] IDOR tests catalog xanh.
- [ ] Audit row khi đổi tier/bank/publish.
