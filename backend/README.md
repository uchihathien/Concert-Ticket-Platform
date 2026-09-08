# NexaTicket — Backend

11 service Spring Boot theo kiến trúc microservices + DDD. Thiết kế: [`docs/architecture-v2/`](../docs/architecture-v2/README.md) · Plan: [`plan/backend.md`](../docs/architecture-v2/plan/backend.md).

## Yêu cầu

JDK 21 · Docker (cho Testcontainers và hạ tầng local). **Không cần cài Maven** — repo có wrapper.

## Chạy

```bash
# Hạ tầng dùng chung, chạy từ thư mục gốc của repo
docker compose -f ../deploy/compose/infra.yml up -d --wait
../scripts/apply-rabbitmq-topology.sh

# Build đầy đủ: lint + unit test + ArchUnit + integration test
./mvnw -B verify

# Bỏ qua integration test (không cần Docker)
./mvnw -B verify -DskipITs

# Chạy một service
./mvnw -pl services/identity-service spring-boot:run
```

Trên Git Bash cần trỏ `JAVA_HOME`:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
```

## Cấu trúc

```text
platform/                thư viện dùng chung — CHỈ hạ tầng kỹ thuật, cấm nghiệp vụ
  shared-kernel/         Money, Role, TenantId, CorrelationContext, TimeWindow (không dependency ngoài JDK)
  starter-web/           model lỗi RFC 7807 + code, CorrelationIdFilter
  starter-security/      TenantContext, TenantFilter, @PublicEndpoint, @CrossTenantQuery
  starter-outbox/        outbox là nhật ký sự kiện (ADR-1009): bảng, writer, publisher
  starter-idempotency/   Idempotency-Key + processed_events
  starter-test/          luật ArchUnit dùng chung

services/
  api-gateway/           route, JWT, rate limit, correlation id — không có business logic
  identity-service/      tổ chức, thành viên, lời mời, mã truy cập soát vé
  _template/             khuôn cho service mới
```

Mỗi service: **1 bounded context = 1 database = 1 pipeline**.

## Ranh giới cứng

Ba luật này chạy như test thường ở mọi service (`ArchitectureRules`):

| Luật | Ý nghĩa |
| --- | --- |
| `domainIsFrameworkFree` | `domain` không được biết Spring hay JPA — nếu không, mô hình dữ liệu sẽ lấn mô hình nghiệp vụ |
| `hexagonalLayers` | `interfaces` không chạm thẳng `domain`; controller đi qua `application`, kể cả đường đọc |
| `noCrossContextImports` | Không service nào import class của context khác. **Cùng repo không có nghĩa là được import chéo** |

`platform/` được phép chứa: model lỗi, tenant, outbox, idempotency, value object kỹ thuật.
`platform/` **bị cấm** chứa: entity nghiệp vụ, DTO dùng chung giữa hai service, enum nghiệp vụ.

## Thêm service mới

```bash
../scripts/new-service.sh catalog 8091
```

Sinh cấu trúc hexagonal, POM, `application.yml`, lớp Application, `ArchitectureTest`, và thêm module vào `services/pom.xml`. Bốn việc phải làm tay được in ra ở cuối.

## Trạng thái

| | |
| --- | --- |
| Module | 11, build xanh |
| Test | 25 (21 unit + ArchUnit, 4 integration với PostgreSQL thật) |
| Giai đoạn | G0 — nền |
| Chưa làm | catalog · inventory · ordering · payment · ledger · payout · ticketing (G1–G6) |

## Xử lý sự cố

### `failed to discover tests` trên Windows

Hai nguyên nhân, cả hai đã xử lý sẵn trong `pom.xml`:

1. Repo ở ổ khác ổ chứa thư mục temp (`F:` vs `C:`) → Surefire relativize hai đường dẫn khác ổ và ném `'other' has different root`. Xử lý bằng `useSystemClassLoader=false`.
2. `spring-boot-maven-plugin:repackage` tạo fat jar, class nằm dưới `BOOT-INF/classes` nên failsafe không thấy gì. Xử lý bằng `<classesDirectory>${project.build.outputDirectory}</classesDirectory>`.

### `Could not find a valid Docker environment`

Docker Engine 25+ đặt `MinAPIVersion = 1.40` và từ chối bản thấp hơn bằng **HTTP 400 không kèm thông báo**. docker-java bên trong Testcontainers mặc định thương lượng **1.32**, nên mọi container đều hỏng với thông báo gây hiểu nhầm là "không tìm thấy Docker".

Đã ép sẵn trong `pom.xml`:

```xml
<docker.api.version>1.41</docker.api.version>
<argLine>-Dapi.version=${docker.api.version}</argLine>
```

Kiểm tra nhanh giả thuyết này trên máy bạn:

```bash
DOCKER_API_VERSION=1.32 docker info    # 400 Bad Request
DOCKER_API_VERSION=1.41 docker info    # OK
```

`services/identity-service/src/test/resources/logback-test.xml` đặt `org.testcontainers.dockerclient` ở mức `TRACE` — nó in ra từng chiến lược dò Docker và lý do loại.
