#!/usr/bin/env bash
# Bật cả hệ thống ở máy phát triển: hạ tầng Docker + backend + (tuỳ chọn) frontend.
#
#   ./scripts/dev-up.sh                  # hạ tầng + install + 7 service lõi
#   ./scripts/dev-up.sh --skip-install   # bỏ qua `mvnw install` (chỉ khi KHÔNG đụng vào platform/)
#   ./scripts/dev-up.sh --all            # thêm ledger, payout, notification, realtime
#   ./scripts/dev-up.sh --with-frontend  # bật luôn 4 app Next.js
#   ./scripts/dev-up.sh --with-frontend=web-customer,web-admin   # chỉ những app cần
#   ./scripts/dev-up.sh --no-infra       # hạ tầng đã chạy sẵn
#
# Dừng lại: ./scripts/dev-down.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/backend/target/dev-logs"
# Chỉ để tra cứu. `dev-down.sh` KHÔNG dựa vào file này — nó giết theo cổng, cách duy nhất đúng
# kể cả khi bạn bật service bằng tay hoặc file này lạc hậu.
PIDFILE="$LOGS/dev-up.pids"

# Bảy service lõi. Đủ để duyệt toàn bộ website: bốn cái còn lại (ledger, payout, notification,
# realtime) không nằm trên đường nào mà giao diện gọi tới.
#
# Định dạng: <thư mục module>:<cổng>
CORE=(
  api-gateway:8080
  identity-service:8090
  catalog-service:8091
  inventory-service:8092
  ordering-service:8093
  payment-service:8095
  ticketing-service:8097
)
EXTRA=(
  ledger-service:8094
  payout-service:8096
  notification-service:8098
  analytics-service:8099
  realtime-gateway:8100
)

SKIP_INSTALL=0; WITH_FRONTEND=0; WITH_INFRA=1; SERVICES=("${CORE[@]}"); FRONTEND_APPS=""
for arg in "$@"; do
  case "$arg" in
    --skip-install)  SKIP_INSTALL=1 ;;
    --all)           SERVICES=("${CORE[@]}" "${EXTRA[@]}") ;;
    --with-frontend) WITH_FRONTEND=1 ;;
    # Chỉ bật những app thật sự cần. Bốn dev server Next chiếm ~2,6 GB, và phần lớn thời gian
    # người ta chỉ mở một. Ví dụ: --with-frontend=web-customer,web-admin
    --with-frontend=*)
      WITH_FRONTEND=1
      FRONTEND_APPS="${arg#*=}"
      ;;
    --no-infra)      WITH_INFRA=0 ;;
    -h|--help)       sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "Tham số lạ: $arg (xem --help)" >&2; exit 2 ;;
  esac
done

mkdir -p "$LOGS"

# --- Cảnh báo bộ nhớ ------------------------------------------------------------------------
#
# Cả stack là 12 JVM + 12 tiến trình Maven cha + 4 dev server Next + Docker. Trên máy 16 GB nó vừa
# đủ — cho tới khi có thứ khác ăn mất vài GB. Lúc đó thứ chết trước là Next, và nó chết bằng một
# thông báo KHÔNG nói gì về bộ nhớ:
#
#   Runtime Error: Jest worker encountered 2 child process exceptions, exceeding retry limit
#
# ("Jest worker" không liên quan tới Jest — Next dùng gói jest-worker để render trong tiến trình
# con. Con bị OS từ chối cấp bộ nhớ, chết hai lần, Next bỏ cuộc.)
#
# Thủ phạm thường gặp nhất là container Testcontainers còn sót: PostgresSingleton dùng
# withReuse(true), và Ryuk CỐ Ý không dọn container reusable — chúng sống mãi tới khi có người
# `docker rm`. Mỗi lần chạy integration test lại thêm vài cái; chín container postgres đã đo được
# là 5,5 GB.
if command -v docker >/dev/null 2>&1; then
  leftovers="$(docker ps -q --filter 'label=org.testcontainers=true' 2>/dev/null | wc -l | tr -d ' ')"
  if [ "${leftovers:-0}" -gt 0 ]; then
    echo "==> CẢNH BÁO: $leftovers container Testcontainers còn sót (withReuse ⇒ Ryuk không dọn)."
    echo "    Dọn: ./scripts/dev-down.sh --prune   — không dọn thì Next có thể chết vì hết RAM."
  fi
fi

# --- Secret thật từ .env, TRỪ ba biến database ---------------------------------------------
#
# `.env` có `DB_URL=…/identity_db` vì nó được viết cho việc chạy MỘT service (README §Thanh toán:
# `set -a && . ./.env && set +a` rồi `mvnw -pl services/payment-service spring-boot:run`). Nạp
# nguyên si rồi bật cả mười hai service thì mọi service đều trỏ vào identity_db — và triệu chứng
# không hề nói ra điều đó:
#
#   catalog-service: FlywayValidateException — "applied migration not resolved locally: 0002"
#
# tức là catalog đọc lịch sử migration của identity. Service chết ở bước validate trước khi ghi
# nên không hỏng dữ liệu, nhưng người đọc log sẽ đi tìm lỗi trong migration của catalog.
#
# Mỗi service đã có sẵn database riêng làm mặc định trong application.yml của nó, nên cách đúng là
# nạp .env rồi BỎ ba biến đó đi.
if [ -f "$ROOT/.env" ]; then
  echo "==> Nạp .env (bỏ DB_URL/DB_USER/DB_PASSWORD — mỗi service tự dùng database của mình)"
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
  unset DB_URL DB_USER DB_PASSWORD
fi

# Chờ tới khi URL trả 200, hoặc bỏ cuộc. Ngủ cố định rồi hy vọng là cách làm cho ra một script
# lúc chạy được lúc không, và không ai biết vì sao.
wait_http() {
  local name="$1" url="$2" tries="${3:-60}"
  for ((i = 0; i < tries; i++)); do
    # -f -s, KHÔNG -S: mỗi vòng chờ là một lần curl hỏng, và -S in hết ra stderr — mười lăm
    # dòng "Connection refused" trước mỗi dòng "sẵn sàng" làm output không đọc được.
    if curl -fs -o /dev/null --max-time 3 "$url"; then echo "    $name sẵn sàng"; return 0; fi
    sleep 2
  done
  echo "    $name KHÔNG lên sau $((tries * 2))s — xem log" >&2
  return 1
}

if [ "$WITH_INFRA" = 1 ]; then
  echo "==> Hạ tầng Docker"
  # KHÔNG dùng `--wait`: nó chờ MỌI container healthy, nên một healthcheck sai làm hỏng cả lệnh.
  # Ta tự chờ đúng thứ mình cần ở dưới, bằng chính giao thức mà ứng dụng sẽ dùng.
  docker compose -f "$ROOT/deploy/compose/infra.yml" up -d
  wait_http "Keycloak" "http://localhost:8081/realms/nexaticket/.well-known/openid-configuration" 90
  wait_http "RabbitMQ" "http://nexaticket:nexaticket@localhost:15672/api/overview" 60
  echo "==> Topology RabbitMQ"
  "$ROOT/scripts/apply-rabbitmq-topology.sh" >/dev/null
fi

if [ "$SKIP_INSTALL" = 0 ]; then
  # `install`, không phải `compile`. `spring-boot:run` giải dependency `com.nexaticket:starter-*`
  # từ kho ~/.m2 chứ không từ target/classes của reactor: sửa platform/ mà chỉ compile thì service
  # khởi động lên vẫn chạy bản CŨ, và bản sửa đúng trông như sai.
  echo "==> Build + cài platform/* vào ~/.m2 (bỏ qua bằng --skip-install)"
  (cd "$ROOT/backend" && ./mvnw -q -B install -DskipTests)
fi

: > "$PIDFILE"
echo "==> Backend (${#SERVICES[@]} service)"
for entry in "${SERVICES[@]}"; do
  svc="${entry%%:*}"; port="${entry##*:}"
  if curl -fsS -o /dev/null --max-time 2 "http://localhost:$port/actuator/health" 2>/dev/null; then
    echo "    $svc đã chạy sẵn ở :$port — bỏ qua"
    continue
  fi
  # `spring-boot:run` LUÔN fork một JVM con, và không có cách nào tắt.
  #
  # Trước đây ở đây có `-Dspring-boot.run.fork=false` kèm một ghi chú nói app chạy trong chính
  # tiến trình Maven. Đo lại thì không phải: tiến trình giữ cổng 8093 có cha là một java.exe khác
  # nặng 202 MB. Tham số `fork` đã bị bỏ khỏi goal `run` từ Spring Boot 3.0 (nó chỉ còn ở
  # `start`/`stop`), nên Maven lặng lẽ bỏ qua cờ đó — không cảnh báo, không lỗi.
  #
  # Hai hệ quả có thật:
  #
  #   1. Mỗi service tốn HAI JVM: Maven (~200 MB) ngồi chờ, và app (~200 MB) làm việc. Mười hai
  #      service là ~1,2 GB trả cho những tiến trình Maven không làm gì. Trên máy 16 GB đang chạy
  #      cả Docker và bốn dev server Next, đó là phần đẩy Next tới chỗ chết vì hết bộ nhớ.
  #   2. Giết Maven KHÔNG giết app. Đó chính là lý do `dev-down.sh` giết theo CỔNG chứ không theo
  #      PID đã ghi — và lý do đó vẫn đúng, chỉ là vì lý do ngược với ghi chú cũ.
  # payment-service: BẮT BUỘC khai sandbox tường minh ở đây.
  #
  # `nexaticket.payment.sandbox` mặc định FALSE trong application.yml — quên khai biến ở production
  # thì cửa vẫn đóng, vì bật nó là biến hệ thống thành máy phát vé miễn phí. Cái giá là máy phát
  # triển phải tự bật, và chỗ đúng để bật là script chạy dev chứ không phải file cấu hình mà
  # production cũng đọc.
  #
  # Thiếu dòng này thì POST /internal/payment-intents/{orderId}/simulate-transfer trả 409
  # SANDBOX_DISABLED, và không còn cách nào chạy hết luồng mua vé ở local: payOS không có môi
  # trường test, mọi lần thử là tiền thật.
  # Qua `env`, không phải một tiền tố gán biến dựng từ mảng: `"${env_prefix[@]}" ./mvnw` khiến
  # bash coi "PAYMENT_SANDBOX=true" là TÊN LỆNH và chết với "command not found". Phép gán biến
  # phải nằm nguyên văn trong câu lệnh lúc parse, không đến từ một lần khai triển.
  env_prefix=()
  [ "$svc" = "payment-service" ] && env_prefix=(PAYMENT_SANDBOX=true)

  (cd "$ROOT/backend" && env "${env_prefix[@]}" ./mvnw -q -pl "services/$svc" spring-boot:run \
      > "$LOGS/$svc.log" 2>&1) &
  echo "$!:$port:$svc" >> "$PIDFILE"
  echo "    $svc → :$port (log: backend/target/dev-logs/$svc.log)"
done

echo "==> Chờ backend lên"
failed=0
for entry in "${SERVICES[@]}"; do
  svc="${entry%%:*}"; port="${entry##*:}"
  wait_http "$svc" "http://localhost:$port/actuator/health" 90 || failed=1
done

if [ "$WITH_FRONTEND" = 1 ]; then
  # turbo lọc theo tên package. Không khai thì chạy cả bốn — giữ nguyên hành vi cũ.
  filters=""
  if [ -n "$FRONTEND_APPS" ]; then
    for app in ${FRONTEND_APPS//,/ }; do filters="$filters --filter=@nexaticket/$app"; done
    echo "==> Frontend ($FRONTEND_APPS)"
  else
    echo "==> Frontend (4 app Next.js — dùng --with-frontend=web-customer để bật ít hơn)"
  fi
  # shellcheck disable=SC2086
  (cd "$ROOT/frontend" && corepack pnpm dev $filters > "$LOGS/frontend.log" 2>&1) &
  echo "$!:3000:frontend" >> "$PIDFILE"
  wait_http "web-customer" "http://localhost:3000" 90 || failed=1
fi

echo
if [ "$failed" = 0 ]; then
  echo "Xong. Khách 3000 · Tổ chức 3001 · Soát vé 3002 · Nền tảng 3003 · Gateway 8080 · Keycloak 8081"
else
  echo "Có service không lên — đọc log ở backend/target/dev-logs/" >&2
fi
exit "$failed"
