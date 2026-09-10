// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.query;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.domain.model.PurchaseLimits;
import com.nexaticket.inventory.domain.model.SessionInventory;
import com.nexaticket.inventory.domain.port.SeatRepository;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
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

    /**
     * Tồn kho gộp theo zone và trạng thái, cho màn hình quản trị của ban tổ chức.
     *
     * <p>Ném {@code SESSION_NOT_FOUND} khi suất chưa được dựng tồn kho. Với sự kiện còn nháp thì đó
     * là trạng thái bình thường, không phải sự cố — bên gọi (bảng điều khiển của catalog) hiểu 404
     * đúng như vậy và hiện "chưa lên bán" thay vì báo lỗi.
     */
    @Transactional(readOnly = true)
    public SeatStatusView seatStatus(UUID eventSessionId) {
        sessions.findBySessionId(eventSessionId)
                .orElseThrow(() -> new ApiException(InventoryErrorCode.SESSION_NOT_FOUND, "Event session not found"));

        List<SeatStatusView.ZoneStatus> zones = seats.zoneStatusCounts(eventSessionId).stream()
                .map(row -> new SeatStatusView.ZoneStatus(
                        row.zoneCode(),
                        row.admissionType(),
                        row.available(),
                        row.held(),
                        row.reserved(),
                        row.sold(),
                        row.blocked()))
                .toList();

        return new SeatStatusView(eventSessionId, sessions.currentAvailabilityVersion(eventSessionId), zones);
    }
}
