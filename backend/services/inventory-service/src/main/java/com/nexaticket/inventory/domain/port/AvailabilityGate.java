// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.port;

import java.util.List;
import java.util.UUID;

/**
 * Cổng từ chối nhanh cho <b>vé ngồi</b>, đứng trước database.
 *
 * <p>Chỉ vé ngồi đi qua đây. Vé đứng không có gì để từ chối nhanh vì khách không chỉ đích danh đơn
 * vị nào — {@code SKIP LOCKED} đã cấp phát đúng trong một câu lệnh (ADR-1012).
 *
 * <p><b>Cổng này không phải chốt chặn oversell.</b> Chốt chặn là unique index
 * {@code uq_hold_item_active} trong database. Cổng chỉ để 9.900 request thua cuộc không phải chạm
 * database. Nếu nó sai (key hết hạn giữa chừng, Redis failover), database vẫn đúng.
 */
public interface AvailabilityGate {

    /**
     * Chiếm chỗ tất-cả-hoặc-không-gì. Kiểm tra hết rồi mới ghi — không có trạng thái nửa vời.
     *
     * @return danh sách chỗ đã bị người khác chiếm; rỗng nghĩa là chiếm thành công
     * @throws GateUnavailableException khi Redis không sẵn sàng — <b>không</b> fallback DB-only
     */
    List<UUID> tryAcquire(UUID eventSessionId, List<UUID> seatIds, UUID holdId, UUID userId, int ttlSeconds);

    /** Nhả chỗ. Gọi sau khi database rollback, và khi giữ chỗ hết hạn / thành đơn. */
    void release(UUID eventSessionId, List<UUID> seatIds);

    /** Redis chết thì trả 503 chứ không âm thầm bỏ qua cổng (ADR-0004). */
    class GateUnavailableException extends RuntimeException {
        public GateUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
