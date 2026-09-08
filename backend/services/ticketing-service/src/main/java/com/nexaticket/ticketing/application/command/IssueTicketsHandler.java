// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.command;

import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phát hành vé sau khi đơn được thanh toán.
 *
 * <p>Idempotent tuyệt đối, và không phải nhờ code mà nhờ {@code tickets.order_item_id UNIQUE}.
 * Sự kiện {@code order.paid} có thể đến hai lần vì webhook SePay retry, vì consumer chạy lại sau
 * khi chết, hoặc vì RabbitMQ giao lại — cả ba đường đều dừng ở unique index đó. Một ghế đã bán mà
 * in ra hai mã QR nghĩa là hai người cùng đến cửa với vé hợp lệ.
 */
@Service
public class IssueTicketsHandler {

    private static final Logger log = LoggerFactory.getLogger(IssueTicketsHandler.class);

    private final TicketRepository tickets;

    public IssueTicketsHandler(TicketRepository tickets) {
        this.tickets = tickets;
    }

    /**
     * @param seats các dòng của đơn hàng, mỗi dòng thành đúng một vé
     */
    public record Command(UUID orderId, UUID eventSessionId, UUID organizationId, UUID userId, List<SeatLine> seats) {

        public record SeatLine(
                UUID orderItemId,
                UUID sessionSeatId,
                String seatCode,
                String zoneCode,
                String admissionType,
                String seatLabel,
                String ticketTypeName) {}
    }

    /**
     * @return số vé thực sự phát mới; 0 nghĩa là lần chạy lại của một sự kiện đã xử lý
     */
    @Transactional
    public int handle(Command cmd) {
        List<Ticket> toIssue = cmd.seats().stream()
                .map(seat -> Ticket.issue(
                        cmd.orderId(),
                        seat.orderItemId(),
                        cmd.eventSessionId(),
                        cmd.organizationId(),
                        cmd.userId(),
                        seat.sessionSeatId(),
                        seat.seatCode(),
                        seat.zoneCode(),
                        seat.admissionType(),
                        seat.seatLabel(),
                        seat.ticketTypeName()))
                .toList();

        int issued = tickets.issueAll(toIssue);
        if (issued == 0) {
            log.info("Đơn {} đã có vé từ trước, bỏ qua lần phát hành lặp", cmd.orderId());
        }
        return issued;
    }
}
