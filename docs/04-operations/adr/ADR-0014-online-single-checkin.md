# ADR-0014: Check-in online, duy nhất một lần

**Status:** Accepted

Scanner phải online. Endpoint check-in transactionally chuyển ticket `VALID` sang `CHECKED_IN` và ghi `check_ins` với unique ticket constraint. Lần scan đầu trả `CHECKED_IN`; lần sau không ghi thêm check-in, trả `ALREADY_CHECKED_IN` cùng thông tin vé đã được phép hiển thị. Không hỗ trợ offline reconciliation trong MVP.
