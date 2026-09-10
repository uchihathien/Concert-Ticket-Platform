-- Khung concert do Tổng công ty (superadmin) định nghĩa, và dấu vết của khung trên dữ liệu thật.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd.
--
-- Vì sao khung nằm ở catalog_db chứ không ở một service riêng: nội dung của một khung ĐÚNG BẰNG
-- nội dung của `venue_zones` — mã khu, loại khu, hình dạng. Tách sang service khác nghĩa là hai
-- định nghĩa "một khu là gì" ở hai database, và chúng sẽ lệch nhau ở lần sửa thứ ba.
--
-- Khung KHÔNG lưu từng ghế, cùng lý do với `venue_zones`: một khu ngồi khai row_count × seats_per_row
-- và ghế thật chỉ được sinh một lần, ở inventory-service, lúc publish. Lưu 5.000 hàng ghế ở đây là
-- lưu bản sao thứ hai của một thứ không ai đọc.

-- ---------------------------------------------------------------------------
-- Khung
-- ---------------------------------------------------------------------------
CREATE TABLE concert_templates (
    id          UUID PRIMARY KEY,

    -- Mã ngắn ổn định, tổ chức nhìn thấy: 'ARENA_5K'. UNIQUE toàn hệ thống vì khung thuộc nền
    -- tảng, không thuộc tổ chức nào.
    code        TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    category    TEXT        NOT NULL,
    description TEXT,

    -- DRAFT: đang dựng, tổ chức không thấy.
    -- ACTIVE: dùng được.
    -- ARCHIVED: không tạo sự kiện mới được nữa. KHÔNG ảnh hưởng sự kiện đã tạo — lúc áp khung,
    --           các khu đã được CHÉP sang địa điểm của tổ chức, nên sự kiện cũ không trỏ ngược
    --           về đây. Đó là lý do lưu trữ khung an toàn còn xoá thì không.
    status      TEXT        NOT NULL DEFAULT 'DRAFT',

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_template_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_template_active ON concert_templates (category, name) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Khu cố định của khung.
--
-- Ràng buộc hình dạng CHÉP NGUYÊN của venue_zones, không nới lỏng. Nới ở đây nghĩa là một khung
-- lưu được thứ mà venue_zones từ chối, và lỗi chỉ lộ ra lúc tổ chức áp khung — tức là ở màn hình
-- của người không gây ra lỗi và không sửa được nó.
-- ---------------------------------------------------------------------------
CREATE TABLE concert_template_zones (
    id                  UUID PRIMARY KEY,
    template_id         UUID   NOT NULL REFERENCES concert_templates (id) ON DELETE CASCADE,

    zone_code           TEXT   NOT NULL,
    name                TEXT   NOT NULL,
    kind                TEXT   NOT NULL,

    row_count           INT,   -- SEATED
    seats_per_row       INT,   -- SEATED
    capacity            INT,   -- STANDING

    sort_order          INT    NOT NULL DEFAULT 0,

    -- Giá gợi ý, không phải giá áp cứng: giá là quyết định thương mại của tổ chức (ADR-1010).
    -- NULL nghĩa là khung không gợi ý gì và tổ chức BẮT BUỘC phải khai giá lúc tạo sự kiện.
    suggested_price_vnd BIGINT,

    CONSTRAINT uq_template_zone_code UNIQUE (template_id, zone_code),
    CONSTRAINT ck_template_zone_kind CHECK (kind IN ('SEATED', 'STANDING')),
    CONSTRAINT ck_template_zone_shape CHECK (
        (kind = 'SEATED'   AND row_count > 0 AND seats_per_row > 0 AND capacity IS NULL)
     OR (kind = 'STANDING' AND capacity  > 0 AND row_count IS NULL AND seats_per_row IS NULL)),
    CONSTRAINT ck_template_price CHECK (suggested_price_vnd IS NULL OR suggested_price_vnd >= 0)
);

CREATE INDEX idx_template_zone ON concert_template_zones (template_id, sort_order);

-- ---------------------------------------------------------------------------
-- Dấu vết khung trên dữ liệu thật.
--
-- `venue_zones.source_template_zone_id` là chỗ câu "khu vực cố định, không thay đổi được" được
-- thực thi: khác NULL nghĩa là khu này đến từ khung của nền tảng, và API tuỳ chỉnh sơ đồ từ chối
-- đụng vào cả địa điểm đó. Không có cột này thì câu đó chỉ là một dòng trong tài liệu.
--
-- ON DELETE SET NULL chứ không CASCADE: xoá một khung KHÔNG được phép kéo theo địa điểm thật của
-- tổ chức. Mất dấu vết thì địa điểm trở thành địa điểm tự dựng — hậu quả duy nhất là tổ chức sửa
-- được nó, và đó là hành vi đúng khi khung đứng sau đã không còn.
-- ---------------------------------------------------------------------------
ALTER TABLE venues
    ADD COLUMN source_template_id UUID REFERENCES concert_templates (id) ON DELETE SET NULL;

ALTER TABLE venue_zones
    ADD COLUMN source_template_zone_id UUID REFERENCES concert_template_zones (id) ON DELETE SET NULL;

CREATE INDEX idx_venue_from_template ON venues (source_template_id) WHERE source_template_id IS NOT NULL;
