// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.command;

import com.nexaticket.inventory.domain.port.SessionMaterializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dựng tồn kho khi Catalog publish một suất diễn.
 *
 * <p>Toàn bộ việc dựng nằm trong <b>một transaction</b>: hoặc suất diễn có đủ chỗ, hoặc không có
 * gì. Dựng dở dang là trường hợp tệ nhất có thể — khách vào thấy một sơ đồ thiếu nửa khán phòng và
 * mua vé bình thường, còn nửa kia thì không bao giờ bán được.
 */
@Service
public class MaterializeSessionHandler {

    private static final Logger log = LoggerFactory.getLogger(MaterializeSessionHandler.class);

    private final SessionMaterializer materializer;

    public MaterializeSessionHandler(SessionMaterializer materializer) {
        this.materializer = materializer;
    }

    /** @return số đơn vị tồn kho đã tạo; 0 nghĩa là suất này đã materialize trước đó */
    @Transactional
    public int handle(SessionMaterializer.SessionManifest manifest) {
        int created = materializer.materialize(manifest);
        if (created == 0) {
            log.debug("Bỏ qua materialize lặp cho suất {}", manifest.eventSessionId());
        }
        return created;
    }
}
