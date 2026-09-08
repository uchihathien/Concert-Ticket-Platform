// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.model.TicketType;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu trữ sự kiện. */
public interface EventRepository {

    void insert(Event event);

    /** Ghi lại phần thuộc chính sự kiện (tiêu đề, trạng thái…), không đụng suất diễn hay hạng vé. */
    void update(Event event);

    void addSession(EventSession session);

    void addTicketType(TicketType ticketType);

    boolean slugExists(Slug slug);

    /**
     * Đọc sự kiện kèm suất diễn và hạng vé, giới hạn trong một tổ chức.
     *
     * <p>Cùng lý do với {@code VenueRepository#findById}: tổ chức nằm trong mệnh đề WHERE, không
     * nằm trong một câu {@code if} ở tầng trên.
     */
    Optional<Event> findByIdForOrganization(UUID organizationId, UUID eventId);

    /** Suất diễn thuộc tổ chức nào — dùng để kiểm quyền trước khi thêm hạng vé. */
    Optional<UUID> organizationOfSession(UUID sessionId);
}
