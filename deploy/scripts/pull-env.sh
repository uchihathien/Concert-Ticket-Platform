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
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-ap-southeast-1}"

# Quyền 600 ngay từ lúc tạo, TRƯỚC khi có nội dung: tạo file rồi mới chmod để lại một khoảng thời
# gian file chứa mọi bí mật của hệ thống mà ai đọc cũng được.
tmp=$(mktemp); chmod 600 "$tmp"
trap 'rm -f "$tmp"' EXIT

# `--with-decryption` giải mã SecureString. `--recursive` để thêm tham số sau này không phải sửa script.
aws ssm get-parameters-by-path --path "$PREFIX/" --with-decryption --recursive \
    --query 'Parameters[].[Name,Value]' --output text \
  | sed "s|^$PREFIX/||" \
  | awk -F'\t' 'NF >= 2 { $1=$1; printf "%s=%s\n", $1, substr($0, index($0, "\t") + 1) }' \
  >> "$tmp"

n=$(grep -c '=' "$tmp" || true)

# Kiem theo TEN, khong theo so luong.
#
# Dem thi khong noi duoc thieu cai gi: 47 bien co the du neu dung bien, va thieu neu sai bien.
# Danh sach bat buoc lay THANG tu prod.yml (cac tham chieu dang ${X:?}) nen khong bao giu mot
# ban sao se lech khi ai do them bien moi vao compose.
compose="$(dirname "$0")/../compose/prod.yml"
missing=""
if [ -f "$compose" ]; then
  # Bo dong comment truoc khi rut: dong 8 cua prod.yml co vi du `${X:?...}` trong loi giai thich,
  # va no se thanh mot "bien bat buoc" ten X khong he ton tai.
  for v in $(grep -v '^[[:space:]]*#' "$compose" | grep -oE '[$][{][A-Z_]+:[?]' | tr -d '${:?' | sort -u); do
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

echo "Đã sinh $OUT ($n biến)"
