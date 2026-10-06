-- Tra cứu vé cho ban tổ chức: theo mã vé, tên khách, khu, trạng thái soát, trạng thái thanh toán.
--
-- ---------------------------------------------------------------------------
-- Vì sao tên khách được CHÉP vào đây
-- ---------------------------------------------------------------------------
-- Tên người mua là dữ liệu của identity-service, và cách "đúng" theo sách vở là hỏi sang đó lúc
-- đọc. Nhưng câu hỏi mà màn hình này phải trả lời là *lọc theo tên* — và không ai lọc được theo
-- một cột mình không có. Hỏi sang identity lúc đọc chỉ trả lời được "tên của những vé tôi đã lấy
-- ra", tức là phải lấy hết vé của cả sự kiện rồi mới lọc, và phân trang mất nghĩa.
--
-- Nên tên được chụp lại lúc phát vé, cùng lúc và cùng lý do với seat_code, ticket_type_name: vé
-- phải in ra đúng thứ khách đã mua. Khách đổi tên sau đó thì vé cũ vẫn mang tên cũ — đó là hành
-- vi đúng của một chứng từ, không phải dữ liệu lệch.
--
-- NULL là giá trị hợp lệ và có nghĩa riêng: identity không trả lời được lúc phát vé. Phát vé
-- KHÔNG được hỏng vì một cái tên — khách đã trả tiền, và một service phụ im lặng không phải là
-- lý do để họ không có vé.

ALTER TABLE tickets
    ADD COLUMN holder_name TEXT;

-- ---------------------------------------------------------------------------
-- Vì sao payment_status không thừa so với status
-- ---------------------------------------------------------------------------
-- Vé chỉ tồn tại khi đơn đã PAID, nên thoạt nhìn cột này luôn bằng 'PAID'. Nó vẫn cần, vì một vé
-- REVOKED có hai nguyên nhân hoàn toàn khác nhau: đơn đã hoàn tiền, hoặc ban tổ chức thu hồi vé
-- (gian lận, sai sót). Hai việc đó cần xử lý khác nhau và `status` một mình không phân biệt được.
--
-- Giá trị ở đây là BẢN CHỤP, và nó có thể cũ: nguồn chân lý là ordering-service. Hiện chưa có sự
-- kiện `order.refunded` nào được phát, nên không có gì đẩy thay đổi sang đây. Đường đọc tự vá lấy
-- — xem TicketSearchQuery: nó hỏi ordering trạng thái của đúng những đơn trên trang đang xem rồi
-- ghi đè lại dòng nào lệch. Bộ lọc chạy trên cột đã lưu, nên một dòng chưa ai mở tới có thể còn
-- cũ; nó tự đúng ngay lần đầu có người nhìn vào.
--
-- Đánh đổi ghi ra rõ ở đây để lần sau có người thêm `order.refunded` thì biết chỗ này chờ sẵn:
-- lúc đó listener chỉ cần UPDATE cột này và phần tự vá trở thành lưới an toàn thay vì cơ chế chính.
ALTER TABLE tickets
    ADD COLUMN payment_status TEXT NOT NULL DEFAULT 'PAID';

ALTER TABLE tickets
    ADD CONSTRAINT ck_ticket_payment_status
        CHECK (payment_status IN ('PAID', 'REFUNDED'));

-- ---------------------------------------------------------------------------
-- Index cho đường tra cứu
-- ---------------------------------------------------------------------------
-- Chỉ một index, và nó KHÔNG đánh trên phần chữ.
--
-- Bộ lọc luôn bắt đầu bằng một tổ chức, và thường thêm một sự kiện: đó là chỗ duy nhất có tính
-- chọn lọc thật. Sau khi đã thu về vé của một sự kiện thì tập còn lại là vài nghìn dòng, và ILIKE
-- trên vài nghìn dòng trong bộ nhớ là vài mili giây — rẻ hơn hẳn việc nuôi một index GIN trgm phải
-- cập nhật ở mỗi lần phát vé, tức là đúng lúc mở bán, đúng lúc hệ thống bận nhất.
--
-- Chỗ này sẽ sai nếu một ngày có người lọc theo tên trên PHẠM VI TOÀN TỔ CHỨC với hàng triệu vé.
-- Khi đó câu trả lời là pg_trgm, không phải thêm một index btree nữa.
CREATE INDEX idx_ticket_org_issued ON tickets (organization_id, issued_at DESC);
