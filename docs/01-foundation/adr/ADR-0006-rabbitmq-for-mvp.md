# ADR-0006: RabbitMQ cho async MVP

**Status:** Accepted

RabbitMQ phục vụ notification, hold/order expiry, analytics projection và outbox consumers. Chuyển Kafka chỉ khi replay/history, throughput hoặc analytics volume chứng minh RabbitMQ không còn phù hợp.
