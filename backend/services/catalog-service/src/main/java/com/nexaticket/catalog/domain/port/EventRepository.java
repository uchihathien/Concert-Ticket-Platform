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

    void updateTicketType(UUID ticketTypeId, String name, long priceVnd, int sortOrder);

    void deleteTicketType(UUID ticketTypeId);

    void updateSession(EventSession session);

    void deleteSession(UUID sessionId);

    /**
     * Xoá hẳn sự kiện.
     *
     * <p>Suất diễn và hạng vé đi theo nhờ {@code ON DELETE CASCADE}. Chỉ dùng cho bản nháp chưa
     * từng lên bán — sự kiện đã publish thì {@code cancel} chứ không xoá, vì bên Inventory và
     * Ticketing còn dữ liệu trỏ vào nó.
     */
    void deleteEvent(UUID eventId);

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
