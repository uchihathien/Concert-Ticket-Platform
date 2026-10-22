#!/usr/bin/env bash
# Khởi động backend cho demo chatbox AI từ jar đã build ở worktree /f/nt-e6.
# Issuer = localhost (frontendUrl tunnel của Keycloak đã gỡ) — khớp .env.local của web-customer/web-platform.
#
#   bash demo-up.sh          # 8 service: gateway + 6 lõi + ai-chatbox (đủ cho web-customer, bàn hỗ trợ và chatbox)
#   bash demo-up.sh --all    # thêm ledger, payout, notification, analytics, realtime (cần thêm ~2,5 GB RAM)
#   bash demo-up.sh --kill   # tắt mọi JVM chạy từ jar nt-e6
#
# Kiểm "đã chạy" theo CỔNG ĐANG LẮNG NGHE, không gọi HTTP: lúc máy nghẽn, health check quá hạn làm
# script tưởng chưa có gì và bật trùng 13 JVM (đã xảy ra một lần).
set -uo pipefail
WT=/f/nt-e6/backend/services
LOGS=/f/nt-e6/logs
mkdir -p "$LOGS"

if [ "${1:-}" = "--kill" ]; then
  powershell -NoProfile -Command "\$p=Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ? { \$_.CommandLine -like '*nt-e6*' }; \$p | % { Stop-Process -Id \$_.ProcessId -Force }; 'đã tắt ' + \$p.Count + ' JVM'"
  exit 0
fi

CORE="api-gateway:8080 identity-service:8090 catalog-service:8091 inventory-service:8092 ordering-service:8093 payment-service:8095 ticketing-service:8097 ai-chatbox-service:8101"
EXTRA="ledger-service:8094 payout-service:8096 notification-service:8098 analytics-service:8099 realtime-gateway:8100"
LIST="$CORE"; [ "${1:-}" = "--all" ] && LIST="$CORE $EXTRA"

if [ -f /f/kltnnnn/.env ]; then set -a; . /f/kltnnnn/.env; set +a; fi
unset DB_URL DB_USER DB_PASSWORD SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI
export OIDC_ISSUER=http://localhost:8081/realms/nexaticket
export KEYCLOAK_ISSUER=$OIDC_ISSUER
export PAYMENT_SANDBOX=true
export AI_PROVIDER=local
export EMBEDDING_PROVIDER=local
export OLLAMA_TIMEOUT=900s
export AGENT_ASYNC_TIMEOUT=3600s

listening() { netstat -ano -p tcp 2>/dev/null | grep -qE "[:.]$1 +.*LISTENING"; }

JVM="-Xmx384m -XX:MaxMetaspaceSize=192m"
for entry in $LIST; do
  svc=${entry%%:*}; port=${entry##*:}; mport=$((port + 1000))
  if listening "$port"; then echo "$svc đã chạy (:$port)"; continue; fi
  jar=$(ls "$WT/$svc/target/"*-SNAPSHOT.jar | head -1)
  opts=$JVM; [ "$svc" = inventory-service ] && opts="-Xmx768m -XX:MaxMetaspaceSize=192m"
  (PORT=$port MANAGEMENT_PORT=$mport nohup java $opts -jar "$jar" > "$LOGS/$svc.log" 2>&1 &)
  echo "start $svc :$port (mgmt $mport)"
  # Bật cách nhau vài giây: 13 JVM cùng mở Hikari + Flyway một lúc đã làm Postgres trả "Read timed out".
  sleep 4
done
