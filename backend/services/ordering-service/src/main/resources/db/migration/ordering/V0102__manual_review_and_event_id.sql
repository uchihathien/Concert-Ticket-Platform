-- ordering-service: chỗ đậu cho tiền vào muộn, và id sự kiện trên đơn.

-- ---------------------------------------------------------------------------
-- MANUAL_REVIEW — tiền thật vào một đơn đã đóng.
--
-- Cuộc đua có thật: ExpireOrdersJob chạy mỗi 15 giây, còn link payOS sống tới đúng hạn thanh
-- toán của đơn. Một lần chuyển khoản ở phút chót được payOS xác nhận sau khi job vừa đóng đơn là
-- chuyện sẽ xảy ra, không phải giả định.
--
-- Trước migration này, đường đó kết thúc bằng IllegalStateException ở Order.markPaid → 500 →
-- payment-service rollback cả dòng bank_webhook_log vừa ghi → payOS giao lại mãi. Kết quả: khách
-- mất tiền, không có vé, và KHÔNG CÒN dấu vết nào trong nhật ký để lần ra.
--
-- Không chuyển sang PAID được: ghế đã nhả lúc đóng đơn và có thể đã bán cho người khác, nên phát
-- vé ở đây là hai người cùng một chỗ. Trạng thái riêng là cách duy nhất vừa giữ được sự thật
-- "tiền đã vào" vừa không phát vé — và nó khớp state machine đã mô tả ở
-- docs/03-seat-checkout/state-machines.md.
--
-- paid_at vẫn NULL ở trạng thái này, nên ck_paid_has_timestamp không cần đụng tới: mốc trả tiền
-- đi vào sổ cái, và một đơn chưa được công nhận đã trả tiền thì không được mang mốc đó.
-- ---------------------------------------------------------------------------
ALTER TABLE orders DROP CONSTRAINT ck_order_status;
ALTER TABLE orders ADD CONSTRAINT ck_order_status CHECK (
    status IN ('AWAITING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED', 'REFUNDED', 'MANUAL_REVIEW'));

-- Đơn cần người xem, tra bằng một câu. Partial index vì đây là trạng thái hiếm — và phải hiếm.
CREATE INDEX idx_order_manual_review ON orders (closed_at DESC) WHERE status = 'MANUAL_REVIEW';

-- ---------------------------------------------------------------------------
-- event_id: sự kiện của suất diễn.
--
-- Ordering không dùng tới, nhưng analytics thì có: read model khoá theo suất diễn và cột
-- session_sales.event_id là NOT NULL. Không có nó trong payload order.paid thì consumer doanh thu
-- không ghi được dòng nào — đó là lý do trang "Doanh thu" của ban tổ chức luôn bằng 0.
--
-- Lấy từ Inventory lúc đặt chỗ (nó đã giữ event_id trong session_inventory) chứ không gọi Catalog:
-- saga checkout không được thêm một chặng mạng vào đúng chỗ khách đang chờ màn hình.
--
-- NULL được, vì những đơn có sẵn trong database không có giá trị này và không có cách nào suy ra
-- mà không gọi Catalog cho từng dòng. Consumer bỏ qua message thiếu eventId thay vì chết.
-- ---------------------------------------------------------------------------
ALTER TABLE orders ADD COLUMN event_id UUID;
