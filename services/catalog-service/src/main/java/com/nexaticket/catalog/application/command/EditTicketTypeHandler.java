// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.EditableEvent;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sửa hoặc xoá một hạng vé.
 *
 * <p>Có mặt vì gõ nhầm giá là chuyện xảy ra thường xuyên và ban tổ chức không thể tự sửa nếu chỉ
 * có lệnh tạo. Không có nó, cách duy nhất để sửa "500.000" thành "5.000.000" là dựng lại cả sự
 * kiện.
 *
 * <p>Sửa và xoá ở chung một lớp vì chúng chia sẻ đúng phần khó: xác minh hạng vé này thật sự thuộc
 * về sự kiện trong đường dẫn. Thiếu bước đó thì id hạng vé của tổ chức khác sửa được từ đây, và
 * bộ lọc theo tổ chức ở tầng trên trở nên vô nghĩa.
 */
@Service
public class EditTicketTypeHandler {

    private final EventRepository events;
    private final EditableEvent editable;

    public EditTicketTypeHandler(EventRepository events, EditableEvent editable) {
        this.events = events;
        this.editable = editable;
    }

    @Transactional
    public void update(
            UUID organizationId,
            UUID eventId,
            UUID sessionId,
            UUID ticketTypeId,
            String name,
            long priceVnd,
            Integer sortOrder) {
        TicketType existing = require(organizationId, eventId, sessionId, ticketTypeId);
        events.updateTicketType(
                existing.id(),
                name == null || name.isBlank() ? existing.name() : name,
                priceVnd < 0 ? existing.priceVnd() : priceVnd,
                sortOrder == null ? existing.sortOrder() : sortOrder);
    }

    @Transactional
    public void delete(UUID organizationId, UUID eventId, UUID sessionId, UUID ticketTypeId) {
        TicketType existing = require(organizationId, eventId, sessionId, ticketTypeId);
        events.deleteTicketType(existing.id());
    }

    /** Hạng vé phải nằm đúng trong sự kiện và suất diễn của đường dẫn, không chỉ tồn tại đâu đó. */
    private TicketType require(UUID organizationId, UUID eventId, UUID sessionId, UUID ticketTypeId) {
        Event event = editable.require(organizationId, eventId);
        return event.sessions().stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new ApiException(CatalogErrorCode.SESSION_NOT_FOUND, "Session not found"))
                .ticketTypes()
                .stream()
                .filter(type -> type.id().equals(ticketTypeId))
                .findFirst()
                .orElseThrow(() -> new ApiException(CatalogErrorCode.TICKET_TYPE_NOT_FOUND, "Ticket type not found"));
    }
}
