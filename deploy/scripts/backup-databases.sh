#!/usr/bin/env bash
# Sao lưu cả mười một database trong cụm Postgres của NexaTicket.
#
#   ./backup-databases.sh                      # vào deploy/compose/backups
#   BACKUP_DIR=/mnt/nas/nexaticket ./backup-databases.sh
#
# Chạy TỪ MÁY CHỦ, gọi vào container qua `docker compose exec`. Không chạy trong container: đích
# đến của bản sao lưu phải nằm ngoài vòng đời của container, và lý tưởng là ngoài cả máy chủ.
#
# Đặt vào cron hoặc systemd timer:
#   17 3 * * *  /opt/nexaticket/deploy/scripts/backup-databases.sh >> /var/log/nexaticket-backup.log 2>&1
#
# ------------------------------------------------------------------------------------------------
# MỘT BẢN SAO LƯU CHƯA TỪNG ĐƯỢC PHỤC HỒI KHÔNG PHẢI LÀ BẢN SAO LƯU — NÓ LÀ MỘT FILE.
# Xem `restore-database.sh` và mục §7 của plan/production-checklist.md.
# ------------------------------------------------------------------------------------------------
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-$(dirname "$0")/../compose/prod.yml}"
ENV_FILE="${ENV_FILE:-$(dirname "$0")/../compose/.env}"
BACKUP_DIR="${BACKUP_DIR:-$(dirname "$0")/../compose/backups}"

# Số ngày giữ lại. Sổ cái giữ LÂU HƠN phần còn lại: đó là sổ tiền, và nghĩa vụ đối soát kéo dài hơn
# nhiều so với nhu cầu vận hành (custodial-funds.md).
KEEP_DAYS="${KEEP_DAYS:-14}"
KEEP_DAYS_LEDGER="${KEEP_DAYS_LEDGER:-370}"

DATABASES=(identity catalog inventory ordering payment ledger payout ticketing notification analytics keycloak)

stamp=$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP_DIR"

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

echo "[$(date -Iseconds)] Bat dau sao luu -> $BACKUP_DIR"
failed=0

for db in "${DATABASES[@]}"; do
  out="$BACKUP_DIR/${db}_db-$stamp.dump"

  # `--format=custom`: nén sẵn, và cho phép phục hồi CHỌN LỌC từng bảng bằng pg_restore. Bản
  # `.sql` thuần chỉ phục hồi được tất-cả-hoặc-không-gì, và lúc cần dùng tới bản sao lưu thì gần
  # như luôn là lúc chỉ muốn lấy lại một bảng.
  #
  # Ghi ra stdout rồi chuyển hướng ở phía máy chủ: `-f` bên trong container sẽ ghi vào filesystem
  # của container, tức là vào đúng thứ mà bản sao lưu sinh ra để sống sót qua.
  if compose exec -T postgres pg_dump \
        --username=postgres --format=custom --no-password "${db}_db" > "$out" 2>/dev/null; then
    size=$(du -h "$out" | cut -f1)
    echo "  OK   ${db}_db  ($size)"
  else
    echo "  LOI  ${db}_db  -- xem log ben tren" >&2
    # Xoá file rỗng/dở: giữ lại một bản sao lưu hỏng còn tệ hơn không có bản nào, vì nó làm người
    # ta tưởng là có.
    rm -f "$out"
    failed=1
  fi
done

# Dọn bản cũ. CHỈ dọn khi mọi database đều sao lưu xong: xoá bản cũ ngay sau một lần chạy hỏng là
# cách đánh mất cả bản cũ lẫn bản mới trong cùng một đêm.
if [ "$failed" -eq 0 ]; then
  find "$BACKUP_DIR" -name '*_db-*.dump' ! -name 'ledger_db-*' -mtime "+$KEEP_DAYS" -delete
  find "$BACKUP_DIR" -name 'ledger_db-*.dump' -mtime "+$KEEP_DAYS_LEDGER" -delete
  echo "[$(date -Iseconds)] Xong. Da don ban cu hon $KEEP_DAYS ngay (so cai: $KEEP_DAYS_LEDGER)."
else
  echo "[$(date -Iseconds)] CO LOI — KHONG don ban cu." >&2
  exit 1
fi
