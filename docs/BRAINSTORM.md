# NexaTicket — Brainstorm kỹ thuật trước khi code

**Đầu vào:** toàn bộ `docs/` (00-discovery → 05-ai-scale, ui/, ui/mobile/).
**Mục đích:** rút gọn yêu cầu thành các bài toán lõi, liệt kê phương án, chốt hướng, và **bóc ra những chỗ docs còn hở** trước khi mở dòng code đầu tiên.
**Trạng thái repo:** chỉ có `docs/`, chưa có code → đây là greenfield.

Plan thực thi: [plan/README.md](plan/README.md) · [plan/backend-spring-boot.md](plan/backend-spring-boot.md) · [plan/frontend-nextjs.md](plan/frontend-nextjs.md)

---

## 1. Yêu cầu rút gọn thành 1 câu

> Bán vé **có chọn ghế cụ thể**, realtime, cho nhiều organizer độc lập (multi-tenant), thanh toán bằng **chuyển khoản ngân hàng bất đồng bộ** (VietQR + webhook SePay), phát hành vé QR ký số, soát vé online — dưới tải **10.000 người cùng tranh một suất diễn** mà **không được bán trùng ghế**.

Mọi thứ khác (catalog, dashboard, promo, notification, AI) là vệ tinh. Bốn chữ in đậm ở trên là nơi dự án sống hoặc chết.

## 2. Bốn bài toán lõi

| # | Bài toán | Vì sao khó | Nếu sai thì sao |
| --- | --- | --- | --- |
| B1 | **Không oversell** khi 10k user tranh ghế | Nhiều ghế/1 request phải atomic; Redis + DB double-write | Bán trùng ghế → khách đến cửa không có chỗ → mất uy tín, hoàn tiền thủ công |
| B2 | **Thanh toán bất đồng bộ** qua bank transfer | Không có "authorize/capture"; tiền về sau, có thể muộn, sai tiền, trùng webhook | Phát hành vé cho đơn chưa trả tiền, hoặc thu tiền mà không có vé |
| B3 | **Tenant isolation** | Mọi query đều phải scope; 1 chỗ quên = rò dữ liệu tài chính org khác | Vi phạm dữ liệu, không cứu được bằng patch |
| B4 | **Realtime fan-out** ghế | 10k client cùng xem 1 seat map, mỗi hold sinh 1 delta | WS storm → API sập lúc đông nhất, hoặc client thấy ghế sai |

Ba việc còn lại (catalog CRUD, dashboard, scanner) là công việc kỹ thuật thường — rủi ro thấp, khối lượng cao.

---

## 3. B1 — Không oversell

### 3.1 Các phương án đã cân nhắc

| Phương án | Cách làm | Đánh giá |
| --- | --- | --- |
| A. DB-only, `SELECT … FOR UPDATE` | Lock N hàng `session_seats` trong 1 transaction | Đúng tuyệt đối, nhưng lock giữ suốt request; p95 300ms khó ở 10k VU; nguy cơ deadlock nếu không sắp xếp thứ tự lock |
| B. DB-only, optimistic (`UPDATE … WHERE status='AVAILABLE'`) | Không lock dài, retry khi affected < N | Đơn giản, an toàn; nhưng partial success cần rollback thủ công; contention cao ở ghế hot |
| C. **Redis Lua gate + DB guard** (ADR-0004) | Lua `SETNX` toàn bộ N key atomic → rồi transaction DB | Nhanh, chặn 99% tranh chấp trước khi chạm DB; DB vẫn là guard cuối | 
| D. Queue tuần tự / actor per session | 1 consumer xử lý tuần tự mọi hold của 1 session | Zero contention, nhưng thành SPOF và thêm độ trễ; khó với 10k |

**Chốt: C** — đúng ADR-0004. Nhưng ADR chỉ nói "Lua script"; phần khó nằm ở chi tiết bên dưới, chưa có trong docs.

### 3.2 Chi tiết cần chốt (docs chưa nói)

**Key design.** `hold:{eventSessionId}:{sessionSeatId}` — cặp `{}` bao quanh `eventSessionId` chính là **hash tag** của Redis Cluster, đảm bảo mọi ghế của cùng một suất nằm trên cùng slot → Lua script multi-key chạy được kể cả khi lên Cluster. Đây là thiết kế đúng, cần ghi rõ để không ai "dọn dẹp" mất cặp ngoặc.

**Thứ tự ghi Redis ↔ DB.** Hai lựa chọn:

1. Redis trước → DB sau. Redis OK nhưng DB fail → còn key rác, tự hết hạn sau 5 phút (ghế bị "kẹt" tối đa 5 phút). Chấp nhận được; thêm best-effort `DEL` trong catch.
2. DB trước → Redis sau. DB commit rồi Redis fail → ghế HELD trong DB nhưng không ai giữ key → phải chờ expiry worker. Tệ hơn.

**Chốt: Redis trước, DB sau, compensating `DEL` khi DB lỗi.**

**Deadlock DB.** Khi `POST /orders` làm `SELECT … FOR UPDATE` trên nhiều `session_seats`, hai order chồng ghế theo thứ tự ngược nhau sẽ deadlock. **Bắt buộc `ORDER BY session_seat_id` trước khi lock.** Docs chưa ghi.

**`session_seats.status` có phải nguồn hiển thị không?** Có — `GET /sessions/{id}/seats` đọc từ DB. Vậy hold phải ghi cả `status = HELD` vào DB, không chỉ Redis. Nghĩa là mỗi hold = 1 Lua + 1 DB transaction (update N ghế + insert hold + insert hold_items + bump version). Đây là chi phí thật của p95 300ms — cần đo sớm ở tuần 7, không để đến tuần 10.

### 3.3 Invariant test phải có từ tuần 7 (không đợi tuần 10)

```sql
-- Không ghế nào có 2 order còn sống
SELECT session_seat_id FROM order_items oi
JOIN orders o ON o.id = oi.order_id
WHERE o.status IN ('AWAITING_PAYMENT','PAID')
GROUP BY session_seat_id HAVING COUNT(*) > 1;
```

Chạy job này sau mỗi lần load test **và** như một scheduled check trên staging.

---

## 4. B2 — Thanh toán bất đồng bộ

### 4.1 Bản chất khác với thẻ

Với thẻ: `authorize` → biết ngay kết quả. Với chuyển khoản: user rời khỏi web, mở app ngân hàng, và **có thể không bao giờ quay lại**. Hệ quả thiết kế:

1. Nguồn chân lý của "đã trả tiền" là **webhook**, không phải hành động của user.
2. Màn C-PAY phải hoạt động đúng cả khi user đóng tab (email `OrderCreated` có deep link `/checkout/orders/{id}/pay`).
3. Mọi nhánh sai đều phải rơi vào một trạng thái **có người xử lý**, không được rơi vào khoảng trống.

### 4.2 Ma trận nhánh webhook — chốt phân loại

Docs (`sepay-webhook.md`, `refunds.md`) mô tả nhưng chưa ràng buộc chặt. Đề xuất chốt:

| Tình huống | Tiền đã về? | Phân loại | Lý do |
| --- | --- | --- | --- |
| Auth/signature sai | Không xác định | `4xx`, không ghi attempt | Không tin payload |
| `sepay_transaction_id` đã xử lý | Có | `DUPLICATE` + `2xx` | Idempotent |
| Reference không parse được / không thuộc hệ thống | **Có** | `MANUAL_REVIEW` | Tiền đã vào TK organizer — **không được** `REJECTED` rồi bỏ đó |
| Reference đúng, số tiền thiếu | Có | `MANUAL_REVIEW` | Cần con người quyết |
| Reference đúng, số tiền thừa | Có | `CONFIRMED` + ghi chú chênh lệch | Không chặn khách vào cửa vì chuyển dư |
| Reference đúng, đúng tiền, order còn hạn | Có | `CONFIRMED` → PAID + issue ticket | Happy path |
| Reference đúng, đúng tiền, order đã `EXPIRED` | Có | `MANUAL_REVIEW` | ADR-0015; ghế có thể đã bán cho người khác |
| Sai tài khoản nhận | Có | `MANUAL_REVIEW` | Snapshot bank không khớp |

**Nguyên tắc rút ra: `REJECTED` chỉ dùng khi chắc chắn không có tiền nào về tài khoản của chúng ta. Có tiền mà không khớp ⇒ luôn `MANUAL_REVIEW`.** Điều này khớp với exit criteria của runbook: *"không có transaction vô chủ"*.

### 4.3 Idempotency webhook — docs còn hở

`payment_attempts.sepay_transaction_id UNIQUE` là guard, nhưng attempt row được tạo từ lúc order (`PENDING`, chưa có transaction id). Hai webhook trùng đến **song song** sẽ cùng `UPDATE` một row `PENDING` → unique constraint không kích hoạt. Phao cứu sinh cuối là `tickets.order_item_id UNIQUE`, nhưng lúc đó đã ghi nửa vời.

**Đề xuất bổ sung bảng dedupe chuyên trách:**

```sql
webhook_events (
  id UUID PK,
  provider TEXT NOT NULL,             -- 'SEPAY'
  provider_event_id TEXT NOT NULL,    -- sepay_transaction_id
  payload_hash TEXT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  processing_status TEXT NOT NULL,    -- RECEIVED | PROCESSED | FAILED
  UNIQUE (provider, provider_event_id)
);
```

Bước 1 của handler = `INSERT … ON CONFLICT DO NOTHING`. `0 rows` ⇒ trả `2xx` ngay, dừng. Sau đó `SELECT … FOR UPDATE` trên `orders` row. Hai lớp này đóng hoàn toàn race.

### 4.4 Race kinh điển: webhook đến đúng lúc expiry worker chạy

Đây là case chaos mà `load-test-plan.md` đã liệt kê. Chốt cách xử lý: **cả hai đều phải lấy lock cùng một hàng `orders` bằng `SELECT … FOR UPDATE`, ai vào trước thắng.**
- Worker thắng → order `EXPIRED`, ghế `AVAILABLE`; webhook sau đó thấy `EXPIRED` → `MANUAL_REVIEW`. Đúng.
- Webhook thắng → order `PAID`; worker sau đó thấy `PAID` → bỏ qua (không được vô điều kiện set `EXPIRED`). Worker **phải** có `WHERE status = 'AWAITING_PAYMENT'`.

---

## 5. B3 — Tenant isolation

### 5.1 Ba tầng phòng thủ (không chỉ một)

Docs (ADR-0008) chọn "service/repository guard", RLS để sau. Rủi ro: guard là quy ước con người — một repository method mới quên `WHERE organization_id = ?` là đủ để rò. Đề xuất **3 tầng**, chi phí thấp, làm ngay ở Foundation:

1. **Tầng request:** filter dựng `TenantContext` từ JWT `sub` → membership. Không bao giờ đọc `organization_id` từ body/query để quyết định quyền (route có `{orgId}` chỉ dùng để *chọn* trong các membership đã xác thực, và phải verify).
2. **Tầng persistence:** Hibernate `@FilterDef`/`@Filter` bật tự động trên mọi entity org-owned, tham số lấy từ `TenantContext`. Quên `WHERE` thủ công vẫn an toàn.
3. **Tầng test:** một bộ IDOR test chạy tự động cho **mọi** endpoint org-scoped (parameterized theo danh sách route), không viết tay từng cái. Thêm ArchUnit rule: entity có cột `organization_id` bắt buộc khai báo filter.

Tầng 2 + 3 là thứ biến "quy ước" thành "cơ chế". Cân nhắc RLS ở Postgres sau soft launch như tầng 4.

### 5.2 Điểm dễ quên

- **Public catalog** (`GET /events`) không có tenant context — phải cứng `status = 'PUBLISHED'`. Đây là endpoint duy nhất được bỏ qua tenant filter; đánh dấu rõ ràng bằng annotation `@PublicEndpoint` để review dễ.
- **Check-in staff** chỉ được check-in vé của org mình → `WRONG_ORGANIZATION` 403. Nhưng token QR là opaque, không mang org → phải load ticket rồi so `ticket.organization_id` với membership.
- **Platform admin** ghi cross-tenant phải đi qua "support mode" có audit — nghĩa là code path riêng, không phải `if (isPlatformAdmin) skipFilter()` rải rác.

---

## 6. B4 — Realtime fan-out

### 6.1 Mâu thuẫn trong docs cần sửa

| Nguồn | Nói gì |
| --- | --- |
| `data-model.md` | `session_seats.availability_version BIGINT` — version **theo từng ghế** |
| `api/hold-order.md` | `GET /sessions/{id}/seats` trả `"version": 1842` — **một** version cho cả suất |
| ADR-0010 | "monotonic `version`" theo `sessionId` |

Hai cái không phải một thứ. Client cần **một counter đơn điệu tăng ở cấp suất diễn** để phát hiện gap. Đề xuất:

- Thêm `event_sessions.availability_version BIGINT NOT NULL DEFAULT 0` — nguồn chân lý, tăng trong cùng transaction với mọi thay đổi ghế.
- Giữ `session_seats.availability_version` như **optimistic lock cấp hàng** (hoặc bỏ hẳn nếu không dùng) — đừng để hai khái niệm trùng tên.

**Cảnh báo hiệu năng:** `UPDATE event_sessions SET availability_version = availability_version + 1` biến hàng suất diễn thành **hot row** — mọi hold trên cùng suất serialize tại đây. Ở 10k VU đây có thể chính là bottleneck p95, chứ không phải Redis.

Phương án giảm nhiệt (chốt sau khi đo ở tuần 7):
- **A:** Chấp nhận, đo trước. Postgres update 1 hàng ~vài chục nghìn/s nếu transaction ngắn; có thể đủ.
- **B:** Redis `INCR seatver:{sessionId}` là counter phát sóng, seed từ DB lúc boot, checkpoint định kỳ về DB. Nhanh nhất, nhưng Redis mất dữ liệu → version tụt lùi → phải kèm cờ `epoch` để client biết "refetch toàn bộ".
- **C:** Postgres sequence riêng cho mỗi suất (`nextval`) — không transactional-rollback-safe nhưng monotonic là đủ (client chỉ dùng để phát hiện gap, không cần liên tục).

**Nghiêng về C** — đơn giản, không mất mát, không hot row. Gap trong dãy số là chấp nhận được vì client chỉ so `version > local`.

### 6.2 Transport: WS hay SSE?

Luồng `seat.availability.changed` là **một chiều server → client**. SSE đơn giản hơn nhiều (HTTP thường, tự reconnect, không cần STOMP/handshake riêng, qua CDN/proxy dễ). Nhưng ADR-0010 và toàn bộ docs UI đã viết "WebSocket".

**Chốt: giữ WebSocket** (tôn trọng ADR), nhưng:
- Dùng WS thuần (`WebSocketHandler`), **không STOMP** — ta không cần routing của STOMP, chỉ cần một topic theo `sessionId`.
- Ghi nhận SSE là phương án dự phòng nếu vận hành WS ở 10k connection trở nên đắt; đổi transport không đổi payload contract.

### 6.3 Chống storm — bắt buộc, chưa có trong docs

Ở 10k người xem 1 suất, mỗi hold sinh 1 broadcast tới 10k connection. 100 hold/giây = 1 triệu message/giây. Không khả thi.

**Bắt buộc coalescing:** gom thay đổi trong cửa sổ **150–250ms** thành **một** message per session. 100 hold/s → ~5 msg/s × 10k = 50k msg/s — vẫn nặng nhưng khả thi, và mỗi message nhỏ.

**Multi-instance:** khi API scale ra 2+ pod, WS session nằm rải rác. Cần Redis Pub/Sub làm cầu: instance ghi DB → publish lên Redis channel `seat.session.{id}` → mọi instance nhận và đẩy xuống WS local của mình.

**Phía client:** khi phát hiện gap version → refetch, nhưng phải **jitter 0–2s** và debounce, nếu không 10k client cùng refetch một lúc sẽ tự DDoS chính mình (`load-test-plan.md` đã ghi "client refetch không storm" — đây là cách làm).

---

## 7. Những chỗ docs còn hở — cần chốt trước tuần 2

Ưu tiên theo mức chặn.

| # | Vấn đề | Đề xuất chốt | Chặn từ |
| --- | --- | --- | --- |
| H1 | `unit_price_cents` thực chất là **số đồng VND**, không phải cents | Đổi tên cột thành `unit_price_vnd` / `total_vnd` **ngay ở migration V1**, đừng mang nợ tên gọi 5 năm. API đã chốt `unitPriceVnd` | Tuần 2 |
| H2 | Version cấp suất vs cấp ghế (§6.1) | Thêm counter cấp suất; đổi tên cột cấp ghế | Tuần 2 (schema) |
| H3 | Thiếu `GET /v1/holds/{id}` | C-HOLD là route riêng (`/checkout/hold/[holdId]`) → refresh trang là mất dữ liệu nếu không có GET. **Phải có** | Tuần 7 |
| H4 | `GET /v1/orders/{id}` chưa chốt path | Chốt `GET /v1/orders/{id}` + `GET /v1/me/orders` | Tuần 8 |
| H5 | Bảng dedupe webhook (§4.3) | Thêm `webhook_events` | Tuần 8 |
| H6 | Chưa chốt cách lưu token ở Next.js | BFF: session cookie httpOnly; access token cấp cho client qua endpoint nội bộ chỉ khi cần WS (§8.1) | Tuần 2 |
| H7 | Định dạng QR token chưa chốt | JWS EdDSA compact, claims chỉ `{jti, exp}`; xoay khóa theo `kid` | Tuần 9 |
| H8 | Format `payment_reference` chưa chốt | 8 ký tự Crockford Base32 (bỏ I/L/O/U), prefix `NT`, ví dụ `NTX9F2K1`. Phải sống sót qua ô "nội dung chuyển khoản" của mọi app NH VN (viết hoa, không dấu, không ký tự đặc biệt) | Tuần 8 |
| H9 | Redis là SPOF cho hold | ADR-0004 nói fail ⇒ 503 retryable. Nghĩa là **Redis down = không bán được vé**. Chốt: Redis có replica + automatic failover trên staging/prod, không chạy single node | Tuần 3 |
| H10 | Giới hạn 8 ghế/hold chỉ nằm trong docs UI | Enforce ở **backend** (không tin client), trả `TOO_MANY_SEATS` | Tuần 7 |
| H11 | Chưa có rule cho suất diễn có nhiều nghìn ghế | Chốt trần MVP (ví dụ 5.000 ghế/suất) và bulk-insert lúc materialize bằng `COPY`/batch, không insert từng hàng | Tuần 5 |
| H12 | Timezone & tiền tệ | Toàn bộ `TIMESTAMPTZ`, app chạy UTC, hiển thị `Asia/Ho_Chi_Minh`. VND là số nguyên, không số thập phân, không `BigDecimal` cho tiền hiển thị | Tuần 2 |

---

## 8. Quyết định kiến trúc phía client cần brainstorm riêng

### 8.1 Auth trong Next.js — 3 phương án

| Phương án | Cách | Đánh giá |
| --- | --- | --- |
| A. Token trong localStorage | Client giữ access token | Đơn giản nhất, nhưng XSS lấy được token → **loại** với app có tiền |
| B. BFF thuần | Mọi call đi qua Next.js route handler, token chỉ ở server | An toàn nhất, nhưng thêm 1 hop cho seat map (nhạy latency) và **WS không dùng được** vì browser cần token để kết nối |
| C. **Lai** | Session cookie httpOnly là nguồn chân lý; client gọi `/api/auth/token` (server đọc session) lấy access token **ngắn hạn giữ trong memory** cho WS + call trực tiếp API | Cân bằng: XSS không lấy được refresh token; access token sống 5–15 phút, mất cũng giới hạn thiệt hại |

**Chốt: C.** Ghi rõ: access token **không bao giờ** chạm `localStorage`/`sessionStorage`.

### 8.2 Render seat map: SVG hay Canvas?

| Số ghế | Khuyến nghị |
| --- | --- |
| ≤ 1.500 | **SVG** — mỗi ghế là `<rect>`, dùng CSS variable cho màu, a11y và hit-test miễn phí |
| 1.500 – 5.000 | SVG + virtualize theo section (chỉ render section đang xem) |
| > 5.000 | Canvas + hit-test bằng color-picking; ngoài scope MVP |

**Chốt MVP: SVG**, kiến trúc component tách `<SeatLayer>` để thay Canvas sau mà không đụng logic hold. Cập nhật màu khi có delta WS = đổi `data-status` attribute + CSS, **không re-render React toàn bộ 1.500 node**.

### 8.3 Ba app hay một app?

Docs chốt 3 app (`web-customer` :3000, `web-admin` :3001, `web-scanner` :3002). Cân nhắc ngược lại: 1 app Next.js với 3 nhóm route sẽ giảm chi phí hạ tầng và chia sẻ code dễ hơn.

**Giữ 3 app** vì: bundle scanner phải cực nhẹ (điện thoại staff, mạng nhà thi đấu), admin không cần SEO/SSR còn customer thì cần, và tách app giúp tách domain + tách client OIDC (ADR-0016 đã tạo 3 client). Chi phí chia sẻ code giải quyết bằng `packages/*` trong monorepo.

---

## 9. Rủi ro dự án (không phải rủi ro kỹ thuật)

| Rủi ro | Xác suất | Ảnh hưởng | Giảm thiểu |
| --- | --- | --- | --- |
| SePay sandbox chậm cấp / không có tài khoản test | Trung bình | **Chặn tuần 8–9** | Tuần 1 phải xin xong (đã có trong WEEKLY-BACKLOG). Dựng `PaymentProvider` interface + `FakeSePayProvider` để phát triển song song không phụ thuộc |
| Load test tuần 10 mới phát hiện p95 vượt 300ms | **Cao** | Trượt gate, không còn thời gian sửa kiến trúc | **Đẩy micro-benchmark hold lên tuần 7**, ngay khi Lua script chạy được. Đừng đợi tuần 10 |
| Seat map authoring (A-SEATMAP) tốn thời gian hơn dự kiến | Cao | Trượt milestone 02 | MVP chỉ grid + CSV như docs chốt; **không** WYSIWYG. Cắt sớm |
| 2 BE + 2 FE cho 13 tuần là mỏng | Cao | — | Đội 3 người thì bỏ RN hoàn toàn, giảm admin xuống mức tối thiểu |
| WS ở 10k connection tốn RAM/FD hơn dự kiến | Trung bình | Sập lúc mở bán | Đo sớm; có đường lui SSE (§6.2) |

---

## 10. Cái nên làm khác đi so với thứ tự trong docs

Docs xếp: Foundation → Catalog → Checkout → Ops. Hợp lý cho tổ chức, nhưng **rủi ro dồn hết vào tuần 7–10**.

**Đề xuất: bóc một "spike" 3 ngày trong tuần 3** (song song Foundation B): dựng đường trục mỏng nhất của luồng hold — Redis Lua + bảng `session_seats` giả lập + endpoint hold + k6 script — chỉ để **đo p95 và chứng minh không oversell**. Không UI, không auth, dữ liệu seed tay.

Giá trị: nếu kiến trúc hold sai, ta biết ở tuần 3 (còn 10 tuần để sửa) thay vì tuần 10 (còn 3 tuần). Chi phí: 3 ngày. Đây là đánh đổi tốt nhất trong toàn bộ kế hoạch.

Tương tự, **spike webhook 1 ngày ở tuần 3**: một endpoint nhận payload SePay mẫu, verify chữ ký, ghi `webhook_events`. Xác nhận sớm rằng cơ chế xác thực của SePay là thứ ta hiểu đúng.

---

## 11. Chốt lại — thứ không được thương lượng

Lặp lại từ `product-decisions.md`, vì mọi quyết định kỹ thuật ở trên đều phục vụ 6 dòng này:

1. Server là nguồn chân lý cho availability.
2. Không oversell: tối đa một reservation/sold hợp lệ cho mỗi `(event_session_id, seat_id)`.
3. Tenant scope lấy từ membership đã xác thực.
4. Webhook/payment/ticket mutation idempotent.
5. QR không chứa PII; `jti` unique.
6. AI không tham gia quyết định checkout.

Bất cứ tối ưu hiệu năng nào vi phạm 6 điều này đều bị loại, kể cả khi nó đạt p95.
