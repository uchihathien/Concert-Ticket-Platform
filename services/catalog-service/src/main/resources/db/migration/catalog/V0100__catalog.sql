-- catalog-service: địa điểm, sự kiện, suất diễn, hạng vé.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.
-- (Tên `unit_price_cents` trong docs/01-foundation/data-model.md là tên cũ; ở đây dùng price_vnd.)
--
-- Catalog là NGUỒN CHÂN LÝ của "sự kiện nào tồn tại, bán ở đâu, giá bao nhiêu". Tồn kho từng chỗ
-- KHÔNG nằm ở đây — nó thuộc inventory-service, dựng từ sự kiện `session.published`. Ranh giới đó
-- là cố ý: catalog bị đọc nhiều nhưng ghi ít, còn tồn kho bị ghi 10k lần/giây lúc mở bán. Chung
-- một database thì đường đọc catalog chết theo mỗi lần mở bán.

-- ---------------------------------------------------------------------------
-- Địa điểm
-- ---------------------------------------------------------------------------
CREATE TABLE venues (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    name            TEXT        NOT NULL,
    city            TEXT        NOT NULL,
    address         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_venue_org ON venues (organization_id, name);

-- ---------------------------------------------------------------------------
-- Khu vực của địa điểm.
--
-- Đây là thứ thay cho "seat map" đầy đủ trong docs/02-catalog-admin: thay vì bắt ban tổ chức vẽ
-- từng ghế, mỗi khu khai số hàng × số ghế mỗi hàng và hệ thống tự sinh. Đánh đổi: mất khả năng tả
-- khu có hình dạng bất thường, được lại một màn hình nhập liệu làm xong trong một phút thay vì
-- một buổi. Khu hình dạng lạ vẫn tả được bằng cách tách thành nhiều khu chữ nhật.
--
-- Khu đứng không có hàng/ghế, chỉ có sức chứa; Inventory sinh đơn vị ảo cho nó (ADR-1012).
-- ---------------------------------------------------------------------------
CREATE TABLE venue_zones (
    id            UUID PRIMARY KEY,
    venue_id      UUID NOT NULL REFERENCES venues (id) ON DELETE CASCADE,

    -- Mã ngắn làm tiền tố mã chỗ: 'A' -> 'A-3-12'. Ổn định, không đổi sau khi đã bán vé.
    zone_code     TEXT NOT NULL,
    name          TEXT NOT NULL,
    kind          TEXT NOT NULL,

    row_count     INT,   -- SEATED
    seats_per_row INT,   -- SEATED
    capacity      INT,   -- STANDING

    sort_order    INT  NOT NULL DEFAULT 0,

    CONSTRAINT uq_zone_code  UNIQUE (venue_id, zone_code),
    CONSTRAINT ck_zone_kind  CHECK (kind IN ('SEATED', 'STANDING')),
    -- Hình dạng khu phải khớp loại khu. Thiếu ràng buộc này thì một khu ngồi quên số hàng vẫn
    -- lưu được, và lỗi chỉ lộ ra lúc publish — sau khi ban tổ chức đã khai xong mọi thứ khác.
    CONSTRAINT ck_zone_shape CHECK (
        (kind = 'SEATED'   AND row_count > 0 AND seats_per_row > 0 AND capacity IS NULL)
     OR (kind = 'STANDING' AND capacity  > 0 AND row_count IS NULL AND seats_per_row IS NULL))
);

CREATE INDEX idx_zone_venue ON venue_zones (venue_id, sort_order);

-- ---------------------------------------------------------------------------
-- Sự kiện
-- ---------------------------------------------------------------------------
CREATE TABLE events (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    venue_id        UUID        NOT NULL REFERENCES venues (id),

    -- Unique TOÀN HỆ THỐNG chứ không phải theo tổ chức: slug nằm trên URL công khai
    -- /events/{slug}, nên hai tổ chức không thể cùng giữ một slug.
    slug            TEXT        NOT NULL UNIQUE,
    title           TEXT        NOT NULL,
    summary         TEXT,
    description     TEXT,
    category        TEXT        NOT NULL,
    poster_url      TEXT,

    status          TEXT        NOT NULL DEFAULT 'DRAFT',
    published_at    TIMESTAMPTZ,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_event_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'UNPUBLISHED', 'CANCELLED')),
    -- Đã publish thì phải có mốc thời gian: trang công khai sắp xếp theo đúng cột này.
    CONSTRAINT ck_published_at CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

-- Đường đọc công khai: chỉ sự kiện PUBLISHED, mới nhất trước.
CREATE INDEX idx_event_published ON events (published_at DESC) WHERE status = 'PUBLISHED';
CREATE INDEX idx_event_org       ON events (organization_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Suất diễn
-- ---------------------------------------------------------------------------
CREATE TABLE event_sessions (
    id                       UUID PRIMARY KEY,
    event_id                 UUID        NOT NULL REFERENCES events (id) ON DELETE CASCADE,

    starts_at                TIMESTAMPTZ NOT NULL,
    ends_at                  TIMESTAMPTZ,
    sales_open_at            TIMESTAMPTZ NOT NULL,
    sales_close_at           TIMESTAMPTZ NOT NULL,

    -- Trần mua vé; NULL nghĩa là "theo mặc định nền tảng". Kế thừa được giải lúc publish rồi gửi
    -- giá trị đã chốt sang Inventory: đường giữ chỗ đọc một cột thay vì tính min() ở 10k đồng
    -- thời (ADR-1014 §1).
    max_seated_per_hold      INT,
    max_standing_per_hold    INT,
    max_units_per_hold       INT,
    max_tickets_per_customer INT,

    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_sales_window CHECK (sales_close_at > sales_open_at),
    CONSTRAINT ck_limits_positive CHECK (
        coalesce(max_seated_per_hold, 1) > 0 AND coalesce(max_standing_per_hold, 1) > 0
    AND coalesce(max_units_per_hold, 1) > 0 AND coalesce(max_tickets_per_customer, 1) > 0)
);

CREATE INDEX idx_session_event ON event_sessions (event_id, starts_at);

-- ---------------------------------------------------------------------------
-- Hạng vé: một khu của địa điểm, ở một suất diễn, với một mức giá.
--
-- Khoá duy nhất (suất, khu) là chốt chặn cho bất biến "mỗi chỗ đúng một giá". Không có nó thì hai
-- hạng vé cùng trỏ vào một khu sẽ sinh hai mã chỗ trùng nhau lúc materialize, Inventory từ chối
-- cả suất diễn, và lỗi hiện ra ở service khác cách chỗ gây lỗi một chặng.
-- ---------------------------------------------------------------------------
CREATE TABLE ticket_types (
    id               UUID   PRIMARY KEY,
    event_session_id UUID   NOT NULL REFERENCES event_sessions (id) ON DELETE CASCADE,
    venue_zone_id    UUID   NOT NULL REFERENCES venue_zones (id),

    name             TEXT   NOT NULL,
    price_vnd        BIGINT NOT NULL,
    sort_order       INT    NOT NULL DEFAULT 0,

    CONSTRAINT uq_type_zone          UNIQUE (event_session_id, venue_zone_id),
    CONSTRAINT ck_price_non_negative CHECK (price_vnd >= 0)
);

CREATE INDEX idx_type_session ON ticket_types (event_session_id, sort_order);
