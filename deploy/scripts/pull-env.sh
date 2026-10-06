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
# 49 là số biến prod.yml đòi bắt buộc. Thiếu thì `compose up` sẽ chết với tên biến còn thiếu —
# nhưng chết ở đây, trước khi kéo 22 ảnh, thì nhanh hơn nhiều.
if [ "$n" -lt 49 ]; then
  echo "CHỈ CÓ $n biến, cần tối thiểu 49. Chạy gen-secrets.sh và nạp nốt các khoá ngoài." >&2
  exit 1
fi

install -m 600 "$tmp" "$OUT"
echo "Đã sinh $OUT ($n biến)"
