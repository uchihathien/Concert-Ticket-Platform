// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Sơ đồ chỗ như client nhìn thấy.
 *
 * <p>Vé đứng <b>không</b> được trả xuống dưới dạng từng đơn vị: một zone 3.000 vé đứng sẽ thành
 * 3.000 phần tử vô nghĩa với khách và làm payload phình vô ích (ADR-1012). Thay vào đó là một khối
 * tóm tắt theo zone.
 *
 * @param purchaseAllowance chỉ có khi đã đăng nhập; để khách biết trước còn mua được mấy vé thay vì
 *     chọn 8 chỗ rồi mới bị từ chối ở bước cuối (ADR-1014 §4)
 */
public record SeatMapView(
        UUID eventSessionId,
        long availabilityVersion,
        List<Seat> seats,
        List<StandingZone> standingZones,
        PurchaseAllowance purchaseAllowance) {

    public record Seat(
            UUID id,
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
            String status) {}

    public record StandingZone(
            String zoneCode, UUID ticketTypeId, String ticketTypeName, long priceVnd, int available, int capacity) {}

    public record PurchaseAllowance(int limit, int used, int remaining) {}
}
