# Plan Frontend — 4 app Next.js

Triển khai theo [kiến trúc v2](../README.md) và [hướng thiết kế v2](../ui-direction.md). Đọc cùng [plan/README.md](README.md).

---

## 1. Bốn app

| App | Port | Persona | Nền | Ghi chú |
| --- | --- | --- | --- | --- |
| `web-customer` | 3000 | Khách (mobile là kênh chính) | Sáng | SSR/ISR cho SEO |
| `web-admin` | 3001 | Tổ chức | Sáng | Desktop-first, dùng được tablet |
| `web-platform` | 3003 | **Superadmin** | Sáng | **Mới ở v2** — tổ chức, địa điểm, tiền |
| `web-scanner` | 3002 | Nhân viên soát vé | **Tối** | Bundle nhẹ nhất, có lý do riêng |

`web-platform` tách khỏi `web-admin` vì ranh giới tài chính là ranh giới bảo mật: nó gọi `finance` deployable, có client OIDC riêng, và không bao giờ nên chạy chung bundle với app mà tổ chức dùng.

## 2. Stack

| Hạng mục | Chọn |
| --- | --- |
| Framework | Next.js 15, App Router, TypeScript strict |
| Monorepo | pnpm workspaces + Turborepo |
| Styling | CSS Modules + CSS variables từ `packages/tokens` |
| Server state | TanStack Query v5 |
| Client state | Zustand — **chỉ** cho sơ đồ chỗ ngồi và trình thiết kế chỗ ngồi |
| Form | React Hook Form + Zod |
| Auth | Auth.js (NextAuth v5) + Keycloak, **4 client** |
| API client | `packages/ts-sdk` sinh từ OpenAPI của từng service |
| QR render | `qrcode` (customer) |
| QR đọc | `BarcodeDetector` + `zxing-wasm` fallback (scanner) |
| Bảng dữ liệu | TanStack Table — `web-platform` nhiều bảng |
| Test | Vitest + Testing Library + MSW; Playwright E2E |
| i18n | `next-intl`, `vi-VN` mặc định, khoá `en` |

**Không dùng** UI kit nặng (MUI/Ant): [ui-direction](../ui-direction.md) mô tả một ngôn ngữ hình ảnh riêng; kéo theme kit vào tốn công chống lại nó hơn là tự dựng ~30 component.

## 3. Packages dùng chung

```text
packages/
├── tokens/      CSS variables (§3 ui-direction), reset, thang chữ
├── ui/          Button, Input, Select, Table, Drawer, Modal, Toast, Badge,
│                Skeleton, EmptyState, ErrorState, Countdown, CopyField,
│                EventCard, CarouselRow, CategoryChips, MoneyText
├── seatmap/     ← MỚI: renderer SVG dùng chung customer + admin preview
├── ts-sdk/      client + type + TanStack Query hooks
├── auth/        Auth.js config, middleware, useAccessToken
└── config/      eslint, tsconfig, vitest
```

`packages/seatmap` là điểm tái sử dụng quan trọng nhất: màn khách (`C-SEATS`) và màn xem trước của trình thiết kế (`A-SEATING-PLAN`) vẽ **cùng một** cấu trúc dữ liệu. Viết hai lần là cầm chắc lệch nhau.

## 4. Auth — phương án lai, 4 client

```
Browser ──login──> Next.js route handler ──OIDC PKCE──> Keycloak
   │  cookie httpOnly (session id)          │ session server giữ access + refresh
   └── GET /api/auth/token ────────────────>│ trả access token ngắn hạn
              └──> giữ trong memory → WebSocket + gọi API trực tiếp
```

Luật cứng:
1. Refresh token **không bao giờ** rời server.
2. Access token **không bao giờ** chạm `localStorage`/`sessionStorage`.
3. RSC / Server Action đọc token từ session phía server.
4. Role lấy từ session, không từ payload client gửi.

Bốn client Keycloak riêng: `web-customer`, `web-admin`, `web-platform`, `web-scanner`. MFA ép ở phía Keycloak cho `web-admin` (role `ORG_ADMIN`+) và `web-platform` (`SUPER_ADMIN`) — không ép ở app.

**Scanner có đường đăng nhập thứ hai:** mã truy cập theo suất diễn ([ADR-1008](../adr/ADR-1008-scanner-access-codes.md)). Nhân viên nhập mã 8 ký tự → nhận token phạm vi hẹp, lưu `sessionStorage`, hết hạn theo giờ sự kiện. Đường này **không** qua Keycloak.

## 5. Tầng dữ liệu

| Loại | Cấu hình |
| --- | --- |
| Catalog công khai | `staleTime: 60s`, prefetch trên server |
| Sơ đồ chỗ ngồi | `staleTime: 0`; cập nhật chủ yếu qua WS, `GET` để khởi tạo và refetch khi lệch version |
| Đơn chờ thanh toán | `refetchInterval: 6s` **chỉ khi tab visible** |
| Bảng admin/platform | `staleTime: 30s`, invalidate sau mutation |

**Idempotency-Key** sinh khi người dùng **bấm nút**, không phải khi gửi request — retry phải dùng lại đúng key, nếu không sẽ tạo hold/order thứ hai. Reset key khi người dùng đổi lựa chọn.

**WebSocket:**

```ts
onMessage({ sessionId, version, changes }) {
  if (version <= local) return;                  // cũ, bỏ
  if (version > local + 1) scheduleRefetch();    // lệch → refetch CÓ JITTER 0–2s
  else applyChanges(changes);
}
```

Jitter là bắt buộc: 10.000 client cùng phát hiện lệch và cùng gọi `GET seats` sẽ tự đánh sập backend.

## 6. Sơ đồ chỗ ngồi — component nặng nhất

Chốt **SVG**. Kiến trúc trong `packages/seatmap`:

```
<SeatMapView>                đọc dữ liệu, quản lý zoom/pan, hạn mức
├── <ZoneChips>              lọc theo khu vực
├── <SeatLayer>              <svg>: <rect> cho ghế đánh số — render MỘT LẦN
├── <StandingZoneLayer>      vùng tô + số chỗ còn lại + bộ tăng giảm
├── <SeatLegend>             6 trạng thái ghế + 1 trạng thái vùng đứng
└── <SelectionTray>          đã chọn, tổng tiền, CTA
```

Sáu quy tắc hiệu năng, quyết định thành bại của màn này:

1. **Cập nhật trạng thái bằng DOM attribute, không qua React.** Delta WS → `el.dataset.status = 'HELD_OTHER'`; màu do CSS lo. Không re-render 1.500 node cho mỗi delta.
2. **Event delegation** — một `onClick` trên `<svg>`, đọc `dataset.seatId`.
3. Ô chạm trong suốt ≥ 44px chồng lên ô hiển thị nhỏ.
4. Optimistic select đổi màu ngay 150ms, rollback nếu API trả 409.
5. Vùng đứng **không vẽ từng đơn vị** — backend không trả xuống, frontend không sinh ra.
6. Lọc theo khu vực = ẩn/hiện bằng CSS, không tháo node khỏi DOM.

Hạn mức: `purchaseAllowance` hiện ngay đầu màn; bộ tăng giảm vé đứng và việc chọn ghế đều bị chặn khi chạm trần, kèm giải thích.

## 7. Bảng lỗi — mã → tiếng Việt

Một từ điển duy nhất trong `packages/ui/errors.ts` cho cả 4 app.

| `code` | Copy | Cách hiện |
| --- | --- | --- |
| `SEAT_UNAVAILABLE` | Ghế vừa được người khác giữ | Toast + bỏ ghế lỗi + refetch |
| `ZONE_SOLD_OUT` | Khu vực này đã hết vé đứng | Toast + cập nhật số còn lại |
| `TOO_MANY_SEATS` | Vượt số vé tối đa mỗi lần giữ | Toast, nêu rõ trần hiện hành |
| `PURCHASE_LIMIT_EXCEEDED` | Bạn đã đạt giới hạn vé cho suất này | Banner + hiện đã mua/còn lại |
| `SESSION_NOT_ON_SALE` | Suất này chưa mở bán hoặc đã đóng | Banner |
| `REDIS_UNAVAILABLE` | Hệ thống đang bận, thử lại | Toast + nút thử lại, **giữ nguyên lựa chọn** |
| `HOLD_EXPIRED` | Hết thời gian giữ chỗ | Modal → về sơ đồ |
| `PROMOTION_INVALID` | Mã giảm giá không hợp lệ | Inline dưới ô nhập |
| `INVALID_TOKEN` / `TICKET_NOT_VALID` / `WRONG_ORGANIZATION` | Mã không hợp lệ / Vé đã huỷ / Vé không thuộc tổ chức này | Overlay đỏ (scanner) |
| `ZONE_CAPACITY_EXCEEDED` / `DUPLICATE_SEAT_CODE` / `ZONE_WITHOUT_TIER` | — | Lỗi inline trong trình thiết kế chỗ ngồi |
| `LIMIT_EXCEEDS_PLATFORM_CEILING` | Vượt giới hạn nền tảng cho phép | Inline, nêu trần |
| `PLAN_FROZEN` / `SEAT_ALREADY_SOLD` | Không sửa được vì đã bán vé | Modal giải thích |

## 8. `web-customer`

| Màn | Route | Giai đoạn | Điểm đáng lưu |
| --- | --- | --- | --- |
| C-LOGIN | `/login` | G0 | Chỉ nút → IdP; giữ `returnUrl` |
| C-HOME | `/` | G1 | **Hero xoay vòng → chip thể loại → dải cuộn ngang** ([ui-direction §4](../ui-direction.md)) |
| C-LIST | `/events` | G1 | Search params là nguồn chân lý; debounce 300ms; infinite scroll |
| C-DETAIL | `/events/[slug]` | G1 | SSR + ISR 60s; chọn suất; CTA dính đáy trên mobile |
| C-SEATS | `/events/[slug]/sessions/[id]/seats` | G2 | §6. Khu vực, ngồi + đứng, hạn mức. Ẩn bottom nav |
| C-HOLD | `/checkout/hold/[holdId]` | G3 | Cần `GET /v1/holds/{id}` để F5 không mất trang |
| C-PAY | `/checkout/orders/[orderId]/pay` | G3 | QR ≥ 70% chiều rộng; copy nội dung CK 1 chạm; **trạng thái thứ ba** (§8.1) |
| C-ORDER(S) | `/checkout/orders/[id]`, `/me/orders` | G3 | Tiếp tục thanh toán **không** tạo đơn mới |
| C-TICKETS / C-TICKET | `/me/tickets[/id]` | G6 | Vé đứng hiện tên khu vực thay cho số ghế |
| C-ACCOUNT | `/account` | G0 | |

### 8.1 Trạng thái thứ ba của C-PAY — hệ quả của microservices

Giữa `OrderPaid` và `TicketsIssued` có một khoảng (thường < 1 giây, có thể vài giây khi broker trễ).

| Đơn | Vé đã có? | UI |
| --- | --- | --- |
| `AWAITING_PAYMENT` | Không | QR + đếm ngược |
| `PAID` | **Chưa** | ✅ "Đã nhận thanh toán — đang phát hành vé…" + spinner |
| `PAID` | Rồi | Chuyển sang màn vé |

Không xử lý thì khách thấy "đã thanh toán" nhưng "Vé của tôi" trống — và họ gọi tổng đài. v1 không có tình huống này.

### 8.2 Ba quy tắc phải test kỹ

1. Bấm hai lần "Giữ chỗ" không tạo hai hold — khoá CTA khi pending + Idempotency-Key ổn định.
2. Reload ở mỗi bước khôi phục đúng trạng thái.
3. Back từ C-PAY khi còn `AWAITING_PAYMENT` phải hỏi xác nhận.

## 9. `web-admin` (tổ chức)

| Màn | Giai đoạn | Ghi chú |
| --- | --- | --- |
| A-LOGIN | G0 | Không membership → "Chưa thuộc tổ chức"; multi-org → chọn |
| A-MEMBERS | G0 | Mời thành viên; chặn xoá `ORG_OWNER` cuối |
| A-SCANNER-CODES | G1 | **Mới** — phát hành/thu hồi mã soát vé theo suất |
| A-VENUE-LIST / A-VENUE | G1 | Địa điểm dùng chung (chỉ xem) + **địa điểm riêng (tự dựng mặt bằng)** |
| A-SEATING-PLAN | G1 | **Thay A-SEATMAP** — màn khó nhất, xem §9.1 |
| A-EVENT (wizard 5 bước) | G1 | Autosave mỗi bước; `?step=3` trong URL |
| A-PUBLISH | G1 | Checklist 4 mục; **trạng thái "Đang xuất bản…"** (saga bất đồng bộ) |
| A-PROMO | G1 | |
| A-LIMITS | G1 | **Mới** — trần mua vé của tổ chức; hiện **giá trị hiệu lực + kế thừa từ đâu** |
| A-DASH | G6 | **Chỉ 2 con số tài chính**: số vé bán, số tiền bán. Cộng dữ liệu vận hành |

Đã **gỡ khỏi** `web-admin` so với v1: `A-BANK`, `A-REVIEW`, `A-REFUND` — chuyển sang `web-platform` ([ADR-1010](../adr/ADR-1010-superadmin-tenancy-and-finance-visibility.md)).

### 9.1 A-SEATING-PLAN — trình thiết kế chỗ ngồi

Màn phức tạp nhất của toàn dự án phía frontend.

```text
┌─────────────────────────────────────────────────────────┐
│ Suất 01/11 19:00 · Địa điểm: Nhà thi đấu X · v3         │
│ Tổng chỗ bán được: 4.240        [Kiểm tra] [Lưu mẫu]    │
├──────────────────────┬──────────────────────────────────┤
│ KHU VỰC              │                                  │
│ ☑ Khán đài A  CỐ ĐỊNH│      XEM TRƯỚC (SVG)             │
│    1.200 ghế · [VIP▾]│      dùng packages/seatmap       │
│ ☑ Khán đài B  CỐ ĐỊNH│                                  │
│    1.800 ghế · [Th▾] │                                  │
│ ☑ Sân TT   LINH HOẠT │                                  │
│    ĐỨNG · [2000] chỗ │                                  │
│    tối đa 2.500      │                                  │
│ ☑ Khu VIP  LINH HOẠT │                                  │
│    NGỒI · 2 khối     │                                  │
│    ┌ Khối FLOOR-A ─┐ │                                  │
│    │ 10 hàng × 24  │ │                                  │
│    │ nhãn A,B,C…   │ │                                  │
│    └───────────────┘ │                                  │
│    [+ Thêm khối]     │                                  │
│ ☐ Khán đài C  (tắt)  │                                  │
└──────────────────────┴──────────────────────────────────┘
```

Nguyên tắc:

- Khu vực **cố định** hiện huy hiệu "CỐ ĐỊNH" và **không có** nút sửa ghế — chỉ bật/tắt, gán hạng vé, chặn ghế.
- Khu vực **linh hoạt + ngồi**: thêm/sửa khối bằng **form tham số** (số hàng, số ghế/hàng, kiểu nhãn, khoảng cách, gốc, xoay). **Không kéo thả.**
- Khu vực **linh hoạt + đứng**: một ô nhập sức chứa, hiện rõ trần.
- **Xem trước gọi `GET /preview`** — backend sinh, frontend chỉ vẽ. Không tự tính chỗ ở client, nếu không sẽ lệch với kết quả materialize.
- `POST /validate` trước khi cho publish; lỗi map vào đúng khu vực/khối gây ra.
- Đã bán vé → khoá các thao tác trừ "thêm khối" và thao tác trên chỗ chưa bán; hiện banner giải thích.

## 10. `web-platform` (superadmin) — app mới

| Màn | Giai đoạn | Nội dung |
| --- | --- | --- |
| P-LOGIN | G0 | OIDC + MFA bắt buộc |
| P-ORGS | G0 | Tạo tổ chức + mời chủ sở hữu; khoá/mở; hồ sơ pháp nhân |
| P-VENUES / P-VENUE | G1 | Địa điểm dùng chung: phiên bản mặt bằng, khu vực, **nhập ghế cố định (grid + CSV)**, kích hoạt, nâng cấp venue riêng |
| P-CONFLICTS | G1 | Hàng đợi trùng lịch; đánh dấu đã xử lý |
| P-LIMITS | G1 | Trần mua vé cứng của nền tảng |
| P-ESCROW | G3 | Tài khoản ký quỹ nhận tiền |
| P-PAYMENTS-REVIEW | G3 | Hàng đợi `MANUAL_REVIEW`; xử lý có lý do bắt buộc |
| P-LEDGER | G4 | Bảng cân đối thử; sao kê tài khoản; xem định khoản |
| P-RECON | G4 | Nhập sao kê ngân hàng, đối soát hằng ngày, đóng ngày |
| P-PAYOUTS | G5 | Tạo lệnh chi, lô, **duyệt bốn mắt**, xác nhận đã chuyển, xuất bảng kê |
| P-AUDIT | G6 | Tra cứu audit; mask số tài khoản |

Đặc thù app này: **nhiều bảng, ít đồ hoạ**. TanStack Table + bộ lọc bền trên URL. Mọi hành động lên tiền có modal xác nhận nêu rõ hệ quả, và ô nhập lý do bắt buộc ở nơi quy định.

P-RECON và P-PAYOUTS là hai màn mà một thao tác sai làm mất tiền thật — cần xác nhận hai bước và hiện lại số liệu để người dùng đối chiếu trước khi bấm.

## 11. `web-scanner`

Ba màn (S-LOGIN, S-HOME, S-RESULT overlay) nhưng chạy ở điều kiện tệ nhất: điện thoại nhân viên, mạng nhà thi đấu, ngoài trời.

| Yêu cầu | Cách làm |
| --- | --- |
| Bundle nhẹ | Không import `packages/ui` toàn bộ; mục tiêu < 150KB JS |
| Đăng nhập | OIDC **hoặc** mã truy cập theo suất ([ADR-1008](../adr/ADR-1008-scanner-access-codes.md)) |
| Quét QR | `BarcodeDetector` → fallback `zxing-wasm` (Safari iOS) |
| Camera sau | `facingMode: 'environment'`; copy rõ khi bị từ chối quyền |
| Không quét đôi | Khoá UI đến khi có response + cooldown 500ms + nhớ mã vừa quét |
| Đọc ngoài trời | Nền tối, chữ cực lớn, **không chỉ dựa vào màu** |
| Offline | `navigator.onLine` + ping; offline → banner, **dừng submit** |
| Nhập tay | Luôn có ô dán mã — camera hỏng thì cửa vẫn chạy |
| Màn hình | Khoá dọc, safe area, `wake lock` |

Mục tiêu cảm nhận: **< 2 giây** từ đưa mã vào khung đến khi thấy kết quả.

## 12. Ánh xạ theo giai đoạn

| Giai đoạn | Backend | Frontend |
| --- | --- | --- |
| G0 (1–4) | Nền, identity | Dựng 4 app, tokens, `ui`, auth 4 client, P-ORGS, A-MEMBERS, C-LOGIN |
| G1 (5–9) | Catalog, địa điểm | P-VENUE (nhập ghế cố định), **A-SEATING-PLAN**, A-EVENT wizard, A-PUBLISH, A-LIMITS, C-HOME, C-LIST, C-DETAIL, P-CONFLICTS |
| G2 (10–13) | Inventory | **C-SEATS** + `packages/seatmap` (ngồi + đứng + hạn mức), WS client |
| G3 (14–17) | Checkout | C-HOLD, C-PAY (+ trạng thái thứ ba), C-ORDER(S), P-ESCROW, P-PAYMENTS-REVIEW |
| G4 (18–21) | Sổ cái | P-LEDGER, P-RECON |
| G5 (22–24) | Chi trả | P-PAYOUTS (duyệt bốn mắt, bảng kê) |
| G6 (25–27) | Vận hành | C-TICKETS/C-TICKET, scanner 3 màn, A-DASH, P-AUDIT |
| G7 (28–30) | Cứng hoá | QA mobile thật, a11y, hiệu năng, sửa lỗi |

## 13. Test

| Loại | Cover |
| --- | --- |
| Unit | Hook đếm ngược (lệch giờ, tab nền), map lỗi, format VND, hook idempotency key |
| Component + MSW | Mọi màn ở 4 trạng thái: loading / rỗng / lỗi / có dữ liệu |
| `packages/seatmap` | Áp delta đúng; lệch version → refetch; rollback optimistic; trần mỗi lần giữ; trần cộng dồn; vùng đứng tăng giảm |
| Trình thiết kế | Khu vực cố định không sửa được ghế; vượt sức chứa báo đúng khối; xem trước khớp kết quả materialize |
| E2E Playwright | 5 luồng: superadmin tạo tổ chức + địa điểm → tổ chức publish sự kiện hỗn hợp → khách mua (ngồi + đứng) → soát vé → superadmin đối soát & chi trả |
| Đa client | 2 browser context: ghế A giữ hiện đúng trên máy B |
| A11y | axe-core: tương phản, thứ tự focus, vùng chạm |
| Mobile | Safari iOS + Chrome Android trước go-live |

Bài test "2 trình duyệt" là bài kiểm tra thật của tính realtime — không thay được bằng unit test.

## 14. Hiệu năng

| Màn | Ngân sách |
| --- | --- |
| C-HOME / C-LIST | LCP < 2,5s trên 4G; JS < 200KB gzip |
| C-SEATS | Vẽ 1.500 ghế < 500ms; áp delta WS < 16ms (1 frame) |
| Scanner | JS < 150KB; decode → overlay < 300ms |
| P-LEDGER | Bảng 10.000 dòng cuộn mượt — dùng virtualization |

Kỹ thuật: RSC cho phần tĩnh, `next/image` cho ảnh sự kiện, `next/font` self-host, code-split `packages/seatmap` và thư viện QR.

## 15. Thứ tự ưu tiên nếu phải cắt

1. Auth + layout + tokens.
2. C-SEATS + C-HOLD + C-PAY + C-TICKET — **luồng ra tiền**.
3. C-LIST + C-DETAIL.
4. Scanner 3 màn.
5. A-SEATING-PLAN + A-EVENT + A-PUBLISH.
6. P-ORGS + P-VENUE.
7. P-LEDGER + P-RECON + P-PAYOUTS.
8. C-HOME đánh bóng, A-DASH, P-AUDIT, animation.

Mục 7 **không cắt được** dù nằm gần cuối: giữ tiền mà không có màn đối soát và chi trả thì không vận hành được. Nếu tiến độ căng thì làm bản thô (bảng + nút) thay vì cắt.
