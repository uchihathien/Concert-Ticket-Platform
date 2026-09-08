// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Danh sách chỗ sẽ được tạo ở Inventory khi suất diễn được publish.
 *
 * <p>Đây là toàn bộ nội dung của sự kiện {@code session.published}: Inventory nhận cái này rồi
 * dựng {@code session_seats} — sau đó nó <b>không bao giờ</b> gọi lại Catalog khi giữ chỗ
 * (ADR-1002). Vì vậy mọi thứ Inventory cần đều phải nằm ở đây: nhãn chỗ, giá, hạng vé, và trần
 * mua vé đã giải quyết kế thừa.
 *
 * @param seats chỗ ngồi đánh số, mỗi phần tử là một chỗ
 * @param standingBlocks khu vực vé đứng, mỗi phần tử là một khối có số lượng — Inventory tự sinh
 *     đơn vị ảo, không truyền 3.000 phần tử qua message
 */
public record SeatManifest(
        UUID eventSessionId,
        UUID eventId,
        UUID organizationId,
        List<SeatLine> seats,
        List<StandingBlock> standingBlocks,
        ResolvedPurchaseLimits limits) {

    public record SeatLine(
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

    public record StandingBlock(
            String zoneCode, int quantity, UUID ticketTypeId, String ticketTypeName, long priceVnd) {}

    /** Trần đã giải quyết {@code coalesce(suất, tổ chức, nền tảng)} và kẹp bằng trần cứng. */
    public record ResolvedPurchaseLimits(
            int maxSeatedPerHold, int maxStandingPerHold, int maxUnitsPerHold, int maxTicketsPerCustomer) {}

    public int totalSeatedCount() {
        return (int) seats.stream().filter(seat -> !seat.blocked()).count();
    }

    public int totalStandingCount() {
        return standingBlocks.stream().mapToInt(StandingBlock::quantity).sum();
    }

    public int totalSellableCount() {
        return totalSeatedCount() + totalStandingCount();
    }
}
