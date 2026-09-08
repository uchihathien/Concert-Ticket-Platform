// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.ticketing.application.command.IssueTicketsHandler;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service — chỉ service nội bộ gọi được.
 *
 * <p>Đây là đường phát hành vé. Để lọt ra internet nghĩa là ai cũng tự in vé cho mình.
 */
@RestController
@RequestMapping("/internal/tickets")
public class InternalTicketController {

    private final IssueTicketsHandler issueTickets;

    public InternalTicketController(IssueTicketsHandler issueTickets) {
        this.issueTickets = issueTickets;
    }

    public record IssueRequest(
            @NotNull UUID orderId,
            @NotNull UUID eventSessionId,
            @NotNull UUID organizationId,
            @NotNull UUID userId,
            List<SeatLine> seats) {

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
     * @param issued số vé thực sự phát mới; 0 nghĩa là lần gọi lặp, không phải lỗi
     */
    public record IssueResponse(int issued) {}

    @PostMapping
    public IssueResponse issue(@RequestBody IssueRequest request) {
        return new IssueResponse(issueTickets.handle(new IssueTicketsHandler.Command(
                request.orderId(),
                request.eventSessionId(),
                request.organizationId(),
                request.userId(),
                request.seats().stream()
                        .map(s -> new IssueTicketsHandler.Command.SeatLine(
                                s.orderItemId(),
                                s.sessionSeatId(),
                                s.seatCode(),
                                s.zoneCode(),
                                s.admissionType(),
                                s.seatLabel(),
                                s.ticketTypeName()))
                        .toList())));
    }
}
