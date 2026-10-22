-- Tài khoản nhân viên soát vé do HỆ THỐNG cấp: tên đăng nhập tự sinh + mã PIN 6 số.
--
-- ---------------------------------------------------------------------------
-- Vì sao không dùng staff_invitations
-- ---------------------------------------------------------------------------
-- Lời mời bằng email giả định nhân viên có email và tự đặt mật khẩu. Cộng tác viên theo ca thường
-- không có email dùng được, và ban tổ chức cần phát tài khoản NGAY tại quầy (in phiếu, quét QR là
-- vào). Hai luồng có vòng đời khác hẳn: lời mời có "đã gửi thư / link hết hạn / đã kích hoạt", tài
-- khoản PIN có "đang dùng / bị khoá / hết hạn theo lịch suất diễn". Bảng cũ giữ nguyên cho dữ liệu cũ.
--
-- ---------------------------------------------------------------------------
-- Bảng này KHÔNG lưu mã PIN
-- ---------------------------------------------------------------------------
-- PIN chỉ tồn tại ba nơi: Keycloak (dạng băm), phản hồi HTTP lúc tạo/cấp lại (một lần), và phiếu in
-- trên tay nhân viên. Mất phiếu thì cấp lại PIN mới — không có đường nào đọc lại PIN cũ.

-- Mã ngắn của tổ chức, phần đầu của tên đăng nhập nhân viên (`nexa-nv01`). Cấp một lần, không đổi:
-- đổi là đổi tên đăng nhập của mọi nhân viên đang có phiếu in trên tay.
CREATE TABLE staff_login_prefixes (
    organization_id UUID PRIMARY KEY REFERENCES organizations (id),
    prefix          TEXT NOT NULL UNIQUE,
    -- Số thứ tự kế tiếp. Tăng bằng UPDATE ... RETURNING, nên hai người tạo cùng lúc không bao giờ
    -- nhận cùng một số.
    next_number     INT  NOT NULL DEFAULT 1,
    CONSTRAINT ck_staff_prefix CHECK (prefix ~ '^[a-z0-9]{2,12}$')
);

CREATE TABLE staff_accounts (
    -- users.id. Một người là tài khoản PIN của đúng một tổ chức.
    user_id           UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    organization_id   UUID        NOT NULL REFERENCES organizations (id),
    -- Phần nhân viên gõ, KHÔNG gồm miền email nội bộ phía sau `@`.
    username          TEXT        NOT NULL UNIQUE,
    status            TEXT        NOT NULL DEFAULT 'ACTIVE',
    -- Ban tổ chức gia hạn tay: tài khoản còn dùng được ít nhất tới mốc này, kể cả khi suất cuối đã
    -- qua. NULL = chỉ theo lịch suất diễn của sự kiện được gán.
    access_until      TIMESTAMPTZ,
    created_by        UUID        REFERENCES users (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    pin_issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ACTIVE: đăng nhập được. LOCKED: ban tổ chức khoá tay. EXPIRED: hệ thống khoá vì suất cuối của
    -- mọi sự kiện được gán đã kết thúc quá lâu. Cả hai trạng thái sau đều tắt tài khoản bên Keycloak.
    CONSTRAINT ck_staff_account_status CHECK (status IN ('ACTIVE', 'LOCKED', 'EXPIRED'))
);

CREATE INDEX idx_staff_accounts_org ON staff_accounts (organization_id);
-- Tác vụ tự hết hạn chỉ quét những tài khoản còn đang dùng.
CREATE INDEX idx_staff_accounts_active ON staff_accounts (organization_id) WHERE status = 'ACTIVE';
