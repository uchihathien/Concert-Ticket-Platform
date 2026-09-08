// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import com.nexaticket.catalog.domain.model.SeatManifest;

/** Phát integration event của Catalog. */
public interface CatalogOutboxPort {

    /**
     * Suất diễn đã publish, kèm toàn bộ danh sách chỗ.
     *
     * <p>Đây là event-carried state transfer (ADR-1002): Inventory nhận đủ dữ liệu để dựng tồn
     * kho và sau đó <b>không bao giờ</b> gọi lại Catalog khi giữ chỗ — điều kiện bắt buộc để chịu
     * được 10k đồng thời.
     */
    void sessionPublished(SeatManifest manifest);
}
