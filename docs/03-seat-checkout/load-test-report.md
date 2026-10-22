# Báo cáo load test (k6) — lần chạy trên máy dev

Báo cáo này điền cho [load-test-plan.md](load-test-plan.md), nhưng **không phải** phép thử của cổng
Milestone 03. Kế hoạch đòi 10.000 người dùng đồng thời trên staging cỡ production; đây là lần chạy đầu
tiên của bộ script `load-tests/k6/`, trên **một laptop 16 GB** nơi k6, Docker, Keycloak, RabbitMQ,
PostgreSQL, Redis và bốn service Spring cùng dùng chung CPU và RAM. Số tuyệt đối vì thế **bi quan hơn
thực tế**; thứ có giá trị là các bất biến (không bán trùng, không âm) và các lỗi lộ ra.

- Ngày chạy: 22/10/2026 · k6 v2.2.0 (`winget install GrafanaLabs.k6`)
- Hệ thống: gateway, identity, catalog, inventory chạy bằng jar (1 JVM mỗi service), dữ liệu demo của
  catalog (34 sự kiện). Ở lượt `checkout.js` bật thêm ordering và payment-service; **payment-service trỏ
  vào payOS giả** (`load-tests/fake-payos/`), không nạp `.env`, nên không có lời gọi nào tới payOS thật.
  ledger, ticketing, notification không bật.
- Người dùng: 4 tài khoản dev của realm `nexaticket` (customer, organizer, staff, superadmin), token
  lấy bằng password grant từ Keycloak cục bộ.

## 1. `discovery.js` — đọc catalog và sơ đồ ghế (50 VU, 30 s lên + 60 s giữ)

Suất được chọn tự động: sơ đồ vòng chung kết có **16.796 ghế, 5,3 MB JSON** mỗi lần đọc.

| | Heap inventory 384 MB (mặc định của `dev-up.sh --jar`) | Heap inventory 1 GB |
| --- | --- | --- |
| Kết quả | **hỏng**: 62 lần `OutOfMemoryError: Java heap space`, inventory ngừng trả lời | ổn định, 0 lỗi |
| Request lỗi | 3,10% (36/1.160), nhiều cái chờ tới 60 s | **0,00%** (0/3.851) |
| Checks (gồm bất biến) | 96,9% | **100%** (8.981/8.981) |
| Bất biến "khu đứng ∈ [0, sức chứa]" | đạt | đạt |
| `seat_map` p95 | 24,9 s | **2,82 s** (ngưỡng 500 ms ✗) |
| `events_list` p95 | 347 ms | **465 ms** (ngưỡng 300 ms ✗) |
| `event_detail` p95 | 237 ms | **320 ms** (ngưỡng 300 ms ✗) |
| Dữ liệu nhận | 1,8 GB | 6,8 GB (64 MB/s trên loopback) |

**Đọc kết quả:** cả ba ngưỡng độ trễ đều vượt. Không có lỗi chức năng và không số liệu nào sai, nhưng:

1. **Sơ đồ ghế không được nén — dù `server.compression.enabled: true` đã có trong `application.yml`.** Gửi
   `Accept-Encoding: gzip` thẳng vào inventory vẫn nhận 5.312.781 byte, không có `Content-Encoding`. Nguyên nhân:
   controller trả **ETag mạnh** (`"301-anon"`), và Tomcat **không nén** phản hồi có ETag mạnh (mặc định
   `noCompressionStrongETag=true`) — im lặng, không log. Cấu hình đúng, mọi test đúng, và nén không bao giờ chạy.
   Test MockMvc không bắt được vì nó không có Tomcat.
2. **Mỗi request dựng cả bản đồ rồi mới so ETag.** Kể cả khi client gửi đúng `If-None-Match` và nhận 304, server vẫn
   đọc và dựng 16.796 ghế để rồi vứt đi. Cùng chỗ đó đọc version SAU khi đọc ghế, nên một lần giữ chỗ chen giữa làm
   version mới đi kèm dữ liệu cũ — client giữ dữ liệu cũ dưới ETag mới và nhận 304 mãi tới lần đổi sau.
3. **Hết bộ nhớ ở heap 384 MB** (`JVM_OPTS` của `dev-up.sh --jar` / `--demo`).
4. Ngưỡng 300–500 ms được đặt cho đường đọc nhẹ; với payload 5 MB nó không thể đạt dù hệ thống nhanh.
   Nên tách ngưỡng (`seat_map` theo kích thước suất) hoặc trả bản đồ dạng gọn / phân trang theo khu.

### Đã sửa, và đo lại

- `SeatMapController`: ETag **yếu** (`W/"…"`) để Tomcat nén; so sánh `If-None-Match` theo RFC 9110 (bỏ `W/`, chấp nhận
  danh sách và `*`, nên client còn giữ ETag mạnh cũ vẫn khớp); **304 sớm** — chỉ đọc version (một câu truy vấn nhẹ), không
  dựng sơ đồ.
- `SeatQueries.seatMap`: đọc version **trước** dữ liệu.
- `dev-up.sh`: inventory chạy với heap 768 MB (các service còn lại giữ 384 MB).
- `discovery.js`: gửi `Accept-Encoding: gzip` như trình duyệt (k6 không tự gửi — các lần đo trước đó vô tình đo bản
  KHÔNG nén) và kiểm `Content-Encoding: gzip`.

| | Trước (heap 1 GB, không nén) | Sau (heap 768 MB, nén) |
| --- | ---: | ---: |
| Một sơ đồ qua gateway | 5.312.781 B | **674.165 B** (−87%) |
| 304 khi gửi đúng `If-None-Match` | dựng cả sơ đồ rồi vứt | **29 ms, thân rỗng** |
| Dữ liệu k6 nhận (90 s, 50 VU) | 6,8 GB | **1,1 GB** |
| `seat_map` p95 | 2,82 s | **1,74 s** (ngưỡng 500 ms vẫn ✗) |
| `events_list` p95 / `event_detail` p95 | 465 / 320 ms | **349 / 206 ms** (`event_detail` đạt 300 ms) |
| Request / giây | 36 | **43** |
| Lỗi / checks / `OutOfMemoryError` | 0% / 100% / 0 | 0% / **100% (12.280/12.280, gồm "được nén")** / 0 |

Test giữ lại: `SeatMapCompressionIT` (Tomcat thật qua `HttpClient` của JDK: có `Content-Encoding: gzip`, nhỏ hơn 1/5, giải
nén ra đúng nội dung; ETag yếu; 304 không thân) — **đỏ khi đặt lại ETag mạnh** (đã thử), xanh khi có bản sửa — và
`SeatMapControllerTest` (304 sớm không gọi `seatMap`, ETag mạnh cũ vẫn khớp, danh sách / `*`).

**Còn lại:** `seat_map` vẫn vượt 500 ms ở 50 người dùng đồng thời trên một laptop dùng chung. Mỗi request không có
`If-None-Match` vẫn đọc 16.796 dòng và tuần tự hoá 5 MB JSON. Bước tiếp theo đáng làm: nhớ bản sơ đồ đã dựng theo
(suất, version) — với khách ẩn danh nội dung giống hệt nhau tới lần version nhảy — và đo lại trên staging.

## 2. `hold-contention.js` — 4 người cùng giữ đúng một nhóm 4 ghế

Bốn VU (bốn người dùng thật) bắn `POST /v1/sessions/{id}/holds` cùng một khoảnh khắc qua gateway →
Redis gate → PostgreSQL, với `Idempotency-Key` riêng. Người thắng giữ hold 4–10 s rồi nhả.

| Lần chạy | Thắng (201) | Thua (409) | Trạng thái lạ | Ghế về `AVAILABLE` sau khi nhả |
| --- | ---: | ---: | ---: | --- |
| 1 | 1 | 3 | 0 | ✓ |
| 2 | 1 | 3 | 0 | ✓ |
| 3 | 1 | 3 | 0 | ✓ |
| 4 | 1 | 3 | 0 | ✓ |

**Đạt cả bốn lần: đúng một người thắng, không bán trùng, không ghế nào kẹt `HELD`.** Ngưỡng
`holds_won == 1` đạt. (k6 báo `http_req_failed` 33% vì coi 409 là lỗi; đó là kết quả mong đợi, script
không đặt ngưỡng lên số đó.)

Giới hạn: chỉ có 4 người dùng dev nên tối đa 4 bên tranh nhau; không thay cho 10.000 VU của kế hoạch.
Bán trùng đã được kiểm xác định hơn ở tầng service bằng integration test nhiều luồng
(`SeatHoldConcurrencyIT`, `OversellBackstopIT`, `ReleaseReserveRaceIT`).

## 3. `checkout.js` — giữ chỗ → đặt đơn → gửi lại cùng khoá → huỷ / thanh toán (payOS giả)

payOS không có sandbox, nên lượt này chạy với `load-tests/fake-payos/server.mjs`: một server nói đúng giao thức
payOS mà `PayosHttpGateway` dùng (tạo / tra / huỷ link; phản hồi ký HMAC-SHA256 đúng thuật toán, request
cũng bị kiểm chữ ký và `description` ≤ 9 ký tự). Tiền không bao giờ vào; hoàn tất thanh toán bằng
`simulate-transfer` (`PAYMENT_SANDBOX=true`).

**Thử tay một đơn trước khi chạy tải:** giữ chỗ 201 → đặt đơn 201 (link do payOS giả cấp, chữ ký phản hồi qua
kiểm) → gửi lại cùng `Idempotency-Key` trả đúng đơn cũ kèm `Idempotent-Replayed: true` và **không** mở link thứ
hai → gửi không có khoá 400 → `simulate-transfer` → `CONFIRMED` → đơn `PAID`.

| Lượt | Cấu hình | Kết quả |
| --- | --- | --- |
| A — không thanh toán (huỷ cuối vòng) | 4 người dùng, 2 đơn/s, 30 s, khu `UB-325` | **61 vòng, 244/244 checks (100%)**, 0% request lỗi. p95 giữ chỗ **43 ms** (ngưỡng 800), p95 đặt đơn **115 ms** (ngưỡng 1.500). payOS giả: 62 link tạo (61 + 1 đơn thử tay), 61 huỷ — gửi lại cùng khoá **không** mở thêm link |
| B — có thanh toán | 1 đơn/s, 6 s, khu `UB-302` | **7 vòng, 35/35 checks**, mọi đơn chuyển `PAID` sau `simulate-transfer` (≈ 550 ms, phần lớn là nhịp thăm 0,5 s của script) |

Các check đạt, mỗi vòng: giữ chỗ 201 · đặt đơn 201 · tổng tiền do server tính và dương · gửi lại cùng khoá trả đúng
đơn cũ · đơn chuyển `PAID`.

Giới hạn: tải thấp (2 đơn/s, 4 người dùng) trên máy dùng chung; chưa chạm trần hạn mức mua của người dùng và
chưa có ticketing / ledger / notification nên chưa kiểm phát vé sau `PAID`.

**Một điều quan sát được — đã tìm ra nguyên nhân và đã sửa:** hạng vé "Sát sàn 16 · Courtside" của
`chung-ket-vba-2026` có giá **25.000đ** trong catalog (`ticket_types.price_vnd`, và trên trang chi tiết sự kiện)
nhưng **2.500.000đ** trong inventory (`session_seats.price_vnd`) — khách thấy một giá, đơn lại tính giá kia
(inventory là nguồn giá của đơn).

Nguyên nhân là một lỗi thật, không phải dữ liệu demo: sửa giá hạng vé chỉ làm được khi sự kiện đã rút xuống
(`EditableEvent`), rồi publish lại. Publish lại gửi `session.published` với giá mới, nhưng
`JdbcSessionMaterializer` chỉ đồng bộ lại cửa bán, trần mua vé và toạ độ — **không đồng bộ giá** — nên tồn kho giữ
giá cũ vĩnh viễn. (Cùng họ lỗi với cửa bán đã được sửa trước đó; bản sửa cố ý không đụng giá vì lo hoá đơn của chỗ
đã bán, một lo ngại đúng nhưng chỉ áp cho chỗ đã bán.)

**Đã sửa:** `syncUnsoldPrices` đồng bộ giá và tên hạng vé cho chỗ **chưa có ai đụng tới** (`AVAILABLE`, `BLOCKED`, kể cả
đơn vị ảo của vé đứng); chỗ đã bán / đang giữ / đã đặt giữ giá khách đã thấy. Kiểm bằng
`MaterializeSessionIT.publish_lai_thi_dong_bo_gia_cho_chua_ban` (đỏ khi bỏ bản sửa: giá vẫn 1.500.000, xanh khi có).
Dữ liệu demo đã lệch sẵn được chữa bằng `POST /v1/organizations/{org}/events/{id}/resync-inventory` (hoặc rút xuống rồi
publish lại) sau khi triển khai bản sửa.

## 4. Chưa chạy

| Mục | Lý do |
| --- | --- |
| payOS **thật** (trang thanh toán, webhook thật) | Không có sandbox; không tự động hoá an toàn được — kiểm tay một lần với số tiền nhỏ trước khi lên production |
| ticketing / ledger / notification trong luồng `PAID` | Không bật ở lượt này |
| Điều kiện 10.000 VU, p95 hold ≤ 300 ms | Cần staging; một laptop không dựng nổi |
| Kill Redis giữa lúc giữ chỗ → 503 retryable; webhook trùng; worker khởi động lại | Cần phá hạ tầng có chủ đích (mục "Chaotic cases" của kế hoạch) |

## 5. Việc nên làm tiếp (còn lại)

- Nhớ bản sơ đồ đã dựng theo (suất, version) để request không có `If-None-Match` không phải đọc lại hàng chục nghìn ghế.
- Đặt ngưỡng `seat_map` riêng theo kích thước suất; chạy lại trên staging với đủ người dùng để có số cho cổng M03.
