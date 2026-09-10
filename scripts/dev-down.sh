#!/usr/bin/env bash
# Dừng những gì `dev-up.sh` đã bật.
#
#   ./scripts/dev-down.sh            # backend + frontend
#   ./scripts/dev-down.sh --infra    # dừng luôn 5 container hạ tầng
#   ./scripts/dev-down.sh --prune    # dọn container Testcontainers bỏ quên sau `mvnw verify`
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIDFILE="$ROOT/backend/target/dev-logs/dev-up.pids"

# Mọi cổng dev-up có thể đã chiếm — kể cả khi PIDFILE mất hoặc bạn bật service bằng tay.
PORTS=(8080 8090 8091 8092 8093 8094 8095 8096 8097 8098 8099 8100 3000 3001 3002 3003)

WITH_INFRA=0; PRUNE=0
for arg in "$@"; do
  case "$arg" in
    --infra) WITH_INFRA=1 ;;
    --prune) PRUNE=1 ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    *) echo "Tham số lạ: $arg" >&2; exit 2 ;;
  esac
done

# Giết theo CỔNG, không chỉ theo PID đã ghi.
#
# PIDFILE là đường nhanh, nhưng nó không đủ một mình: Maven và pnpm đều có thể fork thêm tiến
# trình con, và tiến trình con thì giữ cổng kể cả khi cha đã chết. Triệu chứng nếu bỏ qua bước
# này rất khó chịu — lần khởi động sau gọi vào instance CŨ, health trả 200, và bản sửa đúng bị
# kết luận là sai.
echo "==> Dừng tiến trình đang giữ cổng dev"
killed=0
for port in "${PORTS[@]}"; do
  pids="$(powershell -NoProfile -Command "
    Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
      Select-Object -ExpandProperty OwningProcess -Unique" 2>/dev/null | tr -d '\r' | grep -E '^[0-9]+$' || true)"
  for pid in $pids; do
    # /T để cuốn theo cả cây con, /F vì Next và Maven đều không thoát khi chỉ được yêu cầu lịch sự.
    taskkill //PID "$pid" //T //F >/dev/null 2>&1 || true
    echo "    :$port — PID $pid"
    killed=$((killed + 1))
  done
done
[ "$killed" = 0 ] && echo "    (không có gì đang chạy)"
[ -f "$PIDFILE" ] && : > "$PIDFILE"

if [ "$PRUNE" = 1 ]; then
  echo "==> Dọn container Testcontainers bỏ quên"
  ids="$(docker ps -q --filter label=org.testcontainers=true || true)"
  if [ -n "$ids" ]; then docker rm -f $ids >/dev/null; echo "    đã xoá $(echo "$ids" | wc -l) container"; else echo "    (sạch)"; fi
fi

if [ "$WITH_INFRA" = 1 ]; then
  echo "==> Dừng hạ tầng"
  # `stop`, KHÔNG phải `down`: `down` xoá network và — nếu lỡ thêm -v — xoá cả volume, tức là mất
  # database lẫn realm Keycloak đã import. Bật lại bằng dev-up.sh là chạy tiếp chỗ cũ.
  docker compose -f "$ROOT/deploy/compose/infra.yml" stop
fi

echo "Xong."
