// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dựng tồn kho cho một suất diễn từ danh sách chỗ mà Catalog gửi sang.
 *
 * <p>Đây là ranh giới giữa "tổ chức đang dựng sự kiện" và "hệ thống đang bán vé". Sau bước này,
 * Inventory <b>không biết</b> chỗ đến từ khu vực áp cứng hay từ thiết kế của tổ chức — nó chỉ thấy
 * một danh sách phẳng, và nhờ vậy đường giữ chỗ không phải phân biệt gì (ADR-1012).
 */
public interface SessionMaterializer {

    /**
     * @return số đơn vị tồn kho đã tạo; 0 nghĩa là suất này đã materialize trước đó
     */
    int materialize(SessionManifest manifest);

    /**
     * @param seats chỗ ngồi đánh số, mỗi phần tử là một chỗ
     * @param standingBlocks khối vé đứng có số lượng — đơn vị ảo được sinh ở đây, không truyền
     *     3.000 phần tử qua message
     */
    record SessionManifest(
            UUID eventSessionId,
            UUID eventId,
            UUID organizationId,
            Instant salesOpenAt,
            Instant salesCloseAt,
            int maxSeatedPerHold,
            int maxStandingPerHold,
            int maxUnitsPerHold,
            int maxTicketsPerCustomer,
            List<SeatLine> seats,
            List<StandingBlock> standingBlocks) {}

    record SeatLine(
            String seatCode,
            String zoneCode,
            String sectionLabel,
            String rowLabel,
            String seatLabel,
            BigDecimal posX,
            BigDecimal posY,
            UUID ticketTypeId,
            String ticketTypeName,
            long priceVnd,
            boolean blocked) {}

    record StandingBlock(String zoneCode, int quantity, UUID ticketTypeId, String ticketTypeName, long priceVnd) {}
}
