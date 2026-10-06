#!/usr/bin/env bash
# Tao canh bao chi tieu cho tai khoan.
#
#   BUDGET_EMAIL=ban@example.com bash deploy/scripts/budget-alert.sh
#
# TACH RIENG khoi aws-bootstrap.sh vi mot loi THU TU: email lay tu SUPER_ADMIN_EMAILS o Parameter
# Store, ma bien do chi duoc nap o giai doan 2 (gen-secrets) — tuc la SAU bootstrap. Nen o lan chay
# dau tien, bootstrap luon bo qua viec tao ngan sach va khong ai nhan ra.
#
# deploy-all.sh goi script nay SAU khi bi mat da san sang.
set -euo pipefail

NAME="${NAME:-nexaticket}"
export AWS_DEFAULT_REGION="${REGION:-ap-southeast-1}"
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
say() { printf '
=== %s
' "$*"; }

# --- Cảnh báo chi tiêu -------------------------------------------------------
# Tạo NGAY lúc dựng hạ tầng, không để "làm sau".
#
# t3.xlarge quên tắt một tuần là ~35 USD; quên cả tháng là ~150 USD. Ngân sách không CHẶN được chi
# tiêu — AWS không có công tắc đó — nhưng nó gửi email trước khi con số thành chuyện lớn.
#
# Không chặn luồng nếu thất bại: thiếu quyền budgets:CreateBudget là chuyện thường với IAM user hẹp,
# và đó không phải lý do để dừng cả lần dựng.
say "Cảnh báo chi tiêu"
BUDGET_LIMIT="${BUDGET_LIMIT:-20}"
BUDGET_EMAIL="${BUDGET_EMAIL:-$(aws ssm get-parameter --name "/$NAME/SUPER_ADMIN_EMAILS" \
                 --query Parameter.Value --output text 2>/dev/null | cut -d, -f1 || true)}"

if [ -z "${BUDGET_EMAIL:-}" ] || [ "$BUDGET_EMAIL" = "None" ]; then
  echo "  Bỏ qua: chưa biết email. Chạy gen-secrets.sh trước, hoặc BUDGET_EMAIL=ban@example.com"
elif aws budgets describe-budget --account-id "$ACCOUNT" \
       --budget-name "$NAME-monthly" >/dev/null 2>&1; then
  echo "  Đã có ngân sách $NAME-monthly"
else
  # Hai mốc: 80% chi tiêu THỰC, và 100% DỰ BÁO. Mốc dự báo quan trọng hơn — nó cảnh báo khi đà chi
  # tiêu sẽ vượt ngưỡng, tức là trước khi tiền đã mất.
  if aws budgets create-budget --account-id "$ACCOUNT" \
      --budget "{\"BudgetName\":\"$NAME-monthly\",
                 \"BudgetLimit\":{\"Amount\":\"$BUDGET_LIMIT\",\"Unit\":\"USD\"},
                 \"TimeUnit\":\"MONTHLY\",\"BudgetType\":\"COST\"}" \
      --notifications-with-subscribers "[
        {\"Notification\":{\"NotificationType\":\"ACTUAL\",\"ComparisonOperator\":\"GREATER_THAN\",
          \"Threshold\":80,\"ThresholdType\":\"PERCENTAGE\"},
         \"Subscribers\":[{\"SubscriptionType\":\"EMAIL\",\"Address\":\"$BUDGET_EMAIL\"}]},
        {\"Notification\":{\"NotificationType\":\"FORECASTED\",\"ComparisonOperator\":\"GREATER_THAN\",
          \"Threshold\":100,\"ThresholdType\":\"PERCENTAGE\"},
         \"Subscribers\":[{\"SubscriptionType\":\"EMAIL\",\"Address\":\"$BUDGET_EMAIL\"}]}]" 2>/dev/null; then
    echo "  Ngân sách $BUDGET_LIMIT USD/tháng → email $BUDGET_EMAIL (80% thực, 100% dự báo)"
  else
    echo "  Không tạo được (thường do thiếu quyền budgets:CreateBudget)."
    echo "  Tạo tay: Billing and Cost Management > Budgets > Create budget > ngưỡng $BUDGET_LIMIT USD"
  fi
fi
