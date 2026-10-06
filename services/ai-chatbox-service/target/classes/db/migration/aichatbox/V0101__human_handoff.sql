-- Chuyển cuộc chat từ trợ lý AI sang người thật.
--
-- ---------------------------------------------------------------------------
-- Vì sao người trực cũng ghi vào chat_messages
-- ---------------------------------------------------------------------------
-- Cách khác là một bảng riêng cho tin nhắn của nhân viên. Nó hỏng ở đúng chỗ quan trọng nhất:
-- cuộc hội thoại là MỘT dòng thời gian, và khách nhìn thấy nó như một dòng. Hai bảng nghĩa là mọi
-- đường đọc phải trộn và sắp xếp lại hai nguồn, và cái sắp sai sẽ là cuộc hội thoại mà người trực
-- đọc lúc đang tiếp khách.
--
-- Nên chỉ nới ràng buộc vai trò thêm một giá trị. AGENT khác ASSISTANT một cách có chủ đích:
-- khách phải biết mình đang nói với máy hay với người, và một dòng chat không phân biệt được hai
-- thứ đó là một dòng chat nói dối.
ALTER TABLE chat_messages DROP CONSTRAINT chat_messages_role_check;

ALTER TABLE chat_messages
    ADD CONSTRAINT chat_messages_role_check CHECK (role IN ('USER', 'ASSISTANT', 'AGENT'));

-- ---------------------------------------------------------------------------
-- Phiếu chuyển tiếp
-- ---------------------------------------------------------------------------
-- Một PHIẾU, không phải một cột trạng thái trên chat_sessions. Lý do: cùng một phiên chat có thể
-- được chuyển sang người thật nhiều lần, cách nhau vài ngày, vì những việc khác nhau. Một cột
-- trạng thái chỉ nhớ được lần gần nhất, và lịch sử "đã từng phải nhờ người 4 lần" là đúng thứ cần
-- để biết trợ lý đang hụt ở đâu.
CREATE TABLE chat_handoffs (
    id                 UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    session_id         UUID        NOT NULL REFERENCES chat_sessions (id) ON DELETE CASCADE,

    -- Chép từ chat_sessions chứ không join mỗi lần: hàng đợi của người trực là đường đọc nóng
    -- nhất của bảng này, và nó luôn cần "ai đang chờ".
    user_id            UUID        NOT NULL,

    -- WAITING: đang xếp hàng, chưa ai nhận.
    -- ASSIGNED: một người trực đã nhận và đang trả lời.
    -- RESOLVED: đã xong. KHÔNG xoá — xem ghi chú ở trên về lịch sử.
    status             TEXT        NOT NULL DEFAULT 'WAITING',

    -- Vì sao phải chuyển. Hai nguồn: khách tự yêu cầu, hoặc trợ lý tự nhận là không trả lời được.
    -- Lưu cả hai vào một cột vì người trực đọc nó như một câu, không như một mã.
    reason             TEXT        NOT NULL,
    -- CUSTOMER_REQUEST | LOW_CONFIDENCE — dùng để đo, không để hiển thị.
    trigger_kind       TEXT        NOT NULL,

    -- Câu hỏi đang treo, chụp lại lúc chuyển. Người trực cần biết ngay khách đang hỏi gì mà không
    -- phải mở cả hội thoại — với một hàng đợi 40 phiếu thì đó là 40 lần mở.
    last_question      TEXT,

    assigned_agent_id  UUID,
    requested_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_at        TIMESTAMPTZ,
    resolved_at        TIMESTAMPTZ,

    CONSTRAINT ck_handoff_status  CHECK (status IN ('WAITING', 'ASSIGNED', 'RESOLVED')),
    CONSTRAINT ck_handoff_trigger CHECK (trigger_kind IN ('CUSTOMER_REQUEST', 'LOW_CONFIDENCE')),

    -- Trạng thái và mốc thời gian phải đi cùng nhau. Thiếu cặp ràng buộc này thì một phiếu
    -- ASSIGNED không có assigned_at vẫn lưu được, và báo cáo "chờ bao lâu mới có người nhận" sẽ
    -- lặng lẽ bỏ qua đúng những phiếu chờ lâu nhất.
    --
    -- Ba mệnh đề, KHÔNG phải một phép tương đương với 'WAITING'. Viết
    --   (status = 'WAITING') = (assigned_agent_id IS NULL AND assigned_at IS NULL)
    -- sẽ chặn mất một đường hợp lệ: đóng thẳng một phiếu chưa ai nhận. Người trực cần làm được
    -- điều đó với phiếu rác hoặc phiếu khách đã tự giải quyết xong — bắt họ nhận phiếu trước rồi
    -- mới đóng được là thêm một bước chỉ để thoả một ràng buộc.
    CONSTRAINT ck_handoff_assigned CHECK (
        -- Nhận phiếu là một sự kiện: có người thì có mốc thời gian, và ngược lại.
        (assigned_agent_id IS NULL) = (assigned_at IS NULL)
        -- ASSIGNED mà không có ai phụ trách là một phiếu không ai trả lời.
        AND (status <> 'ASSIGNED' OR assigned_agent_id IS NOT NULL)
        -- WAITING mà đã có người phụ trách nghĩa là nó đáng lẽ phải ở ASSIGNED.
        AND (status <> 'WAITING' OR assigned_agent_id IS NULL)),
    CONSTRAINT ck_handoff_resolved CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

-- Hàng đợi của người trực: phiếu chưa xong, cũ nhất trước.
--
-- Index BỘ PHẬN, không phải index đầy đủ trên (status, requested_at): phiếu RESOLVED tích lại mãi
-- mãi còn phiếu đang mở thì luôn là vài chục. Index đầy đủ sẽ lớn dần theo tổng số cuộc chat từng
-- xảy ra, để phục vụ một câu truy vấn không bao giờ hỏi tới chúng.
CREATE INDEX idx_handoff_queue ON chat_handoffs (requested_at)
    WHERE status IN ('WAITING', 'ASSIGNED');

-- Một phiên chỉ được có MỘT phiếu đang mở.
--
-- Không có ràng buộc này thì khách bấm "gặp nhân viên" ba lần sẽ tạo ba phiếu, ba người trực nhận
-- ba phiếu, và cả ba cùng trả lời vào một cuộc hội thoại.
CREATE UNIQUE INDEX uq_handoff_open_per_session ON chat_handoffs (session_id)
    WHERE status IN ('WAITING', 'ASSIGNED');
