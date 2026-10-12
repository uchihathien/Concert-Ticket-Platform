-- Vào / ra / vào lại tại cửa.
--
-- ---------------------------------------------------------------------------
-- Vì sao KHÔNG thêm giá trị mới vào tickets.status
-- ---------------------------------------------------------------------------
-- `status` là hợp đồng mà màn tra cứu của ban tổ chức, app khách và web-scanner đều đang đọc, và ở
-- cả ba nơi CHECKED_IN có nghĩa "vé này đã được dùng". Nghĩa đó vẫn đúng với một vé đã vào rồi ra
-- ngoài hút thuốc — nên trong/ngoài là một trục KHÁC, nằm ở cột riêng:
--
--   chưa dùng       status = VALID,      inside = false, entry_count = 0
--   đang ở trong    status = CHECKED_IN, inside = true
--   đã ra ngoài     status = CHECKED_IN, inside = false, entry_count >= 1
--
-- checked_in_at / checked_in_by giữ nguyên nghĩa cũ: lần vào ĐẦU TIÊN. Lần vào gần nhất nằm ở
-- last_entry_at — đó là thứ nhân viên cần đọc cho khách khi báo "vé đang ở trong khu vực".
ALTER TABLE tickets
    ADD COLUMN inside          BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN entry_count     INT         NOT NULL DEFAULT 0,
    ADD COLUMN last_entry_at   TIMESTAMPTZ,
    ADD COLUMN last_entry_gate TEXT,
    ADD COLUMN last_exit_at    TIMESTAMPTZ;

-- Vé đã soát trước migration này: chưa từng có khái niệm "ra", nên chúng đang ở trong.
UPDATE tickets
   SET inside = TRUE, entry_count = 1, last_entry_at = checked_in_at
 WHERE status = 'CHECKED_IN';

ALTER TABLE tickets
    ADD CONSTRAINT ck_inside_needs_checkin CHECK (NOT inside OR status = 'CHECKED_IN'),
    ADD CONSTRAINT ck_entry_count          CHECK (entry_count >= 0 AND (entry_count > 0) = (status = 'CHECKED_IN'));

-- Đếm "đang ở trong" cho header máy soát: chỉ những dòng inside mới nằm trong index.
CREATE INDEX idx_ticket_session_inside ON tickets (event_session_id) WHERE inside;

-- ---------------------------------------------------------------------------
-- Nhật ký: thêm hướng, cổng, cách nhận diện vé, và tổ chức của người quét.
-- ---------------------------------------------------------------------------
-- organization_id là tổ chức của NHÂN VIÊN lúc quét. Cần nó vì một lần quét mã giả không gắn với
-- vé nào (ticket_id NULL), mà lịch sử quét thì phải lọc theo tổ chức — không có cột này thì hoặc
-- bỏ sót các lần quét mã giả, hoặc để tổ chức này đọc được nhật ký của tổ chức khác.
ALTER TABLE checkin_log
    ADD COLUMN direction       TEXT NOT NULL DEFAULT 'IN',
    ADD COLUMN gate            TEXT,
    ADD COLUMN entry_method    TEXT NOT NULL DEFAULT 'QR',
    ADD COLUMN organization_id UUID;

ALTER TABLE checkin_log
    DROP CONSTRAINT ck_checkin_result,
    ADD CONSTRAINT ck_checkin_result CHECK (result IN (
        'ACCEPTED', 'ALREADY_CHECKED_IN', 'REVOKED', 'WRONG_SESSION', 'INVALID_TOKEN', 'NOT_FOUND',
        'EXITED', 'NOT_INSIDE', 'NOT_OPEN_YET', 'SESSION_ENDED', 'REENTRY_NOT_ALLOWED')),
    ADD CONSTRAINT ck_checkin_direction CHECK (direction IN ('IN', 'OUT')),
    -- QR: quét mã. CODE: nhân viên gõ mã vé vì QR hỏng. OVERRIDE: cho vào thủ công dù máy báo
    -- đang ở trong — lần duy nhất con người thắng máy, nên phải tách riêng để tra lại được.
    ADD CONSTRAINT ck_checkin_method CHECK (entry_method IN ('QR', 'CODE', 'OVERRIDE'));
