// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thiết kế chỗ ngồi của một suất diễn: khu vực nào được bán, hạng vé nào, ghi đè gì.
 *
 * <p>Đây là nơi hai chiều của mô hình khu vực gặp nhau và biến thành một danh sách chỗ phẳng.
 * Sau bước này, Inventory <b>không còn biết</b> chỗ đến từ khu vực áp cứng hay từ thiết kế của tổ
 * chức — nó chỉ thấy một danh sách, và nhờ vậy đường giữ chỗ không phải phân biệt gì.
 */
public final class SeatingPlan {

    private final UUID eventSessionId;
    private final UUID eventId;
    private final UUID organizationId;
    private final List<Zone> zones;
    private final Map<UUID, ZoneUsage> usageByZone;
    private final List<SeatOverride> overrides;
    private final Map<UUID, TicketTier> tiersById;

    public SeatingPlan(
            UUID eventSessionId,
            UUID eventId,
            UUID organizationId,
            List<Zone> zones,
            List<ZoneUsage> usages,
            List<SeatOverride> overrides,
            List<TicketTier> tiers) {
        this.eventSessionId = eventSessionId;
        this.eventId = eventId;
        this.organizationId = organizationId;
        this.zones = List.copyOf(zones);
        this.overrides = List.copyOf(overrides);
        this.usageByZone = new HashMap<>();
        for (ZoneUsage usage : usages) {
            usageByZone.put(usage.zoneId(), usage);
        }
        this.tiersById = new HashMap<>();
        for (TicketTier tier : tiers) {
            tiersById.put(tier.id(), tier);
        }
    }

    /** Hạng vé áp cho một khu vực. */
    public record TicketTier(UUID id, String name, long priceVnd) {}

    /**
     * Biến thiết kế thành danh sách chỗ cụ thể.
     *
     * <p>Quy tắc ghi đè, theo thứ tự áp dụng:
     *
     * <ol>
     *   <li>{@code REMOVE} — chỗ không xuất hiện trong danh sách. Sân khấu dựng đè lên nó.
     *   <li>{@code BLOCK} — chỗ xuất hiện nhưng không bán được. Vẫn phải có mặt để sơ đồ hiển thị
     *       đúng hình dạng khán phòng; xoá đi thì khách thấy một lỗ hổng khó hiểu.
     *   <li>{@code SET_TIER} — đổi hạng vé cho riêng chỗ đó.
     * </ol>
     */
    public SeatManifest materialize(SeatManifest.ResolvedPurchaseLimits limits) {
        Map<String, SeatOverride> overrideIndex = new HashMap<>();
        for (SeatOverride override : overrides) {
            overrideIndex.put(override.zoneId() + "|" + override.seatCode(), override);
        }

        List<SeatManifest.SeatLine> seats = new ArrayList<>();
        List<SeatManifest.StandingBlock> standing = new ArrayList<>();

        for (Zone zone : zones) {
            ZoneUsage usage = usageByZone.get(zone.id());
            if (usage == null || !usage.included()) {
                continue;
            }
            TicketTier zoneTier = tiersById.get(usage.ticketTierId());
            if (zoneTier == null) {
                continue; // Preflight đã chặn trường hợp này; ở đây chỉ để không NPE.
            }

            if (zone.admissionType() == AdmissionType.STANDING) {
                standing.add(new SeatManifest.StandingBlock(
                        zone.zoneCode(),
                        effectiveStandingCapacity(zone, usage),
                        zoneTier.id(),
                        zoneTier.name(),
                        zoneTier.priceVnd()));
                continue;
            }

            for (Zone.FixedSeat seat : zone.fixedSeats()) {
                SeatOverride override = overrideIndex.get(zone.id() + "|" + seat.seatCode());
                if (override != null && override.action() == SeatOverride.Action.REMOVE) {
                    continue;
                }
                TicketTier tier = override != null && override.action() == SeatOverride.Action.SET_TIER
                        ? tiersById.getOrDefault(override.ticketTierId(), zoneTier)
                        : zoneTier;

                seats.add(new SeatManifest.SeatLine(
                        zone.zoneCode() + "-" + seat.seatCode(),
                        zone.zoneCode(),
                        zone.name(),
                        seat.rowLabel(),
                        seat.seatLabel(),
                        seat.posX(),
                        seat.posY(),
                        tier.id(),
                        tier.name(),
                        tier.priceVnd(),
                        override != null && override.action() == SeatOverride.Action.BLOCK));
            }
        }

        return new SeatManifest(eventSessionId, eventId, organizationId, seats, standing, limits);
    }

    /**
     * Tổ chức bán được nhiều nhất là sức chứa của khu vực.
     *
     * <p>Bán ít hơn là quyền của họ (chừa lối đi, khu kỹ thuật). Bán nhiều hơn thì không —
     * sức chứa là ràng buộc vật lý và phòng cháy của địa điểm, không phải con số thương lượng.
     */
    private static int effectiveStandingCapacity(Zone zone, ZoneUsage usage) {
        int venueCapacity = zone.standingCapacity();
        return usage.standingCapacity() == null ? venueCapacity : Math.min(usage.standingCapacity(), venueCapacity);
    }

    public List<Zone> zones() {
        return zones;
    }

    public ZoneUsage usageOf(UUID zoneId) {
        return usageByZone.get(zoneId);
    }

    public boolean hasTier(UUID tierId) {
        return tiersById.containsKey(tierId);
    }

    public boolean anyZoneIncluded() {
        return zones.stream().anyMatch(zone -> {
            ZoneUsage usage = usageByZone.get(zone.id());
            return usage != null && usage.included();
        });
    }
}
