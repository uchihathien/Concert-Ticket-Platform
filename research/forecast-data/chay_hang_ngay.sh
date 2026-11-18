#!/usr/bin/env bash
# Chụp trạng thái vé mỗi ngày rồi xuất lại Excel. launchd gọi script này (xem README, mục Lịch chạy).
# Chạy tay cũng được: ./chay_hang_ngay.sh
cd "$(dirname "$0")" || exit 1
echo "=== $(date '+%Y-%m-%d %H:%M:%S') ==="
.venv/bin/python snapshot_status.py && .venv/bin/python export_excel.py
