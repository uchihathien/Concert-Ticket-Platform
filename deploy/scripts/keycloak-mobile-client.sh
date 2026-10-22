#!/usr/bin/env bash
# Tạo hoặc cập nhật một client công khai (PKCE) cho app mobile trong realm ĐANG CHẠY.
#
# Vì sao cần: Keycloak chỉ đọc deploy/keycloak/nexaticket-realm.json lúc import realm LẦN ĐẦU. Realm
# đã có thì sửa file JSON không đổi được gì — phải đi qua Admin API (kcadm.sh trong container).
#
#   deploy/scripts/keycloak-mobile-client.sh mobile-staff \
#       --name "NexaTicket Staff — Mobile" \
#       --redirect 'nexaticket-staff://auth' --redirect 'exp://*' \
#       --direct-grants on --brute-force on
#
# Tham số:
#   <clientId>              bắt buộc, vd mobile-staff
#   --name <tên>            tên hiển thị (chỉ dùng khi tạo mới)
#   --redirect <uri>        THÊM một redirect URI (lặp được). Không xoá URI đã có.
#   --direct-grants on|off  bật/tắt grant `password` (đăng nhập bằng mã PIN của nhân viên)
#   --brute-force on        bật chống dò mật khẩu cho CẢ realm (khoá tạm sau 10 lần sai) — nên bật
#                           khi có tài khoản dùng mã PIN 6 số
#   --container <tên>       container Keycloak (mặc định nexaticket-infra-keycloak-1)
#   --realm <tên>           realm (mặc định nexaticket)
#
# Không có mật khẩu nào trong script: tài khoản quản trị Keycloak lấy từ biến môi trường của chính
# container. Chạy lại bao nhiêu lần cũng được.

set -euo pipefail

CLIENT_ID="${1:-}"
[ -n "$CLIENT_ID" ] && [ "${CLIENT_ID#--}" = "$CLIENT_ID" ] || {
  sed -n '2,24p' "$0"
  exit 1
}
shift

NAME="$CLIENT_ID"
REDIRECTS=()
DIRECT_GRANTS=""
BRUTE_FORCE=""
CONTAINER="nexaticket-infra-keycloak-1"
REALM="nexaticket"

while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="$2"; shift 2 ;;
    --redirect) REDIRECTS+=("$2"); shift 2 ;;
    --direct-grants) DIRECT_GRANTS="$2"; shift 2 ;;
    --brute-force) BRUTE_FORCE="$2"; shift 2 ;;
    --container) CONTAINER="$2"; shift 2 ;;
    --realm) REALM="$2"; shift 2 ;;
    *) echo "Tham số không hiểu: $1"; exit 1 ;;
  esac
done

case "$DIRECT_GRANTS" in "" | on | off) ;; *) echo "--direct-grants nhận on|off"; exit 1 ;; esac

# Danh sách redirect truyền vào container dưới dạng một biến, mỗi URI một dòng.
REDIRECT_LINES=$(printf '%s\n' "${REDIRECTS[@]+"${REDIRECTS[@]}"}")

docker exec \
  -e CLIENT_ID="$CLIENT_ID" -e NAME="$NAME" -e REALM="$REALM" \
  -e REDIRECT_LINES="$REDIRECT_LINES" -e DIRECT_GRANTS="$DIRECT_GRANTS" -e BRUTE_FORCE="$BRUTE_FORCE" \
  "$CONTAINER" bash -c '
  set -euo pipefail
  KCADM=/opt/keycloak/bin/kcadm.sh
  $KCADM config credentials --server "http://localhost:${KC_HTTP_PORT:-8080}" --realm master \
    --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1

  ID=$($KCADM get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id --format csv --noquotes)
  if [ -z "$ID" ]; then
    $KCADM create clients -r "$REALM" \
      -s "clientId=$CLIENT_ID" -s "name=$NAME" -s enabled=true -s publicClient=true \
      -s standardFlowEnabled=true -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=false \
      -s "redirectUris=[]" -s "webOrigins=[]" \
      -s "attributes.\"pkce.code.challenge.method\"=S256"
    ID=$($KCADM get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id --format csv --noquotes)
    echo "Đã tạo client $CLIENT_ID"
  else
    echo "Client $CLIENT_ID đã có — chỉ cập nhật"
  fi

  # Chỉ THÊM redirect URI còn thiếu; không xoá URI cũ (có thể là địa chỉ Expo của người khác).
  while IFS= read -r R; do
    [ -n "$R" ] || continue
    if $KCADM get "clients/$ID" -r "$REALM" --fields redirectUris | grep -qF "\"$R\""; then
      echo "  redirect đã có: $R"
    else
      $KCADM update "clients/$ID" -r "$REALM" -s "redirectUris+=$R"
      echo "  đã thêm redirect: $R"
    fi
  done <<<"$REDIRECT_LINES"

  if [ "$DIRECT_GRANTS" = on ]; then
    $KCADM update "clients/$ID" -r "$REALM" -s directAccessGrantsEnabled=true
    echo "  đã BẬT grant password (đăng nhập bằng mã PIN)"
  elif [ "$DIRECT_GRANTS" = off ]; then
    $KCADM update "clients/$ID" -r "$REALM" -s directAccessGrantsEnabled=false
    echo "  đã TẮT grant password"
  fi

  if [ "$BRUTE_FORCE" = on ]; then
    $KCADM update "realms/$REALM" -s bruteForceProtected=true -s failureFactor=10
    echo "  đã bật chống dò mật khẩu cho realm $REALM (khoá tạm sau 10 lần sai)"
  fi

  $KCADM get "clients/$ID" -r "$REALM" --fields clientId,publicClient,standardFlowEnabled,directAccessGrantsEnabled,redirectUris
'
