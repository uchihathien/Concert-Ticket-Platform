// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Nạp một sự kiện để <b>sửa nội dung bán hàng</b> — suất diễn, hạng vé, giá.
 *
 * <p>Gom vào một chỗ vì cả sáu lệnh sửa đều hỏi đúng ba câu giống nhau: người này có quyền không,
 * sự kiện có tồn tại trong tổ chức này không, và nó đã lên bán chưa. Chép ba câu ấy vào sáu handler
 * là sáu chỗ để quên câu thứ ba — mà câu thứ ba mới là câu quan trọng.
 *
 * <h3>Vì sao đang bán thì không sửa được</h3>
 *
 * <p>Tồn kho nằm ở inventory-service và chỉ được dựng một lần, lúc publish. Xoá một hạng vé ở đây
 * không xoá được những chỗ đã sinh ra từ nó bên kia — kết quả là những chỗ mồ côi vẫn bán được với
 * mức giá không còn tồn tại. Đổi giá cũng vậy: khách đang xem sơ đồ chỗ thấy giá cũ, kết toán ra
 * giá mới.
 *
 * <p>Cách làm đúng là rút xuống, sửa, rồi publish lại — publish lại là an toàn vì Inventory dùng
 * {@code ON CONFLICT DO NOTHING} nên chỗ đã bán không bị dựng lại thành trống.
 */
@Component
public class EditableEvent {

    private final EventRepository events;
    private final CatalogAccess access;

    public EditableEvent(EventRepository events, CatalogAccess access) {
        this.events = events;
        this.access = access;
    }

    public Event require(UUID organizationId, UUID eventId) {
        access.requireCatalogManager(organizationId);

        Event event = events.findByIdForOrganization(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));

        if (event.status().isVisibleToPublic()) {
            throw new ApiException(
                    CatalogErrorCode.INVALID_EVENT_STATE, "Rút sự kiện xuống trước khi sửa suất diễn hoặc hạng vé");
        }
        return event;
    }
}
