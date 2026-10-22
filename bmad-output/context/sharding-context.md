# Sharding context — AI Chatbox Phase 1

Đọc tệp này trước khi biên soạn story. Mọi story là **tài liệu kế hoạch**: không viết code, không chạy test.

## Nguồn

- Kiến trúc (nguồn chính, ADR đã Accepted): `bmad-output/architecture.md`
- Brief: `bmad-output/product-brief-ai-chatbox-2026-10-22.md`
- Bản đồ epic: `bmad-output/epics.md`
- Template story: `C:/Users/thien/.claude/plugins/cache/bmad-method-harness/bmad-planning-orchestrator/0.5.0/skills/bmad-epics-and-stories/templates/story.template.md`
- Mã nguồn: `backend/services/ai-chatbox-service` (KHÔNG phải `services/ai-chatbox-service` ở gốc — bản cũ), `backend/services/analytics-service`, `backend/services/catalog-service`, `frontend/packages/ts-sdk`, `frontend/apps/web-customer`.

## Quy tắc biên soạn

- Tên tệp `bmad-output/stories/{epic}.{story}.{slug}.story.md`; slug và thứ tự lấy từ `epics.md`.
- Mỗi story ≤ một ngày làm việc; AC 3–7, mỗi Task ghi `(AC: #N)`; Dev Notes có citation `[Source: architecture.md#ADR-2001]`, `[Source: epics.md#epic-1]`, hoặc `[Source: <đường dẫn mã nguồn>:<dòng>]`; suy luận ghi `[Inference]`.
- **Owned File/Module Scope**: đường dẫn cụ thể (tệp, không glob rộng), tính từ gốc repo. Gạch đầu dòng, một đường dẫn mỗi dòng, không chú thích sau đường dẫn (checker lấy từ đầu tiên của dòng). Ghi rõ tệp tranh chấp.
- Status để `backlog`. Dev Agent Record để trống.
- Viết tiếng Việt, kỹ thuật giữ tiếng Anh. Không dùng story point.
- Không sửa `services/ai-chatbox-service` (bản cũ). Không thêm hạ tầng mới (Redis, Kafka).

## Dữ kiện mã nguồn đã xác minh (để Dev Notes trích dẫn)

### ai-chatbox-service (`backend/services/ai-chatbox-service/src/main/java/com/nexaticket/aichatbox/`)
- Hexagonal: `domain/model`, `domain/port`, `application/{agent,booking,handoff,knowledge}`, `infrastructure/{http,llm,embedding,persistence,seed,concurrent}`, `interfaces/rest`. `src/test/java/.../ArchitectureTest.java` có 3 `@ArchTest` (ArchUnit).
- `application/agent/CustomerSupportAgentUseCase.java`: vòng ReAct; `runAgent` bước 1 lịch sử, 1b phiếu mở, 1c `HandoffIntent`, 2 RAG `retrieve`, 3 `SupportAgentPrompts.userMessageWithContext`, 4 vòng `for round < properties.maxToolIterations()`; tool điều khiển (`CONTROL_TOOLS`) xử lý tại chỗ bằng `handleControlTool`; tool dữ liệu qua `tools.dispatch(call)` trả `ToolDispatcher.DispatchResult(outcome, events)`; bước 5 `AnswerGrounding.enforce`, `withTicketLinks`; bước 6 `history.appendTurn(sessionId, userId, userQuery, ChatRole.ASSISTANT, withLinks)`; trả `new AgentReply(sessionId, answer, toolsUsed)`.
- `application/agent/AgentReply.java`: record `(sessionId, answer, toolsUsed)`.
- `application/agent/ToolDispatcher.java`: `switch (call.toolName())` cho `GET_ORDER_STATUS, GET_EVENT_RULES, FIND_EVENTS, GET_EVENT_DETAILS, GET_CUSTOMER_PROFILE_AND_HISTORY, INITIATE_TICKET_BOOKING`; `initiateTicketBooking(call)` gọi `booking.initiate(slug, sessionId, zoneCode, quantity, call.callId())`; `describe(EventDetail)` đưa `summary`, `description`, `sessions[].sessionId/tiers[].zoneCode/priceVnd`, `customerUrl`; `DispatchResult.of(outcome)`; `metrics.recordTool(name, "ok"|"failed")`.
- `application/agent/SupportAgentTools.java`: hằng tên tool, `CONTROL_TOOLS = {escalateToHuman, requestTicketRefund, reportIncident}`, `CATALOG` là `List<ToolSpec>` có `INITIATE_TICKET_BOOKING` (slug, sessionId, zoneCode, quantity). `ToolSpec.Param.requiredString/optionalString`, `new ToolSpec.Param(name, "integer", desc, required)`.
- `application/agent/SupportAgentPrompts.java`: `systemPrompt()`, `userMessageWithContext(question, List<KnowledgeChunk>)`, `withTicketLinks`, `stripInternalMarkers`, các câu báo.
- `application/agent/AgentProperties.java`: record `@ConfigurationProperties("nexaticket.aichatbox.agent")` với `model` (mặc định hard-code `claude-opus-5` — cần bỏ), `maxToolIterations=4`, `historyTurns=10`, `retrievalTopK=5`, `maxRetrievalDistance=0.55`, `maxConcurrentTurns=16`, `maxQueuedTurns=32`, `maxConcurrentTurnsPerUser=2`.
- `application/agent/AgentMetrics.java`: `recordLlmCall`, `recordRounds`, `recordHandoff(trigger,intent)`, `recordTool(name,outcome)`; Micrometer `MeterRegistry`.
- `application/agent/HandoffIntent.java`: bộ nhận ý định bằng từ khoá, không LLM — mẫu cho `CommerceIntent`.
- `application/booking/TicketBookingUseCase.java`: `initiate(slug, eventSessionId, zoneCode, quantity, idempotencySeed)` → `BookingOutcome.Started(event, hold, order) | Rejected(reason)`; kiểm `MAX_QUANTITY=6`, slug, session thuộc event, zone thuộc session, `catalog.findZoneAdmission`; `inventory.holdZone(..., "chat-hold-"+seed)`; `ordering.placeOrder(holdId, token, "chat-order-"+seed)`; `releaseQuietly` khi đặt đơn hỏng.
- `domain/port`: `CatalogClientPort` (`searchEvents`, `findEventBySlug(slug) → Optional<EventDetail>`, `findZoneAdmission`), `InventoryClientPort` (`holdZone`, `releaseHold`), `OrderingClientPort` (`fetchOrder`, `listMyOrders`, `placeOrder`), `ChatHistoryPort` (`ownerOf`, `recentTurns`, `append(sessionId,userId,role,text)`, `appendTurn`, `transcript`, `transcriptForSupport`), `CallerCredentialsPort.currentAccessToken()`.
- `domain/model`: `ChatRole {USER, ASSISTANT, AGENT}` — **không có SYSTEM**; lượt "đã tạo đơn" ghi bằng `ASSISTANT`. `EventDetail(slug,title,summary,description,city,venueName,venueAddress,sessions[])`, `Session(id,startsAt,salesCloseAt,tiers[])`, `Tier(name,priceVnd,zoneCode)`. `PlacedOrder(orderId, orderNumber, totalVnd, checkoutUrl, paymentExpiresAt)`. `TicketHold(holdId, expiresAt, quantity)`. `ToolOutcome.ok(callId, json)/error(callId, msg)`, `ToolInvocation.stringArg/intArg/callId/toolName`.
- `application/AiChatboxErrorCode.java`: enum mã lỗi dùng với `ApiException(code, message)` của `com.nexaticket.platform.web.error`.
- `interfaces/rest/SupportChatController.java`: `POST /v1/chat/agent/support` nhận `AskRequest(sessionId?, message ≤2000)`, trả `CompletableFuture<AskResponse(sessionId, answer, toolsUsed)>`; `TenantContext.requireAuthenticated().userId().value()` lấy userId; `TurnAdmission`, `ChatIdempotency`, `agentExecutor`; `GET /sessions/{id}/messages`; `POST /sessions/{id}/handoff`.
- `application/handoff/AbandonedHandoffReaper.java`: job theo lịch, cấu hình `nexaticket.aichatbox.handoff.reaper.*` — mẫu cho job retention/expiry.
- Migration: `src/main/resources/db/migration/aichatbox/V0100..V0103`; Flyway `out-of-order: true`. Quy ước: snake_case, uuid PK, `*_vnd BIGINT`, `timestamptz`, index khớp truy vấn.
- Test: `src/test/java/.../support/{AiChatboxTestBase, FakeAiProviders, FakeCommerce, InMemoryChatHistory, InMemoryHandoffRepository}`; IT dùng Testcontainers (lưu ý memory: lớp base chung, fixture duy nhất theo lần chạy).
- `application.yml`: `nexaticket.aichatbox.{provider, ollama.*, agent.*, anthropic.*, embedding.*, starter-knowledge, handoff.*, services.*}`; `AI_PROVIDER` local|anthropic; `LlmProvider` enum chọn adapter.

### Service khác
- analytics-service: `db/migration/analytics/V0100__analytics.sql` có `session_sales(event_session_id PK, event_id, organization_id, tickets_sold, gross_vnd, orders_paid, orders_expired, orders_cancelled, updated_at)` — cộng dồn, KHÔNG CHECK ≥ 0; `infrastructure/amqp/OrderEventsListener` (queue, dedupe `processed_events` của starter-idempotency), `application/ApplySalesDeltaHandler`, `SalesDelta`, `SalesReadModelRepository`/`JdbcSalesReadModelRepository`, `interfaces/rest/InternalSalesController` (`/internal/events/{id}/sales`, `/internal/events/trending`, `/internal/organizations/{id}/sales`), `SalesDashboardController`. Chưa có `@Scheduled` nào.
- catalog-service: `InternalCatalogController` `/internal/sessions/{eventSessionId}` và `/internal/sessions/{eventSessionId}/pricing`; gọi bằng RestClient có `X-Internal-Token` tự gắn (starter-security `InternalTokenPropagation`).
- Routing key AMQP đang có: `order.created`, `order.paid`, `order.expired`, `order.cancelled`, `order.refunded`, `session.published`, `ticket.checked_in`.
- frontend: `packages/ts-sdk/src/api/support.ts` (`askSupport`, `getChatThread`, `requestHumanAgent`, …), `packages/ts-sdk/src/types/support.ts` (`AskRequest`, `AskResponse`, `ChatThread`, `ChatMessage`, `ChatRole`…), test ở `packages/ts-sdk/src/__tests__/`; `apps/web-customer/src/components/SupportChat.tsx` (`send()`, `WithTicketLinks`, `bubbleClass`), `SupportLauncher.tsx`, trang `src/app/(site)/support/page.tsx`. mobile-customer **chưa có** chat.

## Quyết định LOCKED (tóm tắt từ architecture.md)

- ADR-2001: gỡ `initiateTicketBooking` khỏi `CATALOG`; thêm `proposeBooking(slug, sessionId, zoneCode, quantity)` → lưu `booking_proposals` TTL 10 phút, trả `proposalId`; `AskResponse.action: ActionCard | null` với `{kind: "BOOKING_PROPOSAL", id, summary, totalVnd, expiresAt}`; `POST /v1/chat/agent/proposals/{id}/confirm` và `/cancel` không qua mô hình; confirm chạy `TicketBookingUseCase.initiate` với seed = `proposalId`; ArchitectureTest cấm `application.agent` → `InventoryClientPort`, `OrderingClientPort.placeOrder`.
- ADR-2002: bảng `chat_session_context(session_id PK, focus_event JSONB, updated_at)`; `focus_event = {slug, title, sessionId, startsAt, zones[{zoneCode,name,priceVnd}], pendingOrder{orderId,orderNumber,paymentExpiresAt}|null, fetchedAt}`; ghi khi `getEventDetails`/`proposeBooking` thành công; đọc đầu lượt nếu `fetchedAt` < 15 phút, chèn vào **message người dùng** khối `<<NGỮ CẢNH ĐÃ XÁC MINH>>`; `propose` kiểm `pendingOrder` cùng `sessionId+zoneCode` → `proposed:false, reason:"ĐÃ CÓ ĐƠN CHỜ THANH TOÁN"` kèm `checkoutUrl`.
- ADR-2006: idempotency `"proposal-" + proposalId` cho cả hold và order; không dùng `callId`/random.
- ADR-2008: bean `faqLlm`/`commerceLlm`; `AI_PROVIDER_FAQ`/`AI_PROVIDER_COMMERCE` mặc định = `AI_PROVIDER`; `CommerceIntent.isLikely(query, sessionContext)` từ khoá + `focus_event != null`; eval YAML `src/test/resources/eval/*.yaml`, `mvn -Peval`, ngưỡng tool-call ≥ 90%, đề xuất sai = 0; `AgentProperties.model` mặc định ra `application.yml`.
- ADR-2009: `session_sales_daily(event_session_id, zone_code, snapshot_date PK ghép, event_id, organization_id, days_since_on_sale, days_to_event, capacity, tickets_sold_cum, holds_created, orders_expired, min_price_vnd, sold_out_at, snapshot_at)`; cron `0 5 0 * * *` Asia/Ho_Chi_Minh; `pg_advisory_lock`; `holds_created` từ `order.created`, `sold_out_at` khi `tickets_sold == capacity` lần đầu.
- ADR-2010: REST `/v1/chat/agent/**`; mã lỗi mới `PROPOSAL_NOT_FOUND(404)`, `PROPOSAL_EXPIRED(410)`, `PROPOSAL_ALREADY_CONFIRMED(409, trả đơn cũ)`, `BOOKING_REJECTED(422)`, `REMOTE_UNAVAILABLE(503)`; JSON camelCase, DB snake_case; trạng thái `booking_proposals.status ∈ {PROPOSED, CONFIRMED, EXPIRED, CANCELLED}`; tool trả `proposed` + `reason` + `hint`; metric tiền tố `aichatbox_`.
- ADR-2011: che email/SĐT giữ nguyên; xoá `chat_messages`, `chat_session_context`, `booking_proposals` sau 180 ngày; mô tả sự kiện bọc `"untrusted": true`, cắt ≤ 1.500 ký tự, system prompt nói rõ là dữ liệu.
- Bảng `booking_proposals`: `id uuid PK, user_id, chat_session_id FK chat_sessions, event_slug, event_session_id, zone_code, quantity, unit_price_vnd, total_vnd, status, order_id null, expires_at, created_at, confirmed_at`; index `(user_id, status, expires_at)`; partial unique `(user_id, event_session_id, zone_code) WHERE status='PROPOSED'`.

## Sửa đổi 2026-10-22 (course correction) — đọc TRƯỚC khi sửa story 1.3/1.4/1.5/2.4, viết 1.7/1.8

ADR-2001 mục Decision đã sửa (xem `architecture.md#ADR-2001`, điểm 2–6):
- `ActionCard` có ba loại: `BOOKING_PROPOSAL {id, summary, eventTitle, sessionStartsAt, zoneCode, quantity, totalVnd, expiresAt}`, `PAYMENT {orderId, orderNumber, totalVnd, vietQrPayload, paymentReference, checkoutUrl, paymentExpiresAt}`, `BOOKING_INTENT` (Phase 2, chỉ khai enum).
- Xác nhận có hai đường không qua mô hình: nút (`POST /proposals/{id}/confirm`) và **câu trả lời ngắn** do `BookingConfirmIntent` (từ khoá, mẫu `HandoffIntent`) bắt ở bước 1d của `runAgent`, trước RAG/LLM, chỉ khi phiên có đúng một đề xuất `PROPOSED` còn hạn của chính user. Từ chối ("thôi/không/huỷ") → cancel. Không có đề xuất mở → câu đi tiếp vào LLM.
- Confirm (cả hai đường) trả thẻ `PAYMENT` kèm VietQR. `PlacedOrder` thêm `vietQrPayload`, `paymentReference` (ordering `OrderCreated` đã trả — xem `OrderController.OrderCreated`); `OrderingHttpAdapter` hiện cố ý bỏ hai trường này (~dòng 150) → giữ lại, nhưng KHÔNG đưa vào JSON tool cho mô hình; chỉ đi qua `ActionCard`.
- Kết quả tool `proposeBooking` có `nextStep`: "Nhắc lại suất/khu/số vé/tổng tiền và mời khách trả lời *ok* để giữ chỗ".
- `GET /v1/chat/agent/sessions/{id}/messages` (`HandoffViews.ChatThread`) thêm `action: ActionCard | null` = đề xuất `PROPOSED` còn hạn mới nhất, hoặc thẻ PAYMENT của đề xuất `CONFIRMED` mới nhất còn trong hạn thanh toán (dựng từ `order_number/checkout_url/payment_expires_at` lưu ở `booking_proposals` + `vietQrPayload` tra `OrderingClientPort.fetchOrder` nếu `OrderSummary` có, ngược lại thẻ chỉ có `checkoutUrl` và frontend tự tra `GET /v1/orders/{id}` để lấy payload — ghi rõ [Inference] chọn cách nào sau khi đọc `OrderSummary`/`OrderView`).
- Frontend: `apps/web-customer/src/components/PaymentPanel.tsx` đã vẽ VietQR từ `vietQrPayload` và poll trạng thái đơn — tái dùng (tách component QR dùng chung nếu cần).
- Thứ tự mới: 1.3 → 1.5 → 1.7 (thẻ PAYMENT frontend); 1.8 (`confirm-by-reply`) blocked by 1.3, 1.4; 2.2 blocked by 1.8 (cùng `CustomerSupportAgentUseCase`); 2.4 không đổi phụ thuộc nhưng ghi rõ đường xác nhận bằng câu cũng ghi `pendingOrder`.
- Đây là thay đổi mục LOCKED có uỷ quyền của người dùng (decision-log 2026-10-22, Course Correction).


## Epic 6 — Review hardening (2026-10-22) — đọc TRƯỚC khi viết story 6.x

- Nguồn chính: `bmad-output/tech-spec.md` (bảng R-01…R-29 có `tệp:dòng`, FR-001…FR-013, Technical Approach, Testing Strategy, Story List kèm thứ tự với Phase 1). Citation dạng `[Source: tech-spec.md#FR-003]`, `[Source: tech-spec.md#requirements — R-14]`.
- Epic 6 sửa trên mã `main` HIỆN TẠI (chưa có Phase 1). Không giả định tệp/kiểu của Phase 1 (ActionCard, BookingProposal…) tồn tại.
- Migration dùng chung `V0108__handoff_indexes_and_seed_unique.sql`: 6.3 sở hữu tệp, 6.5 thêm phần tri thức SAU 6.3 (Blocked-by 6.3) — hoặc 6.5 dùng `V0109__knowledge_seed_unique.sql` riêng; chọn V0109 riêng để không tranh chấp tệp.
- Mã lỗi mới thêm vào `AiChatboxErrorCode.java` (tệp tranh chấp giữa 6.1, 6.2, 6.10 và Phase 1 1.2/1.10) — khai shared, xếp tuần tự.
- `application.yml` (tệp tranh chấp 6.2, 6.5, 6.8, 6.9 và Phase 1 1.6, 3.3, 5.1).
- Thứ tự gợi ý trong epic: 6.1 → 6.2 → 6.3 → 6.4 (cùng HandoffUseCase/JdbcHandoffRepository); 6.5, 6.6 độc lập; 6.7 → 6.11 (SupportAgentPrompts); 6.8; 6.9 → 6.10 (SupportChatController); 6.12 độc lập.
- Memory dự án cần nhắc trong Testing của story có IT: fixture duy nhất theo từng lần chạy (container `withReuse` giữ dữ liệu cũ); không khai `@Container` trong lớp base dùng chung (dùng `AiChatboxTestBase` hiện có).
- Status: `ready-for-dev`; scope mỗi dòng một đường dẫn từ gốc repo, không chú thích cùng dòng; mọi Task có `(AC: #N)`.
