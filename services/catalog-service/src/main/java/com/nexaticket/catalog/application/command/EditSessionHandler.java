// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.EditableEvent;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đổi giờ hoặc xoá một suất diễn.
 *
 * <p>Xoá suất kéo theo hạng vé của nó nhờ {@code ON DELETE CASCADE} — đúng ý: một hạng vé không có
 * suất diễn thì không có nghĩa gì.
 *
 * <p>Không kiểm "cửa bán phải hợp lệ" ở đây. Chỗ kiểm là {@code preflight} lúc publish, và đó là
 * cố ý: ban tổ chức phải được lưu bản nháp dở dang. Chặn ngay lúc gõ sẽ buộc họ nhập đúng thứ tự
 * mà form áp đặt, thay vì thứ tự họ có thông tin.
 */
@Service
public class EditSessionHandler {

    private final EventRepository events;
    private final EditableEvent editable;

    public EditSessionHandler(EventRepository events, EditableEvent editable) {
        this.events = events;
        this.editable = editable;
    }

    @Transactional
    public void update(
            UUID organizationId,
            UUID eventId,
            UUID sessionId,
            Instant startsAt,
            Instant endsAt,
            Instant salesOpenAt,
            Instant salesCloseAt,
            Integer maxSeatedPerHold,
            Integer maxStandingPerHold,
            Integer maxUnitsPerHold,
            Integer maxTicketsPerCustomer) {
        EventSession existing = require(organizationId, eventId, sessionId);

        // null nghĩa là "giữ nguyên" với mốc thời gian, nhưng nghĩa là "về mặc định nền tảng" với
        // các trần mua vé — vì null CHÍNH LÀ giá trị hợp lệ của những cột đó. Không phân biệt được
        // hai nghĩa ấy trong một hàm PATCH là chuyện đã biết; ở đây chọn cách để trần luôn ghi đè,
        // nên form phải gửi lại giá trị hiện có nếu không muốn đổi.
        events.updateSession(new EventSession(
                existing.id(),
                existing.eventId(),
                startsAt == null ? existing.startsAt() : startsAt,
                endsAt == null ? existing.endsAt() : endsAt,
                salesOpenAt == null ? existing.salesOpenAt() : salesOpenAt,
                salesCloseAt == null ? existing.salesCloseAt() : salesCloseAt,
                maxSeatedPerHold,
                maxStandingPerHold,
                maxUnitsPerHold,
                maxTicketsPerCustomer,
                existing.ticketTypes()));
    }

    @Transactional
    public void delete(UUID organizationId, UUID eventId, UUID sessionId) {
        EventSession existing = require(organizationId, eventId, sessionId);
        events.deleteSession(existing.id());
    }

    private EventSession require(UUID organizationId, UUID eventId, UUID sessionId) {
        Event event = editable.require(organizationId, eventId);
        return event.sessions().stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new ApiException(CatalogErrorCode.SESSION_NOT_FOUND, "Session not found"));
    }
}
