#!/usr/bin/env bash
# Đặt theme đăng nhập NexaTicket cho realm ĐANG CHẠY. CHẠY TRÊN MÁY CÓ CONTAINER KEYCLOAK.
#
#   bash deploy/scripts/keycloak-set-theme.sh                                   # production
#   KC=nexaticket-infra-keycloak-1 bash deploy/scripts/keycloak-set-theme.sh    # máy dev
#
# Làm bốn việc, đều idempotent (chạy lại bao nhiêu lần cũng ra cùng một kết quả):
#   1. Realm: `loginTheme=nexaticket`.
#   2. Realm: bật đa ngôn ngữ, CHỈ hỗ trợ vi, mặc định vi (Keycloak 26.0 không có sẵn tiếng Việt —
#      bản dịch nằm trong theme).
#
#      Vì sao không kèm `en`: Keycloak chọn ngôn ngữ theo Accept-Language của trình duyệt TRƯỚC
#      ngôn ngữ mặc định. Link "Quên mật khẩu?" và link trong email mời không mang được
#      `ui_locales`, nên máy để tiếng Anh sẽ thấy trang tiếng Anh. Muốn mở lại tiếng Anh thì đặt
#      SUPPORTED_LOCALES='["vi","en"]'.
#   3. Client: gán `login_theme=nexaticket-lime` cho các client tông xanh chanh. Client nào chưa có
#      trên realm thì BỎ QUA và cảnh báo — script này không tạo client.
#   4. In lại cấu hình để đối chiếu.
#
# VÌ SAO CẦN SCRIPT CHỨ KHÔNG CHỈ SỬA FILE REALM. `--import-realm` CHỈ chạy khi realm chưa tồn tại,
# nên `loginTheme` mới thêm vào nexaticket-realm.prod.json không tới được máy đang chạy — cùng lý do
# với keycloak-mobile-client.sh. Riêng `mobile-staff` không có trong file realm nào (client này được
# tạo tay trên máy chạy), nên chỉ script này gán được theme cho nó.
#
# Thư mục theme phải được mount sẵn vào container (deploy/compose/*.yml). Thiếu mount thì Keycloak
# vẫn nhận `loginTheme` nhưng rơi về theme mặc định khi render — script kiểm tra trước việc đó.
set -euo pipefail

KC="${KC:-nexaticket-prod-keycloak-1}"
REALM="${REALM:-nexaticket}"
LOGIN_THEME="${LOGIN_THEME:-nexaticket}"
LIME_THEME="${LIME_THEME:-nexaticket-lime}"
SUPPORTED_LOCALES="${SUPPORTED_LOCALES:-[\"vi\"]}"
# Ba client dùng tông xanh chanh: app mobile khách, app mobile nhân viên/ban tổ chức, web Tổ chức.
LIME_CLIENTS="${LIME_CLIENTS:-mobile-customer mobile-staff web-admin}"

kc() { docker exec "$KC" /opt/keycloak/bin/kcadm.sh "$@"; }

echo "==> Kiểm tra thư mục theme trong container $KC"
for theme in "$LOGIN_THEME" "$LIME_THEME"; do
  if ! docker exec "$KC" test -f "/opt/keycloak/themes/$theme/login/theme.properties"; then
    echo "Không thấy theme '$theme' trong /opt/keycloak/themes. Đã mount deploy/keycloak/themes và" >&2
    echo "tạo lại container chưa? (docker compose ... up -d --force-recreate keycloak)" >&2
    exit 1
  fi
done
echo "    có đủ $LOGIN_THEME và $LIME_THEME"

echo "==> Đăng nhập admin Keycloak"
# Production nghe ở 8080 trong container, bản dev đặt KC_HTTP_PORT=8081. Thử lần lượt thay vì bắt
# người chạy phải nhớ cổng của từng môi trường.
logged_in=""
for port in 8080 8081; do
  if docker exec -e KC_PORT="$port" "$KC" sh -c '/opt/keycloak/bin/kcadm.sh config credentials \
      --server "http://localhost:$KC_PORT" --realm master \
      --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' >/dev/null 2>&1; then
    logged_in="$port"
    break
  fi
done
[ -n "$logged_in" ] || { echo "Không đăng nhập được admin Keycloak. Container $KC có chạy không?" >&2; exit 1; }
echo "    qua cổng $logged_in"

echo "==> Realm $REALM: loginTheme=$LOGIN_THEME, tiếng Việt mặc định"
kc update "realms/$REALM" \
  -s "loginTheme=$LOGIN_THEME" \
  -s internationalizationEnabled=true \
  -s "supportedLocales=$SUPPORTED_LOCALES" \
  -s defaultLocale=vi

echo "==> Client tông xanh chanh: login_theme=$LIME_THEME"
for client_id in $LIME_CLIENTS; do
  id="$(kc get clients -r "$REALM" -q "clientId=$client_id" --fields id --format csv --noquotes 2>/dev/null | head -1 || true)"
  if [ -z "$id" ]; then
    echo "    CẢNH BÁO: chưa có client $client_id trên realm $REALM — bỏ qua, không tạo mới" >&2
    continue
  fi
  # `kcadm update` đọc bản hiện tại rồi trộn thuộc tính vào, nên các attribute khác của client
  # (PKCE, post logout redirect…) giữ nguyên.
  kc update "clients/$id" -r "$REALM" -s "attributes.login_theme=$LIME_THEME"
  echo "    $client_id ($id)"
done

echo "==> Cấu hình hiện tại:"
kc get "realms/$REALM" --fields loginTheme,internationalizationEnabled,supportedLocales,defaultLocale --format json | sed 's/^/    /'; echo
for client_id in $LIME_CLIENTS; do
  # Cú pháp lọc trường lồng của kcadm là `attributes(login_theme)`, không phải dấu chấm.
  kc get clients -r "$REALM" -q "clientId=$client_id" --fields 'clientId,attributes(login_theme)' --format json 2>/dev/null \
    | tr -d '\n ' | sed 's/^/    /'; echo
done
