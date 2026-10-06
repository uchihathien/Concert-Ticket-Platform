#!/usr/bin/env bash
# Cài máy chủ Ubuntu 24.04 để chạy NexaTicket. Chạy MỘT LẦN, qua SSH, trên chính máy chủ.
#
#   ssh -i nexaticket.pem ubuntu@<EIP>
#   curl -fsSL https://raw.githubusercontent.com/uchihathien/Concert-Ticket-Platform/main/deploy/scripts/server-setup.sh | bash
#
# KHÔNG tự chạy `compose up`: bước đó cần .env, và .env cần bí mật đã nạp vào Parameter Store.
# Script in ra các bước còn lại ở cuối.
set -euo pipefail

say() { printf '\n=== %s\n' "$*"; }

# `DPkg::Lock::Timeout` chặn lỗi hay gặp nhất khi cài một máy vừa boot.
#
# Instance mới đang chạy `unattended-upgrades` và `apt-daily`, cả hai giữ lock của dpkg. Gọi
# apt-get ngay thì nhận:
#
#     E: Could not get lock /var/lib/dpkg/lock-frontend (11: Resource temporarily unavailable)
#
# Lỗi đó không liên quan gì tới mã nguồn, và chỉ xảy ra ở lần chạy đầu trên máy mới — nên chạy lại
# sau vài phút là qua, và người ta dễ kết luận sai rằng script "chạy không ổn định". Chờ tối đa 10
# phút thay vì thất bại.
APT="sudo DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=600"

say "Cập nhật hệ thống"
$APT update -qq
$APT upgrade -y -qq

say "Docker (repo chính thức)"
# Bản `docker.io` trong Ubuntu thường cũ và KHÔNG có compose v2 — mà prod.yml dùng cú pháp v2
# (`mem_limit`, `--wait`, anchor YAML). Dùng repo của Docker để tránh một lớp lỗi khó đoán.
sudo install -m 0755 -d /etc/apt/keyrings
# `--batch --yes --no-tty` là BẮT BUỘC khi chạy qua SSM, không phải để cho gọn.
#
# Lệnh này chạy không có terminal. File đích đã tồn tại từ một lần chạy trước thì gpg hỏi
# "File exists. Overwrite?" và đi tìm /dev/tty để đọc câu trả lời:
#
#     gpg: cannot open '/dev/tty': No such device or address
#
# Thông báo đó không nói gì về nguyên nhân thật (một câu hỏi không ai trả lời được), và nó chỉ xuất
# hiện ở lần chạy THỨ HAI — nên lần đầu xanh, lần chạy lại đỏ.
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --batch --yes --no-tty --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
$APT update -qq
$APT install -y -qq \
  docker-ce docker-ce-cli containerd.io docker-compose-plugin \
  nginx git unzip jq

sudo usermod -aG docker ubuntu
docker --version && docker compose version

say "AWS CLI v2"
# Bản trong apt là v1 và không đọc được một số cú pháp dùng ở đây.
if ! command -v aws >/dev/null; then
  curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscli.zip
  # `-o` ghi đè không hỏi — CÙNG LỚP LỖI với gpg ở trên: `/tmp/aws` còn sót từ một lần chạy dở
  # thì unzip hỏi "replace?" và đi tìm TTY không tồn tại. Lỗi chỉ xuất hiện ở lần chạy thứ hai.
  unzip -q -o /tmp/awscli.zip -d /tmp && sudo /tmp/aws/install --update
fi
aws --version

say "Swap 4 GB"
# 22 container trên 16 GB là chật. Swap KHÔNG giúp container vượt mem_limit (nó vẫn bị OOMKill),
# nhưng cứu host khỏi chết khi có đợt tăng đột biến.
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 4G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi
# JVM đang tải nặng mà bị swap thì chậm thảm. Giữ swappiness thấp: chỉ dùng swap khi thật cần.
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-nexaticket.conf >/dev/null
sudo sysctl -q --system
free -h | head -3

say "Siết SSH"
sudo sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo sed -i 's/^#*PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sudo systemctl restart ssh
echo "Chỉ còn đăng nhập bằng khoá; root không đăng nhập được."

say "Mã nguồn"
# CLONE nếu chưa có, CẬP NHẬT nếu đã có.
#
# Bản trước chỉ clone, nên repo trên máy chủ đứng mãi ở commit của lần chạy đầu. Mọi bản sửa push
# sau đó không bao giờ tới máy chủ — và script vẫn chạy, vẫn báo xanh ở bước này, rồi hỏng ở bước sau
# bằng đúng lỗi đã được sửa từ lâu. Đã xảy ra thật: `nexa-env` tiếp tục lỗi
# `ssm:GetParametersByPath` dù đường dự phòng đã có trong repo mười phút trước đó.
#
# `reset --hard origin/main` chứ không `git pull`: repo ở đây có thể đang ở trạng thái detached HEAD
# vì release.yml checkout theo SHA. `git pull` khi đó không làm gì có ích, và nó thất bại một cách
# khó hiểu. Không mất gì: file không theo dõi (`deploy/compose/.env`) vẫn nguyên.
sudo install -d -o ubuntu -g ubuntu /srv/nexaticket
if [ ! -d /srv/nexaticket/.git ]; then
  git clone --quiet https://github.com/uchihathien/Concert-Ticket-Platform.git /srv/nexaticket
else
  git -C /srv/nexaticket fetch --quiet origin main
  git -C /srv/nexaticket reset --hard --quiet origin/main
fi
cd /srv/nexaticket && echo "  commit: $(git log --oneline -1)"

# --- Đăng nhập registry ------------------------------------------------------
# Mười ba ảnh backend trên ghcr.io là PRIVATE. GitHub để package ở chế độ private theo mặc định, kể
# cả khi repo là public — một mặc định hay gây bất ngờ, vì mọi thứ khác của repo đều mở.
#
# Không đăng nhập thì `compose pull` thất bại với "pull access denied ... repository does not exist",
# một thông điệp gợi ý sai: repository CÓ tồn tại, chỉ là không cho xem.
#
# Token lấy từ Parameter Store nếu có. Không có thì bỏ qua — các ảnh public vẫn kéo được, và thông
# điệp bên dưới nói rõ phải làm gì.
say "Đăng nhập ghcr.io"
GHCR_TOKEN=$(aws ssm get-parameter --name /nexaticket/GHCR_TOKEN --with-decryption                --query Parameter.Value --output text 2>/dev/null || true)
if [ -n "${GHCR_TOKEN:-}" ] && [ "$GHCR_TOKEN" != None ]; then
  GHCR_USER=$(aws ssm get-parameter --name /nexaticket/GHCR_USER                 --query Parameter.Value --output text 2>/dev/null || echo uchihathien)
  echo "$GHCR_TOKEN" | sudo docker login ghcr.io -u "$GHCR_USER" --password-stdin
  # Chép cho cả `ubuntu`: compose chạy dưới user đó ở các lệnh thủ công.
  sudo install -d -o ubuntu -g ubuntu /home/ubuntu/.docker
  sudo cp /root/.docker/config.json /home/ubuntu/.docker/config.json
  sudo chown ubuntu:ubuntu /home/ubuntu/.docker/config.json
  echo "  đã đăng nhập bằng $GHCR_USER"
else
  echo "  BỎ QUA: chưa có /nexaticket/GHCR_TOKEN."
  echo "  Mười ba ảnh backend là private, nên compose pull sẽ thất bại. Chọn một:"
  echo "    a) Đặt 13 package thành Public: github.com/uchihathien?tab=packages"
  echo "    b) Nạp token:  aws ssm put-parameter --name /nexaticket/GHCR_TOKEN \\"
  echo "         --type SecureString --value '<PAT co scope read:packages>' --overwrite"
fi

say "Nginx, systemd, script sinh .env"
sudo install -m 755 deploy/scripts/pull-env.sh /usr/local/bin/nexa-env
sudo cp deploy/systemd/nexaticket.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo cp deploy/nginx/nexaticket.conf /etc/nginx/sites-available/nexaticket
sudo ln -sf /etc/nginx/sites-available/nexaticket /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

say "Certbot"
# snapd cũng chưa sẵn sàng ngay sau khi boot: `snap install` khi đó trả
# "error: cannot communicate with server". `snap wait` chờ đúng việc đó.
sudo snap wait system seed.loaded 2>/dev/null || true
# Kiểm trước khi cài: `snap install` trên gói đã có trả về lỗi, và `set -e` sẽ dừng cả script ở
# lần chạy thứ hai.
snap list certbot >/dev/null 2>&1 || sudo snap install --classic certbot >/dev/null
sudo ln -sf /snap/bin/certbot /usr/bin/certbot

cat <<'NEXT'

================================================================
  MÁY CHỦ ĐÃ SẴN SÀNG. Bốn bước còn lại, THEO ĐÚNG THỨ TỰ:

  1. Đăng xuất rồi vào lại (để quyền nhóm `docker` có hiệu lực):
       exit && ssh -i nexaticket.pem ubuntu@<EIP>

  2. Sinh .env từ Parameter Store (đòi đủ 49 biến, báo lỗi nếu thiếu):
       nexa-env

  3. KIỂM DNS TRƯỚC KHI XIN CHỨNG CHỈ. Let's Encrypt giới hạn 5 lần thất bại
     mỗi giờ cho mỗi bộ tên — thử sai vài lần là bị khoá một tiếng:
       for h in concertth.site to-chuc soat-ve quan-tri api tai-khoan media; do
         n=$h; [ "$h" = concertth.site ] || n="$h.concertth.site"
         echo "$n -> $(dig +short "$n" | tail -1)"
       done
     Cả bảy PHẢI ra đúng Elastic IP.

  4. Chứng chỉ cho cả bảy tên trong MỘT lần, rồi bật hệ:
       sudo certbot --nginx --redirect --agree-tos --no-eff-email \
         -m <email-cua-ban> \
         -d concertth.site      -d to-chuc.concertth.site \
         -d soat-ve.concertth.site -d quan-tri.concertth.site \
         -d api.concertth.site  -d tai-khoan.concertth.site \
         -d media.concertth.site
       sudo systemctl enable --now nexaticket
       docker compose -f deploy/compose/prod.yml --env-file deploy/compose/.env ps
       ./deploy/scripts/smoke.sh
================================================================
NEXT
