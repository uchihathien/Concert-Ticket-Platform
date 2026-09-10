-- payment-service: yêu cầu thanh toán và đối chiếu chuyển khoản.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.
--
-- Mô hình tiền theo ADR-1004: khách chuyển khoản vào TÀI KHOẢN KÝ QUỸ CỦA NỀN TẢNG qua VietQR,
-- nội dung chuyển khoản là payment_reference, và một dịch vụ đối soát ngân hàng (SePay ở
-- production) báo về khi tiền vào. Service này KHÔNG giữ thẻ, KHÔNG chạm dữ liệu thẻ, và không
-- bao giờ là bên chuyển tiền — nó chỉ sinh mã và đối chiếu.
--
-- Hệ quả cho phạm vi PCI: không có dữ liệu thẻ nào đi qua đây, nên toàn bộ service nằm ngoài
-- phạm vi PCI-DSS. Thêm một cổng thẻ sau này là quyết định làm thay đổi điều đó, và phải được
-- cân nhắc tường minh chứ không rơi ra như hệ quả của một lần thêm provider.

-- ---------------------------------------------------------------------------
-- Yêu cầu thanh toán của một đơn hàng.
--
-- Khoá chính là order_id, KHÔNG phải một id riêng: mỗi đơn có đúng một yêu cầu thanh toán, và
-- đặt khoá chính ở đây biến bất biến đó thành thứ database tự giữ. Saga checkout có thể chạy lại
-- bước mở intent sau timeout mạng; lần thứ hai phải trả về đúng mã QR lần đầu, không phải sinh mã
-- mới — khách đang nhìn màn hình và mã trên đó không được đổi.
-- ---------------------------------------------------------------------------
CREATE TABLE payment_intents (
    order_id            UUID PRIMARY KEY,
    organization_id     UUID        NOT NULL,

    -- Nội dung khách gõ vào ô "nội dung chuyển khoản". Đây là thứ DUY NHẤT nối một giao dịch
    -- ngân hàng với một đơn hàng, nên nó phải: không trùng, đọc được qua điện thoại, và không
    -- chứa ký tự mà app ngân hàng lọc mất. Dạng NT + 10 ký tự Crockford Base32, không có chữ
    -- I/L/O/U để khỏi nhầm với 1/0.
    reference           TEXT        NOT NULL UNIQUE,

    amount_vnd          BIGINT      NOT NULL,
    status              TEXT        NOT NULL DEFAULT 'PENDING',

    -- Snapshot chuỗi EMVCo. Sinh một lần rồi lưu, không dựng lại mỗi lần đọc: mã QR khách đã
    -- chụp màn hình phải quét ra đúng số tiền và đúng nội dung cũ, kể cả khi cấu hình tài khoản
    -- ký quỹ đã đổi sau đó.
    vietqr_payload      TEXT        NOT NULL,
    bank_bin            TEXT        NOT NULL,
    bank_account_number TEXT        NOT NULL,

    -- Nhà cung cấp đã xác nhận giao dịch này. 'SANDBOX' nghĩa là tiền GIẢ.
    provider            TEXT,
    -- Mã giao dịch phía ngân hàng/cổng. Khoá chống trùng của webhook.
    provider_txn_id     TEXT,
    paid_amount_vnd     BIGINT,

    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at        TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,

    CONSTRAINT ck_intent_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_amount_positive CHECK (amount_vnd > 0),
    -- Trạng thái và mốc thời gian phải đi cùng nhau. Một intent CONFIRMED mà không có
    -- confirmed_at là một khoản tiền không biết vào lúc nào, và mốc đó đi thẳng vào sổ cái.
    CONSTRAINT ck_confirmed_has_timestamp CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CONSTRAINT ck_confirmed_has_provider  CHECK (
        (status = 'CONFIRMED') = (provider IS NOT NULL AND provider_txn_id IS NOT NULL))
);

-- Một giao dịch ngân hàng chỉ được ghi nhận đúng một lần.
--
-- Đây là chốt chặn chống ghi nhận trùng, và nó phải nằm ở database chứ không ở tầng ứng dụng:
-- mọi cổng thanh toán đều giao lại webhook cho tới khi nhận 2xx, nên lời gọi thứ hai là điều
-- CHẮC CHẮN xảy ra chứ không phải trường hợp hiếm. Partial index vì các intent chưa thanh toán
-- đều có provider_txn_id NULL.
CREATE UNIQUE INDEX uq_provider_txn ON payment_intents (provider, provider_txn_id)
    WHERE provider_txn_id IS NOT NULL;

-- Worker quét intent quá hạn.
CREATE INDEX idx_intent_expiry ON payment_intents (expires_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- Nhật ký webhook thô.
--
-- Ghi lại NGUYÊN VĂN mọi thứ nhận được, kể cả những cái bị từ chối, và ghi TRƯỚC khi xử lý.
-- Khi khách nói "tôi đã chuyển tiền rồi", đây là chỗ duy nhất trả lời được câu hỏi "ngân hàng
-- có báo về không, và ta đã làm gì với nó". Không có bảng này thì mọi tranh chấp về tiền đều
-- kết thúc bằng việc tin lời một bên.
-- ---------------------------------------------------------------------------
CREATE TABLE bank_webhook_log (
    id              UUID PRIMARY KEY,
    provider        TEXT        NOT NULL,
    provider_txn_id TEXT,
    reference       TEXT,
    amount_vnd      BIGINT,
    raw_payload     JSONB       NOT NULL,
    outcome         TEXT        NOT NULL,
    note            TEXT,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_webhook_outcome CHECK (
        outcome IN ('CONFIRMED', 'DUPLICATE', 'UNKNOWN_REFERENCE', 'AMOUNT_MISMATCH', 'NOT_PENDING', 'REJECTED'))
);

CREATE INDEX idx_webhook_reference ON bank_webhook_log (reference, received_at DESC);
CREATE INDEX idx_webhook_time      ON bank_webhook_log (received_at DESC);
