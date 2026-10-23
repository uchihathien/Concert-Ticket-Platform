# Rà soát ai-chatbox theo tiêu chí production — 23/10/2026

Phạm vi: `ai-chatbox-service` trên nhánh `fix/ai-chatbox-epic6` (đã có 12 bản sửa Epic 6 và bản tối ưu
giữ mô hình trong GPU). Số đo lấy từ lượt thử đầu-cuối cùng ngày trên máy phát triển (RTX 3050 Ti 4 GB,
`qwen2.5:7b-instruct`, `bge-m3`) và từ mã nguồn. Mục đích: xếp ưu tiên trước khi vào Phase 1, không
phải thay kế hoạch Phase 1 (23 story đã duyệt).

## Tóm tắt ưu tiên

| Ưu tiên | Việc | Vì sao | Cỡ | Ở đâu |
|---|---|---|---|---|
| **P0** | Metric token/chi phí, độ trễ cả lượt, hàng đợi hỗ trợ; dashboard + 3 cảnh báo | Hiện không đo được chi phí và SLA hỗ trợ; không có dashboard nào trong `deploy/` | S | story mới (Epic 7) |
| **P0** | HealthIndicator cho mô hình (Ollama/Anthropic) trong readiness | Service "khoẻ" dù không gọi được mô hình → mọi lượt 503 | S | story mới |
| **P0** | Eval harness + bộ ca (3.1, 3.2) | Chưa có cách nào biết đổi prompt/model làm tệ đi | M | Phase 1 Epic 3 |
| **P0** | Kiểu `CustomerHandoff` trong ts-sdk (hậu quả 6.4) | Frontend đọc `status` nên chưa vỡ, nhưng kiểu TS đang sai | S | repo frontend |
| **P1** | Streaming trạng thái qua SSE ("đang tra sự kiện…"), sau đó stream token câu cuối | Khách chờ 15–25 s nhìn một dòng "đang soạn"; cảm nhận chậm hơn thực tế | M | sau 1.3/1.9 |
| **P1** | Phase 1 Epic 1 (đặt vé trong chat, thẻ xác nhận, VietQR, chọn ghế) | Đây là phần "chức năng giống production" | L | Phase 1 |
| **P1** | Retention 180 ngày (5.1), mô tả sự kiện không tin cậy (5.2) | Dữ liệu cá nhân và prompt injection từ dữ liệu ban tổ chức | S–M | Phase 1 Epic 5 |
| **P2** | Khoá/giới hạn dùng Redis để chạy nhiều replica | `TurnAdmission`, `SessionTurnLock`, idempotency đang trong bộ nhớ — đúng với 1 replica (decision-log) | M | sau khi cần scale |
| **P2** | Định tuyến mô hình theo ý định (3.3), ngân sách token/ngày | Giảm chi phí khi dùng API trả phí | M | Phase 1 Epic 3 |

## 1. Độ trễ

**Đo được.** Mô hình local: 15–25 s một câu (RAG 24 s, tool `findEvents` 15 s, tra đơn 23 s). Trước
bản tối ưu hôm nay: 80–150 s, vì bge-m3 và qwen đẩy nhau khỏi GPU ở mỗi lượt (nạp lại 4,7 GB mất
10–60 s). Khi mô hình nằm yên: sinh 7,5 token/s, prompt 92 token xử lý 0,6 s (Ollama cache prefix
prompt hệ thống). Bước không qua mô hình (phiếu đang mở, phát lại idempotency) trả < 1 s.

**Cấu trúc một lượt:** nhúng câu hỏi (CPU, ~0,2 s) → tìm 5 đoạn RAG → vòng ReAct tối đa 4 lần gọi
mô hình; mỗi tool = thêm một lần gọi (local +10–20 s, Anthropic +2–4 s). Trần sinh 320 token.

**Đòn bẩy còn lại**
- Production dùng `AI_PROVIDER=anthropic` (vài giây/lượt; đã có `cache_control` cho prompt hệ thống,
  embedding vẫn local). Mô hình local chỉ cho dev/demo.
- Nếu buộc chạy local trên GPU 4 GB: `qwen2.5:3b-instruct` vừa GPU hoàn toàn, nhanh gấp 3–5 lần,
  kém hơn ở tool calling tiếng Việt — cần eval (Epic 3) trước khi đổi.
- `history-turns: 10` + 5 đoạn RAG + 9 schema tool ≈ 2–4k token prompt: giảm RAG xuống 3 đoạn khi
  câu hỏi đã có tool rõ ràng (3.3) tiết kiệm cả thời gian lẫn tiền.
- Ngân sách thời gian đã khớp (6.9): gateway không đặt timeout riêng cho route chat; service
  `spring.mvc.async.request-timeout = 720s`; ts-sdk `CHAT_TURN_TIMEOUT_MS` theo đó.

## 2. Streaming

**Hiện trạng:** không có. `POST /v1/chat/agent/support` trả trọn câu sau khi vòng ReAct kết thúc.
Frontend hiện "Trợ lý đang soạn câu trả lời" kèm số giây đã chờ.

**Vì sao chưa stream token ngay:** hai chốt chặn (`AnswerGrounding`, và `BookingClaimGuard` của 1.9)
đọc **cả câu** rồi mới quyết có thay bằng câu dự phòng hay không; 1.3 còn gắn thẻ hành động vào phản
hồi. Stream token rồi "rút lại" là trải nghiệm tệ hơn chờ.

**Đề xuất hai bước (P1, sau 1.3 và 1.9):**
1. SSE sự kiện trạng thái: `accepted` → `tool{name}` ("đang tra sự kiện Đêm nhạc Trịnh…") →
   `answer{...}` (đúng payload hiện tại). Không đổi vòng ReAct, không đụng chốt chặn; khách thấy
   tiến trình trong 1–2 s. Giữ endpoint cũ cho ts-sdk/mobile tới khi chuyển xong.
2. Stream token cho **vòng cuối** (vòng không gọi tool), chốt chặn chạy trên bản đầy đủ và gửi một
   sự kiện `replace` hiếm khi cần. Ollama và Anthropic SDK đều hỗ trợ stream.

## 3. Giới hạn tần suất và chịu tải

**Đã có, ba lớp:** gateway `RequestRateLimiter` 1 req/s, burst 5, theo user (Redis); service
`TurnAdmission` tối đa 2 lượt đồng thời/người; `SessionTurnLock` một lượt/phiên (6.10); pool agent 16
luồng + hàng đợi 32, quá thì 503 ngay (6.9); pool Hikari 10, nhúng và gọi mô hình đều ngoài transaction.

**Thiếu:**
- Trần theo ngày/tháng cho lời gọi mô hình trả phí (chống một tài khoản "nói chuyện" 24/7). Khi có
  metric token (P0) thì thêm ngưỡng theo user + toàn hệ thống.
- Nhiều replica: ba cơ cấu trên đều trong bộ nhớ. Giữ 1 replica như decision-log đã chốt; chuyển
  sang Redis khi cần scale (P2). Idempotency của chat cũng in-memory (6.9).

## 4. Giám sát

**Đã có:** Micrometer → `/actuator/prometheus`; tracing OTLP (sampling cấu hình); log có
`correlationId`; metric `aichatbox.llm.latency`, `aichatbox.llm.calls{outcome}`, `aichatbox.tool.calls`,
`aichatbox.turn.rounds`, `aichatbox.handoff.opened`.

**Thiếu (P0, cỡ nhỏ):**
- Độ trễ **cả lượt** (gồm chờ hàng đợi) và số 503/409 (`TURN_IN_PROGRESS`, hết suất) — thứ khách cảm nhận.
- Token vào/ra và cache hit theo provider → chi phí.
- Bàn hỗ trợ: số phiếu đang chờ, tuổi phiếu cũ nhất, thời gian tới lần nhận đầu — SLA của người.
- Số lần chốt chặn thay câu trả lời (`AnswerGrounding`, 1.9): tăng = mô hình đang bịa nhiều hơn.
- HealthIndicator cho mô hình: hiện `/actuator/health` xanh dù Ollama chết.
- Chưa có dashboard Grafana hay rule cảnh báo nào cho ai-chatbox trong `deploy/`. Đề xuất 3 cảnh
  báo: tỷ lệ `llm.calls{outcome=failed}` > 5 % trong 5 phút; phiếu chờ > 15 phút; p95 độ trễ lượt > 30 s.

## 5. Eval

**Hiện trạng:** unit/IT với mô hình giả (491 unit + 98 IT); e2e thủ công 8 kịch bản với mô hình thật
(script trong scratchpad, kết quả 8/8). Không có bộ ca cố định để chạy lại sau mỗi lần đổi prompt,
model, hay `retrieval-top-k`.

**Đề xuất:** đúng Epic 3 của Phase 1 — 3.1 harness (chạy N ca qua `CustomerSupportAgentUseCase` với
provider thật, chấm bằng quy tắc: tool đúng, có/không số liệu bịa, có `<cau_hoi>`…), 3.2 bộ ca gồm
8 kịch bản hôm nay + ca "giục đặt vé" + ca tấn công prompt. Chạy trong CI ở profile riêng (không
chạy mặc định vì cần mô hình). Thứ tự: sau 1.2/1.3 (cần tool `proposeBooking` và `AgentReply.action`).

## 6. Chi phí

**Hiện trạng:** Anthropic trả `usage` nhưng chỉ ghi log khi chạm `max_tokens`; Ollama trả
`prompt_eval_count`/`eval_count` nhưng không ai đọc. Không có ước tính chi phí/lượt.

**Đề xuất:** counter `aichatbox.llm.tokens{provider,direction=input|output|cache_read}` (P0) →
dashboard chi phí/ngày theo giá công bố của nhà cung cấp (không ghi giá vào mã). Sau đó mới quyết
định tuyến (3.3): câu hỏi chính sách thuần RAG có thể đi mô hình rẻ; câu đặt vé đi mô hình tốt.
Prompt caching đã bật cho prompt hệ thống; mỗi lần sửa prompt mất cache một lần (chấp nhận, 5.2 ghi).

## Việc nhỏ ngoài sáu tiêu chí, nên làm sớm

- `frontend/packages/ts-sdk`: kiểu `CustomerHandoff { status, requestedAt, assignedAgentName }` cho
  `requestHumanAgent`/`ChatThread.handoff` (hậu quả 6.4; repo frontend tách riêng).
- `scripts/dev-up.sh` mặc định issuer `172.16.0.149` (LAN cho mobile) còn `.env.local` của web là
  `localhost` — hai cấu hình dễ lệch nhau; nên một nguồn.
- Máy 16 GB không chạy nổi 13 service + Ollama + 2 app Next: dev tiếp tục dùng 8 service
  (`scripts/demo-chatbox-up.sh`).

## Thứ tự thực hiện (chốt)

1. Phase 1 theo thứ tự đã lên: 1.1 → 1.2 → (1.3 ∥ 1.4 ∥ 1.6) → 1.5 → 1.7; 1.9 sau 1.3; 1.10 → 1.11 → 1.12;
   Epic 2 sau 1.11; Epic 3 sau 1.2 và 2.1; Epic 4 song song; Epic 5 cuối.
2. Các mục P0 cỡ nhỏ (metric, health, cảnh báo) gom thành Epic 7 "Vận hành" — viết story khi tới
   lượt, không chen vào tệp đang bị Phase 1 sửa (`AgentMetrics.java`, `application.yml` thuộc nhiều story).
3. Streaming (P1) sau 1.3 và 1.9.
