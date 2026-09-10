-- Vô hiệu hoá tài khoản và thu hồi phiên đăng nhập.
--
-- Bối cảnh: Keycloak là nguồn chân lý của danh tính (ADR-0016) và nó tự quản refresh token. Nhưng
-- một access token đã phát thì sống 15 phút và KHÔNG có gì ở phía ta từ chối được nó. Nghĩa là
-- trước migration này, "cho nhân viên nghỉ việc" chỉ có hiệu lực sau khi token của họ hết hạn — và
-- "khoá tài khoản" thì hoàn toàn không làm được, vì không có chỗ nào ghi trạng thái đó.
--
-- Gỡ thành viên khỏi tổ chức thì đã có hiệu lực ngay từ trước: membership được tra mỗi request chứ
-- không nằm trong token. Hai bảng dưới đây lo phần còn lại — chính DANH TÍNH.

-- ---------------------------------------------------------------------------
-- Trạng thái tài khoản.
--
-- Tách khỏi việc xoá bản ghi: người dùng bị vô hiệu hoá vẫn còn đơn hàng, vé đã mua và dòng trong
-- nhật ký kiểm toán trỏ vào id của họ. Xoá là làm mồ côi tất cả những thứ đó ở năm service khác.
-- ---------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE', 'DISABLED'));

-- ---------------------------------------------------------------------------
-- Mốc "mọi token phát trước thời điểm này đều vô hiệu".
--
-- Đây là cơ chế thu hồi TOÀN BỘ phiên, và nó rẻ đúng vì nó không lưu danh sách gì: một cột duy
-- nhất trên hàng người dùng vốn đã được đọc ở mọi request. So `iat` của token với cột này là một
-- phép so sánh trên dữ liệu đã có trong tay.
--
-- NULL nghĩa là chưa từng thu hồi — khác 0 hay epoch: một giá trị mặc định trong quá khứ vẫn là
-- một phép so sánh phải làm ở mọi request, còn NULL thì bỏ qua được cả nhánh.
-- ---------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN tokens_valid_from TIMESTAMPTZ;

-- ---------------------------------------------------------------------------
-- Thu hồi MỘT phiên — tức là một thiết bị.
--
-- Vì sao cần cả hai: "quên đăng xuất ở máy ở quán net" và "cho nhân viên nghỉ việc" là hai việc
-- khác nhau. Cái đầu mà phải đá người dùng ra khỏi mọi thiết bị thì họ sẽ không dùng nút đó.
--
-- Khoá là claim `sid` của Keycloak: một phiên SSO. IdP nào không phát `sid` thì chỉ dùng được cơ
-- chế thu hồi toàn bộ ở trên — mất tính năng, không mất an toàn.
--
-- `expires_at` để dọn được: sau khi phiên SSO đã hết hạn ở Keycloak thì hàng này không còn ý nghĩa,
-- và một bảng chỉ tăng là một bảng sẽ làm chậm chính đường nóng nó phục vụ.
-- ---------------------------------------------------------------------------
CREATE TABLE revoked_sessions (
    sid        TEXT PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    revoked_by UUID        REFERENCES users (id),
    reason     TEXT,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_revoked_sessions_user ON revoked_sessions (user_id);

-- Quét dọn: xoá hàng đã quá hạn. Đường ghi của việc thu hồi tự dọn theo index này.
CREATE INDEX idx_revoked_sessions_expiry ON revoked_sessions (expires_at);
