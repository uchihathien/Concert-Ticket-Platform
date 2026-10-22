// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.SupportCaseUseCase;
import com.nexaticket.aichatbox.application.handoff.SupportCaseUseCase.RefundOutcome;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import com.nexaticket.aichatbox.support.FakeCommerce;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Chống trùng phiếu hoàn vé trên Postgres thật — thứ bản in-memory không kiểm được là phép ép
 * {@code details::jsonb ->> 'orderId'} chạy đúng, kể cả trên phiếu cũ không có {@code orderId}.
 */
class RefundDedupIT extends AiChatboxTestBase {

    @Autowired
    HandoffUseCase handoffs;

    @Autowired
    ObjectMapper json;

    private final FakeCommerce.Ordering ordering = new FakeCommerce.Ordering();

    private SupportCaseUseCase cases() {
        return new SupportCaseUseCase(
                ordering,
                new FakeCommerce.Knowledge(),
                new FakeCommerce.Credentials(),
                handoffs,
                Clock.systemUTC(),
                json);
    }

    @Test
    void tim_thay_phieu_hoan_dang_mo_dung_khach_dung_don() {
        UUID userId = UUID.randomUUID();
        OrderSummary order = ordering.put(
                FakeCommerce.paidOrder(UUID.randomUUID(), Instant.now().minus(Duration.ofHours(1))));

        RefundOutcome first = cases().requestRefund(UUID.randomUUID(), userId, order.orderId(), null, "hoàn vé");
        assertThat(first).isInstanceOf(RefundOutcome.Opened.class);
        Handoff opened = ((RefundOutcome.Opened) first).handoff();

        assertThat(handoffs.findOpenRefund(userId, order.orderId()))
                .map(Handoff::id)
                .contains(opened.id());
        assertThat(handoffs.findOpenRefund(UUID.randomUUID(), order.orderId())).isEmpty();
        assertThat(handoffs.findOpenRefund(userId, UUID.randomUUID())).isEmpty();

        // Phiên khác, cùng đơn → từ chối, không có phiếu thứ hai.
        UUID otherSession = UUID.randomUUID();
        assertThat(cases().requestRefund(otherSession, userId, order.orderId(), null, "hoàn lần nữa"))
                .isInstanceOf(RefundOutcome.Denied.class);
        assertThat(handoffs.openFor(otherSession)).isEmpty();
    }

    /** Phiếu REFUND trước story này: {@code details} null hoặc không có {@code orderId} — không lỗi, không khớp. */
    @Test
    void phieu_cu_khong_co_orderId_khong_lam_vo_truy_van() {
        UUID userId = UUID.randomUUID();
        handoffs.escalateWithTurn(
                UUID.randomUUID(),
                userId,
                HandoffTrigger.CUSTOMER_REQUEST,
                SupportIntent.REFUND,
                "phiếu cũ",
                null,
                "hoàn vé",
                "đã chuyển");
        handoffs.escalateWithTurn(
                UUID.randomUUID(),
                userId,
                HandoffTrigger.CUSTOMER_REQUEST,
                SupportIntent.REFUND,
                "phiếu cũ",
                "{\"kind\":\"REFUND\"}",
                "hoàn vé",
                "đã chuyển");

        assertThat(handoffs.findOpenRefund(userId, UUID.randomUUID())).isEmpty();
    }
}
