# Sitemap & information architecture

```text
web-customer                         web-admin                            web-scanner
├── /                                ├── /login                           ├── /login
├── /events                          ├── / (redirect theo role)           ├── / (scan home)
├── /events/[slug]                   ├── /org/[orgId]/dashboard          ├── /result (inline)
├── /events/[slug]/sessions/[id]/seats ├── /org/[orgId]/venues
├── /checkout/hold/[holdId]          ├── /org/[orgId]/venues/[id]
├── /checkout/orders/[orderId]       ├── /org/[orgId]/venues/[id]/seat-maps/[mapId]
├── /checkout/orders/[orderId]/pay   ├── /org/[orgId]/events
├── /me/orders                       ├── /org/[orgId]/events/[id]          (wizard)
├── /me/tickets                      ├── /org/[orgId]/events/[id]/publish
├── /me/tickets/[id]                 ├── /org/[orgId]/promotions
├── /login (OIDC callback)           ├── /org/[orgId]/bank-accounts
└── /account                         ├── /org/[orgId]/members
                                     ├── /org/[orgId]/payments/review
                                     ├── /platform/tenants                 (PLATFORM_ADMIN)
                                     └── /platform/audit
```

## Navigation chrome

### Customer

| Vùng | Nội dung |
| --- | --- |
| Top bar | Logo **NexaTicket**, Search (desktop), link Sự kiện, Vé của tôi, Account |
| Mobile | Bottom nav: Khám phá / Vé / Tài khoản — **ẩn** khi đang seat map hoặc checkout pay (focus mode) |
| Footer | Điều khoản, Privacy — chỉ trang marketing/list, không trên checkout |

### Admin

| Vùng | Nội dung |
| --- | --- |
| Left nav | Tổng quan, Sự kiện, Địa điểm, Khuyến mãi, Tài khoản NH, Thành viên, Đối soát |
| Top | Org switcher (nếu multi-org), user menu |
| Platform | Nav riêng khi `PLATFORM_ADMIN`: Tenants, Audit |

### Scanner

| Vùng | Nội dung |
| --- | --- |
| Chrome tối giản | Logo nhỏ, event/session đang chọn, user, online indicator |
| Main | Camera / manual token input full viewport |
| Không | Marketing, list event công khai |

## Deep links quan trọng

| Link | Dùng khi |
| --- | --- |
| `/events/{slug}` | Share marketing |
| `/checkout/orders/{id}/pay` | Email OrderCreated |
| `/me/tickets/{id}` | Email ticket |
| Scanner `/` | Bookmark event-day |
