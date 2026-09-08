// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.port;

import java.util.List;
import java.util.UUID;

/**
 * Gọi inventory-service. Đây là Anti-Corruption Layer: kiểu dữ liệu ở đây là của Ordering, không
 * phải bản sao DTO của Inventory.
 */
public interface InventoryPort {

    /**
     * Giữ chỗ → đặt chỗ. Idempotent theo {@code orderId} ở phía Inventory.
     *
     * @throws RemoteCallException khi mạng hỏng hoặc service không phản hồi trong hạn
     * @throws SeatsUnavailableException khi Inventory từ chối vì lý do nghiệp vụ
     */
    Reservation reserve(UUID orderId, UUID holdId, UUID userId);

    /** Bù trừ: nhả chỗ. Phải idempotent — job quét có thể gọi lại nhiều lần. */
    void cancelReservation(UUID orderId);

    /** Chi tiết chỗ đã đặt, đủ để dựng dòng đơn hàng mà không cần gọi thêm lần nữa. */
    record Reservation(UUID eventSessionId, UUID organizationId, List<Seat> seats) {}

    record Seat(
            UUID sessionSeatId,
            String seatCode,
            String zoneCode,
            String admissionType,
            String seatLabel,
            UUID ticketTypeId,
            String ticketTypeName,
            long priceVnd) {}

    /** Inventory từ chối có lý do nghiệp vụ — bù trừ không cần, chỉ trả lỗi cho khách. */
    class SeatsUnavailableException extends RuntimeException {
        private final String code;

        public SeatsUnavailableException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
