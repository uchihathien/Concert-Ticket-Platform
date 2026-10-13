// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.messaging;

import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.port.TicketEventsPort;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OutboxTicketEvents implements TicketEventsPort {

    private static final String EXCHANGE = "nexaticket.ticketing";

    private final OutboxWriter writer;

    public OutboxTicketEvents(OutboxWriter writer) {
        this.writer = writer;
    }

    @Override
    public void checkedIn(Ticket ticket, Instant at, String gate, String eventTitle) {
        // LinkedHashMap chứ không phải Map.of: gate và eventTitle được phép null.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketId", ticket.id().toString());
        payload.put("orderId", ticket.orderId().toString());
        payload.put("eventSessionId", ticket.eventSessionId().toString());
        payload.put("organizationId", ticket.organizationId().toString());
        payload.put("userId", ticket.userId().toString());
        payload.put("eventTitle", eventTitle);
        payload.put("checkedInAt", at.toString());
        payload.put("gate", gate);
        writer.append(EXCHANGE, "Ticket", ticket.id(), "ticket.checked_in", payload);
    }
}
