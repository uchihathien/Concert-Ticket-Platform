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

say "Cập nhật hệ thống"
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -qq

say "Docker (repo chính thức)"
# Bản `docker.io` trong Ubuntu thường cũ và KHÔNG có compose v2 — mà prod.yml dùng cú pháp v2
# (`mem_limit`, `--wait`, anchor YAML). Dùng repo của Docker để tránh một lớp lỗi khó đoán.
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  docker-ce docker-ce-cli containerd.io docker-compose-plugin \
  nginx git unzip jq

sudo usermod -aG docker ubuntu
docker --version && docker compose version

say "AWS CLI v2"
# Bản trong apt là v1 và không đọc được một số cú pháp dùng ở đây.
if ! command -v aws >/dev/null; then
  curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscli.zip
  unzip -q /tmp/awscli.zip -d /tmp && sudo /tmp/aws/install
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
sudo install -d -o ubuntu -g ubuntu /srv/nexaticket
if [ ! -d /srv/nexaticket/.git ]; then
  git clone --quiet https://github.com/uchihathien/Concert-Ticket-Platform.git /srv/nexaticket
fi
cd /srv/nexaticket && git log --oneline -1

say "Nginx, systemd, script sinh .env"
sudo install -m 755 deploy/scripts/pull-env.sh /usr/local/bin/nexa-env
sudo cp deploy/systemd/nexaticket.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo cp deploy/nginx/nexaticket.conf /etc/nginx/sites-available/nexaticket
sudo ln -sf /etc/nginx/sites-available/nexaticket /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

say "Certbot"
sudo snap install --classic certbot >/dev/null
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
