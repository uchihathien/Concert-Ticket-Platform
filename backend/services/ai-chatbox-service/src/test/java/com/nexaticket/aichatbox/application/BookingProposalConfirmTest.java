// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase;
import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase.Confirmed;
import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase.ProposalOutcome;
import com.nexaticket.aichatbox.application.booking.TicketBookingUseCase;
import com.nexaticket.aichatbox.domain.model.ActionCard;
import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.BookingRejectedException;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.support.FakeCommerce;
import com.nexaticket.aichatbox.support.InMemoryBookingProposalRepository;
import com.nexaticket.aichatbox.support.InMemoryChatHistory;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Nút Xác nhận/Huỷ: đơn chỉ ra đời từ đây, khoá chống trùng suy từ proposalId, lỗi nghiệp vụ huỷ đề
 * xuất còn lỗi hệ thống giữ nguyên, và thẻ sau khi tải lại trang (story 1.4).
 */
class BookingProposalConfirmTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID chatSessionId = UUID.randomUUID();
    private final UUID eventSessionId = UUID.randomUUID();
    private final FakeCommerce.Catalog catalog = new FakeCommerce.Catalog();
    private final FakeCommerce.Inventory inventory = new FakeCommerce.Inventory();
    private final FakeCommerce.Ordering ordering = new FakeCommerce.Ordering();
    private final InMemoryBookingProposalRepository proposals = new InMemoryBookingProposalRepository();
    private final InMemoryChatHistory history = new InMemoryChatHistory();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final BookingProposalUseCase useCase = new BookingProposalUseCase(
            catalog,
            proposals,
            clock,
            new TicketBookingUseCase(catalog, inventory, ordering, new FakeCommerce.Credentials(), Duration.ZERO),
            history);

    @BeforeEach
    void seed() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        ordering.nextOrder = new PlacedOrder(
                UUID.randomUUID(),
                "NT-240010",
                3_600_000L,
                "https://pay.payos.vn/web/xyz",
                NOW.plus(Duration.ofMinutes(15)),
                "000201010212VIETQR",
                "NT240010");
    }

    private BookingProposal proposeA(int qty) {
        return ((ProposalOutcome.Proposed)
                        useCase.propose(userId, chatSessionId, "dem-nhac-trinh", eventSessionId, "A", qty))
                .proposal();
    }

    private static ApiException api(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("mong đợi ApiException");
    }

    @Test
    void xac_nhan_giu_cho_tao_don_voi_khoa_theo_proposalId_va_ghi_mot_luot_ASSISTANT() {
        BookingProposal p = proposeA(2);

        Confirmed c = useCase.confirm(userId, p.id());

        assertThat(inventory.holdKeys).containsExactly("chat-hold-" + p.id());
        assertThat(ordering.placeKeys).containsExactly("chat-order-" + p.id());
        assertThat(c.order().orderNumber()).isEqualTo("NT-240010");
        assertThat(c.card().kind()).isEqualTo(ActionCard.Kind.PAYMENT);
        assertThat(c.card().vietQrPayload()).isEqualTo("000201010212VIETQR");
        assertThat(c.card().paymentReference()).isEqualTo("NT240010");
        assertThat(c.announcement())
                .startsWith("Đã tạo đơn NT-240010 (2 vé VIP, Đêm nhạc Trịnh) — tổng 3.600.000đ, thanh toán trước ")
                .contains("ngày")
                .endsWith("Thanh toán tại: https://pay.payos.vn/web/xyz");

        BookingProposal saved = proposals.findById(p.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(BookingProposalStatus.CONFIRMED);
        assertThat(saved.order().orderId()).isEqualTo(c.order().orderId());
        assertThat(saved.order().checkoutUrl()).isEqualTo("https://pay.payos.vn/web/xyz");
        assertThat(saved.confirmedAt()).isEqualTo(NOW);
        // Lượt sau trợ lý đọc được "đơn X đã được tạo" — ghi đúng một lượt ASSISTANT, không có USER.
        assertThat(history.transcript(chatSessionId, userId, 10))
                .extracting(m -> m.role())
                .containsExactly(ChatRole.ASSISTANT);
        assertThat(history.transcript(chatSessionId, userId, 10).getFirst().content())
                .isEqualTo(c.announcement());
    }

    @Test
    void xac_nhan_lan_hai_la_409_kem_don_cu_khong_goi_service() {
        BookingProposal p = proposeA(1);
        useCase.confirm(userId, p.id());

        ApiException e = api(() -> useCase.confirm(userId, p.id()));

        assertThat(e.errorCode()).isEqualTo(AiChatboxErrorCode.PROPOSAL_ALREADY_CONFIRMED);
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) e.meta().get("order");
        assertThat(order).containsEntry("orderNumber", "NT-240010").containsEntry("totalVnd", 1_800_000L);
        assertThat(e.meta().get("action")).isNotNull();
        assertThat(inventory.holdKeys).hasSize(1);
        assertThat(ordering.placeKeys).hasSize(1);
    }

    @Test
    void khong_co_hoac_cua_nguoi_khac_la_404() {
        BookingProposal p = proposeA(1);

        assertThat(api(() -> useCase.confirm(UUID.randomUUID(), p.id())).errorCode())
                .isEqualTo(AiChatboxErrorCode.PROPOSAL_NOT_FOUND);
        assertThat(api(() -> useCase.confirm(userId, UUID.randomUUID())).errorCode())
                .isEqualTo(AiChatboxErrorCode.PROPOSAL_NOT_FOUND);
        assertThat(api(() -> useCase.cancel(UUID.randomUUID(), p.id())).errorCode())
                .isEqualTo(AiChatboxErrorCode.PROPOSAL_NOT_FOUND);
        assertThat(inventory.holdKeys).isEmpty();
    }

    @Test
    void het_han_hoac_da_huy_la_410_va_khong_cham_kho() {
        BookingProposal p = proposeA(1);
        BookingProposalUseCase later = new BookingProposalUseCase(
                catalog,
                proposals,
                Clock.fixed(NOW.plus(Duration.ofMinutes(10)), ZoneOffset.UTC),
                new TicketBookingUseCase(catalog, inventory, ordering, new FakeCommerce.Credentials(), Duration.ZERO),
                history);
        assertThat(api(() -> later.confirm(userId, p.id())).errorCode()).isEqualTo(AiChatboxErrorCode.PROPOSAL_EXPIRED);

        BookingProposal q = ((ProposalOutcome.Proposed)
                        useCase.propose(userId, chatSessionId, "dem-nhac-trinh", eventSessionId, "GA", 1))
                .proposal();
        useCase.cancel(userId, q.id());
        assertThat(api(() -> useCase.confirm(userId, q.id())).errorCode())
                .isEqualTo(AiChatboxErrorCode.PROPOSAL_EXPIRED);
        assertThat(inventory.holdKeys).isEmpty();
    }

    @Test
    void inventory_tu_choi_la_422_va_de_xuat_bi_huy() {
        BookingProposal p = proposeA(1);
        inventory.holdFailure = new BookingRejectedException("Khu này không còn đủ chỗ trống.");

        ApiException e = api(() -> useCase.confirm(userId, p.id()));

        assertThat(e.errorCode()).isEqualTo(AiChatboxErrorCode.BOOKING_REJECTED);
        assertThat(e.meta()).containsEntry("reason", "Khu này không còn đủ chỗ trống.");
        assertThat(proposals.findById(p.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.CANCELLED);
    }

    @Test
    void service_khong_phan_hoi_la_503_va_de_xuat_giu_nguyen() {
        BookingProposal p = proposeA(1);
        inventory.holdFailure = new RemoteCallException("inventory-service", "giả: hỏng", null);

        assertThat(api(() -> useCase.confirm(userId, p.id())).errorCode())
                .isEqualTo(AiChatboxErrorCode.REMOTE_UNAVAILABLE);
        assertThat(proposals.findById(p.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.PROPOSED);
    }

    /** 6.12: ordering quá hạn hai lần là "chưa biết" — không huỷ đề xuất, không bảo khách đặt lại. */
    @Test
    void ordering_qua_han_hai_lan_la_503_va_de_xuat_giu_nguyen() {
        BookingProposal p = proposeA(1);
        ordering.placeFailure = new RemoteCallException("ordering-service", "giả: quá hạn", null);

        ApiException e = api(() -> useCase.confirm(userId, p.id()));

        assertThat(e.errorCode()).isEqualTo(AiChatboxErrorCode.REMOTE_UNAVAILABLE);
        assertThat(e.getMessage()).isEqualTo(TicketBookingUseCase.UNCONFIRMED_REASON);
        assertThat(proposals.findById(p.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.PROPOSED);
        assertThat(inventory.released).isEmpty();
    }

    @Test
    void huy_la_idempotent_va_da_xac_nhan_thi_409() {
        BookingProposal p = proposeA(1);

        useCase.cancel(userId, p.id());
        useCase.cancel(userId, p.id());
        assertThat(proposals.findById(p.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.CANCELLED);

        BookingProposal q = ((ProposalOutcome.Proposed)
                        useCase.propose(userId, chatSessionId, "dem-nhac-trinh", eventSessionId, "GA", 1))
                .proposal();
        useCase.confirm(userId, q.id());
        assertThat(api(() -> useCase.cancel(userId, q.id())).errorCode())
                .isEqualTo(AiChatboxErrorCode.PROPOSAL_ALREADY_CONFIRMED);
    }

    @Test
    void the_dang_mo_sau_khi_tai_lai_trang() {
        assertThat(useCase.openCardFor(chatSessionId, userId)).isEmpty();

        BookingProposal p = proposeA(2);
        ActionCard open = useCase.openCardFor(chatSessionId, userId).orElseThrow();
        assertThat(open).isInstanceOf(ActionCard.BookingProposal.class);
        assertThat(((ActionCard.BookingProposal) open).id()).isEqualTo(p.id());

        useCase.confirm(userId, p.id());
        ActionCard paying = useCase.openCardFor(chatSessionId, userId).orElseThrow();
        assertThat(paying).isInstanceOf(ActionCard.Payment.class);
        // Dựng lại từ dữ liệu đã lưu: không có QR — giao diện lấy qua GET /v1/orders/{id}.
        assertThat(((ActionCard.Payment) paying).vietQrPayload()).isNull();
        assertThat(((ActionCard.Payment) paying).orderNumber()).isEqualTo("NT-240010");

        // Phiên khác, khách khác: không thấy gì.
        assertThat(useCase.openCardFor(UUID.randomUUID(), userId)).isEmpty();
        assertThat(useCase.openCardFor(chatSessionId, UUID.randomUUID())).isEmpty();
    }

    @Test
    void catalog_hong_thi_khong_co_the_de_xuat_va_khong_500() {
        proposeA(1);
        FakeCommerce.Catalog broken = new FakeCommerce.Catalog() {
            @Override
            public java.util.Optional<com.nexaticket.aichatbox.domain.model.EventDetail> findEventBySlug(String slug) {
                throw new RemoteCallException("catalog-service", "giả: hỏng", null);
            }
        };
        BookingProposalUseCase withBroken = new BookingProposalUseCase(broken, proposals, clock);

        assertThat(withBroken.openCardFor(chatSessionId, userId)).isEmpty();
    }
}
