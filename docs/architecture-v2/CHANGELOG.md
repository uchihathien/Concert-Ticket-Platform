# Lịch sử sửa đổi — Kiến trúc v2

## Bản 10 — Kiểm toàn hệ, và những gì phép kiểm ấy đòi phải sửa

Bản 9 sửa năm lỗi tìm được lúc chạy thật. Bản này đi hết phần còn lại của hệ thống, và điều đáng
ghi nhất là **ba giả định phổ biến về một nền tảng bán vé đều SAI với hệ này**: cơ chế retry đã có
(backoff mũ 1→16 phút, `MAX_ATTEMPTS=5`, `event_id UNIQUE` chống gửi trùng), chống double-click đã
có (`Button` khoá khi `loading`, `SeatPicker` chặn `submitting`), và tranh chấp ghế đã được kiểm
nghiêm túc (200 luồng giành 1 ghế, 500 người mua 200 chỗ đứng, trần 20-tab, và cả ca "cổng Redis
mù").

Chỗ yếu thật nằm ở nơi khác, và nó có một hình dạng chung: **độ phủ ngược với mức rủi ro**.

### 1. API Gateway: thiết kế đúng, nhưng chưa ai kiểm

Rate limit dùng `XForwardedRemoteAddressResolver.maxTrustedIndex` — đúng API cho đúng vấn đề, có
ghi chú nêu cả hai hướng sai. CORS không wildcard, `allowCredentials=false`. `/internal/**` thật sự
không có route nào (đã kiểm 29 mẫu đường dẫn).

Nhưng service này có **0 test và 0 phụ thuộc test**. Giờ có 17.

Bài kiểm quan trọng nhất là giả mạo `X-Forwarded-For`: rate limit chỉ có tác dụng khi khoá đếm
không do người gọi tự chọn. Trong lúc viết nó, ca đầu tiên của tôi **xanh vì lý do sai** — tôi
dựng chuỗi XFF ngược chiều (IP khách nằm ở CUỐI, do proxy ghi thêm, không phải ở đầu). Chỉ vì ca
thứ tư đỏ mới lộ ra. Có thêm một ca ghim lại mối nguy khai thừa `trusted-proxy-count`: khai 2 trong
khi chỉ có 1 ingress thì địa chỉ do người gọi tự gõ trở thành địa chỉ được tin, và hạn mức bốc hơi
trong im lặng.

### 2. Hạn mức: một ngưỡng cho mọi đường là sai ở hai đầu

| Đường | Ngưỡng | Vì sao khác |
| --- | --- | --- |
| `POST /v1/sessions/*/holds` | 5 rps | Điểm tranh chấp thật của đợt mở bán. Rộng rãi với người thật, chặt với bot rải request. |
| `POST /v1/chat/agent/support` | 1 rps | Đường DUY NHẤT mà một request tốn tiền ở bên ngoài. Cái mất không phải tải hệ thống mà là hoá đơn — autoscaling không cứu được. |
| còn lại | 50 rps | Sàn chung, áp cho cả route thêm vào ngày mai mà người thêm quên nghĩ tới hạn mức. |

Điểm suýt sai: tôi định bóp `/v1/holds/**`, cho tới khi đọc controller và thấy đường ấy chỉ có
`DELETE` — tức là **NHẢ** ghế. Bóp nó lại là làm khó đúng cái việc trả ghế về cho người khác mua.
Tạo hold thật ra là `POST /v1/sessions/{id}/holds`. Tương tự, `/v1/support/**` là bàn làm việc của
nhân viên hỗ trợ chứ không phải đường gọi mô hình — bóp xuống 1 rps là làm hỏng công việc của chính
người đang dọn hậu quả cho trợ lý.

`GatewayRoutingIT` ghim bảng route lại. Hai route mới trỏ tới **đúng service** như route rộng ngay
sau chúng, nên đảo thứ tự không gây lỗi nào, không 404 nào — chỉ có hạn mức chặt biến mất. Đã kiểm
bằng cách đảo thật: test đỏ.

### 3. Hiệu năng: đo trước, rồi mới quyết định không làm gì

Soi toàn hệ tìm được 9 khoá ngoại không có index. Thêm **đúng một**.

Sáu cột không có truy vấn nào lọc theo chúng. `events.venue_id` thì có hai, và `events` là bảng duy
nhất trong chín cái vừa tăng không giới hạn theo quy mô kinh doanh — đã chứng minh bằng đo: ở 50k
dòng, có index cho `Index Scan` xét 0 dòng, bỏ index cho `Seq Scan` loại bỏ 50.029 dòng.

`seat_holds.event_session_id` là cái quan trọng nhất phải **không** thêm: đó là bảng ghi nặng nhất
của cả hệ trong một đợt mở bán, và không câu truy vấn nào lọc riêng theo cột ấy.

### 4. Giao diện: ranh giới lỗi ở đúng chỗ không có

`web-customer` — app gánh toàn bộ lưu lượng mua vé — có **0 `error.tsx`** và **0 `loading.tsx`**,
trong khi hai khu quản trị lưu lượng thấp thì có đủ. `web-scanner`, chạy ở cửa soát vé với hàng
người đang chờ, không có gì cả.

Đã thêm cho cả hai. Skeleton dùng lại **chính** CSS của trang thật, nên lưới chờ và lưới thật cùng
số cột ở cùng các ngưỡng màn hình — dựng lưới riêng thì đến 1240px hoặc 900px hai bên lệch nhau và
khoảnh khắc dữ liệu về là một cú nhảy bố cục. `EventCardSkeleton` nằm cạnh `EventCard` và dùng
chung `catalog.module.css`; có test so tên lớp CSS đọc từ DOM, đã xác nhận nó đỏ khi cố tình làm
lệch.

### 5. Mã QR: bốn chỗ hiện trần, và hai trong số đó là hai LOẠI mã khác nhau

Một lưới ô vuông đen trắng thả giữa trang không nói được đó là mã gì, quét bằng cái gì. Tệ hơn: mã
vào cửa và mã VietQR trông y hệt nhau, dùng ở hai hoàn cảnh khác hẳn, và quét nhầm thì không có gì
xảy ra cả.

`QrPanel` đóng khung cả bốn chỗ, hai biến thể khác màu. Ràng buộc không được vi phạm vì thẩm mỹ:
**mọi trang trí nằm ngoài tấm nền trắng**. Chuẩn QR đòi một vùng yên tĩnh bao quanh mã và máy quét
dùng nó để tìm ba mốc định vị — đặt gradient hay hoạ tiết lên đó cho ra một tấm mã *vẫn đẹp và
không quét được*, kiểu hỏng chỉ lộ ra ở cửa soát vé. Có test so chính đường vẽ của mã với mã trần.

### 6. Hình bố cục thứ ba: bàn tròn

`TABLE` — `rowCount` bàn, mỗi bàn `seatsPerRow` ghế quây quanh. Cách ngồi của gala, tiệc cuối năm
và đêm nhạc phòng trà.

Điều kiện để một hình được nhận vào `LayoutShape` là **giữ được nghĩa của mã chỗ** `A-3-5`: soát vé
và hỗ trợ khách hàng đọc nó thành lời hàng ngày. Với `GRID`/`ARC` thì "đơn vị" là hàng; với `TABLE`
là bàn — "bàn 3, ghế 5" vẫn nói được qua điện thoại. Đa giác tự do không qua được cửa này.

Không thêm cột nào: `layout_inner_radius` mang nghĩa "bán kính bàn", đúng như nó mang nghĩa "bán
kính hàng đầu" cho `ARC` — hai hình không bao giờ cùng tồn tại trên một khu. Số bàn mỗi hàng suy ra
được (`ceil(sqrt(n))`), và một cột suy ra được là một cột sẽ có ngày mâu thuẫn.

`V0106` nới ràng buộc theo đúng bài học `V0103`: mọi cột canh bằng so sánh phải có `IS NOT NULL`
tường minh. Soi lại toàn hệ sau đó: 73/73 ràng buộc, 0 lỗ NULL.

### 7. Dữ liệu mẫu: 26 → 36 sự kiện, và ba hình dạng có chỗ nhìn thấy được

Trước đó **mọi** khu mẫu đều xếp tự động, nên không hình bố cục nào có chỗ nào nhìn thấy. Giờ Nhà
hát Lớn là ba tầng `GRID` đặt tường minh, Sân vận động Quốc gia là hai khán đài `ARC` chia trái/phải
(không đồng tâm — chia đồng tâm thì khán đài ngoài có cùng số ghế trên một cung dài gấp ba và hiện
ra thưa thớt vô lý), và Phòng trà Không Tên là `TABLE` với sân khấu `CIRCLE`.

Poster tự vẽ giờ mang thêm dòng đội hình nghệ sĩ. Việc chèn nó **làm lộ một lỗi trong chính phép
dựng chữ**: tiêu đề neo theo dòng ĐẦU rồi vẽ xuống dưới, nên nó tràn đè lên khối nghệ sĩ vừa chèn —
vẫn là SVG hợp lệ, vẫn không lỗi nào, chỉ là hai dòng chữ chồng khít. Đã viết lại cho cả ba khối
cùng neo theo dòng CUỐI, và test "mọi dòng cách nhau ≥ 24 đơn vị" là thứ đã bắt được nó.

Tên nghệ sĩ đều **hư cấu**. Gắn tên người có thật vào những buổi diễn không có thật là chuyện không
nên làm kể cả trong dữ liệu mẫu.

### 8. "Đang hot": tín hiệu đã có sẵn, chỉ thiếu đường ra

Trang chủ trước đó hiện "Sự kiện nổi bật" = mới nhất, không phải đang hot. Nhưng
`analytics.ordering.all` vốn đã tiêu thụ mọi sự kiện đơn hàng và giữ `session_sales.tickets_sold`
theo từng suất, kèm index trên `event_id`.

Nên không dựng bộ đếm thứ hai trong catalog: hai con số cho cùng một câu hỏi, và ngày chúng lệch
nhau thì không ai biết tin cái nào. Analytics thêm `/internal/events/trending`; catalog gọi nó qua
`AnalyticsSalesAdapter` vốn đã tồn tại.

Hai quyết định đáng ghi. **Cổng chỉ nhận về thứ tự, không nhận số vé** — số vé một sự kiện bán được
là con số kinh doanh của ban tổ chức ấy, và một endpoint công khai không phải chỗ công bố nó cho
đối thủ của họ. **Analytics chết thì trang chủ vẫn mở** — hỏng thì trả danh sách rỗng và hàng "đang
hot" biến mất, khách không mất cả trang chủ; ghi log WARN vì một hàng biến mất là thứ không ai nhận
ra qua màn hình.

### 9. Đồng ý điều khoản: đặt ở bước mua, không ở bước tạo tài khoản

Keycloak có sẵn required action `TERMS_AND_CONDITIONS` nhưng văn bản mặc định của nó là placeholder
tiếng Anh. Quan trọng hơn: với bán vé thì điều khoản có giá trị thật là **chính sách hoàn/đổi của
lần mua này**, và nó phải nằm trong tầm mắt đúng lúc người ta sắp trả tiền.

Ô tick không tick sẵn — một ô đã tick sẵn không phải là sự đồng ý của ai cả. Điều kiện được kiểm cả
trong `submit()` chứ không chỉ ở thuộc tính `disabled` của nút: `disabled` là chuyện của giao diện
và bỏ qua được bằng công cụ phát triển.

### Những chỗ đã kiểm và KHÔNG tìm thấy lỗi

Ghi ra vì "đã soi rồi" là thông tin, và vì nó quyết định nên tin con số nào:

- **identity-service** — không tự làm mật mã, giao JWT/refresh/mật khẩu cho Keycloak. Token lời mời
  dùng `SecureRandom` 32 byte, chỉ lưu SHA-256, dùng một lần, hết hạn 7 ngày, chặn leo thang vai
  trò. Test có sẵn phủ cả ca liên tổ chức, phân biệt 401/403/404, và chốt "chủ sở hữu cuối cùng".
- **inventory-service** — `FOR UPDATE SKIP LOCKED` đúng chỗ, partial unique index chống đặt trùng.
- **notification-service** — retry backoff mũ, `DEAD` sau 5 lần, chống gửi trùng bằng `event_id`.
- **Quên mật khẩu** — đã có, `resetPasswordUrlFromEnv` nối vào cả 4 trang đăng nhập.

### Điều rút ra

Bản 9 nói: lỗi im lặng chỉ lộ ra khi chạy thật. Bản này thêm một vế — **phép soi lỗi cũng phải
được soi**. Phép soi NULL đầu tiên của tôi dùng regex và bỏ sót `ck_zone_shape`; phép thứ hai cho
66 kết quả toàn nhiễu vì thay NULL cả vào cột `NOT NULL`; phép đo index đầu tiên dùng
`gen_random_uuid()` nên bộ lập kế hoạch không dùng index được và tôi suýt kết luận index vô dụng;
bài kiểm giả mạo XFF đầu tiên xanh vì tôi dựng dữ liệu ngược chiều.

Một công cụ soi lỗi chưa được kiểm lại chỉ là một nguồn tự tin sai chỗ. Nó phải chứng minh được
rằng nó bắt được cái lỗi đã biết trước — đúng như một test phải đỏ khi bản sửa bị gỡ ra.

## Bản 9 — Lần đầu chạy thật, và năm thứ chỉ lộ ra khi chạy

Bản 8 đóng lại với một câu thú nhận: *"vòng sinh → upload → hiển thị thì phải chạy thật mới
biết"*. Docker lên được, và lần chạy thật ấy tìm ra năm vấn đề mà không lần đọc mã nào bắt được.

### 1. `CHECK` đi qua khi biểu thức bằng `NULL` — không phải khi nó đúng

Ràng buộc hình học của bản 8 kiểm bán kính và kích thước sân khấu bằng phép **so sánh**:

```sql
AND layout_inner_radius > 0            -- NULL > 0  ⇒  NULL
AND (stage_shape = 'CIRCLE' OR stage_height > 0)
```

Trong SQL, so sánh với `NULL` cho ra `NULL` chứ không cho ra sai, và `CHECK` **chỉ chặn khi biểu
thức sai hẳn**. Nên một khu `ARC` thiếu bán kính, hay một sân khấu chữ nhật thiếu chiều cao, vẫn
lưu được — đúng hai trạng thái mà ràng buộc ấy sinh ra để ngăn.

Điều làm nó nguy hiểm chứ không chỉ luộm thuộm nằm ở đường **đọc**: `rs.getDouble` trả `0.0` cho
cột `NULL` mà không báo gì, rồi `ZoneLayout` từ chối bán kính 0 bằng `IllegalArgumentException` —
ném ra giữa lúc dựng kết quả. Hậu quả không phải một lần ghi hỏng mà là **sơ đồ mặt bằng của địa
điểm ấy trả 500 vĩnh viễn**, kể cả với người chỉ vào xem, cho tới khi có người sửa hàng bằng tay.

`V0103__seat_geometry_null_guard.sql` viết lại cả bốn ràng buộc với `IS NOT NULL` tường minh cho
mọi cột chỉ được canh bằng so sánh. Những cột vốn đã có `IS NOT NULL` thì không hở: `IS NOT NULL`
trả sai chứ không trả `NULL`.

Dữ liệu cũ được **đặt về `NULL` toàn bộ bố cục** chứ không đoán giá trị còn thiếu — `NULL` nghĩa là
"xếp tự động" và `FloorPlan.of` xử lý được, nên khu vẫn hiện ra. Đoán một bán kính thì tạo ra một
sơ đồ trông đúng mà sai chỗ, và không ai biết để sửa.

14 phép thử trên database thật khoá lại hành vi này.

### 2. Hệ thống tự xoá tấm ảnh mà chính nó vừa vẽ

`DemoPosterSeeder` đẩy poster lên dưới dạng `image/svg+xml`. `PosterUrlPolicy` đối chiếu
content-type với `allowedContentTypes` — `jpeg, png, webp` — và với định dạng lạ thì nó **xoá vật
thể rồi ném lỗi**. Hai nửa của cùng một hệ thống mâu thuẫn trực tiếp.

Cách sửa không phải là thêm `svg` vào danh sách ấy. Danh sách đó gác cổng **tải lên**, và SVG là
tài liệu XML mang được `<script>` chạy dưới origin của kho ảnh — cho ban tổ chức tải lên SVG tuỳ ý
là mở một đường XSS lưu trữ.

| Danh sách | Câu hỏi nó trả lời | Nội dung |
| --- | --- | --- |
| `allowedContentTypes` | trình duyệt được **gửi lên** kiểu gì | jpeg, png, webp |
| `isStorableType` | kiểu gì được **tồn tại** trong kho | thêm `image/svg+xml` |

Tách ra thì vật thể SVG chỉ có thể do `PosterArtwork` tạo ở phía máy chủ. Trần kích thước vẫn áp
cho cả SVG — miễn kiểm định dạng không có nghĩa là miễn kiểm mọi thứ.

### 3. MinIO không còn tồn tại để kéo về

`minio/minio` và `minio/mc` đã bị gỡ khỏi mọi registry công khai — Docker Hub, quay.io, ghcr.io —
kể cả các tag phiên bản cũ. `deploy/compose/infra.yml` và `prod.yml` có một giai đoạn **không ai
khởi động được**, và lỗi hiện ra là `pull access denied`, một câu không hề gợi ý rằng nguyên nhân
nằm ở chuyện cấp phép của nhà cung cấp.

SeaweedFS thay vào chỗ đó (Apache-2.0, một container). Đã kiểm đúng sáu luồng mà ứng dụng dùng
chứ không kiểm "nó là S3": URL ký sẵn `PUT` được, **chữ ký sai bị chặn**, `HEAD` trả đúng
content-type và kích thước (`PosterUrlPolicy` dựa vào cả hai), `GET` ẩn danh đọc được, CORS
preflight đúng — và với origin lạ thì bị từ chối — và xoá thật sự xoá.

| Quyết định | Vì sao |
| --- | --- |
| Ghim `chrislusf/seaweedfs:4.47`, bỏ mọi tag `latest` | Đây chính là cách sự cố này xảy ra. Tag nổi làm bản dựng hôm nay khác hôm qua mà không ai đổi một dòng nào, và khi hỏng thì hỏng cho tất cả cùng lúc. |
| Bỏ hẳn container `minio-init` | `-s3.autoCreateBucket` tạo bucket ở lần `PUT` đầu, và danh tính `anonymous` với quyền `Read` thay cho `mc anonymous set download`. Ít đi một container và một ảnh phụ thuộc. |
| Cấu hình danh tính **sinh lúc khởi động** từ biến môi trường | `-s3.config` chỉ đọc file tĩnh. Đặt sẵn file ấy trong repo nghĩa là khoá production nằm trong lịch sử git. |
| Healthcheck gọi `/status`, không gọi `/` | `/` trả 403 vì danh tính ẩn danh không được liệt kê bucket — dùng nó thì container không bao giờ lành. |
| Service đổi tên `minio` → `objectstore` | Giữ tên cũ cho một phần mềm khác là để lại một cái bẫy cho người đọc sau. |

Muốn dùng dịch vụ có sẵn (S3, Cloudflare R2): xoá service này và trỏ `MEDIA_ENDPOINT` +
`MEDIA_PUBLIC_URL` sang đó. Adapter đã là AWS SDK v2 nên không phải sửa code.

### 4. Cùng lỗ NULL ấy, ở hai ràng buộc có từ V0100

Sau khi vá §1, câu hỏi tiếp theo là "còn chỗ nào nữa không". Phép soi đầu tiên dùng regex tìm cột
không có `IS NULL` đi kèm — và nó **bỏ sót** `ck_zone_shape`, vì `capacity IS NULL` có mặt trong
biểu thức, chỉ là ở nhánh kia:

```sql
   (kind = 'SEATED'   AND row_count > 0 AND seats_per_row > 0 AND capacity IS NULL)
OR (kind = 'STANDING' AND capacity > 0  AND row_count IS NULL AND seats_per_row IS NULL)
```

Khu SEATED thiếu `row_count`: nhánh đầu ra NULL, nhánh sau ra sai, `NULL OR false` = NULL, CHECK
đi qua. Nhìn chữ không đủ.

Phép soi thứ hai bắt Postgres tự tính: thay từng cột bằng NULL hoặc giá trị thật, chạy hết mọi tổ
hợp, hỏi tổ hợp nào cho ra NULL. Một điều chỉnh quan trọng — **chỉ thay NULL cho cột thật sự cho
phép NULL**; thay cho cột `NOT NULL` cho ra 66 "lỗ" đều là nhiễu, vì chính `NOT NULL` đã chặn.

Kết quả trên 73 ràng buộc của cả 11 database: đúng **2** lỗ thật, `ck_zone_shape` và
`ck_template_zone_shape`. `V0104` vá cả hai; soi lại cho 0.

`V0104` **không** dọn dữ liệu, khác hẳn `V0103`. Ở đó bố cục thiếu đặt về NULL được vì NULL nghĩa
là "xếp tự động" và khu vẫn hiện ra. Ở đây không có giá trị dự phòng an toàn nào — bịa ra số ghế
là bịa ra thứ đang được bán — nên nếu có hàng hỏng thì `ADD CONSTRAINT` tự hỏng và người triển
khai phải nhìn từng khu. Hỏng ồn ào đúng hơn là sửa im lặng.

### 5. `rs.getLong` trả 0 cho cột NULL, và 0 ấy đi gọi nhà cung cấp thanh toán

Nửa Java của cùng một lỗi. Soi mọi lời gọi đọc kiểu nguyên thuỷ từ `ResultSet`, đối chiếu tên cột
với danh sách cột cho phép NULL lấy từ database thật: 13 điểm, trong đó 12 vô hại (10 trong
`LayoutColumns` đã được `V0103` bảo đảm, 2 là `coalesce` có sẵn).

Điểm thứ 13 là thật. `payment_intents.payos_order_code` cho phép NULL — không phải do thiết kế mà
vì `V0101` thêm cột vào một bảng đã có dữ liệu thời SePay. `rs.getLong` biến NULL ấy thành `0`, và
`0` đi tiếp vào miền như một mã đơn thật. `ReconcileIntentHandler` chỉ chặn `isConfirmed()`, nên
đối soát một đơn cũ sẽ gọi `payos.fetchSettlement(0)` — **một request thật ra payOS với một mã
bịa**. Trong database phát triển có 29 hàng như thế.

| Quyết định | Vì sao |
| --- | --- |
| Đọc bằng `getObject(..., Long.class)` rồi quy về `NO_PAYOS_LINK` | 0 vẫn là giá trị đại diện cho "không có link", nhưng giờ nó được **chọn** ở ranh giới JDBC chứ không phải rơi ra từ `getLong`. |
| Thêm `PaymentIntent.hasPayosLink()` | Chốt trạng thái (`isConfirmed`) canh một câu hỏi khác với "có link không". Dựa vào cái này để trả lời cái kia là chỗ lỗi hay quay lại. |
| Không đổi trường sang `Long` | Tám chỗ đọc `payosOrderCode()` truyền thẳng vào gateway nhận `long`. Đổi kiểu là mở tám đường NPE mới trong đúng service không được phép hỏng — đắt hơn nhiều so với lợi ích. |

Test dựng hàng NULL thật trong database rồi khẳng định **không có lời gọi nào** ra payOS. Vô hiệu
bản sửa thì cả hai đỏ với `Expecting empty but was: [0L]` — con số 0 hiện ra đúng như mô tả.

### Điều rút ra

Cả năm đều nằm ngoài tầm với của unit test, và cả năm đều **im lặng**: một ràng buộc trông như
đang canh mà không canh, hai nửa hệ thống mâu thuẫn nhưng chỉ lộ ra khi có người sửa sự kiện mẫu,
một file compose đúng cú pháp nhưng trỏ vào thứ không còn tồn tại, và một `0` không ai gõ ra được
đi làm khoá gọi sang nhà cung cấp thanh toán. Bản 7 đã nói đúng điều này một lần rồi; bản 9 là lần
thứ hai cùng một bài học.

Cũng đáng ghi: phép soi **đầu tiên** cho §4 sai, và phép soi thứ hai suýt sai theo kiểu khác (66
kết quả toàn nhiễu). Một công cụ soi lỗi chưa được kiểm lại chỉ là một nguồn tự tin sai chỗ — nó
phải chứng minh được rằng nó bắt được cái lỗi đã biết trước, đúng như một test phải đỏ khi bản sửa
bị gỡ ra.

Một chi tiết đáng ghi riêng: 42 integration test của catalog chạy ở pha `verify` (failsafe), không
phải `test` (surefire). Chạy `mvn test` thì chúng **báo xanh bằng cách không chạy**.

## Bản 8 — Trình sửa sơ đồ, và chỗ lưu ảnh đầu tiên

Hai mảng đều có chung một hình dạng: phần lõi đã xong từ bản trước, nhưng thiếu đúng cái mảnh
khiến người dùng chạm tới được.

### 1. Trình sửa sơ đồ — và một endpoint xem trước

Mảng hình học (§1) dựng xong engine, API và bộ vẽ, nhưng **ban tổ chức không có cách nào đặt toạ
độ từ giao diện**: frontend chỉ có `createZone` (thêm một khu), còn `PUT …/zones` — đường duy nhất
nhận `layout` và `stage` — không có hàm SDK lẫn màn hình. Hệ quả thực tế là mọi sơ đồ vẫn rơi về
bố cục tự động, tức là các khối chữ nhật xếp dọc y như trước khi có hình học.

| Quyết định | Nội dung |
| --- | --- |
| Thêm `POST …/floor-plan/preview` | Trình sửa phải thấy mặt bằng của thay đổi **chưa lưu**. Cách rẻ nhất là chép công thức `ZoneLayout` sang TypeScript — đúng thứ `plan/frontend.md` bản 5 đã cấm. Nên phép tính ở lại một chỗ và màn hình hỏi nó: đắt hơn một lời gọi mạng, đổi lại thứ xem trước **đúng bằng** thứ sẽ được materialize. |
| Endpoint không ghi gì | Dựng một `Venue` trong bộ nhớ rồi giải mặt bằng. Không chạm repository, nên không có đường nào để một lần xem trước lỡ tay lưu đè sơ đồ thật. Có test riêng đếm mọi lời gọi ghi. |
| Không kiểm `VENUE_LAYOUT_LOCKED` / `VENUE_IN_USE` ở xem trước | Xem trước không đổi gì. Chặn nó sẽ làm ban tổ chức không nhìn được sơ đồ của chính địa điểm đang bán vé. Hai cửa ấy ở lại `ConfigureVenueZonesHandler`, đúng chỗ có hậu quả. |
| Mặt bằng trả thêm `layout` **đã giải** | Khu chưa đặt vị trí vẫn có một chỗ đứng sau khi bố cục tự động chạy. Không lộ ra thì lần kéo đầu tiên vào một khu tự xếp sẽ làm nó nhảy về gốc toạ độ. |
| Kéo cho vị trí, số cho góc | Vị trí khổ sở khi nhập bằng số và dễ khi kéo; góc quét thì ngược lại — kéo ra một cung 137° là tai nạn, gõ 140 thì không. |

Bản nháp gửi lên giữ nguyên `layout: undefined` cho khu chưa đặt. Điền sẵn giá trị mặc định sẽ
**ghim mọi khu lại** ngay lần lưu đầu, và chúng thôi tự dịch xuống khi ban tổ chức chèn thêm khu
phía trên — mất đúng tính chất mà §1 đã dựng.

### 2. Kho ảnh bìa: MinIO, và byte không đi qua backend

Trước đó `events.poster_url` chỉ là một chuỗi — backend *"không tải ảnh về và không kiểm tra gì"*
— và web-admin còn chưa có ô nhập nào. Giờ có kho vật thể thật (MinIO khi chạy local, S3 ở
production: cùng giao thức, khác endpoint, nên không có đường mã riêng cho môi trường dev).

| Quyết định | Vì sao |
| --- | --- |
| Trình duyệt `PUT` **thẳng** lên kho bằng URL đã ký | catalog là service đọc nhiều ghi ít. Một poster 4 MB đi qua gateway rồi qua JVM ấy là 4 MB trong heap của đúng service đang phục vụ trang danh sách công khai, cộng việc giữ luồng Tomcat suốt thời gian truyền trên một đường mạng ta không kiểm soát. |
| Hai địa chỉ, không phải một | Chữ ký S3 bao gồm cả tên host. Ký bằng `http://minio:9000` rồi đưa cho trình duyệt cho ra URL vừa không phân giải được vừa sai chữ ký. Nên `MEDIA_ENDPOINT` (nội bộ) tách hẳn `MEDIA_PUBLIC_URL` (trình duyệt). |
| Trần kích thước thi hành **sau** khi tải lên | URL ký sẵn kiểu `PUT` không chặn được kích thước — chỉ `POST` kèm policy mới làm được, và cách đó đổi hẳn hình dạng request phía trình duyệt. Nên luật chạy ở `PosterUrlPolicy` lúc lưu sự kiện, và vật thể quá cỡ bị **xoá** chứ không nằm lại làm rác. |
| Kiểm ở handler, không ở annotation | Ba đường tạo/sửa sự kiện phải qua cùng một luật; annotation trên request record chỉ canh được một đường. Và luật chính — "vật thể phải tồn tại, đúng kiểu, đúng cỡ" — là câu hỏi phải đi hỏi kho vật thể. |
| `null` khác chuỗi rỗng | `Event.rename` là phép patch: chỉ ghi đè khi khác `null`. Gộp hai thứ lại làm nút "xoá ảnh bìa" im lặng không có tác dụng. |

`poster_url` trước đây chỉ bị chặn độ dài. Giờ phải là `https` (ngoại lệ cho `http://localhost` của
kho nội bộ, nếu không tính năng này không thử được ở máy chưa có chứng chỉ), và URL ngoài bị chặn
hẳn khi `MEDIA_ALLOW_EXTERNAL_URLS=false` — mặc định ở production, vì mọi khách xem trang tải ảnh
thẳng từ host lạ đó và host ấy thấy được IP của từng người.

---

## Bản 7 — Trợ lý AI: nửa còn thiếu của kho tri thức, và ba lỗi ở lượt chat đầu tiên

Bản 6 dựng xong đường chuyển cuộc chat sang người thật. Bản này sửa những gì nó để lại, và phần
lớn chúng có chung một nguyên nhân: **ai-chatbox là service duy nhất không có integration test nào**,
nên mọi ràng buộc nằm trong SQL đều chưa từng được chạy.

### 1. Phiếu chuyển tiếp vỡ ở khoá ngoại ngay lượt chat ĐẦU TIÊN

`chat_handoffs.session_id` trỏ tới `chat_sessions`, mà dòng đó chỉ ra đời khi có tin nhắn đầu tiên.
Đường chuyển tiếp lại mở phiếu **trước** rồi mới ghi hội thoại — nên khách vừa mở trang và gõ ngay
"cho tôi gặp nhân viên" (hoặc mô hình gọi tool chuyển tiếp, hoặc hết vòng ReAct) nhận **500** thay
vì một phiếu. Với mô hình local 7B trên một kho tri thức rỗng thì nhánh "hết vòng" không hiếm chút
nào — nó là kết cục thường gặp.

Sửa bằng cách đảo thứ tự và gộp cả bốn lệnh ghi vào **một** transaction (`escalateWithTurn`). Thứ
tự ấy giờ là một phần của hợp đồng, viết rõ trong javadoc, và có ba test giữ nó.

### 2. Mở phiếu trên phiên của người khác

`POST /v1/chat/agent/sessions/{id}/handoff` không kiểm chủ sở hữu, trong khi hai đường **đọc** đã
kiểm từ đầu. Hậu quả không nhẹ: đoán ra một UUID phiên là tắt được trợ lý của người đó (phiếu mở là
công tắc tắt trợ lý), đẩy hội thoại của họ vào hàng đợi bàn hỗ trợ, và ghi `user_id` của kẻ gọi lên
phiếu của phiên người khác. Giờ kiểm ở `HandoffUseCase`, và trả cùng một mã lỗi cho "không có
phiên" lẫn "phiên của người khác" — phân biệt được nghĩa là dò ra được UUID nào có thật.

### 3. Kho tri thức không có đường ghi

| Điểm thiết kế | Nội dung |
| --- | --- |
| Vấn đề | Hai bảng tri thức có từ migration đầu, truy vấn đọc có, nhưng **không một lệnh INSERT nào** trong cả repo. Không phải "kho còn ít" mà là một tính năng chạy đúng và vô dụng: prompt hệ thống buộc trợ lý chỉ nói những gì có trong ngữ cảnh hoặc kết quả tool, nên nó còn làm được đúng một việc — tra đơn hàng. |
| Đường soạn | `/v1/support/knowledge/**`, dùng lại quyền `PLATFORM_SUPPORT_HANDLE`. Người soạn câu trả lời cho trợ lý và người trực bàn hỗ trợ là cùng một nhóm việc; tách quyền chỉ có nghĩa khi có hai nhóm người thật sự khác nhau. |
| Nhúng lúc GHI | Đó là lý do `embedDocument` tồn tại và tách khỏi `embedQuery` — nhà cung cấp phân biệt hai loại đầu vào, và khai sai không gây lỗi nào, chỉ làm chất lượng tìm kiếm tệ đi theo cách không truy nguyên được. |
| Endpoint thử | `GET .../preview?q=…` đi qua **đúng** đường agent đi và trả cả những đoạn bị ngưỡng loại, kèm cờ `used`. Không có nó thì một kho "trông đầy" mà mọi đoạn đều trên ngưỡng là thứ chỉ phát hiện được qua câu trả lời tệ với khách thật. |
| Hỏng thì báo ngay | Đường đọc của agent nuốt lỗi embedding (mất RAG không phải hỏng); đường ghi thì không — một đoạn không có vector là một đoạn không bao giờ tìm ra được. |

### 4. Ba chỗ trạng thái của phiếu

Nhận lại phiếu của **chính mình** (F5, bấm hai lần) từng trả 409 như thể người khác vừa nhận — màn
hình gỡ phiếu khỏi tay người đang trả lời nó. Trả lời vào phiếu **đã đóng** từng đi qua, dựng lại
đúng cảnh hai phía cùng nói mà cả tính năng này tồn tại để tránh. Đóng phiếu hai lần từng dịch
`resolved_at`, làm sai báo cáo "xử lý mất bao lâu". Cả ba giờ có mã lỗi riêng
(`HANDOFF_ALREADY_RESOLVED`) hoặc hành xử idempotent, và có test.

### 5. Deploy: service chưa từng có mặt ở production

`prod.yml` không có khối `ai-chatbox-service`, gateway không có `AICHATBOX_URL`, và — nặng nhất —
`postgres` không nhận `DB_PASSWORD_AI_CHATBOX` mà `initdb-prod` bắt buộc. Cái cuối không làm
ai-chatbox không lên, nó làm **cả stack** không lên, bằng một thông báo nằm trong log của container
`postgres`. Ollama giờ là một service có hồ sơ riêng (`ai-local` ở prod, `ai` ở dev): ~6GB không
nên là cái giá mặc định của `docker compose up`.

### 6. Integration test

25 test mới trên PostgreSQL + pgvector thật. Chúng kiểm đúng những thứ nằm trong SQL chứ không nằm
trong Java: khoá ngoại, unique index bộ phận, `UPDATE ... WHERE status = 'WAITING'`, cặp CHECK giữa
trạng thái và mốc thời gian, cờ `published`, và toán tử `<=>`. Mô hình là hàng giả — thứ cần kiểm
là vòng ReAct và những gì ghi xuống database, không phải khả năng gọi API có tính tiền.

---

## Bản 6 — Hình học mặt bằng, tra cứu vé, bàn hỗ trợ người thật

Bốn mảng, một nhịp phát hành. Điểm chung: cả bốn đều là thứ đã có backend nhưng thiếu đúng một
mảnh khiến nó chưa dùng được.

### 1. Hình học mặt bằng (catalog-service)

Trước bản này, một khu chỉ có `row_count × seats_per_row`, nên mọi sơ đồ đều là những khối chữ
nhật xếp dọc — đủ cho nhà hát, sai cho mọi khán phòng vây quanh sân khấu.

| Điểm thiết kế | Nội dung |
| --- | --- |
| Hai hình, không phải N | `GRID` (khối chữ nhật xoay được) và `ARC` (hàng là cung tròn đồng tâm). Sân khấu chữ U = một `ARC` ôm đầu + hai `GRID` xoay 90°. Hình thứ ba nghĩa là màn hình khai báo khu phải thành trình vẽ vector, và mã chỗ `A-3-12` mất nghĩa "hàng 3 ghế 12" mà soát vé đang đọc hàng ngày. |
| Đơn vị là "ghế" | Không pixel, không mét. Cùng sơ đồ được vẽ trên điện thoại 360px, màn quản trị 1600px và ảnh poster 2480px; con số duy nhất đúng ở cả ba chỗ là con số không mang đơn vị hiển thị. |
| Lưu công thức, không lưu kết quả | Khu cung 40×60 là 7 con số trong `venue_zones`, không phải 2.400 dòng. Toạ độ từng ghế tính lúc publish, đi theo `session.published`, nằm ở `seats.pos_x/pos_y` của inventory — nơi duy nhất thật sự đọc tới từng ghế. Cột ấy **đã có sẵn**; trước đây catalog chỉ điền chỉ số hàng/cột vào. |
| `NULL` = "chưa đặt" | Khác hẳn "đặt đúng chỗ mặc định": khu chưa đặt sẽ tự dịch xuống khi chèn thêm khu phía trên, khu đã đặt thì đứng yên. Vì vậy migration không có bước backfill và không sơ đồ nào đang chạy bị xê dịch. |
| Sửa hình học sau publish **không** di chuyển ghế đã dựng | Hành vi đúng: vé đã bán mang mã chỗ, và mã chỗ phải trỏ tới đúng chỗ ngồi hôm mở bán. Muốn đổi thì rút sự kiện xuống rồi publish lại. |

Khung concert của Tổng công ty mang theo hình học và sân khấu, nên áp khung vẫn chỉ là một phép
chép — đó là điều kiện để một khung sân khấu tròn không áp xuống thành mấy khối chữ nhật.

**Frontend giữ đúng nguyên tắc của plan bản 5**: không tự tính chỗ. `SeatMapCanvas` nhận toạ độ từ
backend (mặt bằng cho màn xem trước của ban tổ chức, sơ đồ tồn kho cho khách) và chỉ lo phần vẽ.
Ba việc thay cho một `<button>` mỗi ghế: mức chi tiết theo khu (chưa chọn khu thì chỉ vẽ đường
bao), cắt theo khung nhìn, và **một** listener ở gốc `<svg>`. Với khán phòng 20.000 chỗ thì số node
DOM giữ ở mức vài trăm thay vì 20.000.

### 2. Tra cứu vé của ban tổ chức (ticketing-service)

Lọc theo mã vé, mã ghế, tên khách, khu, trạng thái vào cửa và trạng thái thanh toán.

| Quyết định | Vì sao |
| --- | --- |
| Tên khách **chụp** vào `tickets.holder_name` lúc phát vé | Lọc theo một cột mình không có là điều không làm được. Hỏi identity lúc đọc chỉ trả lời được "tên của những vé tôi đã lấy ra", tức là phải lấy hết vé của cả sự kiện rồi mới lọc — và phân trang mất nghĩa. |
| Identity im lặng thì vẫn phát vé | Tên là dữ liệu tô điểm. Khách đã trả tiền; một service phụ không trả lời không phải lý do để họ không có vé. |
| `payment_status` là bản chụp, **tự vá lúc đọc** | Chưa có sự kiện `order.refunded` nào được phát. Đường đọc hỏi ordering trạng thái của đúng những đơn trên trang đang xem — một lời gọi cho tối đa 50 đơn — rồi ghi đè dòng nào lệch. Đánh đổi nói thẳng: bộ lọc chạy trên cột đã lưu, nên một vé vừa hoàn tiền mà chưa ai mở tới vẫn hiện là PAID; nó tự đúng ngay lần đầu có người nhìn vào. |
| Một index, và nó không đánh trên phần chữ | Bộ lọc luôn bắt đầu bằng tổ chức (thường thêm sự kiện) — đó là chỗ duy nhất có tính chọn lọc thật. Sau đó còn vài nghìn dòng, và `ILIKE` trên vài nghìn dòng rẻ hơn nuôi một index GIN trgm phải cập nhật ở mỗi lần phát vé, tức đúng lúc mở bán. |

### 3. Chuyển cuộc chat sang người thật (ai-chatbox-service)

| Điểm thiết kế | Nội dung |
| --- | --- |
| Phiếu mở là **công tắc tắt trợ lý** | Phần quan trọng nhất của cả tính năng. Thiếu nó thì trợ lý trả lời xen vào giữa, khách nhận hai câu trả lời khác nhau cho cùng một câu hỏi và không biết tin câu nào. |
| Ba đường kích hoạt | (a) nhận ý định trong câu chữ — không hỏi mô hình, không tốn tiền, không thể bị mô hình diễn giải thành "thử giúp thêm lần nữa"; (b) tool `escalateToHuman` cho trường hợp trợ lý tự thấy không xử lý được; (c) hết vòng ReAct mà chưa trả lời — đúng định nghĩa của "độ tin cậy thấp". |
| `AGENT` tách khỏi `ASSISTANT` | Khách phải biết mình đang nói với máy hay với người. Với mô hình thì hai vai gộp làm một ("phía hỗ trợ đã nói"); với giao diện thì không. |
| Một phiếu mở cho mỗi phiên | Unique index bộ phận, không phải đọc-rồi-ghi. Khách bấm "gặp nhân viên" ba lần mà ra ba phiếu nghĩa là ba người trực cùng trả lời một cuộc hội thoại. |
| Nhận phiếu là `UPDATE ... WHERE status = 'WAITING'` | Hai người trực bấm trong cùng một giây là chuyện bình thường giờ cao điểm. 409 ở đây là câu trả lời **đúng**, không phải lỗi. |
| Hỏi lại, không đẩy | Realtime-gateway phục vụ fan-out tồn kho, nơi một giây chậm là bán trùng ghế. Bàn hỗ trợ không có ràng buộc ấy — hỏi lại mỗi 5 giây rẻ hơn dựng thêm một kênh đẩy cùng phần dò kết nối lại. Đánh đổi có chủ đích. |

Quyền `PLATFORM_SUPPORT_HANDLE` ở **phạm vi nền tảng**: khách chat với nền tảng về đơn của chính
họ, và một cuộc chat có thể nhắc tới sự kiện của nhiều tổ chức. Hiện chỉ `SUPER_ADMIN` có — thêm
*quyền* thì không phải sửa ràng buộc CHECK của database hay realm Keycloak, còn thêm *vai trò* thì
phải. Khi vai trò trực hỗ trợ ra đời, nó chỉ cần khai thêm một dòng ở `Role`.

### 4. Chi phí mô hình: mặc định đảo sang chạy tại chỗ

`AI_PROVIDER=local` (Ollama) là mặc định mới; `anthropic` vẫn còn nguyên sau một biến môi trường.
Hai lý do: mỗi lượt chat tính tiền hai lần (nhúng + mô hình) nhân với số vòng ReAct, nên một mặc
định có tính tiền ghi mọi môi trường dev vào hoá đơn thật; và trước đây service **từ chối khởi
động** khi thiếu `ANTHROPIC_API_KEY`.

`bge-m3` được chọn vì nó sinh vector **1024 chiều** — đúng bằng `vector(1024)` đã khai cho
voyage-3.5. Trùng số chiều cho phép đổi nhà cung cấp mà không sửa schema, nhưng **không** làm hai
bên so sánh được: đổi nhà cung cấp vẫn phải nhúng lại toàn bộ kho tri thức.

### 5. Bộ lọc: đẩy xuống database chỗ cần, giữ ở client chỗ không cần

Một quy tắc duy nhất quyết định lọc ở đâu: **tập dữ liệu có nằm sẵn trong bộ nhớ của người đang
xem hay không.**

| Màn hình | Lọc ở đâu | Vì sao |
| --- | --- | --- |
| Danh sách sự kiện công khai | **Database** | Có bao nhiêu sự kiện đang bán là điều không kiểm soát được |
| Tra cứu vé của ban tổ chức | **Database** | Một sự kiện có hàng chục nghìn vé |
| Ví vé của khách | Client | Vài chục vé, đã tải về rồi |
| Đơn hàng của khách | Client | 50 đơn, đã tải về rồi |
| Địa điểm, thành viên | Client | Vài chục dòng, `GET` trả hết trong một lượt |

**`GET /v1/events` nhận thêm `from`/`to` và `minPrice`/`maxPrice`.** Đây là việc mà
`quick-filters.ts` đã ghi sẵn trong chính mã nguồn từ trước: hai bộ lọc thời gian và giá vốn chạy
tại chỗ trên **tối đa 60 sự kiện** lấy về, nên chúng chỉ đúng trong phạm vi 60 cái đó — và con số
tổng hiện trên màn hình cũng chỉ đếm trong phạm vi ấy. Giới hạn đó không còn.

Ba quyết định trong lần sửa này:

1. **Endpoint nhận KHOẢNG, không nhận tên lựa chọn** (`from=…` chứ không `when=weekend`). "Cuối
   tuần này" phụ thuộc hôm nay là thứ mấy **ở Việt Nam**, mà tiến trình backend chạy giờ UTC —
   07:00 giờ Việt Nam là 00:00 UTC, nên để backend tự giải nghĩa thì "hôm nay" nhảy sang hôm khác
   đúng vào buổi sáng. Frontend đã có phép tính ấy và nó đúng. Danh sách lựa chọn cũng là quyết
   định giao diện: thêm mốc "3 tháng tới" chỉ nên sửa một hằng số ở frontend.

2. **Lọc trên giá trị dẫn xuất, không trên bảng gốc.** `from`/`to` so với *suất kế tiếp* và
   `minPrice`/`maxPrice` so với *giá thấp nhất* — đúng hai con số hiện trên thẻ sự kiện. Lọc theo
   "có suất bất kỳ trong khoảng" sẽ trả về một sự kiện mà thẻ của nó hiện một ngày nằm ngoài
   khoảng vừa lọc, và người dùng đọc đó là lỗi. Vì hai giá trị ấy là subquery chứ không phải cột,
   câu lệnh dùng CTE: tính một lần rồi lọc bên ngoài, thay vì viết lại subquery lần thứ hai trong
   `WHERE`.

3. **Câu lấy trang và câu đếm dùng chung mệnh đề lọc và chung hàm dựng tham số.** Mười bốn tham số
   vị trí là chỗ dễ sai nhất, và sai kiểu ấy không gây lỗi — nó chỉ lặng lẽ lọc theo thành phố
   bằng giá trị của phân loại. Hai câu lọc khác nhau thì tổng số trang không khớp số dòng thật, và
   người dùng chỉ phát hiện ở trang cuối.

Phía client, `applyQuickFilters` và `needsLocalFiltering` bị **bỏ** cùng với giới hạn 60 sự kiện;
`timeRange`/`priceRange` ở lại vì chúng vẫn là chỗ dịch lựa chọn giao diện thành khoảng số.

Ví vé mặc định lọc **"Sắp diễn ra"**: ví mở ra mà trên cùng là concert năm ngoái thì việc đầu tiên
khách phải làm là cuộn qua chỗ mình không cần. Vé của sự kiện đã gỡ đăng bán (không tra được ngày)
**luôn được giữ lại** — giấu đi một cái vé khách đã trả tiền hỏng nặng hơn nhiều so với hiện thừa
một dòng.

Đơn hàng gộp sáu trạng thái thành **ba việc** (chờ thanh toán / đã thanh toán / đã đóng): khách
không phân biệt `EXPIRED` với `CANCELLED` — cả hai đều là "đơn này hỏng rồi". `MANUAL_REVIEW` nằm
cùng nhóm "đã thanh toán" vì tiền đã tới, việc còn lại là của nền tảng.

Mọi màn hình lọc tại chỗ đều phân biệt **"không có dữ liệu"** với **"bộ lọc đang giấu dữ liệu"**.
Gộp hai câu làm một sẽ nói với người vừa mua vé rằng họ chưa mua gì.

### 6. Ảnh vé — và vì sao mã QR không đổi

Poster in tên khách, khu, ghế, tên sự kiện ra **bằng chữ**; mã QR vẫn chỉ mang `jti` và `exp`
(H7 giữ nguyên). Ảnh vé bị đăng lên mạng xã hội là chuyện hàng ngày và mọi mã QR đều giải ra được
bằng một cái điện thoại — chữ in trên ảnh thì người đăng nhìn thấy và tự quyết định che đi, dữ
liệu giấu trong mã thì không. Nhãn ghế hiện trên máy soát vé là do **server trả về** sau khi kiểm
quyền nhân viên, nên nhét chúng vào mã cũng không làm cửa vào nhanh hơn một giây nào.

Dựng ở client, cùng lý do màn hình quản trị không có endpoint xuất file: chỗ ấy biết ngôn ngữ, múi
giờ và định dạng ngày mà người dùng đang xem.

---

## Bản 5 — Kế hoạch triển khai + hướng giao diện

| Tài liệu | Nội dung |
| --- | --- |
| [plan/README.md](plan/README.md) | Monorepo 11 service, 6 starter dùng chung và ranh giới cứng, RabbitMQ topology as code, cách chạy local với 4 deployable, branching, CI theo path filter |
| [plan/backend.md](plan/backend.md) | Khuôn service, cross-cutting, migration theo service, giai đoạn G0–G7, thuật toán hold có trần, test |
| [plan/frontend.md](plan/frontend.md) | 4 app, `packages/seatmap` dùng chung, trình thiết kế chỗ ngồi, `web-platform` mới |
| [ui-direction.md](ui-direction.md) | Hướng giao diện v2 — thay `docs/ui/design-direction.md` |

### Giao diện đổi hệ

Tham chiếu bố cục ticketbox.vn: lấy **cấu trúc thông tin** (header ưu tiên tìm kiếm, hero xoay vòng, chip thể loại, dải sự kiện cuộn ngang, thẻ 4 dòng, CTA dính đáy), **không** lấy logo, ảnh, câu chữ hay mã màu thương hiệu của họ.

| | v1 | v2 |
| --- | --- | --- |
| Nền customer/admin/platform | Tối `#0c1210` | **Sáng `#ffffff`** |
| Màu chính | Lime `#e8f56d` | **Đỏ ấm `#c02a2a`** + vàng kim `#f2b705` |
| Trang chủ | Brand hero, không card ở màn đầu | **Nội dung trước** |
| Scanner | Tối | **Vẫn tối** — dùng ngoài trời buổi tối, có lý do |

Acceptance criteria cũ của `C-HOME` (*"không card grid ở first viewport"*) không còn áp dụng. Bảng màu sơ đồ ghế phải làm lại vì trước đó thiết kế cho nền tối.

### Điểm đáng chú ý trong plan

1. **Ranh giới thư viện dùng chung** là chỗ dễ giết microservices nhất — 6 starter kỹ thuật được phép, entity/DTO/enum nghiệp vụ bị cấm. Kèm luật ArchUnit cấm import chéo context.
2. **Chạy local 11 JVM là bất khả thi** (6–8 GB RAM). Đây là lập luận thực dụng mạnh nhất cho lộ trình B.
3. **Ba spike bắt buộc ở G0** (hold, sổ cái, webhook) — không dồn rủi ro về cuối như v1.
4. **Nhả chỗ phải là một hàm duy nhất** dùng cho cả bốn đường, nếu không sẽ quên xoá `holder_user_id` ở một đường.
5. **`packages/seatmap` dùng chung** cho màn khách và màn xem trước của trình thiết kế — viết hai lần là cầm chắc lệch nhau.
6. **Xem trước sơ đồ gọi `GET /preview` của backend**, frontend không tự tính chỗ — nếu tính ở client sẽ lệch với kết quả materialize.

---

## Bản 4.2 — Trần mua vé cho tổ chức cấu hình

[ADR-1014](adr/ADR-1014-configurable-purchase-limits.md). Trần đổi từ hằng số cứng thành cấu hình ba tầng: **nền tảng (cứng) → tổ chức → suất diễn**, giá trị hiệu lực là `coalesce(suất, tổ chức, nền tảng)`. Bổ sung luôn trần còn treo: **cộng dồn mỗi tài khoản trên mỗi suất diễn**.

| Điểm thiết kế | Nội dung |
| --- | --- |
| Kiểm trần cứng lúc **ghi cấu hình** | Đường giữ chỗ chỉ đọc một cột, không tính `min()` ở 10k đồng thời |
| Sao trần vào `session_inventory` lúc materialize | Inventory không gọi Catalog khi giữ chỗ — event-carried state |
| `session_seats.holder_user_id` | Biến phép đếm xuyên ba service thành một câu đếm có index trong một bảng |
| `pg_advisory_xact_lock(user, session)` | Chống mở hai tab; không tranh chấp giữa các người dùng khác nhau |
| `purchaseAllowance` trong `GET /seats` | Khách biết trước còn mua được mấy vé, không bị từ chối ở bước cuối |

Mã lỗi mới: `PURCHASE_LIMIT_EXCEEDED`, `LIMIT_EXCEEDS_PLATFORM_CEILING`.

Cập nhật: `README.md` §4, `services.md` (catalog + inventory), `venue-seating-model.md` §10, `tactical-ddd.md`.

**Rủi ro phải test kỹ:** `holder_user_id` phải được xoá ở **cả bốn** đường nhả chỗ — hết hạn giữ chỗ, đơn hết hạn, huỷ đơn, hoàn tiền. Quên một đường là khách bị khoá hạn mức oan.

---

## Bản 4.1 — Chốt hai tham số

| Tham số | Chốt | Lý do |
| --- | --- | --- |
| `VENUE_SETUP_MINUTES` / `VENUE_TEARDOWN_MINUTES` | **240 / 180 phút** mặc định, superadmin chỉnh theo từng địa điểm | Quán cà phê cần 1–2 giờ, sân vận động cần 1–2 ngày — một con số cứng không dùng được |
| Trần giữ chỗ | **Ngồi ≤ 8, đứng ≤ 10, tổng ≤ 10** | Nhóm đi vé đứng đông hơn; tách đơn kéo theo hai lần chuyển khoản riêng, ma sát đủ để mất khách |

Ghi vào: [README §4 Hằng số nghiệp vụ](README.md#4-hằng-số-nghiệp-vụ) (mục mới), `venue-seating-model.md` §10 + §11, `tactical-ddd.md`, [ADR-1012](adr/ADR-1012-standing-admission-inventory.md). Thêm mã lỗi `TOO_MANY_SEATS`.

**Còn treo:** trần vé mỗi **tài khoản** trên mỗi suất diễn. Trần-mỗi-lần-giữ không cản được phe vé (đặt 13 đơn liên tiếp là xong); công cụ đúng là giới hạn tổng số vé một người mua được cho một suất. Hiện chưa có trong thiết kế.

---

## Bản 4 — Địa điểm riêng, ba kiểu concert, cảnh báo trùng lịch

Ba câu trả lời cho các việc còn treo ở bản 3:

| Câu hỏi treo | Chốt |
| --- | --- |
| Tổ chức có được tự tạo địa điểm riêng không? | **Có** — `scope = ORGANIZATION`, và **vẫn có zone `FIXED`** |
| Có cần vé đứng không? | **Có, cả ba kiểu** — toàn ghế ngồi, vừa ngồi vừa đứng, chỉ đứng |
| Trùng lịch địa điểm: cảnh báo hay chặn? | **Cảnh báo**, vì hệ thống không nắm dữ liệu thuê địa điểm |

### ADR mới

| ADR | Nội dung |
| --- | --- |
| [ADR-1012](adr/ADR-1012-standing-admission-inventory.md) | Vé đứng: đơn vị tồn kho ảo, cấp phát bằng `FOR UPDATE SKIP LOCKED`, **không** dùng Redis |
| [ADR-1013](adr/ADR-1013-venue-schedule-conflict-warning.md) | Trùng lịch: cảnh báo không chặn; quy tắc lộ thông tin; truy vấn xuyên tenant có kiểm soát |

### Thay đổi theo tài liệu

| Tài liệu | Thay đổi |
| --- | --- |
| `venue-seating-model.md` | §2 tách "sửa mặt bằng" khỏi "thiết kế chỗ ngồi"; §3 thêm `setup_minutes`/`teardown_minutes`; §4 thêm `admission_type` + index cấp phát; §7 viết lại (địa điểm riêng **có** zone `FIXED`); §8 mới — ma trận `kind` × `admission_type` + ba kiểu concert; §9 mới — tồn kho vé đứng; §10 API mở rộng; §11 mới — cảnh báo trùng lịch; §12 UI thêm 2 màn platform |
| `services.md` | catalog: API địa điểm riêng cho tổ chức, `promote`, `venue-conflicts`; inventory: hold nhận `standing[]`, thêm đường `SKIP LOCKED` |
| `context-map.md` | Thêm `AdmissionType`, đơn vị vé đứng, `VenueScheduleConflict`; ánh xạ 2 yêu cầu mới |
| `tactical-ddd.md` | `SeatHold` nhận cả chỗ ngồi lẫn chỗ đứng; `EventSeatingPlan` thêm bất biến về `admission_type` |
| `README.md` | Danh sách yêu cầu lên 8 mục; bảng quyền tách địa điểm dùng chung vs riêng; ADR-0008 ghi nhận ngoại lệ hẹp; việc chưa chốt còn 2 mục |

### Điểm thiết kế đáng chú ý

1. **Ranh giới "áp cứng" nằm ở thao tác, không ở quyền sở hữu.** Sửa mặt bằng (tạo `VenueLayoutVersion` mới) là quyền của chủ địa điểm; thiết kế chỗ ngồi cho sự kiện (`EventSeatingPlan`) không bao giờ sửa được zone `FIXED`. Nhờ vậy địa điểm riêng của tổ chức vẫn có ghế áp cứng mà bất biến vẫn giữ.
2. **Vé đứng không sinh cơ chế chống oversell thứ hai.** Đơn vị ảo + `SKIP LOCKED` dùng lại nguyên chốt chặn `seat_hold_items` unique index. Chỉ khác cách *chọn*, không khác cách *bảo vệ*.
3. **Ba kiểu concert là tổ hợp của một ma trận 2×2**, không cần khái niệm mới — tổ chức đổi kiểu chỉ bằng `EventSeatingPlan`, cùng một địa điểm.
4. **Cảnh báo trùng lịch phải chặn rò rỉ.** Suất diễn chưa công bố của tổ chức khác chỉ được báo bằng câu chung, không lộ tên sự kiện hay tên tổ chức — nếu không, tính năng này thành công cụ dò lịch đối thủ.
5. Phạm vi ảnh hưởng vẫn **chỉ Catalog + một đường cấp phát trong Inventory**. Ordering, Payment, Ledger, Payout, Ticketing không phân biệt vé ngồi với vé đứng.

---

## Bản 3 — Địa điểm dùng chung: khu vực cố định và khu vực linh hoạt

Yêu cầu: một địa điểm dùng chung cho nhiều tổ chức; mỗi tổ chức tự thiết kế khu vực ghế cho từng sự kiện; ngoài ra có khu vực ghế áp cứng không thay đổi được.

| Tài liệu | Thay đổi |
| --- | --- |
| [venue-seating-model.md](venue-seating-model.md) | **Mới.** Mô hình đầy đủ: `Venue` → `VenueLayoutVersion` → `VenueZone` (`FIXED` \| `FLEXIBLE`); `EventSeatingPlan` của tổ chức; `seat_code`; materialize; sửa sơ đồ sau khi bán vé; API và mã lỗi |
| [ADR-1011](adr/ADR-1011-shared-venue-fixed-and-flexible-zones.md) | **Mới.** Thay mô hình `venue_seat_maps`/`seat_map_seats` của v1 |
| `services.md` | catalog-service: API địa điểm tách làm hai nhóm (platform vs tổ chức); thêm nhóm `seating-plans`; preflight publish lên 4 mục; inventory giữ thêm `seat_code`/`zone_code` |
| `context-map.md` | Thêm mục thuật ngữ Catalog; ánh xạ 2 yêu cầu mới; sửa nghĩa của từ `Seat` |
| `tactical-ddd.md` | `Venue` đổi entity bên trong; thêm aggregate `EventSeatingPlan` |
| `README.md` | Danh sách yêu cầu; bảng quyền tách "tạo địa điểm + ghế áp cứng" khỏi "thiết kế khu vực linh hoạt"; G1 giãn từ 5–8 lên 5–9 tuần; thêm 3 việc chưa chốt |

### Điểm thiết kế đáng chú ý

1. **Ranh giới quyền:** kết cấu vật lý thuộc chủ địa điểm, quyết định thương mại thuộc ban tổ chức. Tổ chức không sửa được định nghĩa ghế cố định, nhưng toàn quyền quyết định có bán ghế đó không và bán giá nào.
2. **Ghim `layoutVersionId`:** cải tạo địa điểm không làm hỏng sự kiện đã bán vé — cùng nguyên tắc snapshot mà v1 dùng cho giá và tài khoản ngân hàng.
3. **`seat_code` thay `seat_map_seat_id`:** ghế nay đến từ hai nguồn, cần một khoá thống nhất. Bất biến chống oversell giữ nguyên hình dạng `UNIQUE (event_session_id, seat_code)`.
4. **Trạng thái `FROZEN`:** sau khi có ghế bán, chỉ còn thao tác cộng thêm và thao tác trên ghế chưa bán. Thêm khối ghế mới vẫn được (nhu cầu thật); bỏ ghế đã bán thì không.
5. **Phạm vi ảnh hưởng chỉ trong Catalog.** Inventory, Ordering, Payment, Ledger, Payout, Ticketing không đổi một dòng. Đây là bằng chứng ranh giới bounded context đặt đúng chỗ.

### Đề xuất thêm, chưa chốt

- **Địa điểm riêng của tổ chức** (`venues.scope = ORGANIZATION`, toàn bộ zone `FLEXIBLE`) để tránh nút thắt phải chờ superadmin cho mọi địa điểm nhỏ.
- **Khu vực vé đứng** (`admission_type = STANDING`), khuyến nghị cài bằng ghế ảo để không phải viết đường thứ hai cho cơ chế chống oversell.
- **Trùng lịch cùng địa điểm** giữa hai tổ chức: cảnh báo mềm, không chặn cứng.

---

## Bản 2 — Superadmin sở hữu tenancy và tài chính; chỉ RabbitMQ

Bốn yêu cầu mới từ chủ dự án:

1. Superadmin tạo ra các tổ chức.
2. Chỉ dùng RabbitMQ, không dùng Kafka.
3. Thanh toán tiền do superadmin quản lý.
4. Tổ chức chỉ biết số tiền và số vé đã bán.

### ADR

| ADR | Thay đổi |
| --- | --- |
| [ADR-1009](adr/ADR-1009-rabbitmq-only.md) | **Mới.** Chỉ RabbitMQ; `outbox` giữ vĩnh viễn và đóng vai trò nhật ký sự kiện. Thay thế ADR-1003 |
| [ADR-1010](adr/ADR-1010-superadmin-tenancy-and-finance-visibility.md) | **Mới.** Superadmin tạo tổ chức; tổ chức không chạm vào tiền. Thay thế ADR-1007 |
| [ADR-1003](adr/ADR-1003-kafka-event-backbone.md) | **Superseded.** Giữ lại để ghi lý do đã cân nhắc |
| [ADR-1007](adr/ADR-1007-self-service-organizations.md) | **Superseded.** Giữ lại để ghi lý do đã cân nhắc |

### Thay đổi theo tài liệu

| Tài liệu | Thay đổi |
| --- | --- |
| `README.md` | Danh sách yêu cầu; sơ đồ hệ thống (RabbitMQ, tách deployable `finance`, thêm `web-platform`); bảng "ai làm được gì"; ước lượng giảm từ 26–34 xuống 24–30 tuần; lộ trình G0–G7; danh sách việc chưa chốt |
| `context-map.md` | §4 thuật ngữ: bỏ `OrganizationVerification`, thêm `SUPER_ADMIN`, `SettlementReport`, `GrossSales`; §5 ánh xạ lại 5 yêu cầu |
| `services.md` | Thêm mục "Mô hình vai trò"; identity chỉ superadmin tạo org; catalog thêm thư viện địa điểm dùng chung và **bỏ mã lỗi `NO_ACTIVE_BANK_ACCOUNT`**; realtime-gateway dùng fanout exchange; payment/ledger/payout chuyển sang chỉ `SUPER_ADMIN`; thêm mục `reporting` với `sales-summary` |
| `tactical-ddd.md` | Messaging đổi sang RabbitMQ; exchange/routing key thay cho topic; JSON Schema trong repo thay cho schema registry; thêm luật consumer không phụ thuộc thứ tự |
| `custodial-funds.md` | §7 bỏ cổng `KYC_REQUIRED`, chi trả do superadmin khởi tạo; §8 viết lại thành "thẩm định khi onboarding" + bảng phạm vi tổ chức nhìn thấy; §10 cập nhật phòng thủ gian lận; §11 cập nhật checklist |
| `sagas.md` | Kafka → RabbitMQ trong mọi sơ đồ; thêm mục "không dựa vào thứ tự"; saga hoàn tiền và chi trả do superadmin khởi tạo; thêm bước gửi `settlement-report`; thêm test thứ tự đảo và test replay |

### Hệ quả dây chuyền đáng chú ý

1. **Mã lỗi `NO_ACTIVE_BANK_ACCOUNT` bị gỡ khỏi preflight publish.** Điều kiện này tồn tại vì tiền chảy vào tài khoản tổ chức. Giờ tiền chảy vào tài khoản ký quỹ nền tảng — luôn tồn tại. Preflight còn 3 mục. Màn `A-BANK` của v1 chuyển sang khu vực platform.
2. **Máy trạng thái KYC bị gỡ khỏi phần mềm.** `organizations.status` quay về `ACTIVE` | `SUSPENDED` như v1. Việc thẩm định là thao tác của con người trước khi tạo tổ chức.
3. **Bảng kê thanh toán (`settlement-report`) trở thành bắt buộc.** Vì tổ chức không tự kiểm chứng được số tiền thực nhận, đây là kênh đối chiếu duy nhất của họ.
4. **Test thứ tự đảo trở thành bắt buộc.** RabbitMQ không bảo đảm thứ tự — đây là bài test đặc thù mà Kafka sẽ không đòi hỏi.
5. **Cần app thứ tư: `web-platform`** cho superadmin (tạo tổ chức, sổ cái, đối soát, chi trả). v1 gộp phần platform vào `web-admin`; với ranh giới tài chính chặt như hiện tại, tách app riêng là hợp lý hơn — chưa chốt.

---

## Bản 1 — Microservices + DDD + Custodial funds

Ba yêu cầu ban đầu: microservices, kiến trúc DDD, nền tảng giữ tiền. Tạo ADR-1001…1008 và 6 tài liệu thiết kế. Xem lịch sử git.
