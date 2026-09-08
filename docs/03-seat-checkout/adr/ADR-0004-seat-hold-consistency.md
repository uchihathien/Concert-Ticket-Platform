# ADR-0004: Redis Lua hold, database reservation là guard cuối

**Status:** Accepted

Hold seat dùng một Redis Lua script để atomically xác minh toàn bộ seat và ghi key TTL 5 phút. Cùng request phải persist `seat_holds` ACTIVE. Redis lỗi trả retryable error, không fallback sang update database không phối hợp.

Khi tạo order, backend revalidate owner/TTL rồi transactionally reserve seat bằng `SELECT ... FOR UPDATE` và availability predicate. Expiry worker và synchronous check cùng bảo vệ against hold hết hạn.
