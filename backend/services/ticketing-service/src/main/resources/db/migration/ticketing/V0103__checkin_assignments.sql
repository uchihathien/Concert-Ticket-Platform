-- Nhân viên soát vé được soát sự kiện nào.
--
-- ---------------------------------------------------------------------------
-- Vì sao bảng này nằm ở ticketing chứ không ở identity hay catalog
-- ---------------------------------------------------------------------------
-- Tư cách thành viên (ai thuộc tổ chức nào, vai trò gì) là của identity; sự kiện là của catalog.
-- Nhưng câu hỏi "người này có được quét vé của sự kiện này không" được hỏi ở ĐÚNG MỘT chỗ: lúc
-- quét, tức là ở đây. Để câu trả lời ở service khác thì mỗi lượt quét phải đi một vòng mạng để lấy
-- nó, và ngày service kia chậm là ngày cửa vào ùn người.
--
-- Nên đây là nơi DUY NHẤT lưu việc gán. Identity (màn hình Nhân sự của ban tổ chức) đọc và ghi
-- qua /internal/checkin-assignments chứ không giữ bản sao nào.
--
-- Bảng chỉ áp dụng cho người CHỈ có quyền soát vé. Người quản lý được sự kiện của tổ chức
-- (CATALOG_MANAGE) soát mọi sự kiện của tổ chức mà không cần dòng nào ở đây — xem CheckinAccess.
CREATE TABLE checkin_assignments (
    organization_id UUID        NOT NULL,
    -- users.id của identity-service (không phải id bên Keycloak): tài khoản chưa kích hoạt được
    -- tạo lại bên Keycloak thì id này không đổi, nên việc gán không bao giờ mồ côi.
    user_id         UUID        NOT NULL,
    event_id        UUID        NOT NULL,
    -- NULL: gán bởi script chuẩn bị dữ liệu dev, không phải bởi một người.
    assigned_by     UUID,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, event_id)
);

-- Bảng Nhân sự đọc theo tổ chức.
CREATE INDEX idx_checkin_assignment_org ON checkin_assignments (organization_id, user_id);
