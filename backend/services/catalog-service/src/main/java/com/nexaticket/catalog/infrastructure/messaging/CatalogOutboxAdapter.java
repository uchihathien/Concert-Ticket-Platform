// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.messaging;

import com.nexaticket.catalog.domain.model.SeatManifest;
import com.nexaticket.catalog.domain.port.CatalogOutboxPort;
import com.nexaticket.platform.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

/**
 * Phát sự kiện của Catalog qua transactional outbox.
 *
 * <p>{@code session.published} mang <b>toàn bộ</b> danh sách chỗ, khác hẳn quy ước "payload gọn,
 * consumer tự gọi lại" của các sự kiện khác. Đây là ngoại lệ có chủ ý: Inventory phải dựng được
 * tồn kho mà không gọi ngược lại Catalog, vì đó là điều kiện để nó không phụ thuộc Catalog ở
 * đường nóng (ADR-1002).
 *
 * <p>Vé đứng đi dạng khối có số lượng chứ không phải từng đơn vị: một zone 3.000 vé đứng sẽ thành
 * một message 3.000 phần tử vô nghĩa, trong khi Inventory tự sinh được đơn vị ảo.
 */
@Component
public class CatalogOutboxAdapter implements CatalogOutboxPort {

    private static final String EXCHANGE = "nexaticket.catalog";

    private final OutboxWriter writer;

    public CatalogOutboxAdapter(OutboxWriter writer) {
        this.writer = writer;
    }

    @Override
    public void sessionPublished(SeatManifest manifest) {
        writer.append(EXCHANGE, "EventSession", manifest.eventSessionId(), "session.published", manifest);
    }
}
