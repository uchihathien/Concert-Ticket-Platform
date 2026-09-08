# PWA (customer + scanner)

## Scope

| App | PWA |
| --- | --- |
| web-customer | Should tuần 11–13 |
| web-scanner | Should — khuyến nghị event-day |
| web-admin | Out |

## Manifest (customer)

- `name`: NexaTicket
- `short_name`: NexaTicket
- `display`: standalone
- `start_url`: `/`
- `background_color` / `theme_color`: `#0c1210`
- Icons 192 / 512 (maskable)

## Service worker

- Precache **app shell** only (HTML/JS/CSS).
- **Never** cache: `/v1/sessions/*/seats`, holds, orders, tickets API.
- Network-first for API; offline fallback page tĩnh “Cần mạng để đặt vé”.

## Scanner PWA

- `start_url`: `/scan` hoặc `/`
- Hướng dẫn lần đầu: “Thêm vào Màn hình chính” (iOS Share sheet).
- Camera permission trong standalone mode test trên thiết bị thật.

## Push

Out MVP (dùng email). Không xin notification permission sớm.
