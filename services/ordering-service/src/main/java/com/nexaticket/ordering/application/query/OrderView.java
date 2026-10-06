// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.query;

import com.nexaticket.ordering.domain.model.Order;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Đơn hàng như khách nhìn thấy.
 *
 * <p>Cố ý <b>không</b> có {@code commissionBps} và {@code commissionVnd}: hoa hồng là chuyện giữa
 * nền tảng và tổ chức, khách không cần biết đơn của mình bị cắt bao nhiêu phần trăm. Tổ chức cũng
 * không thấy — họ chỉ thấy số vé và số tiền đã bán (ADR-1010).
 */
public record OrderView(
        UUID id,
        String orderNumber,
        /**
         * Suất diễn của đơn.
         *
         * <p>Thiếu trường này, client không có cách nào biết đơn thuộc sự kiện nào. Trang thanh
         * toán từng phải hiện "Đơn NT-… · Khán đài A · ghế 20" mà không nói được khách đang trả
         * 1.800.000đ cho sự kiện gì — và trang đơn hàng phải bắc cầu qua ví vé, cách chỉ chạy được
         * với đơn ĐÃ thanh toán (đơn chưa trả tiền thì chưa có vé nào).
         */
        UUID eventSessionId,
        /** Sự kiện của suất. Null với đơn tạo trước migration V0102. */
        UUID eventId,
        String status,
        long subtotalVnd,
        long discountVnd,
        long totalVnd,
        String paymentReference,
        String vietQrPayload,
        String checkoutUrl,
        Instant paymentExpiresAt,
        Instant paidAt,
        List<Item> items) {

    public record Item(
            String seatCode,
            String zoneCode,
            String admissionType,
            String seatLabel,
            String ticketTypeName,
            long unitPriceVnd,
            long discountVnd) {}

    public static OrderView from(Order order) {
        return new OrderView(
                order.id(),
                order.orderNumber().value(),
                order.eventSessionId(),
                order.eventId(),
                order.status().name(),
                order.subtotal().amountVnd(),
                order.discount().amountVnd(),
                order.total().amountVnd(),
                order.paymentReference(),
                order.vietQrPayload(),
                order.checkoutUrl(),
                order.paymentExpiresAt(),
                order.paidAt(),
                order.items().stream()
                        .map(i -> new Item(
                                i.seatCode(),
                                i.zoneCode(),
                                i.admissionType(),
                                i.seatLabel(),
                                i.ticketTypeName(),
                                i.unitPrice().amountVnd(),
                                i.discount().amountVnd()))
                        .toList());
    }
}
