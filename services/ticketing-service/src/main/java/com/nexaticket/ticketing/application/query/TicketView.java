// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.query;

import com.nexaticket.ticketing.domain.model.Ticket;
import java.time.Instant;
import java.util.UUID;

/**
 * Vé như khách nhìn thấy trong ví vé của mình.
 *
 * @param qrToken chuỗi JWS; frontend tự render QR
 */
public record TicketView(
        UUID id,
        UUID orderId,
        UUID eventSessionId,
        String seatCode,
        String zoneCode,
        String admissionType,
        String seatLabel,
        String ticketTypeName,
        String status,
        Instant checkedInAt,
        String qrToken) {

    public static TicketView from(Ticket ticket, String qrToken) {
        return new TicketView(
                ticket.id(),
                ticket.orderId(),
                ticket.eventSessionId(),
                ticket.seatCode(),
                ticket.zoneCode(),
                ticket.admissionType(),
                ticket.seatLabel(),
                ticket.ticketTypeName(),
                ticket.status().name(),
                ticket.checkedInAt(),
                qrToken);
    }
}
