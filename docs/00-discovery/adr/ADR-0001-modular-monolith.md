# ADR-0001: Modular monolith theo domain

**Status:** Accepted

## Context

Seat, order và payment phải nhất quán giao dịch trong MVP. Microservices tạo thêm distributed transaction, vận hành và độ trễ khi chưa có bằng chứng cần tách.

## Decision

Dùng một Ticketing API deployable, chia boundary thành Identity, Catalog, Inventory, Orders, Payments, Tickets, Notifications và Analytics. Module chỉ giao tiếp qua application service hoặc integration event; không truy cập persistence internal của module khác.

## Consequences

Triển khai nhanh và giữ transaction local. Mỗi module phải có schema/table ownership rõ để có thể tách sau này.

## Validation

Architecture test cấm dependency ngược giữa modules; mọi cross-module async flow đi qua outbox.
