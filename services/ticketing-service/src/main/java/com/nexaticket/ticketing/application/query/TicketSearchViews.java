// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO của màn hình tra cứu vé.
 *
 * <p>Tách khỏi {@link TicketView} — đó là vé như <b>khách</b> nhìn thấy trong ví của mình, và nó
 * mang theo token QR đã ký. Bản này là vé như <b>ban tổ chức</b> nhìn thấy: có tên người mua,
 * không có token. Gộp hai thứ vào một record nghĩa là một trong hai màn hình sẽ nhận trường nó
 * không được phép có.
 */
public final class TicketSearchViews {

    private TicketSearchViews() {}

    /**
     * Bộ lọc, đúng hình dạng query string mà controller nhận.
     *
     * @param query một ô nhập cho cả mã vé, mã chỗ và tên khách — người trực tổng đài không biết
     *     trước khách sắp đọc thứ gì cho mình
     * @param status VALID · CHECKED_IN · REVOKED — "đã vào cửa chưa"
     * @param paymentStatus PAID · REFUNDED — khác {@code status}, xem {@code TicketSearchPort}
     */
    public record Filter(
            UUID eventSessionId,
            String query,
            String zoneCode,
            String status,
            String paymentStatus,
            int page,
            int size) {}

    /** @param total tổng số vé khớp bộ lọc, để màn hình nói được "1–50 trong 2.480" */
    public record TicketPage(List<TicketRow> rows, int total, int page, int size) {}

    /**
     * @param holderName {@code null} khi identity không trả lời được lúc phát vé. Màn hình hiện
     *     "—" chứ không hiện chuỗi rỗng: ô trống đọc như dữ liệu chưa tải xong.
     * @param checkedInAt {@code null} nghĩa là chưa vào cửa
     */
    public record TicketRow(
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
