#!/usr/bin/env bash
# Dựng những thứ Keycloak production cần mà `--import-realm` KHÔNG làm được — chạy trên máy chủ.
#
#   bash deploy/scripts/bootstrap-keycloak.sh          # idempotent, chạy lại bao nhiêu lần cũng được
#   RESEND=1 bash deploy/scripts/bootstrap-keycloak.sh # gửi lại email đặt mật khẩu cho user đã có
#
# HAI KHOẢNG TRỐNG mà file này lấp:
#
# 1. Realm production không có một tài khoản nào, và không có đường nào để tạo. Realm dev cài sẵn
#    superadmin/organizer/staff/customer với mật khẩu trùng tên — đúng cho local, và KHÔNG được mang
#    lên production. Nhưng bỏ chúng đi mà không thay bằng gì thì SUPER_ADMIN_EMAILS trở nên vô nghĩa:
#    identity-service chỉ CẤP VAI TRÒ khi email đó đăng nhập lần đầu, nó không tạo tài khoản Keycloak.
#    Kết quả là một hệ thống 22 container xanh mà không ai vào được — và không log nào nói ra điều đó.
#
#    Cách tạo: user với requiredAction UPDATE_PASSWORD và KHÔNG có mật khẩu; Keycloak gửi email
#    "đặt mật khẩu" bằng SMTP của realm. Mật khẩu không bao giờ đi qua script, qua SSM, hay qua
#    Parameter Store — người đó tự đặt trong trình duyệt.
#
# 2. `--import-realm` CHỈ chạy khi realm chưa tồn tại. Sửa nexaticket-realm.prod.json sau lần dựng
#    đầu không có tác dụng gì với máy chủ đang chạy — cùng lớp vấn đề với initdb-prod của Postgres.
#    Nên những thiết lập realm mà file JSON khai cũng được áp lại ở đây, để JSON và broker đang chạy
#    không trôi khỏi nhau.
#
# Credential admin đọc từ MÔI TRƯỜNG CỦA CONTAINER, không qua dòng lệnh của host: `docker exec ... sh -c`
# giãn biến bên trong container, nên mật khẩu không xuất hiện trong `ps`, trong log SSM, hay ở đây.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/compose/.env}"
KC="${KC:-nexaticket-prod-keycloak-1}"
REALM=nexaticket

[ -s "$ENV_FILE" ] || { echo "Không có $ENV_FILE" >&2; exit 1; }
val() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }
EMAILS="$(val SUPER_ADMIN_EMAILS)"
PLATFORM_URL="$(val WEB_PLATFORM_URL)"
KC_URL="$(val KEYCLOAK_HOSTNAME)"
[ -n "$EMAILS" ] || { echo "Thiếu SUPER_ADMIN_EMAILS trong $ENV_FILE" >&2; exit 1; }

kc() { docker exec "$KC" /opt/keycloak/bin/kcadm.sh "$@"; }

echo "==> Đăng nhập admin Keycloak (credential nằm trong container)"
docker exec "$KC" sh -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 \
  --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' \
  || { echo "Không đăng nhập được admin. Keycloak có đang chạy không? docker logs $KC" >&2; exit 1; }

echo "==> Thiết lập realm phải khớp nexaticket-realm.prod.json"
# registrationAllowed: khách tự tạo tài khoản ở trang bán vé. Callback `keycloak-register` đã nằm sẵn
# trong redirectUris của web-customer, nên thiếu cờ này là một nửa tính năng — Keycloak trả trang lỗi
# thay vì form đăng ký (deploy/keycloak/README.md). Ba app còn lại không có nút đăng ký, nên không mở
# thêm cửa nào vào khu quản trị.
kc update "realms/$REALM" -s registrationAllowed=true -s registrationEmailAsUsername=true \
  -s loginWithEmailAllowed=true -s resetPasswordAllowed=true
echo "    registrationAllowed=true"

echo "==> Tài khoản SUPER_ADMIN"
fails=0
IFS=',' read -r -a list <<< "$EMAILS"
for raw in "${list[@]}"; do
  email="$(printf '%s' "$raw" | tr -d '[:space:]')"
  [ -n "$email" ] || continue

  id="$(kc get users -r "$REALM" -q "email=$email" -q exact=true --fields id --format csv --noquotes 2>/dev/null | head -1 || true)"
  created=0
  if [ -z "$id" ]; then
    id="$(kc create users -r "$REALM" -i \
            -s "username=$email" -s "email=$email" -s enabled=true -s emailVerified=true \
            -s 'requiredActions=["UPDATE_PASSWORD"]')"
    created=1
    echo "    tạo mới  $email  ($id)"
  else
    echo "    đã có    $email  ($id)"
  fi

  # Gửi email đặt mật khẩu khi vừa tạo, hoặc khi được yêu cầu gửi lại. Link sống 12 giờ (mặc định
  # của Keycloak); hết hạn thì chạy lại với RESEND=1. Sau khi đặt xong, Keycloak đưa về trang đăng
  # nhập của app quản trị tổng — nơi SUPER_ADMIN tạo tổ chức đầu tiên.
  if [ "$created" = 1 ] || [ "${RESEND:-0}" = 1 ]; then
    if kc update "users/$id/execute-actions-email?client_id=web-platform&redirect_uri=$PLATFORM_URL/login" \
         -r "$REALM" -n -b '["UPDATE_PASSWORD"]' 2>/tmp/kc-mail.err; then
      echo "    đã gửi email đặt mật khẩu tới $email"
    else
      fails=$((fails + 1))
      echo "    KHÔNG gửi được email tới $email:" >&2
      sed 's/^/      /' /tmp/kc-mail.err >&2
    fi
  fi
done

if [ "$fails" -gt 0 ]; then
  cat >&2 <<HINT

  Tài khoản ĐÃ TỒN TẠI, chỉ email không đi. Gần như luôn là SMTP: sai SMTP_HOST, App Password của
  Gmail còn dấu cách, hoặc Gmail chặn. Kiểm bằng: docker logs $KC 2>&1 | grep -i -A3 'mail\|smtp'
  Sửa SMTP_* trong Parameter Store rồi: RESEND=1 bash deploy/scripts/bootstrap-keycloak.sh

  Đường vòng không cần email — đặt mật khẩu bằng tay trong console quản trị Keycloak:
    $KC_URL/admin/   (user KEYCLOAK_ADMIN_USER, mật khẩu KEYCLOAK_ADMIN_PASSWORD trong .env)
    Realm nexaticket -> Users -> $email -> Credentials -> Set password
HINT
  exit 1
fi
echo "==> Xong. Mở email, đặt mật khẩu, rồi đăng nhập ở $PLATFORM_URL"
