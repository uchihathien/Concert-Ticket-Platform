# Khoảng trống kiểm thử đã biết

Những gì chưa kiểm được, vì sao, và test nào cần viết tiếp. Cập nhật lần cuối cùng đợt bổ sung
test đầu tiên; số liệu thực tế ở [test-execution-report.md](test-execution-report.md).

## 1. Test đang ĐỎ có chủ đích

**Không còn.** Hai test từng đỏ (thiếu `Idempotency-Key`, route soát vé cũ) đã được sửa ở ứng dụng — xem §2.

## 2. Lỗi tìm thấy VÀ đã sửa trong đợt này

Ứng dụng được sửa, test giữ nguyên làm bằng chứng hồi quy. Mọi thay đổi nằm trong `src/main`
và tách khỏi thay đổi test.

| Lỗi | Sửa ở |
| --- | --- |
| Javadoc nói có filter `Idempotency-Key` chặn `POST` thiếu khoá nhưng **filter không tồn tại** — đặt đơn trùng chỉ dựa vào tra theo `holdId` và unique index | Hiện thực `IdempotencyFilter` trong `starter-idempotency` (bật theo route bằng `nexaticket.idempotency.required-paths`; áp cho `POST /v1/orders` và `POST /v1/sessions/*/holds`): thiếu / sai khoá → 400 `IDEMPOTENCY_KEY_REQUIRED`; gửi lại cùng khoá và nội dung → phát lại đúng câu trả lời 2xx đã lưu (`Idempotent-Replayed: true`), handler không chạy lần hai; cùng khoá khác nội dung → 409 `IDEMPOTENCY_KEY_REUSED`; đang chạy → 409 `REQUEST_IN_PROGRESS`; handler lỗi → bỏ chỗ giữ để thử lại; bản ghi dở quá 2 phút (tiến trình chết) được nhận lại. Khoá có phạm vi theo người dùng. Kiểm bằng `IdempotencyFilterTest` (11), `IdempotencyFilterIT` (4, PostgreSQL thật, gồm 4 luồng song song cùng khoá), `OrderIdempotencyIT` (5, qua chuỗi bảo mật + saga thật), `HoldControllerTest` |
| Route quét vé cũ `POST /v1/sessions/{id}/checkins` gắn `@RequiresPermission(CHECKIN_SCAN)` nhưng đường dẫn không có `organizationId` nên `PermissionGuard` từ chối **mọi** người (`ts-sdk.scanTicket` còn gọi route này) | Bỏ annotation; quyền `CHECKIN_SCAN` và việc gán sự kiện vẫn được kiểm trong `CheckinAccess.requireScanner` theo tổ chức của membership đang hoạt động. Thêm test: chưa được gán → 403 `NOT_YOUR_EVENT`, khách → 4xx |
| Giá catalog ≠ giá tồn kho (25.000đ niêm yết, đơn tính 2.500.000đ): sửa giá hạng vé khi sự kiện đã rút xuống rồi publish lại không đồng bộ giá sang tồn kho — khách thấy một giá, bị tính giá khác | `JdbcSessionMaterializer.syncUnsoldPrices` đồng bộ giá và tên hạng vé cho chỗ chưa bán (`AVAILABLE`, `BLOCKED`, cả đơn vị ảo vé đứng); chỗ đã bán / đang giữ giữ giá cũ. `MaterializeSessionIT.publish_lai_thi_dong_bo_gia_cho_chua_ban`. Sự kiện đã lệch sẵn: `resync-inventory` |
| **Bán trùng ghế**: bỏ giữ chỗ chạy chồng lên đặt chỗ — bên đến sau ghi đè trạng thái giữ chỗ vô điều kiện, ghế RESERVED bị trả về kho và bán lần hai (tái hiện bằng `ReleaseReserveRaceIT` trên PostgreSQL thật) | `SeatHoldRepository.updateStatus` thành compare-and-set (`WHERE status='ACTIVE'`, trả boolean); `ReleaseHoldHandler`, `ReserveSeatsHandler`, `ExpireHoldsJob` xử lý bên thua |
| Lối tắt `holdId` của `PlaceOrderHandler` trả đơn **của người khác** kèm QR và link thanh toán | kiểm chủ đơn, trả 404 |
| Giảm giá làm tròn dồn vào dòng cuối; dòng cuối là vé 0đ thì vượt đơn giá, `OrderItem` từ chối, khách nhận "thử lại" mãi | dồn phần dư vào dòng giá cao nhất |
| `Order.close()` nhận PAID/REFUNDED/MANUAL_REVIEW làm lý do — đường vào PAID không qua `markPaid`, không có `paidAt` | chỉ EXPIRED / CANCELLED |
| `limit`/`offset` âm xuống tới SQL → 500 | kẹp ở `OrderController`, `MyTicketsController` |
| `ORG_ADMIN` mời email mình kiểm soát làm `ORG_OWNER`, và hạ / gỡ được `ORG_OWNER` khác (leo thang đặc quyền) | `InviteMemberHandler`, `ManageMembersHandler` |
| Đổi vai trò sang `SUPER_ADMIN`/`CUSTOMER` lọt qua aggregate rồi nổ CHECK của DB → 500 | `Organization.changeRole`, handlers → 400 |
| Hai lệnh sửa thành viên chạy chồng: lệnh sau xoá mất người vừa nhận lời mời / chèn lại người vừa bị gỡ | khoá dòng tổ chức (`FOR UPDATE`) trong giao dịch ghi (`OrganizationConcurrencyIT`) |
| Huỷ lời mời nhân viên xoá tài khoản Keycloak rồi mới phát hiện tổ chức bị khoá — rollback DB nhưng tài khoản đã mất | kiểm tổ chức trước khi gọi hệ thống ngoài |
| Kích hoạt tổ chức đang hoạt động ghi dòng audit sai ("trước: SUSPENDED") | no-op idempotent |
| Slug sai định dạng / tên 64 ký tự trùng → 500 | 400 / `SLUG_ALREADY_TAKEN` |
| Email có `+` gửi thô sang Keycloak (đọc thành dấu cách) | biến mẫu URI |
| `PlatformUserController.disable` thiếu `@Valid` | thêm |
| `ConsumedEvent.uuid` ném `IllegalArgumentException` với UUID hỏng → message giao lại **mãi**, chặn đầu hàng đợi (inventory, ticketing, ledger) | trả `null`, consumer vốn đã ack và log |
| `SessionPublishedListener` ném NPE với payload thiếu `limits` → giao lại mãi | ack + log lỗi cho payload sai khuôn |
| `OrderPaidListener` (ledger) ném với tổng âm → giao lại mãi; hoa hồng 100% tạo định khoản 0đ làm hỏng bút toán | ack số tiền vô lý; bỏ dòng 0đ |
| `PayosHttpGateway`: 5xx kèm JSON bị coi là `Rejected` (502) thay vì `Unavailable` (503, thử lại) | phân loại theo mã HTTP trước |
| `PayoutGate`: hai tên không có chữ Latin cùng chuẩn hoá thành `""` và "khớp" | từ chối tên rỗng sau chuẩn hoá |
| `GlobalExceptionHandler`: UUID sai trong đường dẫn, thiếu tham số, sai method, sai content-type, `@Size` trên tham số → **500** + stack trace trong log ERROR; thân lỗi ghi status 400 trong khi HTTP là 409 | 400/405/415/406 đúng, `meta.fields` cho validation tham số, `status` trong thân khớp HTTP |
| `CorrelationIdFilter`: CR/LF trong `X-Correlation-Id` đi vào log và header phản hồi (CWE-117) | whitelist `[A-Za-z0-9._:-]{1,64}` |
| `/v1/admin/organizations/{id}/sales` không kiểm quyền: nhân viên soát vé đọc được doanh thu cả tổ chức | `@RequiresPermission(ORG_METRICS_VIEW)` |
| `GET /v1/chat/agent/sessions/{id}/messages` của người khác → 500 | 404 |
| Chủ đề email chứa CR/LF → chèn header SMTP | thay ký tự điều khiển bằng dấu cách |
| Catalog: giá vé thiếu → vé 0đ, `1.5` bị cắt thành `1`; `PATCH` suất không `@Valid`; `page=Integer.MAX_VALUE` tràn thành offset âm; khu ngồi 50.000×50.000 tràn int thành sức chứa âm; cửa bán đóng trước khi mở và nhiều luật domain → 500; hình `TABLE` không tạo được qua HTTP; `PricingQuery` tạm tính âm → 500 | `@NotNull Long`, `accept-float-as-int: false`, `@Valid`, kẹp offset, kiểm tràn, `InputRules` + `DomainRuleExceptionAdvice` → 400, thêm `TABLE` |
| `api-gateway`: không có route `/v1/platform/payouts/**` (biến `PAYOUT_URL` đã khai nhưng không dùng) | thêm route `payout` |
| Frontend: `csv.ts` không chặn công thức bắt đầu bằng TAB/CR (CSV injection); `event-report.ts` xuất giờ theo múi giờ máy thay vì giờ Việt Nam | sửa trong `packages/ui`, `apps/web-admin` |

## 3. Lỗi đã ghi nhận, chưa có test đỏ riêng

- **`ConfirmPaymentHandler` không kiểm số tiền / tiền tệ**: nó chỉ nhận `orderId`. Việc kiểm phải
  nằm ở payment-service (đã kiểm ở `ConfirmTransferHandler`: thiếu tiền → `AMOUNT_MISMATCH`). Tiền tệ
  không được kiểm ở đâu cả — nằm trong payload đã ký và payOS chỉ dùng VND.
- **Trạng thái `REFUNDED` không có đường vào** trong mã: hoàn tiền theo [refunds.md](../04-operations/refunds.md)
  chưa được hiện thực.
- **Bù trừ saga khi payment/inventory trả 404** có thể để saga ở `COMPENSATION_PENDING` mãi; phụ
  thuộc việc hai service kia có idempotent khi huỷ cái không tồn tại hay không.
- **Catalog**: không kiểm `endsAt > startsAt`; controller gọi `orElseThrow()` sau khi ghi (race → 500).
- **Frontend**: `ts-sdk/src/http/client.ts` ném `SyntaxError` trần khi phản hồi 200 không phải JSON
  (nhánh lỗi thì bọc đúng `ApiError`); trang chi tiết sự kiện với slug lạ trả HTTP 200 kèm giao diện
  404 (soft 404, có `noindex`).
- **`NotificationIT.mot_thu_hong_khong_chan_ca_lo` chập chờn** (có sẵn từ trước): job gửi thư
  `@Scheduled` mỗi 10 giây có thể chạy chen giữa `queue.handle` và `dispatch.runOnce()` của test
  rồi lấy mất thư. Đỏ 1 lần trong một lượt chạy đầy đủ, xanh khi chạy lại và khi chạy trên nhánh gốc.
  Cách sửa: tắt worker trong IT như `OrderingTestBase` đã làm (`workers.enabled=false`).

## 4. Chưa kiểm được ở mức nào

| Khoảng trống | Lý do | Test cần viết tiếp |
| --- | --- | --- |
| Mobile (4 app React Native) | Ngoài phạm vi đợt này; có thay đổi chưa commit của người dùng ở `apps/mobile-*` | Jest + React Native Testing Library; Maestro/Detox cho E2E |
| Đăng nhập Keycloak thật, đăng ký, Google IdP | Cần Keycloak + realm thật; E2E dùng nhà phát hành OIDC giả (Authorization Code + PKCE) | E2E nightly với `deploy/compose/infra.yml` |
| Trang thanh toán payOS | payOS **không có sandbox** (README gốc). Luồng được kiểm bằng `FakePayos` và `simulate-transfer` | Không thể tự động hoá an toàn; kiểm tay với tài khoản thật số tiền nhỏ |
| Hết hạn / làm mới token giữa phiên | Token giả sống 300 giây | Test với đồng hồ giả + Keycloak thật |
| Cập nhật chỗ ngồi realtime ở trình duyệt | Cần `realtime-gateway` + RabbitMQ trong E2E | E2E có hạ tầng đầy đủ |
| Sơ đồ chỗ ngồi vẽ trên trang đặt vé | Fixture cố ý không có floor plan | Thêm fixture floor plan và kiểm chọn ghế bằng chuột |
| payOS **thật** (trang thanh toán, webhook thật) | payOS không có sandbox; `checkout.js` đã chạy với payOS giả (`load-tests/fake-payos`), xem [load-test-report.md](../03-seat-checkout/load-test-report.md) | Kiểm tay một lần với số tiền nhỏ trước khi lên production — không tự động hoá an toàn được |
| Đạt 10.000 VU / p95 hold ≤ 300 ms ([load-test-plan.md](../03-seat-checkout/load-test-plan.md)) | Như trên | Gatling/k6 phân tán |
| Kill Redis giữa lúc giữ chỗ → 503 retryable, worker khởi động lại → hết hạn bù | Cần phá hạ tầng có chủ đích | IT với Toxiproxy / container dừng giữa chừng |
| Webhook payOS đến cùng lúc `ExpireOrdersJob` đóng đơn | Cuộc đua giữa hai service | IT hai service hoặc test đồng thời ở `ordering` với hàng giả điều khiển được |
| Tải thực tế của JDBC repository (truy vấn thống kê) | Chỉ kiểm đúng/sai qua IT có sẵn | EXPLAIN + dữ liệu lớn |
| Tệp tải lên (poster, ảnh sơ đồ) | Đã kiểm content-type, kích thước, đuôi và không phục vụ SVG/HTML; chưa kiểm quét mã độc | Tích hợp ClamAV nếu cần |
| Điều kiện `@Conditional` của `OutboxAutoConfiguration`, `IdempotencyAutoConfiguration` | Chỉ là wiring | `ApplicationContextRunner` |

## 5. Coverage chưa đạt mục tiêu

Mục tiêu: ≥ 80% statement và ≥ 80% branch cho logic lõi. Xem số liệu thật và danh sách lớp thiếu nhiều
dòng nhất ở [test-execution-report.md](test-execution-report.md) §4. Phần thấp nhất là
`ai-chatbox-service` (adapter LLM Anthropic/Ollama/Voyage cần mạng thật, `ToolDispatcher`) và các
adapter HTTP / repository JDBC thống kê của `catalog`, `ordering`, `ticketing`.

## 6. Rủi ro vận hành khi chạy test

- **RAM**: máy dev 16 GB, Docker Desktop (WSL) chiếm ~6 GB; chạy IT song song nhiều service sẽ hết bộ
  nhớ. Chạy tuần tự, `MAVEN_OPTS=-Xmx512m` nếu cần. Xem thêm ghi chú về container `withReuse`.
- **Container tái dùng** (`withReuse(true)`): dữ liệu cũ còn lại giữa các lần chạy; mọi fixture mới
  phải duy nhất theo từng lần chạy.
- **`~/.m2` cũ**: `spring-boot:run` và các module phụ thuộc đọc jar trong `~/.m2`; sửa `platform/*` mà
  không `install` thì service vẫn chạy mã cũ (đã gây nhầm lẫn khi một process khác ghi đè
  `starter-web` bằng bản cũ trong lúc viết test).

## 7. Việc còn lại của filter Idempotency-Key

- **Dọn bản ghi cũ**: bảng `idempotency_records` chưa có job xoá (ADR-0007 nói giữ ≥ 24 giờ); chưa có
  `@Scheduled` nào. Bảng lớn dần — thêm job xoá bản ghi `created_at < now() - 24h` (chỉ số
  `idx_idempotency_cleanup` đã có sẵn).
- **Chỉ phát lại 2xx**: 4xx/5xx không được lưu, nên client gửi lại cùng khoá sau một lỗi nghiệp vụ được
  xử lý lại từ đầu (chủ đích: phát lại mãi một "hết ghế" là sai khi ghế vừa được nhả).
- **Bản ghi dở bị nhận lại sau 2 phút** (`nexaticket.idempotency.stale-after`). Một request thật sự chạy lâu
  hơn mức đó có thể bị chạy lần hai; đã có lưới thứ hai (tra theo `holdId`) và thứ ba (unique `orders.hold_id`).
- **Mở rộng**: route khác muốn bảo vệ chỉ cần thêm vào `required-paths` của service đó.

## 8. Phát hiện từ lần chạy k6 đầu tiên (chưa sửa)

Chi tiết số liệu ở [load-test-report.md](../03-seat-checkout/load-test-report.md).

- **Sơ đồ ghế không được nén**: `GET /v1/sessions/{id}/seats` của suất 16.796 ghế trả 5,3 MB và vẫn
  5,3 MB khi gửi `Accept-Encoding: gzip`.
- **Hết bộ nhớ ở heap 384 MB**: 50 request đồng thời vào sơ đồ ghế lớn làm inventory ném
  `OutOfMemoryError`; 384 MB là `JVM_OPTS` của `scripts/dev-up.sh --jar` / `--demo`.
- **Độ trễ vượt ngưỡng đã đặt** ở cả `seat_map`, `events_list`, `event_detail` (trên một laptop dùng
  chung với k6 và toàn bộ hạ tầng; số tuyệt đối bi quan hơn thực tế).
