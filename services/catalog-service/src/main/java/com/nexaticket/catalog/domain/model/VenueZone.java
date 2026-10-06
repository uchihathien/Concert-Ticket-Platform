// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.UUID;

/**
 * Một khu của địa điểm.
 *
 * <p>Khu ngồi khai hình chữ nhật {@code rowCount × seatsPerRow} thay vì liệt kê từng ghế. Khu hình
 * dạng lạ thì tách thành nhiều khu — vẫn tả được, mà màn hình nhập liệu không phải biến thành một
 * trình vẽ.
 *
 * @param sourceTemplateZoneId khu này được chép từ khu nào của khung nền tảng; {@code null} nghĩa
 *     là tổ chức tự dựng. Khác {@code null} là chỗ câu "khu vực cố định, không thay đổi được"
 *     được thực thi — {@link #isFixed()} chặn đường sửa sơ đồ.
 * @param layout vị trí của khu trên mặt bằng; {@code null} nghĩa là tổ chức chưa đặt và hệ thống
 *     tự xếp (xem {@link FloorPlan}). Cố ý cho phép {@code null} thay vì ép một giá trị mặc định
 *     lúc ghi: "chưa đặt" và "đặt đúng bằng chỗ mặc định" là hai điều khác nhau — cái đầu đi theo
 *     bố cục tự động khi thêm khu mới, cái sau thì đứng yên.
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
        int sortOrder,
        UUID sourceTemplateZoneId,
        ZoneLayout layout) {

    /** Khu tổ chức tự dựng, chưa đặt vị trí trên mặt bằng. */
    public VenueZone(
            UUID id,
            UUID venueId,
            String zoneCode,
            String name,
            AdmissionKind kind,
            Integer rowCount,
            Integer seatsPerRow,
            Integer capacity,
            int sortOrder) {
        this(id, venueId, zoneCode, name, kind, rowCount, seatsPerRow, capacity, sortOrder, null, null);
    }

    /** Khu chép từ khung, chưa đặt vị trí trên mặt bằng. */
    public VenueZone(
            UUID id,
            UUID venueId,
            String zoneCode,
            String name,
            AdmissionKind kind,
            Integer rowCount,
            Integer seatsPerRow,
            Integer capacity,
            int sortOrder,
            UUID sourceTemplateZoneId) {
        this(id, venueId, zoneCode, name, kind, rowCount, seatsPerRow, capacity, sortOrder, sourceTemplateZoneId, null);
    }

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

    /** Khu đến từ khung của nền tảng, nên tổ chức không sửa được hình dạng của nó. */
    public boolean isFixed() {
        return sourceTemplateZoneId != null;
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

    public VenueZone withLayout(ZoneLayout newLayout) {
        return new VenueZone(
                id,
                venueId,
                zoneCode,
                name,
                kind,
                rowCount,
                seatsPerRow,
                capacity,
                sortOrder,
                sourceTemplateZoneId,
                newLayout);
    }
}
