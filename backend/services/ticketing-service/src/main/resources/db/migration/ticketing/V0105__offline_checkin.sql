-- Soát vé khi máy mất mạng, và cho vào bằng tra cứu (khách quên vé).
--
-- ---------------------------------------------------------------------------
-- Một lượt quét offline là một dòng checkin_log như mọi lượt khác
-- ---------------------------------------------------------------------------
-- Máy soát vé tự quyết định tại chỗ (chữ ký QR + dữ liệu vé đã tải về), cho khách vào, rồi gửi lượt
-- quét lên khi có mạng lại. Server chạy lại ĐÚNG logic soát vé theo thứ tự thời điểm quét và ghi
-- kết quả của server. Bốn cột mới giữ phần "offline" của câu chuyện:
--
--   offline         lượt quét lúc mất mạng — báo cáo phải tách được
--   client_scan_id  id máy tự sinh cho từng lượt; gửi lại cùng id thì không ghi lần hai
--   device_result   kết quả máy ĐÃ HIỆN cho khách lúc đó
--   conflict        máy đã cho vào nhưng server nói không (hai máy cùng cho một vé vào, vé bị thu
--                   hồi trong lúc máy mất mạng…). Khách đã ở trong rồi — không sửa được quá khứ,
--                   nhưng ban tổ chức phải thấy được.
ALTER TABLE checkin_log
    ADD COLUMN offline        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN client_scan_id UUID,
    ADD COLUMN device_result  TEXT,
    ADD COLUMN conflict       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN synced_at      TIMESTAMPTZ;

-- Chống gửi trùng: máy gửi lại một lô sau khi mất kết nối giữa chừng là chuyện thường.
CREATE UNIQUE INDEX uq_checkin_client_scan ON checkin_log (client_scan_id) WHERE client_scan_id IS NOT NULL;
CREATE INDEX idx_checkin_conflicts ON checkin_log (event_session_id, scanned_at DESC) WHERE conflict;
-- Tổng kết ca: theo nhân viên, suất, khoảng thời gian.
CREATE INDEX idx_checkin_staff_session ON checkin_log (scanned_by, event_session_id, scanned_at);

-- LOOKUP: nhân viên tìm khách theo tên / email / SĐT rồi cho vào sau khi đối chiếu giấy tờ.
ALTER TABLE checkin_log
    DROP CONSTRAINT ck_checkin_method,
    ADD CONSTRAINT ck_checkin_method CHECK (entry_method IN ('QR', 'CODE', 'OVERRIDE', 'LOOKUP'));

-- Liên lạc của người mua, chụp từ identity-service để tìm khách và để máy soát vé tra offline.
--
-- Một dòng mỗi NGƯỜI, không mỗi vé: một khách mua mười vé là một email. Làm mới khi cũ hơn một giờ
-- (xem TicketHolderContacts). Ra khỏi service này chỉ ở dạng ĐÃ CHE một phần.
CREATE TABLE ticket_holder_contacts (
    user_id      UUID PRIMARY KEY,
    email        TEXT,
    phone        TEXT,
    full_name    TEXT,
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Bỏ dấu tiếng Việt để tìm khách: nhân viên gõ "nguyen van a" trên bàn phím không dấu vẫn phải ra
-- "Nguyễn Văn A". Không dùng extension unaccent: nó không có sẵn trên mọi cụm PostgreSQL được cấp.
CREATE OR REPLACE FUNCTION vn_fold(value TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT translate(lower(value), 'àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ', 'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd')
$$;
