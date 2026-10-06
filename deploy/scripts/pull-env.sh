#!/usr/bin/env bash
# Sinh deploy/compose/.env từ AWS SSM Parameter Store.
#
# Cài lên máy chủ tại /usr/local/bin/nexa-env và gọi trước mỗi lần `compose up`.
# Không cần access key: instance lấy credential tạm từ IAM role gắn kèm.
#
#   sudo install -m 755 deploy/scripts/pull-env.sh /usr/local/bin/nexa-env
#   nexa-env
set -euo pipefail

PREFIX="${PREFIX:-/nexaticket}"
OUT="${OUT:-/srv/nexaticket/deploy/compose/.env}"
# Vùng lấy từ METADATA của chính instance, không ghim cứng.
#
# Script này chạy trên máy chủ, nơi AWS_REGION không được đặt. Ghim một vùng ở đây thì nó đọc
# Parameter Store của vùng KHÁC vùng instance đang nằm — không thấy tham số nào, và nếu tổ chức có
# Service Control Policy chỉ cho phép một vùng thì lời gọi còn bị "explicit deny", khiến lỗi trông
# như SSM bị chặn thay vì vùng bị sai.
#
# IMDSv2 (bắt buộc trên instance này): phải lấy token trước, không gọi thẳng được.
if [ -z "${AWS_REGION:-}${AWS_DEFAULT_REGION:-}" ]; then
  _tok=$(curl -sS -m 3 -X PUT http://169.254.169.254/latest/api/token            -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' 2>/dev/null || true)
  if [ -n "$_tok" ]; then
    AWS_DEFAULT_REGION=$(curl -sS -m 3 -H "X-aws-ec2-metadata-token: $_tok"       http://169.254.169.254/latest/meta-data/placement/region 2>/dev/null || true)
  fi
fi
export AWS_DEFAULT_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-ap-southeast-2}}"
export AWS_REGION="$AWS_DEFAULT_REGION"

# Quyền 600 ngay từ lúc tạo, TRƯỚC khi có nội dung: tạo file rồi mới chmod để lại một khoảng thời
# gian file chứa mọi bí mật của hệ thống mà ai đọc cũng được.
tmp=$(mktemp); chmod 600 "$tmp"
trap 'rm -f "$tmp"' EXIT

# Hai đường lấy tham số, thử lần lượt.
#
# ĐƯỜNG 1 — `GetParametersByPath`: một lời gọi lấy hết, không cần biết trước tên biến nào.
#
# ĐƯỜNG 2 — `GetParameters` theo TÊN, từng lô 10: dùng khi đường 1 bị từ chối. Tài khoản nằm trong
# AWS Organization có thể có Service Control Policy chặn riêng `GetParametersByPath` — nó là lời gọi
# LIỆT KÊ, nên bị siết chặt hơn việc đọc một tham số đã biết tên. Đã xảy ra thật trên tài khoản do
# tổ chức cấp:
#
#     AccessDeniedException ... not authorized to perform: ssm:GetParametersByPath
#     with an explicit deny in a service control policy
#
# Danh sách tên rút THẲNG từ prod.yml — mọi biến compose tham chiếu tới, cả bắt buộc (`${X:?}`) lẫn
# có mặc định (`${X:-...}`). Không giữ một bản sao sẽ lệch khi ai đó thêm biến mới.
# prod.yml nằm CẠNH file .env, nên suy từ $OUT — KHÔNG từ $0.
#
# Script này được cài vào /usr/local/bin/nexa-env, nên `dirname $0` là /usr/local/bin và
# `../compose` thành /usr/local/compose — không tồn tại:
#
#     /usr/local/bin/nexa-env: line 34: cd: /usr/local/bin/../compose: No such file or directory
#
# Bản trước bọc phép kiểm trong `if [ -f ... ]` nên nó BỎ QUA IM LẶNG: phép kiểm biến thiếu chưa
# từng chạy một lần nào kể từ khi script được cài vào /usr/local/bin.
COMPOSE_FILE="${COMPOSE_FILE:-$(dirname "$OUT")/prod.yml}"
[ -f "$COMPOSE_FILE" ] || {
  echo "Không thấy $COMPOSE_FILE — cần nó để biết danh sách biến bắt buộc." >&2
  echo "Đặt COMPOSE_FILE=/duong/dan/prod.yml nếu nó nằm chỗ khác." >&2
  exit 1
}

by_path() {
  aws ssm get-parameters-by-path --path "$PREFIX/" --with-decryption --recursive \
      --query 'Parameters[].[Name,Value]' --output text 2>"$err"
}

by_name() {
  # Thử MỘT tên trước khi chạy cả bảy lô.
  #
  # Nếu Service Control Policy chặn `GetParameters` thì cả bảy lô đều thất bại y như nhau, và vòng
  # lặp in ra năm mươi dòng AccessDenied giống hệt — che mất mọi thứ đáng đọc trong output. Một lời
  # gọi thăm dò trả lời cùng câu hỏi bằng một dòng.
  if ! aws ssm get-parameter --name "$PREFIX/INTERNAL_SHARED_SECRET" --with-decryption         >/dev/null 2>"$err"; then
    if grep -q "service control policy" "$err"; then
      echo "  Đọc theo tên cũng bị Service Control Policy chặn (ssm:GetParameters)." >&2
      return 0
    fi
  fi

  # Mọi tên biến prod.yml nhắc tới, bỏ dòng comment (dòng 8 có ví dụ `${X:?...}` trong lời giải thích).
  local names batch full
  names=$(grep -v '^[[:space:]]*#' "$COMPOSE_FILE" \
          | grep -oE '[$][{][A-Z_][A-Z_0-9]*' | tr -d '${' | sort -u)
  # Lô 10 là trần của API; chia nhỏ hơn chỉ tốn thêm lời gọi.
  # `--query` chỉ in tham số CÓ THẬT; tên không tồn tại nằm ở InvalidParameters và bị bỏ qua — đó là
  # hành vi mong muốn, vì prod.yml nhắc cả những biến chỉ có ý nghĩa ở máy phát triển.
  echo "$names" | xargs -n 10 | while read -r batch; do
    full=""
    for b in $batch; do full="$full $PREFIX/$b"; done
    # shellcheck disable=SC2086
    aws ssm get-parameters --names $full --with-decryption \
      --query 'Parameters[].[Name,Value]' --output text 2>>"$err" || true
  done
}

err=$(mktemp); trap 'rm -f "$tmp" "$err"' EXIT

raw=$(by_path) || raw=""
if [ -z "$raw" ]; then
  if grep -q "service control policy\|AccessDenied" "$err"; then
    echo "  GetParametersByPath bị Service Control Policy chặn — chuyển sang đọc theo tên." >&2
  else
    echo "  GetParametersByPath không trả về gì:" >&2
    sed 's/^/    /' "$err" >&2
  fi
  raw=$(by_name)
  # In lỗi của ĐƯỜNG HAI nếu nó cũng không ra gì.
  #
  # `|| true` trong by_name giữ cho vòng lặp đi hết các lô, nhưng nó cũng nuốt luôn thông điệp lỗi —
  # nên khi cả hai đường đều bị chặn, thứ người dùng thấy chỉ là một danh sách "thiếu 46 biến", không
  # có manh mối nào về việc AWS đã từ chối cái gì. Đó là chẩn đoán tệ hơn cả không có.
  if [ -z "$raw" ] || [ "$(printf '%s
' "$raw" | wc -l)" -lt 10 ]; then
    echo "  Đọc theo tên cũng không lấy được gì. AWS trả về:" >&2
    sed 's/^/    /' "$err" >&2
    echo >&2
    echo "  Nếu đây cũng là Service Control Policy: instance KHÔNG đọc được Parameter Store, và .env" >&2
    echo "  phải được chuyển từ CloudShell sang bằng cách khác. Dán lỗi này vào phiên làm việc." >&2
  fi
fi

printf '%s\n' "$raw" \
  | sed "s|^$PREFIX/||" \
  | awk -F'\t' 'NF >= 2 { printf "%s=%s\n", $1, substr($0, index($0, "\t") + 1) }' \
  >> "$tmp"

n=$(grep -c '=' "$tmp" || true)

# Kiem theo TEN, khong theo so luong.
#
# Dem thi khong noi duoc thieu cai gi: 47 bien co the du neu dung bien, va thieu neu sai bien.
# Danh sach bat buoc lay THANG tu prod.yml (cac tham chieu dang ${X:?}) nen khong bao giu mot
# ban sao se lech khi ai do them bien moi vao compose.
missing=""
if [ -f "$COMPOSE_FILE" ]; then
  # Bo dong comment truoc khi rut: dong 8 cua prod.yml co vi du `${X:?...}` trong loi giai thich,
  # va no se thanh mot "bien bat buoc" ten X khong he ton tai.
  for v in $(grep -v '^[[:space:]]*#' "$COMPOSE_FILE" | grep -oE '[$][{][A-Z_]+:[?]' | tr -d '${:?' | sort -u); do
    grep -q "^$v=" "$tmp" || missing="$missing $v"
  done
fi

if [ -n "$missing" ]; then
  echo "THIEU $(echo $missing | wc -w) bien bat buoc:" >&2
  for v in $missing; do echo "   - $v" >&2; done
  echo >&2
  echo "Chay gen-secrets.sh, roi nap not cac gia tri ngoai (payOS, SMTP, email superadmin)." >&2
  echo "Chet o day — truoc khi keo 22 anh — nhanh hon nhieu so voi chet luc compose up." >&2
  exit 1
fi

# GHI FILE. Dòng này từng bị một bản vá xoá mất, và script vẫn in "Đã sinh ... (56 biến)" như
# thường — báo thành công mà không ghi gì. Compose sau đó chết vì thiếu biến, ở một chỗ cách đây rất
# xa, và không ai nghĩ tới việc kiểm xem file có tồn tại hay không.
install -m 600 "$tmp" "$OUT"

# Chủ sở hữu theo THƯ MỤC ĐÍCH, không theo người đang chạy script.
#
# systemd gọi script này bằng root (ExecStartPre), nên .env thành root:root mode 600. Khi đó `ubuntu`
# không đọc được, và mọi lệnh `docker compose --env-file .env` chạy dưới `ubuntu` đều hỏng — kể cả
# smoke.sh. Triệu chứng là MƯỜI LĂM mục đỏ cùng lúc, trông như cả cụm chết, trong khi nguyên nhân là
# một dòng "Permission denied" lọt giữa output.
if [ "$(id -u)" = 0 ]; then
  owner=$(stat -c '%u:%g' "$(dirname "$OUT")" 2>/dev/null || echo '')
  [ -z "$owner" ] || chown "$owner" "$OUT"
fi

# Đọc lại từ ĐÍCH, không tin vào việc lệnh trên đã chạy. Một thông báo thành công phải dựa trên trạng
# thái quan sát được, không dựa trên việc mã nguồn đã đi qua dòng nào.
[ -s "$OUT" ] || { echo "Ghi $OUT thất bại hoặc file rỗng." >&2; exit 1; }

# Siết quyền lần nữa và KIỂM LẠI. `install -m 600` đã đặt quyền, nhưng file này chứa toàn bộ bí mật
# của hệ thống — mật khẩu 13 database, khoá payOS, secret Keycloak. Để nó 644 là mọi user trên máy
# đọc được, và đó không phải thứ nên phụ thuộc vào một cờ của một lệnh.
chmod 600 "$OUT"
perm=$(stat -c %a "$OUT" 2>/dev/null || stat -f %Lp "$OUT" 2>/dev/null || echo '?')
case "$perm" in
  600) ;;
  *) echo "CẢNH BÁO: $OUT đang có quyền $perm, đáng lẽ 600." >&2 ;;
esac
written=$(grep -c '^[A-Z_][A-Z_0-9]*=' "$OUT" || true)
[ "$written" -ge 49 ] || { echo "$OUT chỉ có $written biến, cần tối thiểu 49." >&2; exit 1; }

echo "Đã sinh $OUT ($written biến)"
