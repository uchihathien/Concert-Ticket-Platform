-- ordering-service: đơn hàng và trạng thái saga checkout.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.
--
-- Service này mỏng về nghiệp vụ nhưng dày về ĐIỀU PHỐI: nó là saga orchestrator của checkout
-- (sagas.md §2). Phần lớn schema ở đây tồn tại để trả lời được câu "saga đang dở ở bước nào"
-- sau khi process chết giữa chừng.

CREATE TABLE orders (
    id                   UUID PRIMARY KEY,

    -- Mã đơn cho người đọc: NT-YYMMDD-XXXXXX. Khách đọc mã này qua điện thoại cho CSKH,
    -- nên không dùng UUID.
    order_number         TEXT        NOT NULL UNIQUE,

    event_session_id     UUID        NOT NULL,
    organization_id      UUID        NOT NULL,
    user_id              UUID        NOT NULL,

    -- Một lần giữ chỗ chỉ thành đúng một đơn. Khách bấm "Thanh toán" hai lần trên mạng chập
    -- chờn sẽ gửi hai request cùng holdId; unique index này biến request thứ hai thành
    -- "trả lại đơn cũ" thay vì tạo đơn thứ hai và giữ ghế hai lần.
    hold_id              UUID        NOT NULL UNIQUE,

    status               TEXT        NOT NULL DEFAULT 'AWAITING_PAYMENT',

    subtotal_vnd         BIGINT      NOT NULL,
    discount_vnd         BIGINT      NOT NULL DEFAULT 0,
    total_vnd            BIGINT      NOT NULL,

    -- Hoa hồng nền tảng CHỐT TẠI THỜI ĐIỂM BÁN. Đổi biểu phí sau này không được làm sai sổ
    -- sách của đơn cũ, nên không bao giờ tính lại từ bảng cấu hình hiện hành.
    commission_bps       INT         NOT NULL,
    commission_vnd       BIGINT      NOT NULL,

    promotion_code       TEXT,

    -- Do payment-service sinh, ordering chỉ lưu lại để đối chiếu và hiển thị.
    payment_reference    TEXT UNIQUE,

    -- Snapshot chuỗi EMVCo mà khách đang nhìn. Lưu ở đây chứ không hỏi lại payment-service
    -- vì hai lý do: mã QR khách đã thấy KHÔNG được đổi, và mở lại trang đơn hàng không nên
    -- phụ thuộc payment-service còn sống. Cùng lý lẽ với snapshot hoa hồng ở trên.
    vietqr_payload       TEXT,

    -- Lưới an toàn thứ ba của saga (sagas.md §2): kể cả khi bù trừ hỏng và job quét cũng hỏng,
    -- mốc này vẫn nhả ghế. Hạn xấu nhất 15 phút.
    payment_expires_at   TIMESTAMPTZ NOT NULL,

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at              TIMESTAMPTZ,
    closed_at            TIMESTAMPTZ,
    close_reason         TEXT,

    CONSTRAINT ck_order_status CHECK (
        status IN ('AWAITING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT ck_amounts_non_negative CHECK (
        subtotal_vnd >= 0 AND discount_vnd >= 0 AND total_vnd >= 0 AND commission_vnd >= 0),
    CONSTRAINT ck_commission_bps CHECK (commission_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_total_is_subtotal_minus_discount CHECK (total_vnd = subtotal_vnd - discount_vnd),
    -- Hoa hồng không bao giờ được vượt số tiền khách trả: nếu vượt, phần chia cho tổ chức âm
    -- và sổ cái sẽ ghi một khoản phải trả âm.
    CONSTRAINT ck_commission_within_total CHECK (commission_vnd <= total_vnd),
    CONSTRAINT ck_paid_has_timestamp CHECK ((status = 'PAID') = (paid_at IS NOT NULL))
);

-- Worker hết hạn thanh toán quét đúng index này mỗi 15 giây.
CREATE INDEX idx_order_payment_expiry ON orders (payment_expires_at) WHERE status = 'AWAITING_PAYMENT';
CREATE INDEX idx_order_user           ON orders (user_id, created_at DESC);
CREATE INDEX idx_order_session        ON orders (event_session_id);
CREATE INDEX idx_order_organization   ON orders (organization_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Dòng đơn hàng — SNAPSHOT, không phải tham chiếu.
--
-- Nhãn ghế, tên hạng vé, đơn giá và tỷ lệ hoa hồng đều được sao vào đây. Sáu tháng sau, khi tổ
-- chức đã đổi tên hạng vé và nền tảng đã đổi biểu phí, hoá đơn của đơn cũ vẫn phải in ra đúng
-- những gì khách đã thấy lúc mua.
-- ---------------------------------------------------------------------------
CREATE TABLE order_items (
    id               UUID PRIMARY KEY,
    order_id         UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,

    session_seat_id  UUID        NOT NULL,

    seat_code        TEXT        NOT NULL,
    zone_code        TEXT        NOT NULL,
    admission_type   TEXT        NOT NULL,
    seat_label       TEXT,

    ticket_type_id   UUID        NOT NULL,
    ticket_type_name TEXT        NOT NULL,

    unit_price_vnd   BIGINT      NOT NULL,
    discount_vnd     BIGINT      NOT NULL DEFAULT 0,
    commission_bps   INT         NOT NULL,
    commission_vnd   BIGINT      NOT NULL,

    CONSTRAINT uq_item_seat_in_order  UNIQUE (order_id, session_seat_id),
    CONSTRAINT ck_item_admission_type CHECK (admission_type IN ('SEATED', 'STANDING')),
    CONSTRAINT ck_item_amounts        CHECK (unit_price_vnd >= 0 AND discount_vnd >= 0 AND commission_vnd >= 0)
);

CREATE INDEX idx_item_order ON order_items (order_id);

-- ---------------------------------------------------------------------------
-- Trạng thái saga checkout.
--
-- Bảng này tồn tại vì một lý do duy nhất: process có thể chết giữa saga. Khi đó ghế đã
-- RESERVED ở inventory-service nhưng chưa có gì trong orders — nhìn vào orders sẽ không thấy
-- dấu vết nào để dọn. Ghi bước đã làm vào đây TRƯỚC khi làm nó, nên sau khi sống lại ta biết
-- phải bù trừ những gì.
--
-- Ba lớp an toàn (sagas.md §2):
--   1. Bù trừ ngay trong luồng đồng bộ
--   2. COMPENSATION_PENDING + job quét mỗi 30 giây
--   3. orders.payment_expires_at 15 phút
-- ---------------------------------------------------------------------------
CREATE TABLE checkout_sagas (
    order_id            UUID PRIMARY KEY,
    hold_id             UUID        NOT NULL,
    user_id             UUID        NOT NULL,
    status              TEXT        NOT NULL DEFAULT 'STARTED',

    -- Bước nào đã thực sự thay đổi trạng thái ở service khác. Chỉ những bước này mới cần bù trừ.
    seats_reserved      BOOLEAN     NOT NULL DEFAULT FALSE,
    payment_intent_open BOOLEAN     NOT NULL DEFAULT FALSE,

    attempts            INT         NOT NULL DEFAULT 0,
    last_error          TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_saga_status CHECK (
        status IN ('STARTED', 'COMPLETED', 'COMPENSATED', 'COMPENSATION_PENDING', 'FAILED'))
);

-- Job quét mỗi 30 giây tìm đúng các saga cần bù trừ lại.
CREATE INDEX idx_saga_compensation_pending ON checkout_sagas (updated_at)
    WHERE status = 'COMPENSATION_PENDING';
