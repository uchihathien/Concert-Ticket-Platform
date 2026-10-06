-- =====================================================================
-- Bất biến 2 của sổ cái (ADR-1005) — ÉP THẬT, không chỉ khai.
--
-- V0100 đã viết REVOKE UPDATE, DELETE, TRUNCATE ... FROM PUBLIC, và chính nó ghi chú rằng lệnh đó
-- chỉ có tác dụng khi "service chạy bằng một role riêng không phải owner". Điều kiện đó CHƯA BAO
-- GIỜ được đáp ứng: initdb tạo `CREATE DATABASE ledger_db OWNER ledger` và service kết nối bằng
-- đúng role `ledger` đó. Owner bỏ qua mọi REVOKE ... FROM PUBLIC trên bảng của chính mình.
--
-- Kiểm bằng lệnh thật trên cụm đang chạy trước khi viết file này:
--     psql -U ledger -d ledger_db -c "UPDATE postings SET amount_vnd = amount_vnd WHERE false;"
--     UPDATE 0        <- ĐƯỢC PHÉP
--     psql -U ledger -d ledger_db -c "DELETE FROM journal_entries WHERE false;"
--     DELETE 0        <- ĐƯỢC PHÉP
--
-- Nên bất biến quan trọng nhất của hệ thống tiền đang chỉ được giữ bằng quy ước trong application
-- code — đúng thứ mà V0100 nói là không đủ.
--
-- Bản sửa tách hai role:
--   ledger_owner : sở hữu schema, chạy Flyway. KHÔNG dùng để chạy service.
--   ledger_app   : role runtime. Không bao giờ có UPDATE/DELETE trên hai bảng bất biến.
--
-- VÌ SAO LÀ V0101 CHỨ KHÔNG SỬA THẲNG V0100
-- V0100 đã áp dụng ở mọi database đang chạy. Sửa nội dung nó làm đổi checksum, và Flyway sẽ từ
-- chối khởi động với "Migration checksum mismatch" ở mọi môi trường đã có dữ liệu — kể cả
-- production. Quyền là trạng thái của database, nên nó thuộc về một migration mới.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Role phải có sẵn, do script khởi tạo database tạo ra.
--
-- CỐ Ý ném lỗi thay vì tự tạo role: một `CREATE ROLE ... NOLOGIN` âm thầm ở đây sẽ khiến mọi lệnh
-- GRANT bên dưới chạy trót lọt và trông như đã ép được bất biến, trong khi service vẫn kết nối
-- bằng owner. Đó đúng là lớp lỗi mà file này sinh ra để chấm dứt: một chốt chặn được tin là đang
-- chạy trong khi nó không chạy.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_app') THEN
        RAISE EXCEPTION
            'Thieu role ledger_app. Sổ cái chỉ append-only khi service chạy bằng role KHÔNG phải '
            'owner. Tạo role rồi chạy lại:  CREATE ROLE ledger_app LOGIN PASSWORD ''<mat-khau>'';  '
            '(xem deploy/compose/initdb/01-databases.sql)';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ledger_app;

-- ---------------------------------------------------------------------
-- Hai bảng BẤT BIẾN: chỉ đọc và ghi thêm.
--
-- Sửa sai bằng bút toán đảo có reverses_entry_id (JournalEntry.reverse), không bao giờ sửa bản ghi
-- cũ. Không có UPDATE, không có DELETE, không có TRUNCATE — kể cả khi application code sai.
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT ON journal_entries TO ledger_app;
GRANT SELECT, INSERT ON postings        TO ledger_app;

-- Nói KHÔNG một cách tường minh. GRANT ở trên vốn đã không cấp ba quyền này, nên hai dòng dưới về
-- kỹ thuật là thừa. Giữ lại vì chúng biến ý định thành thứ đọc được ngay tại chỗ, và vì chúng vẫn
-- có tác dụng thật nếu sau này ai đó cấp nhầm quyền rộng hơn ở một migration khác.
REVOKE UPDATE, DELETE, TRUNCATE ON journal_entries FROM ledger_app;
REVOKE UPDATE, DELETE, TRUNCATE ON postings        FROM ledger_app;

-- ---------------------------------------------------------------------
-- Các bảng còn lại: quyền theo đúng nhu cầu đọc từ mã nguồn, không rộng hơn.
-- ---------------------------------------------------------------------

-- ledger_accounts CẦN UPDATE: JdbcLedgerRepository dùng
-- `ON CONFLICT (code, owner_type, owner_id) ... DO UPDATE SET code = EXCLUDED.code` để lấy lại id
-- một cách nguyên tử, và ON CONFLICT DO UPDATE đòi quyền UPDATE dù không hàng nào thật sự đổi.
GRANT SELECT, INSERT, UPDATE ON ledger_accounts TO ledger_app;

-- Ảnh chụp số dư cũng chỉ ghi thêm: số dư = snapshot gần nhất + phát sinh sau đó (V0100).
GRANT SELECT, INSERT ON account_balance_snapshots TO ledger_app;

-- Bảng hạ tầng của platform. Outbox được đánh dấu published_at sau khi broker xác nhận, và
-- idempotency_records bị dọn khi hết hạn — nên hai bảng này cần đủ DML.
GRANT SELECT, INSERT, UPDATE, DELETE ON outbox              TO ledger_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_records TO ledger_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON processed_events    TO ledger_app;

-- journal_entries.seq và outbox.seq là BIGSERIAL: thiếu quyền trên sequence thì INSERT hỏng lúc
-- chạy với "permission denied for sequence", và chỉ hỏng ở môi trường đã tách role.
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledger_app;

-- KHÔNG cấp gì trên flyway_schema_history: migration là việc của ledger_owner.

-- CỐ Ý KHÔNG dùng ALTER DEFAULT PRIVILEGES.
--
-- Nó sẽ tự cấp quyền cho mọi bảng ledger_owner tạo về sau — tiện, nhưng cũng có nghĩa là bảng bất
-- biến tiếp theo sẽ mặc định nhận cả UPDATE và DELETE, và không ai nhận ra. Ở đây quên cấp quyền
-- thì service đỏ ngay trong integration test với "permission denied", vì bộ test của ledger chạy
-- bằng chính ledger_app (LedgerTestBase). Hỏng to và sớm tốt hơn cấp rộng và im lặng.
--
-- Nên: migration nào thêm bảng cho ledger thì phải tự khai GRANT cho ledger_app trong chính nó.
