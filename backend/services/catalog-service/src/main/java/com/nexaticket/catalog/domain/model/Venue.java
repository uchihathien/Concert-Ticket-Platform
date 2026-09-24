// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * Địa điểm cùng các khu của nó.
 *
 * <p>Khu là entity con của địa điểm, không phải aggregate riêng: không ai sửa một khu mà không
 * nghĩ tới cả địa điểm, và bất biến "mã khu không trùng nhau" chỉ kiểm được khi nhìn cả tập.
 *
 * @param stage sân khấu trên mặt bằng; {@code null} nghĩa là chưa khai và {@link StageArea#DEFAULT}
 *     được dùng. Nằm ở địa điểm chứ không ở sự kiện vì sân khấu là kết cấu của khán phòng — hai sự
 *     kiện ở cùng địa điểm nhìn thấy cùng một sân khấu.
 */
public record Venue(
        UUID id,
        UUID organizationId,
        String name,
        String city,
        String address,
        List<VenueZone> zones,
        UUID sourceTemplateId,
        StageArea stage) {

    /** Địa điểm tổ chức tự dựng: không sinh ra từ khung nào, sân khấu để mặc định. */
    public Venue(UUID id, UUID organizationId, String name, String city, String address, List<VenueZone> zones) {
        this(id, organizationId, name, city, address, zones, null, null);
    }

    public Venue(
            UUID id,
            UUID organizationId,
            String name,
            String city,
            String address,
            List<VenueZone> zones,
            UUID sourceTemplateId) {
        this(id, organizationId, name, city, address, zones, sourceTemplateId, null);
    }

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
        return new Venue(UUID.randomUUID(), organizationId, name, city, address, List.of(), null, null);
    }

    /**
     * Địa điểm dựng từ khung của nền tảng.
     *
     * <p>Các khu được <b>chép</b> vào đây chứ không tham chiếu tới khung: sự kiện đã tạo phải giữ
     * nguyên sơ đồ của nó kể cả khi nền tảng sửa hay lưu trữ khung sau đó. Sân khấu chép theo cùng
     * lý do — khung tả cả khán phòng, và khán phòng không có sân khấu thì không tả xong.
     */
    public static Venue fromTemplate(
            UUID organizationId, String name, String city, String address, UUID templateId, StageArea stage) {
        UUID venueId = UUID.randomUUID();
        return new Venue(venueId, organizationId, name, city, address, List.of(), templateId, stage);
    }

    /** Tổng sức chứa, cộng cả khu ngồi lẫn khu đứng. */
    public int capacity() {
        return zones.stream().mapToInt(VenueZone::seatCount).sum();
    }

    public boolean hasZones() {
        return !zones.isEmpty();
    }

    /** Sân khấu đã khai, hoặc sân khấu mặc định. Đường đọc không bao giờ phải tự kiểm {@code null}. */
    public StageArea stageOrDefault() {
        return stage == null ? StageArea.DEFAULT : stage;
    }

    /** Mặt bằng đã giải: sân khấu, đường bao từng khu, toạ độ từng ghế. */
    public FloorPlan floorPlan() {
        return FloorPlan.of(this);
    }

    /**
     * Sơ đồ của địa điểm này là kết cấu cố định do nền tảng định nghĩa.
     *
     * <p>Hỏi ở tầng địa điểm chứ không hỏi từng khu: một địa điểm nửa cố định nửa tự dựng là thứ
     * không ai giải thích được cho người dùng — "khu nào sửa được" trở thành một câu hỏi phải tra
     * từng dòng. Muốn sơ đồ riêng thì dựng địa điểm riêng, và đó là một thao tác một phút.
     */
    public boolean isFromTemplate() {
        return sourceTemplateId != null;
    }
}
