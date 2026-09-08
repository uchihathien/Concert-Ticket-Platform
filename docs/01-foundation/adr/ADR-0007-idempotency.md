# ADR-0007: Idempotency cho mutation và webhook

**Status:** Accepted

Mọi mutation client yêu cầu `Idempotency-Key`, lưu fingerprint và response tối thiểu 24 giờ. External webhook dùng provider transaction/event ID unique cùng payload hash. Retry không được tạo hold, order, payment hoặc ticket trùng.
