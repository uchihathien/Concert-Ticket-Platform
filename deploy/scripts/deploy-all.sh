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
NAME="${NAME:-nexaticket}"
PREFIX="${PREFIX:-/$NAME}"
SKIP="${SKIP:-}"
export AWS_DEFAULT_REGION="$REGION" AWS_REGION="$REGION"

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

# CHỐT CHẶN VÙNG. Nếu đã có instance mang tag này ở một vùng KHÁC, dừng ngay.
#
# Vùng sai không hỏng một cách rõ ràng — nó giả dạng thành lỗi khác: tham số "không tồn tại", bucket
# "sai LocationConstraint", và tệ nhất là "explicit deny in a service control policy" khi tổ chức chỉ
# cho phép một vùng. Thông điệp cuối nói về `ssm:GetParameters`, nên người đọc đi tìm quyền SSM trong
# khi thứ sai là vùng. Đã mất nhiều lần chạy vì đúng chuyện này.
for r in $(aws ec2 describe-regions --query 'Regions[].RegionName' --output text 2>/dev/null); do
  [ "$r" = "$REGION" ] && continue
  other=$(aws ec2 describe-instances --region "$r" \
            --filters "Name=tag:Name,Values=$NAME" "Name=instance-state-name,Values=running,stopped" \
            --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || echo None)
  [ "$other" = "None" ] && continue
  cat >&2 <<REGIONMISMATCH

  [DUNG] Da co instance '$NAME' ($other) o vung $r, nhung script dang chay voi vung $REGION.

  Dung tiep o $REGION se tao mot bo tai nguyen THU HAI, va bo cu van tinh tien.
  Chon mot:
    REGION=$r DOMAIN=$DOMAIN bash $0
    aws ec2 terminate-instances --region $r --instance-ids $other

REGIONMISMATCH
  exit 1
done

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
  # $4 = "soft": TRẢ VỀ mã lỗi thay vì dừng cả script. Cần cho bước có đường dự phòng — `die`
  # là `exit 1`, nên `if ! on_server ...` sẽ không bao giờ chạy tới nhánh thứ hai.
  local desc="$1" script="$2" timeout="${3:-900}" mode="${4:-hard}"
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
    # stderr của SSM bị cắt ở 24.000 ký tự, và phần bị cắt là phần ĐẦU — tức là dòng lỗi thật
    # thường nằm trong chỗ bị mất. In ra những gì có, rồi chỉ đường lấy log đầy đủ trên máy chủ.
    echo "  --- stderr (SSM cắt ở 24.000 ký tự) ---" >&2
    aws ssm get-command-invocation --command-id "$cid" --instance-id "$INST" \
      --query StandardErrorContent --output text | sed 's/^/      /' >&2
    cat >&2 <<HINT
  --- lấy log đầy đủ ---
    aws ssm get-command-invocation --command-id $cid --instance-id $INST \
      --query StandardErrorContent --output text
  --- hoặc vào thẳng máy chủ xem (không cần khoá, không cần cổng 22) ---
    aws ssm start-session --target $INST
HINT
    if [ "$mode" = soft ]; then return 1; fi
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
  # Thử để MÁY CHỦ tự đọc Parameter Store trước — đó là đường sạch nhất: bí mật không đi qua đâu
  # ngoài giữa instance và SSM API.
  #
  # Service Control Policy của Organization có thể chặn (đã xảy ra trên tài khoản do tổ chức cấp:
  # chặn cả `GetParametersByPath` lẫn `GetParameters`). Khi đó rơi sang `push-env.sh`: CloudShell đọc
  # được nên nó sinh .env rồi chuyển qua S3. Xem javadoc của push-env.sh để biết vì sao là S3 chứ
  # không phải nhúng nội dung vào lệnh SSM.
  if ! on_server "sinh .env từ Parameter Store" "nexa-env" 300 soft; then
    info "máy chủ không đọc được Parameter Store — chuyển sang đẩy .env từ CloudShell qua S3"
    INSTANCE="$INST" bash "$HERE/push-env.sh" | sed 's/^/    /'       || die "Không chuyển được .env sang máy chủ."
    ok "sinh .env từ Parameter Store (qua S3)"
  fi
fi

# ===========================================================================
HOSTS="@ to-chuc soat-ve quan-tri api tai-khoan media"
fqdn() { [ "$1" = "@" ] && echo "$DOMAIN" || echo "$1.$DOMAIN"; }

# Trả về MỌI địa chỉ A của một tên, mỗi dòng một địa chỉ — không phải chỉ một.
#
# Vì sao phải lấy hết: một tên có thể mang HAI bản ghi A trỏ hai IP khác nhau, và DNS trả về luân
# phiên. Lấy một địa chỉ thì phép kiểm bên dưới đúng hay sai tuỳ lần gọi — nó sẽ xanh một cách may
# mắn, rồi certbot hỏng sau đó vì Let's Encrypt gặp đúng lần trả về IP sai. Đã xảy ra thật: bản ghi
# `@` cũ trỏ IP mẫu nằm lại cạnh bản ghi mới, và năm lần gọi thì một lần ra IP cũ.
#
# Hỏi THẲNG 8.8.8.8 chứ không qua resolver của hệ thống: resolver cache cả câu trả lời phủ định, nên
# sau khi tạo bản ghi nó vẫn trả "không có" thêm vài phút và vòng chờ sẽ đứng dù DNS đã đúng.
#
# Ba công cụ vì không công cụ nào chắc chắn có: `dig` nằm trong bind-utils và KHÔNG phải bản
# CloudShell nào cũng cài sẵn — máy viết script này không có `dig`.
resolve() {
  local srv="${2:-8.8.8.8}"
  if command -v dig >/dev/null 2>&1; then
    dig +short "$1" "@$srv" 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'
  elif command -v nslookup >/dev/null 2>&1; then
    # CHỈ đọc phần sau dòng `Name:`. Dòng `Address:` đầu tiên là địa chỉ của chính máy chủ DNS, nên
    # đọc bừa sẽ trả về 8.8.8.8 cho mọi tên KHÔNG tồn tại — một kết quả trông như hợp lệ.
    # `Addresses:` (số nhiều) xuất hiện khi có nhiều bản ghi; lấy cả các dòng tiếp theo của nó.
    nslookup "$1" "$srv" 2>/dev/null | awk '
      /^Name:/ { ans = 1; next }
      ans && /^Address(es)?:/ { sub(/^Address(es)?:[ \t]*/, ""); print; inlist = 1; next }
      inlist && /^[ \t]+[0-9]/ { gsub(/[ \t]/, ""); print; next }
      { inlist = 0 }
    ' | tr -d ' '
  elif command -v host >/dev/null 2>&1; then
    host -t A "$1" "$srv" 2>/dev/null | awk '/has address/ {print $NF}'
  else
    getent ahostsv4 "$1" 2>/dev/null | awk '{print $1}' | sort -u
  fi
}

# Tên này đã trỏ ĐÚNG và CHỈ trỏ về $EIP chưa?
#
# Đòi mọi câu trả lời khớp, không phải "có $EIP trong số đó": một bản ghi cũ còn sót làm nửa số lượt
# truy cập đi sai máy chủ, và đó là lỗi gián đoạn — thứ khó lần ra nhất.
# Ba resolver công khai, độc lập nhau. Một tên coi là ĐÚNG chỉ khi cả ba đồng ý.
#
# Hỏi lại cùng một resolver nhiều lần là tín hiệu yếu: 8.8.8.8 có rất nhiều điểm phục vụ, và những
# điểm đã hỏi tên này lúc nó CHƯA tồn tại còn giữ câu trả lời phủ định — trường cuối của bản ghi SOA
# là TTL cho câu trả lời đó, với zone này là 3600 giây. Nên lặp lại chỉ đo được "lần này rơi vào điểm
# nào", không đo được việc lan truyền đã xong.
#
# Ba nhà cung cấp khác nhau cùng trả lời đúng là bằng chứng mạnh hơn hẳn — và Let's Encrypt cũng xác
# minh từ nhiều hướng, nên đây là phép thử gần với thứ sẽ thật sự xảy ra.
RESOLVERS="8.8.8.8 1.1.1.1 9.9.9.9"

points_only_to() {
  local name="$1" want="$2" got srv
  for srv in $RESOLVERS; do
    got=$(resolve "$name" "$srv")
    [ -n "$got" ] || return 1
    [ "$(echo "$got" | sort -u)" = "$want" ] || return 1
  done
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
    bad=""; stale=""
    for h in $HOSTS; do
      name=$(fqdn "$h")
      if points_only_to "$name" "$EIP"; then continue; fi
      bad="$bad $name"
      # Phân biệt hai tình huống cần hai hành động KHÁC NHAU: tên đang trả về IP lạ thì phải SỬA bản
      # ghi cũ; tên chưa có bản ghi thì phải THÊM. Gộp chung thành "còn thiếu" là để người dùng đi
      # thêm một bản ghi thứ hai cạnh bản ghi sai — đúng cái làm DNS trả về luân phiên.
      wrong=$(resolve "$name" | sort -u | grep -v "^$EIP\$" | tr '\n' ' ')
      [ -z "$wrong" ] || stale="$stale$name còn trỏ về $wrong|"
    done
    # ĐÒI BA VÒNG LIÊN TIẾP ĐÚNG, không phải một.
    #
    # Trong lúc lan truyền, 8.8.8.8 trả lời khác nhau giữa các lần gọi — nó có nhiều điểm phục vụ,
    # và những điểm đã hỏi tên này lúc nó CHƯA tồn tại còn giữ câu trả lời phủ định. Trường cuối của
    # bản ghi SOA là TTL cho câu trả lời phủ định đó; với zone này là 3600, tức tới một giờ.
    #
    # Đo thật trên tai-khoan.concertth.site: mười lần gọi thì năm lần ra IP, năm lần không có gì.
    # Một vòng kiểm đúng vào lúc may mắn sẽ cho script đi tiếp, rồi certbot gặp đúng lần trả lời
    # phủ định và thất bại — mà Let's Encrypt chỉ cho 5 lần thất bại mỗi giờ cho mỗi bộ tên. Đổi một
    # phút chờ thêm lấy việc không bị khoá một tiếng là đổi rất đáng.
    if [ -z "$bad" ]; then
      streak=$((${streak:-0} + 1))
      if [ "$streak" -ge 3 ]; then dns_ok=1; ok "cả bảy tên trỏ về $EIP, ổn định qua 3 lần kiểm"; break; fi
      printf '
    cả bảy tên đúng — xác nhận lại lần %d/3...                              ' "$streak"
      sleep 10
      continue
    fi
    streak=0

    # In cảnh báo bản ghi cũ MỘT LẦN. Lặp mỗi 10 giây thì nó trôi mất giữa dòng đếm thời gian.
    if [ -n "$stale" ] && [ -z "${stale_shown:-}" ]; then
      stale_shown=1
      printf '\n\n    BẢN GHI CŨ CÒN SÓT — phải SỬA giá trị, KHÔNG thêm bản ghi thứ hai:\n'
      echo "$stale" | tr '|' '\n' | sed '/^$/d; s/^/      /'
      printf '    Hai bản ghi A cùng tên làm DNS trả về luân phiên, và certbot hỏng ngẫu nhiên.\n\n'
    fi
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

  # IN RA 22 TÊN ẢNH ĐÃ PHÂN GIẢI, trước khi kéo bất cứ thứ gì.
  #
  # 22, đúng bằng số container: `config --images` bỏ qua service nằm sau profile (ollama,
  # otel-collector), nên danh sách này là chính xác những ảnh mà `up` sẽ cần — không nhiều hơn.
  #
  # `config --images` là chỗ DUY NHẤT thấy được kết quả của ba biến REGISTRY, BACKEND_TAG,
  # FRONTEND_TAG sau khi compose đã thay thế. Thiếu REGISTRY trong .env thì mặc định `nexaticket`
  # áp dụng và mọi dòng trỏ về Docker Hub — một lỗi mà `compose up` chỉ báo lại thành
  # "pull access denied", nghe như vấn đề quyền trên GHCR chứ không như sai registry.
  on_server "đọc 22 tên ảnh đã phân giải"     "cd /srv/nexaticket && docker compose -f deploy/compose/prod.yml --env-file deploy/compose/.env config --images | sort" 120

  # KÉO ẢNH THÀNH MỘT BƯỚC RIÊNG, không để `compose up` của systemd tự kéo.
  #
  # systemd cũng kéo ảnh thiếu, nhưng lỗi khi đó nằm trong journal của unit, còn SSM chỉ trả về
  # "Failed" — không tên ảnh, không lý do. Tách ra thì thông điệp thật hiện lên ngay ở đây: ảnh nào,
  # tag nào, và `denied` (package còn private) hay `manifest unknown` (tag không tồn tại) — hai
  # nguyên nhân cần hai cách sửa khác nhau.
  #
  # --ignore-buildable: rabbitmq không có trên GHCR, ảnh của nó dựng tại chỗ ở ExecStartPre của unit.
  if ! on_server "keo 21 anh (17 tu GHCR, 4 cong khai) - lan dau 5-10 phut"     "cd /srv/nexaticket && docker compose -f deploy/compose/prod.yml --env-file deploy/compose/.env pull --ignore-buildable" 1800 soft; then
    cat >&2 <<GHCRHINT

  Không kéo được ảnh. Đọc tên ảnh trong lỗi ở trên, rồi đối chiếu:

    denied / pull access denied   -> package còn PRIVATE. Phải mở CẢ 17, không chỉ 13 ảnh backend:
                                    bốn ảnh web-customer, web-admin, web-scanner, web-platform
                                    thuộc repo Concert-Ticket-Frontend và mặc định cũng private.
                                    github.com/uchihathien?tab=packages -> từng package ->
                                    Package settings -> Change visibility -> Public
                                    Hoặc nạp token một lần cho máy chủ:
                                      aws ssm put-parameter --name $PREFIX/GHCR_TOKEN \
                                        --type SecureString --value '<PAT co read:packages>' --overwrite
                                    rồi chạy lại với SKIP=1,2,3 để server-setup.sh đăng nhập lại.

    manifest unknown              -> tag không tồn tại. CI đẩy :main và :<sha>, nên .env phải là
                                    BACKEND_TAG=main và FRONTEND_TAG=main, KHÔNG phải latest.
                                    Riêng FRONTEND_TAG=main đòi workflow Release của repo
                                    Concert-Ticket-Frontend đã chạy xanh ít nhất một lần.

GHCRHINT
    die "Kéo ảnh thất bại — không bật container khi còn thiếu ảnh."
  fi

  # `--wait` trả về khi container HEALTHY. Lần đầu 5–10 phút: Postgres chạy migration của 12 service,
  # Keycloak nhập realm.
  # `restart`, KHÔNG `start`: unit là Type=oneshot + RemainAfterExit=yes, nên khi nó đang ở trạng
  # thái active (exited) từ một lần trước, `start` là lệnh KHÔNG LÀM GÌ và trả về 0 — script khi đó
  # báo xanh cho một việc chưa xảy ra.
  on_server "bật 22 container (lần đầu 5–10 phút)"     "sudo systemctl enable nexaticket && sudo systemctl restart nexaticket" 1800

  # KIỂM TRẠNG THÁI THẬT, không tin mã trả về của systemctl.
  #
  # Đã xảy ra: bước trên báo ✓ trong khi journal cho thấy unit hỏng và không container nào được tạo.
  # Một mã trả về 0 chỉ nói "lệnh chạy xong", không nói "hệ thống đang chạy".
  # KHÔNG dùng dấu nháy kép trong lệnh: `on_server` nhúng nó vào JSON của SSM
  # (`commands=["sudo -u ubuntu bash -lc '...'"]`), nên một dấu " sẽ đóng chuỗi JSON sớm và AWS CLI
  # từ chối cả lời gọi. Số nguyên không cần bọc nháy, nên ở đây không mất gì.
  on_server "đếm container đang chạy"     "cd /srv/nexaticket && n=\$(docker compose -f deploy/compose/prod.yml --env-file deploy/compose/.env ps -q | wc -l) && echo \$n container dang chay && test \$n -ge 20" 300
  on_server "kiểm khói" "cd /srv/nexaticket && bash deploy/scripts/smoke.sh" 300

  # SAU smoke test, vì nó cần Keycloak đã healthy và SMTP đã đúng — và vì một hệ thống xanh mà không
  # ai đăng nhập được thì chưa phải là đã deploy xong.
  #
  # Realm production KHÔNG có tài khoản nào và không có đường tự tạo: realm dev cài sẵn
  # superadmin/organizer/... với mật khẩu trùng tên, bỏ đi là đúng, nhưng SUPER_ADMIN_EMAILS chỉ CẤP
  # VAI TRÒ lúc email đó đăng nhập lần đầu — nó không tạo tài khoản Keycloak. Script tạo user, Keycloak
  # gửi email đặt mật khẩu; mật khẩu không đi qua bất kỳ đâu ngoài trình duyệt của người nhận.
  # Idempotent: lần chạy lại chỉ báo "đã có".
  on_server "tài khoản SUPER_ADMIN trên Keycloak" "cd /srv/nexaticket && bash deploy/scripts/bootstrap-keycloak.sh" 300
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
