| Date | Action | Artifact |
|---|---|---|
| 2026-10-22 | Create product brief (draft, from code analysis) | product-brief-ai-chatbox-2026-10-22.md |
| 2026-10-22 | Create architecture.md (draft) — ADR-2001 AI đề xuất / người xác nhận / mã thực thi, thay ADR-0012 | architecture.md |
| 2026-10-22 | ADR-2002 slot memory theo phiên; ADR-2006 idempotency theo proposalId / intentId:attempt | architecture.md |
| 2026-10-22 | ADR-2003 worker săn vé qua on-behalf-of + grant RS256; ADR-2004 trigger từ AMQP có sẵn; ADR-2005 FIFO + grace 60s | architecture.md |
| 2026-10-22 | ADR-2007 SSE + realtime-gateway; ADR-2008 định tuyến mô hình + eval; ADR-2009 session_sales_daily; ADR-2010 quy ước; ADR-2011 PII/retention | architecture.md |
| 2026-10-22 | Chấp nhận ADR-2001…2011 (architecture.md → Approved); ADR-0012 đánh dấu Superseded by ADR-2001 trong docs/05-ai-scale/adr | architecture.md, docs/05-ai-scale/adr/ADR-0012-ai-async-only.md |
| 2026-10-22 | Chia Phase 1 thành 5 epic / 18 story (Booking Proposal 6, Slot memory 4, Eval & định tuyến 3, Snapshot doanh số 3, PII & prompt 2); người dùng chốt bản đồ | epics.md |
| 2026-10-22 | Story cùng tệp tranh chấp (ToolDispatcher, CustomerSupportAgentUseCase, SupportAgentPrompts, BookingProposalUseCase, application.yml) xếp tuần tự bằng Blocked-by thay vì chia lại | epics.md#notes |
| 2026-10-22 | ADR-2009 sửa khi chia story: job snapshot KHÔNG gọi catalog `/internal/sessions/{id}/pricing` (trả hoa hồng — ADR-1010 cấm analytics thấy); thay bằng mở rộng `/internal/sessions/{id}` với tiers/capacity/salesOpenAt (story 4.3); capacity nạp từ `session.published` (4.2); Phase 1 snapshot theo suất (`zone_code='*'`), theo khu là revisit | architecture.md#ADR-2009, stories/4.2, 4.3 |
| 2026-10-22 | Story 5.1 chọn: phiếu WAITING/ASSIGNED chặn xoá phiên; phiếu RESOLVED > 180 ngày đi theo phiên (chữ của khách thuộc hạn lưu NFR-009) — khác chú thích V0101 "RESOLVED không xoá" | stories/5.1 |
| 2026-10-22 | 18 story Phase 1 biên soạn xong; thêm 6 cạnh Blocked-by (2.2←1.3,1.4; 3.3←2.3,1.6; 5.1←3.3; 5.2←2.4) để mọi cặp story trùng tệp đều có thứ tự; chuyển 18/18 sang ready-for-dev | stories/*.story.md, epics.md |
| 2026-10-22 | Story 1.1 bỏ FK `booking_proposals.chat_session_id → chat_sessions` (tool chạy trước khi phiên được tạo ở appendTurn); thêm cột order_number/checkout_url/payment_expires_at. Story 1.2 đưa `TurnContext(sessionId,userId)` vào `ToolDispatcher.dispatch`. Story 1.4 seed idempotency = proposalId (khoá thực tế `chat-hold-{id}`/`chat-order-{id}`) | stories/1.1, 1.2, 1.4 |

### 2026-10-22 — Course Correction: xác nhận đặt vé bằng câu trả lời, thẻ thanh toán VietQR trong chat
- **Decision:** ADR-2001 điểm 3–6 sửa: xác nhận đề xuất có hai đường không qua mô hình — nút, hoặc câu trả lời ngắn do `BookingConfirmIntent` (mã, không LLM) nhận khi phiên có đúng một đề xuất mở. Confirm trả thẻ `PAYMENT` kèm `vietQrPayload` để khách quét ngay trong chat; thanh toán là xác nhận cuối, đơn chưa trả hết hạn 15 phút. Không gộp đề xuất + xác nhận vào một lượt.
- **Rationale:** Người dùng muốn "AI đặt vé, khách chỉ cần quét mã". Giữ nguyên tắc mô hình không tạo được đơn bằng cách để mã nhận chữ "ok" thay vì mô hình; chữ "ok" là thứ phân biệt "đặt 2 vé" với "còn 2 vé không" — bỏ nó là khoá chỗ của người khác 15 phút lúc mở bán. Thay thế đã cân nhắc: gộp một lượt khi câu đầu đủ thông tin (từ chối, chỉ sau ADR mới + cờ theo sự kiện).
- **Impact:** Epic 1 reshaped — story 1.3, 1.4, 1.5 re-scoped (mục LOCKED thay đổi, người dùng uỷ quyền "ok" 2026-10-22); thêm 1.7 `payment-card-in-chat`, 1.8 `confirm-by-reply`; 2.2 và 2.4 thêm Blocked-by 1.8; `ChatThread.action` mới. Tổng 20 story. Không có sprint-status.yaml nên không re-sequence.
- **In-progress stories affected:** none (tất cả ready-for-dev/backlog, chưa có dev)
- **Made by:** bmad-correct-course
- **Supersedes:** mục "Chia Phase 1 thành 5 epic / 18 story" cùng ngày
- **Follow-up (cùng ngày):** 1.3/1.4/1.5/2.4 sửa, 1.7/1.8 viết mới; kiểm lại 20/20 story đủ mục, mọi cặp trùng tệp có thứ tự Blocked-by, không vòng → cả 20 `ready-for-dev`. Quyết định trong story: thẻ PAYMENT dựng lại khi tải trang KHÔNG mang `vietQrPayload` (frontend lấy từ `GET /v1/orders/{id}` vốn đang poll) để chuỗi QR không lọt vào `OrderSummary`/prompt; 422 khi xác nhận → đề xuất `CANCELLED`; `BookingConfirmIntent` so khớp toàn câu ≤ 6 từ, không `contains`.

### 2026-10-22 — Course Correction (lần 2): chỉ nút Đồng ý, bỏ xác nhận bằng câu "ok"
- **Decision:** Người dùng chọn xác nhận đặt vé bằng một nút "Đồng ý" trên thẻ đề xuất. Bỏ đường gõ "ok" (`BookingConfirmIntent`, bước 1d). Câu chữ của khách không còn xác nhận đơn.
- **Rationale:** Một nút rõ ràng hơn, không phải đoán ý từ câu chữ, và bỏ hẳn rủi ro bắt nhầm câu thành đơn thật. Luồng vẫn là: AI đề xuất → bấm Đồng ý → quét VietQR.
- **Impact:** ADR-2001 điểm 3 viết lại; bỏ mục API "xác nhận bằng câu trả lời". Story 1.8 `cancelled` (giữ tệp). 1.3, 1.4 (`confirm(userId, proposalId)` bỏ tham số câu khách), 1.5, 1.7, 2.2, 2.4 gỡ tham chiếu 1.8. Còn 19 story.
- **In-progress stories affected:** none
- **Made by:** bmad-correct-course
- **Supersedes:** Course Correction "xác nhận đặt vé bằng câu trả lời" cùng ngày (phần thẻ PAYMENT VietQR giữ nguyên)

### 2026-10-22 — Course Correction: chốt chặn "đã đặt vé" (story 1.9)
- **Decision:** Thêm story 1.9 `booking-claim-guard`: `BookingClaimGuard` (mã, không LLM) chặn câu trả lời khẳng định đã đặt/giữ chỗ hoặc đưa số đơn/UUID không có nguồn trong payload tool của lượt hoặc lượt ASSISTANT đã lưu; thay bằng câu "mình chưa tạo đơn nào", chạy trước khi lưu lịch sử.
- **Rationale:** Thử đầu-cuối với qwen2.5 7B: khách giục "đặt luôn", trợ lý trả "Đã đặt 2 vé, số đơn 3fa85f64-…, quét VietQR" với toolsUsed rỗng; không có đơn nào. AnswerGrounding để lọt vì RAG có đoạn tri thức. ADR-2001 chặn mô hình *tạo* đơn nhưng không chặn mô hình *nói* là đã tạo.
- **Impact:** +1 story (1.9, Epic 1), 2.2 thêm Blocked-by 1.9, 3.2 thêm ca eval "giục đặt" (mục LOCKED, người dùng uỷ quyền), ADR-2001 thêm một Consequence, ma trận thêm NFR-001b. Tổng 20 story active.
- **In-progress stories affected:** none
- **Made by:** bmad-correct-course

### 2026-10-22 — Course Correction: khách tự chọn số ghế trong chat
- **Decision:** Thẻ đề xuất có nút "Chọn ghế" mở lưới hàng × số ghế (dữ liệu `GET /v1/sessions/{id}/seats`, không cần toạ độ); khách chọn đúng số vé, xác nhận giữ đúng những ghế đó. Không chọn thì hệ thống chọn hộ. Mô hình không nhận/điền mã ghế. Trang đặt vé web giữ nguyên chọn theo khu (người dùng chọn "chỉ chat").
- **Rationale:** Người dùng yêu cầu khách được chọn số ghế. Web đã bỏ chọn từng ghế vì 52/63 suất không có toạ độ đúng; lưới theo nhãn hàng/số tránh được vấn đề đó. Phương án đã cân nhắc: sơ đồ khi có toạ độ (hai đường hiển thị), khách gõ mã ghế (khách phải biết mã) — người dùng chọn lưới.
- **Impact:** ADR-2001 thêm điểm 7; ma trận thêm FR-002b; +3 story 1.10, 1.11, 1.12; 2.2 thêm Blocked-by 1.11 (2.4 kế thừa qua 2.2); migration Phase 2 `booking_intents` dời sang V0107. Tổng 23 story active. Khoá chống trùng khi đổi ghế: `proposalId-s{seat_version}` (1.11).
- **In-progress stories affected:** none
- **Made by:** bmad-correct-course
