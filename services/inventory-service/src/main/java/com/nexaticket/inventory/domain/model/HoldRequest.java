// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Một lần giữ chỗ — chứa được cả vé ngồi lẫn vé đứng, và phải nguyên tử trên cả hai (ADR-1012).
 *
 * <p>Ghế trùng lặp bị loại ở constructor chứ không phải ở database: gửi cùng một ghế hai lần là lỗi
 * của client, và nếu để lọt xuống thì unique index sẽ báo {@code SEAT_UNAVAILABLE} — một thông báo
 * sai, vì ghế đó thật ra vẫn trống.
 *
 * <p>Vé ngồi tới đây bằng HAI đường, và cả hai đều là vé ngồi: khách bấm đúng ô ghế trên sơ đồ
 * ({@code seatIds}), hoặc khách chỉ nói khu và số lượng ({@code seatedZones}) rồi hệ thống chọn hộ.
 * Đường thứ hai là đường chính của giao diện hiện nay — xem {@link SeatedZoneRequest}.
 *
 * @param seatIds các đơn vị vé ngồi khách chỉ đích danh, đã khử trùng lặp, giữ nguyên thứ tự nhập
 * @param seatedZones các nhóm vé ngồi xin theo khu, đã gộp theo zone
 * @param standing các nhóm vé đứng, đã gộp theo zone
 */
public record HoldRequest(List<UUID> seatIds, List<SeatedZoneRequest> seatedZones, List<StandingRequest> standing) {

    public HoldRequest {
        seatIds = List.copyOf(new LinkedHashSet<>(seatIds == null ? List.<UUID>of() : seatIds));
        seatedZones = mergeSeatedByZone(seatedZones == null ? List.of() : seatedZones);
        standing = mergeByZone(standing == null ? List.of() : standing);
    }

    /** Gộp hai dòng cùng zone thành một — cùng lý do với vé đứng bên dưới. */
    private static List<SeatedZoneRequest> mergeSeatedByZone(List<SeatedZoneRequest> raw) {
        Map<String, Integer> byZone = raw.stream()
                .collect(Collectors.groupingBy(
                        SeatedZoneRequest::zoneCode,
                        java.util.LinkedHashMap::new,
                        Collectors.summingInt(SeatedZoneRequest::quantity)));
        return byZone.entrySet().stream()
                .map(e -> new SeatedZoneRequest(e.getKey(), e.getValue()))
                .toList();
    }

    /** Gộp hai dòng cùng zone thành một — nếu không, trần sẽ đếm đúng nhưng cấp phát chạy hai lượt. */
    private static List<StandingRequest> mergeByZone(List<StandingRequest> raw) {
        Map<String, Integer> byZone = raw.stream()
                .collect(Collectors.groupingBy(
                        StandingRequest::zoneCode,
                        java.util.LinkedHashMap::new,
                        Collectors.summingInt(StandingRequest::quantity)));
        return byZone.entrySet().stream()
                .map(e -> new StandingRequest(e.getKey(), e.getValue()))
                .toList();
    }

    /** Cả hai đường vé ngồi cộng lại — đây là con số trần {@code maxSeatedPerHold} nói tới. */
    public int seatedCount() {
        return seatIds.size()
                + seatedZones.stream().mapToInt(SeatedZoneRequest::quantity).sum();
    }

    public int standingCount() {
        return standing.stream().mapToInt(StandingRequest::quantity).sum();
    }

    public int totalUnits() {
        return seatedCount() + standingCount();
    }

    /** Có ghế nào khách chỉ đích danh không — tức có phải đi qua cổng Redis không. */
    public boolean hasNamedSeats() {
        return !seatIds.isEmpty();
    }

    public boolean hasSeated() {
        return seatedCount() > 0;
    }

    public boolean hasStanding() {
        return !standing.isEmpty();
    }

    /** Dùng cho thông điệp lỗi và log; không dùng để quyết định nghiệp vụ. */
    public <T> List<T> mapSeats(Function<UUID, T> mapper) {
        return seatIds.stream().map(mapper).toList();
    }

    public Set<UUID> seatIdSet() {
        return Set.copyOf(seatIds);
    }
}
