# Ca kiểm thử chức năng

Các ca cốt lõi theo nghiệp vụ, kèm điều kiện, bước, kết quả mong đợi, độ ưu tiên và **test tự động
thực hiện nó**. Đây là mục lục để đọc hiểu; danh sách đầy đủ từng test nằm trong mã (tên test là câu
tiếng Việt mô tả hành vi). Ưu tiên: **P0** = bất biến không đàm phán / mất tiền / lộ dữ liệu, **P1**
= luồng chính, **P2** = biên và lỗi nhập liệu.

Ký hiệu: *U* unit, *S* API slice (MockMvc + bộ lọc bảo mật thật), *I* integration (PostgreSQL thật),
*E* E2E Playwright, *K* k6.

## A. Tồn kho và giữ chỗ

| ID | Ca | Điều kiện → Bước | Mong đợi | Ưu | Test |
| --- | --- | --- | --- | --- | --- |
| INV-01 | Nhiều người tranh cùng một ghế | N luồng cùng `POST /holds` cho một ghế | Đúng 1 thành công (201), còn lại 409; không ghế nào có hai hold ACTIVE | P0 | I `SeatHoldConcurrencyIT`; K `hold-contention.js` |
| INV-02 | Backstop chống bán trùng ở DB | Bỏ qua gate Redis, ghi trực tiếp hai hold cùng ghế | Unique index `uq_hold_item_active` từ chối | P0 | I `OversellBackstopIT` |
| INV-03 | Bỏ giữ chỗ chạy chồng với đặt chỗ | Release và Reserve cùng lúc cho một hold | Đúng một bên thắng; ghế RESERVED không bao giờ về AVAILABLE | P0 | I `ReleaseReserveRaceIT`; U `ReleaseHoldHandlerTest`, `ReserveSeatsHandlerTest` |
| INV-04 | Vượt hạn mức mua | Người dùng xin quá `maxSeatedPerHold` / `maxTicketsPerCustomer` | `HOLD_LIMIT_EXCEEDED` (400) hoặc `CUSTOMER_LIMIT_EXCEEDED` (409), không giữ gì | P1 | U `PurchaseLimitsTest`, `PlaceHoldHandlerTest` |
| INV-05 | Số lượng sai | `quantity` ≤ 0, thiếu zone, trùng zone | 400; trùng zone được gộp | P2 | U `HoldRequestTest`; S `HoldControllerTest` |
| INV-06 | Hết hạn giữ chỗ | Hold quá `expiresAt` | Worker nhả ghế đúng một lần, bump version, xoá khoá Redis | P0 | U `ExpireHoldsJobTest`; I `HoldLifecycleIT` |
| INV-07 | Redis gate chết | Gate ném lỗi | 503 retryable, không giữ chỉ bằng DB | P1 | U `PlaceHoldHandlerTest`, `RedisAvailabilityGateTest` |
| INV-08 | Người khác bỏ hold của mình | `DELETE /holds/{id}` bởi user khác | 404 (không xác nhận hold có thật) | P0 | U `ReleaseHoldHandlerTest`; S `HoldControllerTest` |
| INV-09 | Message hỏng không làm kẹt hàng đợi | `order.paid` có `orderId` không phải UUID; `session.published` thiếu `limits` | Ack + log, không ném | P0 | U `OrderListenersTest`, `SessionPublishedListenerTest` |

## B. Đặt đơn và thanh toán

| ID | Ca | Điều kiện → Bước | Mong đợi | Ưu | Test |
| --- | --- | --- | --- | --- | --- |
| ORD-01 | Giá do server tính | Request chỉ có `holdId` (+ mã giảm giá) | Tổng = Σ giá ghế từ Catalog − giảm giá; client không gửi được giá | P0 | U `PlaceOrderHandlerTest` |
| ORD-02 | Hoa hồng theo dòng | 3 vé 333.333đ, 5% | 49.998đ (không phải 49.999đ); sổ cái cân | P0 | U `OrderTest`, `OrderInvariantsTest` |
| ORD-03 | Giảm giá chia dòng | Giảm 100đ cho vé 1.000/2.000/0đ | Tổng dòng = giảm giá; không dòng nào giảm quá giá; vé 0đ không nhận phần dư | P0 | U `PlaceOrderHandlerTest` |
| ORD-04 | Bấm "Thanh toán" hai lần | Cùng `holdId` hai lần | Trả lại đúng đơn cũ, không mở đơn thứ hai | P0 | U `PlaceOrderHandlerTest`; I `CheckoutSagaIT`; K `checkout.js`; E `checkout.spec` |
| ORD-05 | Dùng `holdId` của người khác | User B gửi `holdId` của A | 404, không lộ QR / link thanh toán của A | P0 | U `PlaceOrderHandlerTest.hold_cua_nguoi_khac_khong_tra_don` |
| ORD-06 | Inventory / payment hỏng giữa saga | Một bước ném lỗi | Bù trừ ngược thứ tự; bù trừ hỏng → `COMPENSATION_PENDING`, job quét thử lại | P0 | U `PlaceOrderHandlerTest`, `CompensationSweeperTest`; I `CheckoutSagaIT` |
| ORD-07 | Máy trạng thái đơn | Mọi `OrderStatus` × mọi chuyển | Chỉ các chuyển hợp lệ; PAID không có đường vào ngoài `markPaid` | P0 | U `OrderStateTransitionTest` |
| ORD-08 | Xác nhận thanh toán lần hai | `markPaid` hai lần | `false`, `paidAt` không đổi | P0 | U `OrderTest`, `ConfirmPaymentHandlerTest` |
| ORD-09 | Tiền vào muộn sau khi đơn đã đóng | Webhook tới sau `ExpireOrdersJob` | `MANUAL_REVIEW`, 200 (không 500), không phát vé | P0 | U `ConfirmPaymentHandlerTest`; I `OrderLifecycleIT` |
| ORD-10 | Xem / huỷ đơn của người khác | `GET`/`POST cancel` đơn người khác | 404 | P0 | U `OrderQueriesTest`, `CloseOrderFlowTest`; S `OrderControllerTest` |
| ORD-11 | Phân trang | `limit`/`offset` âm, quá lớn, không phải số | Kẹp về [1,100]/≥0; không phải số → 400 | P2 | S `OrderControllerTest` |
| PAY-01 | Chữ ký webhook sai | Sửa một byte của payload đã ký | Bị từ chối, không xác nhận gì | P0 | U `PayosSignatureEdgeCaseTest`; S `PayosWebhookControllerWebTest` |
| PAY-02 | Webhook giao lại | Cùng giao dịch hai lần | `DUPLICATE`, một bộ vé | P0 | U `HandlePayosWebhookHandlerTest`; I `PayosPaymentFlowIT`, `WebhookEdgeCasesIT` |
| PAY-03 | Số tiền lệch | Thiếu / thừa tiền | Thiếu → `AMOUNT_MISMATCH`, đơn vẫn chờ; không đánh dấu đã trả | P0 | U `ConfirmTransferHandlerTest` |
| PAY-04 | payOS 5xx | Gateway trả 503 kèm JSON | `Unavailable` (503, thử lại), không `Rejected` | P1 | U `PayosHttpGatewayTest` |
| PAY-05 | `simulate-transfer` ở production | `PAYMENT_SANDBOX=false` | Không thể gọi | P0 | U `SimulateTransferHandlerTest`, `PaymentPropertiesTest`; S `InternalPaymentControllerWebTest` |
| PAY-06 | `/internal/**` không secret | Gọi không có `X-Internal-Token` | 401/403 | P0 | S `InternalPaymentControllerWebTest`, `InternalOrderControllerTest` |
| LED-01 | Sổ cái kép cân | Mỗi bút toán Nợ = Có; không dòng 0đ | Cân; hoa hồng 0% và 100% đều ghi được | P0 | U `LedgerModelEdgeCaseTest`, `RecordPaymentHandlerTest`; I `LedgerInvariantIT` |
| LED-02 | Sổ cái chỉ thêm, không sửa | UPDATE/DELETE bút toán | DB từ chối | P0 | I `LedgerAppendOnlyIT` |
| LED-03 | `order.paid` giao lại | Cùng message hai lần | Ghi một bút toán | P0 | U `OrderPaidListenerTest`; I `OrderPaidListenerIT` |
| OUT-01 | Chi trả bốn mắt | Người tạo tự duyệt | Bị chặn | P0 | I `FourEyesIT`; U `PayoutHandlersTest` |

## C. Vé và soát vé

| ID | Ca | Điều kiện → Bước | Mong đợi | Ưu | Test |
| --- | --- | --- | --- | --- | --- |
| TIC-01 | Phát vé idempotent | `order.paid` giao lại | Một bộ vé | P0 | U `IssueTicketsTest`, `OrderPaidListenerTest`; I `IssueOnOrderPaidIT` |
| TIC-02 | QR giả / bị sửa | Đổi payload, sai khoá, sai chữ ký | `INVALID_TOKEN` | P0 | U `TicketSigningTest`; I `QrTokenIT` |
| TIC-03 | QR không chứa PII | Giải mã payload | Chỉ có `jti` và mã vé | P0 | U `TicketSigningTest` |
| TIC-04 | Quét hai lần | Vé đã vào, quét Vào lần nữa | `ALREADY_CHECKED_IN` kèm giờ và cổng | P0 | U `CheckInHandlerTest`, `TicketLifecycleTest`; I `TicketCheckinIT` |
| TIC-05 | Ra rồi vào lại | Quét Ra rồi Vào | Theo luật vào–ra–vào lại của sự kiện | P1 | U `CheckInHandlerTest` |
| TIC-06 | Sai suất / chưa mở cổng / đã kết thúc | Quét ngoài khung giờ | `WRONG_SESSION` / `NOT_OPEN_YET` / `SESSION_ENDED` | P1 | U `CheckInHandlerTest` |
| TIC-07 | Nhân viên không được gán sự kiện | Staff quét suất không thuộc mình | Từ chối | P0 | U `CheckinAccessTest`; S `CheckinControllerWebTest` |
| TIC-08 | Ghi đè thủ công | `override=true` | Chỉ người quản lý sự kiện, bắt buộc có lý do | P1 | U `CheckInHandlerTest` |
| TIC-09 | Xem vé của người khác | `GET /orders/{id}/tickets` của người khác | 404 | P0 | U `TicketingQueriesTest`; S `TicketReadControllersWebTest` |

## D. Danh tính, tenant, phân quyền

| ID | Ca | Điều kiện → Bước | Mong đợi | Ưu | Test |
| --- | --- | --- | --- | --- | --- |
| IAM-01 | Không token | Gọi mọi endpoint bảo vệ | 401 | P0 | S mọi `*Controller*Test`; U `GatewaySecurityTest` |
| IAM-02 | Sai tenant | Thành viên tổ chức A gọi `/organizations/B/...` | 404/403, không lộ dữ liệu | P0 | U `TenantFilterTest`; I `TenantIsolationIT` |
| IAM-03 | Không tin `organization_id` của client | Gửi `organizationId` trong thân | Bị bỏ qua; lấy từ membership | P0 | U `TenantFilterTest`, `CheckinControllerWebTest` |
| IAM-04 | Đi vòng bằng đường dẫn mã hoá | `%2e%2e`, dot-segment | Gateway/Spring Security chặn | P0 | U `GatewaySecurityTest`; I `TenantIsolationIT` |
| IAM-05 | Admin phong / hạ / gỡ Owner | `ORG_ADMIN` mời, đổi vai trò, gỡ `ORG_OWNER` | 403 | P0 | U `InviteMemberHandlerTest`, `ManageMembersHandlerTest` |
| IAM-06 | Gỡ chủ sở hữu cuối cùng | Một Owner duy nhất | `LAST_OWNER` | P0 | U `ManageMembersHandlerTest`; I `MemberManagementIT` |
| IAM-07 | Vai trò ngoài phạm vi tổ chức | `SUPER_ADMIN` / `CUSTOMER` | 400 | P1 | U `ManageMembersHandlerTest`, `OrganizationMembershipRulesTest` |
| IAM-08 | Nhân viên soát vé xem doanh thu | CHECKIN_STAFF gọi `/sales` | 403 | P0 | S `SalesDashboardControllerTest` |
| IAM-09 | `/internal/**` ra internet | Gọi qua gateway | Không có route (404/401) | P0 | U `GatewayRouteTableTest`, `GatewaySecurityTest` |
| IAM-10 | Thu hồi phiên | Admin thu hồi phiên thành viên | Token cũ vô hiệu | P1 | I `SessionRevocationIT` |
| IAM-11 | Hai lệnh sửa thành viên chạy chồng | Gỡ A trong khi B nhận lời mời | Không ai bị xoá / hồi sinh nhầm | P0 | I `OrganizationConcurrencyIT` |

## E. Catalog và lịch bán

| ID | Ca | Điều kiện → Bước | Mong đợi | Ưu | Test |
| --- | --- | --- | --- | --- | --- |
| CAT-01 | Chỉ sự kiện đã publish hiện công khai | Draft / unpublished / cancelled | 404 ở `/v1/events/{slug}`; không có trong danh sách | P0 | U `EventLifecycleTest`; S `PublicCatalogControllerTest` |
| CAT-02 | Publish thiếu điều kiện | Không có suất / hạng vé / giá | Preflight từ chối | P1 | U `EventLifecycleTest`, `EventPreflightTest` |
| CAT-03 | Cửa bán | Mở sau khi suất diễn xong; đóng trước khi mở | 400 / preflight `INVALID_SALES_WINDOW` | P1 | U `SessionAndTicketTypeHandlersTest`, `EventLifecycleTest` |
| CAT-04 | Giá vé | Thiếu, âm, `1.5`, `1e30`, tràn long | 400 (không bao giờ đọc thành số khác) | P0 | S `AdminCatalogControllerTest` |
| CAT-05 | Phân trang công khai | `size` 0/1000, `page` âm / `Integer.MAX_VALUE` | Kẹp [1,60]; offset không âm | P2 | S `PublicCatalogControllerTest` |
| CAT-06 | Tải poster | Đuôi/ content-type lạ, SVG, HTML, quá lớn | Từ chối; không bao giờ phục vụ SVG/HTML | P0 | U `MediaUseCasesTest`, `PosterUrlPolicyTest` |
| CAT-07 | Khu ngồi khổng lồ | 50.000 × 50.000 | Từ chối, không tràn int | P1 | U `VenueAndTemplateRulesTest` |
| CAT-08 | Khung sơ đồ của nền tảng | Tổ chức sửa / xoá khung; không phải superadmin | 403 / `VENUE_LAYOUT_LOCKED` | P1 | S `PlatformTemplateControllerTest`, `AdminRolesTest` |

## F. Lớp chung

| ID | Ca | Mong đợi | Ưu | Test |
| --- | --- | --- | --- | --- |
| COM-01 | UUID sai trong đường dẫn / thiếu tham số | 400 RFC 7807, không 500, không stack trace | P1 | U `GlobalExceptionHandlerWebTest` |
| COM-02 | Sai method / content-type | 405 / 415 | P2 | U `GlobalExceptionHandlerWebTest` |
| COM-03 | `X-Correlation-Id` chứa CR/LF | Bỏ, sinh mã mới | P0 | U `CorrelationIdFilterTest` |
| COM-04 | Chủ đề email chứa CR/LF | Không chèn được header SMTP | P0 | U `NotificationModelTest` |
| COM-05 | Số tiền VND | Không dùng số thực; làm tròn xuống đúng quy tắc | P0 | U `MoneyTest`, `MoneyEdgeCaseTest` |
| COM-06 | Ranh giới kiến trúc | domain thuần; không import chéo context | P1 | U `ArchitectureTest` (mỗi service) |

## G. Frontend và E2E

| ID | Ca | Mong đợi | Ưu | Test |
| --- | --- | --- | --- | --- |
| WEB-01 | Duyệt, tìm, lọc, sắp xếp sự kiện | Gọi đúng query; hiển thị đúng | P1 | E `public.spec` |
| WEB-02 | Chi tiết sự kiện | Địa điểm, suất, hạng vé và giá | P1 | E `public.spec` |
| WEB-03 | Route cần đăng nhập khi chưa đăng nhập | Về `/login` với `returnUrl` an toàn | P0 | E `auth-guard.spec`; U `return-url.test.ts`, `client-redirect.test.tsx` |
| WEB-04 | Đặt vé trọn luồng | Giữ chỗ → đơn (hai `Idempotency-Key` khác nhau) → trang thanh toán → webhook giả lập → vé | P0 | E `checkout.spec` |
| WEB-05 | Huỷ đơn, ví vé, lịch sử đơn | Trạng thái đúng | P1 | E `checkout.spec` |
| WEB-06 | XSS qua tên sự kiện | Hiển thị dạng chữ, không thực thi | P0 | E `public.spec`; U `ticket-poster.test.tsx` |
| WEB-07 | Chọn ghế | Không vượt hạn mức; ghế đã giữ không chọn được | P1 | U `SeatPicker.test.tsx`, `seat-map.test.tsx` |
| WEB-08 | Đếm ngược thanh toán | Hết hạn → trạng thái hết hạn | P1 | U `PaymentPanel.test.tsx`, `useCountdown.test.ts` |
| WEB-09 | API client | Đúng method/path/header; lỗi RFC 7807 → `ApiError` | P1 | U `api-contract.test.ts`, `client-edge.test.ts` |
| WEB-10 | CSV xuất | Chặn công thức `= + - @ TAB CR` | P0 | U `csv.test.ts`, `helpers-extra.test.ts` |
| WEB-11 | Giờ trong báo cáo | Luôn giờ Việt Nam | P1 | U `event-report.test.ts` |
