#!/usr/bin/env bash
# Tạo/cập nhật một client Keycloak cho app di động, trên realm ĐANG CHẠY. CHẠY TRÊN MÁY CHỦ.
#
#   bash deploy/scripts/keycloak-mobile-client.sh mobile-scanner "exp://192.168.1.60:8083/--/auth"
#   bash deploy/scripts/keycloak-mobile-client.sh mobile-scanner "exp://*"   # mọi IP LAN, mọi tunnel
#   bash deploy/scripts/keycloak-mobile-client.sh mobile-customer            # chỉ scheme, không thêm URI
#
# Nhận NHIỀU URI, cách nhau bằng dấu cách.
#
# VÌ SAO CẦN SCRIPT CHỨ KHÔNG SỬA FILE REALM. `--import-realm` CHỈ chạy khi realm chưa tồn tại, nên
# sửa nexaticket-realm.prod.json sau lần dựng đầu không đổi được gì trên máy đang chạy — cùng lớp vấn
# đề với initdb-prod của Postgres. File realm vẫn được cập nhật, nhưng nó chỉ tới được MÁY MỚI.
#
# VÌ SAO REDIRECT URI LÀ THAM SỐ. Expo Go nạp bundle từ máy phát triển qua LAN, nên URI quay về có
# dạng `exp://<IP-LAN>:<cổng>/--/auth` — một địa chỉ đổi theo từng mạng Wi-Fi và từng máy. Ghim nó vào
# file realm nghĩa là IP của một người lập trình đi vào cấu hình production. Scheme cố định
# (`nexaticket-scanner://auth`) thì luôn được thêm; cái đó dùng cho bản build thật.
#
# PUBLIC + PKCE S256, KHÔNG có secret: app di động nằm trong tay người dùng, mọi "bí mật" nhúng trong
# nó đều đọc được bằng cách giải nén gói cài. PKCE là thứ thay thế đúng đắn cho client secret ở đây.
#
# IDEMPOTENT: client đã có thì chỉ bổ sung redirect URI còn thiếu, không ghi đè cấu hình khác.
set -euo pipefail

CLIENT_ID="${1:?Thiếu clientId, ví dụ: mobile-scanner}"
shift || true
EXTRA_URIS=("$@")
KC="${KC:-nexaticket-prod-keycloak-1}"
REALM="${REALM:-nexaticket}"

kc() { docker exec "$KC" /opt/keycloak/bin/kcadm.sh "$@"; }

echo "==> Đăng nhập admin Keycloak"
docker exec "$KC" sh -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 \
  --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' >/dev/null \
  || { echo "Không đăng nhập được admin Keycloak. Container $KC có chạy không?" >&2; exit 1; }

# Scheme riêng của app, dùng cho bản build thật (không phải Expo Go).
scheme="${CLIENT_ID#mobile-}"
[ "$scheme" = customer ] && base="nexaticket://auth" || base="nexaticket-$scheme://auth"

id="$(kc get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id --format csv --noquotes 2>/dev/null | head -1 || true)"

if [ -z "$id" ]; then
  echo "==> Tạo client $CLIENT_ID"
  uris="\"$base\""
  for u in ${EXTRA_URIS+"${EXTRA_URIS[@]}"}; do
    [ -n "$u" ] && uris="$uris,\"$u\""
  done
  id="$(kc create clients -r "$REALM" -i \
        -s "clientId=$CLIENT_ID" \
        -s enabled=true \
        -s publicClient=true \
        -s standardFlowEnabled=true \
        -s directAccessGrantsEnabled=false \
        -s 'attributes={"pkce.code.challenge.method":"S256"}' \
        -s "redirectUris=[$uris]" \
        -s 'webOrigins=[]')"
  echo "    đã tạo ($id)"
else
  echo "==> Client $CLIENT_ID đã có ($id)"
fi

# Bổ sung redirect URI còn thiếu. Đọc danh sách hiện tại rồi ghi lại cả mảng: kcadm không có lệnh
# "thêm một phần tử", và ghi đè bằng một mảng chỉ chứa URI mới sẽ xoá mất những URI đang dùng.
current="$(kc get "clients/$id" -r "$REALM" --fields redirectUris --format json 2>/dev/null \
            | tr -d ' \n' | sed 's/.*\[//; s/\].*//; s/"//g')"
wanted=("$base")
for u in ${EXTRA_URIS+"${EXTRA_URIS[@]}"}; do
  [ -n "$u" ] && wanted+=("$u")
done

missing=""
for u in "${wanted[@]}"; do
  [ -n "$u" ] || continue
  case ",$current," in *",$u,"*) ;; *) missing="${missing:+$missing,}$u" ;; esac
done

if [ -n "$missing" ]; then
  merged="$current${current:+,}$missing"
  json="$(printf '%s' "$merged" | awk -F, '{for(i=1;i<=NF;i++) printf "%s\"%s\"", (i>1?",":""), $i}')"
  kc update "clients/$id" -r "$REALM" -s "redirectUris=[$json]"
  echo "    đã thêm redirect URI: $missing"
else
  echo "    redirect URI đã đủ, không đổi gì"
fi

echo "==> redirect URI hiện tại:"
kc get "clients/$id" -r "$REALM" --fields clientId,publicClient,redirectUris --format json | sed 's/^/    /'
