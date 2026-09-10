# ADR — Kiến trúc v2

| ADR | Quyết định | Thay thế |
| --- | --- | --- |
| [ADR-1001](ADR-1001-microservices.md) | Microservices theo bounded context | ADR-0001 |
| [ADR-1002](ADR-1002-database-per-service.md) | Database per service | Sửa đổi ADR-0003 |
| ~~[ADR-1003](ADR-1003-kafka-event-backbone.md)~~ | ~~Kafka làm trục sự kiện~~ | **Superseded bởi ADR-1009** |
| [ADR-1004](ADR-1004-custodial-funds.md) | NexaTicket giữ tiền | ADR-0013 |
| [ADR-1005](ADR-1005-double-entry-ledger.md) | Sổ cái kép append-only | — (mới) |
| [ADR-1006](ADR-1006-saga-strategy.md) | Orchestration cho luồng tiền | — (mới) |
| ~~[ADR-1007](ADR-1007-self-service-organizations.md)~~ | ~~Tự tạo tổ chức, KYC chặn ở payout~~ | **Superseded bởi ADR-1010** |
| [ADR-1008](ADR-1008-scanner-access-codes.md) | Mã truy cập soát vé phạm vi hẹp | — (mới) |
| [ADR-1009](ADR-1009-rabbitmq-only.md) | **Chỉ RabbitMQ; outbox là nhật ký sự kiện** | ADR-1003 |
| [ADR-1010](ADR-1010-superadmin-tenancy-and-finance-visibility.md) | **Superadmin sở hữu tenancy + toàn bộ tài chính** | ADR-1007 |
| [ADR-1011](ADR-1011-shared-venue-fixed-and-flexible-zones.md) | **Địa điểm dùng chung; zone cố định vs linh hoạt** | Mô hình seat map v1 |
| [ADR-1012](ADR-1012-standing-admission-inventory.md) | **Vé đứng: đơn vị tồn kho ảo + `SKIP LOCKED`** | — (mới) |
| [ADR-1013](ADR-1013-venue-schedule-conflict-warning.md) | **Trùng lịch địa điểm: cảnh báo, không chặn** | — (mới) |
| [ADR-1014](ADR-1014-configurable-purchase-limits.md) | **Trần mua vé cấu hình theo tổ chức** | — (mới) |
| [ADR-1015](ADR-1015-redis-fail-mode.md) | **Redis chết: giữ chỗ ĐÓNG, rate limit MỞ, phiên ĐÓNG** | Mở rộng ADR-0004 |

Vẫn hiệu lực từ v1: ADR-0002, 0004, 0005, **0006 (RabbitMQ — khôi phục)**, 0007, 0009, 0010, 0011, 0012, 0014, 0015, 0016.
