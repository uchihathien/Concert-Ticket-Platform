# Epics — NexaTicket AI Chatbox, Phase 1

> Bản đồ epic. Chi tiết nằm trong `bmad-output/stories/{epic}.{story}.{slug}.story.md`.
>
> Track: BMad Method (brownfield)
> Sources: `bmad-output/architecture.md` (ADR-2001…2011, ma trận mục 7), `bmad-output/product-brief-ai-chatbox-2026-10-22.md`
> Phạm vi: **Phase 1** theo architecture.md §1 Scope. Booking Intent, SSE, realtime-gateway, grounding đối chiếu số (FR-012) thuộc Phase 2 — không có ở đây.

---

## Epic 1: Booking Proposal — AI đề xuất, khách xác nhận

**Goal:** Mô hình không còn tạo được đơn; nó lập đề xuất, giao diện vẽ thẻ, khách bấm xác nhận qua endpoint không đi qua mô hình.

**In scope (cited):**
- FR-001 — mô hình lập đề xuất đặt vé, không tạo đơn [Source: architecture.md#7-fr--nfr-coverage-matrix]
- FR-002 — khách xác nhận/huỷ bằng thẻ; xác nhận tạo hold + order [Source: architecture.md#7-fr--nfr-coverage-matrix]
- NFR-001 — 0 đơn khi chưa xác nhận [Source: architecture.md#ADR-2001]
- NFR-002 — idempotency theo `proposalId` [Source: architecture.md#ADR-2006]
- NFR-008 — injection không dẫn tới hành động [Source: architecture.md#ADR-2001]
- NFR-012 — metric `aichatbox_proposals_total{status}` [Source: architecture.md#7-fr--nfr-coverage-matrix]

**Architecture touchpoints:** `application/booking` (BookingProposalUseCase, TicketBookingUseCase), `application/agent` (SupportAgentTools, ToolDispatcher, AgentReply), `interfaces/rest` (SupportChatController.AskResponse, BookingActionController mới), `ArchitectureTest`, `frontend/packages/ts-sdk`, `web-customer/SupportChat.tsx` [Source: architecture.md#4-component-design]

**Out of scope:** slot memory (Epic 2), chống đơn trùng theo `pendingOrder` (2.4), mobile-customer (chưa có chat), kênh `orders.channel` ở ordering (Phase 2, ADR-2003).

**Stories (ordered):**

| ID | Slug | Intent | Status |
|------|------|--------|--------|
| 1.1 | booking-proposals-migration | Bảng `booking_proposals` (V0104), domain record, port + Jdbc adapter | ready-for-dev |
| 1.2 | propose-booking-tool | `BookingProposalUseCase.propose`; tool `proposeBooking` thay `initiateTicketBooking`; rule ArchitectureTest | ready-for-dev |
| 1.3 | action-card-in-reply | `ActionCard` (BOOKING_PROPOSAL, PAYMENT) đi từ DispatchResult → AgentReply → AskResponse; type trong ts-sdk — *sửa 2026-10-22: thêm loại PAYMENT* | ready-for-dev |
| 1.4 | confirm-cancel-endpoints | `POST /proposals/{id}/confirm|cancel`; idempotency `proposal-{id}`; `PlacedOrder` mang `vietQrPayload`; confirm trả thẻ PAYMENT; `ChatThread.action` cho thẻ đang mở — *sửa 2026-10-22* | ready-for-dev |
| 1.5 | web-confirmation-card | SupportChat.tsx vẽ thẻ đề xuất (gợi ý "trả lời ok", nút dự phòng); sdk `confirmProposal`/`cancelProposal` — *sửa 2026-10-22* | ready-for-dev |
| 1.6 | proposal-expiry-and-metrics | Job đánh dấu EXPIRED sau 10 phút; metric `aichatbox_proposals_total` | ready-for-dev |
| 1.7 | payment-card-in-chat | `# COURSE-CORRECTION 2026-10-22` — thẻ PAYMENT trong SupportChat: VietQR (tái dùng mã QR của `PaymentPanel`), đếm ngược, link dự phòng, poll `GET /v1/orders/{id}` tới PAID/EXPIRED, vẽ lại từ `ChatThread.action` khi tải lại | ready-for-dev |
| 1.8 | confirm-by-reply | `# COURSE-CORRECTION 2026-10-22` — `BookingConfirmIntent` (từ khoá, không LLM) + bước 1d trong `runAgent`: "ok" khi có đúng một đề xuất mở → confirm, trả PAYMENT; "thôi" → cancel | ready-for-dev |

**Cross-epic dependencies:**
- Blocked by: none
- Blocks: Epic 2 (2.2 sau 1.8 vì cùng `CustomerSupportAgentUseCase`; 2.4 sửa `BookingProposalUseCase`), Epic 3 (eval cần tool `proposeBooking`), Epic 5 (5.2 sửa ToolDispatcher)
- Thứ tự trong epic: 1.1 → 1.2 → (1.3 ∥ 1.4 ∥ 1.6) → 1.5 → 1.7; 1.8 sau 1.3 và 1.4

---

## Epic 2: Slot memory — ngữ cảnh đặt vé giữa các lượt

**Goal:** "Ok lấy 2 vé" chạy trong một vòng ReAct; UUID suất/khu chỉ vào prompt từ nguồn đã xác minh; không đề xuất trùng khi đã có đơn chờ thanh toán.

**In scope (cited):**
- FR-003 — lượt sau không tra lại sự kiện/suất/khu [Source: architecture.md#ADR-2002]
- FR-004 — không đặt trùng khi khách nhắn lại [Source: architecture.md#ADR-2002]
- NFR-004 — giảm số vòng cho lượt đề xuất [Source: architecture.md#7-fr--nfr-coverage-matrix]

**Architecture touchpoints:** bảng `chat_session_context` (V0105), `SessionContextPort` mới, `ToolDispatcher.DispatchResult.contextUpdate`, `CustomerSupportAgentUseCase` (đọc đầu lượt), `SupportAgentPrompts.userMessageWithContext` [Source: architecture.md#ADR-2002]

**Out of scope:** định tuyến mô hình theo `focus_event` (3.3 dùng port này nhưng Epic 3 sở hữu classifier).

**Stories (ordered):**

| ID | Slug | Intent | Status |
|------|------|--------|--------|
| 2.1 | session-context-migration | Bảng `chat_session_context` (V0105), record `FocusEvent`, port + Jdbc adapter | ready-for-dev |
| 2.2 | capture-context-from-tools | `getEventDetails`/`proposeBooking` trả `contextUpdate`; agent ghi vào port | ready-for-dev |
| 2.3 | inject-verified-context | Đọc context đầu lượt, TTL 15 phút, khối `<<NGỮ CẢNH ĐÃ XÁC MINH>>` trong message | ready-for-dev |
| 2.4 | pending-order-guard | `propose` từ chối khi `pendingOrder` cùng suất/khu; `confirm` ghi `pendingOrder` (blocked by 1.4, 2.1, 2.2) | ready-for-dev |

**Cross-epic dependencies:**
- Blocked by: Epic 1 (1.2 cho 2.2; 1.4 cho 2.4)
- Blocks: 3.3 (CommerceIntent đọc `focus_event`)

---

## Epic 3: Chất lượng mô hình — eval và định tuyến

**Goal:** Mọi thay đổi prompt/tool/mô hình được đo bằng bộ ca cố định với mô hình thật; lượt có ý định mua đi qua mô hình đủ mạnh.

**In scope (cited):**
- FR-010 — bộ eval với mô hình thật trước khi đổi prompt/mô hình [Source: architecture.md#ADR-2008]
- NFR-007 — tool-call ≥ 90%, "đề xuất khi chưa đủ xác nhận" = 0 [Source: architecture.md#ADR-2008]
- NFR-004 — lượt commerce ≤ 60 giây với mô hình trả phí [Source: architecture.md#7-fr--nfr-coverage-matrix]

**Architecture touchpoints:** `src/test/resources/eval/*.yaml`, profile Maven `eval`, `CommerceIntent` (cùng mẫu `HandoffIntent`), hai bean `faqLlm`/`commerceLlm` trong `infrastructure/llm`, `application.yml` [Source: architecture.md#ADR-2008]

**Out of scope:** streaming (Phase 2), GPU/hạ tầng Ollama.

**Stories (ordered):**

| ID | Slug | Intent | Status |
|------|------|--------|--------|
| 3.1 | eval-harness | Profile `eval`, loader YAML, runner JUnit, báo cáo tool-call accuracy / proposal precision | ready-for-dev |
| 3.2 | eval-cases-phase1 | ≥ 40 ca: tra sự kiện, đề xuất đúng, không đề xuất khi hỏi giá, injection trong mô tả, grounding | ready-for-dev |
| 3.3 | llm-routing-commerce-intent | `CommerceIntent.isLikely`; bean `faqLlm`/`commerceLlm`; `AI_PROVIDER_FAQ/COMMERCE`; model mặc định ra `application.yml` | ready-for-dev |

**Cross-epic dependencies:**
- Blocked by: 1.2 (tool `proposeBooking` để eval), 1.3 (`AgentReply.action` cho runner), 2.1 (port context cho 3.3)
- Blocks: none

---

## Epic 4: Nền dữ liệu dự báo — snapshot doanh số theo ngày

**Goal:** Từ hôm nay, mỗi suất có một dòng/ngày/khu ghi vé bán cộng dồn, sức chứa, giá thấp nhất, số giữ chỗ, số đơn hết hạn và thời điểm hết vé.

**In scope (cited):**
- FR-009 — snapshot `session_sales_daily` [Source: architecture.md#ADR-2009]
- NFR-006 — dữ liệu ghi từ Phase 1, không ghi bù [Source: architecture.md#ADR-2009]

**Architecture touchpoints:** analytics-service: migration `V0101`, `SessionSalesSnapshotJob` (cron 00:05 Asia/Ho_Chi_Minh, `pg_advisory_lock`), `OrderEventsListener`/`ApplySalesDeltaHandler` (đếm thêm), catalog `/internal/sessions/{id}` và `/pricing` [Source: architecture.md#ADR-2009, #5-data-model]

**Out of scope:** forecast-service, API dự báo cho organizer (Phase 3).

**Stories (ordered):**

| ID | Slug | Intent | Status |
|------|------|--------|--------|
| 4.1 | session-sales-daily-migration | Bảng `session_sales_daily` + repository | ready-for-dev |
| 4.2 | funnel-counters | Đếm `holds_created` (order.created), `orders_expired`, ghi `sold_out_at` trong read model | ready-for-dev |
| 4.3 | daily-snapshot-job | Job chụp hằng ngày, tra sức chứa/giá từ catalog, khoá advisory | ready-for-dev |

**Cross-epic dependencies:**
- Blocked by: none (service độc lập)
- Blocks: none trong Phase 1

---

## Epic 5: Dữ liệu cá nhân và an toàn prompt

**Goal:** Hội thoại và đề xuất không sống quá 180 ngày; mô tả sự kiện do organizer viết vào prompt dưới nhãn "dữ liệu không tin cậy", cắt ngắn.

**In scope (cited):**
- NFR-009 — hạn lưu 180 ngày, che PII [Source: architecture.md#ADR-2011]
- NFR-008 — nhãn `untrusted`, cắt ≤ 1.500 ký tự [Source: architecture.md#ADR-2011]

**Architecture touchpoints:** `AbandonedHandoffReaper` (mở rộng), `ToolDispatcher.describe(EventDetail)`, `SupportAgentPrompts.systemPrompt` [Source: architecture.md#ADR-2011]

**Stories (ordered):**

| ID | Slug | Intent | Status |
|------|------|--------|--------|
| 5.1 | chat-retention-job | Xoá `chat_messages`, `chat_session_context`, `booking_proposals` > 180 ngày | ready-for-dev |
| 5.2 | untrusted-event-description | Bọc `summary`/`description` với `untrusted: true`, cắt 1.500 ký tự; một dòng trong system prompt | ready-for-dev |

**Cross-epic dependencies:**
- Blocked by: 1.1, 2.1 (bảng để xoá) cho 5.1; 1.2 cho 5.2 (cùng `ToolDispatcher`)
- Blocks: none

---

## Delivery Tracking (count-based)

- Total stories: 20
- Done: 0
- Remaining: 20
- Completion rate: 0/20

## Notes

- **Thứ tự khuyến nghị:** 1.1 → 1.2 → (1.3 ∥ 1.4 ∥ 1.6) → 1.5; Epic 4 chạy song song hoàn toàn với Epic 1 (service khác). Epic 2 sau 1.2/1.4. Epic 3 sau 1.2 và 2.1. Epic 5 cuối.
- **Tệp tranh chấp:** `ToolDispatcher.java` (1.2, 2.2, 5.2), `BookingProposalUseCase.java` (1.2, 1.4, 2.4), `CustomerSupportAgentUseCase.java` (1.3, 2.2, 2.3, 3.3), `SupportAgentPrompts.java` (2.3, 5.2), `application.yml` (3.3, 5.1). Những story cùng tệp đã được xếp tuần tự qua Blocked-by; không chạy song song chúng.
- **Rủi ro:** 4.3 giả định catalog `/internal/sessions/{id}` trả sức chứa theo khu — story phải kiểm và, nếu thiếu, mở rộng endpoint đó trong cùng story (scope đã khai).
- Bản dịch vụ được dùng: `backend/services/ai-chatbox-service` (bản `services/ai-chatbox-service` ở gốc là bản cũ — không sửa).
