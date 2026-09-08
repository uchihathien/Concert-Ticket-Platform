// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sửa phần mô tả của sự kiện.
 *
 * <p>Sửa được cả khi đang bán — đính chính một dòng mô tả sai không có lý do gì phải rút sự kiện
 * xuống trước. Thứ <b>không</b> sửa được là slug (đã nằm trên link người ta chia sẻ) và địa điểm
 * (tồn kho đã dựng theo khu của địa điểm cũ); đổi hai thứ đó là tạo sự kiện mới.
 */
@Service
public class UpdateEventHandler {

    private final EventRepository events;
    private final CatalogAccess access;

    public UpdateEventHandler(EventRepository events, CatalogAccess access) {
        this.events = events;
        this.access = access;
    }

    @Transactional
    public void handle(
            UUID organizationId,
            UUID eventId,
            String title,
            String summary,
            String description,
            String category,
            String posterUrl) {
        access.requireCatalogManager(organizationId);

        Event event = events.findByIdForOrganization(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));

        event.rename(title, summary, description, category, posterUrl);
        events.update(event);
    }
}
