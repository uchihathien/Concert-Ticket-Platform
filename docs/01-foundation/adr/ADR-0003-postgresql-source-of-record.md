# ADR-0003: PostgreSQL là source of record

**Status:** Accepted

PostgreSQL lưu state giao dịch, price snapshot, payment attempt, ticket và audit data. Redis chỉ dùng cache/coordination có thể tái tạo; không phải nguồn chân lý. Mỗi module sở hữu schema/tables của mình trong một PostgreSQL instance MVP.

`session_seats` là snapshot theo event session. Unique `(event_session_id, seat_id)` và `tickets.qr_jti` là invariant database.
