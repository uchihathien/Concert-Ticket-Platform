# ADR-0005: Transactional outbox

**Status:** Accepted

Khi transaction đổi state nghiệp vụ, ghi integration event vào `outbox` cùng transaction. Worker publish event tới RabbitMQ và đánh dấu đã dispatch idempotently. Không publish broker trước khi database commit.
