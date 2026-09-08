// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import com.nexaticket.catalog.domain.model.PurchaseLimits;
import com.nexaticket.catalog.domain.model.SeatingPlan;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Đọc toàn bộ thiết kế chỗ ngồi của một suất diễn. */
public interface SeatingPlanRepository {

    /** Nạp khu vực, cách dùng, ghi đè và hạng vé trong một lần — publish cần tất cả cùng lúc. */
    Optional<SeatingPlan> load(UUID eventSessionId);

    Optional<SessionTiming> timingOf(UUID eventSessionId);

    /** Trần ghi đè theo suất diễn; các trường null nghĩa là kế thừa. */
    PurchaseLimits sessionLimits(UUID eventSessionId);

    void markMaterialized(UUID eventSessionId, Instant at);

    record SessionTiming(
            UUID eventId, UUID organizationId, Instant startsAt, Instant salesOpenAt, Instant salesCloseAt) {}
}
