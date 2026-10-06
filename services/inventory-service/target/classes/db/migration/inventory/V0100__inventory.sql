-- inventory-service: tồn kho chỗ ngồi + chỗ đứng của từng suất diễn.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.
--
-- Bảng ở đây giữ BẢN SAO dữ liệu của Catalog (organization_id, seat_code, zone_code, giá, nhãn chỗ).
-- Đó là event-carried state transfer (ADR-1002), không phải trùng lặp sai: nhờ nó mà
-- GET /sessions/{id}/seats trả được sơ đồ chỗ mà không gọi Catalog — điều bắt buộc ở 10k đồng thời.

-- ---------------------------------------------------------------------------
-- Version khả dụng: dùng sequence, KHÔNG dùng cột đếm trên một hàng.
--
-- Một cột đếm trên session_inventory sẽ biến mỗi lần giữ chỗ thành một UPDATE vào
-- cùng một hàng ⇒ hot row, mọi transaction xếp hàng sau transaction trước.
-- Sequence không transactional nên không khoá nhau; đổi lại có thể có lỗ hổng số
-- khi transaction rollback. Client chỉ dùng version để phát hiện "có gì đó đổi rồi,
-- fetch lại", nên lỗ hổng số hoàn toàn vô hại.
-- ---------------------------------------------------------------------------
CREATE SEQUENCE availability_version_seq AS BIGINT START WITH 1;

CREATE TABLE session_inventory (
    id                       UUID PRIMARY KEY,
    event_session_id         UUID        NOT NULL UNIQUE,
    event_id                 UUID        NOT NULL,
    organization_id          UUID        NOT NULL,

    sales_open_at            TIMESTAMPTZ NOT NULL,
    sales_close_at           TIMESTAMPTZ NOT NULL,

    availability_version     BIGINT      NOT NULL DEFAULT 0,

    -- Trần mua vé ĐÃ GIẢI QUYẾT KẾ THỪA lúc materialize:
    -- coalesce(suất diễn, tổ chức, nền tảng), đã kẹp bằng LEAST với trần cứng (ADR-1014 §1).
    -- Đường giữ chỗ chỉ đọc một cột, không phải tính min() ở 10k đồng thời.
    max_seated_per_hold      INT         NOT NULL,
    max_standing_per_hold    INT         NOT NULL,
    max_units_per_hold       INT         NOT NULL,
    max_tickets_per_customer INT         NOT NULL,

    hold_ttl_seconds         INT         NOT NULL DEFAULT 600,

    materialized_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_sales_window    CHECK (sales_close_at > sales_open_at),
    CONSTRAINT ck_limits_positive CHECK (
        max_seated_per_hold > 0 AND max_standing_per_hold > 0
        AND max_units_per_hold > 0 AND max_tickets_per_customer > 0),
    CONSTRAINT ck_hold_ttl        CHECK (hold_ttl_seconds BETWEEN 60 AND 3600)
);

-- ---------------------------------------------------------------------------
-- Đơn vị tồn kho.
--
-- Vé đứng cũng nằm ở đây dưới dạng ĐƠN VỊ ẢO: lúc materialize sinh đúng
-- standing_capacity hàng với admission_type = 'STANDING' và seat_code dạng
-- '{zone}-GA-000137' (ADR-1012). Chúng không hiện trên sơ đồ chỗ.
--
-- Nhờ vậy chốt chặn oversell là Y HỆT cho cả hai loại vé — chỉ khác cách CHỌN
-- đơn vị, không khác cách BẢO VỆ. Không có đường code thứ hai cho bất biến
-- quan trọng nhất hệ thống.
-- ---------------------------------------------------------------------------
CREATE TABLE session_seats (
    id               UUID PRIMARY KEY,
    event_session_id UUID        NOT NULL REFERENCES session_inventory (event_session_id),

    seat_code        TEXT        NOT NULL,
    zone_code        TEXT        NOT NULL,
    admission_type   TEXT        NOT NULL,

    -- Nhãn hiển thị; NULL với vé đứng vì không có vị trí.
    section_label    TEXT,
    row_label        TEXT,
    seat_label       TEXT,
    pos_x            NUMERIC(8, 2),
    pos_y            NUMERIC(8, 2),

    ticket_type_id   UUID        NOT NULL,
    ticket_type_name TEXT        NOT NULL,
    price_vnd        BIGINT      NOT NULL,

    status           TEXT        NOT NULL DEFAULT 'AVAILABLE',

    -- Ai đang giữ đơn vị này. Ghi khi → HELD, giữ nguyên qua RESERVED và SOLD,
    -- XOÁ khi quay về AVAILABLE. Đây là toàn bộ dữ liệu cần để thực thi trần
    -- cộng dồn mỗi tài khoản (ADR-1014 §2) — không phải hỏi Ordering hay Ticketing.
    holder_user_id   UUID,

    CONSTRAINT uq_seat_code          UNIQUE (event_session_id, seat_code),
    CONSTRAINT ck_admission_type     CHECK (admission_type IN ('SEATED', 'STANDING')),
    CONSTRAINT ck_seat_status        CHECK (status IN ('AVAILABLE', 'HELD', 'RESERVED', 'SOLD', 'BLOCKED')),
    CONSTRAINT ck_price_non_negative CHECK (price_vnd >= 0),
    -- Cột phải đồng bộ với status: quên xoá là khách bị khoá oan hạn mức.
    -- Ràng buộc này biến "quên xoá" từ lỗi âm thầm thành lỗi ghi không chạy được.
    CONSTRAINT ck_holder_matches_status CHECK (
        (status IN ('HELD', 'RESERVED', 'SOLD') AND holder_user_id IS NOT NULL)
        OR (status IN ('AVAILABLE', 'BLOCKED') AND holder_user_id IS NULL))
);

CREATE INDEX idx_seat_session_status ON session_seats (event_session_id, status);

-- Cấp phát vé đứng: ORDER BY id ... FOR UPDATE SKIP LOCKED quét theo index này.
CREATE INDEX idx_seat_standing_alloc ON session_seats (event_session_id, zone_code, id)
    WHERE admission_type = 'STANDING' AND status = 'AVAILABLE';

-- Đếm trần cộng dồn mỗi tài khoản: một câu đếm có index, một bảng, một service.
CREATE INDEX idx_seat_holder ON session_seats (event_session_id, holder_user_id)
    WHERE holder_user_id IS NOT NULL;

-- ---------------------------------------------------------------------------
CREATE TABLE seat_holds (
    id               UUID PRIMARY KEY,
    event_session_id UUID        NOT NULL REFERENCES session_inventory (event_session_id),
    user_id          UUID        NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'ACTIVE',
    expires_at       TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at      TIMESTAMPTZ,

    CONSTRAINT ck_hold_status CHECK (status IN ('ACTIVE', 'CONVERTED', 'EXPIRED', 'RELEASED'))
);

-- Worker hết hạn quét đúng index này mỗi 10 giây.
CREATE INDEX idx_hold_expiry ON seat_holds (expires_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_hold_user   ON seat_holds (user_id, event_session_id);

-- ---------------------------------------------------------------------------
-- CHỐT CHẶN OVERSELL.
--
-- Redis Lua từ chối nhanh, nhưng Redis có thể mất key (hết hạn giữa chừng, failover).
-- Unique index dưới đây mới là thứ KHÔNG THỂ sai: hai transaction cùng chèn một
-- session_seat_id ở trạng thái ACTIVE thì một trong hai vi phạm unique và rollback.
-- Đã từng có Redis hay không, bất biến vẫn đúng.
-- ---------------------------------------------------------------------------
CREATE TABLE seat_hold_items (
    id              UUID PRIMARY KEY,
    hold_id         UUID NOT NULL REFERENCES seat_holds (id) ON DELETE CASCADE,
    session_seat_id UUID NOT NULL REFERENCES session_seats (id),
    status          TEXT NOT NULL DEFAULT 'ACTIVE',

    CONSTRAINT ck_hold_item_status CHECK (status IN ('ACTIVE', 'CONVERTED', 'RELEASED'))
);

CREATE UNIQUE INDEX uq_hold_item_active ON seat_hold_items (session_seat_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_hold_item_hold ON seat_hold_items (hold_id);

-- ---------------------------------------------------------------------------
CREATE TABLE seat_reservations (
    order_id         UUID PRIMARY KEY,
    hold_id          UUID        NOT NULL REFERENCES seat_holds (id),
    event_session_id UUID        NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'RESERVED',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at       TIMESTAMPTZ,

    CONSTRAINT ck_reservation_status CHECK (status IN ('RESERVED', 'SETTLED', 'CANCELLED'))
);

CREATE INDEX idx_reservation_hold ON seat_reservations (hold_id);
