#!/usr/bin/env bash
# Sinh bí mật production vào AWS SSM Parameter Store (tier standard — MIỄN PHÍ).
#
#   DOMAIN=concertth.site bash gen-secrets.sh
#
# CHẠY MỘT LẦN, ở AWS CloudShell. Bí mật không bao giờ đi qua máy bạn, không nằm trong repo,
# không nằm trong lịch sử shell.
#
# KHÔNG GHI ĐÈ giá trị đã tồn tại, và đó là điều quan trọng nhất của script này.
# Postgres khởi tạo user bằng mật khẩu ở lần chạy đầu tiên và giữ nguyên mãi. Chạy lại script với
# `--overwrite` sẽ sinh mật khẩu mới trong Parameter Store nhưng database vẫn giữ mật khẩu cũ —
# mọi service mất kết nối, và triệu chứng ("password authentication failed") trông như lỗi cấu hình
# chứ không như một lần xoay vòng bí mật ngoài ý muốn.
set -euo pipefail

DOMAIN="${DOMAIN:?Dat DOMAIN, vi du: DOMAIN=concertth.site bash gen-secrets.sh}"
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
PREFIX="${PREFIX:-/nexaticket}"
export AWS_DEFAULT_REGION="$REGION" AWS_REGION="$REGION"

created=0; kept=0

# Tạo nếu chưa có. `put-parameter` không kèm --overwrite sẽ thất bại khi đã tồn tại — ta dựa vào
# đúng hành vi đó thay vì tự đi kiểm tra trước, nên không có khoảng hở giữa lúc kiểm và lúc ghi.
put() {
  local name="$1" value="$2" type="${3:-SecureString}"
  if aws ssm put-parameter --name "$PREFIX/$name" --type "$type" \
        --value "$value" >/dev/null 2>&1; then
    printf '  + %s\n' "$name"; created=$((created + 1))
  else
    printf '  = %s (giữ giá trị cũ)\n' "$name"; kept=$((kept + 1))
  fi
}

# 32 byte ngẫu nhiên. `tr -d` bỏ ký tự có thể gây rắc rối khi đi qua shell và file .env.
gen() { openssl rand -base64 36 | tr -d '\n=+/' | cut -c1-40; }

echo "Domain $DOMAIN · vùng $REGION · tiền tố $PREFIX"

echo
echo "--- Mật khẩu database (mỗi service một giá trị riêng — ADR-1002) ---"
for svc in IDENTITY CATALOG INVENTORY ORDERING PAYMENT PAYOUT TICKETING \
           NOTIFICATION ANALYTICS AI_CHATBOX KEYCLOAK; do
  put "DB_PASSWORD_$svc" "$(gen)"
done
# Sổ cái có HAI role: owner chạy Flyway, app chạy service. Đây là thứ làm cho sổ cái thật sự
# append-only — owner bỏ qua mọi REVOKE trên bảng của chính mình (ADR-1005).
put DB_PASSWORD_LEDGER_OWNER "$(gen)"
put DB_PASSWORD_LEDGER_APP   "$(gen)"
put POSTGRES_SUPERUSER_PASSWORD "$(gen)"

echo
echo "--- Hạ tầng ---"
put REDIS_PASSWORD    "$(gen)"
put RABBITMQ_USER     "nexaticket" String
put RABBITMQ_PASSWORD "$(gen)"
# Cửa vào /internal/**. Trống thì InternalApiFilter KHÔNG được cắm vào và mọi service gọi được
# endpoint nội bộ của nhau; /internal/reservations đổi tồn kho mà không kiểm chủ sở hữu.
put INTERNAL_SHARED_SECRET "$(gen)"

echo
echo "--- Auth.js: BỐN giá trị KHÁC NHAU ---"
# Dùng chung thì cookie phiên của app khách giải mã được ở app quản trị, và ranh giới giữa hai app
# chỉ còn là quy ước.
for app in WEB_CUSTOMER WEB_ADMIN WEB_SCANNER WEB_PLATFORM; do
  put "AUTH_SECRET_$app" "$(gen)"
done

echo
echo "--- Keycloak ---"
put KEYCLOAK_ADMIN_USER     "nexaadmin" String
put KEYCLOAK_ADMIN_PASSWORD "$(gen)"
for c in WEB_CUSTOMER WEB_ADMIN WEB_SCANNER WEB_PLATFORM IDENTITY_ADMIN; do
  put "KC_SECRET_$c" "$(gen)"
done

echo
echo "--- Kho ảnh (SeaweedFS — KHÔNG phải khoá AWS) ---"
put MEDIA_ACCESS_KEY "$(gen)"
put MEDIA_SECRET_KEY "$(gen)"

echo
echo "--- URL công khai (suy từ DOMAIN, không phải bí mật) ---"
put WEB_CUSTOMER_URL     "https://$DOMAIN"                 String
put WEB_ADMIN_URL        "https://to-chuc.$DOMAIN"         String
put WEB_SCANNER_URL      "https://soat-ve.$DOMAIN"         String
put WEB_PLATFORM_URL     "https://quan-tri.$DOMAIN"        String
put PUBLIC_API_BASE_URL  "https://api.$DOMAIN"             String
put KEYCLOAK_HOSTNAME    "https://tai-khoan.$DOMAIN"       String
put OIDC_ISSUER          "https://tai-khoan.$DOMAIN/realms/nexaticket" String
# Địa chỉ Keycloak nhìn từ TRONG mạng docker — khác OIDC_ISSUER, và cố ý: identity-service gọi
# Admin API bằng đường nội bộ, không vòng ra internet rồi quay lại.
put OIDC_BASE_URL        "http://keycloak:8080"            String
put MEDIA_PUBLIC_URL     "https://media.$DOMAIN"           String
put MEDIA_CORS_ORIGIN    "https://to-chuc.$DOMAIN"         String

echo
echo "--- Vận hành ---"
# TLS kết thúc ở Nginx, Keycloak nói HTTP trong mạng docker. Khai tường minh rằng đó là chủ đích,
# thay vì tắt cả chốt chặn issuer của ProductionHardening.
put NEXATICKET_SECURITY_ALLOWPLAINTEXTISSUER "true" String
# ĐÚNG một proxy (Nginx) đứng trước gateway. Đếm thiếu thì kẻ tấn công tự khai X-Forwarded-For và
# vô hiệu hoá rate limit; đếm thừa thì mọi IP nhìn ra như IP của proxy và cả internet dùng chung
# một hạn mức 50 rps.
put TRUSTED_PROXY_COUNT "1" String
put SERVICE_MEMORY      "512m" String
put APP_MEMORY          "384m" String
put LOG_LEVEL           "INFO" String
# Ollama KHÔNG chạy (nó nằm sau profile `ai-local`). Provider `local` trỏ vào một địa chỉ không có
# ai nghe, nên service vẫn khởi động và lượt chat trả 503 "trợ lý đang bận" — hỏng đúng hướng.
# Đổi sang `anthropic` khi đã nạp hai khoá ở phần dưới.
put AI_PROVIDER "local" String

cat <<MANUAL

================================================================
  Đã tạo: $created   ·   Giữ nguyên: $kept

  CÒN 8 GIÁ TRỊ CHỈ BẠN LẤY ĐƯỢC. Chạy từng dòng dưới đây, TRONG CloudShell,
  thay <...> bằng giá trị thật. Đừng dán chúng vào bất kỳ cuộc hội thoại nào.

  p() { aws ssm put-parameter --name "$PREFIX/\$1" --type SecureString \
          --value "\$2" --overwrite >/dev/null && echo "  ok \$1"; }

  # Email được cấp SUPER_ADMIN ở lần đăng nhập đầu. KHÔNG để mặc định —
  # ProductionHardening chặn khởi động nếu còn superadmin@nexaticket.local.
  aws ssm put-parameter --name $PREFIX/SUPER_ADMIN_EMAILS --type String \
    --value '<email that cua ban>' --overwrite

  # payOS — lấy ở trang quản trị merchant.
  p PAYOS_CLIENT_ID    '<...>'
  p PAYOS_API_KEY      '<...>'
  p PAYOS_CHECKSUM_KEY '<...>'   # khoá ký webhook: lộ nó là giả được "đã trả tiền"

  # Gmail App Password (bật 2FA → Google Account → App passwords).
  aws ssm put-parameter --name $PREFIX/SMTP_HOST --type String --value smtp.gmail.com --overwrite
  aws ssm put-parameter --name $PREFIX/SMTP_PORT --type String --value 587 --overwrite
  aws ssm put-parameter --name $PREFIX/SMTP_USER --type String --value '<email>@gmail.com' --overwrite
  aws ssm put-parameter --name $PREFIX/SMTP_FROM --type String --value '<email>@gmail.com' --overwrite
  p SMTP_PASSWORD '<app password 16 ky tu, bo het dau cach>'

  # CHỈ nếu muốn bật trợ lý AI (khi đó đổi AI_PROVIDER sang anthropic):
  # p ANTHROPIC_API_KEY '<sk-ant-...>'
  # p VOYAGE_API_KEY    '<pa-...>'
  # aws ssm put-parameter --name $PREFIX/AI_PROVIDER --type String --value anthropic --overwrite

  Kiểm lại:
    # ĐỪNG dùng --query 'length(Parameters)': AWS CLI phân trang, và JMESPath được áp cho TỪNG
    # trang, nên bạn nhận một số mỗi trang (10 10 10 10 7) chứ không phải tổng. Đếm TÊN thì đúng:
    aws ssm get-parameters-by-path --path $PREFIX/ --recursive \
      --query 'Parameters[].Name' --output text | tr '\t' '\n' | wc -l

    # Và kiểm có thiếu biến bắt buộc nào không — báo thẳng TÊN, không chỉ đếm:
    bash deploy/scripts/pull-env.sh   # chạy trên máy chủ; ở đây chỉ để xem danh sách thiếu
================================================================
MANUAL
