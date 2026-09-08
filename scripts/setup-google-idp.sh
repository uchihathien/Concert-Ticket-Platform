#!/usr/bin/env bash
# Khai Google làm identity provider của realm nexaticket.
# Idempotent: chạy lại nhiều lần không sao, lần sau chỉ cập nhật cấu hình.
#
#   GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=... ./scripts/setup-google-idp.sh
#
# Không nhét vào nexaticket-realm.json vì file đó được commit: client secret của Google là bí mật
# thật, và realm file thì import tự động ở mọi máy dev. Tách ra script chạy tay giữ được cả hai
# thứ — realm mặc định chạy được ngay khi chưa ai có tài khoản Google Cloud, còn ai cần Google thì
# chạy thêm một lệnh.
set -euo pipefail

KC="${KEYCLOAK_URL:-http://localhost:8081}"
REALM="${KEYCLOAK_REALM:-nexaticket}"
ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
ALIAS="google"

: "${GOOGLE_CLIENT_ID:?Thiếu GOOGLE_CLIENT_ID — lấy ở Google Cloud Console > APIs & Services > Credentials}"
: "${GOOGLE_CLIENT_SECRET:?Thiếu GOOGLE_CLIENT_SECRET}"

echo "==> Lấy token admin từ $KC"
TOKEN="$(curl -fsS -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli \
  -d "username=$ADMIN_USER" --data-urlencode "password=$ADMIN_PASS" \
  | python -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"

api() { curl -sS -o /dev/null -w '%{http_code}' -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' "$@"; }

BASE="$KC/admin/realms/$REALM/identity-provider/instances"

# trustEmail=true: Google đã xác minh email rồi, bắt người dùng xác minh lại qua mail là thừa và
# là chỗ hỏng hay gặp nhất ở môi trường dev (không có SMTP thật thì họ kẹt luôn).
#
# syncMode=IMPORT (không phải FORCE): tên và email chép sang lúc đăng nhập lần đầu, sau đó
# NexaTicket là chủ dữ liệu đó. FORCE sẽ ghi đè mỗi lần đăng nhập, xoá mất tên người dùng tự sửa.
PAYLOAD="$(GOOGLE_CLIENT_ID="$GOOGLE_CLIENT_ID" GOOGLE_CLIENT_SECRET="$GOOGLE_CLIENT_SECRET" \
  ALIAS="$ALIAS" python - <<'PY'
import json, os
print(json.dumps({
    "alias": os.environ["ALIAS"],
    "providerId": "google",
    "displayName": "Google",
    "enabled": True,
    "trustEmail": True,
    "storeToken": False,
    "linkOnly": False,
    "firstBrokerLoginFlowAlias": "first broker login",
    "config": {
        "clientId": os.environ["GOOGLE_CLIENT_ID"],
        "clientSecret": os.environ["GOOGLE_CLIENT_SECRET"],
        "defaultScope": "openid profile email",
        "syncMode": "IMPORT",
        "useJwksUrl": "true",
    },
}))
PY
)"

echo "==> Khai identity provider '$ALIAS' trong realm $REALM"
CODE="$(api -X POST "$BASE" -d "$PAYLOAD")"

if [ "$CODE" = "409" ]; then
  echo "    đã có sẵn — cập nhật lại cấu hình"
  CODE="$(api -X PUT "$BASE/$ALIAS" -d "$PAYLOAD")"
fi

case "$CODE" in
  20*) ;;
  *) echo "!! Keycloak trả về HTTP $CODE" >&2; exit 1 ;;
esac

echo
echo "Xong. Còn hai việc phải làm bằng tay:"
echo
echo "  1. Ở Google Cloud Console, thêm redirect URI được phép cho OAuth client:"
echo "       $KC/realms/$REALM/broker/$ALIAS/endpoint"
echo
echo "  2. Bật nút ở frontend — trong .env.local của web-customer:"
echo "       AUTH_GOOGLE_ENABLED=true"
