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
 * Rút sự kiện khỏi trang công khai.
 *
 * <p>Không phát sự kiện nào sang Inventory, và đó là quyết định có chủ đích: tồn kho vẫn còn, ghế
 * đã bán vẫn thuộc về người mua, vé đã phát vẫn quét được ở cửa. "Rút xuống" chỉ có nghĩa là
 * <b>không bán thêm nữa</b>. Xoá tồn kho ở đây sẽ biến một thao tác sửa nội dung thành một sự cố
 * mất vé của khách.
 *
 * <p>Cửa bán vẫn còn hiệu lực bên Inventory sau khi rút xuống. Khách không tìm thấy sự kiện qua
 * trang công khai nữa, nhưng ai đang giữ link trực tiếp tới suất diễn thì vẫn giữ chỗ được. Muốn
 * chặn hẳn thì đóng cửa bán, đó là một thao tác khác và nên như vậy.
 */
@Service
public class UnpublishEventHandler {

    private final EventRepository events;
    private final CatalogAccess access;

    public UnpublishEventHandler(EventRepository events, CatalogAccess access) {
        this.events = events;
        this.access = access;
    }

    @Transactional
    public void handle(UUID organizationId, UUID eventId) {
        access.requireCatalogManager(organizationId);

        Event event = events.findByIdForOrganization(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
        if (!event.status().isVisibleToPublic()) {
            throw new ApiException(CatalogErrorCode.EVENT_NOT_PUBLISHED, "Sự kiện không ở trạng thái đang bán");
        }

        event.unpublish();
        events.update(event);
    }
}
