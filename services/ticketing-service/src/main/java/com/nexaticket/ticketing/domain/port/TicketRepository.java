// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import com.nexaticket.ticketing.domain.model.Ticket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository {

    /**
     * Phát hành vé, bỏ qua những vé đã tồn tại.
     *
     * <p>Cài bằng {@code ON CONFLICT (order_item_id) DO NOTHING}: đây là chốt chặn cuối cùng chống
     * phát hành trùng (webhook đến hai lần, consumer chạy lại, saga retry). Bỏ qua chứ không ném,
     * vì "vé này đã có" là kết quả đúng của một lần chạy lại, không phải lỗi.
     *
     * @return số vé thực sự được tạo mới
     */
    int issueAll(List<Ticket> tickets);

    Optional<Ticket> findById(UUID ticketId);

    List<Ticket> findByOrder(UUID orderId);

    List<Ticket> findByUser(UUID userId, int limit, int offset);

    /**
     * Chuyển vé sang đã soát, <b>chỉ khi</b> đang VALID.
     *
     * <p>Một câu {@code UPDATE ... WHERE status = 'VALID'} chứ không phải đọc-rồi-ghi: hai máy
     * quét cùng một vé trong cùng một giây là chuyện bình thường ở cửa vào, và đọc-rồi-ghi sẽ cho
     * cả hai cùng thấy VALID rồi cùng cho qua.
     *
     * @return true nếu lần gọi này thực sự soát được vé; false nghĩa là ai đó đã soát trước
     */
    boolean checkIn(UUID ticketId, UUID staffId, Instant at);

    /** Thu hồi vé khi hoàn tiền hoặc huỷ sự kiện. */
    int revokeByOrder(UUID orderId, String reason, Instant at);
}
