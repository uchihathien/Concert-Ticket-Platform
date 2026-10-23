#!/usr/bin/env bash
# Cài deploy kiểu kéo lên máy chủ. Chạy MỘT lần, dưới `ubuntu` (có sudo không mật khẩu):
#
#   cd /srv/nexaticket && git pull --ff-only && bash deploy/scripts/install-pull-deploy.sh
#
# Qua SSM (chạy dưới root, và bằng dash chứ không phải bash):
#
#   sudo -u ubuntu bash -lc 'cd /srv/nexaticket && git pull --ff-only && bash deploy/scripts/install-pull-deploy.sh'
#
# Không đụng gì tới container đang chạy. Lần timer chạy đầu tiên mới deploy, và chỉ khi tag
# `production` đã tồn tại trên GitHub.
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ "$(id -un)" != ubuntu ]; then
  echo "Chạy dưới ubuntu: sudo -u ubuntu bash $0" >&2
  exit 1
fi

# nexaticket.service đổi cùng lúc: nó đọc thêm release.env để reboot bật đúng bản đã duyệt.
sudo install -m 644 deploy/systemd/nexaticket.service        /etc/systemd/system/
sudo install -m 644 deploy/systemd/nexaticket-deploy.service /etc/systemd/system/
sudo install -m 644 deploy/systemd/nexaticket-deploy.timer   /etc/systemd/system/

# Tạo sẵn release.env rỗng: nexaticket.service truyền nó cho compose bằng --env-file, và compose
# chết nếu file không tồn tại. Rỗng = tag lấy từ .env như trước, cho tới lần deploy kéo đầu tiên.
[ -e deploy/compose/release.env ] || install -m 644 /dev/null deploy/compose/release.env

sudo systemctl daemon-reload
sudo systemctl enable --now nexaticket-deploy.timer

echo
systemctl list-timers nexaticket-deploy.timer --no-pager
echo
echo "Xem lần chạy: journalctl -u nexaticket-deploy -f"
echo "Chạy ngay:    sudo systemctl start nexaticket-deploy"
