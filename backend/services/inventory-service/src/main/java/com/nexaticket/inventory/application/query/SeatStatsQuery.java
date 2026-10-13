// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.query;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeatStatsQuery {

    private final SeatStats.Reader reader;

    public SeatStatsQuery(SeatStats.Reader reader) {
        this.reader = reader;
    }

    /** Ảnh chụp hiện tại, không theo kỳ: tồn kho là trạng thái, không phải dòng sự kiện. */
    @Transactional(readOnly = true)
    public SeatStats.Stock stock(UUID organizationId) {
        return new SeatStats.Stock(
                reader.stockByTicketType(organizationId),
                reader.holdsExpiringWithin(organizationId, SeatStats.EXPIRING_MINUTES),
                SeatStats.EXPIRING_MINUTES);
    }
}
