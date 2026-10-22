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
