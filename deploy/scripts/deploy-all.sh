#!/usr/bin/env bash
# Dựng toàn bộ hệ thống trên AWS bằng MỘT lệnh, chạy trong AWS CloudShell.
#
#   DOMAIN=concertth.site bash deploy/scripts/deploy-all.sh
#
# Gộp sáu việc: hạ tầng → bí mật → cài máy chủ → chờ DNS → chứng chỉ → bật hệ.
#
# IDEMPOTENT và CHẠY LẠI ĐƯỢC: mỗi giai đoạn tự kiểm xem đã xong chưa. Hỏng giữa đường thì sửa
# nguyên nhân rồi chạy lại cả lệnh, không phải lần mò xem đang ở đâu.
#
# Nhảy qua giai đoạn đã xong (gỡ lỗi): SKIP=1,2 bash deploy/scripts/deploy-all.sh
#
# BA THỨ SCRIPT NÀY KHÔNG LÀM ĐƯỢC, vì chúng ở ngoài tầm của AWS CLI:
#   - Lấy khoá payOS và Gmail App Password  -> nó HỎI bạn, nhập một lần
#   - Tạo 7 bản ghi DNS                      -> nó CHỜ, và chỉ rõ phải tạo gì
#   - Bấm duyệt environment trên GitHub      -> không liên quan tới lần dựng đầu
set -euo pipefail

DOMAIN="${DOMAIN:?Dat DOMAIN, vi du: DOMAIN=concertth.site bash deploy/scripts/deploy-all.sh}"
REGION="${REGION:-ap-southeast-1}"
NAME="${NAME:-nexaticket}"
PREFIX="${PREFIX:-/$NAME}"
SKIP="${SKIP:-}"
export AWS_DEFAULT_REGION="$REGION"

HERE="$(cd "$(dirname "$0")" && pwd)"
ok()   { printf '\033[32m  ✓\033[0m %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\033[31m\n  ✗ %s\033[0m\n' "$*" >&2; exit 1; }
phase() {
  printf '\n\033[1m━━━ Giai đoạn %s: %s\033[0m\n' "$1" "$2"
  case ",$SKIP," in *",$1,"*) info "bỏ qua (SKIP=$SKIP)"; return 1 ;; esac
  return 0
}

command -v aws >/dev/null || die "Không có AWS CLI. Script này chạy trong AWS CloudShell."
ACCOUNT=$(aws sts get-caller-identity --query Account --output text) \
  || die "Chưa đăng nhập AWS."
printf '\n\033[1mNexaTicket → AWS\033[0m\n  tài khoản %s · vùng %s · domain %s\n' \
  "$ACCOUNT" "$REGION" "$DOMAIN"

# ===========================================================================
if phase 1 "Hạ tầng (Security Group, IAM, EC2, Elastic IP)"; then
  bash "$HERE/aws-bootstrap.sh" || die "Hạ tầng thất bại. Đọc lỗi ở trên rồi chạy lại."
fi

INST=$(aws ec2 describe-instances \
        --filters "Name=tag:Name,Values=$NAME" "Name=instance-state-name,Values=running" \
        --query 'Reservations[0].Instances[0].InstanceId' --output text)
[ "$INST" != "None" ] || die "Không tìm thấy instance đang chạy. Chạy lại giai đoạn 1."
EIP=$(aws ec2 describe-addresses --filters "Name=tag:Name,Values=$NAME" \
       --query 'Addresses[0].PublicIp' --output text)
ok "instance $INST · Elastic IP $EIP"

# ===========================================================================
if phase 2 "Bí mật"; then
  DOMAIN="$DOMAIN" bash "$HERE/gen-secrets.sh" >/dev/null || die "gen-secrets.sh thất bại."
  ok "41 bí mật và URL đã nằm ở Parameter Store"

  # $1=tên  $2=kiểu(sec|str)  $3=mô tả  $4=mặc định  $5=luật kiểm (host|email|rỗng)
  #
  # `read -rs` cho khoá: không hiện ra màn hình, không vào ~/.bash_history. Giá trị đi thẳng từ bàn
  # phím vào Parameter Store — không qua file, không qua biến môi trường của tiến trình khác.
  #
  # Kiểm ĐỊNH DẠNG, không chỉ "có nhập hay chưa". Đã xảy ra thật: người dùng gõ địa chỉ email vào ô
  # SMTP_HOST. Giá trị được nhận, script báo ✓, và lỗi chỉ lộ ra nhiều bước sau — khi Keycloak không
  # gửi được thư tới một hostname không tồn tại. Dấu ✓ sau một giá trị sai còn tệ hơn không có dấu
  # gì: nó xác nhận một điều không đúng.
  #
  # Khai NGOÀI nhánh điều kiện bên dưới, vì bước đọc lại ở cuối luôn chạy và nó cần hàm này.
  ask() {
    local val
    while :; do
      if [ "$2" = sec ]; then
        printf '\n  %s\n  %s: ' "$3" "$1"; read -rs val; echo
      else
        printf '\n  %s\n  %s [%s]: ' "$3" "$1" "${4:-}"; read -r val
        [ -n "$val" ] || val="${4:-}"
      fi
      if [ -z "$val" ]; then info "bỏ qua $1"; return 0; fi
      case "${5:-}" in
        host)
          # Tên máy chủ: KHÔNG chứa @, và phải có ít nhất một dấu chấm.
          case "$val" in
            *@*) echo "    [sai] '$val' la dia chi email, khong phai ten may chu. Gmail: smtp.gmail.com" >&2
                 continue ;;
            *.*) ;;
            *)   echo "    [sai] '$val' khong giong ten may chu (thieu dau cham)." >&2
                 continue ;;
          esac ;;
        email)
          case "$val" in
            *@*.*) ;;
            *) echo "    [sai] '$val' khong giong dia chi email." >&2; continue ;;
          esac ;;
      esac
      break
    done
    local t=String; [ "$2" = sec ] && t=SecureString
    aws ssm put-parameter --name "$PREFIX/$1" --type "$t" --value "$val" --overwrite >/dev/null
    ok "$1"
  }

  EXTERNAL="SUPER_ADMIN_EMAILS SMTP_HOST SMTP_USER SMTP_FROM SMTP_PASSWORD
            PAYOS_CLIENT_ID PAYOS_API_KEY PAYOS_CHECKSUM_KEY"
  need_external=0
  for v in $EXTERNAL; do
    aws ssm get-parameter --name "$PREFIX/$v" >/dev/null 2>&1 || need_external=1
  done

  if [ "$need_external" -eq 0 ]; then
    ok "tám giá trị ngoài đã có"
  else
    cat <<'ASKNOTE'

    Tám giá trị cuối. Chuẩn bị sẵn:
      · Gmail App Password — Google Account > Security > 2-Step Verification > App passwords
        (16 ký tự, BỎ HẾT dấu cách khi dán)
      · Ba khoá payOS — trang quản trị merchant. Chưa có thì gõ `chua-co` rồi bật PAYMENT_SANDBOX.

    Bỏ trống rồi Enter = giữ giá trị đang có.
ASKNOTE
    ask SUPER_ADMIN_EMAILS str "Email được cấp SUPER_ADMIN ở lần đăng nhập đầu" "" email
    ask SMTP_HOST          str "Máy chủ SMTP — KHÔNG phải email của bạn" "smtp.gmail.com" host
    ask SMTP_USER          str "Tài khoản Gmail gửi thư" "" email
    ask SMTP_FROM          str "Địa chỉ hiện ở ô Người gửi" "" email
    ask SMTP_PASSWORD      sec "Gmail App Password (không hiện khi gõ)"
    ask PAYOS_CLIENT_ID    sec "payOS Client ID"
    ask PAYOS_API_KEY      sec "payOS API Key"
    ask PAYOS_CHECKSUM_KEY sec "payOS Checksum Key — khoá ký webhook, lộ là giả được 'đã trả tiền'"
  fi

  # Đọc lại — LUÔN chạy, kể cả khi đã bỏ qua phần hỏi.
  #
  # Luật kiểm định dạng không bắt được lỗi đánh máy: `ten@gmail.ocm` đúng dạng email nên nó qua, rồi
  # thư gửi vào hư không và lỗi lộ ra ở một chỗ không liên quan. Đây cũng là chỗ DUY NHẤT sửa được
  # một giá trị đã nạp sai ở lần chạy trước — nếu bước này nằm trong nhánh `else` thì lần chạy lại
  # sẽ bỏ qua nó, và người dùng không có đường nào để sửa.
  #
  # Chỉ in lại giá trị KHÔNG phải bí mật. Đọc bốn khoá kia ra màn hình là phá đúng điều mà
  # `read -rs` được dùng để bảo vệ.
  while :; do
    printf '\n  Đọc lại — bốn giá trị này đi vào thư gửi cho khách:\n\n'
    for v in SUPER_ADMIN_EMAILS SMTP_HOST SMTP_USER SMTP_FROM; do
      printf '    %-20s %s\n' "$v" \
        "$(aws ssm get-parameter --name "$PREFIX/$v" --query Parameter.Value --output text 2>/dev/null)"
    done
    printf '\n  Đúng cả chưa?  [Enter = đi tiếp · gõ tên biến để sửa] : '
    read -r answer
    case "$answer" in
      ''|y|Y|yes) break ;;
      SUPER_ADMIN_EMAILS) ask SUPER_ADMIN_EMAILS str "Email được cấp SUPER_ADMIN" "" email ;;
      SMTP_HOST)  ask SMTP_HOST str "Máy chủ SMTP — KHÔNG phải email của bạn" "smtp.gmail.com" host ;;
      SMTP_USER)  ask SMTP_USER str "Tài khoản Gmail gửi thư" "" email ;;
      SMTP_FROM)  ask SMTP_FROM str "Địa chỉ hiện ở ô Người gửi" "" email ;;
      SMTP_PASSWORD)      ask SMTP_PASSWORD      sec "Gmail App Password" ;;
      PAYOS_CLIENT_ID)    ask PAYOS_CLIENT_ID    sec "payOS Client ID" ;;
      PAYOS_API_KEY)      ask PAYOS_API_KEY      sec "payOS API Key" ;;
      PAYOS_CHECKSUM_KEY) ask PAYOS_CHECKSUM_KEY sec "payOS Checksum Key" ;;
      *) echo "    Enter để đi tiếp, hoặc gõ tên biến cần sửa (ví dụ: SMTP_USER)." ;;
    esac
  done
fi

# ===========================================================================
if phase 3 "Kiểm đủ biến bắt buộc"; then
  # Rút danh sách THẲNG từ prod.yml, bỏ dòng comment (dòng 8 có ví dụ ${X:?...} trong lời giải thích).
  req=$(grep -v '^[[:space:]]*#' "$HERE/../compose/prod.yml" \
         | grep -oE '[$][{][A-Z_]+:[?]' | tr -d '${:?' | sort -u)
  have=$(aws ssm get-parameters-by-path --path "$PREFIX/" --recursive \
          --query 'Parameters[].Name' --output text | tr '\t' '\n' | sed "s|^$PREFIX/||")
  missing=$(comm -23 <(echo "$req") <(echo "$have" | sort))
  if [ -n "$missing" ]; then
    printf '\n'
    for v in $missing; do printf '      - %s\n' "$v"; done
    die "Thiếu $(echo $missing | wc -w) biến bắt buộc. Chạy lại và nhập ở giai đoạn 2."
  fi
  ok "$(echo "$req" | wc -l) biến bắt buộc đã có đủ"

  # Tạo ngân sách Ở ĐÂY, không ở bootstrap: email đến từ SUPER_ADMIN_EMAILS, mà biến đó chỉ vừa
  # được nạp ở giai đoạn 2. Gọi trong bootstrap thì lần chạy đầu luôn bỏ qua và không ai nhận ra.
  bash "$HERE/budget-alert.sh" 2>&1 | sed 's/^/  /' || true
fi

# ===========================================================================
# Chạy lệnh trên máy chủ qua SSM, KHÔNG qua SSH.
#
# Vì sao SSM: không cần mở cổng 22, không cần khoá, và không phụ thuộc IP — rule cổng 22 mà
# aws-bootstrap.sh tạo trỏ vào IP của CloudShell, mà IP đó đổi giữa các phiên.
#
# Chạy bằng user `ubuntu`, không phải root: SSM thực thi dưới quyền root, và `git clone` dưới root
# sẽ tạo cây /srv/nexaticket thuộc root — sau đó mọi lệnh git và docker của `ubuntu` đều hỏng vì
# quyền. `ubuntu` có sudo không mật khẩu nên bên trong script vẫn làm được việc cần root.
on_server() {
  local desc="$1" script="$2" timeout="${3:-900}"
  info "$desc"
  local cid
  cid=$(aws ssm send-command --instance-ids "$INST" \
          --document-name AWS-RunShellScript \
          --comment "$desc" \
          --timeout-seconds "$timeout" \
          --parameters "commands=[\"sudo -u ubuntu bash -lc '$script'\"]" \
          --query 'Command.CommandId' --output text) || die "Không gửi được lệnh qua SSM."
  local st=''
  while :; do
    st=$(aws ssm get-command-invocation --command-id "$cid" --instance-id "$INST" \
          --query Status --output text 2>/dev/null || echo Pending)
    case "$st" in Success|Failed|TimedOut|Cancelled) break ;; esac
    sleep 5
  done
  aws ssm get-command-invocation --command-id "$cid" --instance-id "$INST" \
    --query StandardOutputContent --output text | sed 's/^/      /'
  if [ "$st" != Success ]; then
    aws ssm get-command-invocation --command-id "$cid" --instance-id "$INST" \
      --query StandardErrorContent --output text | sed 's/^/      /' >&2
    die "$desc — thất bại ($st)."
  fi
  ok "$desc"
}

if phase 4 "Cài máy chủ"; then
  # SSM Agent cần vài phút sau khi máy boot mới đăng ký xong. Chờ thay vì hỏng.
  info "chờ SSM Agent đăng ký (thường 1–3 phút)"
  for i in $(seq 1 40); do
    n=$(aws ssm describe-instance-information \
         --filters "Key=InstanceIds,Values=$INST" \
         --query 'length(InstanceInformationList)' --output text 2>/dev/null || echo 0)
    [ "$n" = "1" ] && break
    sleep 10
  done
  [ "${n:-0}" = "1" ] || die "SSM Agent chưa đăng ký sau 400s. Kiểm IAM role của instance."
  ok "SSM Agent sẵn sàng"

  on_server "cài Docker, AWS CLI, swap, Nginx, certbot, mã nguồn" \
    "curl -fsSL https://raw.githubusercontent.com/uchihathien/Concert-Ticket-Platform/main/deploy/scripts/server-setup.sh | bash" \
    1800
  on_server "sinh .env từ Parameter Store" "nexa-env"
fi

# ===========================================================================
HOSTS="@ to-chuc soat-ve quan-tri api tai-khoan media"
fqdn() { [ "$1" = "@" ] && echo "$DOMAIN" || echo "$1.$DOMAIN"; }

# Hỏi THẲNG 8.8.8.8 chứ không qua resolver của hệ thống: resolver cache cả câu trả lời phủ định,
# nên sau khi bạn tạo bản ghi nó vẫn trả về "không có" thêm vài phút nữa — và vòng chờ bên dưới sẽ
# đứng mãi dù DNS đã đúng.
#
# Ba công cụ vì không công cụ nào chắc chắn có: `dig` nằm trong bind-utils và KHÔNG phải bản
# CloudShell nào cũng cài sẵn. `getent` là lối cuối, dùng resolver hệ thống nên có nhược điểm trên.
resolve() {
  if command -v dig >/dev/null 2>&1; then
    dig +short "$1" @8.8.8.8 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | tail -1
  elif command -v nslookup >/dev/null 2>&1; then
    # CHỈ đọc phần sau dòng `Name:`. Dòng `Address:` đầu tiên là địa chỉ của chính máy chủ DNS,
    # nên đọc bừa sẽ trả về 8.8.8.8 cho mọi tên KHÔNG tồn tại — một kết quả trông như hợp lệ.
    nslookup "$1" 8.8.8.8 2>/dev/null | awk '/^Name:/ {ans=1} ans && /^Address(es)?:/ {a=$2} END {print a}'
  elif command -v host >/dev/null 2>&1; then
    host -t A "$1" 8.8.8.8 2>/dev/null | awk '/has address/ {print $NF}' | tail -1
  else
    getent hosts "$1" 2>/dev/null | awk '{print $1}' | tail -1
  fi
}

if phase 5 "Chờ DNS"; then
  # ĐÂY LÀ VIỆC DUY NHẤT SCRIPT KHÔNG LÀM HỘ ĐƯỢC.
  #
  # Nameserver của domain này không phải Route 53, nên AWS CLI không chạm tới được. Phải vào trang
  # quản trị domain tạo tay.
  #
  # CHỜ cho tới khi đúng, chứ không chạy certbot rồi hỏng: Let's Encrypt giới hạn 5 lần thất bại
  # mỗi giờ cho mỗi bộ tên. Thử sai vài lần là bị khoá một tiếng, và lúc đó không có cách nào đi nhanh hơn.
  printf '\n    Vào trang quản trị DNS của %s, tạo BẢY bản ghi A — tất cả trỏ về %s:\n\n' "$DOMAIN" "$EIP"
  for h in $HOSTS; do printf '      %-12s A   %s   (TTL 300)\n' "$h" "$EIP"; done
  printf '\n    TTL 300 để sửa sai nhanh; nâng lên sau khi đã chạy ổn.\n'
  printf '    Script tự phát hiện khi cả bảy đúng. Ctrl-C để dừng và làm tiếp sau bằng SKIP=1,2,3,4\n\n'

  for round in $(seq 1 180); do      # 180 × 10s = 30 phút
    bad=""
    for h in $HOSTS; do
      got=$(resolve "$(fqdn "$h")")
      [ "$got" = "$EIP" ] || bad="$bad $(fqdn "$h")"
    done
    if [ -z "$bad" ]; then dns_ok=1; ok "cả bảy tên đã trỏ về $EIP"; break; fi
    printf '\r    chờ %-4s còn thiếu: %-60s' "$((round * 10))s" "$(echo $bad | cut -c1-60)"
    sleep 10
  done
  # Cờ riêng, KHÔNG kiểm `[ -z "${bad:-x}" ]`: `:-` thay thế khi biến rỗng HAY chưa đặt, nên khi
  # DNS đã đúng (bad rỗng) nó vẫn trả 'x' và script chết ngay lúc thành công.
  [ "${dns_ok:-0}" = 1 ] || die "DNS chưa đúng sau 30 phút. Kiểm lại trang quản trị rồi chạy: SKIP=1,2,3,4 bash $0"
fi

# ===========================================================================
if phase 6 "Chứng chỉ HTTPS và bật hệ thống"; then
  CERT_EMAIL="${CERT_EMAIL:-$(aws ssm get-parameter --name "$PREFIX/SUPER_ADMIN_EMAILS" \
                --query Parameter.Value --output text | cut -d, -f1)}"
  d_args=""
  for h in $HOSTS; do d_args="$d_args -d $(fqdn "$h")"; done

  # MỘT chứng chỉ cho cả bảy tên. `--redirect` để certbot tự thêm chuyển hướng 80→443 vào Nginx —
  # thiếu nó thì cookie __Secure- không được đặt và vòng đăng nhập quay mãi không vào được.
  on_server "xin chứng chỉ Let's Encrypt cho 7 tên" \
    "sudo certbot --nginx --redirect --agree-tos --no-eff-email -n -m $CERT_EMAIL$d_args" 600

  # `--wait` trả về khi container HEALTHY. Lần đầu 5–10 phút: Postgres chạy migration của 12 service,
  # Keycloak nhập realm.
  on_server "bật 22 container (lần đầu 5–10 phút)" "sudo systemctl enable --now nexaticket" 1800
  on_server "kiểm khói" "cd /srv/nexaticket && ./deploy/scripts/smoke.sh" 300
fi

# ===========================================================================
SG=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$NAME-web" \
      --query 'SecurityGroups[0].GroupId' --output text)
cat <<SUMMARY

$(printf '\033[1m━━━ XONG\033[0m')

  Trang khách     https://$DOMAIN
  Ban tổ chức     https://to-chuc.$DOMAIN
  Soát vé         https://soat-ve.$DOMAIN
  Tổng công ty    https://quan-tri.$DOMAIN
  API             https://api.$DOMAIN
  Tài khoản       https://tai-khoan.$DOMAIN

  Đăng nhập lần đầu bằng email đã khai ở SUPER_ADMIN_EMAILS — tài khoản đó được cấp
  SUPER_ADMIN, và nó là cửa duy nhất để tạo tổ chức đầu tiên.

  TẮT MÁY KHI KHÔNG DÙNG — rẻ hơn vài lần, Elastic IP giữ nguyên:
    aws ec2 stop-instances  --instance-ids $INST
    aws ec2 start-instances --instance-ids $INST   # rồi: systemctl start nexaticket

  Bật tự động deploy — thêm vào GitHub > Settings > Secrets and variables > Actions:
    DEPLOY_TARGET   = ubuntu@$EIP
    AWS_SG_ID       = $SG
    AWS_DEPLOY_ROLE = arn:aws:iam::$ACCOUNT:role/$NAME-github
  (cộng DEPLOY_SSH_KEY và DEPLOY_HOST_KEY nếu muốn deploy qua SSH)

  Vào máy chủ, không cần khoá và không cần mở cổng 22:
    aws ssm start-session --target $INST
SUMMARY
