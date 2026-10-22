# Dữ liệu demo từ Ticketbox

Ba script lấy khoảng 30–50 sự kiện **công khai** của [Ticketbox](https://ticketbox.vn) (tên, thể loại,
địa điểm, giá, ảnh bìa) rồi nhập vào NexaTicket, để trang web trông như đang vận hành khi demo khoá luận.

```
scrape_ticketbox.py  →  data/events.json + data/images/               (cào)
normalize.py         →  data/normalized.json + normalization-report.md (chuẩn hoá)
import_events.py     →  NexaTicket qua API, ghi data/imported.json       (nhập)
```

## Nguồn và giới hạn sử dụng

- Dữ liệu thuộc về **Ticketbox và các ban tổ chức sự kiện**. Điều khoản sử dụng của Ticketbox (bản
  2023) không cấm thu thập tự động, nhưng ghi rõ hình ảnh và nội dung sự kiện thuộc quyền sở hữu
  trí tuệ của ban tổ chức. Vì vậy dữ liệu này **chỉ dùng nội bộ để demo khoá luận**: không đưa lên
  môi trường công khai, không dùng thương mại, không phân phối lại.
- Nguồn của từng sự kiện (link Ticketbox) ghi trong `data/imported.json` và `data/normalized.json`,
  không in vào mô tả cho khách. Khi trình bày khoá luận, ghi rõ dữ liệu demo lấy từ Ticketbox.
- Thư mục `data/` nằm trong `.gitignore`: ảnh và dữ liệu cào **không** được đưa lên GitHub.
- Không lấy dữ liệu cá nhân: không bình luận, không tên / email / SĐT người dùng.
- Dọn dữ liệu sau khi demo xong: xem mục [Dọn dữ liệu](#dọn-dữ-liệu).

### Script tuân thủ những gì

- Đọc lại `robots.txt` của ticketbox.vn mỗi lần chạy (bằng chính User-Agent của script). Không mở
  `/payment/`, `/my-tickets/`, `/my-ticket/`, `/profile/`, `/sign-in/`, `/sign-up/`, `/verify-email/`,
  `/reset-password/`. Không đăng nhập.
- Chờ ngẫu nhiên 2–4 giây giữa hai lần gọi. User-Agent ghi rõ mục đích học tập.
- Đọc JSON mà chính trang Ticketbox đang gọi (danh mục trang chủ, `api-v2.ticketbox.vn/gin/api/v2/events/{id}`)
  thay vì bóc HTML — ổn định hơn khi Ticketbox đổi giao diện.

## Cài đặt

Python 3.9+. Từ thư mục này:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m playwright install chromium
```

Bước 2 (đọc địa điểm đang có) và bước 3 cần NexaTicket đang chạy (gateway `:8080`, Keycloak `:8081`).

| Biến môi trường | Mặc định (máy dev) |
|---|---|
| `GATEWAY_URL` | `http://localhost:8080` |
| `KEYCLOAK_URL` / `KEYCLOAK_REALM` | `http://localhost:8081` / `nexaticket` |
| `NEXA_CLIENT_ID` / `NEXA_CLIENT_SECRET` | `web-admin` / `dev-secret-admin` |
| `NEXA_USERNAME` / `NEXA_PASSWORD` | `organizer` / `organizer` |
| `ORG_ID` | `00000000-0000-4000-a000-000000000001` (tổ chức demo) |

## Chạy

```bash
# 1. Cào (mặc định 40 sự kiện mới; chạy lại thì bỏ qua sự kiện đã có)
.venv/bin/python scrape_ticketbox.py --limit 40

# 2. Chuẩn hoá → data/normalized.json + data/normalization-report.md
.venv/bin/python normalize.py            # --offline: không hỏi API địa điểm đang có

# 3. Nhập — xem trước, rồi nhập thật
.venv/bin/python import_events.py --dry-run
.venv/bin/python import_events.py

# Kiểm thử các hàm chuẩn hoá
.venv/bin/python -m pytest tests
```

- Lỗi từng sự kiện khi cào ghi vào `data/scrape-errors.log`; script đi tiếp.
- Bước 3 nhận sự kiện đã nhập theo `sourceUrl` (`data/imported.json`), nên chạy lại không tạo trùng.
- Nếu đã lâu sau bước 2 (suất đã qua giờ đóng bán), bước 3 bỏ qua sự kiện đó và nhắc chạy lại
  `normalize.py` — bước 3 không tự sửa dữ liệu.

### Chuẩn hoá làm gì

Ràng buộc đọc từ code (`AdminCatalogController`, `OrganizationMediaController`/`PosterUploadUseCase`,
`packages/ui/src/categories.ts`, `Event.preflight`):

- Tiêu đề ≤ 200 (bỏ emoji, bỏ tiền tố `[HCM]`, `[HN]`…), `summary` = 1–3 câu đầu ≤ 500, cắt theo từ.
- Thể loại về `nhac-song` · `san-khau` · `the-thao` · `hoi-thao` · `khac` (theo mã Ticketbox, không
  có thì đoán theo tên; các trường hợp đoán liệt kê trong báo cáo).
- Thành phố theo đúng cách viết đang có trong hệ thống (`TP. Hồ Chí Minh`, `Hà Nội`…), địa điểm gộp
  theo tên bỏ dấu + thành phố, trùng với địa điểm đã có thì dùng lại.
- Ngày đã qua dời sang tương lai theo từng tuần (giữ thứ và giờ); tối đa 5 suất; mở bán từ lúc
  chuẩn hoá, đóng bán 1 giờ trước giờ diễn.
- Mỗi hạng vé là một khu ĐỨNG (tối đa 6), `zoneCode` sinh từ tên, sức chứa 500 (rẻ nhất) → 50 (đắt nhất).
- Ảnh bìa đổi sang JPEG, rộng tối đa 1200px, dưới 8 MB.
- Loại sự kiện thiếu tên / thành phố (sự kiện online) / suất / hạng vé có giá, và sự kiện trùng.

## Dọn dữ liệu

```bash
.venv/bin/python import_events.py --clean --dry-run   # xem trước
.venv/bin/python import_events.py --clean
```

API của catalog chỉ **xoá** được bản nháp chưa từng lên bán. Sự kiện đã publish được **rút xuống rồi
huỷ** (trạng thái cuối, không còn hiện cho khách) — đó là đường duy nhất API cho phép. Địa điểm và khu
không có API xoá nên được giữ lại (lần nhập sau dùng lại).

Muốn xoá hẳn khỏi database trên máy dev (sau `--clean`), làm tay — id sự kiện lấy từ
`data/imported.json` (trước khi dọn) — và nhớ đây là dữ liệu dev:

```bash
# In danh sách id sự kiện đã nhập
python3 -c "import json;print('\\n'.join(r['eventId'] for r in json.load(open('data/imported.json')).values()))"
```

Cuối cùng xoá thư mục `data/` để không còn ảnh và dữ liệu cào trên máy.
