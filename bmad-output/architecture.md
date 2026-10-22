# System Architecture: NexaTicket AI Chatbox — Đặt vé có xác nhận, Săn vé, Nền dự báo

**Document Version:** 1.0
**Date:** 2026-10-22
**Author:** Winston (Architect) cho uchihathien
**Track:** BMad Method (brownfield — mở rộng service đang chạy)
**Status:** Approved (ADR chấp nhận 2026-10-22)
**Source PRD:** chưa có `prd.md`; FR/NFR trích từ `bmad-output/product-brief-ai-chatbox-2026-10-22.md` (mục 2, 4, 5, 6, 8) và ràng buộc từ `docs/**/adr`

> Tài liệu này là nguồn duy nhất cho các quyết định xuyên suốt của ai-chatbox. Mọi story
> sinh ra sau đây kế thừa các quyết định LOCKED ở mục 3. Số ADR dùng dãy **ADR-2xxx** để
> không đụng dãy 00xx (discovery) và 10xx (architecture-v2) đang có trong `docs/`.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture Pattern](#2-architecture-pattern)
3. [Architecture Decision Records](#3-architecture-decision-records)
4. [Component Design](#4-component-design)
5. [Data Model](#5-data-model)
6. [API Specifications](#6-api-specifications)
7. [FR / NFR Coverage Matrix](#7-fr--nfr-coverage-matrix)
8. [Technology Stack](#8-technology-stack)
9. [Trade-off Analysis](#9-trade-off-analysis)
10. [Deployment Architecture](#10-deployment-architecture)
11. [Future Considerations](#11-future-considerations)

---

## 1. System Overview

### Purpose

`ai-chatbox-service` là trợ lý hỗ trợ khách hàng chạy vòng ReAct trên LLM, có RAG (pgvector) và chuyển tiếp cho người thật. Kiến trúc này mở rộng nó theo ba hướng, theo đúng một nguyên tắc: **AI hiểu, mã tất định làm, người dùng xác nhận.**

1. **Booking Proposal** — mô hình không còn tạo đơn; nó chỉ lập *đề xuất*. Đơn chỉ được tạo khi khách bấm xác nhận qua một endpoint không đi qua mô hình.
2. **Booking Intent (săn vé)** — khách để lại "lệnh" (sự kiện, khu, số vé, giá trần); một worker không dùng LLM thực thi khi mở bán hoặc khi vé nhả ra từ đơn hết hạn.
3. **Nền dữ liệu dự báo** — snapshot doanh số theo ngày trong analytics-service, đủ để huấn luyện mô hình dự báo sức mua ở giai đoạn sau.

### Scope

**In Scope:**
- Phase 1: Booking Proposal + endpoint xác nhận; bộ nhớ ngữ cảnh đặt vé giữa các lượt; chặn đề xuất trùng; bộ eval với mô hình thật; snapshot `session_sales_daily`; ADR thay ADR-0012.
- Phase 2: Booking Intent + worker + uỷ quyền `on-behalf-of`; thông báo "đã giữ vé"; trả lời dạng stream; đẩy tin nhân viên qua realtime-gateway.
- Phase 3 (chỉ khung): dịch vụ dự báo đọc snapshot, API cho dashboard organizer.

**Out of Scope:**
- Tự động thanh toán, lưu phương thức thanh toán.
- Hàng đợi ảo lúc mở bán cho web (tiền đề cho công bằng — xem ADR-2005, chỉ nêu phụ thuộc).
- Mô hình dự báo cụ thể (thuật toán, đặc trưng) — thuộc Phase 3 PRD riêng.
- Đổi mô hình nhúng / schema vector.

### Architectural Drivers

1. **NFR-001: Không đơn nào được tạo khi khách chưa xác nhận** — buộc tách "đề xuất" khỏi "thực thi" và lấy tool tạo đơn ra khỏi tay mô hình (ADR-2001).
2. **NFR-002: Mọi chốt chặn thương mại giữ nguyên (hạn mức/người, giữ chỗ 5–10 phút, hạn thanh toán 15 phút, idempotency)** — không được sinh đường tắt; worker phải đi qua đúng handler của inventory/ordering (ADR-2003).
3. **NFR-003: Công bằng — lệnh tự động không thắng người mua tay** — quyết định cách phân bổ khi nhiều lệnh tranh một lô vé (ADR-2005).
4. **NFR-004: Độ trễ cảm nhận ≤ 3 giây tới token đầu tiên; lượt ≤ 60 giây với mô hình trả phí** — quyết định streaming và định tuyến mô hình (ADR-2007, ADR-2008).
5. **NFR-006: Dữ liệu chuỗi thời gian ghi được từ hôm nay, không ghi bù** — snapshot phải vào Phase 1 (ADR-2009).

### Stakeholders & Constraints

- **Users:** khách mua vé concert (di động), ban tổ chức, nhân viên bàn hỗ trợ.
- **Team:** 1–2 người, khoá luận *(giả định — chưa xác nhận)*. Hệ quả: ưu tiên dùng lại starter/pattern có sẵn, không thêm hạ tầng mới.
- **Existing constraints (phải tuân thủ):**
  - 13 service Spring Boot, database-per-service, PostgreSQL chung 100 kết nối, pool 10/service (ADR-1002).
  - RabbitMQ là xương sống sự kiện, giao ít nhất một lần, không bảo đảm thứ tự (ADR-1009); outbox transactional (ADR-0005).
  - Mọi mutation yêu cầu `Idempotency-Key` (ADR-0007).
  - Hold `HELD` 5 phút → order `AWAITING_PAYMENT` 15 phút → `EXPIRED` nhả chỗ, bắn `order.expired` (ADR-0015).
  - Trần mua vé ba tầng, cộng dồn theo tài khoản trên mỗi suất (ADR-1014).
  - Keycloak OIDC, JWT; nội bộ dùng `X-Internal-Token` cho `/internal/**` (ADR-0016, starter-security).
  - ADR-0012 cấm AI ảnh hưởng hold/order/payment — **được thay thế** bởi ADR-2001.

---

## 2. Architecture Pattern

**Pattern:** Microservices (đã có) + **Hexagonal / Ports & Adapters bên trong từng service** (đã có trong ai-chatbox: `domain/port`, `application`, `infrastructure`, `interfaces`). Phần mở rộng tuân theo cùng bố cục và thêm một **mẫu "Propose → Confirm → Execute"** cho mọi hành động giao dịch do AI khởi xướng.

**Justification:**
- Brownfield: ai-chatbox đã là hexagonal với `ArchitectureTest` kiểm ranh giới; thêm module mới theo cùng cấu trúc là rẻ nhất và được test kiến trúc bảo vệ.
- Worker săn vé là consumer AMQP tất định, đúng mẫu của `inventory.OrderClosedListener` / `notification.OrderEventsListener` — không cần hạ tầng mới.
- Không tách service mới cho booking-intent ở Phase 2: nó chia sẻ schema `chat_*`, port `InventoryClientPort`/`OrderingClientPort` và bảng `booking_*` với ai-chatbox; tách sớm là thêm một database, một pool 10 kết nối và một đường `/internal` nữa cho 1–2 người vận hành.

**Alternatives considered:**
- **Dùng tool-runner của SDK (agentic loop có sẵn) thay vòng ReAct tự viết:** từ chối — code hiện tại cố ý giữ vòng lặp trong use case để test được không cần API thật (`CustomerSupportAgentUseCase` javadoc).
- **Service `booking-intent-service` riêng:** hoãn — revisit khi worker cần scale độc lập hoặc có kênh đặt lệnh ngoài chat (web form).

**Application:** Mô hình chỉ gọi tool *đọc* và tool *nháp* (`proposeBooking`, `draftBookingIntent`). Mọi tool ghi vào tồn kho bị gỡ khỏi `SupportAgentTools.CATALOG`. Endpoint xác nhận và worker là mã tất định trong `application/booking` và `application/intent`.

---

## 3. Architecture Decision Records

| ADR | Title | Status | Drives |
|-----|-------|--------|--------|
| ADR-2001 | AI đề xuất, người dùng xác nhận, mã tất định thực thi (thay ADR-0012) | Accepted | FR-001, FR-002, NFR-001, NFR-002, NFR-008 |
| ADR-2002 | Trạng thái đặt vé có cấu trúc theo phiên (slot memory) | Accepted | FR-003, FR-004, NFR-004 |
| ADR-2003 | Worker săn vé đi qua handler của inventory/ordering bằng uỷ quyền `on-behalf-of` có ký | Accepted | FR-005, FR-006, NFR-002, NFR-005 |
| ADR-2004 | Trigger của lệnh săn vé là sự kiện AMQP đã có (`session.published`, `order.expired`, `order.cancelled`) | Accepted | FR-005, FR-007, NFR-006 |
| ADR-2005 | Phân bổ công bằng: FIFO theo thời điểm tạo lệnh, cùng hạn mức với mua tay | Accepted | NFR-003, FR-006 |
| ADR-2006 | Idempotency theo định danh nghiệp vụ (`proposalId`, `intentId:attempt`), không theo `callId` | Accepted | NFR-002, FR-004 |
| ADR-2007 | Trả lời dạng SSE; tin nhân viên qua realtime-gateway | Accepted | NFR-004, FR-008 |
| ADR-2008 | Định tuyến mô hình: local cho FAQ, mô hình trả phí cho lượt có ý định mua; eval chặn thay đổi | Accepted | NFR-004, NFR-007 |
| ADR-2009 | Snapshot doanh số theo ngày trong analytics-service, ghi từ Phase 1 | Accepted | FR-009, NFR-006 |
| ADR-2010 | Quy ước API/lỗi/đặt tên cho phần mở rộng (REST, `ApiException` + `ErrorCode`, snake_case DB, camelCase JSON) | Accepted | tất cả |
| ADR-2011 | Dữ liệu cá nhân trong prompt và hội thoại: che trước khi vào prompt, hạn lưu 180 ngày | Accepted | NFR-008, NFR-009 |

### ADR-2001: AI đề xuất, người dùng xác nhận, mã tất định thực thi

**Status:** Accepted 2026-10-22 (thay thế ADR-0012)   **Drives:** FR-001, FR-002, NFR-001, NFR-002, NFR-008

**Context:** `initiateTicketBooking` hiện tạo hold + order ngay trong vòng ReAct; điều kiện "khách đã xác nhận" chỉ nằm trong mô tả tool. Mô hình 7B gọi sai là tạo đơn thật. Mô tả sự kiện do organizer viết đi vào prompt nên có đường prompt-injection tới hành động có tiền. ADR-0012 cấm hoàn toàn AI chạm vào hold/order — vừa bị code vi phạm, vừa quá chặt cho mục tiêu sản phẩm.

**Decision:**
1. Gỡ `initiateTicketBooking` khỏi `SupportAgentTools.CATALOG`. Mô hình chỉ còn hai tool liên quan giao dịch, cả hai **không ghi vào tồn kho**:
   - `proposeBooking(slug, sessionId, zoneCode, quantity)` → kiểm hợp lệ (như `TicketBookingUseCase.initiate` các bước trước `holdZone`), tính `totalVnd` từ `EventDetail.tiers`, lưu `booking_proposals` (TTL 10 phút), trả `proposalId` + tóm tắt.
   - `draftBookingIntent(slug, zoneCodes[], quantity, maxUnitPriceVnd, validUntil, trigger)` → lưu `booking_intents.status = DRAFT`, trả `intentId`.
2. `AskResponse` thêm trường có kiểu `action: ActionCard | null`, ba loại: `BOOKING_PROPOSAL {id, summary, eventTitle, sessionStartsAt, zoneCode, quantity, totalVnd, expiresAt}`; `PAYMENT {orderId, orderNumber, totalVnd, vietQrPayload, paymentReference, checkoutUrl, paymentExpiresAt}`; `BOOKING_INTENT {id, summary, validUntil}` (Phase 2). Giao diện vẽ thẻ từ trường này, **không** parse từ `answer`. `GET /sessions/{id}/messages` cũng trả `action` đang mở (đề xuất còn hạn, hoặc đơn vừa tạo còn trong hạn thanh toán) để tải lại trang vẫn thấy thẻ.
3. Xác nhận đề xuất bằng **nút "Đồng ý" trên thẻ** — đường duy nhất, không qua mô hình *(sửa 2026-10-22, lần 2)*. Nút gọi `POST /v1/chat/agent/proposals/{id}/confirm`; nút "Huỷ" gọi `/cancel`. Chủ sở hữu = `userId` trong JWT phải trùng `booking_proposals.user_id`; đề xuất chỉ xác nhận được một lần và trong 10 phút; bấm là chấp nhận đúng suất/khu/số vé/tổng tiền thẻ đang hiển thị (đã lưu lúc đề xuất). Câu trả lời của trợ lý sau `proposeBooking` kết bằng "bấm *Đồng ý* trên thẻ để mình giữ chỗ" (`nextStep` của kết quả tool). Câu chữ của khách ("ok", "đặt đi") **không** xác nhận đơn — chúng đi vào LLM như mọi câu khác; mô hình chỉ có thể nhắc khách bấm nút.
4. Confirm chạy `TicketBookingUseCase.initiate` với idempotency key = `proposalId` (ADR-2006) bằng token của chính khách; trả thẻ `PAYMENT` kèm **VietQR** để khách quét ngay trong chat; ghi lượt `ASSISTANT` "đã tạo đơn X, thanh toán trước HH:mm" vào `chat_messages` để lượt sau mô hình biết. `vietQrPayload`/`paymentReference` đi **chỉ** qua thẻ — không bao giờ vào JSON cho mô hình (một mô hình chép sai một ký tự là tiền đi lạc).
5. Thanh toán **không bao giờ** tự động; thanh toán là bước xác nhận cuối: đơn chưa trả hết hạn sau 15 phút (ADR-0015) và chỗ tự nhả. `checkoutUrl` được trả nguyên làm đường dự phòng. Giao diện poll `GET /v1/orders/{id}` 5 giây một lần khi thẻ `PAYMENT` đang mở, đổi thẻ sang "đã thanh toán"/"hết hạn" — cùng cách trang checkout đang làm.
6. **Không** gộp đề xuất và xác nhận vào một lượt dù câu đầu đã đủ thông tin: cú bấm "Đồng ý" là thứ phân biệt "đặt 2 vé khu A" với "khu A còn 2 vé không?", và nhầm là khoá chỗ của người khác 15 phút lúc mở bán. Gộp chỉ được phép sau ADR mới, kèm cờ theo sự kiện.

**Consequences — LOCKED for all stories:**
- *(Thêm 2026-10-22, từ thử đầu-cuối)* Mô hình cũng không được **nói** là đã có đơn: `BookingClaimGuard` chặn mọi lời khẳng định đã đặt/giữ chỗ và mọi số đơn/UUID không có nguồn trong payload tool của lượt hoặc lượt ASSISTANT đã lưu, chạy trước khi lưu lịch sử (story 1.9).
- Không có tool nào trong `SupportAgentTools.CATALOG` được gọi `InventoryClientPort.holdZone` hay `OrderingClientPort.placeOrder`. `ArchitectureTest` thêm rule: package `application.agent` không phụ thuộc `InventoryClientPort`/`OrderingClientPort.placeOrder`.
- Mọi hành động giao dịch mới trong tương lai (đổi vé, mua thêm) đi cùng mẫu: tool nháp → `ActionCard` → endpoint confirm.
- Easier: prompt injection không thể tạo đơn; eval đo được "tỷ lệ đề xuất đúng" tách khỏi "tỷ lệ đặt thành công".
- Accepted cost: thêm một chạm cho khách. Mitigation: thẻ hiện ngay trong luồng chat, một nút.

**Alternatives:**
- Giữ tool đặt vé, thêm tham số `customerConfirmed: true` do mô hình điền — từ chối: mô hình điền được thì injection cũng điền được.
- Hỏi lại bằng một lượt LLM "bạn chắc chứ?" — từ chối: vẫn là mô hình quyết, và tốn một vòng.

**Revisit when:** có cơ chế xác thực hành động ngoài chat (ví dụ xác nhận bằng sinh trắc trên app) cho phép gộp bước.

---

### ADR-2002: Trạng thái đặt vé có cấu trúc theo phiên (slot memory)

**Status:** Accepted 2026-10-22   **Drives:** FR-003, FR-004, NFR-004

**Context:** `history.appendTurn` chỉ lưu câu hỏi và câu trả lời. Khách hỏi giá ở lượt 1, lượt 2 nói "lấy 2 vé" thì mô hình phải tra lại `findEvents` + `getEventDetails` (2 vòng) rồi mới `proposeBooking` — chạm trần `maxToolIterations = 4`, và có thể chép sai UUID từ trí nhớ.

**Decision:**
- Bảng `chat_session_context(session_id PK, focus_event JSONB, updated_at)` trong DB của ai-chatbox. `focus_event` = `{slug, title, sessionId, startsAt, zones: [{zoneCode, name, priceVnd}], pendingOrder: {orderId, orderNumber, paymentExpiresAt} | null, fetchedAt}`.
- Ghi khi `getEventDetails` hoặc `proposeBooking` chạy thành công (trong `ToolDispatcher.DispatchResult` thêm `contextUpdate`). Đọc ở đầu mỗi lượt; nếu `fetchedAt` < 15 phút, đưa vào **message của người dùng** (không vào system prompt — giữ cache prompt) dưới khối `<<NGỮ CẢNH ĐÃ XÁC MINH>>` với đúng các UUID.
- Trước `proposeBooking`, use case kiểm `pendingOrder` cùng `sessionId`+`zoneCode` → trả `proposed: false, reason: "ĐÃ CÓ ĐƠN CHỜ THANH TOÁN"` kèm `checkoutUrl`.

**Consequences — LOCKED:** UUID của suất/khu đi vào prompt **chỉ** từ `chat_session_context` hoặc kết quả tool cùng lượt; không bao giờ từ lịch sử văn bản.
- Easier: "ok lấy 2 vé" = 1 vòng; chặn đơn trùng không cần gọi ordering.
- Accepted cost: thêm một SELECT/UPSERT mỗi lượt; ngữ cảnh có thể cũ (giá đổi). Mitigation: TTL 15 phút + `proposeBooking` luôn tra lại `findEventBySlug` trước khi tính tiền.

**Alternatives:** lưu toàn bộ `ToolOutcome` vào `chat_messages` — từ chối: prompt phình theo số lượt, và khối JSON cũ bị mô hình đọc như dữ liệu hiện tại.

**Revisit when:** `AgentMetrics.recordRounds` cho thấy p50 vòng của lượt có `proposeBooking` > 2.

---

### ADR-2003: Worker săn vé đi qua handler của inventory/ordering bằng uỷ quyền `on-behalf-of` có ký

**Status:** Accepted 2026-10-22   **Drives:** FR-005, FR-006, NFR-002, NFR-005

**Context:** Khi lệnh được thực thi, khách không online; access token của khách đã hết hạn. `/internal/**` hiện chỉ có `X-Internal-Token` (bí mật chung) và các endpoint nội bộ **không kiểm chủ sở hữu** — chính `OrderingClientPort` javadoc đã cảnh báo đường này nguy hiểm. Nhưng hạn mức/người (ADR-1014) và quyền sở hữu phải được inventory/ordering tính như với mọi đơn khác.

**Decision:**
1. inventory và ordering thêm endpoint nội bộ **hẹp**: `POST /internal/holds/on-behalf-of` và `POST /internal/orders/on-behalf-of`. Chúng gọi **đúng** `PlaceHoldHandler` / `PlaceOrderHandler` với `userId` lấy từ body đã ký, nên hạn mức, trần, chọn chỗ, TTL giữ nguyên.
2. Body mang `grant`: JWT do ai-chatbox ký bằng khoá riêng (RS256; public key đăng ký trong cấu hình của inventory/ordering), claims `{sub: userId, intent_id, event_session_id, zone_codes, max_quantity, exp = intents.valid_until, jti}`. Endpoint kiểm chữ ký, kiểm `X-Internal-Token`, kiểm tham số request ⊆ claims (không giữ nhiều hơn `max_quantity`, không khu ngoài `zone_codes`).
3. Grant được tạo **lúc khách bấm xác nhận lệnh** (ADR-2001) và lưu mã hoá trong `booking_intents.grant_token`; thu hồi = đổi `status` thành `CANCELLED` (worker không đọc nữa) và endpoint từ chối `jti` nằm trong `revoked_grants` (ordering giữ bảng nhỏ này, TTL = `exp`).
4. Audit: mỗi hold/order on-behalf-of ghi `placed_by = 'BOOKING_INTENT_WORKER'`, `intent_id` vào bảng tương ứng; `order.created` payload thêm `channel: "CHAT_INTENT"`.

**Consequences — LOCKED:** Không service nào được gọi `/internal/orders/{id}` hay `/internal/reservations` để đặt hộ khách. Đặt hộ chỉ qua hai endpoint on-behalf-of, và chỉ với grant có `sub` = khách.
- Easier: mọi chốt chặn thương mại nằm nguyên ở handler; audit rõ ràng; thu hồi một lệnh không ảnh hưởng lệnh khác.
- Accepted cost: thêm một cặp khoá cần quản lý, hai endpoint mới ở hai service khác. Mitigation: khoá sinh lúc deploy như `KEYCLOAK_*` hiện có; endpoint chỉ bật khi `BOOKING_INTENT_ENABLED=true`.

**Alternatives:**
| Alternative | Pros | Cons | Why rejected |
|---|---|---|---|
| Lưu refresh token của khách | không sửa inventory/ordering | lưu bí mật dài hạn của khách; Keycloak offline token có phạm vi toàn tài khoản | quá rộng, vi phạm nguyên tắc tối thiểu |
| Service-account + `on_behalf_of` không ký | đơn giản | bất kỳ ai có `X-Internal-Token` đặt được đơn cho bất kỳ ai | không có bằng chứng khách đã đồng ý |
| Keycloak token exchange (RFC 8693) | chuẩn | cần cấu hình Keycloak phức tạp, token vẫn ngắn hạn | không giải quyết "khách offline" |

**Revisit when:** Keycloak bật được token exchange với `offline_access` có phạm vi hẹp theo sự kiện.

---

### ADR-2004: Trigger của lệnh săn vé là sự kiện AMQP đã có

**Status:** Accepted 2026-10-22   **Drives:** FR-005, FR-007, NFR-006

**Context:** Có sẵn `session.published` (catalog → inventory dựng tồn kho), `order.expired` và `order.cancelled` (ordering → inventory nhả chỗ, notification gửi thư). Không có sự kiện "khu X vừa có vé trống".

**Decision:**
- ai-chatbox khai hai queue riêng, bind vào exchange hiện có: `aichatbox.catalog.session-published` (key `session.published`) và `aichatbox.ordering.released` (keys `order.expired`, `order.cancelled`). Dedupe bằng `processed_events` của starter-idempotency (cùng mẫu analytics-service).
- Consumer **không** thực thi ngay; nó chỉ đánh dấu `(event_session_id)` vào bảng `intent_wakeups(event_session_id PK, requested_at)`. Một `IntentExecutionJob` (`@Scheduled`, 5 giây, khoá `SELECT … FOR UPDATE SKIP LOCKED`) đọc wakeups, nạp lệnh `ACTIVE` của suất đó theo FIFO (ADR-2005) và thực thi từng lệnh.
- Lý do tách: `order.expired` tới sau khi ordering đóng đơn nhưng **trước** khi inventory nhả chỗ (hai consumer độc lập, ADR-1009 không bảo đảm thứ tự). Job thử lại với backoff (5s, 15s, 60s; tối đa 5 lần) cho tới khi `holdZone` thành công hoặc hết lệnh.
- `session.published` khi suất **đổi giờ mở bán**: catalog đã bắn lại cùng key → wakeup bình thường; job tự kiểm `salesOpenAt <= now` qua `CatalogClientPort` trước khi giữ chỗ.

**Consequences — LOCKED:** Worker chỉ phản ứng với wakeup; không có polling định kỳ toàn bảng lệnh.
- Easier: không cần sửa inventory để phát sự kiện mới; thứ tự message không quan trọng.
- Accepted cost: trễ 5–30 giây giữa vé nhả và lệnh được thực thi; có thể thử giữ chỗ mà chưa có chỗ. Mitigation: backoff; `BookingRejectedException` "hết chỗ" không tính là lỗi.

**Alternatives:** inventory phát `zone.availability_changed` cho mọi suất — từ chối ở Phase 2: realtime-gateway đã có coalescer cho mục đích khác; thêm sự kiện mới là sửa ba service.

**Revisit when:** số wakeup/phút > 100 hoặc trễ p95 > 60 giây.

---

### ADR-2005: Phân bổ công bằng — FIFO theo thời điểm tạo lệnh, cùng hạn mức với mua tay

**Status:** Accepted 2026-10-22   **Drives:** NFR-003, FR-006

**Context:** Lệnh tự động nhanh hơn người. Nếu nó được ưu tiên, tính năng trở thành công cụ phe vé và organizer sẽ tắt nó.

**Decision:**
- Tối đa **1 lệnh ACTIVE / người / suất**; `quantity` ≤ `min(6, max_units_per_hold)`; hạn mức cộng dồn theo tài khoản do inventory/ordering tính (ADR-1014) — worker không có ngoại lệ.
- Thứ tự thực thi trong một wakeup: `ORDER BY created_at ASC` (FIFO). Mỗi lệnh chỉ nhận được số vé còn lại; không chia nhỏ (`quantity` là tất-cả-hoặc-không).
- Organizer bật/tắt săn vé theo sự kiện: `catalog.events.chat_intents_enabled` (mặc định **false** cho sự kiện có `max_tickets_per_customer` ≤ 2, true cho còn lại) — `draftBookingIntent` tra cờ này và từ chối nếu tắt.
- Worker **không chạy trong 60 giây đầu sau `session.published`** khi suất có `salesOpenAt` công khai — cửa sổ cho người mua tay và cho hàng đợi ảo (nếu có sau này). Hằng số cấu hình `INTENT_ON_SALE_GRACE=60s`.
- Lệnh có `max_unit_price_vnd` < giá khu thì bỏ qua khu đó, thử khu kế tiếp trong `zone_codes[]`.

**Consequences — LOCKED:** Không story nào thêm ưu tiên theo hạng thành viên, phí, hay "bốc thăm có trọng số" mà không có ADR mới.
- Easier: giải thích được cho organizer bằng một câu.
- Accepted cost: người để lệnh sớm luôn thắng người để lệnh muộn. Mitigation: hiển thị "vị trí trong hàng" cho khách (`GET /intents/{id}` trả `queuePosition`).

**Alternatives:** bốc thăm ngẫu nhiên — hoãn: công bằng hơn với concert hot nhưng khó giải thích; revisit khi organizer yêu cầu.

**Revisit when:** có hàng đợi ảo lúc mở bán cho web; khi đó lệnh xếp chung hàng đó.

---

### ADR-2006: Idempotency theo định danh nghiệp vụ

**Status:** Accepted 2026-10-22   **Drives:** NFR-002, FR-004

**Context:** Hiện khoá là `chat-hold-{callId}` / `chat-order-{callId}`; `callId` mới mỗi lượt nên khách nhắn "đặt lại" sinh đơn thứ hai. ADR-0007 yêu cầu retry không tạo trùng.

**Decision:**
- Confirm proposal: `Idempotency-Key = "proposal-" + proposalId` cho cả hold và order. Bấm xác nhận hai lần = cùng đơn.
- Worker: `"intent-" + intentId + "-" + attempt` với `attempt` tăng chỉ khi lần trước kết thúc bằng `BookingRejectedException` (hết chỗ); lỗi mạng giữ nguyên `attempt`.
- `POST /support` giữ `Idempotency-Key` của client như hiện tại (ChatIdempotency).

**Consequences — LOCKED:** Không sinh idempotency key từ `callId` hay `UUID.randomUUID()` ở bất kỳ đường ghi tồn kho nào.

**Alternatives:** hash(userId, sessionId, zoneCode, quantity, ngày) — từ chối: khách thật sự muốn hai đơn cùng khu hai ngày khác nhau vẫn bị gộp.

**Revisit when:** inventory đổi TTL lưu khoá (hiện ≥ 24 giờ theo ADR-0007).

---

### ADR-2007: Trả lời dạng SSE; tin nhân viên qua realtime-gateway

**Status:** Accepted 2026-10-22   **Drives:** NFR-004, FR-008

**Context:** `POST /support` chờ trọn lượt (tới 540 giây); khách thấy màn hình đứng. Tin nhân viên tới khách bằng polling vài giây dù đã có `realtime-gateway` (WebSocket, AMQP → session registry).

**Decision:**
- Thêm `POST /v1/chat/agent/support/stream` trả `text/event-stream`. Sự kiện: `status` (`{stage: "RETRIEVING"|"THINKING"|"TOOL", tool?}`), `delta` (`{text}`), `action` (`ActionCard`), `done` (`AskResponse` đầy đủ), `error` (`{code, message}`). Endpoint cũ giữ nguyên cho client chưa hỗ trợ.
- `LlmProviderPort` thêm `stream(LlmRequest, Consumer<Delta>)`; `OllamaLlmAdapter` và `AnthropicLlmAdapter` đều có streaming gốc. Vòng ReAct không đổi: chỉ vòng **cuối** (Answer) được stream; các vòng tool vẫn chờ.
- `AnswerGrounding` và `withTicketLinks` chạy trên văn bản **đã gom** trước khi gửi `done`; nếu chốt chặn thay câu trả lời, gửi `replace` `{text}` — client thay toàn bộ bong bóng. (Chấp nhận: khách có thể thấy câu bị thay trong < 1 giây.)
- Tin nhân viên: `HandoffUseCase.reply` bắn `support.handoff.replied {sessionId, userId, messageId}` qua outbox; realtime-gateway thêm topic `chat:{sessionId}` bên cạnh seat map, đẩy xuống WebSocket của đúng `userId`. Polling giữ làm fallback khi WebSocket rớt.

**Consequences — LOCKED:** Chuỗi stage và tên sự kiện SSE ở trên là hợp đồng với mọi client (web, mobile).
- Easier: trải nghiệm chờ rõ ràng; hạn 540 giây không còn là thứ khách cảm nhận.
- Accepted cost: luồng của `agentExecutor` vẫn bị giữ suốt lượt. Mitigation: không đổi `maxConcurrentTurns`; chuyển sang mô hình trả phí (ADR-2008) mới là cách giảm thời gian giữ luồng.

**Alternatives:** WebSocket cho cả chat — từ chối: SSE đủ cho một chiều, dùng lại `ApiClient` hiện có; realtime-gateway chỉ nhận AMQP, không nhận request chat.

**Revisit when:** mobile cần nhận trả lời khi app ở nền (lúc đó cần push, không phải SSE).

---

### ADR-2008: Định tuyến mô hình và eval chặn thay đổi

**Status:** Accepted 2026-10-22   **Drives:** NFR-004, NFR-007

**Context:** Mặc định `AI_PROVIDER=local` + qwen2.5 7B CPU: ~1,5 token/giây, gọi tool kém. Một lượt có ý định mua cần tool-call chính xác; một câu "vé có hoàn được không" thì không.

**Decision:**
- `LlmProviderPort` có hai bean được đặt tên: `faqLlm` và `commerceLlm`; cấu hình `AI_PROVIDER_FAQ` / `AI_PROVIDER_COMMERCE` (mặc định cả hai = `AI_PROVIDER` để hành vi không đổi). Môi trường production khuyến nghị `COMMERCE=anthropic` (Claude Sonnet 5.5 hoặc Haiku 5.5, `effort=low`), `FAQ=local` nếu có GPU, ngược lại cả hai `anthropic`.
- Bộ phân loại **không dùng LLM** `CommerceIntent.isLikely(userQuery, sessionContext)`: từ khoá (đặt/mua/lấy/giữ/săn/vé + số lượng) hoặc `chat_session_context.focus_event != null` → `commerceLlm`. Cùng mẫu với `HandoffIntent`.
- Bộ eval `src/test/resources/eval/*.yaml` (≥ 40 ca: tra sự kiện, đề xuất đúng tham số, không đề xuất khi chỉ hỏi giá, từ chối injection trong mô tả sự kiện, grounding) chạy bằng `mvn -Peval` với mô hình thật; CI chạy khi thay đổi `SupportAgentPrompts`, `SupportAgentTools`, `AgentProperties` hoặc `application.yml`. Ngưỡng: tool-call accuracy ≥ 90%, "đề xuất khi chưa đủ xác nhận" = 0.
- `AgentProperties.model` mặc định đổi về giá trị hợp lệ hiện hành của nhà cung cấp (khai ở `application.yml`, không hard-code trong record).

**Consequences — LOCKED:** Mọi PR đụng prompt/tool phải kèm kết quả eval trong mô tả.
- Accepted cost: chi phí API cho lượt commerce. Mitigation: chỉ lượt có ý định mua; prompt hệ thống cache được.

**Alternatives:** một mô hình lớn cho tất cả — chấp nhận được khi không có GPU; ghi là cấu hình, không phải kiến trúc khác.

**Revisit when:** mô hình local ≤ 14B đạt ≥ 90% trên bộ eval.

---

### ADR-2009: Snapshot doanh số theo ngày trong analytics-service

**Status:** Accepted 2026-10-22   **Drives:** FR-009, NFR-006

**Context:** `analytics.session_sales` là tổng cộng dồn; không có đường cong bán theo thời gian. Dự báo cần `tickets_sold` theo ngày kể từ mở bán, theo khu, kèm sức chứa và dấu hiệu sold-out. Dữ liệu này không ghi bù được.

**Decision:**
- Bảng `session_sales_daily` (mục 5) trong DB analytics; job `@Scheduled(cron = "0 5 0 * * *", zone = "Asia/Ho_Chi_Minh")` chụp từ `session_sales` + sức chứa/giá tra từ catalog qua `/internal/sessions/{id}` — endpoint này được **mở rộng** (story 4.3) thêm `salesOpenAt`, `salesCloseAt`, `tiers[{zoneCode, priceVnd, capacity}]`. **Không** gọi `/internal/sessions/{id}/pricing`: nó trả hoa hồng nền tảng, thứ ADR-1010 cấm analytics lưu hay đọc. Sức chứa "bán được" ưu tiên lấy từ `session.published` (story 4.2), fallback Σ tier catalog. Mỗi suất một dòng/ngày/khu từ ngày mở bán tới ngày diễn.
- `OrderEventsListener` của analytics thêm đếm `holds_created` (từ `order.created`) và `orders_expired`; `sold_out_at` ghi khi `tickets_sold == capacity` lần đầu.
- Phase 3: `forecast-service` (Python, đọc replica/CSV export) **ngoài** đường hold/order/payment — giữ đúng tinh thần ADR-0012 cho phần dự báo. Chỉ khai interface: `GET /v1/organizations/{orgId}/forecast/sessions/{sessionId}` trả `{expectedTotal, p10, p90, asOf, modelVersion}`.

**Consequences — LOCKED:** Mọi đặc trưng dự báo lấy từ `session_sales_daily` + taxonomy `docs/05-ai-scale/event-taxonomy.md`; không đọc trực tiếp DB của ordering/inventory.
- Accepted cost: độ phân giải ngày (không giờ). Mitigation: đủ cho dự báo trước 14 ngày; thêm snapshot giờ cho 48 giờ đầu mở bán nếu cần (revisit).

**Alternatives:** lưu sự kiện thô rồi tính sau — không mâu thuẫn, taxonomy đã có; snapshot là bảng dẫn xuất rẻ cho Phase 3 bắt đầu được ngay.

**Revisit when:** có ≥ 30 suất có đủ đường cong từ mở bán tới ngày diễn.

---

### ADR-2010: Quy ước API, lỗi, đặt tên (naming conventions) cho phần mở rộng

**Status:** Accepted 2026-10-22   **Drives:** tất cả

**Decision (kế thừa hệ thống hiện có, ghi lại để LOCKED):**
- REST JSON, đường `/v1/chat/agent/**` (khách), `/v1/support/**` (người trực), `/internal/**` (service, cần `X-Internal-Token`). Không GraphQL.
- Lỗi: `ApiException(ErrorCode, message)` → envelope của starter-web; mã mới thêm vào `AiChatboxErrorCode`: `PROPOSAL_NOT_FOUND` (404), `PROPOSAL_EXPIRED` (410), `PROPOSAL_ALREADY_CONFIRMED` (409), `INTENT_LIMIT_REACHED` (409), `INTENT_DISABLED_FOR_EVENT` (422), `GRANT_INVALID` (403, ở inventory/ordering).
- DB: snake_case, UUID PK, `*_vnd BIGINT`, `timestamptz`; migration `V01xx__` trong `db/migration/aichatbox`; không CHECK trên tổng cộng dồn (bài học analytics).
- JSON: camelCase; tiền `totalVnd`; thời gian ISO-8601 UTC trong API, định dạng giờ Việt Nam chỉ trong payload cho mô hình (`VN_TIME`).
- Tool name camelCase động từ trước (`proposeBooking`, `draftBookingIntent`); kết quả tool luôn có cờ nghiệp vụ (`proposed`, `drafted`) + `reason` + `hint` khi false; lỗi hệ thống dùng `ToolOutcome.error`.
- Trạng thái dạng enum chữ hoa: `booking_proposals.status ∈ {PROPOSED, CONFIRMED, EXPIRED, CANCELLED}`, `booking_intents.status ∈ {DRAFT, ACTIVE, FULFILLED, EXPIRED, CANCELLED, FAILED}`.
- Log tiếng Việt, có `correlationId` (pattern hiện có); metric Micrometer tiền tố `aichatbox_` (`AgentMetrics`).

---

### ADR-2011: Dữ liệu cá nhân trong prompt và hội thoại

**Status:** Accepted 2026-10-22   **Drives:** NFR-008, NFR-009

**Decision:**
- Giữ che email/SĐT (`ToolDispatcher.maskEmail/maskPhone`). Không đưa tên người nhận vé hay địa chỉ vào prompt.
- `chat_messages`, `chat_session_context`, `booking_proposals` xoá sau **180 ngày** (job trong `AbandonedHandoffReaper` mở rộng); `booking_intents` giữ 30 ngày sau `valid_until`; `grant_token` xoá ngay khi lệnh rời `ACTIVE`.
- Mô tả sự kiện do organizer viết được bọc trong kết quả tool với nhãn `"untrusted": true` và cắt ≤ 1.500 ký tự; prompt hệ thống ghi rõ nội dung đó là dữ liệu. Chốt chặn thật là ADR-2001.
- Dữ liệu analytics không PII (taxonomy hiện có).

---

## 4. Component Design

### Component Overview

```
web-customer / mobile-customer
   │  POST /support | /support/stream  ·  POST /proposals/{id}/confirm  ·  POST /intents/{id}/confirm
   ▼
ai-chatbox-service ───────────────────────────────────────────────────────────────┐
  interfaces/rest   SupportChatController · BookingActionController · IntentController
  application/agent CustomerSupportAgentUseCase (ReAct) · ToolDispatcher · CommerceIntent
  application/booking  BookingProposalUseCase · TicketBookingUseCase
  application/intent   BookingIntentUseCase · IntentExecutionJob · GrantIssuer
  infrastructure/amqp  IntentWakeupListener (session.published, order.expired, order.cancelled)
  infrastructure/http  Catalog · Inventory(+onBehalfOf) · Ordering(+onBehalfOf) · Identity
  DB: chat_* · booking_proposals · booking_intents · intent_wakeups · processed_events
└──────────────┬──────────────────────┬─────────────────────┬──────────────────────┘
               │ token khách          │ grant (RS256)       │ outbox: support.handoff.replied
   inventory-service          ordering-service        realtime-gateway → WS chat:{sessionId}
   /v1/sessions/{id}/holds    /v1/orders              notification-service ← intent.fulfilled
   /internal/holds/on-behalf-of  /internal/orders/on-behalf-of
                                                     analytics-service: session_sales_daily
```

### Component: CustomerSupportAgentUseCase (sửa)
**Responsibility:** Vòng ReAct; chọn `faqLlm`/`commerceLlm`; ghép slot memory; stream vòng cuối.
**Interfaces Provided:** `executeAgentProcess(sessionId, userId, query)`, `stream(…, SseEmitter)`.
**Interfaces Required:** `LlmProviderPort` ×2, `ChatHistoryPort`, `SessionContextPort` (mới), `ToolDispatcher`, `HandoffUseCase`.
**Data Owned:** `chat_sessions`, `chat_messages`.
**ADRs:** 2001, 2002, 2007, 2008. **NFRs:** NFR-001 (không có tool ghi), NFR-004 (stream).

### Component: BookingProposalUseCase (mới)
**Responsibility:** Lập và xác nhận đề xuất đặt vé.
**Interfaces Provided:** `propose(userId, sessionId, slug, eventSessionId, zoneCode, qty) → Proposal | Rejected`; `confirm(userId, proposalId) → Started | Rejected`.
**Interfaces Required:** `CatalogClientPort`, `TicketBookingUseCase`, `SessionContextPort`, `BookingProposalRepository`.
**Data Owned:** `booking_proposals`.
**ADRs:** 2001, 2002, 2006. **NFRs:** NFR-001, NFR-002.

### Component: BookingIntentUseCase + IntentExecutionJob + GrantIssuer (mới, Phase 2)
**Responsibility:** Nháp/xác nhận/huỷ lệnh; phát grant; thực thi lệnh theo wakeup, FIFO, backoff.
**Interfaces Provided:** `draft`, `confirm`, `cancel`, `get(queuePosition)`; job nội bộ.
**Interfaces Required:** `InventoryClientPort.holdOnBehalfOf`, `OrderingClientPort.placeOrderOnBehalfOf`, `CatalogClientPort`, outbox (`intent.fulfilled`, `intent.failed`).
**Data Owned:** `booking_intents`, `intent_wakeups`, `intent_attempts`.
**ADRs:** 2003, 2004, 2005, 2006. **NFRs:** NFR-002, NFR-003, NFR-005.

### Component: inventory-service / ordering-service (sửa nhỏ)
**Responsibility:** Thêm endpoint on-behalf-of gọi đúng handler hiện có; bảng `revoked_grants` (ordering).
**ADRs:** 2003. **NFRs:** NFR-002, NFR-005.

### Component: notification-service (sửa nhỏ)
**Responsibility:** Consumer `intent.fulfilled` → thư "Đã giữ N vé … thanh toán trước HH:mm" kèm `checkoutUrl`; `intent.failed` → thư "không giữ được". Hiện chỉ có email; push mobile là phụ thuộc ngoài.
**ADRs:** 2004.

### Component: realtime-gateway (sửa nhỏ)
**Responsibility:** Topic `chat:{sessionId}` từ `support.handoff.replied`. **ADRs:** 2007.

### Component: analytics-service (sửa)
**Responsibility:** `SessionSalesSnapshotJob` + bảng `session_sales_daily`; đếm `holds_created`, `orders_expired`, `sold_out_at`. **ADRs:** 2009.

### Component: Eval harness (test-only)
**Responsibility:** Chạy bộ ca YAML với mô hình thật; báo tool-call accuracy, proposal precision, injection resistance. **ADRs:** 2008.

---

## 5. Data Model

### Entity: booking_proposals (ai-chatbox)
- `id` (uuid, PK) · `user_id` (uuid) · `chat_session_id` (uuid, FK chat_sessions) · `event_slug` (text) · `event_session_id` (uuid) · `zone_code` (text) · `quantity` (int) · `unit_price_vnd` (bigint) · `total_vnd` (bigint) · `status` (text) · `order_id` (uuid, null) · `expires_at` (timestamptz, +10 phút) · `created_at` / `confirmed_at`.
- Index: `(user_id, status, expires_at)`; partial unique `(user_id, event_session_id, zone_code) WHERE status = 'PROPOSED'` — một đề xuất mở cho một khu.

### Entity: chat_session_context (ai-chatbox)
- `session_id` (uuid, PK, FK) · `focus_event` (jsonb) · `updated_at`. Không index thêm.

### Entity: booking_intents (ai-chatbox)
- `id` (uuid, PK) · `user_id` · `chat_session_id` · `event_id` · `event_session_id` (uuid, null = suất nào mở bán trước) · `zone_codes` (text[]) · `quantity` (int, 1–6) · `max_unit_price_vnd` (bigint, null) · `trigger` (text: `ON_SALE` | `ON_RELEASE` | `ANY`) · `status` (text) · `grant_token` (text, mã hoá, null khi không ACTIVE) · `grant_jti` (uuid) · `valid_until` (timestamptz) · `fulfilled_order_id` (uuid, null) · `created_at` / `updated_at`.
- Index: `(event_session_id, status, created_at)` cho FIFO; partial unique `(user_id, event_session_id) WHERE status = 'ACTIVE'`.

### Entity: intent_wakeups (ai-chatbox)
- `event_session_id` (uuid, PK) · `requested_at` · `attempts` (int) · `next_attempt_at`. Job xoá dòng khi không còn lệnh ACTIVE.

### Entity: intent_attempts (ai-chatbox, audit)
- `id` (bigserial) · `intent_id` · `attempt` (int) · `outcome` (text: HELD/ORDERED/NO_STOCK/REJECTED/ERROR) · `detail` (text) · `at`.

### Entity: revoked_grants (ordering) · cột `placed_by`, `intent_id` trên `orders` (ordering) và `seat_holds` (inventory)

### Entity: session_sales_daily (analytics)
- `event_session_id` · `zone_code` · `snapshot_date` (date) — PK ghép · `event_id` · `organization_id` · `days_since_on_sale` (int) · `days_to_event` (int) · `capacity` (int) · `tickets_sold_cum` (int) · `holds_created` (int) · `orders_expired` (int) · `min_price_vnd` (bigint) · `sold_out_at` (timestamptz, null) · `snapshot_at`.
- Index: `(event_id, snapshot_date)`, `(organization_id, snapshot_date)`.

### Storage Strategy
- **Primary store:** PostgreSQL, database-per-service (ADR-1002); migration Flyway `V0104__booking_proposals`, `V0105__session_context`, `V0106__booking_intents` (ai-chatbox); `V01xx` tương ứng ở ordering/inventory/analytics.
- **Cache:** không thêm Redis cho phần này; slot memory nằm trong Postgres (một dòng/phiên). Prompt cache ở phía nhà cung cấp mô hình nhờ system prompt bất biến.
- **Retention:** ADR-2011. **Backup:** theo cụm Postgres hiện có.

---

## 6. API Specifications

**Protocol:** REST/JSON (ADR-2010)   **AuthN:** Bearer JWT Keycloak; `/internal/**` thêm `X-Internal-Token`   **Versioning:** tiền tố `/v1`, thêm trường không phá; endpoint mới thay vì sửa shape cũ.

### `POST /v1/chat/agent/support` (sửa) và `POST /v1/chat/agent/support/stream` (mới)
**Auth:** Required. **Request:** `{sessionId?, message ≤ 2000}`; header `Idempotency-Key` khuyến nghị.
**Response:** `AskResponse {sessionId, answer, toolsUsed[], action: ActionCard|null}`; stream: sự kiện `status|delta|replace|action|done|error` (ADR-2007).
**Errors:** 503 `ASSISTANT_BUSY`, 404 `CHAT_SESSION_NOT_FOUND`. **NFR:** token đầu ≤ 3 giây (p95, mô hình trả phí); trần đồng thời giữ nguyên.

### `POST /v1/chat/agent/proposals/{proposalId}/confirm`
**Auth:** Required; chủ sở hữu. **Request:** rỗng; `Idempotency-Key` tuỳ chọn (server tự dùng `proposalId`).
**Response 201:** `{proposalId, status: "CONFIRMED", order: {orderId, orderNumber, totalVnd, checkoutUrl, paymentExpiresAt}, action: ActionCard<PAYMENT>}` — cùng thẻ mà đường xác nhận bằng câu trả lời trả về trong `AskResponse.action`.
**Errors:** 404 `PROPOSAL_NOT_FOUND`, 410 `PROPOSAL_EXPIRED`, 409 `PROPOSAL_ALREADY_CONFIRMED` (trả lại đơn cũ trong body), 422 `BOOKING_REJECTED {reason}` (hết chỗ, vượt hạn mức — từ inventory/ordering), 503 `REMOTE_UNAVAILABLE`.
**NFR:** p95 ≤ 2 giây (hai lời gọi nội bộ, timeout 3 giây mỗi lời).

### `POST /v1/chat/agent/proposals/{proposalId}/cancel` → 204.

### `POST /v1/chat/agent/intents/{intentId}/confirm` (Phase 2)
**Response 200:** `{intentId, status: "ACTIVE", queuePosition, validUntil}`. **Errors:** 409 `INTENT_LIMIT_REACHED`, 422 `INTENT_DISABLED_FOR_EVENT`, 410 khi `validUntil` đã qua.

### `GET /v1/chat/agent/intents` · `GET /v1/chat/agent/intents/{id}` · `POST /v1/chat/agent/intents/{id}/cancel`
Trả `{id, event: {slug, title}, zoneCodes, quantity, maxUnitPriceVnd, status, queuePosition?, fulfilledOrder?, validUntil}`.

### `POST /internal/holds/on-behalf-of` (inventory) · `POST /internal/orders/on-behalf-of` (ordering)
**Auth:** `X-Internal-Token` + `grant` RS256 trong body. **Request (hold):** `{grant, eventSessionId, seatedZones: [{zoneCode, quantity}] | standing: […]}`; **(order):** `{grant, holdId}`; header `Idempotency-Key` bắt buộc.
**Response:** như `HoldCreated` / `OrderCreated` hiện có. **Errors:** 403 `GRANT_INVALID` (chữ ký, hết hạn, `jti` thu hồi, tham số ngoài claims), còn lại như endpoint công khai.

### Sự kiện AMQP mới (outbox): `support.handoff.replied {sessionId, userId, messageId}` · `intent.fulfilled {intentId, userId, orderId, orderNumber, paymentExpiresAt}` · `intent.failed {intentId, userId, reason}`.

---

## 7. FR / NFR Coverage Matrix

| ID | Type | Requirement | Component(s) | ADR(s) | Status |
|----|------|-------------|--------------|--------|--------|
| FR-001 | FR | Mô hình lập đề xuất đặt vé; không tạo đơn | Agent, BookingProposalUseCase | 2001 | Addressed |
| FR-002 | FR | Khách xác nhận/huỷ đề xuất bằng thẻ; xác nhận tạo hold+order | BookingActionController, TicketBookingUseCase | 2001, 2006 | Addressed |
| FR-003 | FR | Lượt sau không phải tra lại sự kiện/suất/khu | SessionContextPort, Agent | 2002 | Addressed |
| FR-004 | FR | Không đặt trùng khi khách nhắn lại | BookingProposalUseCase | 2002, 2006 | Addressed |
| FR-005 | FR | Khách để lệnh săn vé (mở bán / vé nhả); worker thực thi | BookingIntentUseCase, IntentExecutionJob, IntentWakeupListener | 2003, 2004 | Addressed (Phase 2) |
| FR-006 | FR | Lệnh tuân hạn mức, giá trần, 1 lệnh/người/suất | IntentUseCase, inventory/ordering handler | 2003, 2005 | Addressed (Phase 2) |
| FR-007 | FR | Báo khách khi lệnh giữ được vé / thất bại | notification-service | 2004 | Partial — chỉ email; push là phụ thuộc ngoài |
| FR-008 | FR | Trả lời dạng stream; tin nhân viên tới ngay | SSE endpoint, realtime-gateway | 2007 | Addressed (Phase 2) |
| FR-009 | FR | Ghi snapshot doanh số theo ngày làm nền dự báo | analytics SnapshotJob | 2009 | Addressed |
| FR-010 | FR | Bộ eval với mô hình thật trước khi đổi prompt/mô hình | Eval harness | 2008 | Addressed |
| FR-011 | FR | Organizer bật/tắt săn vé theo sự kiện | catalog flag, draftBookingIntent | 2005 | Addressed (Phase 2) |
| FR-012 | FR | Chốt chặn grounding đối chiếu con số với payload tool | AnswerGrounding | — | Deferred — Phase 2, không cần ADR (nội bộ một lớp) |
| NFR-001 | NFR | 0 đơn tạo khi chưa xác nhận | ArchitectureTest rule, confirm endpoint | 2001 | Addressed |
| NFR-001b | NFR | Trợ lý không khẳng định đã đặt/không đưa số đơn khi không có đơn thật | BookingClaimGuard | 2001 | Addressed (1.9) |
| NFR-002 | NFR | Chốt chặn thương mại giữ nguyên; idempotency theo ADR-0007 | handler hiện có, on-behalf-of, key nghiệp vụ | 2003, 2006 | Addressed |
| NFR-003 | NFR | Công bằng với người mua tay | FIFO, grace 60 giây, cùng hạn mức | 2005 | Addressed |
| NFR-004 | NFR | Hiệu năng/độ trễ (performance, latency): token đầu ≤ 3 giây p95; lượt ≤ 60 giây với mô hình trả phí; confirm ≤ 2 giây | SSE, định tuyến mô hình, slot memory | 2002, 2007, 2008 | Addressed |
| NFR-005 | NFR | Bảo mật uỷ quyền: không service nào đặt hộ ngoài grant có ký; audit đầy đủ | on-behalf-of + revoked_grants + intent_attempts | 2003 | Addressed |
| NFR-006 | NFR | Dữ liệu dự báo ghi từ Phase 1; trigger săn vé không cần sự kiện mới | SnapshotJob, wakeup từ AMQP có sẵn | 2004, 2009 | Addressed |
| NFR-007 | NFR | Chất lượng mô hình đo được: tool-call ≥ 90%, đề xuất sai = 0 trên bộ eval | Eval harness | 2008 | Addressed |
| NFR-008 | NFR | Prompt injection từ dữ liệu organizer không dẫn tới hành động | ADR-2001 + nhãn untrusted | 2001, 2011 | Addressed |
| NFR-009 | NFR | Dữ liệu cá nhân: che trước prompt, hạn lưu, không PII trong analytics (NĐ 13/2023) | mask, reaper mở rộng | 2011 | Addressed |
| NFR-010 | NFR | Khả năng mở rộng: trần đồng thời theo instance, hàng đợi hữu hạn, job có khoá SKIP LOCKED chạy được nhiều replica | TurnAdmission, IntentExecutionJob | 2004 | Addressed |
| NFR-011 | NFR | Độ tin cậy: thứ tự AMQP bất kỳ, giao ≥ 1 lần, backoff; mô hình sập → chuyển người thật | processed_events, wakeup+retry, executeAgentProcess | 2004 | Addressed |
| NFR-012 | NFR | Quan sát được: metric `aichatbox_proposals_total{status}`, `aichatbox_intents_executed_total{outcome}`, `aichatbox_first_token_seconds`; log có correlationId; cảnh báo khi `LOW_CONFIDENCE` handoff > 20%/giờ | AgentMetrics mở rộng, Prometheus hiện có | 2010 | Addressed |
| NFR-013 | NFR | Bảo trì (maintainability): hexagonal, ArchitectureTest, migration Flyway, tài liệu ADR (documentation) | cấu trúc package | 2010 | Addressed |

### Detailed NFR notes (per driver)

- **NFR-001** — kiểm bằng ba lớp: (a) tool catalog không có tool ghi; (b) `ArchitectureTest` cấm `application.agent` → `InventoryClientPort`; (c) truy vấn đối soát hằng ngày: mọi `orders.channel = 'CHAT'` phải có `proposal_id` CONFIRMED, mọi `channel = 'CHAT_INTENT'` phải có `intent_id` với grant hợp lệ.
- **NFR-002** — on-behalf-of gọi đúng `PlaceHoldHandler`/`PlaceOrderHandler`; không có code path "bỏ qua hạn mức"; test tích hợp của inventory thêm ca "grant hợp lệ nhưng vượt `max_tickets_per_customer` → từ chối".
- **NFR-003** — FIFO + grace 60 giây + flag organizer; đo bằng tỷ lệ vé của một suất đi qua kênh `CHAT_INTENT` (cảnh báo nếu > 30%).
- **NFR-004** — SSE vòng cuối; mô hình trả phí cho lượt commerce; slot memory giảm vòng. Baseline hiện tại: 114 giây/lượt với local (đo trong `support.ts`).
- **NFR-006** — SnapshotJob vào Phase 1 vì không ghi bù được; wakeup từ sự kiện có sẵn để không sửa ba service ở Phase 2.

---

## 8. Technology Stack

| Layer | Choice | Version | Rationale (→ driver) | ADR |
|-------|--------|---------|----------------------|-----|
| Backend | Spring Boot (Java 21, records, sealed) | như repo | brownfield; hexagonal + ArchitectureTest có sẵn (→ NFR-013) | 2010 |
| LLM commerce | Anthropic Claude Sonnet 5.5 / Haiku 5.5 qua `AnthropicLlmAdapter` | SDK hiện có | tool-call tin cậy, streaming, prompt cache (→ NFR-004, NFR-007) | 2008 |
| LLM FAQ | Ollama qwen2.5 7B (GPU) hoặc Anthropic | hiện có | không tốn phí cho câu hỏi chính sách (→ chi phí) | 2008 |
| Embedding | bge-m3 1024d (local) / voyage-3.5 | hiện có | khớp `vector(1024)`, không đổi schema | — |
| Database | PostgreSQL + pgvector, Flyway | hiện có | database-per-service (ADR-1002) | 2010 |
| Messaging | RabbitMQ + starter-outbox + starter-idempotency | hiện có | ≥ 1 lần, dedupe `processed_events` (ADR-1009, 0005, 0007) | 2004 |
| Uỷ quyền nội bộ | JWT RS256 (nimbus-jose đã có qua Spring Security) + `X-Internal-Token` | hiện có | grant có ký, thu hồi theo `jti` (→ NFR-005) | 2003 |
| Streaming | Spring MVC `SseEmitter` trên `agentExecutor` | hiện có | một chiều, dùng lại ApiClient (→ NFR-004) | 2007 |
| Realtime | realtime-gateway WebSocket (hiện có) | hiện có | topic mới `chat:{sessionId}` | 2007 |
| Frontend | Next.js web-customer, Expo mobile-customer, `packages/ts-sdk` | hiện có | thẻ `ActionCard` dựng từ trường có kiểu | 2001 |
| Eval | JUnit profile `eval` + YAML; chạy với khoá thật | mới | chặn hồi quy prompt (→ NFR-007) | 2008 |
| Dự báo (Phase 3) | Python: LightGBM/statsforecast; đọc export từ analytics | sau | ngoài đường giao dịch (ADR-0012 tinh thần) | 2009 |

**Alternatives considered:** LangChain4j/Spring AI tool-runner — từ chối (mất kiểm soát vòng ReAct đã test được); Kafka — không (ADR-1009); Redis cho slot memory — không cần (một dòng/phiên, Postgres đủ và ADR-1015 ghi Redis có chế độ hỏng riêng).

---

## 9. Trade-off Analysis

### Trade-off: Một chạm xác nhận vs. "đặt ngay trong câu chat"
**Decision:** thẻ xác nhận (ADR-2001). **Options:** A — mô hình tự đặt khi "đủ chắc": mượt nhưng không chứng minh được; B — thẻ: thêm một chạm, chặn được injection và lỗi mô hình. **Rationale:** NFR-001 là bất biến; chi phí một chạm nhỏ hơn một đơn sai lúc cao điểm. **Accepted:** Benefit: 0 đơn ngoài ý muốn / Cost: +1 chạm / Mitigation: thẻ trong luồng chat. **Revisit when:** có xác nhận ngoài chat.

### Trade-off: Grant có ký vs. refresh token của khách
**Decision:** grant RS256 phạm vi hẹp (ADR-2003). **Rationale:** quyền chỉ đúng một suất, một số vé, một hạn; thu hồi được; không giữ bí mật của khách. **Cost:** sửa hai service khác + quản lý khoá. **Revisit when:** Keycloak token exchange có phạm vi hẹp.

### Trade-off: Wakeup + job vs. thực thi ngay trong consumer
**Decision:** wakeup + job (ADR-2004). **Rationale:** `order.expired` tới trước khi inventory nhả chỗ; job có backoff và SKIP LOCKED chạy được nhiều replica. **Cost:** trễ 5–30 giây. **Revisit when:** p95 trễ > 60 giây.

### Trade-off: FIFO vs. bốc thăm
**Decision:** FIFO + grace (ADR-2005). **Rationale:** giải thích được; grace bảo vệ người mua tay lúc mở bán. **Cost:** người đến sau luôn thua. **Revisit when:** organizer yêu cầu hoặc có hàng đợi ảo.

### Trade-off: Một service vs. tách booking-intent-service
**Decision:** giữ trong ai-chatbox (mục 2). **Cost:** service có hai nhịp (chat đồng bộ, worker nền) chia chung pool 10 kết nối. **Mitigation:** job dùng ≤ 2 kết nối; `maxConcurrentTurns` không đổi. **Revisit when:** worker cần scale riêng.

### Trade-off: Mô hình trả phí cho lượt commerce vs. local toàn bộ
**Decision:** định tuyến (ADR-2008). **Cost:** chi phí API theo lượt. **Benefit:** tool-call đạt ngưỡng eval; lượt ≤ 60 giây. **Revisit when:** local ≤ 14B đạt ≥ 90% eval.

---

## 10. Deployment Architecture

### Environments
- **Development:** compose `deploy/compose`, `AI_PROVIDER=local`, `BOOKING_INTENT_ENABLED=false` mặc định; eval chạy tay với khoá.
- **Staging:** như prod, `AI_PROVIDER_COMMERCE=anthropic`, intent bật cho sự kiện test; eval chạy trong CI khi đụng prompt/tool.
- **Production:** EC2 + compose hiện có (`prod.yml`); intent bật theo cờ môi trường và theo cờ sự kiện của organizer.

### Topology
ai-chatbox 1–2 replica sau api-gateway; job thực thi lệnh an toàn với nhiều replica nhờ `SKIP LOCKED`; `TurnAdmission` vẫn theo instance (chấp nhận, như hiện tại). Khoá RS256 của GrantIssuer: biến môi trường `INTENT_GRANT_PRIVATE_KEY` (ai-chatbox) và `INTENT_GRANT_PUBLIC_KEY` (inventory, ordering).

### Strategy
- **Deployment method:** rolling theo service như hiện có; thứ tự Phase 2: inventory/ordering (endpoint on-behalf-of, tắt bằng cờ) → ai-chatbox → notification → bật cờ.
- **Rollback:** tắt `BOOKING_INTENT_ENABLED` dừng worker ngay (lệnh giữ trạng thái ACTIVE, không mất); Phase 1 rollback là bật lại `initiateTicketBooking` — **không** làm, vì vi phạm NFR-001; thay vào đó tắt `proposeBooking` và trợ lý chỉ đưa link trang sự kiện.
- **Scaling:** tăng `AGENT_MAX_CONCURRENT_TURNS` khi dùng mô hình trả phí; thêm replica ai-chatbox; snapshot job chạy một instance (khoá `pg_advisory_lock`).

---

## 11. Future Considerations

**Anticipated changes:** hàng đợi ảo lúc mở bán (lệnh xếp chung); push mobile cho `intent.fulfilled`; `forecast-service` Phase 3 đọc `session_sales_daily`; gợi ý khu/suất thay thế khi hết vé (dùng slot memory + catalog).

**Scalability path:** hiện 16 lượt đồng thời/instance với local → với mô hình trả phí nâng 64/instance; worker giới hạn bởi tốc độ `holdZone` (≈ 50 ms) nên hàng trăm lệnh/wakeup là đủ.

**Revisit triggers (tổng hợp):** p50 vòng ReAct cho lượt proposal > 2; trễ wakeup p95 > 60 giây; vé qua `CHAT_INTENT` > 30% một suất; local ≤ 14B đạt ≥ 90% eval; ≥ 30 suất có đường cong đầy đủ; Keycloak token exchange phạm vi hẹp khả dụng.

---

## Appendix

### Glossary
| Term | Definition |
|------|------------|
| Booking Proposal | Đề xuất đặt vé do mô hình lập, chưa chạm tồn kho, chờ khách xác nhận trong 10 phút |
| Booking Intent | Lệnh săn vé: điều kiện (suất/khu/số vé/giá trần/hạn) + grant uỷ quyền, worker thực thi |
| Grant | JWT RS256 ai-chatbox ký, phạm vi một lệnh, cho inventory/ordering đặt hộ đúng khách |
| Wakeup | Dấu hiệu "suất X có thể có vé" sinh từ sự kiện AMQP, job xử lý |
| Slot memory | `chat_session_context.focus_event` — sự kiện/suất/khu đang bàn, UUID đã xác minh |
| ActionCard | Trường có kiểu trong `AskResponse` để giao diện vẽ thẻ xác nhận |

### References
- Brief: `bmad-output/product-brief-ai-chatbox-2026-10-22.md`
- Decision log: `bmad-output/decision-log.md`
- ADR hệ thống: `docs/01-foundation/adr/ADR-0005`, `ADR-0007`; `docs/03-seat-checkout/adr/ADR-0015`; `docs/05-ai-scale/adr/ADR-0012`; `docs/architecture-v2/adr/ADR-1002`, `ADR-1009`, `ADR-1014`, `ADR-1015`
- Mã nguồn tham chiếu: `backend/services/ai-chatbox-service/src/main/java/com/nexaticket/aichatbox/{application/agent,application/booking,interfaces/rest}`, `inventory-service … HoldController`, `ordering-service … OrderController`, `notification-service … OrderEventsListener`, `frontend/packages/ts-sdk/src/api/support.ts`

### Document History
| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-10-22 | Winston (Architect) | Kiến trúc ban đầu cho Booking Proposal, Booking Intent, nền dự báo |

---

**END OF DOCUMENT** — Sẵn sàng cho bmad-epics-and-stories sau khi ADR-2001 được chấp nhận và ADR-0012 được đánh dấu Superseded.
