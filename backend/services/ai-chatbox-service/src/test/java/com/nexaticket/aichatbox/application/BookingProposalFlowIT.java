// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase;
import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase.Confirmed;
import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase.ProposalOutcome;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.BookingProposalRepository;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import com.nexaticket.aichatbox.support.FakeCommerce;
import com.nexaticket.aichatbox.support.FakeCommerceBeans;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Luồng nút Xác nhận trên Postgres thật: dòng CONFIRMED với bốn cột đơn, lượt ASSISTANT trong
 * chat_messages, 409 kèm meta ở lần hai, 410 khi hết hạn, và thẻ đang mở ở màn hình khách (story 1.4).
 */
@Import(FakeCommerceBeans.class)
class BookingProposalFlowIT extends AiChatboxTestBase {

    @Autowired
    BookingProposalUseCase proposals;

    @Autowired
    BookingProposalRepository repository;

    @Autowired
    HandoffUseCase handoffs;

    @Autowired
    FakeCommerce.Catalog catalog;

    @Autowired
    FakeCommerce.Ordering ordering;

    @Autowired
    JdbcClient db;

    private BookingProposal propose(UUID userId, UUID chatSessionId, UUID eventSessionId, String zone) {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        ordering.nextOrder = new PlacedOrder(
                UUID.randomUUID(),
                "NT-" + UUID.randomUUID().toString().substring(0, 6),
                1_800_000L,
                "https://pay.payos.vn/web/" + UUID.randomUUID(),
                Instant.now().plus(Duration.ofMinutes(15)),
                "000201010212",
                "REF-1");
        return ((ProposalOutcome.Proposed)
                        proposals.propose(userId, chatSessionId, "dem-nhac-trinh", eventSessionId, zone, 1))
                .proposal();
    }

    @Test
    void xac_nhan_ghi_don_vao_bang_va_luot_ASSISTANT_vao_hoi_thoai() {
        UUID userId = UUID.randomUUID();
        UUID chatSessionId = UUID.randomUUID();
        BookingProposal p = propose(userId, chatSessionId, UUID.randomUUID(), "A");

        Confirmed c = proposals.confirm(userId, p.id());

        BookingProposal saved = repository.findById(p.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(BookingProposalStatus.CONFIRMED);
        assertThat(saved.order().orderId()).isEqualTo(c.order().orderId());
        assertThat(saved.order().orderNumber()).isEqualTo(c.order().orderNumber());
        assertThat(saved.order().checkoutUrl()).isEqualTo(c.order().checkoutUrl());
        assertThat(saved.order().paymentExpiresAt()).isNotNull();
        assertThat(saved.confirmedAt()).isNotNull();
        assertThat(db.sql("select role from chat_messages where session_id = :s order by created_at")
                        .param("s", chatSessionId)
                        .query(String.class)
                        .list())
                .containsExactly("ASSISTANT");

        // Lần hai: 409 kèm đơn cũ, không tạo đơn thứ hai.
        assertThatThrownBy(() -> proposals.confirm(userId, p.id())).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(AiChatboxErrorCode.PROPOSAL_ALREADY_CONFIRMED);
            assertThat(e.meta()).containsKeys("order", "action");
        });
        assertThat(ordering.placeKeys)
                .filteredOn(k -> k.equals("chat-order-" + p.id()))
                .hasSize(1);

        // Màn hình khách sau khi tải lại: thẻ PAYMENT dựng từ dữ liệu đã lưu.
        var thread = handoffs.customerThread(chatSessionId, userId, 50);
        assertThat(thread.action()).isNotNull();
        assertThat(thread.action().kind()).isEqualTo("PAYMENT");
        assertThat(thread.action().orderNumber()).isEqualTo(c.order().orderNumber());
        assertThat(thread.action().vietQrPayload()).isNull();
    }

    @Test
    void huy_va_het_han() {
        UUID userId = UUID.randomUUID();
        UUID chatSessionId = UUID.randomUUID();
        BookingProposal p = propose(userId, chatSessionId, UUID.randomUUID(), "A");

        // Thẻ đề xuất đang mở hiện ở màn hình khách.
        var open = handoffs.customerThread(chatSessionId, userId, 50);
        assertThat(open.action()).isNotNull();
        assertThat(open.action().kind()).isEqualTo("BOOKING_PROPOSAL");
        assertThat(open.action().id()).isEqualTo(p.id());

        proposals.cancel(userId, p.id());
        assertThat(repository.findById(p.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.CANCELLED);
        assertThat(handoffs.customerThread(chatSessionId, userId, 50).action()).isNull();

        // Hết hạn: job (hoặc thời gian) đã chuyển EXPIRED → 410.
        BookingProposal q = propose(userId, chatSessionId, UUID.randomUUID(), "GA");
        repository.expireOverdue(q.expiresAt());
        assertThatThrownBy(() -> proposals.confirm(userId, q.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(AiChatboxErrorCode.PROPOSAL_EXPIRED));
    }
}
