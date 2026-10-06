-- Phiếu có ý định, và quy định có chính sách hoàn vé thực thi được.

-- ---------------------------------------------------------------------------
-- Ý định của phiếu
-- ---------------------------------------------------------------------------
-- `trigger_kind` nói VÌ SAO phải chuyển (khách đòi / trợ lý bỏ cuộc); `intent` nói chuyển ĐỂ LÀM GÌ.
-- Hai câu hỏi khác nhau, hai cột. Bàn hỗ trợ chia việc theo cột thứ hai: người quen xử lý hoàn tiền
-- lọc REFUND, người trực sự cố ngày diễn lọc INCIDENT.
--
-- DEFAULT 'GENERAL' chứ không NULL: phiếu cũ đọc lên vẫn có một nhãn, và màn hình lọc "mọi phiếu
-- GENERAL" không bỏ sót chúng. NOT NULL kèm CHECK để một nhãn gõ sai ở tầng trên vỡ ngay lúc ghi.
ALTER TABLE chat_handoffs
    ADD COLUMN intent TEXT NOT NULL DEFAULT 'GENERAL';

ALTER TABLE chat_handoffs
    ADD CONSTRAINT ck_handoff_intent CHECK (intent IN
        ('GENERAL', 'ORDER_STATUS', 'BOOKING', 'REFUND', 'INCIDENT', 'COMPLAINT', 'EVENT_INFO'));

-- Dữ liệu có cấu trúc đi kèm phiếu: mã đơn, loại sự cố, kết quả xét chính sách hoàn vé… Dạng JSON
-- nhưng cột TEXT, không JSONB: không truy vấn vào bên trong nó bao giờ — người trực đọc nó qua màn
-- hình — và JSONB sẽ kéo theo một phép ép kiểu ở mọi lệnh ghi chỉ để đổi lấy một chỉ mục không ai
-- cần. Đổi sang JSONB sau là một ALTER, không mất gì.
ALTER TABLE chat_handoffs
    ADD COLUMN details TEXT;

-- Hàng đợi lọc theo ý định — nhưng chỉ phiếu đang mở, vì cùng lý do với idx_handoff_queue: phiếu
-- RESOLVED tích lại mãi còn phiếu mở thì luôn là vài chục.
CREATE INDEX idx_handoff_queue_intent ON chat_handoffs (intent, requested_at)
    WHERE status IN ('WAITING', 'ASSIGNED');

-- ---------------------------------------------------------------------------
-- Chính sách hoàn vé của sự kiện
-- ---------------------------------------------------------------------------
-- Hai cột CÓ CẤU TRÚC bên cạnh `content` tự do, vì phần này được THỰC THI chứ không chỉ được đọc:
-- tool xin hoàn vé so sánh nó với đơn hàng để từ chối ngay những yêu cầu ngoài chính sách. Để trong
-- văn bản thì mô hình là thứ duy nhất "đọc" chính sách, và mô hình đọc sai không để lại dấu vết.
--
-- Mặc định KHÔNG nhận hoàn vé: sai theo hướng an toàn, cùng triết lý với `published`. Nhưng lưu ý
-- tầng application: sự kiện CHƯA CÓ DÒNG nào trong bảng này thì không bị từ chối — "chưa ai quyết
-- chính sách" khác "đã quyết là không".
ALTER TABLE event_rules
    ADD COLUMN refund_allowed BOOLEAN NOT NULL DEFAULT false;

-- Số giờ kể từ lúc thanh toán còn được xin hoàn. 0 = không giới hạn (chỉ có nghĩa khi refund_allowed).
ALTER TABLE event_rules
    ADD COLUMN refund_window_hours INTEGER NOT NULL DEFAULT 0;

ALTER TABLE event_rules
    ADD CONSTRAINT ck_event_rules_refund_window CHECK (refund_window_hours >= 0);
