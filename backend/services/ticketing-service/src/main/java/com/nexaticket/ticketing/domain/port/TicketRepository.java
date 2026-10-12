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
     * Tìm vé theo mã nhân viên gõ tay — phần đầu của id vé, đúng thứ app khách đang in ra.
     *
     * <p>Giới hạn trong một tổ chức: không ai dò được vé của tổ chức khác bằng cách gõ thử mã. Trả
     * tối đa hai vé — người gọi chỉ cần biết "không có", "đúng một", hay "trùng, gõ thêm ký tự".
     */
    List<Ticket> findByCodePrefix(UUID organizationId, String codePrefix);

    /**
     * Ghi nhận khách vào cửa, <b>chỉ khi</b> vé đang ở ngoài và chưa bị thu hồi.
     *
     * <p>Một câu {@code UPDATE ... WHERE inside = FALSE} chứ không phải đọc-rồi-ghi: hai máy quét
     * cùng một vé trong cùng một giây là chuyện bình thường ở cửa vào, và đọc-rồi-ghi sẽ cho cả hai
     * cùng thấy "đang ở ngoài" rồi cùng cho qua.
     *
     * @param reentryAllowed false thì chỉ lần vào đầu tiên mới thắng
     * @return số lần vào sau lần này (1 = lần đầu); rỗng nghĩa là lần gọi này không thắng
     */
    Optional<Integer> enter(UUID ticketId, UUID staffId, Instant at, String gate, boolean reentryAllowed);

    /**
     * Cho vào thủ công một vé đang được ghi nhận là ở trong — khách đã ra mà quên quét Ra.
     *
     * @return số lần vào sau lần này; rỗng nếu vé không còn ở trạng thái "đang ở trong"
     */
    Optional<Integer> forceEnter(UUID ticketId, Instant at, String gate);

    /**
     * Ghi nhận khách ra ngoài, <b>chỉ khi</b> vé đang ở trong.
     *
     * @return true nếu lần gọi này thực sự ghi nhận được
     */
    boolean exit(UUID ticketId, Instant at);

    /** Số khách của một suất, đếm trong phạm vi một tổ chức. */
    Presence presenceOf(UUID organizationId, UUID eventSessionId);

    /**
     * @param inside đang ở trong khu vực
     * @param checkedIn đã vào ít nhất một lần
     */
    record Presence(int inside, int checkedIn) {}

    /** Thu hồi vé khi hoàn tiền hoặc huỷ sự kiện. */
    int revokeByOrder(UUID orderId, String reason, Instant at);
}
