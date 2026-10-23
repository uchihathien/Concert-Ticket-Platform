#!/usr/bin/env bash
# Khởi động backend cho demo chatbox AI từ jar đã build — chạy được trên macOS, Linux và Git Bash (Windows).
# Issuer = localhost (frontendUrl tunnel của Keycloak đã gỡ) — khớp .env.local của web-customer/web-platform.
#
#   bash scripts/demo-chatbox-up.sh          # 8 service: gateway + 6 lõi + ai-chatbox (đủ cho web-customer, bàn hỗ trợ và chatbox)
#   bash scripts/demo-chatbox-up.sh --all    # thêm ledger, payout, notification, analytics, realtime (cần thêm ~2,5 GB RAM)
#   bash scripts/demo-chatbox-up.sh --kill   # tắt mọi JVM đã bật từ jar của repo này
#
# Jar lấy ở backend/services/*/target — build trước bằng:  cd backend && ./mvnw -DskipTests -Dspotless.check.skip package
# Kiểm "đã chạy" theo CỔNG ĐANG LẮNG NGHE, không gọi HTTP: lúc máy nghẽn, health check quá hạn làm
# script tưởng chưa có gì và bật trùng 13 JVM (đã xảy ra một lần).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WT="$ROOT/backend/services"
LOGS="$ROOT/logs"
mkdir -p "$LOGS"

case "$(uname -s)" in
  Darwin* | Linux*) ON_WINDOWS=0 ;;
  *) ON_WINDOWS=1 ;;
esac

if [ "${1:-}" = "--kill" ]; then
  if [ "$ON_WINDOWS" = 1 ]; then
    powershell -NoProfile -Command "\$p=Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ? { \$_.CommandLine -like '*backend?services*SNAPSHOT.jar*' }; \$p | % { Stop-Process -Id \$_.ProcessId -Force }; 'đã tắt ' + @(\$p).Count + ' JVM'"
  else
    pkill -f "$WT/.*-SNAPSHOT\.jar" && echo "đã tắt các JVM của $WT" || echo "không có JVM nào đang chạy"
  fi
  exit 0
fi

CORE="api-gateway:8080 identity-service:8090 catalog-service:8091 inventory-service:8092 ordering-service:8093 payment-service:8095 ticketing-service:8097 ai-chatbox-service:8101"
EXTRA="ledger-service:8094 payout-service:8096 notification-service:8098 analytics-service:8099 realtime-gateway:8100"
LIST="$CORE"; [ "${1:-}" = "--all" ] && LIST="$CORE $EXTRA"

# .env ở gốc repo (khoá payOS, …) là tuỳ chọn; demo chatbox không cần.
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
unset DB_URL DB_USER DB_PASSWORD SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI
export OIDC_ISSUER=http://localhost:8081/realms/nexaticket
export KEYCLOAK_ISSUER=$OIDC_ISSUER
export PAYMENT_SANDBOX=true
export AI_PROVIDER=${AI_PROVIDER:-local}
export EMBEDDING_PROVIDER=${EMBEDDING_PROVIDER:-local}
# Ollama chạy ở máy thật (macOS: `brew install ollama`), không trong Docker — mặc định localhost:11434.
export OLLAMA_TIMEOUT=${OLLAMA_TIMEOUT:-900s}
export AGENT_ASYNC_TIMEOUT=${AGENT_ASYNC_TIMEOUT:-3600s}

listening() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$1" -sTCP:LISTEN -t >/dev/null 2>&1
  elif [ "$ON_WINDOWS" = 1 ]; then
    netstat -ano -p tcp 2>/dev/null | grep -qE "[:.]$1 +.*LISTENING"
  else
    ss -ltn 2>/dev/null | grep -qE ":$1 "
  fi
}

JVM="-Xmx384m -XX:MaxMetaspaceSize=192m"
for entry in $LIST; do
  svc=${entry%%:*}; port=${entry##*:}; mport=$((port + 1000))
  if listening "$port"; then echo "$svc đã chạy (:$port)"; continue; fi
  jar=$(ls "$WT/$svc/target/"*-SNAPSHOT.jar 2>/dev/null | head -1)
  if [ -z "$jar" ]; then echo "THIẾU jar của $svc — build trước: cd backend && ./mvnw -DskipTests -Dspotless.check.skip package"; continue; fi
  opts=$JVM; [ "$svc" = inventory-service ] && opts="-Xmx768m -XX:MaxMetaspaceSize=192m"
  (PORT=$port MANAGEMENT_PORT=$mport nohup java $opts -jar "$jar" > "$LOGS/$svc.log" 2>&1 &)
  echo "start $svc :$port (mgmt $mport)"
  # Bật cách nhau vài giây: 13 JVM cùng mở Hikari + Flyway một lúc đã làm Postgres trả "Read timed out".
  sleep 4
done
