# Notifications (MVP)

## Channels

| Channel | MVP |
| --- | --- |
| Email | Must — Mailgun/SES/SMTP |
| SMS | Could — cắt nếu trễ |
| Push | Out |

## Triggers

| Event | Template |
| --- | --- |
| `OrderCreated` | Hướng dẫn chuyển khoản + reference + hạn 15 phút |
| `OrderPaid` / `TicketIssued` | Link my-tickets |
| `OrderExpired` | Ghế đã mở lại |
| `ManualReviewOpened` | Email nội bộ org admin |

## Delivery

1. Outbox → RabbitMQ `notification.requested`.
2. Worker render template + send.
3. Retry exponential; sau N lần → DLQ + alert.
4. Không gửi marketing transactional nhầm; respect opt-out chỉ với marketing.

## PII

Không log full email body có PII không cần thiết; correlation id + template id + user id.
