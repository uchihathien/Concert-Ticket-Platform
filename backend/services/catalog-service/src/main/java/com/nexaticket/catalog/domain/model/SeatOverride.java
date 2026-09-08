// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.UUID;

/**
 * Ghi đè một chỗ cụ thể trong khu vực đã bật.
 *
 * <p>Đây là cách tổ chức chạm được vào chỗ áp cứng mà không sửa dữ liệu của địa điểm: họ không
 * xoá ghế của nhà hát, họ chỉ nói "ở sự kiện của tôi, ghế này không tồn tại".
 */
public record SeatOverride(UUID zoneId, String seatCode, Action action, UUID ticketTierId) {

    public enum Action {
        /** Chỗ không tồn tại ở sự kiện này — sân khấu dựng đè lên, lối đi mở rộng. */
        REMOVE,

        /** Chỗ tồn tại nhưng không bán: ghế kỹ thuật, ghế mời, ghế hỏng. */
        BLOCK,

        /** Đổi hạng vé riêng cho chỗ này. */
        SET_TIER
    }
}
