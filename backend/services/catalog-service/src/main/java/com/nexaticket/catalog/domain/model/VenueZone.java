// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.UUID;

/**
 * Một khu của địa điểm.
 *
 * <p>Khu ngồi khai hình chữ nhật {@code rowCount × seatsPerRow} thay vì liệt kê từng ghế. Khu hình
 * dạng lạ thì tách thành nhiều khu chữ nhật — vẫn tả được, mà màn hình nhập liệu không phải biến
 * thành một trình vẽ.
 */
public record VenueZone(
        UUID id,
        UUID venueId,
        String zoneCode,
        String name,
        AdmissionKind kind,
        Integer rowCount,
        Integer seatsPerRow,
        Integer capacity,
        int sortOrder) {

    public VenueZone {
        if (zoneCode == null || zoneCode.isBlank()) {
            throw new IllegalArgumentException("Khu phải có mã");
        }
        if (kind == AdmissionKind.SEATED) {
            if (rowCount == null || seatsPerRow == null || rowCount <= 0 || seatsPerRow <= 0) {
                throw new IllegalArgumentException("Khu ngồi phải có số hàng và số ghế mỗi hàng > 0");
            }
            capacity = null;
        } else {
            if (capacity == null || capacity <= 0) {
                throw new IllegalArgumentException("Khu đứng phải có sức chứa > 0");
            }
            rowCount = null;
            seatsPerRow = null;
        }
    }

    /** Số chỗ bán được của khu. Dùng để hiện tổng sức chứa trước khi publish. */
    public int seatCount() {
        return kind == AdmissionKind.SEATED ? rowCount * seatsPerRow : capacity;
    }

    /**
     * Mã chỗ của một ghế cụ thể, ví dụ {@code A-3-12}.
     *
     * <p>Đây là khoá duy nhất của chỗ bên Inventory nên định dạng phải ổn định: đổi nó ở phiên bản
     * sau là cắt đứt liên kết giữa vé đã bán và chỗ ngồi.
     */
    public String seatCode(int row, int seat) {
        return zoneCode + "-" + row + "-" + seat;
    }
}
