-- Đề xuất đặt vé do trợ lý AI lập, chờ khách bấm xác nhận (story 1.1, ADR-2001).
--
-- ---------------------------------------------------------------------------
-- Vì sao là một BẢNG riêng
-- ---------------------------------------------------------------------------
-- Trợ lý KHÔNG được tự đặt vé: nó lập một đề xuất (sự kiện, suất, khu, số vé, tổng tiền), khách bấm
-- "Đồng ý" trên thẻ thì endpoint xác nhận mới giữ chỗ và tạo đơn. Đề xuất sống 10 phút, có vòng đời
-- (PROPOSED → CONFIRMED / EXPIRED / CANCELLED), và cần đếm được ("bao nhiêu đề xuất được xác nhận")
-- để biết tính năng có đáng không. Nhét nó vào chat_messages dưới dạng JSON là mất cả ba thứ đó.
--
-- Bảng này cũng là nơi DUY NHẤT giữ user_id của đề xuất: endpoint xác nhận đối chiếu userId trong
-- JWT với cột này — khách không xác nhận được đề xuất của người khác dù biết id.
--
-- ---------------------------------------------------------------------------
-- Vì sao chat_session_id KHÔNG có khoá ngoại sang chat_sessions
-- ---------------------------------------------------------------------------
-- Dòng chat_sessions chỉ ra đời ở CUỐI lượt chat (khi ghi tin nhắn), còn tool lập đề xuất chạy GIỮA
-- vòng ReAct — ở lượt đầu tiên của một phiên, dòng cha chưa tồn tại và một FK sẽ vỡ đúng như đã vỡ
-- với chat_handoffs. Phiếu chuyển tiếp giải quyết bằng cách ghi lượt chat trước phiếu trong cùng
-- transaction; đề xuất không có lựa chọn ấy vì câu trả lời của mô hình chưa có lúc tool chạy.
-- Giữ cột để tra cứu, bỏ FK; dọn theo created_at do job retention (story 5.1) lo.
CREATE TABLE booking_proposals (
    id                 UUID PRIMARY KEY,
    user_id            UUID        NOT NULL,
    chat_session_id    UUID        NOT NULL,

    -- Chép đủ để dựng lại thẻ xác nhận và để kiểm "đề xuất này có còn đúng giá không" lúc xác nhận.
    event_slug         TEXT        NOT NULL,
    event_session_id   UUID        NOT NULL,
    zone_code          TEXT        NOT NULL,
    -- 1–6: trần đặt vé qua chat (TicketBookingUseCase.MAX_QUANTITY). Mua nhiều hơn thì dùng trang web.
    quantity           INT         NOT NULL,
    unit_price_vnd     BIGINT      NOT NULL,
    total_vnd          BIGINT      NOT NULL,

    -- PROPOSED: đang chờ khách bấm. CONFIRMED: đã giữ chỗ và tạo đơn. EXPIRED: quá 10 phút không ai bấm.
    -- CANCELLED: khách đổi ý (hoặc trợ lý thay bằng đề xuất mới cho cùng khu).
    status             TEXT        NOT NULL DEFAULT 'PROPOSED',

    -- Ghi lúc xác nhận, chép từ PlacedOrder: ordering không trả lại checkout_url ở đường tra đơn, nên
    -- không lưu thì lần bấm thứ hai (409 "đã xác nhận") không trả lại được đơn cũ mà không gọi thêm service.
    order_id           UUID,
    order_number       TEXT,
    checkout_url       TEXT,
    payment_expires_at TIMESTAMPTZ,

    expires_at         TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at       TIMESTAMPTZ,

    CONSTRAINT ck_proposal_status    CHECK (status IN ('PROPOSED', 'CONFIRMED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_proposal_quantity  CHECK (quantity BETWEEN 1 AND 6),
    -- Trạng thái và mốc thời gian không được lệch pha: CONFIRMED thì phải có confirmed_at và order_id,
    -- không CONFIRMED thì không có confirmed_at. Mỗi phép chuyển là một UPDATE có điều kiện; CHECK này
    -- bắt mọi bản ghi nửa vời mà một UPDATE viết sai có thể tạo ra.
    CONSTRAINT ck_proposal_confirmed CHECK (
        ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL))
        AND (status <> 'CONFIRMED' OR order_id IS NOT NULL))
);

-- Đường đọc của khách và của job: "đề xuất đang mở của tôi", "đề xuất quá hạn".
CREATE INDEX idx_booking_proposals_user ON booking_proposals (user_id, status, expires_at);

-- MỘT đề xuất mở cho một khu của một suất với một khách. Khách đổi số vé thì trợ lý huỷ cái cũ rồi
-- lập cái mới; hai đề xuất mở song song cho cùng khu là hai thẻ "Đồng ý" và hai đơn tiềm năng.
CREATE UNIQUE INDEX uq_booking_proposal_open
    ON booking_proposals (user_id, event_session_id, zone_code)
    WHERE status = 'PROPOSED';

-- Job hết hạn chỉ quét phần đang mở.
CREATE INDEX idx_booking_proposals_expiry ON booking_proposals (expires_at) WHERE status = 'PROPOSED';
