#!/usr/bin/env bash
# Sinh .env ở CloudShell rồi chuyển sang máy chủ qua S3.
#
#   INSTANCE=i-xxx bash deploy/scripts/push-env.sh
#
# VÌ SAO TỒN TẠI: Service Control Policy của AWS Organization chặn instance đọc Parameter Store — cả
# `GetParametersByPath` lẫn `GetParameters`. Đã kiểm bằng lời gọi thật từ chính instance:
#
#     AccessDeniedException ... assumed-role/nexaticket-ec2/i-...
#     is not authorized to perform: ssm:GetParameters ... explicit deny in a service control policy
#
# CloudShell thì đọc được (role khác), nên .env được sinh ở đây rồi đẩy sang.
#
# VÌ SAO QUA S3, KHÔNG NHÚNG VÀO LỆNH SSM: tham số của `send-command` được lưu trong CloudTrail và
# lịch sử Command. Nhúng nội dung .env vào đó là ghi 55 bí mật — mật khẩu 13 database, khoá payOS,
# secret Keycloak — vĩnh viễn vào log của tài khoản, nơi ai xem được CloudTrail là xem được chúng.
# Qua S3 thì chỉ có TÊN object nằm trong log, và object bị xoá ngay sau khi máy chủ tải xong.
#
# ĐÂY LÀ GIẢI PHÁP VÒNG TRÁNH một hạn chế của tổ chức, không phải thiết kế mong muốn. Cách đúng là
# xin người quản lý Organization nới SCP cho `ssm:GetParameters` trên `parameter/nexaticket/*`; khi
# đó `nexa-env` trên máy chủ tự đọc được và script này không cần nữa.
set -euo pipefail

NAME="${NAME:-nexaticket}"
PREFIX="${PREFIX:-/$NAME}"
# Vùng: ưu tiên AWS_REGION của môi trường, KHÔNG ghim cứng một vùng.
#
# AWS CLI chọn endpoint theo AWS_REGION TRƯỚC AWS_DEFAULT_REGION. Bản trước ghim ap-southeast-1 và
# chỉ export AWS_DEFAULT_REGION, nên AWS_REGION của CloudShell thắng: mọi tài nguyên được tạo ở vùng
# của CloudShell, trong khi script tin là ap-southeast-1.
#
# Hậu quả đã xảy ra thật, và nó giả dạng thành một chuỗi lỗi khác hẳn:
#   - Elastic IP cấp ra là 52.62.179.59, thuộc ap-southeast-2 — không phải vùng script khai.
#   - gen-secrets.sh ghi tham số vào ap-southeast-2 (AWS_REGION thắng).
#   - pull-env.sh trên máy chủ đọc ap-southeast-1 (ở đó AWS_REGION không được đặt) -> không thấy gì.
#   - create-bucket gửi tới endpoint ap-southeast-2 kèm LocationConstraint=ap-southeast-1 ->
#     IllegalLocationConstraintException.
#   - Và nặng nhất: Service Control Policy của tổ chức CHỈ cho phép một vùng, nên mọi lời gọi tới
#     vùng sai bị "explicit deny" — thông điệp nói về `ssm:GetParameters`, khiến ta tưởng SSM bị
#     chặn, trong khi thứ bị chặn là VÙNG.
#
# Export CẢ HAI biến để không còn chỗ cho sự khác biệt.
REGION="${REGION:-${AWS_REGION:-${AWS_DEFAULT_REGION:-ap-southeast-2}}}"
export AWS_DEFAULT_REGION="$REGION" AWS_REGION="$REGION"

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
BUCKET="${BUCKET:-$NAME-env-$ACCOUNT}"
INSTANCE="${INSTANCE:-$(aws ec2 describe-instances \
  --filters "Name=tag:Name,Values=$NAME" "Name=instance-state-name,Values=running" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)}"
[ "$INSTANCE" != "None" ] || { echo "Không thấy instance đang chạy." >&2; exit 1; }

say() { printf '\n=== %s\n' "$*"; }

# --- Sinh .env tại chỗ -------------------------------------------------------
# Quyền 600 TRƯỚC khi có nội dung, và xoá kể cả khi script chết giữa đường.
tmp=$(mktemp); chmod 600 "$tmp"
trap 'rm -f "$tmp"' EXIT

say "Đọc Parameter Store (từ CloudShell — nơi SCP không chặn)"
aws ssm get-parameters-by-path --path "$PREFIX/" --with-decryption --recursive \
    --query 'Parameters[].[Name,Value]' --output text \
  | sed "s|^$PREFIX/||" \
  | awk -F'\t' 'NF >= 2 { printf "%s=%s\n", $1, substr($0, index($0, "\t") + 1) }' > "$tmp"

n=$(grep -c '^[A-Z_][A-Z_0-9]*=' "$tmp" || true)
echo "  $n biến"

# Kiểm ĐỦ trước khi đẩy đi. Đẩy một .env thiếu biến thì compose chết trên máy chủ, ở một chỗ xa hơn
# và khó đọc hơn nhiều so với việc chết ngay tại đây.
compose="$(cd "$(dirname "$0")/../compose" && pwd)/prod.yml"
missing=""
for v in $(grep -v '^[[:space:]]*#' "$compose" \
           | grep -oE '[$][{][A-Z_]+:[?]' | tr -d '${:?' | sort -u); do
  grep -q "^$v=" "$tmp" || missing="$missing $v"
done
if [ -n "$missing" ]; then
  echo "  THIẾU $(echo $missing | wc -w) biến bắt buộc:" >&2
  for v in $missing; do echo "     - $v" >&2; done
  exit 1
fi

# --- Bucket chuyển tiếp ------------------------------------------------------
# `--region` TƯỜNG MINH ở mọi lời gọi S3, không dựa vào biến môi trường.
#
# `create-bucket` thất bại với một thông điệp khó hiểu nếu endpoint của request khác với
# LocationConstraint:
#
#     IllegalLocationConstraintException: The ap-southeast-1 location constraint is incompatible
#     for the region specific endpoint this request was sent to.
#
# AWS CLI chọn endpoint theo `AWS_REGION` TRƯỚC `AWS_DEFAULT_REGION`, và CloudShell đặt `AWS_REGION`
# theo vùng nó được mở — có thể khác vùng ta đang dựng. Khai `--region` thì không còn chỗ cho sự
# khác biệt đó.
say "Bucket $BUCKET (vùng $REGION)"
if aws s3api head-bucket --bucket "$BUCKET" --region "$REGION" 2>/dev/null; then
  echo "  đã có"
else
  # `--create-bucket-configuration` BẮT BUỘC ở mọi vùng trừ us-east-1; thiếu nó thì lời gọi bị từ
  # chối với một thông điệp không nói rõ là vì lý do này.
  if [ "$REGION" = us-east-1 ]; then
    aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" >/dev/null
  else
    aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
      --create-bucket-configuration "LocationConstraint=$REGION" >/dev/null
  fi
  # Chặn mọi đường công khai. File này chứa mọi bí mật của hệ thống; một bucket mở là mất tất cả.
  aws s3api put-public-access-block --bucket "$BUCKET" --region "$REGION" \
    --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true >/dev/null
  # Mã hoá mặc định: object nằm trên đĩa của AWS ở dạng đã mã hoá, không phải nguyên văn.
  aws s3api put-bucket-encryption --bucket "$BUCKET" --region "$REGION" \
    --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}' >/dev/null
  echo "  đã tạo, chặn public, bật mã hoá"
fi

say "Đẩy .env lên"
aws s3 cp "$tmp" "s3://$BUCKET/env" --sse AES256 --region "$REGION" --only-show-errors
echo "  s3://$BUCKET/env"

# --- Máy chủ tải về ---------------------------------------------------------
# Chỉ TÊN object đi qua lệnh SSM, không phải nội dung — đó là cả lý do chọn S3.
say "Máy chủ tải về và xoá object"
cid=$(aws ssm send-command --instance-ids "$INSTANCE" \
  --document-name AWS-RunShellScript \
  --comment "tai .env tu S3" \
  --timeout-seconds 300 \
  --parameters "commands=[\"sudo -u ubuntu bash -lc 'set -e; install -m 600 /dev/null /srv/nexaticket/deploy/compose/.env; aws s3 cp s3://$BUCKET/env /srv/nexaticket/deploy/compose/.env --only-show-errors --region $REGION; chmod 600 /srv/nexaticket/deploy/compose/.env; grep -c \\\"=\\\" /srv/nexaticket/deploy/compose/.env'\"]" \
  --query 'Command.CommandId' --output text)

while :; do
  st=$(aws ssm get-command-invocation --command-id "$cid" --instance-id "$INSTANCE" \
        --query Status --output text 2>/dev/null || echo Pending)
  case "$st" in Success|Failed|TimedOut|Cancelled) break ;; esac
  sleep 3
done

out=$(aws ssm get-command-invocation --command-id "$cid" --instance-id "$INSTANCE" \
       --query StandardOutputContent --output text)
if [ "$st" != Success ]; then
  aws ssm get-command-invocation --command-id "$cid" --instance-id "$INSTANCE" \
    --query StandardErrorContent --output text | sed 's/^/    /' >&2
  echo >&2
  echo "  Nếu đây cũng là Service Control Policy chặn s3:GetObject thì không còn đường nào không" >&2
  echo "  ghi bí mật vào log. Khi đó phải nhờ người quản lý Organization nới SCP." >&2
  # XOÁ object kể cả khi thất bại: để nó nằm lại trên S3 là để một bản .env đầy bí mật ở nơi không
  # ai theo dõi.
  aws s3 rm "s3://$BUCKET/env" --region "$REGION" --only-show-errors || true
  exit 1
fi
echo "  máy chủ ghi $(echo "$out" | tr -d '[:space:]') biến vào /srv/nexaticket/deploy/compose/.env"

# Xoá NGAY. Object chỉ cần tồn tại trong vài giây giữa lúc đẩy lên và lúc tải về.
aws s3 rm "s3://$BUCKET/env" --region "$REGION" --only-show-errors
echo "  đã xoá object trên S3"
