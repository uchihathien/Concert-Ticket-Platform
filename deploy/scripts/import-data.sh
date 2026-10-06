#!/usr/bin/env bash
# Nạp gói của export-local-data.sh lên cụm production. CHẠY TRÊN MÁY CHỦ.
#
#   YES=1 bash deploy/scripts/import-data.sh /tmp/nexaticket-data.tar.gz
#
# THAY THẾ toàn bộ dữ liệu đang có trong 11 database và volume ảnh. Không có đường lùi ngoài bản sao
# lưu, nên script hỏi xác nhận trừ khi YES=1 (cần khi chạy qua SSM, nơi không có bàn phím).
#
# Sáu việc, đúng thứ tự:
#   1. Kiểm version Flyway từng database khớp với gói. Lệch là dừng — dump dữ liệu chỉ đúng khi hai
#      bên cùng schema, và lỗi lệch schema lúc COPY hiện ra ở một bảng ngẫu nhiên với một thông điệp
#      không nói gì về version.
#   2. Dừng 13 service backend. Chúng giữ kết nối, cache, và poller outbox; nạp dữ liệu dưới chân
#      chúng là nhận một trạng thái nửa vời.
#   3. Mỗi database: TRUNCATE mọi bảng (trừ flyway_schema_history) rồi nạp. URL ảnh `localhost:9000`
#      được đổi sang MEDIA_PUBLIC_URL ngay trong luồng nạp, cho mọi cột của mọi bảng — không phải
#      liệt kê tay từng cột, và không bỏ sót cột nào mới thêm sau này.
#   4. Thay volume ảnh của objectstore bằng bản local.
#   5. Tạo lại user trên Keycloak production cho từng user trong identity_db và cập nhật idp_subject.
#      identity_db nhận diện người dùng theo idp_subject của Keycloak; subject local vô nghĩa ở đây,
#      và không nối lại thì mọi tài khoản đều mồ côi — đăng nhập được nhưng không thuộc tổ chức nào.
#      Mật khẩu tạm sinh ngẫu nhiên, in ra MỘT lần, và Keycloak bắt đổi ở lần đăng nhập đầu.
#   6. Xoá cache Redis, bật lại service, chờ healthy, chạy smoke test.
set -euo pipefail

PKG="${1:?Thiếu đường dẫn gói .tar.gz}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="$ROOT/deploy/compose/.env"
C="docker compose -f $ROOT/deploy/compose/prod.yml --env-file $ENV_FILE"
PG=nexaticket-prod-postgres-1
DBS="identity catalog inventory ordering payment ledger payout ticketing notification analytics ai_chatbox"
SERVICES="identity-service catalog-service inventory-service ordering-service payment-service ledger-service payout-service ticketing-service notification-service analytics-service ai-chatbox-service realtime-gateway api-gateway"

val() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }
MEDIA_PUBLIC_URL="$(val MEDIA_PUBLIC_URL)"
[ -n "$MEDIA_PUBLIC_URL" ] || { echo "Thiếu MEDIA_PUBLIC_URL trong .env" >&2; exit 1; }
psql() { docker exec -i "$PG" psql -U postgres -v ON_ERROR_STOP=1 -q "$@"; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
tar -C "$TMP" -xzf "$PKG"; WORK="$TMP/nexaticket-data"

echo "==> 1. Version Flyway"
while read -r db v; do
  # </dev/null: `docker exec -i` đọc stdin, mà stdin của vòng lặp là flyway.txt — không chặn thì
  # nó nuốt hết file sau database đầu tiên và vòng lặp kết thúc im lặng, 10 database không được kiểm.
  pv=$(psql -d "${db}_db" -tAc "select max(version) from flyway_schema_history where success" </dev/null | tr -d '[:space:]')
  [ "$v" = "$pv" ] || { echo "    LỆCH $db: gói V$v, máy chủ V$pv. Deploy đúng commit của gói rồi chạy lại." >&2; exit 1; }
  printf '    %-13s V%s khớp\n' "$db" "$v"
done < "$WORK/flyway.txt"

if [ "${YES:-0}" != 1 ]; then
  printf '\n  Sẽ XOÁ toàn bộ dữ liệu trong 11 database và volume ảnh của production. Gõ "co" để tiếp: '
  read -r a; [ "$a" = co ] || { echo "Huỷ."; exit 1; }
fi

echo "==> 2. Dừng 13 service backend"
$C stop $SERVICES >/dev/null 2>&1

echo "==> 3. Nạp database (URL ảnh: http://localhost:9000 -> $MEDIA_PUBLIC_URL)"
for db in $DBS; do
  tables=$(psql -d "${db}_db" -tAc "select string_agg(quote_ident(tablename), ', ') from pg_tables where schemaname='public' and tablename <> 'flyway_schema_history'")
  psql -d "${db}_db" -c "TRUNCATE $tables RESTART IDENTITY CASCADE"
  zcat "$WORK/db/$db.sql.gz" | sed "s#http://localhost:9000#$MEDIA_PUBLIC_URL#g" | psql -d "${db}_db"
  printf '    %-13s %s bảng\n' "$db" "$(echo "$tables" | tr ',' '\n' | wc -l)"
done
# Hai sự kiện mẫu của bản dev mang slug `live-concert-*`. smoke.sh coi slug đó là dấu hiệu dữ liệu
# dev lọt lên production — đúng với mọi lần deploy khác, sai với lần này, vì lần này là cố ý. Đổi
# slug thay vì xoá: xoá kéo theo suất diễn, tồn kho, và đơn hàng đã tham chiếu tới chúng.
psql -d catalog_db -c "update events set slug = regexp_replace(slug, '^live-concert-', 'concert-') where slug like 'live-concert-%'"

echo "==> 4. Ảnh"
vol=$(docker volume ls -q | grep -E '^nexaticket-prod_mediadata$')
$C stop objectstore >/dev/null 2>&1
docker run --rm -v "$vol:/data" -v "$WORK:/pkg:ro" alpine sh -c 'rm -rf /data/* /data/.[!.]* 2>/dev/null; tar -C /data -xzf /pkg/media.tar.gz'
$C start objectstore >/dev/null 2>&1
echo "    volume $vol đã thay"

echo "==> 5. Keycloak: tạo user và nối idp_subject"
# Tách thành script riêng vì nó phải chạy lại được độc lập: bản đầu chết ở đúng bước này (xem bẫy
# ghi trong relink-keycloak-users.sh), để lại 13 service đã dừng và dữ liệu đã nạp — chạy lại cả
# import là xoá nạp lại 92 nghìn dòng chỉ để tới được bước 5.
bash "$ROOT/deploy/scripts/relink-keycloak-users.sh"

echo
echo "==> 6. Bật lại"
docker exec -e REDISCLI_AUTH="$(val REDIS_PASSWORD)" nexaticket-prod-redis-1 redis-cli --no-auth-warning FLUSHALL >/dev/null
$C up -d --no-build --wait >/dev/null 2>&1 && echo "    22 container healthy"
bash "$ROOT/deploy/scripts/smoke.sh"
