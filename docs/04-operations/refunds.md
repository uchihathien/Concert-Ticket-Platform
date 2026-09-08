# Refund & manual payment resolve

## Principles

1. Không `UPDATE` trực tiếp status bằng SQL trên production.
2. Mọi resolve qua use case có permission `ORG_ADMIN+` + audit before/after.
3. Tiền hoàn trả thực hiện **ngoài hệ thống** (chuyển khoản ngược); hệ thống ghi nhận trạng thái.

## Cases

| Case | Hệ thống |
| --- | --- |
| Khách hủy trước PAID | Cancel order nếu còn AWAITING; release seats |
| Webhook muộn đúng tiền | `MANUAL_REVIEW` → admin **Confirm pay** → PAID + issue tickets **hoặc** mark REFUNDED sau khi hoàn tiền ngoài |
| Sai tiền / sai reference | REJECTED / MANUAL_REVIEW; không issue |
| Refund sau PAID, chưa check-in | `orders=REFUNDED`, tickets CANCELLED/REFUNDED, seats AVAILABLE (hoặc BLOCKED) |
| Refund sau check-in | Chỉ financial mark; ghế không bán lại; ticket giữ CHECKED_IN lịch sử |

## API (admin)

| Method | Path | Action |
| --- | --- | --- |
| POST | `/admin/payments/{attemptId}/resolve` | `{ decision: "CONFIRM_PAID" \| "MARK_REFUNDED" \| "REJECT", reason }` |
| POST | `/admin/orders/{id}/refund` | `{ reason }` — chỉ PAID + policy |

Chi tiết vận hành: [runbooks/payment-reconciliation.md](runbooks/payment-reconciliation.md).
