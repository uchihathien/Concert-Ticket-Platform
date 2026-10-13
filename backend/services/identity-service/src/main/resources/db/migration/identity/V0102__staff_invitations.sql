-- Lời mời nhân viên soát vé: tài khoản được TẠO SẴN lúc mời, nhân viên chỉ đặt mật khẩu.
--
-- ---------------------------------------------------------------------------
-- Vì sao không dùng lại bảng invitations
-- ---------------------------------------------------------------------------
-- `invitations` mô tả một lời mời mà người nhận tự mang tài khoản của mình tới nhận: có token,
-- chưa có user_id, và membership chỉ ra đời khi họ bấm chấp nhận. Lời mời nhân viên soát vé ngược
-- lại ở cả ba điểm — tài khoản do ban tổ chức tạo, membership có ngay, và "chấp nhận" là việc đặt
-- mật khẩu bên Keycloak. Nhồi vào cùng bảng thì mọi cột đều phải kèm chữ "trừ khi".
--
-- ---------------------------------------------------------------------------
-- Bảng này KHÔNG lưu gì về mật khẩu hay link
-- ---------------------------------------------------------------------------
-- Link đặt mật khẩu do Keycloak phát và Keycloak giữ. Ở đây chỉ có những gì bảng Nhân sự cần để
-- nói "đã mời / đã kích hoạt / link hết hạn / lỗi gửi", và ai đã mời.
--
-- Sự kiện mà nhân viên được soát KHÔNG nằm ở đây: ticketing-service là nơi duy nhất giữ việc gán
-- (bảng checkin_assignments), vì đó là nơi kiểm quyền ở mỗi lượt quét.
CREATE TABLE staff_invitations (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id),

    -- users.id, KHÔNG phải id bên Keycloak. Gửi lại lời mời tạo lại tài khoản Keycloak (id mới) để
    -- link cũ chết; id này đứng yên, nên lời mời và việc gán sự kiện không bao giờ mồ côi.
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    email           TEXT        NOT NULL,
    full_name       TEXT        NOT NULL,
    invited_by      UUID        REFERENCES users (id),
    invited_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Hạn của link trong thư gửi GẦN NHẤT.
    last_sent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,

    -- NULL cho tới khi nhân viên đặt xong mật khẩu.
    activated_at    TIMESTAMPTZ,

    delivery_status TEXT        NOT NULL DEFAULT 'SENT',
    delivery_error  TEXT,

    CONSTRAINT ck_staff_delivery CHECK (delivery_status IN ('SENT', 'FAILED')),
    CONSTRAINT uq_staff_invitation UNIQUE (organization_id, user_id)
);
