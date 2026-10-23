// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexaticket.aichatbox.application.agent.ActionCardView;
import com.nexaticket.aichatbox.application.booking.ActionCards;
import com.nexaticket.aichatbox.domain.model.ActionCard;
import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.support.FakeCommerce;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ba factory dựng thẻ và hình dạng JSON của chúng (story 1.3). */
class ActionCardsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private final UUID eventSessionId = UUID.randomUUID();
    private final EventDetail event = FakeCommerce.sampleEvent(eventSessionId);

    private BookingProposal proposal(String zone) {
        return BookingProposal.proposed(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dem-nhac-trinh",
                eventSessionId,
                zone,
                2,
                1_800_000L,
                NOW,
                Duration.ofMinutes(10));
    }

    @Test
    void the_de_xuat_tom_tat_bang_ten_hang_ve_va_gio_viet_nam() {
        BookingProposal p = proposal("A");

        ActionCard.BookingProposal card = ActionCards.proposal(p, event);

        assertThat(card.kind()).isEqualTo(ActionCard.Kind.BOOKING_PROPOSAL);
        assertThat(card.id()).isEqualTo(p.id());
        assertThat(card.eventTitle()).isEqualTo("Đêm nhạc Trịnh");
        assertThat(card.summary()).startsWith("2 vé VIP · Đêm nhạc Trịnh · ").contains("ngày");
        assertThat(card.sessionStartsAt()).isEqualTo(event.sessions().getFirst().startsAt());
        assertThat(card.totalVnd()).isEqualTo(3_600_000L);
        assertThat(card.expiresAt()).isEqualTo(p.expiresAt());
    }

    @Test
    void khong_thay_suat_thi_bo_gio_va_dung_ma_khu_lam_ten() {
        BookingProposal p = BookingProposal.proposed(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dem-nhac-trinh",
                UUID.randomUUID(),
                "Z9",
                1,
                100L,
                NOW,
                Duration.ofMinutes(10));

        ActionCard.BookingProposal card = ActionCards.proposal(p, event);

        assertThat(card.summary()).isEqualTo("1 vé Z9 · Đêm nhạc Trịnh");
        assertThat(card.sessionStartsAt()).isNull();
    }

    @Test
    void the_thanh_toan_tu_don_vua_tao_va_tu_de_xuat_da_xac_nhan() {
        PlacedOrder order = new PlacedOrder(
                UUID.randomUUID(),
                "NT-240010",
                3_600_000L,
                "https://pay.payos.vn/web/xyz",
                NOW.plus(Duration.ofMinutes(15)));

        ActionCard.Payment fromOrder = ActionCards.payment(order);
        assertThat(fromOrder.kind()).isEqualTo(ActionCard.Kind.PAYMENT);
        assertThat(fromOrder.orderNumber()).isEqualTo("NT-240010");
        assertThat(fromOrder.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/xyz");
        assertThat(fromOrder.paymentExpiresAt()).isEqualTo(order.paymentExpiresAt());

        BookingProposal p = proposal("A");
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
                new BookingProposal.Order(
                        order.orderId(), order.orderNumber(), order.checkoutUrl(), order.paymentExpiresAt()),
                p.expiresAt(),
                p.createdAt(),
                NOW);
        ActionCard.Payment fromProposal = ActionCards.payment(confirmed);
        assertThat(fromProposal.orderId()).isEqualTo(order.orderId());
        assertThat(fromProposal.totalVnd()).isEqualTo(3_600_000L);
        // Dựng lại từ dữ liệu đã lưu: không có QR/tham chiếu — giao diện lấy qua GET /v1/orders/{id}.
        assertThat(fromProposal.vietQrPayload()).isNull();
        assertThat(fromProposal.paymentReference()).isNull();

        assertThatThrownBy(() -> ActionCards.payment(p)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void json_phan_biet_bang_kind_va_khong_mang_khoa_cua_bien_the_kia() throws Exception {
        ObjectMapper json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        BookingProposal p = proposal("A");

        JsonNode proposalJson =
                json.readTree(json.writeValueAsString(ActionCardView.of(ActionCards.proposal(p, event))));
        assertThat(proposalJson.get("kind").asText()).isEqualTo("BOOKING_PROPOSAL");
        assertThat(proposalJson.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "kind",
                        "id",
                        "summary",
                        "eventTitle",
                        "sessionStartsAt",
                        "zoneCode",
                        "quantity",
                        "totalVnd",
                        "expiresAt");
        assertThat(proposalJson.get("totalVnd").asLong()).isEqualTo(3_600_000L);
        assertThat(proposalJson.get("expiresAt").asText()).endsWith("Z");

        ActionCard.Payment payment =
                new ActionCard.Payment(UUID.randomUUID(), "NT-1", 100L, "000201010212", "NT-1", "https://pay", NOW);
        JsonNode paymentJson = json.readTree(json.writeValueAsString(ActionCardView.of(payment)));
        assertThat(paymentJson.get("kind").asText()).isEqualTo("PAYMENT");
        assertThat(paymentJson.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "kind",
                        "orderId",
                        "orderNumber",
                        "totalVnd",
                        "vietQrPayload",
                        "paymentReference",
                        "checkoutUrl",
                        "paymentExpiresAt");

        assertThat(ActionCardView.of(null)).isNull();
        // Round-trip: bản lưu trong idempotency đọc lại được.
        ActionCardView back = json.readValue(json.writeValueAsString(ActionCardView.of(payment)), ActionCardView.class);
        assertThat(back.vietQrPayload()).isEqualTo("000201010212");
        assertThat(List.of(back.kind())).containsExactly("PAYMENT");
    }
}
