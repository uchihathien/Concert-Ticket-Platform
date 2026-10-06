#!/usr/bin/env bash
# Phục hồi MỘT database từ một file .dump.
#
#   ./restore-database.sh identity_db ../compose/backups/identity_db-20260910-031700.dump
#
# ------------------------------------------------------------------------------------------------
# DIỄN TẬP LÀ MỘT PHẦN CỦA CÔNG VIỆC, KHÔNG PHẢI VIỆC LÀM THÊM.
#
# Cách thử mà không đụng gì tới dữ liệu thật — làm ít nhất một lần, và làm lại sau mỗi thay đổi
# lớn về schema:
#
#   docker compose -f ../compose/prod.yml --env-file ../compose/.env exec -T postgres \
#     psql -U postgres -c 'CREATE DATABASE dien_tap'
#   ./restore-database.sh dien_tap ../compose/backups/identity_db-<...>.dump
#   # rồi ĐỐI CHIẾU SỐ BẢN GHI, đừng chỉ xem lệnh có chạy xong không:
#   docker compose ... exec -T postgres psql -U postgres -d dien_tap \
#     -c 'SELECT count(*) FROM users'
#   docker compose ... exec -T postgres psql -U postgres -c 'DROP DATABASE dien_tap'
# ------------------------------------------------------------------------------------------------
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Dung: $0 <ten_database> <duong_dan_file.dump>" >&2
  echo "Vi du: $0 identity_db ../compose/backups/identity_db-20260910-031700.dump" >&2
  exit 2
fi

TARGET_DB="$1"
DUMP_FILE="$2"
COMPOSE_FILE="${COMPOSE_FILE:-$(dirname "$0")/../compose/prod.yml}"
ENV_FILE="${ENV_FILE:-$(dirname "$0")/../compose/.env}"

[ -f "$DUMP_FILE" ] || { echo "Khong thay file: $DUMP_FILE" >&2; exit 1; }

compose() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"; }

# Hỏi lại khi đích KHÔNG phải database diễn tập.
#
# `--clean` xoá đối tượng hiện có trước khi dựng lại: gõ nhầm tên database ở đây là mất dữ liệu
# thật, và mất một cách không rõ ràng — lệnh vẫn báo thành công.
case "$TARGET_DB" in
  *dien_tap*|*test*|*tmp*) ;;
  *)
    echo "SAP GHI DE database '$TARGET_DB' bang noi dung cua $DUMP_FILE."
    echo "Moi doi tuong dang co trong do se bi xoa truoc (--clean)."
    printf "Go dung ten database de xac nhan: "
    read -r confirm
    [ "$confirm" = "$TARGET_DB" ] || { echo "Da huy."; exit 1; }
    ;;
esac

echo "Dang phuc hoi $DUMP_FILE -> $TARGET_DB ..."

# `--exit-on-error` KHÔNG bật, và đó là chủ đích: pg_restore hay báo lỗi ở các lệnh thuộc về vai
# trò/quyền vốn đã tồn tại sẵn, trong khi dữ liệu phục hồi hoàn toàn đúng. Dừng ở lỗi đầu tiên sẽ
# bỏ dở giữa chừng và để lại một database nửa vời — trạng thái tệ nhất trong ba khả năng.
#
# Đổi lại: PHẢI đọc log, và PHẢI đối chiếu số bản ghi sau khi xong.
compose exec -T postgres pg_restore \
    --username=postgres --dbname="$TARGET_DB" --clean --if-exists --no-owner --no-password \
    < "$DUMP_FILE" || echo "(pg_restore bao mot so loi — doc ky ben tren truoc khi ket luan)"

echo
echo "Xong. BAY GIO DOI CHIEU SO BAN GHI — 'chay xong khong loi' khong phai la bang chung:"
echo "  docker compose -f $COMPOSE_FILE --env-file $ENV_FILE exec -T postgres \\"
echo "    psql -U postgres -d $TARGET_DB -c '\dt' -c 'SELECT count(*) FROM <bang_chinh>'"
