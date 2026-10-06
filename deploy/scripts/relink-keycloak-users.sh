#!/usr/bin/env bash
# Nối identity_db với Keycloak production: mỗi user trong identity_db có một user Keycloak cùng email,
# và idp_subject trỏ đúng vào user đó. CHẠY TRÊN MÁY CHỦ. Idempotent — user đã có thì chỉ nối lại.
#
#   bash deploy/scripts/relink-keycloak-users.sh
#
# VÌ SAO CẦN. identity_db nhận diện người dùng theo `idp_subject` — id của user bên Keycloak. Dữ liệu
# chuyển từ máy dev mang theo subject của Keycloak DEV, vô nghĩa ở đây. Không nối lại thì cả 28 tài
# khoản đều mồ côi: đăng nhập được (Keycloak nhận), nhưng identity-service thấy một subject lạ, tạo
# user MỚI, và người đó không thuộc tổ chức nào — giao diện hiển thị là đã đăng nhập, mọi thứ khác
# trống. Không log nào nói ra điều đó.
#
# User chưa có trên Keycloak được tạo với mật khẩu tạm ngẫu nhiên, in ra MỘT lần, Keycloak bắt đổi ở
# lần đăng nhập đầu. User đã có (ví dụ superadmin tạo bởi bootstrap-keycloak.sh) giữ nguyên mật khẩu.
#
# HAI CÁI BẪY đã làm bản đầu chết im lặng, ghi lại để không ai sửa "cho gọn" rồi gặp lại:
#   - `tr ... </dev/urandom | head -c 12` dưới `set -o pipefail`: head đóng ống sau 12 ký tự, tr nhận
#     SIGPIPE, pipeline trả 141, `set -e` thoát — ở đúng dòng đầu tiên của bảng, sau khi header đã in.
#     Mọi lệnh trong chuỗi sinh mật khẩu dưới đây đều ĐỌC HẾT input nên không ai bị SIGPIPE.
#   - `docker exec -i` bên trong `while read`: nó đọc stdin, mà stdin lúc đó là danh sách user của
#     vòng lặp — vòng lặp kết thúc sau user đầu tiên, không lỗi, không cảnh báo. Danh sách được ghi ra
#     file và mọi lệnh trong vòng lặp nhận `</dev/null`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/compose/.env}"
PG="${PG:-nexaticket-prod-postgres-1}"
KC="${KC:-nexaticket-prod-keycloak-1}"
REALM=nexaticket

psql() { docker exec -i "$PG" psql -U postgres -v ON_ERROR_STOP=1 -q "$@"; }
kc()   { docker exec "$KC" /opt/keycloak/bin/kcadm.sh "$@"; }

echo "==> Đăng nhập admin Keycloak (credential nằm trong container)"
docker exec "$KC" sh -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 \
  --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' >/dev/null

list="$(mktemp)"; trap 'rm -f "$list"' EXIT
psql -d identity_db -tAc "select email || '|' || coalesce(full_name, '') from users where email is not null order by is_super_admin desc, email" </dev/null > "$list"
echo "==> $(wc -l < "$list") user trong identity_db"
printf '\n    %-40s %s\n' "EMAIL" "MẬT KHẨU TẠM (đổi ở lần đăng nhập đầu)"

created=0; linked=0
while IFS='|' read -r email name; do
  [ -n "$email" ] || continue
  id="$(kc get users -r "$REALM" -q "email=$email" -q exact=true --fields id --format csv --noquotes </dev/null 2>/dev/null | head -1 || true)"
  if [ -n "$id" ]; then
    printf '    %-40s %s\n' "$email" "(đã có, giữ nguyên mật khẩu)"
  else
    pw="$(head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z2-9' | cut -c1-12)"
    first="${name%% *}"; last="${name#* }"; [ "$last" = "$name" ] && last=""
    id="$(kc create users -r "$REALM" -i -s "username=$email" -s "email=$email" \
            -s "firstName=$first" -s "lastName=$last" -s enabled=true -s emailVerified=true \
            -s "credentials=[{\"type\":\"password\",\"value\":\"$pw\",\"temporary\":true}]" </dev/null)"
    printf '    %-40s %s\n' "$email" "$pw"
    created=$((created + 1))
  fi
  psql -d identity_db -c "update users set idp_subject = '$id' where email = '$email'" </dev/null
  linked=$((linked + 1))
done < "$list"

echo
echo "==> Xong: tạo mới $created, nối lại $linked."
