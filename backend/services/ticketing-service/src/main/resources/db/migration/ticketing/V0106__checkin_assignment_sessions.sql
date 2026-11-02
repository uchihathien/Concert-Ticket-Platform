-- Phân công soát vé theo SUẤT DIỄN, bên cạnh theo cả sự kiện.
--
-- Quy trình mới: nhân viên tự đăng ký tài khoản, ban tổ chức thêm họ vào tổ chức với vai Staff rồi
-- — gần ngày diễn — gán sự kiện, hoặc từng suất khi một sự kiện nhiều đêm cần người khác nhau mỗi
-- đêm. Phân công tự hết hiệu lực khi suất cuối trong phạm vi được gán kết thúc quá 6 giờ; hạn đó
-- tính lúc quét từ lịch diễn của catalog, KHÔNG lưu ở đây — ban tổ chức dời giờ diễn thì hạn dời
-- theo mà không ai phải sửa dòng nào.
--
-- Phân công hết hạn vẫn được giữ để xem lịch sử: lệnh gán chỉ thay các dòng còn hiệu lực.
--
-- Số 0106 (không phải 0104): nhánh main đã dùng 0104 (checkin_shifts) và 0105 (offline_checkin), và
-- database dev đã chạy hai bản đó. Trùng số thì Flyway báo sai checksum và ticketing không khởi động.

-- NULL = gán cả sự kiện (mọi suất, kể cả suất thêm sau). Khác NULL = chỉ suất này.
ALTER TABLE checkin_assignments ADD COLUMN event_session_id UUID;

-- Khoá cũ (user_id, event_id) không cho cùng một người được gán hai suất của một sự kiện.
ALTER TABLE checkin_assignments DROP CONSTRAINT checkin_assignments_pkey;

ALTER TABLE checkin_assignments ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE checkin_assignments ADD PRIMARY KEY (id);

-- Mỗi (người, sự kiện, phạm vi) một dòng. COALESCE vì hai NULL không bằng nhau trong UNIQUE thường:
-- thiếu nó thì "gán cả sự kiện" bấm hai lần ra hai dòng.
CREATE UNIQUE INDEX uq_checkin_assignment_scope
    ON checkin_assignments (user_id, event_id, COALESCE(event_session_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Đường quét vé: "người này có phân công nào cho sự kiện này".
CREATE INDEX idx_checkin_assignment_user_event ON checkin_assignments (organization_id, user_id, event_id);
