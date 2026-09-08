-- Database per service (ADR-1002).
-- MVP dùng một cụm PostgreSQL với nhiều database logic tách bằng quyền — đủ cô lập, rẻ hơn
-- nhiều instance. Riêng ledger_db tách instance vật lý khi lên production.

CREATE USER identity   WITH PASSWORD 'identity';
CREATE USER catalog    WITH PASSWORD 'catalog';
CREATE USER inventory  WITH PASSWORD 'inventory';
CREATE USER ordering   WITH PASSWORD 'ordering';
CREATE USER payment    WITH PASSWORD 'payment';
CREATE USER ledger     WITH PASSWORD 'ledger';
CREATE USER payout     WITH PASSWORD 'payout';
CREATE USER ticketing  WITH PASSWORD 'ticketing';
CREATE USER notification WITH PASSWORD 'notification';
CREATE USER analytics  WITH PASSWORD 'analytics';

CREATE DATABASE identity_db     OWNER identity;
CREATE DATABASE catalog_db      OWNER catalog;
CREATE DATABASE inventory_db    OWNER inventory;
CREATE DATABASE ordering_db     OWNER ordering;
CREATE DATABASE payment_db      OWNER payment;
CREATE DATABASE ledger_db       OWNER ledger;
CREATE DATABASE payout_db       OWNER payout;
CREATE DATABASE ticketing_db    OWNER ticketing;
CREATE DATABASE notification_db OWNER notification;
CREATE DATABASE analytics_db    OWNER analytics;

-- Không user service nào được kết nối vào database của service khác.
-- Kiểm tra bằng test tự động (plan/backend.md — Definition of Done).
