-- Lựa chọn đặt vé đang dở của một phiên chat: khách đã chọn tới đâu (sự kiện, suất, khu, số vé) và
-- những chip đang hiện cho bước kế tiếp.
--
-- Vì sao lưu ở server chứ không gửi kèm trong chip: chip gửi lại một CÂU ("Khu VIP"), đúng như khách
-- tự gõ, để hội thoại đọc lại vẫn tự nhiên. Câu đó chỉ có nghĩa khi đối chiếu với đúng danh sách chip
-- vừa hiện — danh sách ấy nằm ở đây, kèm giá trị có cấu trúc (slug, suất, mã khu) mà mỗi chip đặt
-- vào. Không có bảng này thì phải đoán ngược "Khu VIP" ra mã khu từ chữ — đúng việc mô hình 7B làm
-- sai.
--
-- Một dòng cho một phiên. Lập xong đề xuất (booking_proposals) thì dòng bị xoá: từ đó thẻ đề xuất là
-- nguồn sự thật. chat_session_id không có khoá ngoại vì cùng lý do với V0104 — dòng chat_sessions có
-- thể chưa ra đời khi lượt đầu tiên đang chạy.
CREATE TABLE chat_booking_drafts (
    chat_session_id  UUID PRIMARY KEY,
    user_id          UUID        NOT NULL,
    event_slug       TEXT,
    event_session_id UUID,
    zone_code        TEXT,
    quantity         INT,
    -- Câu hỏi của bước đang chờ, vd. "Bạn chọn khu nào?" — để dựng lại thẻ sau khi tải lại trang.
    prompt           TEXT,
    -- [{label, value, eventSlug?, eventSessionId?, zoneCode?, quantity?}] — chip đang hiện.
    choices          JSONB       NOT NULL DEFAULT '[]',
    -- Bước đang chờ khách: EVENT (chưa biết sự kiện nào), SESSION, ZONE, QUANTITY.
    awaiting         TEXT        NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_draft_awaiting CHECK (awaiting IN ('EVENT', 'SESSION', 'ZONE', 'QUANTITY')),
    CONSTRAINT ck_draft_quantity CHECK (quantity IS NULL OR quantity BETWEEN 1 AND 6)
);

CREATE INDEX idx_chat_booking_drafts_user ON chat_booking_drafts (user_id);
