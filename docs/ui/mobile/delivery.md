# Mobile delivery backlog

## Trong 13 tuần (song song FE web)

| Tuần | Mobile web work |
| --- | --- |
| 2–3 | Shell + tokens; bottom nav breakpoint |
| 4–6 | List/detail mobile layouts |
| 7–8 | SeatMap mobile tray + WS |
| 9 | PaymentQr mobile + copy UX |
| 10 | 2-device seat conflict QA |
| 11–12 | Scanner phone QA + PWA optional |
| 13 | Device matrix checklist A |

## React Native v1 — đề xuất 4–5 tuần sau gate M03

| Sprint | Deliverable | Exit |
| --- | --- | --- |
| RN-0 (3–5 ngày) | Expo/RN bootstrap, theme, nav, SecureStore auth | Login + /me |
| RN-1 | Home, List, Detail | Browse staging |
| RN-2 | SeatMap + WS + Hold | Hold trên device |
| RN-3 | Order + VietQR + poll + Tickets | E2E purchase |
| RN-4 | Deep links, Sentry, internal builds | TestFlight/Play internal |

### Staffing

- 1 RN eng full-time sau khi web checkout ổn, **hoặc** 1 FE chuyển sau tuần 10.
- Không lấy 2 BE khỏi load test tuần 10 để làm RN.

### Dependencies

- OpenAPI ổn định seats/holds/orders/tickets.
- Keycloak public client cho mobile.
- `wss` endpoint documented.
- Design tokens exported (`packages/design-tokens` optional).

## Milestone doc links

- Gate web: [../../03-seat-checkout/README.md](../../03-seat-checkout/README.md)
- Plan tổng: [../../DELIVERY-PLAN-3M.md](../../DELIVERY-PLAN-3M.md)
