// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.booking.ProposalExpiryJob;
import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.support.InMemoryBookingProposalRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Job hết hạn chỉ đụng đề xuất PROPOSED quá giờ, chạy lại không đổi gì, và nuôi metric (story 1.6). */
class ProposalExpiryJobTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private final InMemoryBookingProposalRepository proposals = new InMemoryBookingProposalRepository();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AgentMetrics metrics = new AgentMetrics(registry);
    private final ProposalExpiryJob job = new ProposalExpiryJob(proposals, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

    private BookingProposal proposal(Duration ttlFromCreation, Instant createdAt) {
        return BookingProposal.proposed(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dem-nhac-trinh",
                UUID.randomUUID(),
                "A",
                1,
                100L,
                createdAt,
                ttlFromCreation);
    }

    private double counter(String status) {
        return registry.get("aichatbox.proposals")
                .tag("status", status)
                .functionCounter()
                .count();
    }

    @Test
    void chi_de_xuat_PROPOSED_qua_gio_moi_het_han_va_metric_la_anh_chup_theo_bang() {
        BookingProposal overdue = proposal(Duration.ofMinutes(10), NOW.minus(Duration.ofMinutes(11)));
        BookingProposal exactly = proposal(Duration.ofMinutes(10), NOW.minus(Duration.ofMinutes(10)));
        BookingProposal fresh = proposal(Duration.ofMinutes(10), NOW.minus(Duration.ofMinutes(5)));
        BookingProposal confirmed = proposal(Duration.ofMinutes(10), NOW.minus(Duration.ofMinutes(30)));
        BookingProposal cancelled = proposal(Duration.ofMinutes(10), NOW.minus(Duration.ofMinutes(30)));
        for (BookingProposal p : new BookingProposal[] {overdue, exactly, fresh, confirmed, cancelled}) {
            proposals.insert(p);
        }
        proposals.markConfirmed(
                confirmed.id(), new BookingProposal.Order(UUID.randomUUID(), "NT-1", "https://pay", null), NOW);
        proposals.cancel(cancelled.id(), cancelled.userId(), NOW);

        assertThat(job.expireOverdue()).isEqualTo(2);

        assertThat(proposals.findById(overdue.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.EXPIRED);
        assertThat(proposals.findById(exactly.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.EXPIRED);
        assertThat(proposals.findById(fresh.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.PROPOSED);
        assertThat(proposals.findById(confirmed.id()).orElseThrow().status())
                .isEqualTo(BookingProposalStatus.CONFIRMED);
        assertThat(proposals.findById(cancelled.id()).orElseThrow().status())
                .isEqualTo(BookingProposalStatus.CANCELLED);

        // PROPOSED = tổng mọi đề xuất từng được lập; ba trạng thái kia = số đã kết thúc ở đó.
        assertThat(counter("PROPOSED")).isEqualTo(5);
        assertThat(counter("EXPIRED")).isEqualTo(2);
        assertThat(counter("CONFIRMED")).isEqualTo(1);
        assertThat(counter("CANCELLED")).isEqualTo(1);
    }

    @Test
    void chay_hai_lan_lien_tiep_khong_doi_gi_them() {
        proposals.insert(proposal(Duration.ofMinutes(10), NOW.minus(Duration.ofMinutes(20))));

        assertThat(job.expireOverdue()).isEqualTo(1);
        assertThat(job.expireOverdue()).isZero();
        assertThat(counter("EXPIRED")).isEqualTo(1);
        assertThat(counter("PROPOSED")).isEqualTo(1);
    }

    @Test
    void khong_co_gi_het_han_van_cap_nhat_metric() {
        BookingProposal fresh = proposal(Duration.ofMinutes(10), NOW);
        proposals.insert(fresh);
        proposals.markConfirmed(
                fresh.id(), new BookingProposal.Order(UUID.randomUUID(), "NT-2", "https://pay", null), NOW);

        assertThat(job.expireOverdue()).isZero();

        assertThat(counter("CONFIRMED")).isEqualTo(1);
        assertThat(counter("PROPOSED")).isEqualTo(1);
    }
}
