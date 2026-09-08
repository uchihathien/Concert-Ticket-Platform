// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.query;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.domain.model.PurchaseLimits;
import com.nexaticket.inventory.domain.model.SessionInventory;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đường đọc sơ đồ chỗ.
 *
 * <p>Trả DTO của tầng application chứ không trả aggregate ra ngoài — controller không được chạm vào
 * domain (tactical-ddd.md §7).
 */
@Service
public class SeatQueries {

    private final SessionInventoryRepository sessions;
    private final SeatRepository seats;

    public SeatQueries(SessionInventoryRepository sessions, SeatRepository seats) {
        this.sessions = sessions;
        this.seats = seats;
    }

    /**
     * @param viewerId người đang xem, hoặc {@code null} nếu chưa đăng nhập
     */
    @Transactional(readOnly = true)
    public SeatMapView seatMap(UUID eventSessionId, UUID viewerId) {
        SessionInventory inventory = sessions.findBySessionId(eventSessionId)
                .orElseThrow(() -> new ApiException(InventoryErrorCode.SESSION_NOT_FOUND, "Event session not found"));

        var seatRows = seats.seatedRows(eventSessionId).stream()
                .map(r -> new SeatMapView.Seat(
                        r.id(),
                        r.seatCode(),
                        r.zoneCode(),
                        r.sectionLabel(),
                        r.rowLabel(),
                        r.seatLabel(),
                        r.posX(),
                        r.posY(),
                        r.ticketTypeId(),
                        r.ticketTypeName(),
                        r.priceVnd(),
                        r.status()))
                .toList();

        var zones = seats.standingZones(eventSessionId).stream()
                .map(z -> new SeatMapView.StandingZone(
                        z.zoneCode(), z.ticketTypeId(), z.ticketTypeName(), z.priceVnd(), z.available(), z.capacity()))
                .toList();

        SeatMapView.PurchaseAllowance allowance = null;
        if (viewerId != null) {
            PurchaseLimits limits = inventory.limits();
            int used = seats.countUnitsHeldBy(eventSessionId, viewerId);
            allowance =
                    new SeatMapView.PurchaseAllowance(limits.maxTicketsPerCustomer(), used, limits.remainingFor(used));
        }

        return new SeatMapView(
                eventSessionId, sessions.currentAvailabilityVersion(eventSessionId), seatRows, zones, allowance);
    }
}
