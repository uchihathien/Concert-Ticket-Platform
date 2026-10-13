// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.query;

import java.util.List;
import java.util.UUID;

/** Hình dạng số liệu tồn kho tổng hợp của một tổ chức. */
public final class SeatStats {

    private SeatStats() {}

    /**
     * Tồn kho của một loại vé trong một suất.
     *
     * @param capacity tổng số chỗ đã dựng, gồm cả chỗ bị khoá
     * @param held chỗ đang giữ: trong giỏ ({@code HELD}) hoặc đang chờ thanh toán ({@code RESERVED})
     */
    public record TicketTypeStock(
            UUID eventId,
            UUID eventSessionId,
            UUID ticketTypeId,
            String ticketTypeName,
            long priceVnd,
            long capacity,
            long sold,
            long held,
            long available,
            long blocked) {}

    /** @param seats số chỗ trong các lượt giữ sẽ hết hạn trong {@link #EXPIRING_MINUTES} phút tới */
    public record ExpiringHolds(UUID eventSessionId, long seats) {}

    public record Stock(
            List<TicketTypeStock> ticketTypes, List<ExpiringHolds> expiringHolds, int expiringWithinMinutes) {}

    public static final int EXPIRING_MINUTES = 5;

    /** Cổng đọc; mọi phép gộp chạy trong database. */
    public interface Reader {
        List<TicketTypeStock> stockByTicketType(UUID organizationId);

        List<ExpiringHolds> holdsExpiringWithin(UUID organizationId, int minutes);
    }
}
