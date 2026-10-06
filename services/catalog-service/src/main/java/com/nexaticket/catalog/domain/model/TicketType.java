// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.UUID;

/**
 * Hạng vé: một khu, ở một suất diễn, với một mức giá.
 *
 * <p>Gắn với khu chứ không gắn với từng ghế, nên bất biến "mọi ghế bán được đều có giá" được bảo
 * đảm bởi cấu trúc chứ không bởi một bước kiểm tra: khai hạng vé cho một khu là đã phủ hết ghế
 * trong khu đó. Đây là lý do bước "gán ghế → hạng vé" của wizard trong docs biến mất — không phải
 * bị bỏ sót, mà là không còn việc để làm.
 */
public record TicketType(UUID id, UUID eventSessionId, UUID venueZoneId, String name, long priceVnd, int sortOrder) {

    public TicketType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Hạng vé phải có tên");
        }
        if (priceVnd < 0) {
            throw new IllegalArgumentException("Giá vé không được âm");
        }
    }

    public static TicketType create(UUID eventSessionId, UUID venueZoneId, String name, long priceVnd, int sortOrder) {
        return new TicketType(UUID.randomUUID(), eventSessionId, venueZoneId, name, priceVnd, sortOrder);
    }
}
