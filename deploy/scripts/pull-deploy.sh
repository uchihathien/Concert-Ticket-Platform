#!/usr/bin/env bash
# Deploy kiểu KÉO: máy chủ tự lấy bản đã duyệt, thay cho GitHub Actions SSH vào.
#
#   systemctl start nexaticket-deploy      # chạy ngay một lần (timer gọi 2 phút một lần)
#   journalctl -u nexaticket-deploy -f     # xem nó làm gì
#
# <h3>Vì sao kéo chứ không đẩy</h3>
#
# Bước Deploy cũ trong release.yml SSH từ runner vào EC2. Trên tài khoản này nó không thể chạy:
# Service Control Policy của Organization chặn OIDC, nên runner không có credential AWS để mở cổng 22
# cho chính nó, và Security Group chỉ mở 22 cho IP đã chạy aws-bootstrap.sh. Kéo thì chỉ cần máy chủ
# đi RA ngoài (github.com, ghcr.io) — không cổng nào phải mở, không khoá nào phải nằm trên GitHub.
#
# <h3>"Đã duyệt" nghĩa là gì</h3>
#
# Tag git `production`. Job `promote` của release.yml dời nó tới commit vừa build, SAU KHI 13 ảnh đã
# lên GHCR và (nếu environment `production` có người duyệt) sau khi họ bấm duyệt. Nên tag luôn trỏ
# vào một commit có ảnh. Commit mới trên main mà chưa có tag thì máy chủ không đụng tới.
#
# <h3>Chạy dưới `ubuntu`, không phải root</h3>
#
# /srv/nexaticket thuộc `ubuntu`. git dưới root trong cây đó dừng vì "dubious ownership", và cấp ngoại
# lệ cho root là mở đúng cái lỗ mà kiểm tra ấy được thêm vào để đóng. `ubuntu` có sẵn thông tin đăng
# nhập ghcr.io (server-setup.sh chép sang) và thuộc nhóm docker.
set -euo pipefail

ROOT=/srv/nexaticket
STATE_DIR="${STATE_DIRECTORY:-/var/lib/nexaticket-deploy}"   # systemd đặt qua StateDirectory=
DEPLOYED_FILE="$STATE_DIR/deployed"   # SHA đang chạy, chỉ ghi khi smoke xanh
FAILED_FILE="$STATE_DIR/failed"       # SHA deploy hỏng gần nhất — không thử lại nó mỗi 2 phút
RELEASE_ENV="$ROOT/deploy/compose/release.env"
TAG=production

log() { printf '%s %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }

# Mọi thứ nằm trong main(), gọi ở dòng cuối.
#
# Script này `git checkout` chính cây chứa nó. bash đọc file từng đoạn trong lúc chạy, nên nếu
# checkout đổi nội dung file giữa chừng thì phần còn lại được đọc từ bản MỚI, lệch dòng — lỗi khó
# hiểu nhất có thể có. Một hàm thì được đọc trọn trước khi chạy; bản mới có hiệu lực ở lần sau.
main() {
  install -d "$STATE_DIR"
  exec 9>"$STATE_DIR/lock"
  # systemd không chạy chồng hai lần cùng một unit, nhưng người chạy tay thì có thể.
  if ! flock -n 9; then
    log "Đang có một lần deploy khác chạy — bỏ qua."
    return 0
  fi

  cd "$ROOT"
  if ! git fetch --quiet --force origin "+refs/tags/$TAG:refs/tags/$TAG" 2>/dev/null; then
    # Tag chưa tồn tại (chưa promote lần nào) hoặc mất mạng: cả hai đều là "chưa có gì để làm".
    log "Chưa lấy được tag $TAG từ origin — chưa có bản nào được duyệt, hoặc không ra được mạng."
    return 0
  fi

  local sha deployed failed frontend_tag
  sha=$(git rev-parse "refs/tags/$TAG^{commit}")
  deployed=$(cat "$DEPLOYED_FILE" 2>/dev/null || true)
  failed=$(cat "$FAILED_FILE" 2>/dev/null || true)

  if [ "$sha" = "$deployed" ]; then
    return 0   # im lặng: đây là trường hợp của 99% số lần timer chạy
  fi
  if [ "$sha" = "$failed" ] && [ "${FORCE:-0}" != 1 ]; then
    log "Bỏ qua $sha: lần trước deploy hỏng. Sửa rồi promote commit khác, hoặc chạy lại với FORCE=1."
    return 0
  fi

  # FRONTEND_TAG đi kèm trong lời nhắn của tag (job promote ghi `frontend_tag=...`). Kiểm dạng vì nó
  # đi thẳng vào tên ảnh.
  frontend_tag=$(git cat-file -p "refs/tags/$TAG" | sed -n 's/^frontend_tag=//p' | head -1)
  [[ "$frontend_tag" =~ ^[A-Za-z0-9_.-]+$ ]] || frontend_tag=main

  log "Deploy ${sha:0:7} (frontend $frontend_tag), đang chạy: ${deployed:0:7}"
  if deploy "$sha" "$frontend_tag"; then
    echo "$sha" > "$DEPLOYED_FILE"
    rm -f "$FAILED_FILE"
    log "Xong ${sha:0:7}."
  else
    echo "$sha" > "$FAILED_FILE"
    # KHÔNG tự quay về bản cũ. Migration Flyway của bản mới có thể đã chạy, và mã cũ trên schema mới
    # là một trạng thái chưa ai kiểm. Quay lui là quyết định của người: promote commit cũ hoặc sửa tới.
    log "HỎNG ${sha:0:7}. Cụm có thể đang nửa cũ nửa mới — xem log ở trên."
    return 1
  fi
}

# Mỗi lệnh có `|| return 1` riêng, KHÔNG dựa vào `set -e`: hàm này được gọi trong `if deploy ...`,
# và bash tắt errexit cho mọi thứ chạy bên trong điều kiện của `if` — kể cả thân hàm. Dựa vào nó thì
# `pull` hỏng vẫn đi tiếp, và kết quả cuối chỉ còn là mã thoát của smoke.sh.
deploy() {
  local sha=$1 frontend_tag=$2

  # Checkout ĐÚNG commit đã sinh ra ảnh: prod.yml và smoke.sh phải khớp với ảnh đang chạy.
  git checkout --quiet --detach "$sha" || return 1

  # Ghi tag đã duyệt ra file riêng, không vào .env: .env được `nexa-env` sinh lại từ Parameter Store
  # mỗi lần khởi động, với BACKEND_TAG=main. nexaticket.service đọc file này SAU .env, nên reboot
  # bật lại đúng bản đã duyệt chứ không phải ảnh :main mới nhất (có thể chưa ai duyệt).
  local tmp="$RELEASE_ENV.tmp"
  printf 'BACKEND_TAG=%s\nFRONTEND_TAG=%s\n' "$sha" "$frontend_tag" > "$tmp" || return 1
  mv "$tmp" "$RELEASE_ENV" || return 1

  local compose=(docker compose -f deploy/compose/prod.yml
                 --env-file deploy/compose/.env --env-file "$RELEASE_ENV")

  # --ignore-buildable: rabbitmq không có trên GHCR — ảnh của nó dựng tại chỗ để bật plugin delayed
  # message (ADR-1009). Thiếu cờ này thì `pull` chết ở đúng ảnh đó và các ảnh còn lại không được kéo.
  "${compose[@]}" pull --quiet --ignore-buildable || return 1
  "${compose[@]}" build rabbitmq || return 1
  # Hạ tầng trước, rồi topology, rồi mới tới các service — `service_healthy` chỉ bảo đảm broker
  # CHẠY, không bảo đảm nó có queue, và năm service có @RabbitListener crash-loop khi thiếu queue.
  "${compose[@]}" up -d --no-build --wait rabbitmq postgres redis || return 1
  bash deploy/scripts/apply-topology.sh || return 1
  "${compose[@]}" up -d --no-build --wait --remove-orphans || return 1
  bash deploy/scripts/smoke.sh || return 1
}

main "$@"
exit
