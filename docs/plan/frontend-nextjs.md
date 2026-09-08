# Plan — Frontend (Next.js + TypeScript)

Ba app web theo [ui/README.md](../ui/README.md): `web-customer` (:3000), `web-admin` (:3001), `web-scanner` (:3002). React Native **không** chặn go-live (ADR-0002).

Đọc trước: [../BRAINSTORM.md](../BRAINSTORM.md) §8 (auth, render seat map, 3 app hay 1 app) — plan này thực thi các quyết định đó.
Nguồn spec màn hình: [ui/screens/](../ui/screens/) · [ui/states.md](../ui/states.md) · [ui/design-direction.md](../ui/design-direction.md).

---

## 1. Stack chốt

| Hạng mục | Chọn | Lý do |
| --- | --- | --- |
| Framework | Next.js 15, App Router, TypeScript strict | SSR cho catalog (SEO), RSC giảm bundle |
| Package manager | pnpm workspaces + Turborepo | Chia sẻ `packages/*` giữa 3 app |
| Styling | CSS Modules + CSS variables từ `packages/tokens` | `design-direction.md` đã cho sẵn biến CSS; không cần Tailwind để dịch lại chúng |
| Server state | TanStack Query v5 | Stale-while-revalidate đúng như `states.md` §1 yêu cầu |
| Client state | Zustand — **chỉ** cho seat map | Mọi chỗ khác dùng state cục bộ |
| Form | React Hook Form + Zod | Wizard admin nhiều bước, validate phức tạp |
| Auth | Auth.js (NextAuth v5) + Keycloak provider | ADR-0016 |
| API client | `packages/ts-sdk` sinh từ OpenAPI | Contract-first |
| QR render | `qrcode` (customer) | Nhận chuỗi payload từ BE, render client |
| QR đọc | `BarcodeDetector` API + `zxing-wasm` fallback | Scanner |
| Test | Vitest + Testing Library + MSW; Playwright cho E2E | |
| i18n | `next-intl`, mặc định `vi-VN`, khoá `en` để trống | PD-11 |

**Không dùng:** UI kit nặng (MUI/Ant) — `design-direction.md` mô tả một ngôn ngữ hình ảnh riêng, kéo theme kit vào sẽ tốn công chống lại nó hơn là tự dựng ~20 component.

## 2. Packages dùng chung

```text
packages/
├── tokens/       CSS variables (§3), reset, typography scale
├── ui/           Button, Input, Select, Table, Drawer, Modal, Toast, Badge,
│                 Skeleton, EmptyState, ErrorState, Countdown, CopyField
├── ts-sdk/       client + type + TanStack Query hooks sinh từ OpenAPI
├── auth/         cấu hình Auth.js, middleware bảo vệ route, hook useAccessToken
└── config/       eslint, tsconfig, vitest base
```

Ba app đều import 5 package này. `packages/ui` **không** chứa logic nghiệp vụ — không biết ghế, đơn hay vé là gì.

## 3. Design tokens

Copy nguyên khối `:root` từ [design-direction.md](../ui/design-direction.md) vào `packages/tokens/tokens.css`. Điểm cần quyết ngay tuần 2:

- **Customer + Scanner:** dark (`--nt-bg: #0c1210`).
- **Admin:** docs cho phép chọn nền sáng để đọc bảng lâu. **Chốt: admin dùng nền sáng** (`--nt-bg: #f4f6f3`, text dark) — nhân viên vận hành nhìn bảng số hàng giờ. Cùng accent lime, cùng radius, cùng font. **Không có theme toggle** (docs chốt vậy).
- Tránh đúng những gì `design-direction.md` liệt kê: gradient tím-indigo, glow tím, pill cluster stats trên hero.

`--nt-focus` áp cho mọi phần tử tương tác. Kiểm tra contrast AA cho CTA lime trên nền tối ngay khi dựng token, không để đến QA.

## 4. Auth — phương án lai (brainstorm §8.1)

```
Browser ──login──> Next.js route handler ──OIDC PKCE──> Keycloak
   │                      │
   │  cookie httpOnly     │ session server-side giữ access + refresh token
   │  (session id)        │
   │                      │
   └── GET /api/auth/token ──> trả access token ngắn hạn (chỉ khi cần)
              │
              └──> giữ trong memory (React context) → dùng cho WebSocket + gọi API trực tiếp
```

Luật cứng:

1. **Refresh token không bao giờ rời server.**
2. **Access token không bao giờ chạm `localStorage`/`sessionStorage`** — chỉ trong memory, mất khi reload (lấy lại bằng `/api/auth/token`).
3. RSC/Server Action đọc token từ session server-side, không qua browser.
4. Middleware `packages/auth` bảo vệ route theo nhóm; role lấy từ session, không từ token do client gửi.

**Ba client OIDC riêng** (`web-customer`, `web-admin`, `web-scanner`) như ADR-0016. Admin và scanner ép MFA ở phía Keycloak, không phải ở app.

`returnUrl`: C-SEATS cho phép xem khi chưa login nhưng hold thì cần login → lưu ghế đang chọn vào `sessionStorage` ≤ 2 phút, login xong quay lại đúng chỗ (spec C-SEATS yêu cầu).

## 5. Tầng dữ liệu

### 5.1 TanStack Query

| Loại dữ liệu | Cấu hình |
| --- | --- |
| Catalog công khai | `staleTime: 60s`, prefetch trên server |
| Seat map | `staleTime: 0`, cập nhật chủ yếu qua WS, `GET` chỉ để bootstrap + refetch khi gap |
| Order đang chờ trả tiền | `refetchInterval: 6s` **chỉ khi tab visible** (`states.md` §5) |
| Danh sách admin | `staleTime: 30s`, invalidate sau mutation |

### 5.2 Idempotency-Key

Mọi mutation cần key. **Key phải ổn định qua các lần retry của cùng một ý định người dùng**, nếu không retry sẽ tạo hold/order thứ hai:

```ts
// Sinh key khi người dùng bấm nút, không phải khi gửi request
const key = useIdempotencyKey('create-order', holdId);   // uuid v7, nhớ theo (scope, subject)
```

Reset key khi người dùng đổi lựa chọn (chọn ghế khác ⇒ ý định mới ⇒ key mới).

### 5.3 WebSocket

```ts
// packages/ts-sdk/realtime.ts
connect(sessionId, accessToken)
  → onMessage({ sessionId, version, changes })
      if (version <= local) return;                    // cũ, bỏ
      if (version > local + 1) scheduleRefetch();      // gap → refetch có jitter
      else applyChanges(changes);
```

**Bắt buộc jitter 0–2s + debounce khi refetch** (brainstorm §6.3) — 10.000 client cùng phát hiện gap và cùng gọi `GET seats` sẽ tự đánh sập backend.

Mất kết nối: banner "Đang cập nhật chậm…" + poll 10s (`states.md` §6), reconnect với exponential backoff + jitter.

## 6. Seat map — component nặng nhất dự án

Chốt **SVG** (brainstorm §8.2). Kiến trúc:

```
<SeatMapView>                     đọc dữ liệu, quản lý zoom/pan
├── <SectionChips>                lọc theo khu
├── <SeatLayer>                   <svg> chứa N <rect>, render MỘT LẦN
├── <SeatLegend>                  6 trạng thái bắt buộc
└── <SelectionTray>               ghế đã chọn, tổng tiền, CTA
```

Nguyên tắc hiệu năng — quyết định thành bại của màn này:

1. **Cập nhật trạng thái ghế bằng DOM attribute, không qua React.** Delta WS → `seatEl.dataset.status = 'HELD_OTHER'`, màu do CSS `[data-status="HELD_OTHER"] { fill: var(--nt-held-other) }` lo. React không re-render 1.500 node cho mỗi delta.
2. **Event delegation** — một `onClick` trên `<svg>`, đọc `event.target.dataset.seatId`. Không gắn 1.500 handler.
3. Ghế đang chọn của mình (`--nt-held-mine`) phải khác rõ ghế người khác giữ (`--nt-held-other`) — yêu cầu UX #3 của `ui/README.md`.
4. Hit area ≥ 44px trên mobile: `<rect>` hiển thị nhỏ, thêm `<rect>` trong suốt lớn hơn ở trên để bắt chạm.
5. Optimistic select đổi màu ngay 150ms (`design-direction.md` motion #1), rollback nếu API trả 409.
6. Pan + section filter là Must; pinch-zoom là Should.
7. A11y (Should): toggle "danh sách ghế trống" theo khu — canvas SVG khó cho screen reader, danh sách là đường thoát.

## 7. Countdown — dùng chung, dễ sai

Hai countdown (hold 5 phút, payment 15 phút) đều **lấy `expiresAt` từ server**, không tin đồng hồ máy client (UX rule #2).

```ts
useServerCountdown(expiresAt) {
  // tick 1s bằng setInterval
  // đồng bộ lại mỗi 'visibilitychange' và 'focus'  ← quan trọng: tab nền bị throttle
  // trả { mmss, secondsLeft, isWarning, isExpired }
}
```

- Hold: warn (`--nt-warn`) khi < 60s; hết giờ → modal "Hết thời gian giữ ghế" → về C-SEATS.
- Payment: warn khi < 2 phút; hết giờ → state EXPIRED, **ẩn QR**, CTA về event.
- **Không có nút gia hạn** (docs chốt).
- Lệch đồng hồ client/server: tính offset một lần từ header `Date` của response, áp cho mọi countdown.

## 8. Bảng lỗi — mã → copy tiếng Việt

Một từ điển duy nhất trong `packages/ui/errors.ts`, dùng cho cả 3 app. BE trả `code`, FE quyết cách hiển thị.

| `code` | HTTP | Copy | Cách hiện |
| --- | --- | --- | --- |
| `SEAT_UNAVAILABLE` | 409 | "Ghế vừa được người khác giữ" | Toast + bỏ ghế lỗi + refetch |
| `SESSION_NOT_ON_SALE` | 409 | "Suất này chưa mở bán hoặc đã đóng" | Banner |
| `TOO_MANY_SEATS` | 400 | "Tối đa 8 ghế mỗi lần giữ" | Toast |
| `REDIS_UNAVAILABLE` | 503 | "Hệ thống đang bận, thử lại" | Toast + nút Thử lại, **giữ nguyên lựa chọn** |
| `HOLD_EXPIRED` | 409 | "Hết thời gian giữ ghế" | Modal → C-SEATS |
| `HOLD_NOT_OWNED` | 403 | "Phiên giữ ghế không hợp lệ" | Redirect C-SEATS |
| `PROMOTION_INVALID` | 400 | "Mã giảm giá không hợp lệ hoặc đã hết hạn" | Inline dưới ô nhập |
| `NO_BANK_ACCOUNT` | 409 | "Sự kiện chưa sẵn sàng nhận thanh toán" | Banner |
| `NO_ACTIVE_BANK_ACCOUNT` | 422 | — | Item đỏ trên checklist A-PUBLISH |
| `SEATS_WITHOUT_TIER` | 422 | — | Item đỏ trên checklist A-PUBLISH |
| `INVALID_SALES_WINDOW` | 422 | — | Item đỏ trên checklist A-PUBLISH |
| `INVALID_TOKEN` | 400 | "Mã không hợp lệ hoặc đã hết hạn" | Overlay đỏ |
| `TICKET_NOT_VALID` | 409 | "Vé đã huỷ hoặc hoàn tiền" | Overlay đỏ |
| `WRONG_ORGANIZATION` | 403 | "Vé không thuộc tổ chức này" | Overlay đỏ |
| `IDEMPOTENCY_KEY_REUSED` | 409 | "Yêu cầu trùng lặp" | Toast (hiếm khi người dùng thấy) |

Quy tắc copy (`design-direction.md`): câu tiếng Việt ngắn cho người, **kèm mã** ở dòng phụ để hỗ trợ tra cứu.

---

## 9. `web-customer` — kế hoạch theo màn

| Màn | Route | Tuần | Điểm kỹ thuật đáng lưu |
| --- | --- | --- | --- |
| C-LOGIN | `/login` | 2 | Chỉ nút → IdP; giữ `returnUrl` |
| C-ACCOUNT | `/account` | 2 | `GET/PATCH /v1/me` |
| C-LIST | `/events` | 5 | RSC + search params là nguồn chân lý (deep link giữ filter); debounce 300ms; infinite scroll |
| C-DETAIL | `/events/[slug]` | 5 | SSR + ISR 60s cho SEO; đổi session cập nhật tier; CTA sticky mang `sessionId` |
| C-HOME | `/` | 6 | First viewport chỉ brand + 1 headline + CTA — **không** stat strip, không card grid (acceptance criteria) |
| C-SEATS | `/events/[slug]/sessions/[id]/seats` | 7 | §6. Ẩn bottom nav. Legend đủ 6 trạng thái |
| C-HOLD | `/checkout/hold/[holdId]` | 8 | Cần `GET /v1/holds/{id}` (H3) — nếu không, F5 là mất trang |
| C-PAY | `/checkout/orders/[orderId]/pay` | 8 | QR ≥ 70% chiều rộng; copy reference 1 chạm; poll 6s khi visible; ẩn nav |
| C-ORDER / C-ORDERS | `/checkout/orders/[id]`, `/me/orders` | 9 | Resume pay **không** tạo order mới |
| C-TICKETS / C-TICKET | `/me/tickets`, `/me/tickets/[id]` | 9 | Render QR từ token; gợi ý tăng độ sáng màn hình |

### Luồng mua — chỗ dễ sai nhất

```
C-SEATS ──POST /holds──> C-HOLD ──POST /orders──> C-PAY ──(webhook)──> C-TICKET
   ▲          409            │       HOLD_EXPIRED      │      EXPIRED
   └───────────────────────┴─────────────────────────┘
```

Ba quy tắc phải test kỹ:
1. **Bấm hai lần nút "Giữ ghế"** không được tạo hai hold → khoá CTA khi pending + Idempotency-Key ổn định.
2. **Reload giữa chừng** ở mỗi bước phải khôi phục đúng trạng thái (hold còn hạn → về C-HOLD; order còn hạn → về C-PAY).
3. **Back từ C-PAY** khi còn `AWAITING_PAYMENT` phải hỏi xác nhận, không im lặng bỏ đơn.

### Mobile web (kênh chính, PD-07)

- Bottom nav: Khám phá / Vé / Tài khoản — **ẩn** trên C-SEATS và C-PAY (focus mode).
- CTA trong thumb zone; tray ghế không che legend.
- QA bắt buộc trên **Safari iOS** + **Chrome Android** (ADR-0002) — đặc biệt: `100vh` trên Safari, camera permission, copy-to-clipboard trên iOS.

---

## 10. `web-admin` — kế hoạch theo màn

| Màn | Tuần | Điểm kỹ thuật |
| --- | --- | --- |
| A-LOGIN | 2 | Không membership → màn "Chưa thuộc tổ chức"; multi-org → chọn org |
| A-MEMBERS | 4 | Table + drawer; chặn xoá OWNER cuối cùng |
| A-VENUE-LIST / A-VENUE | 4 | Table + drawer form |
| A-SEATMAP | 4 | Tab Grid / CSV. **DRAFT mới sửa được, ACTIVE chỉ xem** (docs chốt). CSV lỗi hiện số dòng + thông báo |
| A-EVENT-LIST | 5 | Filter theo status |
| A-EVENT (wizard 5 bước) | 5 | Autosave draft mỗi bước; refresh giữ nguyên; bước 4 highlight ghế chưa có tier |
| A-PROMO | 6 | Drawer form; validate PERCENT 1–100, FIXED > 0 |
| A-BANK | 6 | Nhập số đầy đủ khi tạo, sau đó chỉ `****last4`; copy cảnh báo "NexaTicket không giữ tiền" |
| A-PUBLISH | 6 | Checklist 4 mục; CTA disabled đến khi xanh hết; map mã lỗi API → item đỏ |
| A-DASH | 11 | 4 metric, không card-soup; CTA sang A-REVIEW khi `manualReview > 0` |
| A-REVIEW / A-REFUND | 12 | Drawer 3 hành động; `reason` bắt buộc; confirm modal |
| P-TENANTS / P-AUDIT | 12 | Chỉ `PLATFORM_ADMIN`; audit mask số tài khoản trong before/after |

### RBAC ở UI

**Ẩn nav item nếu thiếu quyền, không chỉ trả 403 sau khi bấm** (`organizer-publish.md`). Nhưng ẩn ở UI **không phải** biện pháp bảo mật — backend vẫn phải chặn. Một map role→quyền dùng chung cho việc ẩn hiện, sinh từ [rbac-permission-matrix.md](../00-discovery/rbac-permission-matrix.md).

### Wizard A-EVENT — autosave

Mỗi bước `PATCH` khi rời bước (blur/next), không autosave từng ký tự. Lưu `currentStep` trong URL (`?step=3`) để F5 và back/forward hoạt động tự nhiên.

---

## 11. `web-scanner` — nhỏ nhưng khắt khe nhất

Ba màn (S-LOGIN, S-HOME, S-RESULT overlay), nhưng chạy ở điều kiện tệ nhất: điện thoại staff, mạng nhà thi đấu, ngoài trời, hàng người đang chờ.

| Yêu cầu | Cách làm |
| --- | --- |
| Bundle nhẹ | Không import `packages/ui` toàn bộ; không font display; mục tiêu < 150KB JS |
| Quét QR | `BarcodeDetector` API (Android Chrome hỗ trợ tốt) → fallback `zxing-wasm` (Safari iOS) |
| Camera sau | `getUserMedia({ video: { facingMode: 'environment' } })`; copy rõ khi bị từ chối quyền |
| Không double-submit | Khoá UI đến khi có response + cooldown 500ms + nhớ token vừa quét, bỏ qua nếu decode lại cùng mã |
| Đọc được ngoài trời | Chữ kết quả cực lớn, nền màu đầy khung, **không chỉ dựa vào màu** — có icon + text |
| Offline | `navigator.onLine` + ping; offline → banner "Cần mạng — MVP không quét offline", **dừng submit** (ADR-0014) |
| Nhập tay | Luôn có ô dán mã — camera hỏng thì cửa vẫn chạy |
| Chọn suất | Lưu `sessionStorage` đến khi logout |
| Kết quả | Overlay full-bleed, auto dismiss 2.5s hoặc chạm; flash 300ms rồi settle |
| Âm thanh (Should) | Beep khác nhau cho hợp lệ / đã check-in; cần user gesture đầu tiên để mở AudioContext |
| Màn hình | Portrait lock, safe area cho tai thỏ, `wake lock` để màn không tắt giữa ca |

Mục tiêu cảm nhận: **< 2 giây** từ lúc đưa mã vào khung đến khi thấy kết quả (SRS).

---

## 12. Test

| Loại | Công cụ | Cover |
| --- | --- | --- |
| Unit | Vitest | Countdown hook (kể cả lệch giờ, tab nền), map lỗi, format tiền VND, hook idempotency key |
| Component | Testing Library + MSW | Mọi màn ở 4 state: loading / empty / error / có dữ liệu (`states.md` bắt buộc) |
| Seat map | Vitest + fake WS | Áp delta đúng, phát hiện gap → refetch, rollback optimistic khi 409, giới hạn 8 ghế |
| E2E | Playwright | 4 luồng acceptance của SRS §6: publish, purchase, event-day, exception |
| Đa client | Playwright 2 browser context | Ghế do A giữ hiện đúng trên máy B (acceptance C-SEATS) |
| A11y | axe-core trong Playwright | Contrast, focus order, hit area |
| Mobile | Playwright device emulation + thiết bị thật | `qa-checklist.md` mục A trước go-live |

Test "2 trình duyệt" là bài kiểm tra thật của tính realtime — không thay được bằng unit test.

## 13. Hiệu năng

| Màn | Ngân sách |
| --- | --- |
| C-HOME / C-LIST | LCP < 2.5s trên 4G, JS < 200KB gzip |
| C-SEATS | Vẽ xong 1.500 ghế < 500ms; áp delta WS < 16ms (1 frame) |
| Scanner | JS < 150KB; từ decode đến overlay < 300ms (trừ mạng) |

Kỹ thuật: RSC cho phần tĩnh, `next/image` cho ảnh sự kiện, `next/font` self-host Be Vietnam Pro (không gọi Google Fonts lúc chạy), code-split seat map và thư viện QR.

## 14. Thứ tự làm — nếu tiến độ căng

1. Auth shell + layout + tokens (mọi thứ đứng trên nó).
2. C-SEATS + C-HOLD + C-PAY + C-TICKET — **luồng ra tiền**.
3. C-LIST + C-DETAIL — không có thì không ai vào được luồng trên.
4. Scanner 3 màn — không có thì không tổ chức được sự kiện.
5. A-EVENT wizard + A-PUBLISH + A-BANK — có thể thay tạm bằng SQL seed nếu cực căng.
6. A-VENUE / A-SEATMAP.
7. A-DASH, A-REVIEW, A-REFUND, P-*.
8. C-HOME đánh bóng, animation, PWA.

Khớp với thứ tự cắt scope của [DELIVERY-PLAN-3M.md](../DELIVERY-PLAN-3M.md): cắt từ dưới lên, không bao giờ cắt tính đúng đắn của mục 2.
