// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.port;

import com.nexaticket.inventory.domain.model.SessionInventory;
import java.util.Optional;
import java.util.UUID;

/** Cấu hình tồn kho và version khả dụng của một suất diễn. */
public interface SessionInventoryRepository {

    Optional<SessionInventory> findBySessionId(UUID eventSessionId);

    /**
     * Version hiện tại — chỉ đọc, dùng làm ETag cho {@code GET /sessions/{id}/seats}.
     *
     * <p>Phải là đường thuần đọc: nếu đường đọc cũng sinh version mới thì ETag đổi ở mọi request và
     * client không bao giờ nhận được {@code 304}, tức là mất toàn bộ tác dụng của cache trên
     * payload ~400KB.
     */
    long currentAvailabilityVersion(UUID eventSessionId);

    /**
     * Đánh dấu tồn kho của suất đã đổi, trả về version mới.
     *
     * <p>Giá trị lấy từ sequence toàn cục nên đơn điệu tăng và không bao giờ lặp lại — client so
     * sánh số là đủ, không cần lo ABA. Nhưng nó được <b>lưu vào hàng của suất diễn</b>, không phải
     * đọc thẳng từ sequence: một số toàn cục sẽ nhảy mỗi khi <i>bất kỳ</i> suất nào đổi, khiến
     * client đang xem suất A phải refetch vì suất B vừa có người giữ chỗ.
     *
     * <p>Đây là một UPDATE vào một hàng, tức là hot row của suất đang hot. Vì thế <b>gọi nó ở
     * bước cuối cùng của transaction</b>: khoá hàng chỉ bị giữ từ lệnh này tới COMMIT.
     */
    long bumpAvailabilityVersion(UUID eventSessionId);
}
