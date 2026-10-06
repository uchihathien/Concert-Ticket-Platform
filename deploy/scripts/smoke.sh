#!/usr/bin/env bash
# Kiểm nhanh sau deploy: hệ thống có thật sự phục vụ được không.
#
#   ./deploy/scripts/smoke.sh
#
# Chạy TRÊN máy chủ, sau `docker compose up --wait`. `--wait` đã đợi container healthy, nhưng
# healthy chỉ nói mỗi container tự thấy mình ổn — nó không nói gateway định tuyến tới đúng chỗ,
# Keycloak có realm, hay một request thật đi hết đường. Đó là khoảng cách mà file này lấp.
#
# Thoát khác 0 khi có bất kỳ mục nào đỏ, để CI dừng lại thay vì báo deploy thành công.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE="docker compose -f $ROOT/deploy/compose/prod.yml --env-file $ROOT/deploy/compose/.env"
GATEWAY_PORT="${GATEWAY_PORT:-8080}"

fails=0
ok()   { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
bad()  { printf '  \033[31mĐỎ\033[0m   %s — %s\n' "$1" "$2"; fails=$((fails + 1)); }

SERVICES=(api-gateway identity-service catalog-service inventory-service ordering-service
          payment-service ledger-service payout-service ticketing-service notification-service
          analytics-service realtime-gateway)

echo "==> Readiness của 12 service"
for svc in "${SERVICES[@]}"; do
  # Hỏi TỪ BÊN TRONG mạng docker: cổng quản trị 9090 cố ý không publish ra host, và việc nó không
  # với tới được từ ngoài chính là một phần của thiết kế (production-checklist §4).
  #
  # `readiness`, không phải `health`: health trả UP ngay khi context lên, còn readiness mới là
  # "đã sẵn sàng nhận lưu lượng" — Flyway xong, pool kết nối mở được.
  body="$($COMPOSE exec -T "$svc" wget -qO- --timeout=5 \
          http://127.0.0.1:9090/actuator/health/readiness 2>/dev/null)"
  case "$body" in
    *'"status":"UP"'*) ok "$svc" ;;
    '')                bad "$svc" "không trả lời" ;;
    *)                 bad "$svc" "$(echo "$body" | head -c 120)" ;;
  esac
done

echo "==> Đường công khai"
# Một GET thật đi qua gateway tới catalog. Đây là mục duy nhất chứng minh định tuyến còn đúng:
# mười hai service khoẻ mà bảng route sai thì mọi thứ trên vẫn xanh.
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
        "http://127.0.0.1:$GATEWAY_PORT/v1/events?page=0&size=1")"
[ "$code" = 200 ] && ok "GET /v1/events qua gateway" || bad "GET /v1/events qua gateway" "HTTP $code"

# Keycloak: hỏi discovery document chứ không hỏi trang chủ. Trang chủ trả 200 kể cả khi realm
# `nexaticket` chưa import — và một realm thiếu chỉ lộ ra lúc người đầu tiên bấm đăng nhập.
iss="$(grep -E '^OIDC_ISSUER=' "$ROOT/deploy/compose/.env" | cut -d= -f2-)"
if [ -n "$iss" ]; then
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$iss/.well-known/openid-configuration")"
  [ "$code" = 200 ] && ok "Keycloak realm ($iss)" || bad "Keycloak realm" "HTTP $code tại $iss"
else
  bad "Keycloak realm" "thiếu OIDC_ISSUER trong .env"
fi

echo "==> Dữ liệu"
# Chốt chặn cuối, và là chốt quan trọng nhất trong danh sách này: dữ liệu mẫu KHÔNG được có mặt.
# `ProductionHardening` đã chặn cờ demo lúc khởi động, nhưng nó không chặn được dữ liệu mà một lần
# chạy sai cấu hình trước đó đã kịp ghi vào database.
demo="$($COMPOSE exec -T postgres psql -U postgres -d catalog_db -tAc \
        "select count(*) from events where slug like 'live-concert-%'" 2>/dev/null | tr -d '\r ')"
case "$demo" in
  0)  ok "catalog_db không có sự kiện mẫu" ;;
  '') bad "catalog_db" "không truy vấn được" ;;
  *)  bad "catalog_db" "còn $demo sự kiện mẫu — dữ liệu dev đã lọt lên production" ;;
esac

echo
if [ "$fails" = 0 ]; then
  echo "Smoke test xanh."
else
  echo "Smoke test ĐỎ: $fails mục. Cụm đang chạy ảnh mới — cân nhắc rollback bằng BACKEND_TAG cũ." >&2
fi
exit "$fails"
