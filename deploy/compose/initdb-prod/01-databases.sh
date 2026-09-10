#!/bin/sh
# Tạo database + user cho từng service, mật khẩu lấy từ biến môi trường.
#
# VÌ SAO LÀ .sh CHỨ KHÔNG PHẢI .sql: bản dev (`initdb/01-databases.sql`) ghi thẳng mật khẩu bằng
# chính tên service — đọc được trong repo, và đó là chủ ý ở máy phát triển. Ở production mật khẩu
# không được nằm trong repo, mà `psql` thì không giãn biến môi trường trong file .sql. Script shell
# giãn được, nên mật khẩu đi từ `.env` vào thẳng CREATE USER mà không qua đĩa.
#
# CHỈ CHẠY MỘT LẦN, lúc thư mục dữ liệu còn rỗng. Đã có dữ liệu rồi thì đổi mật khẩu bằng
# `ALTER USER ... PASSWORD ...` bằng tay; sửa file này không có tác dụng gì.
#
# MỖI SERVICE MỘT USER là ràng buộc kiến trúc (ADR-1002), không phải thói quen: có test tự động
# kiểm rằng user của service này KHÔNG kết nối được vào database của service kia. Dùng chung một
# user cho cả mười service thì test đó đỏ, và một lỗi SQL injection ở service nhỏ nhất đọc được
# sổ cái.
set -eu

need() {
  eval "v=\${$1:-}"
  [ -n "$v" ] || { echo "THIEU bien moi truong $1" >&2; exit 1; }
}

for s in IDENTITY CATALOG INVENTORY ORDERING PAYMENT LEDGER_OWNER LEDGER_APP PAYOUT TICKETING NOTIFICATION ANALYTICS KEYCLOAK; do
  need "DB_PASSWORD_$s"
done

create() {
  name="$1"
  # Tên database mặc định suy từ tên role; tham số thứ 3 ghi đè khi hai thứ đó khác nhau
  # (ledger_owner sở hữu ledger_db, không phải ledger_owner_db).
  db="${3:-${name}_db}"
  eval "pw=\$DB_PASSWORD_$2"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE USER $name WITH PASSWORD '$pw';
CREATE DATABASE $db OWNER $name;
SQL
}

create identity     IDENTITY
create catalog      CATALOG
create inventory    INVENTORY
create ordering     ORDERING
create payment      PAYMENT
# Sổ cái là ngoại lệ DUY NHẤT: hai role, không phải một (ADR-1005, bất biến 2).
#
# Owner bỏ qua mọi REVOKE trên bảng của chính mình. Nên nếu service chạy bằng owner thì sổ cái
# KHÔNG append-only, dù migration có REVOKE — đã kiểm bằng lệnh thật, UPDATE và DELETE đều đi qua.
# ledger_owner chạy Flyway; ledger_app là role runtime và không bao giờ có UPDATE/DELETE trên
# journal_entries, postings. Quyền cụ thể do migration V0101 cấp.
create ledger_owner LEDGER_OWNER ledger_db
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE USER ledger_app WITH PASSWORD '$DB_PASSWORD_LEDGER_APP';
SQL
create payout       PAYOUT
create ticketing    TICKETING
create notification NOTIFICATION
create analytics    ANALYTICS

# Keycloak giữ realm, user và session trong Postgres ở production.
#
# Bản dev dùng `start-dev`, vốn giữ database trong chính filesystem của container — nên recreate
# container là mất sạch tài khoản, và identity_db (nhận diện theo `idp_subject`) lệch với mọi tài
# khoản cũ. Ở production điều đó là mất dữ liệu người dùng thật, nên Keycloak phải có database
# riêng nằm trên volume.
create keycloak KEYCLOAK

echo "Da tao 11 database va 12 user (ledger co ledger_owner + ledger_app)."
