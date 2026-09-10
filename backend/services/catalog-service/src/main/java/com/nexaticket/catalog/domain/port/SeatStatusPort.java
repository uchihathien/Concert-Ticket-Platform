// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Trạng thái tồn kho của một suất, đọc từ inventory-service.
 *
 * <p>Anti-Corruption Layer: kiểu ở đây là kiểu của Catalog, không phải hình dạng JSON của
 * Inventory. Inventory đổi tên field thì chỗ duy nhất phải sửa là adapter.
 *
 * <p>Catalog <b>không</b> giữ bản sao của trạng thái ghế, và đó là quyết định có chủ đích: tồn kho
 * bị ghi 10k lần/giây lúc mở bán, còn màn hình này được mở vài lần một ngày. Đồng bộ một bản sao
 * như thế qua sự kiện là chép sai một thứ mà chỉ cần hỏi.
 */
public interface SeatStatusPort {

    /**
     * @return rỗng nếu suất chưa được dựng tồn kho — sự kiện còn nháp thì đó là trạng thái bình
     *     thường, không phải lỗi
     * @throws UpstreamUnavailableException khi không hỏi được inventory-service
     */
    Optional<SessionSeatStatus> forSession(UUID eventSessionId);

    /**
     * @param availabilityVersion để màn hình biết số liệu này ứng với lần thay đổi tồn kho nào
     */
    record SessionSeatStatus(UUID eventSessionId, long availabilityVersion, List<ZoneSeatStatus> zones) {

        public SessionSeatStatus {
            zones = List.copyOf(zones);
        }

        public int total() {
            return zones.stream().mapToInt(ZoneSeatStatus::total).sum();
        }

        public int sold() {
            return zones.stream().mapToInt(ZoneSeatStatus::sold).sum();
        }
    }

    /** Đếm theo trạng thái, không phải danh sách từng ghế: một suất 5.000 ghế thì hai thứ đó khác nhau về bậc. */
    record ZoneSeatStatus(
            String zoneCode, String admissionType, int available, int held, int reserved, int sold, int blocked) {

        public int total() {
            return available + held + reserved + sold + blocked;
        }
    }
}
