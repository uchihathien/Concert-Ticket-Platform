# Technical — React Native customer

## Stack đề xuất

| Layer | Choice |
| --- | --- |
| Runtime | React Native 0.76+ / Expo SDK tương đương (team chọn 1 và giữ) |
| Language | TypeScript strict |
| Nav | React Navigation 7 — native stack + bottom tabs |
| Server state | TanStack Query |
| Client state | Selection seat / checkout draft trong memory + lightweight store |
| Forms | promo code: controlled input |
| WS | `react-native-websocket` hoặc app-level singleton |
| QR render | `react-native-qrcode-svg` |
| Auth | `expo-auth-session` / AppAuth + `expo-secure-store` |
| Crash | Sentry |
| Config | `react-native-config` / EAS env |

## Repo layout

```text
apps/mobile/
  app.json | app.config.ts
  src/
    app/                 # navigation roots
    screens/
    components/
    features/
      auth/
      catalog/
      inventory/         # seats, holds, ws
      checkout/
      tickets/
    api/                 # generated client from packages/ts-sdk
    theme/
    lib/                 # secureStore, idempotencyKey, analytics
  ios/
  android/
```

Share: `packages/ts-sdk`, `packages/api-contracts` — không copy type tay.

## API rules

- Base URL env: staging/prod.
- Header `Authorization: Bearer`, `Idempotency-Key` trên POST/DELETE mutation.
- `Idempotency-Key`: generate UUID per user action; persist until success/fail terminal để retry nút.
- Timeout hold POST: 10s; show retry.

## WebSocket

- URL: `wss://api.../v1/ws/sessions/{id}` (chốt contract Foundation).
- Subscribe after SeatMap focus; unsubscribe blur.
- Heartbeat / reconnect with jitter.
- Same payload `seat.availability.changed` as web.

## Security

- ATS / network security config HTTPS only.
- Certificate pinning: Should prod later.
- Không log token, reference đầy đủ trong crash breadcrumbs (mask).
- Jailbreak detection: Out v1.

## Build & release

| Track | When |
| --- | --- |
| EAS / local dev client | Dev |
| Internal testing Play + TestFlight | Sau parity purchase |
| Production store | Sau 1–2 event pilot web ổn |

Versioning: `CFBundleShortVersionString` / `versionName` align semver với API min version; ForceUpdate screen nếu API trả `UPGRADE_REQUIRED`.

## Feature flags

Remote config (simple JSON): `maxSeatsPerHold`, `wsEnabled`, `maintenanceMessage`.
