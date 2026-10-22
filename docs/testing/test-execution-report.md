# Báo cáo thực thi test

Mọi con số dưới đây **đã chạy và đọc từ đầu ra thật** trên máy dev (Windows 11, JDK 21, Docker Desktop
29.4, Node 20+, pnpm 9). Không có số ước lượng, trừ chỗ ghi rõ. Chạy ngày 22/10/2026.

## 1. Kết quả

### Backend — `./mvnw -B verify -Pcoverage` (toàn bộ reactor, 18 module có test)

| Tầng | Số test | Đạt | Đỏ | Bỏ qua |
| --- | ---: | ---: | ---: | ---: |
| Unit + API slice + ArchUnit (Surefire) | 2.614 | 2.614 | 0 | 0 |
| Integration, PostgreSQL/Redis thật (Failsafe) | 383 | 383 | 0 | 0 |
| **Tổng backend** | **2.997** | **2.997** | **0** | **0** |

Trước đợt này: 358 unit test (đo ở lần chạy ban đầu, tất cả xanh) + bộ IT có sẵn (chưa chạy được lúc đó vì Docker tắt).

Hai test từng đỏ ở lượt đầu (thiếu `Idempotency-Key`; route soát vé cũ luôn 403) đã được sửa ở ứng dụng
và giờ xanh — xem [known-testing-gaps.md §2](known-testing-gaps.md). Lượt chạy cuối: `BUILD SUCCESS`, không
test nào đỏ hay bị bỏ qua.

Theo module (unit / IT): shared-kernel 101/—, starter-web 75/—, starter-security 133/—,
starter-outbox 10/—, starter-idempotency 28/9, api-gateway 163/8, identity 336/76, ledger 64/21,
catalog 486/60, inventory 206/44, ordering 216/30, realtime-gateway 34/—, payment 214/27,
payout 79/8, ticketing 212/39, notification 57/7, analytics 47/7, ai-chatbox 153/47.

### Frontend

| Bộ | Test | Kết quả |
| --- | ---: | --- |
| `@nexaticket/ts-sdk` (Vitest) | 108 | 108 đạt |
| `@nexaticket/ui` | 147 | 147 đạt |
| `@nexaticket/auth` | 72 | 72 đạt |
| `@nexaticket/web-customer` | 41 | 41 đạt |
| `@nexaticket/web-admin` | 9 | 9 đạt |
| **Unit frontend** | **377** | **377 đạt, 0 đỏ, 0 bỏ qua** |
| E2E Playwright (`web-customer`, Chromium) | 33 | 33 đạt |
| `tsc --noEmit` + `eslint` (5 gói/app trên) | — | sạch |

Trước đợt này: 33 tệp test ở `packages/ts-sdk`, `ui`, `auth`; chưa có test ở `apps/*` và chưa có E2E.

### Load test k6 (k6 v2.2.0, một laptop, 4 service + hạ tầng)

Chi tiết: [load-test-report.md](../03-seat-checkout/load-test-report.md).

| Script | Kết quả |
| --- | --- |
| `hold-contention.js` (4 người tranh một nhóm 4 ghế, 4 lần chạy) | **Đạt cả 4 lần**: đúng 1 người thắng, 3 người 409, 0 trạng thái lạ, ghế về `AVAILABLE` sau khi nhả |
| `discovery.js` (50 VU), heap inventory 1 GB | 0% lỗi, 100% checks và bất biến; **ba ngưỡng độ trễ vượt** (`seat_map` p95 2,82 s so với 500 ms; `events_list` 465 ms so với 300 ms; `event_detail` 320 ms so với 300 ms) |
| `discovery.js`, heap inventory 384 MB (mặc định `dev-up.sh --jar`) | **Inventory hết bộ nhớ** (62 `OutOfMemoryError`), 3,1% request lỗi |
| `checkout.js` với **payOS giả** (`load-tests/fake-payos`), 6 service | **Đạt**: 61 vòng không thanh toán + 7 vòng có thanh toán, 279/279 checks, p95 giữ chỗ 43 ms, p95 đặt đơn 115 ms; gửi lại cùng khoá không mở thêm link; mọi đơn thanh toán chuyển `PAID` |

## 2. Tổng số tệp

| | Trước | Sau | Ghi chú |
| --- | ---: | ---: | --- |
| Tệp Java dưới `backend/**/src/test` (gồm helper) | 121 | 290 | +169 tệp tạo mới; 5 tệp có sẵn được sửa (`WebSliceTestBase`, `WebSlice`, `CheckinControllerWebTest`…) |
| Tệp test frontend (`*.test.ts(x)`, `*.spec.ts`) | 33 | 49 | +16, gồm 3 spec E2E; cộng cấu hình Vitest / Playwright, stub API, fixture |
| Tệp `src/main` + `application.yml` backend bị sửa vì lỗi ứng dụng | — | 46 | xem §5; thêm mới 2 lớp (`InputRules`, `DomainRuleExceptionAdvice`) |
| Mã ứng dụng frontend bị sửa | — | 2 | `packages/ui/src/csv.ts`, `apps/web-admin/src/components/event-report.ts` |
| Cấu hình build | — | 12 | poms backend (JaCoCo, `spring-security-test`), vitest/turbo/package.json frontend |
| Tài liệu, k6, script coverage | — | 9 | `docs/testing/*` (5), `load-tests/` (README + 4 script), `scripts/coverage-summary.py` |

Số tệp lấy từ `find` / `git` ngày 22/10/2026; số test (§1) lấy từ báo cáo của Surefire/Failsafe/Vitest.
Một phần tệp backend đã nằm trong commit `612b031` do chủ repo tạo giữa lúc làm việc; phần còn lại
chưa commit.

## 3. Lệnh đã chạy

```bash
# backend (trong backend/)
./mvnw -B -ntp verify -DskipITs                     # unit + slice + ArchUnit
./mvnw -B -ntp verify -Pcoverage                    # + IT + JaCoCo gộp  → kết quả §1
python ../scripts/coverage-summary.py --classes     # bảng coverage §4

# frontend (trong frontend/)
corepack pnpm --filter @nexaticket/<pkg> test:coverage
corepack pnpm --filter @nexaticket/web-customer test:e2e
corepack pnpm --filter @nexaticket/<pkg> typecheck ; … lint
```

Spotless (`palantirJavaFormat`) chạy ở pha `validate` của mọi lần build và đều qua.

## 4. Coverage

### Backend — JaCoCo, unit + integration gộp (`target/site/jacoco-merged/`)

Loại trừ: lớp `*Application` và `infrastructure/seed/**` (dữ liệu demo cho dev).

| Module | Instruction | Branch | Line | Method |
| --- | ---: | ---: | ---: | ---: |
| shared-kernel | 99,9% | 97,7% | 100,0% | 100,0% |
| starter-idempotency | 94,6% | 100,0% | 91,1% | 85,0% |
| starter-outbox | 94,3% | 88,9% | 92,8% | 76,9% |
| starter-security | 79,4% | 87,8% | 81,0% | 75,7% |
| starter-web | 96,9% | 91,0% | 95,8% | 92,0% |
| api-gateway | 100,0% | 100,0% | 100,0% | 100,0% |
| identity-service | 93,5% | 85,3% | 94,0% | 92,4% |
| catalog-service | 88,2% | 76,2% | 88,2% | 88,4% |
| inventory-service | 95,9% | 92,5% | 95,7% | 95,7% |
| ordering-service | 93,0% | 86,7% | 93,7% | 93,5% |
| payment-service | 98,1% | 90,7% | 98,1% | 97,7% |
| ledger-service | 96,8% | 85,3% | 96,2% | 97,6% |
| payout-service | 85,7% | 83,3% | 86,6% | 90,9% |
| ticketing-service | 94,2% | 88,9% | 93,8% | 96,5% |
| notification-service | 98,5% | 91,2% | 98,1% | 100,0% |
| analytics-service | 95,6% | 90,5% | 97,2% | 95,5% |
| realtime-gateway | 90,4% | 85,3% | 88,1% | 89,4% |
| ai-chatbox-service | **64,1%** | **47,2%** | **64,0%** | 71,9% |
| **Tổng** | **87,6%** | **77,0%** | **87,7%** | **89,5%** |

Dòng 10.914/12.447 · nhánh 2.641/3.431.

**So với mục tiêu (≥ 80% statement, ≥ 80% branch cho logic lõi):**

- Statement/line: đạt toàn cục (87,6% / 87,7%) và ở mọi module **trừ `ai-chatbox-service` (64%)**.
- Branch: toàn cục 77,0% — **chưa đạt 80%**. Các module lõi (inventory 92,5%, payment 90,7%,
  ordering 86,7%, ledger 85,3%, ticketing 88,9%, identity 85,3%) đều trên 80%. Thấp: `catalog`
  76,2%, `ai-chatbox` 47,2%.
- Chưa đạt vì: `ai-chatbox-service` có adapter LLM (Anthropic, Ollama, Voyage) và `ToolDispatcher`
  cần mạng/model thật; `catalog` có `JdbcCatalogQueries`, adapter HTTP tới inventory/analytics và
  `LayoutColumns` chỉ được chạm bởi IT có sẵn. Lớp thiếu nhiều dòng nhất: `AnthropicLlmAdapter` (119),
  `ToolDispatcher` (110), `OllamaLlmAdapter` (76), `CatalogHttpAdapter` của chatbox (72),
  `OrderingHttpAdapter` của chatbox (56), `JdbcTicketSearchAdapter` (45), `JdbcSalesStatsReader` (41),
  `JdbcCatalogQueries` (41), `AnalyticsSalesAdapter` (38), `SecurityAutoConfiguration` (36).
- Test tiếp theo cần để đóng khoảng cách: adapter HTTP với `MockRestServiceServer` (chatbox, catalog),
  `ToolDispatcher` với `FakeAiProviders`, IT cho các reader thống kê JDBC.

### Frontend — `@vitest/coverage-v8` (statements / branches / functions / lines)

| Gói | Stmts | Branch | Funcs | Lines |
| --- | ---: | ---: | ---: | ---: |
| ts-sdk | 67,86 | 95,02 | 69,87 | 67,86 |
| ui | 68,14 | 85,28 | 77,58 | 68,14 |
| auth | 70,71 | 86,98 | 89,09 | 70,71 |
| web-customer | 21,28 | 65,39 | 37,11 | 21,28 |
| web-admin | 4,35 | 43,26 | 16,66 | 4,35 |

**Frontend chưa đạt 80% statement.** Vitest tính mọi tệp dưới `src/`, kể cả các trang và component
chưa được test (trang quản trị, `FloorPlanEditor`, `EventMasterPanel`…). Phần logic thuần
(`format`, `csv`, `client`, `return-url`, ghế, thanh toán) phủ cao; phần giao diện được kiểm gián
tiếp bằng E2E, không tính vào số này. Loại trừ duy nhất: tệp `__tests__`, `*.d.ts` và barrel
`src/index.ts` (không loại `types/**` vì ba tệp trong đó có mã chạy).

## 5. Lỗi ứng dụng đã sửa trong đợt này

Mỗi lỗi do một test đỏ chỉ ra; sau khi sửa test giữ lại làm hồi quy. Danh sách đầy đủ, kèm vị trí
sửa, ở [known-testing-gaps.md §2](known-testing-gaps.md). Quan trọng nhất:

1. **Bán trùng ghế** — release chạy chồng đặt chỗ ghi đè trạng thái hold vô điều kiện
   (`ReleaseReserveRaceIT` tái hiện trên PostgreSQL thật).
2. **Lộ đơn của người khác** (kèm QR và link thanh toán) qua lối tắt `holdId`.
3. **Leo thang đặc quyền**: `ORG_ADMIN` tạo / hạ / gỡ `ORG_OWNER`.
4. **Doanh thu tổ chức đọc được bởi nhân viên soát vé** (`/sales` không kiểm quyền).
5. **Message hỏng làm kẹt vĩnh viễn hàng đợi** (inventory, ticketing, ledger).
6. **Chèn log / header SMTP** qua `X-Correlation-Id` và tên sự kiện.
7. **Mất cập nhật khi hai lệnh sửa thành viên chạy chồng** (khoá dòng tổ chức).
8. Hàng chục lỗi nhập liệu trả 500 thay vì 400 (UUID sai, thiếu tham số, slug, giá, tràn số…).

Các thay đổi này nằm ở `src/main` và tách khỏi thay đổi test; mỗi thay đổi có thể xem bằng
`git diff -- '*/src/main/*'`.

## 6. Test không chạy / bị chặn

| Mục | Trạng thái | Lý do |
| --- | --- | --- |
| payOS **thật** (trang thanh toán, webhook) | Chưa kiểm | Không có sandbox; `checkout.js` đã chạy với payOS giả |
| k6 10.000 VU (kế hoạch M03) | Chưa chạy | Cần staging cỡ production |
| E2E đăng nhập Keycloak thật, Google IdP, trang payOS | Chặn | Cần Keycloak / payOS thật (payOS không có sandbox) |
| E2E thời gian thực (ghế đổi trạng thái) | Chặn | Cần realtime-gateway + RabbitMQ |
| 4 app mobile | Ngoài phạm vi | Xem known-testing-gaps §4 |
| `NotificationIT.mot_thu_hong_khong_chan_ca_lo` | Chập chờn (có sẵn từ trước) | Job `@Scheduled` chen vào; đỏ một lần trong một lượt, xanh khi chạy lại và ở lượt cuối |

## 7. Thiết lập thủ công còn lại

- Docker Desktop chạy (cho IT). Trên Windows nếu `docker info` báo không thấy daemon thì khởi động
  Docker Desktop trước; README backend có mục xử lý `Could not find a valid Docker environment`.
- JDK 21 (`JAVA_HOME`), Node ≥ 20, `corepack enable`.
- Không cần biến môi trường cho unit/slice test. IT dùng container Postgres/Redis tự dựng. E2E tự sinh
  `AUTH_SECRET` ngẫu nhiên và dùng nhà phát hành OIDC giả — không đọc `.env.local`.
- k6: `winget install k6`; token người dùng thử cho `TOKENS_FILE` (không commit).
- Cài trình duyệt Playwright một lần: `corepack pnpm exec playwright install chromium`.
