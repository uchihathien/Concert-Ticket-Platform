# Screen inventory

Mỗi màn: ID, route, app, milestone ship, mục đích 1 câu.

**Spec chi tiết (layout, UI, API, acceptance):** [screens/README.md](screens/README.md)

## Customer (`web-customer`)

| ID | Route | Milestone | Mục đích | Spec |
| --- | --- | --- | --- | --- |
| C-HOME | `/` | 02 | Hero brand + CTA khám phá | [customer](screens/customer.md#c-home--trang-chủ) |
| C-LIST | `/events` | 02 | Search/filter event published | [customer](screens/customer.md#c-list--danh-sách-sự-kiện) |
| C-DETAIL | `/events/[slug]` | 02 | Chi tiết, chọn session, CTA chọn ghế | [customer](screens/customer.md#c-detail--chi-tiết-sự-kiện) |
| C-SEATS | `/events/[slug]/sessions/[id]/seats` | 03 | Seat map realtime + hold | [customer](screens/customer.md#c-seats--sơ-đồ-ghế) |
| C-HOLD | `/checkout/hold/[holdId]` | 03 | Xác nhận ghế + countdown + tạo đơn | [customer](screens/customer.md#c-hold--xác-nhận-giữ-ghế) |
| C-PAY | `/checkout/orders/[orderId]/pay` | 03 | VietQR + countdown 15p | [customer](screens/customer.md#c-pay--thanh-toán-vietqr) |
| C-ORDER | `/checkout/orders/[orderId]` | 03 | Chi tiết đơn | [customer](screens/customer.md#c-order--chi-tiết-đơn) |
| C-ORDERS | `/me/orders` | 03 | Lịch sử đơn | [customer](screens/customer.md#c-orders--lịch-sử-đơn) |
| C-TICKETS | `/me/tickets` | 03 | Vé đã có | [customer](screens/customer.md#c-tickets--vé-của-tôi) |
| C-TICKET | `/me/tickets/[id]` | 03 | QR vé | [customer](screens/customer.md#c-ticket--chi-tiết-vé--qr) |
| C-LOGIN | `/login` | 01 | Redirect OIDC | [customer](screens/customer.md#c-login--đăng-nhập) |
| C-ACCOUNT | `/account` | 01 | Hồ sơ cơ bản | [customer](screens/customer.md#c-account--tài-khoản) |

## Admin (`web-admin`)

| ID | Route | Milestone | Mục đích | Spec |
| --- | --- | --- | --- | --- |
| A-LOGIN | `/login` | 01 | OIDC + MFA admin | [admin](screens/admin.md#a-login--đăng-nhập-admin) |
| A-DASH | `/org/[orgId]/dashboard` | 04 | 4 metrics | [admin](screens/admin.md#a-dash--tổng-quan) |
| A-VENUE-LIST | `/org/.../venues` | 02 | Danh sách địa điểm | [admin](screens/admin.md#a-venue-list--danh-sách-địa-điểm) |
| A-VENUE | `/org/.../venues/[id]` | 02 | Chi tiết + seat maps | [admin](screens/admin.md#a-venue--chi-tiết-địa-điểm) |
| A-SEATMAP | `/org/.../seat-maps/[mapId]` | 02 | Author grid / CSV | [admin](screens/admin.md#a-seatmap--author-sơ-đồ-ghế) |
| A-EVENT-LIST | `/org/.../events` | 02 | Draft/published | [admin](screens/admin.md#a-event-list--danh-sách-sự-kiện) |
| A-EVENT | `/org/.../events/[id]` | 02 | Wizard 5 bước | [admin](screens/admin.md#a-event--wizard-sự-kiện) |
| A-PUBLISH | `/org/.../events/[id]/publish` | 02 | Preflight + publish | [admin](screens/admin.md#a-publish--preflight--publish) |
| A-PROMO | `/org/.../promotions` | 02 | CRUD promo | [admin](screens/admin.md#a-promo--khuyến-mãi) |
| A-BANK | `/org/.../bank-accounts` | 02 | CRUD TK nhận tiền | [admin](screens/admin.md#a-bank--tài-khoản-nhận-tiền) |
| A-MEMBERS | `/org/.../members` | 01–02 | Invite/role | [admin](screens/admin.md#a-members--thành-viên) |
| A-REVIEW | `/org/.../payments/review` | 04 | MANUAL_REVIEW | [admin](screens/admin.md#a-review--hàng-đợi-đối-soát) |
| A-REFUND | drawer | 04 | Resolve / refund | [admin](screens/admin.md#a-refund--drawer-resolve--hoàn-tiền) |
| P-TENANTS | `/platform/tenants` | 01+ | Platform tenants | [admin](screens/admin.md#p-tenants--platform-tenants) |
| P-AUDIT | `/platform/audit` | 04 | Audit search | [admin](screens/admin.md#p-audit--audit-log) |

## Scanner (`web-scanner`)

| ID | Route | Milestone | Mục đích | Spec |
| --- | --- | --- | --- | --- |
| S-LOGIN | `/login` | 04 | OIDC staff | [scanner](screens/scanner.md#s-login--đăng-nhập-scanner) |
| S-HOME | `/` | 04 | Session + camera | [scanner](screens/scanner.md#s-home--chọn-suất--quét) |
| S-RESULT | overlay | 04 | Kết quả check-in | [scanner](screens/scanner.md#s-result--overlay-kết-quả) |

## Out of MVP UI

- Native RN screens (**spec** [mobile/README.md](mobile/README.md); ship store sau gate web)
- Offline scanner queue UI
- Recommendation carousel
- Complex BI charts
- Seat map drag-and-drop WYSIWYG nâng cao (MVP: grid + CSV)
- Native organizer / platform admin apps
