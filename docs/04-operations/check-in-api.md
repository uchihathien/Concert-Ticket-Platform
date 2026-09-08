# Check-in API

## `POST /v1/check-ins`

Auth: `CHECKIN_STAFF`+ trong org sở hữu event.

```json
{
  "qrToken": "<signed-opaque-token>"
}
```

### Success first scan `200`

```json
{
  "result": "CHECKED_IN",
  "ticketId": "uuid",
  "seatLabel": "A-1-01",
  "eventTitle": "Hòa Âm 2026",
  "checkedInAt": "2026-11-01T18:05:00+07:00"
}
```

### Already checked in `200`

```json
{
  "result": "ALREADY_CHECKED_IN",
  "ticketId": "uuid",
  "seatLabel": "A-1-01",
  "checkedInAt": "2026-11-01T18:05:00+07:00"
}
```

### Errors

| Code | HTTP | Meaning |
| --- | --- | --- |
| `INVALID_TOKEN` | 400 | chữ ký/expiry sai |
| `TICKET_NOT_VALID` | 409 | cancelled/refunded |
| `WRONG_ORGANIZATION` | 403 | staff sai org |
| `EVENT_NOT_OPEN` | 409 | optional window guard |

Atomic: `UPDATE tickets SET status=CHECKED_IN WHERE id=? AND status=VALID` + insert `check_ins`. Unique `ticket_id` trên `check_ins`.
