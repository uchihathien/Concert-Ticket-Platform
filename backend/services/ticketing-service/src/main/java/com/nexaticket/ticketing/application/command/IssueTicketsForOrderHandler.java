// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.command;

import com.nexaticket.ticketing.domain.port.OrderingPort;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phát vé cho một đơn đã thanh toán.
 *
 * <p>Đọc chi tiết đơn từ Ordering rồi phát vé. Kiểm lại trạng thái {@code PAID} dù sự kiện tên là
 * {@code order.paid}: sự kiện là thứ đã xảy ra <i>lúc phát</i>, còn Ordering là nguồn chân lý
 * <i>lúc này</i>. Một message đến trễ sau khi đơn đã hoàn tiền không được phát vé.
 */
@Service
public class IssueTicketsForOrderHandler {

    private static final Logger log = LoggerFactory.getLogger(IssueTicketsForOrderHandler.class);

    private final OrderingPort ordering;
    private final IssueTicketsHandler issueTickets;

    public IssueTicketsForOrderHandler(OrderingPort ordering, IssueTicketsHandler issueTickets) {
        this.ordering = ordering;
        this.issueTickets = issueTickets;
    }

    /** @return số vé thực sự phát mới; 0 nghĩa là đã phát trước đó hoặc đơn không đủ điều kiện */
    @Transactional
    public int handle(UUID orderId) {
        OrderingPort.PaidOrder order = ordering.fetch(orderId);

        if (!order.isPaid()) {
            log.warn("Đơn {} ở trạng thái {}, không phát vé", orderId, order.status());
            return 0;
        }
        if (order.items().isEmpty()) {
            log.warn("Đơn {} không có dòng nào, không phát vé", orderId);
            return 0;
        }

        return issueTickets.handle(new IssueTicketsHandler.Command(
                order.orderId(),
                order.eventSessionId(),
                order.organizationId(),
                order.userId(),
                order.items().stream()
                        .map(line -> new IssueTicketsHandler.Command.SeatLine(
                                line.orderItemId(),
                                line.sessionSeatId(),
                                line.seatCode(),
                                line.zoneCode(),
                                line.admissionType(),
                                line.seatLabel(),
                                line.ticketTypeName()))
                        .toList()));
    }
}
