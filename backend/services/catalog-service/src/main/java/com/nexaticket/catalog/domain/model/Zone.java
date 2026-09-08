// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * Một khu vực trong sơ đồ địa điểm.
 *
 * @param fixedSeats chỗ áp cứng; chỉ có nghĩa với {@code FIXED} + {@code SEATED}
 * @param standingCapacity sức chứa; chỉ có nghĩa với {@code STANDING}
 */
public record Zone(
        UUID id,
        String zoneCode,
        String name,
        ZoneKind kind,
        AdmissionType admissionType,
        Integer standingCapacity,
        List<FixedSeat> fixedSeats) {

    public Zone {
        fixedSeats = fixedSeats == null ? List.of() : List.copyOf(fixedSeats);
        if (admissionType == AdmissionType.STANDING && (standingCapacity == null || standingCapacity <= 0)) {
            throw new IllegalArgumentException("Khu vực vé đứng phải có sức chứa dương: " + zoneCode);
        }
        if (admissionType == AdmissionType.SEATED && standingCapacity != null) {
            throw new IllegalArgumentException("Khu vực vé ngồi không có sức chứa vé đứng: " + zoneCode);
        }
    }

    /** Tổ chức có được thiết kế lại chỗ trong khu vực này không. */
    public boolean isDesignable() {
        return kind == ZoneKind.FLEXIBLE;
    }

    public record FixedSeat(
            UUID id,
            String seatCode,
            String rowLabel,
            String seatLabel,
            java.math.BigDecimal posX,
            java.math.BigDecimal posY) {}
}
