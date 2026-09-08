# Success metrics (MVP 3 tháng)

## North-star

**Số ghế thanh toán thành công / tuần** trên staging pilot rồi production soft launch.

## Product metrics

| Metric | Định nghĩa | Mục tiêu soft launch |
| --- | --- | --- |
| Checkout conversion | `PAID orders / created orders` | ≥ 60% (sandbox + pilot thật) |
| Hold → order rate | `orders / holds` | ≥ 40% |
| Time-to-ticket | median từ hold → ticket visible | ≤ 10 phút (gồm chuyển khoản user) |
| Check-in success | first-scan `CHECKED_IN` / valid tickets presented | ≥ 98% |
| Support exceptions | `MANUAL_REVIEW` / paid+review volume | ≤ 5% |

## Reliability / SLO

| SLO | Target | Đo |
| --- | --- | --- |
| API availability | 99.9% | Synthetic + uptime probe |
| Seat-hold p95 | ≤ 300 ms | OTel histogram |
| Oversell count | 0 | Invariant job + load test |
| Webhook processing success | ≥ 99% (excl. invalid auth) | SePay adapter metrics |
| RPO | ≤ 5 min | Backup restore drill |

## Analytics events (instrument từ tuần 7)

`impression`, `search`, `event_view`, `favorite`, `seat_hold`, `order_created`, `payment_confirmed`, `payment_rejected`, `refund_completed`, `ticket_issued`, `check_in`.

Consent: chỉ gắn user id khi đã login + policy privacy; anonymous session id riêng.

## Review cadence

- Weekly: conversion, hold errors, webhook failures, open MANUAL_REVIEW.
- Milestone exit: bảng Go/No-go trong [DELIVERY-PLAN-3M.md](../DELIVERY-PLAN-3M.md).
