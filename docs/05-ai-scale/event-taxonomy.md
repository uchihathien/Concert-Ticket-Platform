# Analytics event taxonomy

Mọi event: `event_name`, `occurred_at`, `analytics_subject_id` (nullable nếu anonymous), `session_id` (client), `organization_id` / `event_id` / `event_session_id` khi có, `payload` JSON không PII.

| event_name | Khi nào | Payload chính |
| --- | --- | --- |
| `impression` | Card event hiện viewport | `event_id`, `position` |
| `search` | Submit search | `query`, `filters`, `result_count` |
| `event_view` | Mở detail | `event_id`, `slug` |
| `favorite` | Lưu yêu thích (nếu có) | `event_id` |
| `seat_hold` | Hold thành công | `session_id`, `seat_count` |
| `order_created` | Tạo order | `order_id`, `total_vnd` |
| `payment_confirmed` | Webhook CONFIRMED | `order_id` |
| `payment_rejected` | Reject / mismatch | `reason_code` |
| `ticket_issued` | Issue | `ticket_count` |
| `refund_completed` | Refund marked | `order_id` |
| `check_in` | First successful scan | `event_session_id` |

## Rules

- Không đưa email/phone/seat owner name vào payload analytics.
- Emit sau commit nghiệp vụ (outbox), không best-effort trước commit.
- Recommendation jobs (post-MVP) chỉ đọc taxonomy này.
