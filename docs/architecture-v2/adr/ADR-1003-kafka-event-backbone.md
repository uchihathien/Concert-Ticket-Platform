# ADR-1003: Kafka làm trục sự kiện

**Status:** ~~Accepted~~ → **SUPERSEDED bởi [ADR-1009](ADR-1009-rabbitmq-only.md)**

> Chủ dự án yêu cầu chỉ dùng RabbitMQ. Lập luận "cần Kafka để replay sổ cái" ở dưới đã được ADR-1009
> đánh giá lại và bác bỏ: sổ cái là bản ghi gốc append-only trong PostgreSQL, không dựng lại từ
> event stream. Nhu cầu replay được đáp ứng bằng cách giữ bảng `outbox` vĩnh viễn.
> Giữ tài liệu này để ghi lại lý do đã cân nhắc.

## Context

ADR-0006 chọn RabbitMQ cho MVP và nói sẽ chuyển Kafka khi có bằng chứng về replay, history hoặc throughput. Hai thay đổi tạo ra bằng chứng đó:

1. Sổ cái tài chính cần **dựng lại được** trạng thái từ lịch sử sự kiện khi đối soát phát hiện sai lệch. RabbitMQ xoá message sau khi ack — không có lịch sử để dựng lại.
2. Với 11 service, một sự kiện thường có nhiều consumer độc lập, mỗi bên có nhịp xử lý riêng. Mô hình log giữ vị trí đọc riêng cho từng consumer group phù hợp hơn mô hình hàng đợi.

## Decision

Kafka (hoặc Redpanda nếu muốn nhẹ hơn về vận hành) làm trục sự kiện. Tên topic theo `nexaticket.<context>.<aggregate>.<event>.v<N>`. Schema registry với chính sách tương thích backward. Partition key theo aggregate id để bảo đảm thứ tự trong cùng một thực thể.

Giữ nguyên transactional outbox (ADR-0005) ở mọi service: không publish trước khi database commit.

Retention: topic tài chính giữ tối thiểu 1 năm; topic khác 30 ngày; `availability.changed` giữ 1 giờ vì tần suất cao và giá trị ngắn hạn.

## Consequences

Chi phí vận hành cao hơn RabbitMQ rõ rệt — cần người hiểu Kafka. Đổi lại: replay được, thêm consumer mới không ảnh hưởng consumer cũ, và có lịch sử sự kiện phục vụ kiểm toán tài chính.

Nếu đội không có năng lực vận hành Kafka, dùng dịch vụ Kafka managed thay vì tự dựng. Đây không phải chỗ để tiết kiệm.

## Validation

Test: giết broker giữa lúc xử lý thanh toán, khôi phục, xác nhận không mất event và không phát hành vé trùng. Diễn tập replay: dựng lại toàn bộ sổ cái từ topic `payment.*` trên môi trường staging.
