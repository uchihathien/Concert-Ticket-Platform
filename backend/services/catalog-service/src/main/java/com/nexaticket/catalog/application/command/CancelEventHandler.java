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
 * Huỷ sự kiện, và xoá hẳn bản nháp.
 *
 * <p>Hai lệnh nhìn giống nhau nhưng khác hẳn về hệ quả, nên chúng nằm cạnh nhau ở đây để chỗ khác
 * nhau đó không bị bỏ sót:
 *
 * <ul>
 *   <li><b>Xoá</b> chỉ áp dụng cho bản nháp chưa từng lên bán. Khi đó chưa có tồn kho, chưa có vé,
 *       chưa có gì ở service khác trỏ vào — xoá là xoá sạch, và một sự kiện gõ nhầm không phải nằm
 *       lại trong danh sách mãi mãi.
 *   <li><b>Huỷ</b> áp dụng cho sự kiện đã bán. Không xoá gì cả: tồn kho, vé đã phát và bản ghi
 *       thanh toán đều còn nguyên, vì đó là những thứ cần để hoàn tiền và để đối soát. Chỉ trạng
 *       thái đổi, và sự kiện biến khỏi trang công khai.
 * </ul>
 *
 * <p>Không phát sự kiện nào sang Inventory. Điều đó có thể phản trực giác — huỷ mà tồn kho vẫn còn
 * bán được — nhưng "không bán nữa" đúng ra phải làm bằng cách đóng cửa bán, và gộp hai thao tác vào
 * một nút sẽ khiến việc huỷ trở nên không thể đảo ngược ở ba service cùng lúc. Hoàn tiền là quy
 * trình ngoài hệ thống ở MVP (docs/02-catalog-admin/state-machines.md).
 */
@Service
public class CancelEventHandler {

    private final EventRepository events;
    private final CatalogAccess access;

    public CancelEventHandler(EventRepository events, CatalogAccess access) {
        this.events = events;
        this.access = access;
    }

    @Transactional
    public void cancel(UUID organizationId, UUID eventId) {
        Event event = load(organizationId, eventId);
        if (event.status() == com.nexaticket.catalog.domain.model.EventStatus.CANCELLED) {
            throw new ApiException(CatalogErrorCode.INVALID_EVENT_STATE, "Sự kiện đã huỷ rồi");
        }
        event.cancel();
        events.update(event);
    }

    @Transactional
    public void deleteDraft(UUID organizationId, UUID eventId) {
        Event event = load(organizationId, eventId);
        if (!event.isDeletable()) {
            throw new ApiException(
                    CatalogErrorCode.INVALID_EVENT_STATE,
                    "Chỉ xoá được bản nháp chưa từng lên bán; sự kiện đã bán thì dùng huỷ");
        }
        events.deleteEvent(event.id());
    }

    private Event load(UUID organizationId, UUID eventId) {
        access.requireCatalogManager(organizationId);
        return events.findByIdForOrganization(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
    }
}
