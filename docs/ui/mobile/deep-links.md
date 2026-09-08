# Deep links & email handoff

## Canonical HTTPS paths

Dùng chung web và app:

| Path | Target |
| --- | --- |
| `/events/{slug}` | EventDetail |
| `/events/{slug}/sessions/{id}/seats` | SeatMap |
| `/checkout/orders/{id}/pay` | PaymentQr |
| `/checkout/orders/{id}` | OrderDetail |
| `/me/tickets/{id}` | TicketDetail |
| `/me/orders` | OrderList |
| `/invites/{token}` | Accept invite (thường mở **web admin**/customer web — RN customer có thể mở in-app browser) |

## Mobile web

- Email links mở browser; session cookie/OIDC resume.

## React Native

### iOS Universal Links

- Domain `app.nexaticket.vn` (hoặc `www`) apple-app-site-association.
- Fallback scheme `nexaticket://`.

### Android App Links

- `assetlinks.json` + intent filters cùng path prefix.

### Resolve logic

```text
Cold start with URL → parse path → AuthGate
  if needs auth and no session → Login then continue
  else → navigate screen
```

### Auth callback

Không conflict với marketing paths: `/auth/callback` reserved.

## QR ticket

- Nội dung QR = signed token (không phải HTTPS link). Scanner decode token, không deep link customer app.
