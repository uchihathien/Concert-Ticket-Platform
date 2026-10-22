// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ảnh chụp tồn kho của một tổ chức. */
class SeatStatsQueryTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-4000-a000-0000000000f1");

    private final SeatStats.Reader reader = mock(SeatStats.Reader.class);
    private final SeatStatsQuery query = new SeatStatsQuery(reader);

    @Test
    @DisplayName("Ghép tồn kho theo loại vé với các lượt giữ sắp hết hạn trong 5 phút, của đúng tổ chức")
    void ghep_ton_kho_va_giu_cho_sap_het_han() {
        var stock = new SeatStats.TicketTypeStock(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Ve ngoi", 1_500_000L, 100, 40, 10, 45, 5);
        var expiring = new SeatStats.ExpiringHolds(UUID.randomUUID(), 3);
        when(reader.stockByTicketType(ORG)).thenReturn(List.of(stock));
        when(reader.holdsExpiringWithin(ORG, 5)).thenReturn(List.of(expiring));

        SeatStats.Stock result = query.stock(ORG);

        assertThat(result.ticketTypes()).containsExactly(stock);
        assertThat(result.expiringHolds()).containsExactly(expiring);
        assertThat(result.expiringWithinMinutes())
                .isEqualTo(SeatStats.EXPIRING_MINUTES)
                .isEqualTo(5);
        verify(reader).holdsExpiringWithin(ORG, 5);
    }

    @Test
    @DisplayName("Tổ chức chưa có suất nào: danh sách rỗng, không phải lỗi")
    void to_chuc_chua_co_suat() {
        when(reader.stockByTicketType(ORG)).thenReturn(List.of());
        when(reader.holdsExpiringWithin(ORG, 5)).thenReturn(List.of());

        SeatStats.Stock result = query.stock(ORG);

        assertThat(result.ticketTypes()).isEmpty();
        assertThat(result.expiringHolds()).isEmpty();
    }
}
