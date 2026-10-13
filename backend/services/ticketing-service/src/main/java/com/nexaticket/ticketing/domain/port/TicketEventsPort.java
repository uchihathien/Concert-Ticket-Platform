// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import com.nexaticket.ticketing.domain.model.Ticket;
import java.time.Instant;

/** Integration event của ticketing, ghi qua outbox trong cùng transaction với thay đổi vé. */
public interface TicketEventsPort {

    /**
     * Vé vừa được check-in <b>lần đầu</b>. Ra ngoài và vào lại không phát sự kiện này.
     *
     * @param eventTitle {@code null} khi không đọc được tên sự kiện từ catalog
     */
    void checkedIn(Ticket ticket, Instant at, String gate, String eventTitle);
}
