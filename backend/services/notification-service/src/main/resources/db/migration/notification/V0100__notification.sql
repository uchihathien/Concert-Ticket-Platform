-- notification-service: gửi email cho khách và cho tổ chức.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.

-- ---------------------------------------------------------------------------
-- Mỗi thư đã gửi hoặc đang chờ gửi.
--
-- event_id UNIQUE là chốt chặn duy nhất chống gửi trùng. RabbitMQ bảo đảm giao ÍT NHẤT MỘT
-- LẦN, nên message trùng không phải trường hợp hiếm mà là chuyện thường xuyên: broker khởi
-- động lại, consumer chết trước khi ack, hoặc chính publisher retry. Không có ràng buộc này
-- thì khách nhận ba email "đơn hàng đã thanh toán" cho một lần mua.
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    id            UUID PRIMARY KEY,

    event_id      UUID        NOT NULL UNIQUE,
    event_type    TEXT        NOT NULL,

    channel       TEXT        NOT NULL DEFAULT 'EMAIL',
    recipient     TEXT        NOT NULL,
    template      TEXT        NOT NULL,

    -- Dữ liệu để dựng nội dung thư. KHÔNG chứa số tài khoản, không chứa mã QR vé.
    payload       JSONB       NOT NULL,

    status        TEXT        NOT NULL DEFAULT 'PENDING',
    attempts      INT         NOT NULL DEFAULT 0,
    last_error    TEXT,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_retry_at TIMESTAMPTZ,
    sent_at       TIMESTAMPTZ,

    CONSTRAINT ck_notification_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT ck_notification_status  CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DEAD')),
    CONSTRAINT ck_sent_pair            CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

-- Worker gửi lại quét đúng index này.
CREATE INDEX idx_notification_retry ON notifications (next_retry_at)
    WHERE status = 'PENDING';

-- Hàng đợi chết: thư đã thử hết số lần cho phép. Phải có người nhìn, không được im lặng bỏ qua.
CREATE INDEX idx_notification_dead ON notifications (created_at) WHERE status = 'DEAD';
