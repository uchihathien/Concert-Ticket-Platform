# NexaTicket

Nền tảng bán vé sự kiện có chọn chỗ realtime, thanh toán VietQR, soát vé QR — thị trường Việt Nam.

Kiến trúc microservices + DDD, nền tảng giữ tiền. Toàn bộ thiết kế ở [`docs/architecture-v2/`](docs/architecture-v2/README.md).

## Hai repo

| Repo | Nội dung |
| --- | --- |
| **Repo này** | Backend (12 service Spring Boot + gateway), tài liệu thiết kế, hạ tầng |
| [Concert-Ticket-Frontend](https://github.com/uchihathien/Concert-Ticket-Frontend) | 4 app Next.js, pnpm workspace |

Trong repo này:

| Thư mục | Nội dung | README |
| --- | --- | --- |
| [`backend/`](backend/) | 12 service + gateway, Maven multi-module | [backend/README.md](backend/README.md) |
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
| [Danh mục service](docs/architecture-v2/services.md) | 12 service + gateway |
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

# 4. CHỈ khi cần trợ lý AI (ai-chatbox-service) — hồ sơ riêng, ~6GB tải về
docker compose -f deploy/compose/infra.yml --profile ai up -d ollama
docker compose -f deploy/compose/infra.yml exec ollama ollama pull qwen2.5:7b-instruct
docker compose -f deploy/compose/infra.yml exec ollama ollama pull bge-m3
```

Bước 4 tách riêng vì hai lý do: ảnh cộng mô hình là ~6GB, và không có nó thì phần còn lại của hệ
thống vẫn chạy đủ — chỉ khung chat ở `/support` trả 503 "trợ lý đang bận". Đã cài Ollama thẳng trên
máy thì bỏ qua: service mặc định gọi `http://localhost:11434`.

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

19 module Maven (6 thư viện `platform/` + `api-gateway` + 12 service) đã dựng và **build xanh**,
với **440 test** và **36 luật ArchUnit**. Mỗi service là một bounded context: một database riêng,
một user database riêng, một bộ migration Flyway riêng (ADR-1002).

| Service | Nội dung | Test |
| --- | --- | --- |
| `identity` | Tổ chức, mời thành viên, RBAC theo ma trận quyền, thu hồi phiên, đặt lại mật khẩu qua Keycloak | 79 |
| `catalog` | Địa điểm, khu, **hình học mặt bằng** (GRID + ARC), khung concert dùng chung, sự kiện/suất, publish, ảnh bìa qua S3 | 99 |
| `inventory` | Giữ chỗ bằng Redis Lua, chống oversell, trần mua vé, materialize `session_seats` | 38 |
| `ordering` | Saga đặt vé, hạn thanh toán, bù trừ khi một chặng hỏng | 25 |
| `payment` | payOS: tạo yêu cầu, webhook đủ nhánh, chữ ký HMAC, đối soát, hết hạn | 44 |
| `ledger` | Sổ cái kép, bút toán N1, append-only do **database** ép chứ không do mã nguồn | 27 |
| `payout` | Lịch chi trả cho tổ chức, duyệt bốn mắt | 22 |
| `ticketing` | Phát vé, mã QR ký, ví vé, soát vé, tra cứu của ban tổ chức, ảnh vé | 42 |
| `ai-chatbox` | Trợ lý RAG + tool calling (Ollama hoặc Claude), chuyển sang người thật, kho tri thức soạn được | 48 |
| `analytics` | Read model doanh thu, consumer thuần | 10 |
| `notification` | Gửi email theo sự kiện, consumer thuần | 9 |
| `realtime-gateway` | WebSocket fan-out tồn kho ghế, gom 200ms một lần | 10 |
| `api-gateway` | Route, JWT, rate limit theo người dùng/IP, CORS, correlation id | — |

Trong 440 test có **260 integration test** chạy trên PostgreSQL, Redis, pgvector **thật** bằng
Testcontainers — không mock repository. Lý do: gần hết những gì hệ thống này hứa nằm trong SQL chứ
không nằm trong Java (partial unique index, ràng buộc CHECK, `UPDATE ... WHERE status`, quyền của
role), và mock repository thì test xanh trong khi tất cả những thứ đó chưa từng chạy.

Hạ tầng local (PostgreSQL + pgvector, Redis, RabbitMQ, Keycloak, Mailpit, MinIO, Ollama) và bộ
compose production đầy đủ: ✅. Bốn app Next.js (khách, tổ chức, nền tảng, soát vé) dùng chung
design token: ✅.

### Ba spike bắt buộc

Ba chỗ dồn rủi ro lớn nhất của dự án. **Cả ba đã xong**, mỗi cái có test chứng minh:

| Spike | | Bằng chứng |
| --- | --- | --- |
| Sổ cái luôn cân | ✅ | `LedgerInvariantIT` — ghi thẳng SQL để cố tình làm lệch sổ, database từ chối |
| Chống oversell (ngồi + đứng) | ✅ | `SeatHoldConcurrencyIT`, `OversellBackstopIT` — xem dưới |
| Webhook payOS đủ nhánh | ✅ | `PayosPaymentFlowIT` (trả đủ, trả thiếu, gọi lại, sai chữ ký), `PayosSignatureTest` |

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
