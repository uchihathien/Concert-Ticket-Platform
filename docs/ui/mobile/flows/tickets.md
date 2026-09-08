# Mobile flow — Tickets & orders

## TicketList

- Segment: Sắp tới | Đã dùng | Tất cả.
- Row: event title, session time, seat labels summary, status chip.
- Tap → TicketDetail.

## TicketDetail (gate)

```text
┌─────────────────────┐
│ Event title         │
│ Suất · Ghế A-1-01   │
│                     │
│    ┌─────────┐      │
│    │  QR     │      │
│    └─────────┘      │
│ Mã vé (jti short)   │
│ [Hiện đủ sáng]      │
└─────────────────────┘
```

- QR từ API token string; render local; không screenshot warning bắt buộc (Should).
- Status CHECKED_IN: QR vẫn hiện hoặc dim + stamp “Đã check-in” (chốt: **vẫn hiện** để đối soát cửa, kèm stamp).
- CANCELLED/REFUNDED: không hiện QR hợp lệ; copy trạng thái.

## OrderList / OrderDetail

| Status | UI |
| --- | --- |
| AWAITING_PAYMENT | Countdown nếu còn hạn; CTA tiếp tục CK |
| PAID | Link tới vé |
| EXPIRED | CTA chọn lại ghế |
| MANUAL_REVIEW | “Đang đối soát — chưa có vé” |
| REFUNDED | Badge |

Pull-to-refresh trên OrderDetail khi AWAITING.

## Email → app

| Email | Link |
| --- | --- |
| Order created | `/checkout/orders/{id}/pay` |
| Ticket issued | `/me/tickets/{id}` |

Resolve qua [deep-links.md](../deep-links.md).
