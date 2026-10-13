#!/usr/bin/env bash
# Chuẩn bị môi trường DEV cho luồng mời nhân viên soát vé. Chạy lại bao nhiêu lần cũng được.
#
#   ./scripts/dev-staff-setup.sh              # cả hai bước
#   ./scripts/dev-staff-setup.sh keycloak     # chỉ bật SMTP, hạn link, hành động đặt mật khẩu của realm dev
#   ./scripts/dev-staff-setup.sh assign       # chỉ gán nhân viên hiện có vào sự kiện hiện có
#
# CHỈ dành cho máy phát triển: nó gọi thẳng vào hai container `nexaticket-infra-*` của
# deploy/compose/infra.yml và không đụng tới bất kỳ cấu hình production nào.
#
# Không có mật khẩu hay bí mật nào trong script: tài khoản quản trị Keycloak lấy từ chính biến môi
# trường của container.
set -euo pipefail

KEYCLOAK="${KEYCLOAK_CONTAINER:-nexaticket-infra-keycloak-1}"
POSTGRES="${POSTGRES_CONTAINER:-nexaticket-infra-postgres-1}"
REALM=nexaticket
# 72 giờ: hạn của link đặt mật khẩu trong thư mời nhân viên.
INVITE_LIFESPAN_SECONDS=259200

kc() { docker exec -i "$KEYCLOAK" /opt/keycloak/bin/kcadm.sh "$@"; }

kc_login() {
  # Tài khoản quản trị lấy từ chính biến môi trường của container, không chép vào đây.
  docker exec "$KEYCLOAK" sh -c '/opt/keycloak/bin/kcadm.sh config credentials \
      --server http://localhost:8081 --realm master \
      --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' >/dev/null
}

setup_keycloak() {
  echo "==> Keycloak realm dev: SMTP -> Mailpit, link mời sống 72 giờ"
  kc_login
  # `mailhog` là tên service của Mailpit trong mạng docker — Keycloak gửi thư từ trong container,
  # nên localhost ở đây sẽ là chính nó. Chỉ ghi đúng hai thuộc tính này; phần còn lại của realm
  # (frontendUrl, client, người dùng) giữ nguyên.
  kc update "realms/$REALM" \
    -s 'smtpServer.host=mailhog' -s 'smtpServer.port=1025' \
    -s 'smtpServer.from=no-reply@nexaticket.local' -s 'smtpServer.fromDisplayName=NexaTicket' \
    -s 'smtpServer.ssl=false' -s 'smtpServer.starttls=false' -s 'smtpServer.auth=false' \
    -s "actionTokenGeneratedByAdminLifespan=$INVITE_LIFESPAN_SECONDS"

  # Thư mời bắt nhân viên đặt mật khẩu và xác minh email. Hai hành động đó phải được ĐĂNG KÝ trong
  # realm; realm chỉ khai CONFIGURE_TOTP thì Keycloak lặng lẽ bỏ qua chúng — người dùng bấm link,
  # thấy "tài khoản đã được cập nhật", và vẫn không có mật khẩu.
  local registered
  registered="$(kc get authentication/required-actions -r "$REALM" --fields alias --format csv --noquotes)"
  register_action() { # register_action <providerId> <tên hiển thị>
    if printf '%s\n' "$registered" | grep -qx "$1"; then
      echo "    hành động $1 đã đăng ký"
    else
      kc create authentication/register-required-action -r "$REALM" -s "providerId=$1" -s "name=$2" >/dev/null
      echo "    đã đăng ký hành động $1"
    fi
  }
  register_action UPDATE_PASSWORD "Update Password"
  register_action VERIFY_EMAIL "Verify Email"

  echo "    xong. Hộp thư dev: http://localhost:8025"
}

psql_at() { docker exec -i "$POSTGRES" psql -v ON_ERROR_STOP=1 -U "$1" -d "$2" -At "${@:3}"; }

assign_existing_staff() {
  echo "==> Gán nhân viên soát vé HIỆN CÓ vào mọi sự kiện HIỆN CÓ của tổ chức"
  if ! psql_at ticketing ticketing_db -c "SELECT 1 FROM checkin_assignments LIMIT 1" >/dev/null 2>&1; then
    echo "    bảng checkin_assignments chưa có — khởi động ticketing-service một lần để chạy migration, rồi chạy lại." >&2
    return 1
  fi
  # Ba database riêng nên không JOIN được: đọc hai phía rồi ghép ở đây. Sự kiện tạo SAU lần chạy
  # này không tự được gán — đó là chủ đích, ban tổ chức gán tay trong mục Nhân sự.
  local staff events
  staff="$(psql_at identity identity_db -F ' ' -c \
    "SELECT organization_id, user_id FROM organization_members WHERE role = 'CHECKIN_STAFF'")"
  events="$(psql_at catalog catalog_db -F ' ' -c "SELECT organization_id, id FROM events")"

  local sql="" count=0
  while read -r org user; do
    [ -n "${org:-}" ] || continue
    while read -r event_org event; do
      [ "${event_org:-}" = "$org" ] || continue
      sql+="INSERT INTO checkin_assignments (organization_id, user_id, event_id) VALUES ('$org', '$user', '$event') ON CONFLICT DO NOTHING;"$'\n'
      count=$((count + 1))
    done <<<"$events"
  done <<<"$staff"

  if [ -z "$sql" ]; then
    echo "    không có cặp nhân viên / sự kiện nào để gán"
    return 0
  fi
  local inserted
  inserted="$(printf '%s' "$sql" | psql_at ticketing ticketing_db | grep -c '^INSERT 0 1$' || true)"
  echo "    $count cặp đã xét, $inserted cặp mới được gán (phần còn lại đã có từ trước)"
}

case "${1:-all}" in
  keycloak) setup_keycloak ;;
  assign)   assign_existing_staff ;;
  all)      setup_keycloak; assign_existing_staff ;;
  -h|--help) sed -n '2,11p' "$0" ;;
  *) echo "Tham số lạ: $1" >&2; exit 2 ;;
esac
