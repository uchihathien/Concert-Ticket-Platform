-- analytics-service: read model cho dashboard.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.
--
-- RANH GIỚI CỨNG (ADR-1010): tổ chức chỉ thấy SỐ VÉ và SỐ TIỀN ĐÃ BÁN. Không hoa hồng,
-- không số dư, không lịch chi trả, không sổ cái. Vì vậy bảng ở đây CỐ Ý không có cột hoa
-- hồng — không phải vì quên, mà vì một cột tồn tại là một cột sẽ lọt ra API sau vài lần
-- sửa vội.

CREATE TABLE session_sales (
    event_session_id UUID PRIMARY KEY,
    event_id         UUID        NOT NULL,
    organization_id  UUID        NOT NULL,

    tickets_sold     INT         NOT NULL DEFAULT 0,
    gross_vnd        BIGINT      NOT NULL DEFAULT 0,

    orders_paid      INT         NOT NULL DEFAULT 0,
    orders_expired   INT         NOT NULL DEFAULT 0,
    orders_cancelled INT         NOT NULL DEFAULT 0,

    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()

    -- CỐ Ý KHÔNG có ràng buộc "không âm".
    --
    -- Đã thử CHECK (tickets_sold >= 0 AND gross_vnd >= 0) và nó sai ở hai chỗ:
    --
    -- 1. Nó giả định message đến ĐÚNG THỨ TỰ. RabbitMQ không bảo đảm điều đó (ADR-1009):
    --    order.refunded hoàn toàn có thể đến trước order.paid, và tổng chạy sẽ âm tạm thời
    --    trước khi về đúng. Đó chính là ý nghĩa của "không phụ thuộc thứ tự" — chỉ tổng cuối
    --    cùng mới phải đúng, các trạng thái trung gian thì không.
    --
    -- 2. Với ON CONFLICT DO UPDATE, PostgreSQL kiểm CHECK trên DÒNG ĐỀ XUẤT trước khi phát
    --    hiện đụng độ. Một delta hoàn tiền (-1, -1.500.000) vi phạm ngay cả khi kết quả cộng
    --    dồn cuối cùng là dương — tức là ràng buộc này phá luôn cả đường hoàn tiền bình
    --    thường, không chỉ trường hợp đảo thứ tự.
    --
    -- Nếu số cuối cùng âm thì đó là lỗi ở service phát sự kiện, và chỗ phát hiện đúng là một
    -- job kiểm bất biến chạy định kỳ, không phải một ràng buộc chặn ghi.
);

CREATE INDEX idx_sales_org   ON session_sales (organization_id);
CREATE INDEX idx_sales_event ON session_sales (event_id);

-- ---------------------------------------------------------------------------
-- Chống cộng trùng dùng bảng processed_events CÓ SẴN của starter-idempotency, không tạo
-- bảng riêng.
--
-- Read model được xây bằng phép CỘNG DỒN, và cộng dồn thì không tự idempotent: xử lý lại
-- một message order.paid sẽ cộng doanh thu lần thứ hai. RabbitMQ giao ít nhất một lần nên
-- điều đó chắc chắn xảy ra.
--
-- Bảng dùng chung khoá theo (consumer_queue, event_id) chứ không chỉ event_id, nên nhiều
-- consumer trong cùng một service dedupe độc lập với nhau — thứ mà một bảng riêng chỉ khoá
-- theo event_id sẽ làm hỏng: consumer thứ hai thấy sự kiện "đã xử lý" dù nó chưa từng thấy.
--
-- Bảng đó cũng là thứ làm consumer KHÔNG PHỤ THUỘC THỨ TỰ (ADR-1009): mỗi sự kiện đóng góp
-- đúng một lần, và tổng cuối cùng không đổi dù chúng đến theo thứ tự nào.
-- ---------------------------------------------------------------------------
