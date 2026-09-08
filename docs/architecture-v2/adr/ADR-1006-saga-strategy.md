# ADR-1006: Orchestration cho luồng tiền, choreography cho lan toả

**Status:** Accepted

## Context

Tách service làm mất transaction xuyên context. Cần chọn giữa orchestration (một nhạc trưởng giữ state machine) và choreography (mỗi service phản ứng với event). Chọn nhầm khiến sự cố lúc 3 giờ sáng không tra được đang kẹt ở đâu.

## Decision

Luồng có tiền hoặc cần bù trừ dùng **orchestration**: checkout (ordering-service), hoàn tiền (ordering-service), chi trả (payout-service). Luồng chỉ lan toả thông tin dùng **choreography**: xác nhận thanh toán, publish sự kiện.

Saga checkout dùng HTTP đồng bộ giữa các bước vì khách đang chờ mã QR trên màn hình; bất đồng bộ ở đây sẽ buộc phải hiển thị "đang xử lý" cho một thao tác vốn tức thì.

Mọi saga có bảng `saga_instances` với trạng thái và `business_key` unique, cùng một job dọn saga kẹt chạy mỗi 30 giây. Mọi consumer idempotent qua bảng `processed_events`.

## Consequences

Ordering-service dày hơn về điều phối — chấp nhận, đổi lấy khả năng quan sát. Một truy vấn SQL trả lời được cái gì đang kẹt.

Luồng xác nhận thanh toán là eventual consistency, nên frontend phải có trạng thái "đã thanh toán, đang phát hành vé". Đây là chi phí trực tiếp của microservices và phải xử lý ở UI, không được bỏ qua.

## Validation

Test chaos: giết inventory-service giữa saga checkout dưới tải. Kỳ vọng: không ghế kẹt `RESERVED` quá 15 phút, không đơn `AWAITING_PAYMENT` thiếu payment intent, sổ cái cân.
