// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bất biến của một đề xuất: tổng tiền, hạn, và "còn bấm được" (story 1.1). */
class BookingProposalTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private static BookingProposal sample() {
        return BookingProposal.proposed(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dem-nhac-trinh",
                UUID.randomUUID(),
                "A",
                2,
                1_800_000L,
                NOW,
                Duration.ofMinutes(10));
    }

    @Test
    void proposed_tinh_tong_tien_va_han_tai_mot_cho() {
        BookingProposal p = sample();

        assertThat(p.totalVnd()).isEqualTo(3_600_000L);
        assertThat(p.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(p.createdAt()).isEqualTo(NOW);
        assertThat(p.status()).isEqualTo(BookingProposalStatus.PROPOSED);
        assertThat(p.order()).isNull();
        assertThat(p.confirmedAt()).isNull();
    }

    @Test
    void isOpen_quanh_bien_het_han() {
        BookingProposal p = sample();
        Instant expiry = p.expiresAt();

        assertThat(p.isOpen(NOW)).isTrue();
        assertThat(p.isOpen(expiry.minusMillis(1))).isTrue();
        // Đúng mốc hết hạn là hết — khớp `expires_at <= now` của job hết hạn.
        assertThat(p.isOpen(expiry)).isFalse();
        assertThat(p.isOpen(expiry.plusSeconds(1))).isFalse();
    }

    @Test
    void khong_phai_PROPOSED_thi_khong_mo_du_con_han() {
        BookingProposal p = sample();
        BookingProposal confirmed = new BookingProposal(
                p.id(),
                p.userId(),
                p.chatSessionId(),
                p.eventSlug(),
                p.eventSessionId(),
                p.zoneCode(),
                p.quantity(),
                p.unitPriceVnd(),
                p.totalVnd(),
                BookingProposalStatus.CONFIRMED,
                new BookingProposal.Order(UUID.randomUUID(), "NT-1", "https://pay", null),
                p.expiresAt(),
                p.createdAt(),
                NOW);

        assertThat(confirmed.isOpen(NOW)).isFalse();
    }

    @Test
    void tong_tien_lech_don_gia_nhan_so_ve_bi_tu_choi() {
        BookingProposal p = sample();

        assertThatThrownBy(() -> new BookingProposal(
                        p.id(),
                        p.userId(),
                        p.chatSessionId(),
                        p.eventSlug(),
                        p.eventSessionId(),
                        p.zoneCode(),
                        2,
                        1_800_000L,
                        1_800_000L,
                        p.status(),
                        null,
                        p.expiresAt(),
                        p.createdAt(),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tổng tiền");
    }

    @Test
    void so_ve_duoi_1_bi_tu_choi() {
        assertThatThrownBy(() -> BookingProposal.proposed(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "x",
                        UUID.randomUUID(),
                        "A",
                        0,
                        100L,
                        NOW,
                        Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
