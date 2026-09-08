-- ticketing-service: phát hành vé, mã QR, và soát vé tại cửa.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.

-- ---------------------------------------------------------------------------
-- Khoá ký mã QR.
--
-- Ed25519 chứ không phải RSA hay HMAC:
--   * Chữ ký 64 byte cho token ~180 ký tự — QR ít ô hơn, quét nhanh hơn trong điều kiện
--     ánh sáng kém ở cửa vào. RSA-2048 cho chữ ký 256 byte, QR dày đặc và quét chậm hẳn.
--   * Khoá bất đối xứng nên máy soát vé chỉ cần khoá CÔNG KHAI. HMAC thì mỗi thiết bị
--     soát vé phải giữ khoá ký được — mất một máy là in được vé giả.
--
-- kid trong header cho phép xoay khoá mà KHÔNG vô hiệu vé đã phát hành: vé cũ vẫn verify
-- được bằng khoá cũ vẫn còn trong bảng, vé mới ký bằng khoá mới.
-- ---------------------------------------------------------------------------
CREATE TABLE signing_keys (
    kid          TEXT PRIMARY KEY,
    algorithm    TEXT        NOT NULL DEFAULT 'Ed25519',

    -- PKCS#8 / X.509 dạng base64. Khoá riêng trong database là điểm yếu đã biết; ở
    -- production nó phải nằm trong KMS và bảng này chỉ giữ khoá công khai. Ghi rõ ở đây
    -- để không ai tưởng đây là thiết kế cuối cùng.
    private_key  TEXT,
    public_key   TEXT        NOT NULL,

    is_active    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at   TIMESTAMPTZ,

    CONSTRAINT ck_algorithm CHECK (algorithm = 'Ed25519')
);

-- Đúng một khoá đang dùng để ký. Hai khoá active nghĩa là vé được ký bằng khoá nào là
-- ngẫu nhiên, và việc thu hồi một khoá trở thành trò may rủi.
CREATE UNIQUE INDEX uq_single_active_key ON signing_keys ((1)) WHERE is_active;

-- ---------------------------------------------------------------------------
-- Vé.
--
-- CHỐT CHẶN CUỐI CÙNG chống phát hành trùng: order_item_id UNIQUE. Webhook thanh toán
-- có thể đến hai lần, consumer có thể chạy lại, saga có thể retry — mọi đường đó đều
-- dừng ở unique index này. Một ghế đã bán mà in ra hai vé QR nghĩa là hai người cùng
-- đến cửa với vé hợp lệ.
-- ---------------------------------------------------------------------------
CREATE TABLE tickets (
    id               UUID PRIMARY KEY,

    order_id         UUID        NOT NULL,
    order_item_id    UUID        NOT NULL UNIQUE,

    event_session_id UUID        NOT NULL,
    organization_id  UUID        NOT NULL,
    user_id          UUID        NOT NULL,

    -- Snapshot từ đơn hàng: vé phải in ra đúng thứ khách đã mua, kể cả khi tổ chức
    -- đã đổi tên hạng vé hoặc sơ đồ chỗ sau đó.
    session_seat_id  UUID        NOT NULL,
    seat_code        TEXT        NOT NULL,
    zone_code        TEXT        NOT NULL,
    admission_type   TEXT        NOT NULL,
    seat_label       TEXT,
    ticket_type_name TEXT        NOT NULL,

    status           TEXT        NOT NULL DEFAULT 'VALID',
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    checked_in_at    TIMESTAMPTZ,
    checked_in_by    UUID,
    revoked_at       TIMESTAMPTZ,
    revoke_reason    TEXT,

    CONSTRAINT ck_ticket_status    CHECK (status IN ('VALID', 'CHECKED_IN', 'REVOKED')),
    CONSTRAINT ck_admission_type   CHECK (admission_type IN ('SEATED', 'STANDING')),
    CONSTRAINT ck_checked_in_pair  CHECK ((status = 'CHECKED_IN') = (checked_in_at IS NOT NULL))
);

CREATE INDEX idx_ticket_order   ON tickets (order_id);
CREATE INDEX idx_ticket_session ON tickets (event_session_id, status);
CREATE INDEX idx_ticket_user    ON tickets (user_id, issued_at DESC);

-- ---------------------------------------------------------------------------
-- Nhật ký soát vé — ghi CẢ những lần bị từ chối.
--
-- Đây là bằng chứng khi có tranh cãi ở cửa: "tôi đã quét rồi mà máy báo lỗi". Lần quét
-- trùng, vé của sự kiện khác, vé đã thu hồi — tất cả đều phải để lại dấu vết, kèm thiết
-- bị và nhân viên nào đã quét.
-- ---------------------------------------------------------------------------
CREATE TABLE checkin_log (
    id               UUID PRIMARY KEY,
    ticket_id        UUID REFERENCES tickets (id),
    event_session_id UUID,
    scanned_by       UUID        NOT NULL,
    device_id        TEXT,
    result           TEXT        NOT NULL,
    note             TEXT,
    scanned_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_checkin_result CHECK (result IN (
        'ACCEPTED', 'ALREADY_CHECKED_IN', 'REVOKED', 'WRONG_SESSION', 'INVALID_TOKEN', 'NOT_FOUND'))
);

CREATE INDEX idx_checkin_session ON checkin_log (event_session_id, scanned_at DESC);
CREATE INDEX idx_checkin_ticket  ON checkin_log (ticket_id, scanned_at DESC);
