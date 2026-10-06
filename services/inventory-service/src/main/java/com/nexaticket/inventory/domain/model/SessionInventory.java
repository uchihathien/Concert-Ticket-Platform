// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Tồn kho của một suất diễn — gốc consistency của mọi thao tác giữ chỗ.
 *
 * <p>Giữ bản sao {@code organizationId} và trần mua vé từ Catalog. Bản sao là cố ý: nhờ nó mà giữ
 * chỗ không gọi service nào khác, điều bắt buộc ở 10k đồng thời (ADR-1002).
 */
public record SessionInventory(
        UUID id,
        UUID eventSessionId,
        UUID eventId,
        UUID organizationId,
        Instant salesOpenAt,
        Instant salesCloseAt,
        PurchaseLimits limits,
        Duration holdTtl) {

    /** Ngoài khung giờ này thì không giữ chỗ được, kể cả còn chỗ trống. */
    public boolean isSalesOpenAt(Instant now) {
        return !now.isBefore(salesOpenAt) && now.isBefore(salesCloseAt);
    }

    public Instant holdExpiryFrom(Instant now) {
        return now.plus(holdTtl);
    }
}
