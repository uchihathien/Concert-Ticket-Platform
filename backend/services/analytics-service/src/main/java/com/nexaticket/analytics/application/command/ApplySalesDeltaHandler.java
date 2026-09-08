// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.application.command;

import com.nexaticket.analytics.domain.model.SalesDelta;
import com.nexaticket.analytics.domain.port.SalesReadModelRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cập nhật read model từ một sự kiện.
 *
 * <p>Idempotent và không phụ thuộc thứ tự — hai yêu cầu bắt buộc với mọi consumer (ADR-1009). Cái
 * đầu do bảng {@code processed_events} bảo đảm; cái sau do việc mọi message mang <b>delta</b> chứ
 * không mang trạng thái tuyệt đối.
 */
@Service
public class ApplySalesDeltaHandler {

    private static final Logger log = LoggerFactory.getLogger(ApplySalesDeltaHandler.class);

    private final SalesReadModelRepository readModel;

    public ApplySalesDeltaHandler(SalesReadModelRepository readModel) {
        this.readModel = readModel;
    }

    /** @return true nếu đây là lần đầu thấy sự kiện này */
    @Transactional
    public boolean handle(UUID eventId, String eventType, SalesDelta delta) {
        boolean applied = readModel.applyIfNew(eventId, eventType, delta);
        if (!applied) {
            log.debug("Sự kiện {} đã cộng vào read model trước đó, bỏ qua", eventId);
        }
        return applied;
    }
}
