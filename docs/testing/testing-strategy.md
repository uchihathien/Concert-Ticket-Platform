# Chiến lược kiểm thử

Tài liệu này mô tả **cách** NexaTicket được kiểm thử: phạm vi, các tầng test, công cụ, hạ tầng và
cách chạy. Kết quả chạy thực tế nằm ở [test-execution-report.md](test-execution-report.md); những
gì chưa kiểm được nằm ở [known-testing-gaps.md](known-testing-gaps.md).

## 1. Phạm vi

| Hệ | Repo | Công nghệ | Test |
| --- | --- | --- | --- |
| Backend — 12 service + gateway | repo này, `backend/` | Java 21, Spring Boot 3.5, Maven multi-module, PostgreSQL 16, RabbitMQ, Redis, Keycloak | JUnit 5, AssertJ, Mockito, MockMvc, ArchUnit, Testcontainers, JaCoCo |
| Frontend — 4 app web + 4 app mobile, 6 package | [Concert-Ticket-Frontend](https://github.com/uchihathien/Concert-Ticket-Frontend), clone vào `frontend/` | Next.js App Router, React, TanStack Query, pnpm + turbo | Vitest, Testing Library, msw, Playwright |
| Load / tranh chấp | repo này, `load-tests/` | k6 | ngưỡng p95 + bất biến không bán trùng |

App mobile (React Native) nằm ngoài phạm vi đợt này — xem [known-testing-gaps.md](known-testing-gaps.md).

## 2. Những bất biến được ưu tiên

Thứ tự ưu tiên lấy từ [product-decisions.md § Invariant không đàm phán](../00-discovery/product-decisions.md)
và [state-machines.md](../03-seat-checkout/state-machines.md). Mỗi bất biến phải có ít nhất một test
**ở tầng thấp nhất chứng minh được nó**:

| Bất biến | Tầng chứng minh | Vì sao tầng đó |
| --- | --- | --- |
| Không bán trùng một `(session, seat)` | Integration (PostgreSQL thật, nhiều luồng) + k6 | Chỉ khoá hàng / unique index thật mới chứng minh được; mock luôn "đúng" |
| Giá do server tính, không nhận từ client | Unit (handler) + API slice | Request body không có trường giá nào để tin; handler lấy từ `PricingPort` |
| Hoa hồng cộng theo dòng, sổ cái cân | Unit (domain) + Integration (trigger ledger) | Làm tròn là logic thuần; cân sổ là constraint của DB |
| Webhook / thanh toán / phát vé idempotent | Unit + Integration | Lần giao thứ hai phải không đổi gì, kể cả mốc `paidAt` |
| Chữ ký webhook payOS sai bị từ chối | Unit (`PayosSignature`) + API slice | Thuần mật mã; endpoint công khai nên phải kiểm cả đường HTTP |
| Tenant scope từ membership, không tin `organization_id` client | Integration (`TenantIsolationIT`, `AccessControlIT`) + API slice | Filter thật + membership thật |
| QR không chứa PII, chữ ký Ed25519 | Unit (signer) | Thuần mật mã |
| Một lần check-in thành công / vé, vào–ra–vào lại theo luật | Unit (domain `Ticket`, `CheckInHandler`) + Integration | Trạng thái vé + cuộc đua hai cổng cùng quét |

## 3. Các tầng test

### 3.1 Unit — `*Test.java`, Surefire, không cần Docker

- **Domain** (`domain/model`): aggregate, value object, máy trạng thái. Không mock gì cả — domain
  không phụ thuộc framework (ArchUnit ép điều này).
- **Application** (`application/command`, `application/query`): handler với port được mock bằng
  Mockito. Kiểm luật nghiệp vụ, nhánh lỗi, bù trừ (compensation), idempotency.
- **Infrastructure có logic** (adapter HTTP, chữ ký, listener AMQP): adapter HTTP kiểm bằng
  `MockRestServiceServer`, không gọi mạng thật; listener kiểm thông điệp trùng / hỏng.
- **ArchUnit** (`ArchitectureTest` mỗi service): domain thuần, chiều phụ thuộc hexagonal, cấm import
  chéo bounded context.

### 3.2 API slice — `*ControllerTest.java`, Surefire, không cần Docker

`@WebMvcTest` + MockMvc với tầng application được mock: đúng method/route, mã trạng thái, validation
(thiếu trường, sai kiểu, UUID hỏng), thân lỗi RFC 7807 thống nhất, 401/403 khi thiếu token / sai vai
trò / sai tenant, endpoint `/internal/**` đòi secret nội bộ. Filter bảo mật thật của
`starter-security` được nạp khi khả thi, để test không "xanh nhờ tắt bảo mật".

### 3.3 Integration — `*IT.java`, Failsafe, cần Docker

Spring context đầy đủ + PostgreSQL/Redis thật qua Testcontainers (`platform/starter-test`:
`PostgresSingleton`, `RedisSingleton` — một container cho mỗi database, sống hết JVM, `withReuse`).
Service ngoài (payOS, service khác) là hàng giả điều khiển được (`FakePayos`, `FakeRemoteServices`)
để ép được các tình huống timeout / 5xx / bù trừ cũng hỏng một cách xác định.

Hai luật bắt buộc với IT, cả hai rút ra từ lỗi đã gặp:

1. **Không dùng `@Container` trong lớp base dùng chung** — Spring cache context giữa các lớp test,
   còn JUnit dừng container sau lớp đầu: mọi lớp sau đều đỏ. Dùng `PostgresSingleton`.
2. **Fixture duy nhất theo từng lần chạy** (UUID ngẫu nhiên, slug có hậu tố ngẫu nhiên) — container
   được tái dùng giữa các lần chạy nên dữ liệu cũ còn đó; fixture cố định xanh lần đầu, đỏ mọi lần sau.

### 3.4 Frontend unit — Vitest

`packages/*/src/__tests__` và `apps/web-*/src/**/__tests__`: helper thuần (định dạng VND, giờ
Việt Nam, CSV, bộ lọc nhanh), API client (đúng method/path/header `Idempotency-Key`, ánh xạ lỗi
RFC 7807) với msw, hook React Query, component có logic (chọn ghế, đếm ngược, render chuỗi người
dùng nhập dưới dạng text).

### 3.5 E2E — Playwright (`apps/web-customer/e2e`)

Chạy `next dev` thật trỏ vào một **API giả xác định** (fixture JSON cùng hình dạng với backend thật).
Phủ các hành trình không cần nhà cung cấp danh tính thật: trang chủ, duyệt / tìm / lọc sự kiện, chi
tiết sự kiện, chuyển hướng về `/login` ở trang cần đăng nhập, render an toàn nội dung độc hại. Hành
trình cần Keycloak thật (thanh toán, lịch sử đơn) được ghi rõ là bị chặn và vì sao.

### 3.6 Load / tranh chấp — k6 (`load-tests/`)

Tách khỏi mọi lệnh test thường. Đo p95 đọc catalog / sơ đồ ghế, kiểm "đúng một người thắng" khi N
người giữ cùng một nhóm ghế qua gateway thật, và idempotency của `POST /v1/orders`. Chỉ chạy vào
dev/staging; script tự từ chối host lạ. Chi tiết: [load-tests/README.md](../../load-tests/README.md).

## 4. Dữ liệu test

- Unit: dữ liệu dựng tại chỗ, thời gian cố định (`Instant.parse(...)`, `Clock.fixed`) — không phụ
  thuộc giờ máy.
- Integration: fixture/factory trong `support/` của từng service (`InventoryFixture`,
  `FakeRemoteServices`, `FakePayos`…), id ngẫu nhiên mỗi lần chạy.
- E2E: `apps/web-customer/e2e/fixtures/*.json`.
- Không bao giờ chạy test vào dữ liệu production. payOS **không có sandbox** — mọi test thanh toán
  dùng `FakePayos` hoặc nhánh `simulate-transfer` (chỉ bật khi `PAYMENT_SANDBOX=true`).

## 5. Coverage

- Backend: JaCoCo qua profile `coverage` (`backend/pom.xml`). Unit ghi `jacoco.exec`, IT ghi
  `jacoco-it.exec`, gộp thành `target/site/jacoco-merged/` mỗi module.
  `python scripts/coverage-summary.py` in bảng toàn repo; module chỉ có số unit được đánh dấu
  `unit-only` để con số không trông đầy đủ hơn thực tế.
  Loại trừ: lớp `*Application` (chỉ `main`) và `infrastructure/seed/**` (dữ liệu demo cho dev).
- Frontend: `@vitest/coverage-v8`, lệnh `test:coverage` mỗi package.
- Mục tiêu: ≥ 80% statement toàn phần code kiểm được; ≥ 80% branch cho logic nghiệp vụ lõi (đặt
  chỗ, giá, tồn kho, phân quyền, thanh toán). Đây là mục tiêu, không phải lý do để viết test yếu.

## 6. Lệnh chạy

### Backend (trong `backend/`, JDK 21; trên Git Bash đặt `JAVA_HOME` như README backend)

| Việc | Lệnh |
| --- | --- |
| Toàn bộ unit + API slice + ArchUnit (không cần Docker) | `./mvnw -B verify -DskipITs` |
| Unit một service | `./mvnw -B -pl services/ordering-service verify -DskipITs` |
| Một lớp test | `./mvnw -B -pl services/ordering-service test -Dtest=PlaceOrderHandlerTest` |
| Chỉ integration (cần Docker) | `./mvnw -B verify -Dsurefire.skip=true` |
| Một IT | `./mvnw -B -pl services/inventory-service verify -Dsurefire.skip=true -Dit.test=SeatHoldConcurrencyIT` |
| Mọi thứ an toàn cho CI | `./mvnw -B verify` |
| Coverage | `./mvnw -B verify -Pcoverage && python ../scripts/coverage-summary.py` |
| Lint | `./mvnw -B spotless:check` (sửa: `spotless:apply`) |

Watch mode: Maven không có; dùng chạy test của IDE, hoặc `./mvnw -pl <service> test -Dtest=<Lớp>`.

### Frontend (trong `frontend/`)

| Việc | Lệnh |
| --- | --- |
| Unit mọi package/app | `corepack pnpm test` |
| Watch một package | `corepack pnpm --filter @nexaticket/ui exec vitest` |
| Coverage | `corepack pnpm test:coverage` |
| E2E | `corepack pnpm --filter web-customer test:e2e` |
| Typecheck / lint | `corepack pnpm typecheck` · `corepack pnpm lint` |

### Load

Xem [load-tests/README.md](../../load-tests/README.md).

## 7. CI

`.github/workflows/backend.yml` đã tách ba bước: Spotless → unit + ArchUnit (`-DskipITs`) →
integration (`-Dsurefire.skip=true`, runner GitHub có Docker). Test API slice mới chạy trong bước
unit, không cần thay workflow. Load test không chạy trong CI; E2E nightly (`e2e-nightly.yml`) vẫn
là chỗ giữ chỗ — xem [known-testing-gaps.md](known-testing-gaps.md).
