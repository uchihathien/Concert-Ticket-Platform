-- ADR-0007: mọi mutation của client mang Idempotency-Key; lưu fingerprint + response >= 24h.
CREATE TABLE idempotency_records (
    id              UUID PRIMARY KEY,
    user_id         UUID,
    idem_key        TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    UNIQUE (user_id, idem_key)
);

CREATE INDEX idx_idempotency_cleanup ON idempotency_records (created_at);

-- Consumer guard: RabbitMQ bảo đảm at-least-once; bảng này biến nó thành effectively-once.
-- Ghi trong CÙNG transaction với việc xử lý message.
CREATE TABLE processed_events (
    consumer_queue TEXT        NOT NULL,
    event_id       TEXT        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_queue, event_id)
);

CREATE INDEX idx_processed_events_cleanup ON processed_events (processed_at);
