// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase;
import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase.ProposalOutcome;
import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.support.FakeCommerce;
import com.nexaticket.aichatbox.support.InMemoryBookingProposalRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lập đề xuất đặt vé: cùng chuỗi kiểm với đường đặt vé, ghi đúng một dòng, và KHÔNG bao giờ chạm
 * tồn kho hay đơn hàng (story 1.2).
 */
class BookingProposalUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID chatSessionId = UUID.randomUUID();
    private final UUID eventSessionId = UUID.randomUUID();
    private final FakeCommerce.Catalog catalog = new FakeCommerce.Catalog();
    private final InMemoryBookingProposalRepository proposals = new InMemoryBookingProposalRepository();
    private final BookingProposalUseCase useCase =
            new BookingProposalUseCase(catalog, proposals, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void seed() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
    }

    private ProposalOutcome propose(String slug, UUID session, String zone, int qty) {
        return useCase.propose(userId, chatSessionId, slug, session, zone, qty);
    }

    @Test
    void hop_le_thi_ghi_de_xuat_voi_tong_tien_va_han_10_phut() {
        ProposalOutcome outcome = propose("dem-nhac-trinh", eventSessionId, "A", 2);

        assertThat(outcome).isInstanceOf(ProposalOutcome.Proposed.class);
        ProposalOutcome.Proposed proposed = (ProposalOutcome.Proposed) outcome;
        BookingProposal p = proposed.proposal();
        assertThat(p.userId()).isEqualTo(userId);
        assertThat(p.chatSessionId()).isEqualTo(chatSessionId);
        assertThat(p.eventSlug()).isEqualTo("dem-nhac-trinh");
        assertThat(p.eventSessionId()).isEqualTo(eventSessionId);
        assertThat(p.zoneCode()).isEqualTo("A");
        assertThat(p.unitPriceVnd()).isEqualTo(1_800_000L);
        assertThat(p.totalVnd()).isEqualTo(3_600_000L);
        assertThat(p.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(p.status()).isEqualTo(BookingProposalStatus.PROPOSED);
        // Epic 2 và 1.3 dựng từ đúng bản catalog vừa trả về.
        assertThat(proposed.event().title()).isEqualTo("Đêm nhạc Trịnh");
        assertThat(proposed.tier().name()).isEqualTo("VIP");
        assertThat(proposals.findById(p.id())).contains(p);
    }

    @Test
    void ma_khu_viet_thuong_van_khop_va_ghi_theo_chu_cua_catalog() {
        ProposalOutcome outcome = propose("dem-nhac-trinh", eventSessionId, "ga", 1);

        BookingProposal p = ((ProposalOutcome.Proposed) outcome).proposal();
        assertThat(p.zoneCode()).isEqualTo("GA");
        assertThat(p.unitPriceVnd()).isEqualTo(450_000L);
    }

    @Test
    void doi_so_ve_thi_de_xuat_moi_thay_de_xuat_cu_cung_khu() {
        BookingProposal first =
                ((ProposalOutcome.Proposed) propose("dem-nhac-trinh", eventSessionId, "A", 2)).proposal();

        BookingProposal second =
                ((ProposalOutcome.Proposed) propose("dem-nhac-trinh", eventSessionId, "A", 3)).proposal();

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.quantity()).isEqualTo(3);
        assertThat(proposals.findById(first.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.CANCELLED);
        assertThat(proposals.countByStatus()).containsEntry(BookingProposalStatus.PROPOSED, 1L);
    }

    @Test
    void khu_khac_thi_hai_de_xuat_cung_mo() {
        propose("dem-nhac-trinh", eventSessionId, "A", 1);
        propose("dem-nhac-trinh", eventSessionId, "GA", 1);

        assertThat(proposals.countByStatus()).containsEntry(BookingProposalStatus.PROPOSED, 2L);
    }

    @Test
    void tu_choi_thi_khong_ghi_gi() {
        assertThat(reason(propose("dem-nhac-trinh", eventSessionId, "A", 0))).contains("từ 1 trở lên");
        assertThat(reason(propose("dem-nhac-trinh", eventSessionId, "A", 7))).contains("tối đa 6 vé");
        assertThat(reason(propose("dem-nhac-trinh-2026", eventSessionId, "A", 1)))
                .contains("findEvents");
        assertThat(reason(propose("dem-nhac-trinh", UUID.randomUUID(), "A", 1))).contains("không thuộc sự kiện");
        assertThat(reason(propose("dem-nhac-trinh", eventSessionId, "Z9", 1))).contains("không có khu Z9");

        catalog.zones.remove("dem-nhac-trinh|A");
        assertThat(reason(propose("dem-nhac-trinh", eventSessionId, "A", 1))).contains("Sơ đồ sự kiện không có khu A");

        assertThat(proposals.countByStatus().values()).containsOnly(0L);
    }

    /** Đua hai lượt song song: cái sau gặp index bộ phận → lý do cho mô hình, không phải ngoại lệ. */
    @Test
    void trung_de_xuat_mo_la_ly_do_khong_phai_loi() {
        InMemoryBookingProposalRepository stubborn = new InMemoryBookingProposalRepository() {
            @Override
            public synchronized int cancelOpen(UUID u, UUID s, String z, Instant now) {
                return 0; // giả lập: lượt kia vừa chèn lại ngay sau khi mình huỷ
            }
        };
        BookingProposalUseCase racing = new BookingProposalUseCase(catalog, stubborn, Clock.fixed(NOW, ZoneOffset.UTC));
        racing.propose(userId, chatSessionId, "dem-nhac-trinh", eventSessionId, "A", 1);

        ProposalOutcome outcome = racing.propose(userId, chatSessionId, "dem-nhac-trinh", eventSessionId, "A", 2);

        assertThat(outcome).isInstanceOf(ProposalOutcome.Rejected.class);
        assertThat(reason(outcome)).contains("đang mở");
    }

    private static String reason(ProposalOutcome outcome) {
        assertThat(outcome).isInstanceOf(ProposalOutcome.Rejected.class);
        return ((ProposalOutcome.Rejected) outcome).reason();
    }
}
