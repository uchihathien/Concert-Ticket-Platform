# UI & UX documentation

Tài liệu thiết kế giao diện MVP. Nguồn cho FE implement; chưa thay Figma hi-fi — đủ screen list, flow, state và hướng visual.

| Artifact | Nội dung |
| --- | --- |
| [Sitemap / IA](sitemap.md) | Cây route 3 app **web** |
| [Screen inventory](screen-inventory.md) | Màn hình web + milestone |
| [Screen specs (chi tiết)](screens/README.md) | Từng màn customer / admin / scanner |
| [Mobile](mobile/README.md) | Mobile web + PWA + React Native |
| [Mobile screens (chi tiết)](mobile/screens/README.md) | Spec từng màn điện thoại |
| [Design direction](design-direction.md) | Brand, typography, màu, motion, anti-patterns |
| [UI states](states.md) | Loading / empty / error / countdown / seat legend |
| [Flow: Auth](flows/auth.md) | Login / invite |
| [Flow: Customer purchase](flows/customer-purchase.md) | Discovery → vé (web + parity RN) |
| [Flow: Organizer publish](flows/organizer-publish.md) | Venue → publish (web only) |
| [Flow: Check-in](flows/check-in.md) | Scanner web |
| [Flow: Ops resolve](flows/ops-resolve.md) | Dashboard, MANUAL_REVIEW, refund |

## Apps

| App | Persona | 3 tháng | Port local |
| --- | --- | --- | --- |
| `web-customer` | Khách (desktop + **mobile browser**) | Must | :3000 |
| `web-admin` | Organizer + platform | Must | :3001 |
| `web-scanner` | Check-in staff (điện thoại browser) | Must | :3002 |
| `apps/mobile` | Khách native RN | Optional / post-MVP | — |

Chi tiết: [mobile/README.md](mobile/README.md).

## Nguyên tắc UX chung

1. **Một việc / màn** — không nhồi dashboard vào hero discovery.
2. **Server countdown** — hold 5 phút và payment 15 phút luôn lấy `expiresAt` từ API, không tin đồng hồ client tuyệt đối.
3. **Ghế realtime** — WS delta; gap version → refetch; seat đang chọn của mình highlight khác ghế HELD của người khác.
4. **Thanh toán CK** — màn VietQR phải copy được `paymentReference` + số tiền; trạng thái polling/refresh rõ.
5. **vi-VN** là ngôn ngữ mặc định; mọi copy lỗi có mã + câu tiếng Việt ngắn.
6. **Mobile-first** customer + scanner **web**; admin desktop-first nhưng dùng được tablet.
7. **Native app** không phải gate go-live; parity flow khi làm RN theo [mobile/](mobile/README.md).

## Liên kết kỹ thuật

- SRS: [../00-discovery/srs.md](../00-discovery/srs.md)
- Hold/payment: [../03-seat-checkout/adr/ADR-0015-hold-and-payment-windows.md](../03-seat-checkout/adr/ADR-0015-hold-and-payment-windows.md)
- API hold/order: [../03-seat-checkout/api/hold-order.md](../03-seat-checkout/api/hold-order.md)
- Mobile QA: [mobile/qa-checklist.md](mobile/qa-checklist.md)
