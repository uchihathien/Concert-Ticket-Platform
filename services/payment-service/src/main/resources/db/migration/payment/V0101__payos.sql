-- payment-service: chuyển luồng thu tiền từ SePay sang payOS (ADR-0016).
--
-- Điều gì thay đổi về bản chất:
--
--   Trước  — nền tảng tự dựng chuỗi EMVCo trỏ về MỘT tài khoản ký quỹ cố định, nội dung chuyển khoản
--            là `reference` ngẫu nhiên, và SePay đọc sao kê rồi gửi về nguyên văn nội dung đó. Việc
--            đối soát là "rút mã đơn ra khỏi một câu chữ do khách gõ" — tức là phụ thuộc vào việc
--            khách gõ đúng.
--   Sau    — payOS cấp một TÀI KHOẢN ẢO RIÊNG cho từng link thanh toán và tự dựng mã QR. Webhook trả
--            về `orderCode` — một số nguyên do CHÍNH TA cấp lúc tạo link, nằm trong payload đã ký
--            HMAC-SHA256. Không còn nhánh "khách gõ thiếu một ký tự".
--
-- Hệ quả lên schema: cần lưu lại mọi thứ payOS trả về cho một link, và cần một nguồn cấp `orderCode`
-- không bao giờ trùng.

-- ---------------------------------------------------------------------------
-- Nguồn cấp orderCode.
--
-- payOS TỪ CHỐI một orderCode đã dùng, nên chuỗi số này phải không trùng giữa nhiều instance, qua
-- restart, và qua cả những lần tạo link hỏng giữa đường. Một sequence là thứ duy nhất thoả cả ba mà
-- không cần khoá phân tán.
--
-- Vì sao bắt đầu từ 1.000.000: nội dung chuyển khoản được suy ra từ orderCode theo dạng `NT` + 7 chữ
-- số, và payOS giới hạn trường `description` ở 9 ký tự với tài khoản chưa liên kết. Bắt đầu từ bảy
-- chữ số giữ cho chuỗi đó có độ dài CỐ ĐỊNH — không có lúc 3 chữ số lúc 7 — và trần là 9.999.999.
-- Chạm trần thì PaymentReference.forOrderCode NÉM LỖI chứ không lặng lẽ sinh chuỗi 10 ký tự: một
-- description 10 ký tự làm payOS từ chối MỌI lần tạo link sau đó, và đó là thứ phải nổ to.
--
-- Sequence không lùi khi rollback, nên mỗi lần tạo link hỏng bỏ trống một số. Đó là điều mong muốn:
-- dùng lại số của một lần hỏng là cách chắc chắn nhất để gặp "orderCode đã tồn tại".
-- ---------------------------------------------------------------------------
CREATE SEQUENCE payment_order_code_seq
    AS BIGINT
    START WITH 1000000
    MINVALUE 1000000
    MAXVALUE 9999999
    NO CYCLE;

-- ---------------------------------------------------------------------------
-- Thông tin link payOS, lưu theo từng intent.
--
-- Lưu chứ không hỏi lại payOS mỗi lần đọc, cùng lý lẽ với snapshot mã QR đã có từ V0100: tài khoản ảo
-- gắn với một link là cố định, còn cấu hình kênh thanh toán thì đổi được. Mã khách đã chụp màn hình
-- phải quét ra đúng tài khoản cũ. Và mở lại trang đơn hàng không nên phụ thuộc payOS còn sống.
--
-- Cả ba cột NULL được, không phải vì thiết kế cho phép thiếu mà vì schema này chạy trên database đã có
-- dữ liệu thời SePay. Mọi intent mở từ ADR-0016 trở đi đều có đủ ba; xem phần UPDATE ở cuối file về
-- những dòng cũ.
-- ---------------------------------------------------------------------------
ALTER TABLE payment_intents
    -- Khoá đối soát với payOS. Webhook trả đúng số này về.
    ADD COLUMN payos_order_code      BIGINT,
    -- Định danh chuỗi của link phía payOS; dùng khi tra cứu và khi webhook không có mã giao dịch ngân hàng.
    ADD COLUMN payos_payment_link_id TEXT,
    -- Trang thanh toán payOS host. Đây là thay đổi lớn nhất với người dùng cuối: khách không còn buộc
    -- phải tự quét QR và tự gõ nội dung chuyển khoản, họ bấm vào đây và payOS dẫn đi.
    ADD COLUMN checkout_url          TEXT,
    -- Tên chủ tài khoản ảo, để khách đối chiếu trước khi bấm chuyển. Trước đây không cần vì tài khoản
    -- nhận là một tài khoản cố định của nền tảng; giờ mỗi link một tài khoản ảo khác nhau, và một số
    -- tài khoản lạ không kèm tên là thứ làm người ta do dự đúng ở bước cuối.
    ADD COLUMN bank_account_name     TEXT;

-- Một orderCode chỉ thuộc đúng một intent. Partial index vì các dòng thời SePay không có.
--
-- Đây là chốt chặn cho một lỗi cụ thể: hai intent cùng payos_order_code nghĩa là một webhook "đã trả
-- tiền" không biết thuộc đơn nào, và cả hai đơn đều có thể được phát vé từ một lần chuyển tiền.
CREATE UNIQUE INDEX uq_payos_order_code ON payment_intents (payos_order_code)
    WHERE payos_order_code IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Nhật ký webhook: thêm orderCode.
--
-- Tách khỏi cột `reference` chứ không dùng chung, vì orderCode có mặt KỂ CẢ KHI không tìm được intent
-- nào — và đúng những lần đó mới là lúc cần nó nhất. Một dòng UNKNOWN_REFERENCE không có orderCode là
-- một dòng nhật ký không trả lời được câu hỏi nào.
-- ---------------------------------------------------------------------------
ALTER TABLE bank_webhook_log ADD COLUMN payos_order_code BIGINT;

CREATE INDEX idx_webhook_payos_order_code ON bank_webhook_log (payos_order_code, received_at DESC)
    WHERE payos_order_code IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Đóng các intent thời SePay còn đang chờ tiền.
--
-- Chúng KHÔNG BAO GIỜ xác nhận được nữa: endpoint /api/billing/bank/webhook/sepay đã không còn, nên
-- không có nguồn nào báo tiền về cho chúng. Để nguyên PENDING thì mã QR cũ vẫn quét ra được, khách vẫn
-- chuyển tiền vào tài khoản ký quỹ, và không gì trong hệ thống ghi nhận — trường hợp tệ nhất của cả
-- luồng, vì nó chỉ lộ ra khi khách gọi lên khiếu nại.
--
-- Đánh EXPIRED là nói thật về tình trạng của chúng. Đơn hàng tương ứng sẽ tự hết hạn qua
-- ExpireOrdersJob của ordering-service và ghế được nhả lại, đúng đường bù trừ đã có.
--
-- Tiền đã vào thì KHÔNG đụng tới: điều kiện status = 'PENDING' loại hết CONFIRMED.
-- ---------------------------------------------------------------------------
UPDATE payment_intents
   SET status = 'EXPIRED'
 WHERE status = 'PENDING'
   AND payos_order_code IS NULL;
