-- Outbox là NHẬT KÝ SỰ KIỆN, không phải hàng đợi tạm (ADR-1009).
-- Dòng KHÔNG bị xoá sau khi publish; chỉ đánh dấu published_at.
-- Nhờ vậy replay được mà không cần Kafka.
CREATE TABLE outbox (
    id              UUID PRIMARY KEY,
    seq             BIGSERIAL   NOT NULL UNIQUE,
    aggregate_type  TEXT        NOT NULL,
    aggregate_id    UUID        NOT NULL,
    event_type      TEXT        NOT NULL,          -- routing key: <aggregate>.<event>
    event_version   INT         NOT NULL DEFAULT 1,
    exchange        TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    correlation_id  TEXT,
    causation_id    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

-- Đường nóng của publisher: chỉ quét dòng chưa gửi.
CREATE INDEX idx_outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;

-- Tra cứu lịch sử của một aggregate khi điều tra sự cố.
CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id, seq);
