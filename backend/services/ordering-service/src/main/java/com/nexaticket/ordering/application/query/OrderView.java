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
        String status,
        long subtotalVnd,
        long discountVnd,
        long totalVnd,
        String paymentReference,
        String vietQrPayload,
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
                order.status().name(),
                order.subtotal().amountVnd(),
                order.discount().amountVnd(),
                order.total().amountVnd(),
                order.paymentReference(),
                order.vietQrPayload(),
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
