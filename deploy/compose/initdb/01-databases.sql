-- Database per service (ADR-1002).
-- MVP dùng một cụm PostgreSQL với nhiều database logic tách bằng quyền — đủ cô lập, rẻ hơn
-- nhiều instance. Riêng ledger_db tách instance vật lý khi lên production.

CREATE USER identity   WITH PASSWORD 'identity';
CREATE USER catalog    WITH PASSWORD 'catalog';
CREATE USER inventory  WITH PASSWORD 'inventory';
CREATE USER ordering   WITH PASSWORD 'ordering';
CREATE USER payment    WITH PASSWORD 'payment';
-- ledger CÓ HAI ROLE, không phải một (ADR-1005, bất biến 2).
--
-- ledger_owner sở hữu schema và chạy Flyway; ledger_app là role runtime của service. Owner bỏ qua
-- mọi REVOKE trên bảng của chính mình, nên nếu service chạy bằng owner thì sổ cái KHÔNG append-only
-- dù migration có REVOKE — đã kiểm bằng lệnh thật: UPDATE và DELETE đều đi qua.
CREATE USER ledger_owner WITH PASSWORD 'ledger_owner';
CREATE USER ledger_app   WITH PASSWORD 'ledger_app';
CREATE USER payout     WITH PASSWORD 'payout';
CREATE USER ticketing  WITH PASSWORD 'ticketing';
CREATE USER notification WITH PASSWORD 'notification';
CREATE USER analytics  WITH PASSWORD 'analytics';

CREATE DATABASE identity_db     OWNER identity;
CREATE DATABASE catalog_db      OWNER catalog;
CREATE DATABASE inventory_db    OWNER inventory;
CREATE DATABASE ordering_db     OWNER ordering;
CREATE DATABASE payment_db      OWNER payment;
CREATE DATABASE ledger_db       OWNER ledger_owner;
CREATE DATABASE payout_db       OWNER payout;
CREATE DATABASE ticketing_db    OWNER ticketing;
CREATE DATABASE notification_db OWNER notification;
CREATE DATABASE analytics_db    OWNER analytics;

-- Quyền trên bảng của ledger_db do migration V0101 cấp — nó chạy bằng ledger_owner nên cấp được,
-- và nó NÉM LỖI nếu role ledger_app chưa tồn tại thay vì bỏ qua im lặng.

-- CẢNH BÁO cho máy đã chạy từ trước: script này CHỈ chạy khi thư mục dữ liệu còn rỗng. Database
-- dev đã tồn tại sẽ không có hai role trên, và ledger-service sẽ không kết nối được. Chạy tay:
--   CREATE USER ledger_owner WITH PASSWORD 'ledger_owner';
--   CREATE USER ledger_app   WITH PASSWORD 'ledger_app';
--   ALTER DATABASE ledger_db OWNER TO ledger_owner;
--   REASSIGN OWNED BY ledger TO ledger_owner;   -- chạy khi đang kết nối vào ledger_db

-- Mỗi user service chỉ nên thấy database của mình. Postgres cấp CONNECT cho PUBLIC theo mặc định,
-- nên điều đó KHÔNG tự đúng — xem hạng mục còn lại trong báo cáo kiến trúc.
