# NexaTicket

Nền tảng bán vé sự kiện có chọn chỗ realtime, thanh toán VietQR, soát vé QR — thị trường Việt Nam.

Kiến trúc microservices + DDD, nền tảng giữ tiền. Toàn bộ thiết kế ở [`docs/architecture-v2/`](docs/architecture-v2/README.md).

## Repo chia đôi

| Thư mục | Nội dung | README |
| --- | --- | --- |
| [`backend/`](backend/) | 11 service Spring Boot, Maven multi-module | [backend/README.md](backend/README.md) |
| [`frontend/`](frontend/) | 4 app Next.js, pnpm workspace | [frontend/README.md](frontend/README.md) |
| `deploy/` | Hạ tầng dùng chung: compose, RabbitMQ topology, Keycloak realm | — |
| `scripts/` | Công cụ dùng chung | — |
| `docs/` | Tài liệu thiết kế | [docs/README.md](docs/README.md) |

Hai bên build độc lập, có CI riêng ([`backend.yml`](.github/workflows/backend.yml), [`frontend.yml`](.github/workflows/frontend.yml)) chạy theo path filter — sửa frontend không kích hoạt build backend và ngược lại.

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

# 3. Frontend (terminal khác)
cd frontend && corepack enable pnpm && pnpm install && pnpm dev
```

| Dịch vụ | Địa chỉ |
| --- | --- |
| Gateway | http://localhost:8080 |
| identity-service | http://localhost:8090 |
| Keycloak | http://localhost:8081 (`admin` / `admin`) |
| RabbitMQ UI | http://localhost:15672 (`nexaticket` / `nexaticket`) |
| Mailpit | http://localhost:8025 |
| web-customer · admin · scanner · platform | :3000 · :3001 · :3002 · :3003 |

Tài khoản dev trong realm: `superadmin`, `organizer`, `staff`, `customer` — mật khẩu trùng tên đăng nhập. Chỉ dùng cho local.

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

## Trạng thái: giai đoạn G0

| Thành phần | |
| --- | --- |
| Maven multi-module, 6 thư viện `platform/` | ✅ build xanh |
| `identity-service` — tạo tổ chức, mời thành viên, membership | ✅ lát cắt dọc đầy đủ |
| `api-gateway` — route, JWT, rate limit, correlation id | ✅ |
| Hạ tầng local: PostgreSQL, Redis, RabbitMQ, Keycloak, Mailpit | ✅ |
| 4 app Next.js + design token | ✅ khung |
| CI tách backend / frontend theo path filter | ✅ |
| **25 test** (21 unit + ArchUnit, 4 integration với PostgreSQL thật) | ✅ |
| catalog · inventory · ordering · payment · ledger · payout · ticketing | ⬜ G1–G6 |
| **3 spike bắt buộc**: hold Redis Lua, sổ cái cân bằng, webhook SePay | ⬜ |

Ba spike là chỗ dồn rủi ro lớn nhất của dự án — chi tiết ở [plan/backend.md §5](docs/architecture-v2/plan/backend.md).

## Quy tắc đóng góp

- Nhánh sống ≤ 3 ngày, squash merge. Conventional Commits, scope là tên service.
- Đổi contract ⇒ kèm diff trong `packages/api-contracts`, cùng PR.
- Đổi schema ⇒ migration Flyway **mới**; không sửa migration đã merge.
- Đụng ghế / tiền / vé ⇒ có integration test; không test thì không merge.
- Consumer mới ⇒ có test **thứ tự đảo** — RabbitMQ không bảo đảm thứ tự ([ADR-1009](docs/architecture-v2/adr/ADR-1009-rabbitmq-only.md)).
- Đụng `ledger-service` ⇒ 2 người review.

Đầy đủ: [plan/README.md §7](docs/architecture-v2/plan/README.md) và Definition of Done §10.
