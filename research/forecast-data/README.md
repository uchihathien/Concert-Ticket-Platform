# Bộ dữ liệu nghiên cứu: sức mua vé trên Ticketbox

Dữ liệu **công khai** từ [ticketbox.vn](https://ticketbox.vn) cho bài nghiên cứu *"Forecasting Event
Ticket Sales with LightGBM Quantile Regression to Estimate Break-even Probability"*.

Đây là dữ liệu **nghiên cứu**, khác bộ dữ liệu demo ở `scripts/ticketbox/`:
- giữ nguyên ngày gốc, không dời ngày;
- không nhập vào NexaTicket;
- theo dõi trạng thái vé **mỗi ngày** để biết hạng vé nào hết, và hết lúc nào.

```
collect_events.py   chạy 1 lần (chạy lại để mở rộng/cập nhật)  → su_kien.csv, hang_ve.csv, suc_chua_dia_diem.csv
snapshot_status.py  chạy MỖI NGÀY                               → ghi thêm vào trang_thai_ngay.csv
export_excel.py     khi cần                                      → bo-du-lieu-nghien-cuu.xlsx
chuan_hoa_du_lieu.py  khi cần, không gọi mạng                   → gộp suất trùng, tính nhom_phan_tich, xếp địa điểm
```

## Nguồn dữ liệu và giới hạn sử dụng

- Dữ liệu thuộc về **Ticketbox và các ban tổ chức sự kiện**.
- Bộ dữ liệu **chỉ dùng cho nghiên cứu khoá luận**. Không dùng thương mại, không phân phối lại, không
  đưa lên kho công khai.
- Thư mục `data/` nằm trong `.gitignore` và không được đưa lên GitHub.
- Khi trích dẫn, ghi rõ: *"Dữ liệu thu thập từ ticketbox.vn, ngày … – …"*. Khoảng ngày lấy từ cột
  `ngay_cao`.

### Script tuân thủ những gì

- **robots.txt.** Script đọc lại robots.txt mỗi lần chạy, bằng chính User-Agent của nó. Không đọc được
  thì coi như bị cấm hết và dừng.
- **Đường bị cấm.** Script không bao giờ mở `/payment/`, `/my-tickets/`, `/my-ticket/`, `/profile/`,
  `/sign-in/`, `/sign-up/`, `/verify-email/`, `/reset-password/`. Danh sách này được chặn thêm một lần
  trong code, phòng khi robots.txt đổi.
- **Không đăng nhập**, không gửi cookie tài khoản.
- **Cào chậm.** Script chờ ngẫu nhiên 2–4 giây trước **mỗi** lần gọi.
- **User-Agent** là `NexaTicket-Research/1.0 (student thesis, IUH)`.
- **Không lấy dữ liệu cá nhân.** Không có bình luận, tên người dùng, email hay số điện thoại. Script
  cũng bỏ qua mô tả sự kiện và thông tin pháp lý của ban tổ chức, vì phần đó có thể chứa tên người
  đại diện.

## Ticketbox thể hiện trạng thái vé thế nào

Phần này ghi lại kết quả xem trực tiếp trang sự kiện và JSON của trang, ngày 03/11/2026.

Ticketbox là ứng dụng Next.js. Trang sự kiện tải dữ liệu từ API JSON
`api-v2.ticketbox.vn/gin/api/v2/events/{id}`. Script đọc chính JSON này, không bóc HTML.
`__NEXT_DATA__` chỉ có tiêu đề và thông tin SEO, không có hạng vé.

Mỗi sự kiện có `showings[]` (suất diễn). Mỗi suất có `ticketTypes[]` (hạng vé). Mỗi hạng vé có
`price`, `isFree`, `startTime` và `endTime` (khung giờ bán), cùng `status`:

| `status` trong JSON | `statusName` | Chữ trên trang | Mã trong bộ dữ liệu |
|---|---|---|---|
| `book_now` | Book now | nút chọn vé bình thường | `CON_VE` |
| `sold_out` | Sold out | **Hết vé** | `HET_VE` |
| `sale_closed` | Online booking closed | **Vé ngừng bán online** | `NGUNG_BAN` |
| `registration_closed` | Registration closed | sự kiện miễn phí đã đóng đăng ký | `NGUNG_BAN` |
| `not_open_for_sale_yet` | Ticket available soon | vé chưa mở bán | `CHUA_MO_BAN` |

- **Mỗi hạng vé có trạng thái riêng.** Ví dụ, một suất có thể có hạng "Hạng Sky" đã `sold_out` trong
  khi hạng khác vẫn `book_now`.
- **Sự kiện đã diễn vẫn mở được.** Các hạng vé giữ trạng thái cuối, thường là `sold_out` hoặc
  `sale_closed`. Suất có `status = showing_is_over`.
- **Không có ngày mở bán riêng.** Trang sự kiện không hiện ngày mở bán ở đâu cả. Mốc duy nhất là
  `startTime` của từng hạng vé trong JSON, và đó là nguồn của `ngay_mo_ban`. Với sự kiện chia nhiều đợt
  mở bán, mỗi đợt là một hạng vé có `startTime` riêng, nên `ngay_mo_ban` là đợt sớm nhất.
- **Có "suất" là khung thời gian, không phải buổi diễn.** Khi `ngay_mo_ban` = `ngay_dien` hoặc
  `gio_dien` = 00:00, "suất" thường là một khung bán dài: đợt bán hàng lưu niệm, vé tham quan nhiều
  tuần, giải đấu một suất kéo dài 8 ngày, hoặc workshop theo ngày với giờ thật ghi trong tên hạng vé.
  Ngày giờ trong CSV vẫn đúng với Ticketbox, nhưng không phải giờ biểu diễn.
- **Không có số vé còn lại.** Cả JSON lẫn giao diện đều **không** công bố số vé còn lại hay tổng số vé.
  Vì vậy `so_ve_con` và `so_luong` luôn để trống. Hàm nhận diện vẫn đọc được chữ "Còn X vé", phòng khi
  Ticketbox hiện thêm.
- **Không có "Sắp hết".** Không trang hay JSON nào hiện "Sắp hết" cho từng hạng vé. Mã `SAP_HET` được
  giữ cho trường hợp đó.
- **`CHUA_MO_BAN` là mã thêm.** Mã này không có trong danh sách trạng thái ban đầu. Ticketbox có trạng
  thái riêng cho vé chưa mở bán, nên tách ra thay vì gộp vào `KHONG_RO`.
- **Kết quả tìm kiếm** có trường `badge`. Với sự kiện đã diễn, `badge` là "Đã diễn ra". Trong khoảng
  200 kết quả đã xem, không badge nào là "Hết vé" hay "Sắp hết".

## Cài đặt

Cần Python 3.9 trở lên. Chạy từ thư mục này:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m playwright install chromium
```

Trên Windows, thay `.venv/bin/` bằng `.venv\Scripts\`.

## Chạy

```bash
# 1. Thu thập sự kiện + hạng vé (mặc định tối đa 200 sự kiện, xen kẽ sắp diễn / đã diễn)
.venv/bin/python collect_events.py --limit 200
.venv/bin/python collect_events.py --limit 300 --chi-moi      # chỉ thêm sự kiện chưa có
.venv/bin/python collect_events.py --loai upcoming --limit 50 # chỉ sự kiện sắp diễn

# 2. Chụp trạng thái hôm nay (đặt lịch chạy mỗi ngày, xem bên dưới)
.venv/bin/python snapshot_status.py

# 3. Xuất Excel
.venv/bin/python export_excel.py

# Chuẩn hoá dữ liệu đã cào (không gọi mạng; sao lưu bản cũ vào data/sao-luu-<thời điểm>/)
.venv/bin/python chuan_hoa_du_lieu.py

# Kiểm thử các hàm đọc giá, ngày giờ, trạng thái
.venv/bin/python -m pytest tests
```

- **Chạy lại được.** Suất đã có, xác định theo `url` + `ngay_dien` + `gio_dien`, được **cập nhật**
  chứ không tạo dòng mới. Cột người đã điền tay, như sức chứa, được giữ.
- **Suất trùng giờ được gộp.** Ticketbox đôi khi khai hai suất cùng giờ bắt đầu, nhất là workshop và vé
  tham quan. Bộ dữ liệu chỉ giữ một dòng cho mỗi bộ `url` + `ngay_dien` + `gio_dien`. Hạng vé được hợp
  theo tên: còn bán ở một suất là còn bán, và giờ mở bán lấy mốc sớm nhất. Dữ liệu cào trước khi có quy
  tắc này được gộp bằng `chuan_hoa_du_lieu.py`, giữ mã nhỏ nhất. Vì vậy mã suất có thể nhảy cóc, ví dụ
  TB034-S1, TB034-S3.
- **Lỗi từng sự kiện** ghi vào `data/cao-du-lieu.log`, script đi tiếp. Log cũng ghi sự kiện đổi lịch,
  hạng vé mới xuất hiện hoặc biến mất, và hạng vé chuyển sang hết vé.
- **Một số sự kiện rất cũ bị 404.** Đó là những sự kiện có id nhỏ, ví dụ 161 hay 939, thuộc hệ
  thống cũ của Ticketbox. Script bỏ qua chúng.
- **Không phải mục nào cũng là sự kiện.** Danh sách "sắp diễn" của Ticketbox có cả hàng lưu niệm
  (merchandise) và vé tham quan dài hạn, với ngày bắt đầu nằm trong quá khứ. Script vẫn lưu, để bạn tự
  quyết định có loại khỏi mô hình không. Có thể lọc theo `the_loai` (Khác, Tham quan & Trải nghiệm) và
  theo `dia_diem` = "Online Event".

## Đặt lịch chạy `snapshot_status.py` mỗi ngày (khoảng 9h sáng)

Một hạng vé chỉ được ghi **một dòng mỗi ngày**. Chạy hai lần trong ngày không sao. Máy tắt lỡ một ngày
cũng không sao: hôm sau chạy tiếp, ngày bị lỡ chỉ thiếu dòng.

### macOS / Linux (cron)

```bash
crontab -e
```

Thêm dòng sau, thay đường dẫn cho đúng máy:

```
0 9 * * * cd /Users/<ban>/KLTN/Project/Concert-Ticket-Platform/research/forecast-data && .venv/bin/python snapshot_status.py >> data/snapshot-cron.log 2>&1
```

Nếu máy đang ngủ lúc 9h, cron bỏ qua lần chạy đó. Để vẫn chạy khi máy thức dậy trên macOS, dùng
`launchd`, vì `StartCalendarInterval` sẽ chạy bù khi máy thức.

Máy Mac của dự án dùng launchd, đã cài ở `~/Library/LaunchAgents/vn.nexaticket.forecast-snapshot.plist`.
Job chạy `chay_hang_ngay.sh` lúc 9:00 mỗi ngày (chụp trạng thái rồi xuất lại Excel), log ở
`data/snapshot-launchd.log`. Máy tắt hẳn cả ngày thì ngày đó vẫn mất.

```bash
launchctl print gui/$(id -u)/vn.nexaticket.forecast-snapshot      # xem trạng thái, lần chạy cuối
launchctl kickstart gui/$(id -u)/vn.nexaticket.forecast-snapshot  # chạy ngay một lần
launchctl bootout gui/$(id -u)/vn.nexaticket.forecast-snapshot    # gỡ lịch
```

### Windows (Task Scheduler)

Mở PowerShell rồi chạy, thay đường dẫn cho đúng máy:

```powershell
$dir = "C:\Users\<ban>\KLTN\Project\Concert-Ticket-Platform\research\forecast-data"
$action = New-ScheduledTaskAction -Execute "$dir\.venv\Scripts\python.exe" -Argument "snapshot_status.py" -WorkingDirectory $dir
$trigger = New-ScheduledTaskTrigger -Daily -At 9am
# StartWhenAvailable: lỡ giờ vì máy tắt thì chạy ngay khi bật lại.
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable
Register-ScheduledTask -TaskName "Ticketbox snapshot" -Action $action -Trigger $trigger -Settings $settings
```

Cách dùng giao diện: mở Task Scheduler, chọn *Create Basic Task*, đặt *Daily* lúc 9:00, chọn *Start a
program*. Chương trình là `.venv\Scripts\python.exe`, đối số là `snapshot_status.py`, và *Start in* là
thư mục này. Sau đó vào *Settings* và tick *Run task as soon as possible after a scheduled start is
missed*.

## Ý nghĩa từng cột

### `su_kien.csv`: mỗi **suất diễn** một dòng

| Cột | Ý nghĩa |
|---|---|
| `ma_su_kien` | `TB001` khi sự kiện có một suất; `TB001-S1`, `TB001-S2`… khi có nhiều suất. Mã giữ nguyên qua các lần chạy. |
| `nguon` | Luôn là `ticketbox.vn`. |
| `url` | Trang sự kiện trên Ticketbox. Số cuối url là id sự kiện. |
| `ten_su_kien` | Tên sự kiện như ban tổ chức đặt. |
| `nghe_si` | Lấy từ mục Lineup của trang, nhiều người thì cách nhau bằng `; `. Đa số sự kiện để trống. |
| `the_loai` | Thể loại chính của Ticketbox, tên tiếng Việt, ví dụ "Nhạc sống" hay "Sân khấu & Nghệ thuật". |
| `thanh_pho` | Phần cuối của địa chỉ, chuẩn hoá cách viết, ví dụ "TP. Hồ Chí Minh". Có thể trống với sự kiện online. |
| `dia_diem` | Tên địa điểm như trên trang. |
| `suc_chua` | Trống khi mới cào. Nếu bạn điền ở `suc_chua_dia_diem.csv`, lần chạy sau tự chép sang. |
| `ngay_mo_ban` | Ticketbox không ghi "ngày mở bán" riêng. Cột này là mốc **sớm nhất** mà một hạng vé của suất bắt đầu bán, lấy từ `startTime` của hạng vé. Ban tổ chức sửa khung giờ bán thì mốc này đổi theo. |
| `ngay_thay_lan_dau` | Ngày script thấy suất này lần đầu. Giữ nguyên ở các lần chạy sau. Dùng làm mốc thay thế khi `ngay_mo_ban` trống hoặc đáng ngờ. |
| `ngay_dien`, `gio_dien` | Ngày (YYYY-MM-DD) và giờ (HH:MM) bắt đầu suất, theo **giờ Việt Nam**. |
| `so_hang_ve` | Số hạng vé của suất. |
| `gia_min`, `gia_max` | Giá thấp nhất và cao nhất của các hạng vé, số nguyên VND. Miễn phí là 0. |
| `trang_thai_cuoi` | Trạng thái của cả suất ở lần đọc gần nhất, gộp từ các hạng vé. Còn hạng đang bán là `CON_VE`. Mọi hạng đều hết là `HET_VE`. Còn hạng chưa mở bán là `CHUA_MO_BAN`. Các trường hợp còn lại là `NGUNG_BAN`, ví dụ vài hạng hết và vài hạng đóng bán vì suất đã diễn. |
| `ngay_cao` | Ngày đọc thông tin gần nhất. |
| `nhom_phan_tich` | `CO` khi thể loại là Nhạc sống, Sân khấu & Nghệ thuật hoặc Thể Thao **và** địa điểm không phải Online; còn lại là `KHONG`. Không dòng nào bị xoá. Lọc theo cột này để lấy tập phân tích. |

### `hang_ve.csv`: mỗi hạng vé của mỗi suất một dòng

| Cột | Ý nghĩa |
|---|---|
| `ma_su_kien` | Khớp với `su_kien.csv`. |
| `ten_hang` | Tên hạng vé. |
| `gia` | Số nguyên VND. Miễn phí là 0. |
| `so_luong` | Luôn trống, vì Ticketbox không công bố số lượng vé. |

### `trang_thai_ngay.csv`: chỉ ghi thêm, không bao giờ ghi đè

| Cột | Ý nghĩa |
|---|---|
| `ngay_cao`, `gio_cao` | Lúc chụp, giờ Việt Nam. |
| `ma_su_kien`, `ten_hang` | Hạng vé được chụp. |
| `trang_thai` | Một trong `CON_VE`, `SAP_HET`, `HET_VE`, `NGUNG_BAN`, `CHUA_MO_BAN`, `KHONG_RO`. |
| `so_ve_con` | Trống, vì Ticketbox không công bố. |
| `gia` | Giá lúc chụp, để biết giá có đổi không. |

`collect_events.py` cũng ghi một dòng cho mỗi hạng vé vào ngày nó đọc sự kiện. Nhờ vậy sự kiện **đã
diễn** có trạng thái cuối của từng hạng, ví dụ hạng nào đã hết vé. `snapshot_status.py` chỉ chụp suất có
`ngay_dien` từ hôm nay trở đi.

### `suc_chua_dia_diem.csv`: để bạn tự tra

Mỗi cặp địa điểm và thành phố có một dòng. Địa điểm có ít nhất một sự kiện nhóm `CO` nằm ở đầu file,
vì đó là những chỗ cần tra trước. Bạn tự điền `suc_chua` (số người) và `nguon_tra_cuu` (link
hoặc tên nguồn). Script giữ nguyên những gì bạn đã điền.

### `bo-du-lieu-nghien-cuu.xlsx`

File gồm các sheet `1_su_kien`, `2_hang_ve`, `3_trang_thai_ngay` và `4_suc_chua_dia_diem`, cùng tên cột
như CSV. Sheet `thong_ke` có:
- tổng số sự kiện và suất diễn;
- số sự kiện theo thể loại và theo thành phố;
- số suất và số sự kiện sắp diễn đang được theo dõi;
- số hạng vé đã hết ở lần chụp gần nhất.

Cột số được ghi thành số, nên lọc và tính được ngay.

## Cấu trúc thư mục

```
tb_reader.py        đọc Ticketbox (robots.txt, chờ, JSON), chuẩn hoá giá/ngày giờ/trạng thái, CSV
chuan_hoa_du_lieu.py  gộp suất trùng, tính nhom_phan_tich, xếp địa điểm (không gọi mạng)
collect_events.py   script 1
snapshot_status.py  script 2
export_excel.py     script 3
tests/              pytest, không gọi mạng
data/               dữ liệu (gitignore)
```

Phần đọc trang và các hàm chuẩn hoá giá, ngày giờ, thành phố lấy lại từ bộ cào demo
`scripts/ticketbox/` trên nhánh main, commit d4cd45a.

## Huấn luyện thử (train.py)

Bản kiểm tra quy trình LightGBM quantile regression, chưa phải kết quả cuối. Có thêm dữ liệu thì chạy lại:

```bash
brew install libomp          # chỉ lần đầu, LightGBM trên macOS cần thư viện này
.venv/bin/python train.py    # tuỳ chọn: --ti-le-test 0.2 --thu-muc models
```

- Đơn vị: một suất nhóm CO đã diễn và đã được chụp sau buổi diễn.
- Nhãn: tổng giá các hạng HET_VE / tổng giá mọi hạng, ở lần chụp đầu tiên sau ngày diễn. Đây là đại
  lượng thay thế cho sức mua vì Ticketbox không công khai số vé bán.
- Đặc trưng chỉ dùng thông tin có lúc mở bán và tín hiệu 7 ngày đầu, không dùng `trang_thai_cuoi` hay
  `ngay_thay_lan_dau`. Đặc trưng có ở dưới 30% dòng train (hiện là sức chứa và tín hiệu 7 ngày) bị bỏ
  tự động, và tự được dùng lại khi đủ dữ liệu.
- Chia theo thời gian: 20% suất diễn muộn nhất làm test, cắt theo ngày.
- Mốc so sánh: quantile của nhãn theo thể loại trong tập train.
- Đầu ra ở `models/`: `model_q10.txt`, `model_q50.txt`, `model_q90.txt`, `features.json`,
  `encoders.json`, `du_doan_test.csv`, `bao_cao.json`. Cuối mỗi lần chạy, script mở lại mô hình từ đĩa
  và kiểm tra dự đoán giống hệt.
- Xác suất hòa vốn: `xac_suat_hoa_von(q10, q50, q90, nguong)`, với `nguong` lấy từ
  `nguong_tu_so_ve(số vé hòa vốn, tổng số vé)` hoặc `nguong_tu_chi_phi(chi phí, doanh thu tối đa)`.

## Đánh giá bổ sung (danh_gia_them.py)

Chạy sau `train.py`. Không thay đổi mô hình đã lưu:

```bash
.venv/bin/python danh_gia_them.py
```

Kết quả ghi vào `models/danh_gia_them.json`, gồm:

- phễu dữ liệu;
- kiểm tra nhãn (độ trễ gắn nhãn, số hạng vé, trạng thái sau buổi diễn);
- so sánh với 3 mô hình cơ sở kèm bootstrap ghép cặp;
- Brier score và AUC của xác suất đạt ngưỡng 0,3 / 0,5 / 0,7, so với một bộ phân loại LightGBM;
- learning curve, rolling-origin, mức quan trọng của đặc trưng và ablation.

Hình cho bài báo được ghi vào `../hinh/`.

## Công cụ dự báo cho ban tổ chức (du_bao_api.py)

```bash
.venv/bin/python du_bao_api.py   # mở http://localhost:8765
```

Dịch vụ nạp mô hình trong `models/` (phải chạy `train.py` trước). API gồm:

- `GET /api/thong-tin`
- `POST /api/du-bao`, nhận JSON gồm: `the_loai`, `thanh_pho`, `gia_min`, `gia_max`, `so_hang_ve`, `so_suat_su_kien`, `ngay_mo_ban`, `ngay_dien`, `gio_dien`, `chi_phi`, `doanh_thu_toi_da`.

Kết quả trả về gồm `q10`, `q50`, `q90`, ngưỡng `chi_phi / doanh_thu_toi_da` và xác suất đạt ngưỡng. Dịch vụ hiện chạy độc lập, chưa gắn vào NexaTicket.
