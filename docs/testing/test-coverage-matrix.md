# Ma trận độ phủ test

Ánh xạ từ **mã nguồn → tệp test**, để không endpoint hay module nào bị bỏ sót im lặng. Số liệu
phần trăm nằm ở [test-execution-report.md](test-execution-report.md); phần chưa kiểm được nằm ở
[known-testing-gaps.md](known-testing-gaps.md).

Sinh từ mã nguồn bằng cách quét mọi `*Controller.java` trong `interfaces/rest` và tìm tệp test nhắc
tới tên lớp đó (riêng identity-service gom nhiều controller vào cùng một lớp test nên bảng dưới ghi
tay). Mỗi controller có test slice `@WebMvcTest` chạy **bộ lọc bảo mật thật** (`TenantFilter`,
`PermissionGuard`, `InternalApiFilter`, xử lý lỗi RFC 7807) — chỉ `JwtDecoder` và `MembershipLookup`
được giả.

## 1. API — 43 controller, 154 endpoint

| Service | Controller | Số endpoint | Tệp test |
| --- | --- | ---: | --- |
| ai-chatbox-service | `SupportChatController` | 3 | ChatIdempotencyIT<br>SupportChatControllerTest |
| ai-chatbox-service | `SupportDeskController` | 5 | SupportDeskControllerTest |
| ai-chatbox-service | `SupportKnowledgeController` | 6 | SupportKnowledgeControllerTest |
| analytics-service | `InternalSalesController` | 3 | InternalSalesControllerTest |
| analytics-service | `SalesDashboardController` | 1 | SalesDashboardControllerTest |
| catalog-service | `AdminCatalogController` | 18 | AdminCatalogControllerTest<br>AdminRolesTest |
| catalog-service | `CheckinSessionController` | 1 | OrganizationControllersTest |
| catalog-service | `InternalCatalogController` | 2 | InternalCatalogControllerTest |
| catalog-service | `OrganizationDashboardController` | 2 | OrganizationControllersTest |
| catalog-service | `OrganizationMediaController` | 1 | OrganizationControllersTest |
| catalog-service | `OrganizationSeatingController` | 9 | OrganizationControllersTest |
| catalog-service | `OrganizationSessionDirectoryController` | 1 | OrganizationControllersTest |
| catalog-service | `PlatformTemplateController` | 9 | PlatformTemplateControllerTest |
| catalog-service | `PublicCatalogController` | 4 | PublicCatalogControllerTest |
| identity-service | `AccessController` | 2 | MeAndAccessControllerTest<br>AccessControlIT |
| identity-service | `InternalAuditController` | 1 | StaffAndInternalControllersTest<br>InternalAuditIT |
| identity-service | `InternalMembershipController` | 2 | StaffAndInternalControllersTest<br>InternalOhsIT<br>InternalApiSecretIT |
| identity-service | `MeController` | 2 | MeAndAccessControllerTest<br>AuthenticationFlowIT |
| identity-service | `MyInvitationController` | 2 | MeAndAccessControllerTest<br>StaffInvitationIT |
| identity-service | `OrganizationController` | 15 | OrganizationControllerTest<br>MemberManagementIT<br>TenantIsolationIT<br>SessionRevocationIT |
| identity-service | `PlatformOrganizationController` | 5 | PlatformControllersTest |
| identity-service | `PlatformUserController` | 6 | PermissionGuardWiringIT |
| identity-service | `StaffController` | 6 | StaffAndInternalControllersTest<br>StaffInvitationIT |
| inventory-service | `HoldController` | 2 | HoldControllerTest |
| inventory-service | `InternalReservationController` | 3 | InternalReservationControllerTest |
| inventory-service | `InternalSeatStatusController` | 1 | InternalSeatStatusControllerTest |
| inventory-service | `OrganizationSeatStatsController` | 1 | OrganizationSeatStatsControllerTest |
| inventory-service | `SeatMapController` | 1 | SeatMapControllerTest |
| ledger-service | `PlatformLedgerController` | 2 | PlatformLedgerControllerWebTest |
| ordering-service | `InternalOrderController` | 3 | InternalOrderControllerTest |
| ordering-service | `OrderController` | 4 | OrderControllerTest |
| ordering-service | `OrganizationStatsController` | 7 | OrganizationStatsControllerTest |
| payment-service | `InternalPaymentController` | 4 | InternalPaymentControllerWebTest |
| payment-service | `InternalPayosAdminController` | 1 | InternalPayosAdminControllerWebTest |
| payment-service | `PayosWebhookController` | 1 | PayosWebhookControllerWebTest |
| payout-service | `PlatformPayoutController` | 4 | PlatformPayoutControllerWebTest |
| ticketing-service | `CheckinController` | 1 | CheckinControllerWebTest |
| ticketing-service | `InternalCheckinAssignmentController` | 2 | InternalControllersWebTest |
| ticketing-service | `InternalTicketController` | 1 | InternalControllersWebTest |
| ticketing-service | `MyTicketsController` | 2 | TicketReadControllersWebTest |
| ticketing-service | `OrganizationCheckinController` | 2 | CheckinControllerWebTest |
| ticketing-service | `OrganizationGateStatsController` | 5 | GateStatsControllerWebTest |
| ticketing-service | `OrganizationTicketController` | 1 | TicketReadControllersWebTest |
| | **Tổng** | **154** | |

Mỗi endpoint được kiểm ở các mặt: route/method đúng, 2xx với thân đúng schema, validation (thiếu /
sai kiểu / UUID hỏng / thân JSON hỏng), 401 không token, 403 sai vai trò hoặc sai tenant, 404, và
hình dạng lỗi RFC 7807 thống nhất. `/internal/**` được kiểm là đòi secret nội bộ và **không** đi
qua gateway (`GatewayRouteTableTest`, `GatewaySecurityTest`).

## 2. Module backend → loại test

| Module | Unit / slice | Integration (Docker) | ArchUnit |
| --- | --- | --- | --- |
| `platform/shared-kernel` | MoneyTest, MoneyEdgeCaseTest, RoleMatrixTest, IdentifiersTest, TimeWindow*Test | — | — |
| `platform/starter-web` | GlobalExceptionHandlerWebTest, ConstraintViolationHandlerTest, ApiErrorTest, CorrelationIdFilterTest | — | — |
| `platform/starter-security` | TenantFilterTest, TenantContextTest, TenantPathTest, InternalApiFilterTest, PermissionGuardTest, HttpMembershipLookupTest, InternalTokenPropagation*Test | — | — |
| `platform/starter-outbox` | OutboxWriterTest, OutboxPublisherTest | — | — |
| `platform/starter-idempotency` | ConsumedEventTest, IdempotencyStoreTest | IdempotencyStoreIT | — |
| `api-gateway` | GatewayRouteTableTest, GatewaySecurityTest, CorrelationAndRateLimitKeyTest, GatewayCorsTest, RateLimitKeyResolverTest | GatewayRoutingIT | — |
| `identity-service` | domain, mọi handler, Keycloak adapter, 4 lớp test controller | 12 IT, gồm OrganizationConcurrencyIT | ✔ |
| `catalog-service` | EventLifecycle, VenueAndTemplateRules, handlers, queries, media, 9 controller | 8 IT | ✔ |
| `inventory-service` | HoldRequest, SeatHold, SessionInventory, handlers, listeners AMQP, RedisAvailabilityGate, 5 controller | SeatHoldConcurrencyIT, OversellBackstopIT, HoldLifecycleIT, ReleaseReserveRaceIT… | ✔ |
| `ordering-service` | OrderStateTransition, OrderInvariants, PlaceOrder/ConfirmPayment/CloseOrder/Compensation, queries, HTTP adapters, 3 controller | CheckoutSagaIT, OrderLifecycleIT, OrderRepositoryIT… | ✔ |
| `payment-service` | PaymentStateMatrix, mọi handler, PayosSignature, PayosHttpGateway, 3 controller | PayosPaymentFlowIT, WebhookEdgeCasesIT… | ✔ |
| `ledger-service` | LedgerModelEdgeCase, RecordPaymentHandler, OrderPaidListener, controller | LedgerInvariantIT, LedgerAppendOnlyIT, OrderPaidListenerIT | ✔ |
| `payout-service` | PayoutLifecycle, PayoutHandlers, HTTP adapters, controller | FourEyesIT, PayoutRepositoryIT | ✔ |
| `ticketing-service` | TicketLifecycle, CheckInHandler, TicketSigning (Ed25519), IssueTickets, queries, OrderPaidListener, 7 controller | TicketCheckinIT, QrTokenIT, IssueOnOrderPaidIT… | ✔ |
| `notification-service` | model, listeners, adapters, handlers | NotificationIT | ✔ |
| `analytics-service` | application, listener, 2 controller | SalesReadModelIT | ✔ |
| `realtime-gateway` | handler, topology, listener, security | — | ✔ |
| `ai-chatbox-service` | use case, intent, prompts, tool dispatcher, 3 controller | 4 IT | ✔ |

## 3. Bất biến nghiệp vụ → test chứng minh

| Bất biến ([product-decisions.md](../00-discovery/product-decisions.md)) | Test |
| --- | --- |
| Không bán trùng ghế | `SeatHoldConcurrencyIT`, `OversellBackstopIT`, `ReleaseReserveRaceIT`, `HoldLifecycleIT`, `PlaceHoldHandlerTest`, k6 `hold-contention.js` |
| Giá do server tính | `PlaceOrderHandlerTest` (giá từ `PricingPort`, request không có trường giá), `OrderInvariantsTest` |
| Hoa hồng cộng theo dòng, sổ cái cân | `OrderTest`, `OrderInvariantsTest`, `LedgerModelEdgeCaseTest`, `LedgerInvariantIT` |
| Thanh toán / webhook idempotent | `HandlePayosWebhookHandlerTest`, `ConfirmTransferHandlerTest`, `WebhookEdgeCasesIT`, `ConfirmPaymentHandlerTest` |
| Chữ ký webhook sai bị từ chối | `PayosSignatureEdgeCaseTest`, `PayosWebhookControllerWebTest` |
| Tiền vào đơn đã đóng → MANUAL_REVIEW, không PAID | `OrderStateTransitionTest`, `ConfirmPaymentHandlerTest`, `ConfirmTransferHandlerTest` |
| Tenant scope từ membership | `TenantFilterTest`, `TenantIsolationIT`, `AccessControlIT`, mọi controller test (403 sai tenant) |
| Không leo thang đặc quyền | `InviteMemberHandlerTest`, `ManageMembersHandlerTest`, `OrganizationControllerTest` |
| QR không PII, chữ ký Ed25519 | `TicketSigningTest`, `QrTokenIT` |
| Một lần check-in / vé, vào–ra–vào lại | `TicketLifecycleTest`, `CheckInHandlerTest`, `TicketCheckinIT` |
| Khách không xem đơn / vé / chat của người khác | `OrderQueriesTest`, `TicketingQueriesTest`, `SupportChatControllerTest` |

## 4. Frontend

| Gói / app | Tệp test | Phạm vi |
| --- | --- | --- |
| `packages/ts-sdk` | `api-contract`, `client-edge`, `hooks`, `client`, `query`, `inventory`, `idempotency`… | method/path/header `Idempotency-Key`, ánh xạ lỗi RFC 7807, React Query |
| `packages/ui` | `helpers-extra`, `overlays`, `csv`, `format`, `quick-filters`, `seat-map`… | định dạng VND/giờ VN, CSV, bộ lọc, ghế, dialog/toast |
| `packages/auth` | `client-redirect`, `return-url`, `keycloak`, `access-token`… | chống open-redirect, token, cookie |
| `apps/web-customer` | `src/lib/__tests__`, `src/components/__tests__` | saved-events, auth-popup, SeatPicker, PaymentPanel, OrderList |
| `apps/web-admin` | `event-report.test.ts` | báo cáo sự kiện, múi giờ |
| E2E `apps/web-customer/e2e` | `public`, `auth-guard`, `checkout` `.spec.ts` | duyệt/tìm/lọc, chặn route, đăng nhập OIDC giả, đặt vé → thanh toán giả lập → vé, huỷ đơn, XSS |
