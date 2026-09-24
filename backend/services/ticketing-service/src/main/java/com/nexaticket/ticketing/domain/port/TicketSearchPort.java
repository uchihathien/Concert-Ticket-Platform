// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Đường đọc tra cứu vé của ban tổ chức.
 *
 * <h3>Vì sao không dùng {@code TicketRepository}</h3>
 *
 * <p>{@link TicketRepository} dựng {@code Ticket} — aggregate, có bất biến, có hành vi soát vé.
 * Màn hình này cần 12 cột phẳng cho 50 dòng, và không gọi một phương thức nghiệp vụ nào trên
 * chúng. Dựng 50 aggregate để đọc nhãn là trả giá cho thứ không dùng, và tệ hơn: nó tạo ra một
 * đường thứ hai mà mọi bất biến mới của {@code Ticket} phải thoả, chỉ để hiển thị một cái bảng.
 *
 * <p>Đây là ranh giới CQRS trong service này. Ghi đi qua aggregate; đọc đi qua đây.
 */
public interface TicketSearchPort {

    Page search(Criteria criteria);

    /**
     * Cập nhật lại trạng thái thanh toán đã chụp của những vé thuộc một đơn.
     *
     * <p>Đường tự vá của {@code TicketSearchQuery}: chưa có sự kiện {@code order.refunded} nào
     * được phát, nên bản chụp trong {@code tickets.payment_status} chỉ được sửa khi có người mở
     * trang và ta hỏi lại ordering. Xem V0101__ticket_search.sql.
     *
     * @return số dòng thực sự đổi; 0 là trường hợp thường gặp nhất và không phải lỗi
     */
    int repairPaymentStatus(UUID orderId, String paymentStatus);

    /**
     * Bộ lọc của màn hình tra cứu.
     *
     * <p>Trường {@code null} nghĩa là không lọc theo tiêu chí đó. {@code organizationId} là trường
     * duy nhất bắt buộc — không có nó thì đây là một câu truy vấn quét cả bảng vé của mọi tổ chức.
     *
     * @param text tìm trong mã vé, mã chỗ và tên người mua. Một ô nhập duy nhất chứ không phải ba,
     *     vì người trực tổng đài không biết trước khách sắp đọc thứ gì cho mình.
     * @param status trạng thái vé: VALID · CHECKED_IN · REVOKED. Đây là "đã vào cửa chưa".
     * @param paymentStatus PAID · REFUNDED. Khác {@code status}: một vé REVOKED có thể vì hoàn tiền
     *     hoặc vì ban tổ chức thu hồi, và hai việc đó xử lý khác nhau.
     */
    record Criteria(
            UUID organizationId,
            UUID eventSessionId,
            String text,
            String zoneCode,
            String status,
            String paymentStatus,
            int limit,
            int offset) {}

    /**
     * @param total tổng số dòng khớp bộ lọc, KHÔNG phải số dòng trả về. Đây là thứ cho phép màn
     *     hình nói "1–50 trong 2.480" — và con số đó là cách duy nhất người dùng biết bộ lọc của
     *     mình có quá rộng hay không.
     */
    record Page(List<Row> rows, int total) {}

    record Row(
            UUID ticketId,
            UUID orderId,
            UUID eventSessionId,
            String seatCode,
            String zoneCode,
            String seatLabel,
            String ticketTypeName,
            String holderName,
            String status,
            String paymentStatus,
            Instant issuedAt,
            Instant checkedInAt) {}
}
