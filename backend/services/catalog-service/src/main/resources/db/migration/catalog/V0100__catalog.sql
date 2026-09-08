-- catalog-service: địa điểm, thiết kế chỗ ngồi, sự kiện, khuyến mãi.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.
--
-- Đây là nơi tổ chức làm ba việc chính của mình: chọn địa điểm, thiết kế khu vực ghế,
-- tạo sự kiện (venue-seating-model.md).

-- ---------------------------------------------------------------------------
-- Địa điểm.
--
-- scope = PLATFORM  : superadmin sở hữu, mọi tổ chức chọn được (Nhà hát Lớn, SVĐ Mỹ Đình...)
-- scope = ORGANIZATION: tổ chức tự dựng, chỉ mình dùng
--
-- Một địa điểm dùng chung phục vụ nhiều tổ chức, nên hệ thống KHÔNG biết ai đã thuê nó
-- ngày nào — đó là hợp đồng ngoài hệ thống. Vì vậy trùng lịch chỉ được CẢNH BÁO chứ không
-- chặn: chặn nhầm một sự kiện có thật tệ hơn nhiều so với hiện một cảnh báo thừa.
-- ---------------------------------------------------------------------------
CREATE TABLE venues (
    id              UUID PRIMARY KEY,
    scope           TEXT        NOT NULL,
    organization_id UUID,
    name            TEXT        NOT NULL,
    slug            TEXT        NOT NULL UNIQUE,
    address         TEXT,
    city            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_venue_scope CHECK (scope IN ('PLATFORM', 'ORGANIZATION')),
    -- Địa điểm dùng chung không thuộc tổ chức nào; địa điểm riêng thì bắt buộc phải có chủ.
    CONSTRAINT ck_venue_owner CHECK (
        (scope = 'PLATFORM' AND organization_id IS NULL)
        OR (scope = 'ORGANIZATION' AND organization_id IS NOT NULL))
);

CREATE INDEX idx_venue_org  ON venues (organization_id) WHERE organization_id IS NOT NULL;
CREATE INDEX idx_venue_city ON venues (city);

-- ---------------------------------------------------------------------------
-- Phiên bản sơ đồ của địa điểm.
--
-- Sơ đồ được đánh phiên bản chứ không sửa tại chỗ: một suất diễn ĐÃ BÁN VÉ ghim vào một
-- phiên bản cụ thể. Chủ địa điểm sửa sơ đồ cho sự kiện sau không được làm dịch chuyển ghế
-- của vé đã bán — khách cầm vé ghi "A-12" phải ngồi đúng chỗ A-12 mà họ đã chọn.
-- ---------------------------------------------------------------------------
CREATE TABLE venue_layout_versions (
    id           UUID PRIMARY KEY,
    venue_id     UUID        NOT NULL REFERENCES venues (id),
    version_no   INT         NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'DRAFT',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,

    CONSTRAINT uq_layout_version UNIQUE (venue_id, version_no),
    CONSTRAINT ck_layout_status  CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

-- Mỗi địa điểm có nhiều nhất một phiên bản đang hoạt động.
CREATE UNIQUE INDEX uq_single_active_layout ON venue_layout_versions (venue_id) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Khu vực.
--
-- Hai chiều ĐỘC LẬP (ADR-1012):
--   kind           = FIXED    | FLEXIBLE
--   admission_type = SEATED   | STANDING
--
-- Bốn ô của bảng này phủ hết ba kiểu concert mà không cần khái niệm mới:
--   * Toàn ghế ngồi  = các zone SEATED
--   * Vừa ngồi vừa đứng = trộn zone SEATED và STANDING
--   * Chỉ đứng       = chỉ có zone STANDING
--
-- FIXED  : chỗ áp cứng của địa điểm (khán đài bê tông, ghế bắt vít). Tổ chức KHÔNG sửa được.
-- FLEXIBLE: sàn trống, tổ chức tự bố trí theo từng sự kiện.
--
-- Một zone không thể vừa ngồi vừa đứng; muốn vậy chủ địa điểm chia thành hai zone.
-- ---------------------------------------------------------------------------
CREATE TABLE venue_zones (
    id                UUID PRIMARY KEY,
    layout_version_id UUID    NOT NULL REFERENCES venue_layout_versions (id) ON DELETE CASCADE,
    zone_code         TEXT    NOT NULL,
    name              TEXT    NOT NULL,
    kind              TEXT    NOT NULL,
    admission_type    TEXT    NOT NULL,

    -- Chỉ có nghĩa với zone STANDING: sức chứa tối đa của khu vực đó.
    standing_capacity INT,

    display_order     INT     NOT NULL DEFAULT 0,

    CONSTRAINT uq_zone_code       UNIQUE (layout_version_id, zone_code),
    CONSTRAINT ck_zone_kind       CHECK (kind IN ('FIXED', 'FLEXIBLE')),
    CONSTRAINT ck_zone_admission  CHECK (admission_type IN ('SEATED', 'STANDING')),
    CONSTRAINT ck_standing_capacity CHECK (
        (admission_type = 'STANDING' AND standing_capacity > 0)
        OR (admission_type = 'SEATED' AND standing_capacity IS NULL))
);

-- ---------------------------------------------------------------------------
-- Chỗ áp cứng của zone FIXED + SEATED.
--
-- Đây là dữ liệu của ĐỊA ĐIỂM, không phải của sự kiện. Tổ chức thuê chỗ không sửa được
-- bảng này; họ chỉ được bật/tắt cả zone hoặc chặn từng ghế cho sự kiện của mình.
-- ---------------------------------------------------------------------------
CREATE TABLE venue_fixed_seats (
    id         UUID PRIMARY KEY,
    zone_id    UUID          NOT NULL REFERENCES venue_zones (id) ON DELETE CASCADE,
    seat_code  TEXT          NOT NULL,
    row_label  TEXT          NOT NULL,
    seat_label TEXT          NOT NULL,
    pos_x      NUMERIC(8, 2),
    pos_y      NUMERIC(8, 2),

    CONSTRAINT uq_fixed_seat_code UNIQUE (zone_id, seat_code)
);

CREATE INDEX idx_fixed_seat_zone ON venue_fixed_seats (zone_id);

-- ---------------------------------------------------------------------------
CREATE TABLE events (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    slug            TEXT        NOT NULL UNIQUE,
    title           TEXT        NOT NULL,
    description     TEXT,
    category        TEXT,
    poster_url      TEXT,
    status          TEXT        NOT NULL DEFAULT 'DRAFT',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,

    CONSTRAINT ck_event_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'UNPUBLISHED'))
);

CREATE INDEX idx_event_org    ON events (organization_id, created_at DESC);
CREATE INDEX idx_event_public ON events (status, created_at DESC) WHERE status = 'PUBLISHED';

-- ---------------------------------------------------------------------------
-- Suất diễn.
--
-- Bốn cột trần mua vé ở đây là tầng GHI ĐÈ theo suất của ADR-1014. NULL nghĩa là kế thừa
-- từ tổ chức, và tổ chức NULL thì kế thừa nền tảng. Giá trị hiệu lực được giải quyết một
-- lần duy nhất lúc materialize rồi sao sang inventory — đường giữ chỗ không bao giờ phải
-- tính coalesce() ở 10k đồng thời.
-- ---------------------------------------------------------------------------
CREATE TABLE event_sessions (
    id                       UUID PRIMARY KEY,
    event_id                 UUID        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    venue_id                 UUID        NOT NULL REFERENCES venues (id),
    layout_version_id        UUID        NOT NULL REFERENCES venue_layout_versions (id),

    starts_at                TIMESTAMPTZ NOT NULL,
    ends_at                  TIMESTAMPTZ NOT NULL,
    sales_open_at            TIMESTAMPTZ NOT NULL,
    sales_close_at           TIMESTAMPTZ NOT NULL,

    max_seated_per_hold      INT,
    max_standing_per_hold    INT,
    max_units_per_hold       INT,
    max_tickets_per_customer INT,

    materialized_at          TIMESTAMPTZ,

    CONSTRAINT ck_session_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_session_sales  CHECK (sales_close_at > sales_open_at)
);

CREATE INDEX idx_session_event ON event_sessions (event_id, starts_at);
-- Dò trùng lịch địa điểm: chỉ để CẢNH BÁO, không chặn.
CREATE INDEX idx_session_venue ON event_sessions (venue_id, starts_at);

-- ---------------------------------------------------------------------------
CREATE TABLE ticket_tiers (
    id               UUID PRIMARY KEY,
    event_session_id UUID   NOT NULL REFERENCES event_sessions (id) ON DELETE CASCADE,
    name             TEXT   NOT NULL,
    price_vnd        BIGINT NOT NULL,
    display_order    INT    NOT NULL DEFAULT 0,

    CONSTRAINT ck_tier_price CHECK (price_vnd >= 0),
    CONSTRAINT uq_tier_name  UNIQUE (event_session_id, name)
);

-- ---------------------------------------------------------------------------
-- Thiết kế chỗ ngồi của một suất diễn.
--
-- Gán hạng vé theo KHU VỰC chứ không theo từng ghế: một suất 3.000 ghế mà bắt tổ chức gán
-- hạng vé cho từng ghế là bắt họ bỏ cuộc. Ghi đè từng ghế (chặn ghế kỹ thuật, ghế mời) đi
-- qua bảng overrides.
-- ---------------------------------------------------------------------------
CREATE TABLE seating_plan_zone_usages (
    id                UUID    PRIMARY KEY,
    event_session_id  UUID    NOT NULL REFERENCES event_sessions (id) ON DELETE CASCADE,
    zone_id           UUID    NOT NULL REFERENCES venue_zones (id),

    included          BOOLEAN NOT NULL DEFAULT TRUE,
    ticket_tier_id    UUID REFERENCES ticket_tiers (id),

    -- Chỉ với zone STANDING: tổ chức có thể bán ít hơn sức chứa của địa điểm, không nhiều hơn.
    standing_capacity INT,

    CONSTRAINT uq_zone_usage UNIQUE (event_session_id, zone_id)
);

-- Ghi đè từng chỗ trong một zone đã bật.
CREATE TABLE seating_plan_seat_overrides (
    id               UUID PRIMARY KEY,
    event_session_id UUID NOT NULL REFERENCES event_sessions (id) ON DELETE CASCADE,
    zone_id          UUID NOT NULL REFERENCES venue_zones (id),
    seat_code        TEXT NOT NULL,
    action           TEXT NOT NULL,
    ticket_tier_id   UUID REFERENCES ticket_tiers (id),

    CONSTRAINT uq_seat_override UNIQUE (event_session_id, zone_id, seat_code),
    -- REMOVE: chỗ không tồn tại ở sự kiện này (dựng sân khấu đè lên)
    -- BLOCK : chỗ tồn tại nhưng không bán (ghế kỹ thuật, ghế mời)
    -- SET_TIER: đổi hạng vé riêng cho chỗ này
    CONSTRAINT ck_override_action CHECK (action IN ('REMOVE', 'BLOCK', 'SET_TIER')),
    CONSTRAINT ck_set_tier_has_tier CHECK ((action = 'SET_TIER') = (ticket_tier_id IS NOT NULL))
);

-- ---------------------------------------------------------------------------
-- Trần mua vé — ba tầng (ADR-1014 §1).
-- ---------------------------------------------------------------------------
CREATE TABLE platform_purchase_limits (
    id                       SMALLINT PRIMARY KEY DEFAULT 1,
    max_seated_per_hold      INT         NOT NULL DEFAULT 8,
    max_standing_per_hold    INT         NOT NULL DEFAULT 10,
    max_units_per_hold       INT         NOT NULL DEFAULT 10,
    max_tickets_per_customer INT         NOT NULL DEFAULT 10,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by               UUID,

    CONSTRAINT ck_single_row CHECK (id = 1),
    CONSTRAINT ck_platform_limits_positive CHECK (
        max_seated_per_hold > 0 AND max_standing_per_hold > 0
        AND max_units_per_hold > 0 AND max_tickets_per_customer > 0)
);

-- Hàng duy nhất, tạo sẵn với trần mặc định. Bảng rỗng sẽ làm mọi lần publish hỏng.
INSERT INTO platform_purchase_limits (id) VALUES (1);

CREATE TABLE organization_purchase_limits (
    organization_id          UUID PRIMARY KEY,
    max_seated_per_hold      INT,
    max_standing_per_hold    INT,
    max_units_per_hold       INT,
    max_tickets_per_customer INT,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
CREATE TABLE promotions (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL,
    code             TEXT        NOT NULL,
    kind             TEXT        NOT NULL,
    value            BIGINT      NOT NULL,
    max_discount_vnd BIGINT,
    starts_at        TIMESTAMPTZ,
    ends_at          TIMESTAMPTZ,
    usage_limit      INT,
    used_count       INT         NOT NULL DEFAULT 0,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_promotion_code UNIQUE (organization_id, code),
    -- PERCENT: value là điểm cơ bản (500 = 5%); AMOUNT: value là số tiền VND
    CONSTRAINT ck_promotion_kind  CHECK (kind IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT ck_promotion_value CHECK (value > 0),
    CONSTRAINT ck_percent_range   CHECK (kind <> 'PERCENT' OR value <= 10000)
);

CREATE INDEX idx_promotion_lookup ON promotions (code) WHERE active;
