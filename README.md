# NexaTicket

Nền tảng bán vé sự kiện có chọn chỗ realtime, thanh toán VietQR, soát vé QR — thị trường Việt Nam.

Kiến trúc microservices + DDD, nền tảng giữ tiền. Toàn bộ thiết kế ở [`docs/architecture-v2/`](docs/architecture-v2/README.md).

## Hai repo

| Repo | Nội dung |
| --- | --- |
| **Repo này** | Backend (11 service Spring Boot), tài liệu thiết kế, hạ tầng |
| [Concert-Ticket-Frontend](https://github.com/uchihathien/Concert-Ticket-Frontend) | 4 app Next.js, pnpm workspace |

Trong repo này:

| Thư mục | Nội dung | README |
| --- | --- | --- |
| [`backend/`](backend/) | 11 service + gateway, Maven multi-module | [backend/README.md](backend/README.md) |
| `deploy/` | Hạ tầng dùng chung: compose, RabbitMQ topology, Keycloak realm | — |
| `scripts/` | Công cụ dùng chung | — |
| `docs/` | Tài liệu thiết kế — **nguồn chân lý cho cả hai repo** | [docs/README.md](docs/README.md) |

> `frontend/` nằm trong `.gitignore` của repo này. Nếu bạn clone repo frontend vào đó thì chạy local
> được cả hai bên cùng lúc, mà không có hai lịch sử git chồng lên cùng một code.

## Tài liệu thiết kế

| | |
| --- | --- |
| [Kiến trúc v2](docs/architecture-v2/README.md) | Tổng quan, vai trò, hằng số nghiệp vụ, lộ trình G0–G7 |
| [Context map](docs/architecture-v2/context-map.md) | Bounded context, ubiquitous language |
| [Danh mục service](docs/architecture-v2/services.md) | 11 service + gateway |
| [Mô hình địa điểm](docs/architecture-v2/venue-seating-model.md) | Khu vực cố định / linh hoạt, vé ngồi / đứng |
| [Mô hình giữ tiền](docs/architecture-v2/custodial-funds.md) | Sổ cái kép, chi trả, pháp lý |
| [Plan triển khai](docs/architecture-v2/plan/README.md) | [Backend](docs/architecture-v2/plan/backend.md) · [Frontend](docs/architecture-v2/plan/frontend.md) |
| [Hướng giao diện](docs/architecture-v2/ui-direction.md) | Token, bố cục, sơ đồ chỗ ngồi |
| [ADR](docs/architecture-v2/adr/README.md) | ADR-1001…1014 |

---

## Chạy lần đầu

```bash
# 1. Hạ tầng dùng chung
docker compose -f deploy/compose/infra.yml up -d --wait
./scripts/apply-rabbitmq-topology.sh

# 2. Backend
cd backend && ./mvnw -B verify && ./mvnw -pl services/identity-service spring-boot:run

# 3. Frontend (terminal khác, repo riêng)
git clone https://github.com/uchihathien/Concert-Ticket-Frontend.git frontend
cd frontend && corepack pnpm install && corepack pnpm dev
```

## Thanh toán (payOS)

Luồng thu tiền đi qua **payOS** ([ADR-0016](docs/03-seat-checkout/adr/ADR-0016-payos-payment-gateway.md),
hợp đồng webhook: [api/payos-webhook.md](docs/03-seat-checkout/api/payos-webhook.md)). Hai điều cần biết
trước khi chạy thử:

**payOS không có môi trường sandbox.** Tài liệu của họ nói thẳng điều đó — mọi lần gọi API đi vào hệ
thống thật, bằng tài khoản ngân hàng thật. Nên cứ giữ `PAYMENT_SANDBOX=true` khi phát triển:

```bash
set -a && . ./.env && set +a          # secret thật nằm ở .env (đã gitignore)
cd backend && ./mvnw -pl services/payment-service spring-boot:run
```

Chạy trọn luồng mua vé **không tốn đồng nào** — đi qua đúng cùng đường xử lý với webhook thật, nên nó
không phát vé theo luật khác:

```bash
curl -X POST http://localhost:8095/internal/payment-intents/$ORDER_ID/simulate-transfer
# trả thiếu tiền để thử nhánh chặn:
curl -X POST http://localhost:8095/internal/payment-intents/$ORDER_ID/simulate-transfer \
     -H 'Content-Type: application/json' -d '{"amountVnd": 10000}'
```

**Webhook payOS cần một URL HTTPS gọi được từ internet**, nên ở máy phát triển nó không tới. Hai cách:

```bash
# A. Có tunnel: đăng ký URL một lần cho mỗi môi trường.
#    payOS gọi thử endpoint ngay trong lời gọi này, nên nó cũng là phép thử đầu-cuối.
cloudflared tunnel --url http://localhost:8095          # rồi đặt PAYOS_WEBHOOK_URL trong .env
curl -X POST http://localhost:8095/internal/payos/confirm-webhook

# B. Không có tunnel: đã chuyển tiền THẬT thì KÉO trạng thái từ payOS về.
#    An toàn gọi lại nhiều lần — lần thứ hai chỉ trả DUPLICATE.
curl -X POST http://localhost:8095/internal/payment-intents/$ORDER_ID/reconcile
```

`PAYOS_CHECKSUM_KEY` là secret nặng nhất của hệ thống: ai biết nó thì giả được một webhook "đã trả
tiền", và webhook đó là thứ duy nhất đứng giữa internet với việc phát vé thật. Để trống thì service
**từ chối** mọi webhook — không có chế độ "bỏ qua kiểm".

| Dịch vụ | Địa chỉ |
| --- | --- |
| Gateway | http://localhost:8080 |
| identity-service | http://localhost:8090 |
| inventory-service | http://localhost:8092 |
| Keycloak | http://localhost:8081 (`admin` / `admin`) |
| RabbitMQ UI | http://localhost:15672 (`nexaticket` / `nexaticket`) |
| Mailpit | http://localhost:8025 |
| web-customer · admin · scanner · platform | :3000 · :3001 · :3002 · :3003 |

Tài khoản dev trong realm: `superadmin`, `organizer`, `staff`, `customer` — mật khẩu trùng tên đăng nhập. Chỉ dùng cho local.

> **Ai là superadmin?** Cột `users.is_super_admin` chỉ được ghi theo cấu hình
> `nexaticket.identity.super-admin-emails` (biến môi trường `SUPER_ADMIN_EMAILS`). Mặc định ở dev
> là `superadmin@nexaticket.local`. Không có danh sách này thì hệ thống không khởi động được từ
> trạng thái rỗng: `POST /v1/platform/organizations` — cửa vào duy nhất của luồng onboarding — sẽ
> luôn trả 403.
>
> Bản ghi người dùng được tạo ở **request đầu tiên sau khi đăng nhập**, không có bước đăng ký
> riêng: Keycloak là nguồn chân lý của danh tính.

## Thử luồng G0

Chỉ superadmin tạo được tổ chức ([ADR-1010](docs/architecture-v2/adr/ADR-1010-superadmin-tenancy-and-finance-visibility.md)).

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8081/realms/nexaticket/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-platform \
  -d client_secret=dev-secret-platform \
  -d username=superadmin -d password=superadmin | jq -r .access_token)

curl -X POST http://localhost:8090/v1/platform/organizations \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Nhà hát Lớn Hà Nội","ownerEmail":"organizer@nexaticket.local"}'
```

Trả về `invitationToken` — dùng nó gọi `POST /v1/invitations/{token}/accept` với tài khoản `organizer` để hoàn tất luồng.

> `invitationToken` chỉ xuất hiện trong response ở dev và staging để thử được mà không cần hộp thư. Ở production, `notification-service` gửi email và endpoint không trả token.

## Trạng thái

21 module Maven (6 thư viện `platform/` + gateway + 11 service) đã dựng và **build xanh**. Mức độ
hoàn thiện rất khác nhau giữa các service — bảng dưới nói rõ cái nào là sản phẩm, cái nào mới là khung.

| Thành phần | |
| --- | --- |
| Maven multi-module, 6 thư viện `platform/` | ✅ build xanh |
| `identity-service` — tạo tổ chức, mời thành viên, membership | ✅ lát cắt dọc đầy đủ |
| `ledger-service` — sổ cái kép, bút toán N1, bất biến do database ép | ✅ lát cắt dọc đầy đủ |
| `inventory-service` — giữ chỗ, chống oversell, trần mua vé | ✅ lát cắt dọc đầy đủ |
| `api-gateway` — route, JWT, rate limit, correlation id | ✅ |
| Hạ tầng local: PostgreSQL, Redis, RabbitMQ, Keycloak, Mailpit | ✅ |
| 4 app Next.js + design token | ✅ khung |
| **96 test** (63 unit + ArchUnit, 33 integration với PostgreSQL và Redis thật) | ✅ |
| catalog · ordering · payment · payout · ticketing · realtime · notification · analytics | ⬜ khung: POM, cấu hình, ArchUnit, migration rỗng |

### Ba spike bắt buộc

Ba chỗ dồn rủi ro lớn nhất của dự án. Hai đã xong, có test chứng minh:

| Spike | | Bằng chứng |
| --- | --- | --- |
| Sổ cái luôn cân | ✅ | `LedgerInvariantIT` — ghi thẳng SQL để cố tình làm lệch sổ, database từ chối |
| Chống oversell (ngồi + đứng) | ✅ | `SeatHoldConcurrencyIT`, `OversellBackstopIT` — xem dưới |
| Webhook payOS đủ nhánh | ⬜ | thuộc `payment-service`, xem ADR-0016 |

Bộ test chống oversell chạy **thật sự đồng thời**, không phải nộp việc vào thread pool rồi hy vọng:

- 200 người giành cùng 1 ghế → đúng **1** người giữ được
- 500 người mua vé đứng ở zone 200 chỗ → phát đúng **200** vé, không hơn một vé
- Cùng một người mở 20 tab, trần 10 vé → giữ được đúng **10** chỗ
- Giữ chỗ hỗn hợp 2 ngồi + 2 đứng, hỏng phần đứng → rollback cả bốn, không sót dấu vết người giữ
- **Cổng Redis bị làm cho mù** (mô phỏng mất key / vừa failover) → database vẫn chỉ cho đúng 1 người

Điểm cuối là điểm quan trọng nhất. Redis chỉ để 9.900 request thua cuộc khỏi phải chạm database;
chốt chặn thật là partial unique index `uq_hold_item_active`. Nếu không có test đó, ta không biết
được ai đang thật sự chặn — và ngày Redis failover thì hệ thống bán trùng ghế mà mọi test vẫn xanh.

Ba spike là chỗ dồn rủi ro lớn nhất của dự án — chi tiết ở [plan/backend.md §5](docs/architecture-v2/plan/backend.md).

## Quy tắc đóng góp

- Nhánh sống ≤ 3 ngày, squash merge. Conventional Commits, scope là tên service.
- Đổi contract ⇒ kèm diff trong `packages/api-contracts`, cùng PR.
- Đổi schema ⇒ migration Flyway **mới**; không sửa migration đã merge.
- Đụng ghế / tiền / vé ⇒ có integration test; không test thì không merge.
- Consumer mới ⇒ có test **thứ tự đảo** — RabbitMQ không bảo đảm thứ tự ([ADR-1009](docs/architecture-v2/adr/ADR-1009-rabbitmq-only.md)).
- Đụng `ledger-service` ⇒ 2 người review.

Đầy đủ: [plan/README.md §7](docs/architecture-v2/plan/README.md) và Definition of Done §10.
