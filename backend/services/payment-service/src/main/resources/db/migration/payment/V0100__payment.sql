-- payment-service: tài khoản ký quỹ, yêu cầu thanh toán, và webhook ngân hàng.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.
--
-- Nguyên tắc bao trùm toàn bộ service này (BRAINSTORM §4.2):
--   REJECTED chỉ dùng khi CHẮC CHẮN không có đồng nào về tài khoản của ta.
--   Có tiền mà không khớp ⇒ LUÔN LUÔN là MANUAL_REVIEW.
-- Vì tiền đã nằm trong tài khoản ký quỹ là tiền của một người thật, và một dòng
-- REJECTED nghĩa là "không ai xử lý nữa" — tức là một giao dịch vô chủ.

-- ---------------------------------------------------------------------------
-- Tài khoản ký quỹ của NỀN TẢNG, không phải của tổ chức.
--
-- Nền tảng là bên giữ tiền (custodial-funds.md): khách chuyển vào đây, không chuyển
-- thẳng cho ban tổ chức. Chỉ SUPER_ADMIN thấy và sửa bảng này (ADR-1010).
-- ---------------------------------------------------------------------------
CREATE TABLE escrow_bank_accounts (
    id             UUID PRIMARY KEY,
    bank_bin       TEXT        NOT NULL,
    bank_name      TEXT        NOT NULL,
    account_number TEXT        NOT NULL,
    account_name   TEXT        NOT NULL,
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    is_preferred   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_bank_bin        CHECK (bank_bin ~ '^[0-9]{6}$'),
    CONSTRAINT ck_account_number  CHECK (account_number ~ '^[0-9]{4,20}$'),
    CONSTRAINT uq_bank_account    UNIQUE (bank_bin, account_number)
);

-- Đúng MỘT tài khoản được ưu tiên tại một thời điểm. Hai tài khoản ưu tiên nghĩa là
-- hai khách cùng lúc nhận hai mã QR trỏ vào hai nơi khác nhau, và đối soát sẽ phải
-- đoán tiền của ai vào đâu.
CREATE UNIQUE INDEX uq_single_preferred_account ON escrow_bank_accounts ((1))
    WHERE is_preferred AND is_active;

-- ---------------------------------------------------------------------------
CREATE TABLE payment_intents (
    id                  UUID PRIMARY KEY,

    -- Idempotent theo đơn: saga checkout có thể gọi lại sau timeout mạng, và lần thứ
    -- hai phải trả đúng mã QR cũ chứ không sinh mã mới.
    order_id            UUID        NOT NULL UNIQUE,
    organization_id     UUID        NOT NULL,

    amount_vnd          BIGINT      NOT NULL,

    -- Nội dung chuyển khoản khách phải ghi. Đây là SỢI DÂY DUY NHẤT nối một giao dịch
    -- ngân hàng với một đơn hàng, nên phải unique và phải sống sót qua ô "nội dung"
    -- của mọi app ngân hàng Việt Nam.
    payment_reference   TEXT        NOT NULL UNIQUE,

    bank_account_id     UUID        NOT NULL REFERENCES escrow_bank_accounts (id),

    -- Snapshot tài khoản nhận tại thời điểm sinh mã QR. Nền tảng đổi tài khoản ký quỹ
    -- sau đó không được làm sai mã QR khách đang cầm, và đối soát phải so với tài khoản
    -- ĐÃ IN TRÊN MÃ, không phải tài khoản hiện hành.
    bank_bin            TEXT        NOT NULL,
    bank_account_number TEXT        NOT NULL,

    vietqr_payload      TEXT        NOT NULL,

    status              TEXT        NOT NULL DEFAULT 'PENDING',
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at        TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,

    CONSTRAINT ck_intent_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_intent_amount CHECK (amount_vnd > 0),
    CONSTRAINT ck_confirmed_has_timestamp CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL))
);

CREATE INDEX idx_intent_expiry       ON payment_intents (expires_at) WHERE status = 'PENDING';
CREATE INDEX idx_intent_organization ON payment_intents (organization_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Chống xử lý trùng webhook — bảng dedupe CHUYÊN TRÁCH (BRAINSTORM §4.3).
--
-- Vì sao không dựa vào unique index trên payment_attempts: dòng attempt được tạo từ
-- lúc mở intent với trạng thái PENDING và chưa có transaction id. Hai webhook trùng
-- đến SONG SONG sẽ cùng UPDATE một dòng PENDING, và unique constraint không kích hoạt
-- lần nào. Bảng này thì có: bước đầu tiên của handler là
-- INSERT ... ON CONFLICT DO NOTHING, và 0 dòng nghĩa là "đã xử lý rồi, trả 2xx và dừng".
--
-- payload_hash để phát hiện SePay gửi lại cùng transaction id với nội dung khác —
-- không tự xử lý được, nhưng phải thấy được khi điều tra.
-- ---------------------------------------------------------------------------
CREATE TABLE webhook_events (
    id                UUID PRIMARY KEY,
    provider          TEXT        NOT NULL,
    provider_event_id TEXT        NOT NULL,
    payload_hash      TEXT        NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    processing_status TEXT        NOT NULL DEFAULT 'RECEIVED',
    outcome           TEXT,
    note              TEXT,

    CONSTRAINT uq_provider_event   UNIQUE (provider, provider_event_id),
    CONSTRAINT ck_processing_status CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_outcome           CHECK (outcome IS NULL
        OR outcome IN ('CONFIRMED', 'DUPLICATE', 'MANUAL_REVIEW', 'REJECTED'))
);

CREATE INDEX idx_webhook_manual_review ON webhook_events (received_at)
    WHERE outcome = 'MANUAL_REVIEW';

-- ---------------------------------------------------------------------------
-- Mỗi lần một giao dịch ngân hàng chạm vào một intent.
--
-- Giữ cả những lần KHÔNG khớp: đó chính là danh sách việc cho người đối soát. Một
-- giao dịch có tiền mà không có dòng nào ở đây là một giao dịch vô chủ — thứ mà
-- runbook đối soát lấy làm tiêu chí thoát.
-- ---------------------------------------------------------------------------
CREATE TABLE payment_attempts (
    id                  UUID PRIMARY KEY,
    intent_id           UUID REFERENCES payment_intents (id),
    webhook_event_id    UUID        NOT NULL REFERENCES webhook_events (id),

    amount_received_vnd BIGINT      NOT NULL,
    received_bank_bin   TEXT,
    received_account    TEXT,
    raw_reference       TEXT,

    outcome             TEXT        NOT NULL,
    note                TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_attempt_outcome CHECK (
        outcome IN ('CONFIRMED', 'DUPLICATE', 'MANUAL_REVIEW', 'REJECTED'))
);

CREATE INDEX idx_attempt_intent ON payment_attempts (intent_id);

-- Danh sách việc của người đối soát.
CREATE INDEX idx_attempt_manual_review ON payment_attempts (created_at)
    WHERE outcome = 'MANUAL_REVIEW';

-- ---------------------------------------------------------------------------
-- KHÔNG seed tài khoản ký quỹ ở đây.
--
-- Đã thử điều kiện kiểu `current_database() LIKE '%payment%'` để chỉ seed ở dev, nhưng
-- database production cũng tên payment_db nên điều kiện đó vô nghĩa — và một tài khoản
-- ngân hàng giả lọt vào production là mã QR trỏ vào hư không, khách chuyển tiền đi mất.
--
-- Bảng rỗng thì service TỪ CHỐI mở payment intent. Đó là hành vi đúng: thà checkout
-- không chạy được và ai đó phải cấu hình, còn hơn chạy được với tài khoản sai.
-- Dev và test tự tạo tài khoản của mình; production do SUPER_ADMIN tạo qua API.
