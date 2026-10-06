#!/usr/bin/env bash
# Áp deploy/rabbitmq/topology.yaml lên broker ĐANG CHẠY trong compose, trên máy chủ.
#
# VÌ SAO BƯỚC NÀY PHẢI TỒN TẠI. Topology là nguồn chân lý nằm ở `deploy/rabbitmq/topology.yaml`, và
# nó được áp bằng MỘT job khi deploy — không để mỗi service tự khai tuỳ ý (plan/README.md §4).
# `dev-up.sh` gọi nó, `e2e.yml` gọi nó, còn đường production thì KHÔNG AI GỌI.
#
# Hậu quả đã xảy ra thật, và nó giả dạng thành "năm service lỗi" chứ không thành "thiếu một bước
# deploy": broker lên trắng, không exchange nào, không queue nào. Đúng năm service có
# `@RabbitListener` — analytics, inventory, ledger, notification, ticketing — chết lúc khai queue rồi
# crash-loop; bảy service không chạm RabbitMQ vẫn healthy. `compose up --wait` khi đó đỏ ở một cái
# tên ngẫu nhiên trong năm cái, tuỳ cái nào bị bắt gặp trước, nên nó trông như lỗi của service đó.
#
# VÌ SAO CẦN LỚP BỌC NÀY chứ không gọi thẳng scripts/apply-rabbitmq-topology.sh: script kia nói
# chuyện với management API ở `localhost:15672`, nhưng prod.yml KHÔNG publish cổng đó ra host và đó
# là chủ ý — một giao diện quản trị broker mở ra internet là chuyện khác hẳn. Nên phải tìm địa chỉ
# container trên mạng bridge và gọi vào đó.
#
# IDEMPOTENT: mọi lời gọi đều là PUT/POST khai báo, chạy lại bao nhiêu lần cũng cho cùng kết quả.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/compose/.env}"
CONTAINER="${CONTAINER:-nexaticket-prod-rabbitmq-1}"

[ -s "$ENV_FILE" ] || { echo "Không có $ENV_FILE" >&2; exit 1; }
val() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

RUSER="$(val RABBITMQ_USER)"
RPASS="$(val RABBITMQ_PASSWORD)"
[ -n "$RUSER" ] && [ -n "$RPASS" ] \
  || { echo "Thiếu RABBITMQ_USER hoặc RABBITMQ_PASSWORD trong $ENV_FILE" >&2; exit 1; }

IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
       "$CONTAINER" 2>/dev/null || true)"
[ -n "$IP" ] || { echo "Không thấy container $CONTAINER đang chạy." >&2; exit 1; }

# CHỜ management API, không chỉ chờ container healthy.
#
# Healthcheck của rabbitmq là `rabbitmq-diagnostics check_running` — nó nói broker đã chạy, KHÔNG nói
# plugin management đã mở cổng 15672. Hai việc đó cách nhau vài giây, và gọi sớm thì script kia chết
# với `curl: (7) Failed to connect` — một lỗi mạng, nghe như sai địa chỉ chứ không như gọi quá sớm.
for i in $(seq 1 30); do
  if curl -fsS -o /dev/null -u "$RUSER:$RPASS" "http://$IP:15672/api/overview" 2>/dev/null; then
    break
  fi
  [ "$i" = 30 ] && { echo "Management API ở $IP:15672 không trả lời sau 60s." >&2; exit 1; }
  sleep 2
done

exec bash "$ROOT/scripts/apply-rabbitmq-topology.sh" "$IP:15672" "$RUSER" "$RPASS"
