#!/usr/bin/env bash
# Xuất dữ liệu của cụm dev local (infra.yml) thành MỘT gói, để nạp lên máy chủ bằng import-data.sh.
#
#   bash deploy/scripts/export-local-data.sh <thư-mục-đích>
#   -> <thư-mục-đích>/nexaticket-data.tar.gz
#
# Gói gồm: 11 database (chỉ DỮ LIỆU), volume ảnh của SeaweedFS, và flyway.txt ghi version migration
# của từng database — import-data.sh từ chối nạp nếu máy chủ không ở đúng version đó, vì dump dữ liệu
# chỉ đúng khi hai bên cùng schema.
#
# KHÔNG xuất keycloak_db: trong đó là client secret và redirect URI của localhost, cộng các tài khoản
# dev có mật khẩu trùng tên. Chép lên là phá đăng nhập production và đưa superadmin/superadmin ra
# internet. import-data.sh tạo lại các user đó trên Keycloak production rồi nối lại identity_db.
#
# KHÔNG xuất bảng `outbox` của từng service: đó là hàng đợi sự kiện chưa phát. Chép lên thì poller
# phát lại hàng trăm sự kiện cũ vào broker production ngay khi service khởi động.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:?Thiếu thư mục đích}"
COMPOSE="docker compose -f $ROOT/deploy/compose/infra.yml"
DBS="identity catalog inventory ordering payment ledger payout ticketing notification analytics ai_chatbox"
MEDIA_VOLUME="${MEDIA_VOLUME:-nexaticket-infra_mediadata}"

work="$OUT/nexaticket-data"
rm -rf "$work"; mkdir -p "$work/db"
$COMPOSE exec -T postgres pg_isready -U postgres >/dev/null \
  || { echo "Postgres local chưa chạy: $COMPOSE up -d postgres" >&2; exit 1; }

echo "==> Database"
: > "$work/flyway.txt"
for db in $DBS; do
  v=$($COMPOSE exec -T postgres psql -U postgres -d "${db}_db" -tAc \
        "select max(version) from flyway_schema_history where success" | tr -d '[:space:]')
  echo "$db $v" >> "$work/flyway.txt"
  # --disable-triggers: dữ liệu có khoá ngoại chéo nhau, nạp theo thứ tự bảng sẽ vướng; tắt trigger
  # trong lúc COPY rồi bật lại. Cần superuser khi nạp — import-data.sh nạp bằng `postgres`.
  $COMPOSE exec -T postgres pg_dump -U postgres -d "${db}_db" --data-only --disable-triggers \
      --exclude-table=flyway_schema_history --exclude-table=outbox --no-owner --no-privileges \
    | gzip -6 > "$work/db/$db.sql.gz"
  printf '    %-13s V%s  %s\n' "$db" "$v" "$(du -h "$work/db/$db.sql.gz" | cut -f1)"
done

echo "==> Ảnh (volume $MEDIA_VOLUME)"
# MSYS_NO_PATHCONV: Git Bash trên Windows đổi mọi tham số bắt đầu bằng `/` thành đường dẫn Windows,
# nên `-C /data` tới docker thành `C:/Program Files/Git/data` và tar trong container không tìm thấy.
MSYS_NO_PATHCONV=1 docker run --rm -v "$MEDIA_VOLUME:/data:ro" alpine tar -C /data -cf - . | gzip -6 > "$work/media.tar.gz"
echo "    $(du -h "$work/media.tar.gz" | cut -f1)"

tar -C "$OUT" -czf "$OUT/nexaticket-data.tar.gz" nexaticket-data
rm -rf "$work"
echo "==> $OUT/nexaticket-data.tar.gz  ($(du -h "$OUT/nexaticket-data.tar.gz" | cut -f1))"
