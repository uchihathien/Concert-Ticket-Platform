// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.query;

import java.util.List;
import java.util.UUID;

/**
 * Tồn kho của một suất, gộp theo zone và theo trạng thái.
 *
 * <p>Khác {@link SeatMapView} ở chỗ nó dành cho <b>ban tổ chức</b>, không cho khách: không có toạ
 * độ, không có nhãn ghế, không có hạn mức của người xem. Chỉ có những con số một người quản lý sự
 * kiện cần nhìn.
 *
 * <p>Không có danh sách từng chỗ, và đó là quyết định có chủ đích: sơ đồ chỗ đã có
 * {@code GET /v1/sessions/{id}/seats} với ETag và nén. Nhét 5.000 dòng vào đây là dựng đường thứ
 * hai cho cùng dữ liệu, chậm hơn và không có cache.
 *
 * @param availabilityVersion để bên gọi biết số liệu này ứng với lần thay đổi tồn kho nào
 */
public record SeatStatusView(UUID eventSessionId, long availabilityVersion, List<ZoneStatus> zones) {

    /** @param blocked chỗ có mặt trong tồn kho để sơ đồ hiện đúng hình khán phòng, nhưng không bán */
    public record ZoneStatus(
            String zoneCode, String admissionType, int available, int held, int reserved, int sold, int blocked) {}
}
