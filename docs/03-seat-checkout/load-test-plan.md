# Load & concurrency test plan (Milestone 03 gate)

## Mục tiêu

Chứng minh **0 oversell** và p95 hold trong SLO dưới tải gần thực tế soft launch.

## Scenario chính

| Tham số | Giá trị |
| --- | --- |
| Concurrent users (VU) | 10.000 |
| Target | 1 hot `event_session` |
| Seat block | N ghế popular (ví dụ 200–500) |
| Action mix | 70% GET seats, 20% POST hold (1–2 seats), 10% release/order |
| Tool | k6 hoặc Gatling |
| Env | staging sized gần prod |

## Assertions

1. Mỗi `session_seat` kết thúc với tối đa một trong: SOLD hoặc RESERVED hợp lệ; không hai order PAID cùng seat.
2. Invariant job: `COUNT(*) … GROUP BY session_seat_id HAVING count > 1` = 0 cho holds ACTIVE chồng chéo.
3. p95 `POST /holds` ≤ 300 ms (loại trừ artificial client think time).
4. Sau kill Redis: API trả 503 retryable cho hold; không silent DB-only hold.
5. Sau restart worker: expiry catch-up; không ghế HELD vĩnh viễn.
6. SePay duplicate delivery: một ticket set.

## Chaotic cases (tuần 10)

- Webhook đến cùng lúc payment expiry worker.
- Idempotency-Key replay song song 100 requests.
- WS fan-out khi version tăng nhanh — client refetch không storm (debounce).

## Báo cáo exit

File `docs/03-seat-checkout/load-test-report.md` (điền sau chạy): VU, duration, p50/p95/p99, oversell count, errors, conclusion Pass/Fail.
