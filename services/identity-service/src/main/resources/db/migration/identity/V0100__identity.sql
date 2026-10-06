-- =====================================================================
-- Identity & Access
-- ADR-1010: CHỈ SUPER_ADMIN tạo tổ chức. Không có luồng tự tạo.
-- Máy trạng thái KYC của ADR-1007 đã gỡ; status chỉ còn ACTIVE | SUSPENDED.
-- =====================================================================

CREATE TABLE organizations (
    id          UUID PRIMARY KEY,
    slug        TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_org_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- Hồ sơ pháp nhân do superadmin nhập khi tạo tổ chức (custodial-funds.md §8).
-- Dữ liệu tuân thủ, KHÔNG phải cổng kiểm soát tự động.
CREATE TABLE organization_profiles (
    organization_id     UUID PRIMARY KEY REFERENCES organizations (id),
    legal_name          TEXT,
    tax_code            TEXT,
    representative_name TEXT,
    contact_email       TEXT,
    contact_phone       TEXT,
    note                TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id                   UUID PRIMARY KEY,
    idp_subject          TEXT        NOT NULL UNIQUE,
    email                TEXT        NOT NULL UNIQUE,
    full_name            TEXT,
    phone                TEXT,
    -- Tách khỏi user_id để analytics không join được với PII (legal-constraints-vn.md §4)
    analytics_subject_id UUID        NOT NULL UNIQUE,
    is_super_admin       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE organization_members (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id),
    user_id         UUID        NOT NULL REFERENCES users (id),
    role            TEXT        NOT NULL,
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_id),
    CONSTRAINT ck_member_role CHECK (role IN ('ORG_OWNER', 'ORG_ADMIN', 'EVENT_MANAGER', 'CHECKIN_STAFF'))
);

CREATE INDEX idx_members_user ON organization_members (user_id);

-- Luôn còn ít nhất một ORG_OWNER: ép ở tầng application (xoá owner cuối bị chặn),
-- index này để câu kiểm tra đó rẻ.
CREATE INDEX idx_members_owner ON organization_members (organization_id) WHERE role = 'ORG_OWNER';

CREATE TABLE invitations (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id),
    email           TEXT        NOT NULL,
    role            TEXT        NOT NULL,
    -- Chỉ lưu hash; token thô chỉ tồn tại trong email gửi đi.
    token_hash      TEXT        NOT NULL UNIQUE,
    invited_by      UUID        REFERENCES users (id),
    expires_at      TIMESTAMPTZ NOT NULL,
    accepted_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_invite_role CHECK (role IN ('ORG_OWNER', 'ORG_ADMIN', 'EVENT_MANAGER', 'CHECKIN_STAFF'))
);

CREATE INDEX idx_invitations_pending ON invitations (organization_id) WHERE accepted_at IS NULL;

-- ADR-1008: mã truy cập soát vé phạm vi hẹp cho nhân viên thời vụ.
CREATE TABLE scanner_access_codes (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id),
    event_session_id UUID        NOT NULL,
    code_hash        TEXT        NOT NULL UNIQUE,
    max_devices      INT         NOT NULL DEFAULT 5,
    devices_issued   INT         NOT NULL DEFAULT 0,
    expires_at       TIMESTAMPTZ NOT NULL,
    revoked_at       TIMESTAMPTZ,
    created_by       UUID        REFERENCES users (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_devices CHECK (devices_issued <= max_devices)
);

CREATE INDEX idx_scanner_codes_active ON scanner_access_codes (organization_id, event_session_id)
    WHERE revoked_at IS NULL;

-- Trần mua vé mặc định của tổ chức (ADR-1014). NULL = kế thừa trần nền tảng.
CREATE TABLE organization_purchase_limits (
    organization_id          UUID PRIMARY KEY REFERENCES organizations (id),
    max_seated_per_hold      INT,
    max_standing_per_hold    INT,
    max_units_per_hold       INT,
    max_tickets_per_customer INT,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY,
    actor_user_id   UUID,
    organization_id UUID,
    action          TEXT        NOT NULL,
    entity_type     TEXT        NOT NULL,
    entity_id       UUID,
    before_state    JSONB,
    after_state     JSONB,
    correlation_id  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_org_time ON audit_logs (organization_id, created_at DESC);
CREATE INDEX idx_audit_actor_time ON audit_logs (actor_user_id, created_at DESC);
