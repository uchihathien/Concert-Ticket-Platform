# ADR-1002: Database per service

**Status:** Accepted — sửa đổi ADR-0003

## Context

ADR-0003 giữ PostgreSQL làm source of record, một instance cho toàn hệ thống, mỗi module sở hữu bảng của mình. Với microservices, "sở hữu bảng" không đủ: chỉ cần một service đọc trực tiếp bảng của service khác là toàn bộ lợi ích của việc tách biến mất, và ta có một monolith phân tán — thứ tệ hơn cả monolith lẫn microservices.

## Decision

Mỗi service có database riêng, credential riêng, migration riêng. Không service nào có quyền đọc database của service khác, kể cả quyền chỉ đọc.

PostgreSQL vẫn là source of record ở mọi nơi. Redis vẫn chỉ là phối hợp/cache, không phải nguồn chân lý — giữ nguyên tinh thần ADR-0003.

Dữ liệu cần dùng chéo context được sao chép qua integration event (event-carried state transfer), không qua truy vấn trực tiếp. Ví dụ: inventory-service giữ bản sao `organization_id`, nhãn ghế và giá của suất diễn để trả seat map mà không phải gọi catalog-service ở 10k đồng thời.

Vận hành: MVP dùng một cụm PostgreSQL với nhiều database logic tách bằng quyền — đủ cô lập, rẻ hơn nhiều instance. Riêng `ledger_db` tách instance vật lý khi lên production.

## Consequences

Bản sao dữ liệu là chấp nhận được và có chủ đích; nguồn chân lý vẫn duy nhất. Báo cáo xuyên context không JOIN được nên analytics-service tổng hợp từ event. Nhất quán giữa các context là eventual; mọi màn hình phải chịu được điều đó.

## Validation

Kiểm tra quyền: user DB của mỗi service chỉ thấy schema của mình. Test tự động thử kết nối chéo và kỳ vọng bị từ chối.
