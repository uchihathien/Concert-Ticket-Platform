// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * Địa điểm cùng các khu của nó.
 *
 * <p>Khu là entity con của địa điểm, không phải aggregate riêng: không ai sửa một khu mà không
 * nghĩ tới cả địa điểm, và bất biến "mã khu không trùng nhau" chỉ kiểm được khi nhìn cả tập.
 */
public record Venue(UUID id, UUID organizationId, String name, String city, String address, List<VenueZone> zones) {

    public Venue {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Địa điểm phải có tên");
        }
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("Địa điểm phải có thành phố");
        }
        zones = List.copyOf(zones);
    }

    public static Venue create(UUID organizationId, String name, String city, String address) {
        return new Venue(UUID.randomUUID(), organizationId, name, city, address, List.of());
    }

    /** Tổng sức chứa, cộng cả khu ngồi lẫn khu đứng. */
    public int capacity() {
        return zones.stream().mapToInt(VenueZone::seatCount).sum();
    }

    public boolean hasZones() {
        return !zones.isEmpty();
    }
}
