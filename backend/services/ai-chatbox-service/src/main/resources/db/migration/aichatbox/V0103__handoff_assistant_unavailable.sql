-- Thêm một lý do chuyển người thật: TRỢ LÝ KHÔNG DÙNG ĐƯỢC.
--
-- Hai lý do cũ đều giả định trợ lý đang chạy: khách tự đòi gặp người (CUSTOMER_REQUEST), hoặc trợ lý
-- trả lời nhưng không chắc (LOW_CONFIDENCE). Không có lý do nào cho trường hợp trợ lý KHÔNG trả lời
-- được gì cả — nhà cung cấp mô hình chưa cấu hình, hết hạn mức, hoặc đang sự cố.
--
-- Trước đây trường hợp đó kết thúc bằng 503 và không để lại dấu vết nào: câu hỏi của khách KHÔNG được
-- lưu (CustomerSupportAgentUseCase chỉ ghi lịch sử sau khi mô hình trả lời xong), nên phiên chat
-- không tồn tại, nút "gặp nhân viên" trả 404 vì không có phiên để mở phiếu, và bàn hỗ trợ không bao
-- giờ biết có người vừa hỏi. Khách gặp một cánh cửa đóng, không phải một hàng chờ.
--
-- Tách thành giá trị riêng thay vì dùng lại LOW_CONFIDENCE: hai thứ cần hai hành động vận hành khác
-- nhau. LOW_CONFIDENCE nhiều nghĩa là kho tri thức thiếu; ASSISTANT_UNAVAILABLE nhiều nghĩa là hạ
-- tầng mô hình đang hỏng. Gộp chung thì cả hai tín hiệu cùng mất.
ALTER TABLE chat_handoffs
    DROP CONSTRAINT ck_handoff_trigger;

ALTER TABLE chat_handoffs
    ADD CONSTRAINT ck_handoff_trigger CHECK (trigger_kind IN
        ('CUSTOMER_REQUEST', 'LOW_CONFIDENCE', 'ASSISTANT_UNAVAILABLE'));
