// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.port.BookingProposalRepository;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V0104 và adapter trên Postgres thật: index bộ phận, CHECK trạng thái/mốc thời gian, và mỗi phép
 * chuyển trạng thái thắng đúng một lần (story 1.1).
 */
class JdbcBookingProposalRepositoryIT extends AiChatboxTestBase {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    @Autowired
    BookingProposalRepository repository;

    @Autowired
    JdbcClient db;

    private static BookingProposal proposal(UUID userId, UUID eventSessionId, String zone, int qty) {
        return BookingProposal.proposed(
                UUID.randomUUID(),
                userId,
                UUID.randomUUID(),
                "dem-nhac-trinh",
                eventSessionId,
                zone,
                qty,
                1_800_000L,
                NOW,
                TTL);
    }

    private static BookingProposal.Order order() {
        return new BookingProposal.Order(
                UUID.randomUUID(), "NT-240009", "https://pay.payos.vn/web/abc", NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void insert_roi_findById_khop_tung_truong() {
        BookingProposal p = proposal(UUID.randomUUID(), UUID.randomUUID(), "A", 2);

        repository.insert(p);

        assertThat(repository.findById(p.id())).contains(p);
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void mot_de_xuat_mo_cho_mot_khu_nhung_khu_khac_hoac_suat_khac_thi_duoc() {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        repository.insert(proposal(user, session, "A", 1));

        assertThatThrownBy(() -> repository.insert(proposal(user, session, "A", 3)))
                .isInstanceOf(DuplicateKeyException.class);
        repository.insert(proposal(user, session, "GA", 1));
        repository.insert(proposal(user, UUID.randomUUID(), "A", 1));
        repository.insert(proposal(UUID.randomUUID(), session, "A", 1));
    }

    @Test
    void sau_cancelOpen_thi_chen_lai_duoc() {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        BookingProposal first = proposal(user, session, "A", 1);
        repository.insert(first);

        assertThat(repository.cancelOpen(user, session, "A", NOW)).isEqualTo(1);
        assertThat(repository.cancelOpen(user, session, "A", NOW)).isZero();
        repository.insert(proposal(user, session, "A", 3));

        assertThat(repository.findById(first.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.CANCELLED);
    }

    @Test
    void markConfirmed_thang_dung_mot_lan_va_ghi_don() {
        BookingProposal p = proposal(UUID.randomUUID(), UUID.randomUUID(), "A", 2);
        repository.insert(p);
        BookingProposal.Order order = order();

        assertThat(repository.markConfirmed(p.id(), order, NOW.plusSeconds(30))).isTrue();
        assertThat(repository.markConfirmed(p.id(), order(), NOW.plusSeconds(31)))
                .isFalse();

        BookingProposal loaded = repository.findById(p.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(BookingProposalStatus.CONFIRMED);
        assertThat(loaded.order()).isEqualTo(order);
        assertThat(loaded.confirmedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(loaded.isOpen(NOW)).isFalse();
    }

    @Test
    void markConfirmed_khong_co_han_thanh_toan_van_ghi_duoc() {
        BookingProposal p = proposal(UUID.randomUUID(), UUID.randomUUID(), "A", 1);
        repository.insert(p);
        BookingProposal.Order order = new BookingProposal.Order(UUID.randomUUID(), "NT-1", "https://pay", null);

        assertThat(repository.markConfirmed(p.id(), order, NOW)).isTrue();
        assertThat(repository.findById(p.id()).orElseThrow().order()).isEqualTo(order);
    }

    @Test
    void cancel_chi_dung_chu_va_chi_khi_con_PROPOSED() {
        UUID owner = UUID.randomUUID();
        BookingProposal p = proposal(owner, UUID.randomUUID(), "A", 1);
        repository.insert(p);

        assertThat(repository.cancel(p.id(), UUID.randomUUID(), NOW)).isFalse();
        assertThat(repository.findById(p.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.PROPOSED);
        assertThat(repository.cancel(p.id(), owner, NOW)).isTrue();
        assertThat(repository.cancel(p.id(), owner, NOW)).isFalse();

        BookingProposal confirmed = proposal(owner, UUID.randomUUID(), "A", 1);
        repository.insert(confirmed);
        repository.markConfirmed(confirmed.id(), order(), NOW);
        assertThat(repository.cancel(confirmed.id(), owner, NOW)).isFalse();
        assertThat(repository.findById(confirmed.id()).orElseThrow().status())
                .isEqualTo(BookingProposalStatus.CONFIRMED);
    }

    @Test
    void expireOverdue_chon_dung_dong_theo_han_va_trang_thai() {
        UUID user = UUID.randomUUID();
        BookingProposal overdue = proposal(user, UUID.randomUUID(), "A", 1); // hết hạn NOW + 10'
        BookingProposal exactly = BookingProposal.proposed(
                UUID.randomUUID(), user, UUID.randomUUID(), "x", UUID.randomUUID(), "A", 1, 1L, NOW, Duration.ZERO);
        BookingProposal fresh = BookingProposal.proposed(
                UUID.randomUUID(),
                user,
                UUID.randomUUID(),
                "x",
                UUID.randomUUID(),
                "A",
                1,
                1L,
                NOW,
                Duration.ofHours(1));
        BookingProposal confirmed = proposal(user, UUID.randomUUID(), "GA", 1);
        for (BookingProposal p : new BookingProposal[] {overdue, exactly, fresh, confirmed}) {
            repository.insert(p);
        }
        repository.markConfirmed(confirmed.id(), order(), NOW);

        // expires_at == now cũng hết hạn (`<=`), dòng còn hạn và dòng CONFIRMED không bị đụng.
        assertThat(repository.expireOverdue(NOW.plus(TTL))).isEqualTo(2);
        assertThat(repository.expireOverdue(NOW.plus(TTL))).isZero();

        assertThat(repository.findById(overdue.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.EXPIRED);
        assertThat(repository.findById(exactly.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.EXPIRED);
        assertThat(repository.findById(fresh.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.PROPOSED);
        assertThat(repository.findById(confirmed.id()).orElseThrow().status())
                .isEqualTo(BookingProposalStatus.CONFIRMED);
    }

    @Test
    void countByStatus_du_bon_khoa_ke_ca_bang_rong() {
        assertThat(repository.countByStatus())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        BookingProposalStatus.PROPOSED, 0L,
                        BookingProposalStatus.CONFIRMED, 0L,
                        BookingProposalStatus.EXPIRED, 0L,
                        BookingProposalStatus.CANCELLED, 0L));

        UUID user = UUID.randomUUID();
        BookingProposal a = proposal(user, UUID.randomUUID(), "A", 1);
        BookingProposal b = proposal(user, UUID.randomUUID(), "A", 1);
        BookingProposal c = proposal(user, UUID.randomUUID(), "A", 1);
        repository.insert(a);
        repository.insert(b);
        repository.insert(c);
        repository.markConfirmed(b.id(), order(), NOW);
        repository.cancel(c.id(), user, NOW);

        assertThat(repository.countByStatus())
                .containsEntry(BookingProposalStatus.PROPOSED, 1L)
                .containsEntry(BookingProposalStatus.CONFIRMED, 1L)
                .containsEntry(BookingProposalStatus.CANCELLED, 1L)
                .containsEntry(BookingProposalStatus.EXPIRED, 0L);
    }

    /** CHECK ck_proposal_confirmed: CONFIRMED mà không có order_id (hoặc không có confirmed_at) bị từ chối. */
    @Test
    void check_tu_choi_CONFIRMED_thieu_order_id() {
        assertThatThrownBy(() -> db.sql(
                                """
                                insert into booking_proposals (id, user_id, chat_session_id, event_slug, event_session_id,
                                    zone_code, quantity, unit_price_vnd, total_vnd, status, expires_at, confirmed_at)
                                values (:id, :u, :s, 'x', :es, 'A', 1, 1, 1, 'CONFIRMED', :exp, :now)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("u", UUID.randomUUID())
                        .param("s", UUID.randomUUID())
                        .param("es", UUID.randomUUID())
                        .param("exp", Timestamp.from(NOW.plus(TTL)))
                        .param("now", Timestamp.from(NOW))
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
