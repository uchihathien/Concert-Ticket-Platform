#!/usr/bin/env bash
# Dựng hạ tầng AWS cho NexaTicket: Security Group, IAM role, EC2, Elastic IP.
#
# CHẠY Ở ĐÂU: AWS CloudShell (biểu tượng terminal góc trên phải AWS Console). Ở đó `aws` đã có sẵn
# và đã đăng nhập, nên không phải cài gì trên máy bạn và không có access key nào nằm trên đĩa.
#
#   bash aws-bootstrap.sh
#
# IDEMPOTENT: chạy lại nhiều lần không tạo thêm bản sao. An toàn khi bị gián đoạn giữa chừng.
#
# KHÔNG tạo RDS, NAT Gateway hay Load Balancer — xem lý do trong phần kiến trúc: với một instance
# duy nhất, NAT Gateway thêm ~35 USD/tháng mà không thêm lớp bảo vệ nào (cổng đã không publish).
set -euo pipefail

REGION="${REGION:-ap-southeast-1}"
NAME="${NAME:-nexaticket}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t3.xlarge}"
VOLUME_SIZE="${VOLUME_SIZE:-80}"

export AWS_DEFAULT_REGION="$REGION"
say() { printf '\n=== %s\n' "$*"; }

# Thu muc tam cho thong diep loi cua cac lenh duoc phep that bai.
TMPDIR_OIDC=$(mktemp -d)
trap 'rm -rf "$TMPDIR_OIDC"' EXIT

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
say "Tài khoản $ACCOUNT · vùng $REGION · loại máy $INSTANCE_TYPE"

# --- VPC mặc định ------------------------------------------------------------
VPC=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true \
        --query 'Vpcs[0].VpcId' --output text)
[ "$VPC" != "None" ] || { echo "Không có VPC mặc định ở $REGION. Tạo bằng: aws ec2 create-default-vpc" >&2; exit 1; }
SUBNET=$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC" \
          --query 'Subnets[0].SubnetId' --output text)
echo "VPC $VPC · subnet $SUBNET"

# --- Security Group ----------------------------------------------------------
# 80/443 mở cho internet: đây là cổng vào DUY NHẤT.
# 22 chỉ mở cho IP hiện tại của bạn. KHÔNG mở 22 cho 0.0.0.0/0 — trong vài giờ sẽ có hàng nghìn
# lượt dò mật khẩu. CI mở/đóng rule riêng cho runner của nó (xem release.yml).
#
# KHÔNG mở 5432/6379/5672/9000/11434. prod.yml bind chúng vào 127.0.0.1, nhưng một rule gõ sai ở
# đây là phơi Postgres ra internet — và Postgres mở bị quét thấy trong vài phút.
say "Security Group"
SG=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$NAME-web" \
      "Name=vpc-id,Values=$VPC" --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo None)
if [ "$SG" = "None" ]; then
  SG=$(aws ec2 create-security-group --group-name "$NAME-web" \
        --description "NexaTicket web (80/443 public, 22 han che)" \
        --vpc-id "$VPC" --query GroupId --output text)
  echo "Đã tạo $SG"
else
  echo "Đã có $SG"
fi

MYIP=$(curl -s --max-time 10 https://checkip.amazonaws.com || true)
for rule in "80:0.0.0.0/0" "443:0.0.0.0/0" "22:${MYIP:-127.0.0.1}/32"; do
  port="${rule%%:*}"; cidr="${rule#*:}"
  aws ec2 authorize-security-group-ingress --group-id "$SG" \
    --protocol tcp --port "$port" --cidr "$cidr" >/dev/null 2>&1 \
    && echo "  mở $port ← $cidr" || echo "  $port ← $cidr (đã có)"
done

# --- IAM role cho instance ---------------------------------------------------
# Role, KHÔNG phải access key: instance nhận credential tạm tự động và không có khoá dài hạn nào
# nằm trên đĩa để bị lộ. Hai quyền: SSM (mở shell không cần cổng 22) và đọc bí mật ở Parameter Store.
say "IAM role"
if ! aws iam get-role --role-name "$NAME-ec2" >/dev/null 2>&1; then
  aws iam create-role --role-name "$NAME-ec2" --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},
                  "Action":"sts:AssumeRole"}]}' >/dev/null
  echo "Đã tạo role $NAME-ec2"
else
  echo "Đã có role $NAME-ec2"
fi

aws iam attach-role-policy --role-name "$NAME-ec2" \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore >/dev/null

# Chỉ đọc, và chỉ dưới /nexaticket/ — không phải toàn bộ Parameter Store của tài khoản.
aws iam put-role-policy --role-name "$NAME-ec2" --policy-name ssm-read \
  --policy-document "{
    \"Version\":\"2012-10-17\",
    \"Statement\":[{
      \"Effect\":\"Allow\",
      \"Action\":[\"ssm:GetParameter\",\"ssm:GetParameters\",\"ssm:GetParametersByPath\"],
      \"Resource\":\"arn:aws:ssm:$REGION:$ACCOUNT:parameter/$NAME/*\"
    },{
      \"Effect\":\"Allow\",\"Action\":\"kms:Decrypt\",
      \"Resource\":\"*\",
      \"Condition\":{\"StringEquals\":{\"kms:ViaService\":\"ssm.$REGION.amazonaws.com\"}}
    }]}" >/dev/null

# Instance profile la thu EC2 nhan; role chi la thu nam TRONG no. Hai doi tuong khac nhau, o day
# dat cung ten cho de nho.
#
# KHONG dung `|| true` de bo qua loi. Ban truoc lam vay, nen khi `create-instance-profile` that bai
# thi khong ai thay — loi chi lo ra may chuc dong sau, o `run-instances`, duoi dang
# "Invalid IAM Instance Profile name", va trong nhu loi cua EC2 chu khong phai cua IAM.
if ! aws iam get-instance-profile --instance-profile-name "$NAME-ec2" >/dev/null 2>&1; then
  aws iam create-instance-profile --instance-profile-name "$NAME-ec2" >/dev/null
  echo "Da tao instance profile $NAME-ec2"
fi

if ! aws iam get-instance-profile --instance-profile-name "$NAME-ec2" --output text \
      --query 'InstanceProfile.Roles[].RoleName' | grep -qw "$NAME-ec2"; then
  aws iam add-role-to-instance-profile --instance-profile-name "$NAME-ec2" --role-name "$NAME-ec2"
  echo "Da gan role vao instance profile"
fi
echo "Quyền: SSM session + đọc /$NAME/* ở Parameter Store"

# --- Khoá SSH ----------------------------------------------------------------
say "Khoá SSH"
if aws ec2 describe-key-pairs --key-names "$NAME" >/dev/null 2>&1; then
  echo "Đã có key pair '$NAME' (file .pem chỉ tải được một lần, lúc tạo)"
else
  aws ec2 create-key-pair --key-name "$NAME" --query KeyMaterial --output text > "$NAME.pem"
  chmod 400 "$NAME.pem"
  echo "Đã lưu $(pwd)/$NAME.pem — TẢI VỀ MÁY NGAY, CloudShell xoá sau 120 ngày không dùng"
fi

# --- EC2 ---------------------------------------------------------------------
say "EC2"
INST=$(aws ec2 describe-instances \
        --filters "Name=tag:Name,Values=$NAME" "Name=instance-state-name,Values=pending,running,stopped" \
        --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || echo None)

if [ "$INST" = "None" ]; then
  # AMI lấy từ Parameter Store công khai của Canonical: luôn là bản Ubuntu 24.04 mới nhất đã vá,
  # thay vì một AMI ID ghim cứng sẽ cũ dần và mang lỗ hổng chưa vá.
  AMI=$(aws ssm get-parameters \
    --names /aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id \
    --query 'Parameters[0].Value' --output text)
  echo "AMI $AMI (Ubuntu 24.04 LTS, amd64)"

  # amd64 CỐ Ý, không phải arm64: release.yml build ảnh trên ubuntu-latest mà không khai
  # `platforms`, nên ảnh là amd64. Chạy chúng trên Graviton phải giả lập qua QEMU — chậm gấp nhiều lần.
  # IAM la EVENTUALLY CONSISTENT: instance profile vua tao chua chac EC2 da thay. Goi ngay thi
  # nhan "Invalid IAM Instance Profile name" — mot loi nghe nhu khai sai ten, nhung that ra chi la
  # goi qua som. Da xay ra that o lan chay dau tien.
  #
  # Thu lai thay vi sleep co dinh: cho du lau thi lang phi thoi gian cua moi lan chay sau, cho
  # qua ngan thi van hong — va khong ai biet con so dung la bao nhieu.
  launch() {
    aws ec2 run-instances \
      --image-id "$AMI" \
      --instance-type "$INSTANCE_TYPE" \
      --key-name "$NAME" \
      --security-group-ids "$SG" \
      --subnet-id "$SUBNET" \
      --iam-instance-profile "Name=$NAME-ec2" \
      --block-device-mappings "[{\"DeviceName\":\"/dev/sda1\",\"Ebs\":{
          \"VolumeSize\":$VOLUME_SIZE,\"VolumeType\":\"gp3\",
          \"DeleteOnTermination\":true,\"Encrypted\":true}}]" \
      --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$NAME}]" \
      --metadata-options 'HttpTokens=required' \
      --query 'Instances[0].InstanceId' --output text
  }

  for attempt in $(seq 1 12); do
    if INST=$(launch 2>/tmp/run-err); then break; fi
    # Tai khoan goi Free Tier (loai tai khoan moi cua AWS tu 2025) KHONG duoc chay instance
    # ngoai danh sach free-tier. Day la chan cung o phia AWS, khong phai van de cau hinh —
    # thu lai bao nhieu lan cung vo ich, nen dung ngay va noi ro phai lam gi.
    if grep -q "not eligible for Free Tier" /tmp/run-err; then
      cat >&2 <<FREETIER

  Tai khoan AWS cua ban dang o goi FREE TIER, va goi do chi cho phep instance
  thuoc danh sach free-tier (t3.micro — 1 GB RAM).

  He thong nay la 22 container, can khoang 11-12 GB. 1 GB khong du cho rieng
  Keycloak, nen khong co cach tinh chinh nao cuu duoc.

  CACH DUY NHAT: nang tai khoan len goi tra phi.
    Console > Billing and Cost Management > tim phan goi tai khoan / Free tier
    > Upgrade to paid plan.
  Credit mien phi dang co VAN GIU NGUYEN sau khi nang.

  Chi phi thuc te neu TAT MAY ngoai gio lam viec (4h/ngay x 20 ngay):
    t3.xlarge  ~0.21 USD/gio  ->  ~17 USD/thang
    EBS 80 GB                 ->  ~8 USD/thang
    IPv4                      ->  ~3.6 USD/thang
  Voi 100 USD credit thi du khoang 3-4 thang.

  Sau khi nang, nho dat canh bao chi tieu:
    Billing > Budgets > Create budget > nguong 20 USD

  Roi chay lai: DOMAIN=concertth.site bash deploy/scripts/deploy-all.sh
FREETIER
      exit 1
    fi
    if ! grep -q "Invalid IAM Instance Profile" /tmp/run-err; then
      cat /tmp/run-err >&2; exit 1       # loi khac: dung ngay, dung thu lai mu quang
    fi
    echo "  IAM chua lan truyen, thu lai ($attempt/12)..."
    sleep 5
  done
  [ -n "${INST:-}" ] && [ "$INST" != "None" ] || { echo "Khong tao duoc instance sau 60s." >&2; exit 1; }
  echo "Đã tạo $INST — chờ running..."
  aws ec2 wait instance-running --instance-ids "$INST"
else
  echo "Đã có $INST"
  aws ec2 start-instances --instance-ids "$INST" >/dev/null 2>&1 || true
  aws ec2 wait instance-running --instance-ids "$INST"
fi

# `Encrypted=true`: mã hoá ổ đĩa, miễn phí, không có lý do để tắt.
# `HttpTokens=required`: bắt buộc IMDSv2 — chặn lớp tấn công SSRF đọc credential của instance
# qua metadata endpoint. Mặc định của AWS vẫn cho IMDSv1, nên phải khai tường minh.

# --- Elastic IP --------------------------------------------------------------
# Cần IP cố định vì DNS trỏ vào nó: IP công khai mặc định ĐỔI sau mỗi lần stop/start, và cả bảy
# bản ghi DNS sẽ trỏ sai. EIP miễn phí khi đang gắn vào instance chạy.
say "Elastic IP"
ALLOC=$(aws ec2 describe-addresses --filters "Name=tag:Name,Values=$NAME" \
         --query 'Addresses[0].AllocationId' --output text 2>/dev/null || echo None)
if [ "$ALLOC" = "None" ]; then
  ALLOC=$(aws ec2 allocate-address --query AllocationId --output text)
  aws ec2 create-tags --resources "$ALLOC" --tags "Key=Name,Value=$NAME"
fi
aws ec2 associate-address --instance-id "$INST" --allocation-id "$ALLOC" >/dev/null
EIP=$(aws ec2 describe-addresses --allocation-ids "$ALLOC" --query 'Addresses[0].PublicIp' --output text)

# --- IAM role cho GitHub Actions (OIDC) — TUỲ CHỌN ---------------------------
# Chỉ phục vụ một việc: để CI tự mở/đóng cổng 22 quanh mỗi lần deploy, thay vì để 22 mở cho cả
# internet. Mọi thứ khác của hệ thống KHÔNG phụ thuộc vào nó.
#
# OIDC chứ không phải access key lưu trong secret: GitHub xuất trình một token ngắn hạn do chính nó
# ký, AWS đổi lấy credential tạm. Không có khoá dài hạn nào tồn tại để bị lộ hay phải xoay vòng.
#
# KHÔNG CHẶN LUỒNG nếu thất bại. Tài khoản do trường hoặc tổ chức cấp thường nằm trong một AWS
# Organization có Service Control Policy chặn `iam:CreateOpenIDConnectProvider`, và người dùng không
# sửa được SCP từ bên trong. Dừng cả lần dựng vì một tính năng tuỳ chọn là sai.
say "IAM role cho GitHub Actions (tuỳ chọn)"
GH_REPO="${GH_REPO:-uchihathien/Concert-Ticket-Platform}"
OIDC_ARN="arn:aws:iam::$ACCOUNT:oidc-provider/token.actions.githubusercontent.com"
OIDC_OK=1

if ! aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null 2>&1; then
  if aws iam create-open-id-connect-provider \
       --url https://token.actions.githubusercontent.com \
       --client-id-list sts.amazonaws.com \
       --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 >/dev/null 2>"$TMPDIR_OIDC/err"; then
    echo "  Đã khai GitHub làm nhà cung cấp OIDC"
  else
    OIDC_OK=0
    if grep -qE "service control policy|AccessDenied|explicit deny" "$TMPDIR_OIDC/err"; then
      cat <<'SCPNOTE'
  BỎ QUA — tài khoản này bị chặn bởi Service Control Policy của AWS Organization.

  Thường gặp ở tài khoản do trường hoặc tổ chức cấp. Bạn không sửa được SCP từ bên trong tài khoản
  thành viên; phải nhờ người quản lý Organization, và việc đó không đáng cho một tính năng tuỳ chọn.

  HỆ QUẢ: chỉ mất phần CI tự mở/đóng cổng 22. Toàn bộ phần còn lại chạy bình thường.
  Khi bật auto-deploy trên GitHub, ĐỪNG khai secret AWS_SG_ID — release.yml tự nhảy qua ba bước đó.
SCPNOTE
    else
      echo "  Không khai được nhà cung cấp OIDC:" >&2
      sed 's/^/    /' "$TMPDIR_OIDC/err" >&2
      echo "  Bỏ qua và đi tiếp — đây là tính năng tuỳ chọn." >&2
    fi
  fi
else
  echo "  Đã có nhà cung cấp OIDC"
fi

if [ "$OIDC_OK" = 1 ]; then
  # `sub` ghim ĐÚNG repo và ĐÚNG nhánh main. Thiếu điều kiện này thì bất kỳ workflow nào của bất kỳ
  # repo nào trên GitHub cũng assume được role — một lỗi cấu hình rất phổ biến và rất tốn kém.
  if ! aws iam get-role --role-name "$NAME-github" >/dev/null 2>&1; then
    aws iam create-role --role-name "$NAME-github" --assume-role-policy-document "{
      \"Version\":\"2012-10-17\",
      \"Statement\":[{
        \"Effect\":\"Allow\",
        \"Principal\":{\"Federated\":\"$OIDC_ARN\"},
        \"Action\":\"sts:AssumeRoleWithWebIdentity\",
        \"Condition\":{
          \"StringEquals\":{\"token.actions.githubusercontent.com:aud\":\"sts.amazonaws.com\"},
          \"StringLike\":{\"token.actions.githubusercontent.com:sub\":\"repo:$GH_REPO:ref:refs/heads/main\"}
        }}]}" >/dev/null
    echo "Đã tạo role $NAME-github cho $GH_REPO (chỉ nhánh main)"
  else
    echo "Đã có role $NAME-github"
  fi

  # Quyền HẸP NHẤT có thể: chỉ sửa ingress của đúng một Security Group.
  aws iam put-role-policy --role-name "$NAME-github" --policy-name sg-ingress   --policy-document "{
      \"Version\":\"2012-10-17\",
      \"Statement\":[{
        \"Effect\":\"Allow\",
        \"Action\":[\"ec2:AuthorizeSecurityGroupIngress\",\"ec2:RevokeSecurityGroupIngress\"],
        \"Resource\":\"arn:aws:ec2:$REGION:$ACCOUNT:security-group/$SG\"
      }]}" >/dev/null
  GH_ROLE_ARN="arn:aws:iam::$ACCOUNT:role/$NAME-github"
else
  # Khong co nha cung cap OIDC thi role nay vo dung: trust policy cua no tro vao chinh ARN do.
  GH_ROLE_ARN="(bo qua - SCP chan OIDC)"
fi

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

cat <<SUMMARY

================================================================
  XONG. Instance : $INST
        Elastic IP: $EIP
        SG        : $SG
        GitHub secrets cần tạo:
          DEPLOY_TARGET    = ubuntu@$EIP
          AWS_SG_ID        = $(if [ "$OIDC_OK" = 1 ]; then echo "$SG"; else echo "ĐỪNG khai — xem bên dưới"; fi)
          AWS_DEPLOY_ROLE  = $GH_ROLE_ARN
$(if [ "$OIDC_OK" != 1 ]; then cat <<'NOTE'
        SCP của Organization chặn OIDC, nên CI không tự mở/đóng được cổng 22.
        ĐỪNG khai AWS_SG_ID — release.yml tự nhảy qua ba bước đó và deploy bằng SSH.
        Khi đó cổng 22 phải mở cho runner bằng cách khác (xem mục 5.2 trong hướng dẫn).
NOTE
fi)

  BƯỚC TIẾP THEO — theo đúng thứ tự:

  1. Trỏ BẢY bản ghi A về $EIP (xem deploy/nginx/nexaticket.conf).
     Kiểm: dig +short concertth.site   →  phải ra $EIP
  2. bash deploy/scripts/gen-secrets.sh        (sinh bí mật vào Parameter Store)
  3. ssh -i $NAME.pem ubuntu@$EIP
  4. Chạy phần cài máy chủ trong docs/04-operations/runbooks/

  TẮT MÁY KHI KHÔNG DÙNG — đòn tiết kiệm lớn nhất:
     aws ec2 stop-instances --instance-ids $INST
     aws ec2 start-instances --instance-ids $INST   (EIP giữ nguyên)
================================================================
SUMMARY
