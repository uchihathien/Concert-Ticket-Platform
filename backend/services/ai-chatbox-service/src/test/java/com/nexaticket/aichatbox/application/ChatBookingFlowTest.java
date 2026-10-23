// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase;
import com.nexaticket.aichatbox.application.booking.ChatBookingFlow;
import com.nexaticket.aichatbox.application.booking.TicketBookingUseCase;
import com.nexaticket.aichatbox.domain.model.ActionCard;
import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.model.EventBrief;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.support.FakeCommerce;
import com.nexaticket.aichatbox.support.InMemoryBookingDraftRepository;
import com.nexaticket.aichatbox.support.InMemoryBookingProposalRepository;
import com.nexaticket.aichatbox.support.InMemoryChatHistory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Đặt vé bằng chip: câu đặt vé → chip suất → chip khu → thẻ đề xuất; thẻ chỉ dựng từ dữ liệu thật, và
 * luồng không bao giờ tự giữ chỗ hay tạo đơn.
 */
class ChatBookingFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID chatSessionId = UUID.randomUUID();
    private final UUID evening = UUID.randomUUID();
    private final UUID matinee = UUID.randomUUID();

    private final FakeCommerce.Catalog catalog = new FakeCommerce.Catalog();
    private final FakeCommerce.Inventory inventory = new FakeCommerce.Inventory();
    private final FakeCommerce.Ordering ordering = new FakeCommerce.Ordering();
    private final InMemoryBookingProposalRepository proposals = new InMemoryBookingProposalRepository();
    private final InMemoryBookingDraftRepository drafts = new InMemoryBookingDraftRepository();
    private final Map<UUID, Map<String, Integer>> seatsLeft = new HashMap<>();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final BookingProposalUseCase proposalUseCase = new BookingProposalUseCase(
            catalog,
            proposals,
            clock,
            new TicketBookingUseCase(catalog, inventory, ordering, new FakeCommerce.Credentials(), Duration.ZERO),
            new InMemoryChatHistory(),
            drafts);

    private final ChatBookingFlow flow = new ChatBookingFlow(
            catalog,
            sessionId -> seatsLeft.getOrDefault(sessionId, Map.of()),
            proposalUseCase,
            proposals,
            drafts,
            ordering,
            new FakeCommerce.Credentials(),
            clock);

    @BeforeEach
    void seed() {
        catalog.put(twoSessionEvent(), ZoneAdmission.SEATED);
        seatsLeft.put(evening, Map.of("A", 12, "GA", 0));
        ordering.nextOrder = new PlacedOrder(
                UUID.randomUUID(),
                "NT-240010",
                3_600_000L,
                "https://pay.payos.vn/web/xyz",
                NOW.plus(Duration.ofMinutes(15)),
                "000201010212VIETQR",
                "NT240010");
    }

    private ChatBookingFlow.Turn say(String message) {
        return flow.handle(chatSessionId, userId, message).orElseThrow();
    }

    @Test
    void cau_dat_ve_hoi_suat_roi_khu_roi_lap_de_xuat() {
        seatsLeft.put(evening, Map.of("A", 12, "GA", 30));
        ChatBookingFlow.Turn sessions = say("cho mình 2 vé đêm nhạc Trịnh");
        ActionCard.Choices sessionChips = (ActionCard.Choices) sessions.card();
        assertThat(sessionChips.step()).isEqualTo("SESSION");
        assertThat(sessionChips.options()).hasSize(2);

        ActionCard.Choices zoneChips =
                (ActionCard.Choices) say(eveningChip(sessionChips).value()).card();
        assertThat(zoneChips.step()).isEqualTo("ZONE");
        assertThat(zoneChips.options()).extracting(ActionCard.Option::label).containsExactly("VIP", "Phổ thông");
        assertThat(zoneChips.options().get(0).hint()).contains("1.800.000đ").contains("còn 12 chỗ");

        ChatBookingFlow.Turn proposed = say(zoneChips.options().get(0).value());
        ActionCard.BookingProposal card = (ActionCard.BookingProposal) proposed.card();
        assertThat(card.quantity()).isEqualTo(2);
        assertThat(card.totalVnd()).isEqualTo(3_600_000L);
        assertThat(card.zoneCode()).isEqualTo("A");
        assertThat(proposed.answer()).contains("Đồng ý");

        // Chỉ ĐỀ XUẤT: không giữ chỗ, không tạo đơn, và bản nháp đã nhường chỗ cho đề xuất.
        assertThat(inventory.holdKeys).isEmpty();
        assertThat(ordering.placeKeys).isEmpty();
        assertThat(drafts.find(chatSessionId, userId)).isEmpty();
        assertThat(proposals.findById(card.id()).orElseThrow().status()).isEqualTo(BookingProposalStatus.PROPOSED);
    }

    @Test
    void khu_het_cho_khong_co_chip_va_con_mot_khu_thi_chon_luon() {
        // Suất tối: khu đứng GA đã hết, chỉ còn VIP — không hỏi lại, lập đề xuất VIP ngay.
        ActionCard.Choices sessionChips =
                (ActionCard.Choices) say("cho mình 2 vé đêm nhạc Trịnh").card();

        ActionCard.BookingProposal card = (ActionCard.BookingProposal)
                say(eveningChip(sessionChips).value()).card();

        assertThat(card.zoneCode()).isEqualTo("A");
        assertThat(card.quantity()).isEqualTo(2);
    }

    private static ActionCard.Option eveningChip(ActionCard.Choices sessionChips) {
        return sessionChips.options().stream()
                .filter(o -> o.value().contains("20:00"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void thieu_so_ve_thi_hoi_so_ve_va_nhan_so_go_tay() {
        ChatBookingFlow.Turn first = say("đặt vé đêm nhạc Trịnh");
        ActionCard.Choices sessionChips = (ActionCard.Choices) first.card();
        say(sessionChips.options().get(0).value()); // suất chiều: hai hạng, hỏi hạng
        ActionCard.Choices quantityChips = (ActionCard.Choices) say("Hạng VIP").card();
        assertThat(quantityChips.step()).isEqualTo("QUANTITY");
        assertThat(quantityChips.options()).extracting(ActionCard.Option::value).startsWith("1 vé", "2 vé");

        ActionCard.BookingProposal card = (ActionCard.BookingProposal) say("3").card();
        assertThat(card.quantity()).isEqualTo(3);
    }

    @Test
    void xac_nhan_hai_lan_van_mot_don() {
        ActionCard.Choices sessions =
                (ActionCard.Choices) say("cho mình 1 vé đêm nhạc Trịnh").card();
        say(sessions.options().get(0).value());
        ActionCard.BookingProposal card =
                (ActionCard.BookingProposal) say("Hạng VIP").card();

        BookingProposalUseCase.Confirmed first = proposalUseCase.confirm(userId, card.id());
        // Lần bấm thứ hai: đề xuất đã CONFIRMED — 409 kèm đơn cũ, không giữ thêm chỗ nào.
        try {
            proposalUseCase.confirm(userId, card.id());
        } catch (com.nexaticket.platform.web.error.ApiException e) {
            assertThat(e.errorCode()).isEqualTo(AiChatboxErrorCode.PROPOSAL_ALREADY_CONFIRMED);
        }
        assertThat(inventory.holdKeys).containsExactly("chat-hold-" + card.id());
        assertThat(ordering.placeKeys).containsExactly("chat-order-" + card.id());
        assertThat(first.card().event()).isNotNull();
        assertThat(first.card().event().eventTitle()).isEqualTo("Đêm nhạc Trịnh");
        assertThat(first.card().event().lines()).singleElement().satisfies(line -> {
            assertThat(line.zoneName()).isEqualTo("VIP");
            assertThat(line.quantity()).isEqualTo(1);
        });
    }

    @Test
    void hoi_da_thanh_toan_chua_thi_tra_the_don_voi_trang_thai_moi() {
        ActionCard.Choices sessions =
                (ActionCard.Choices) say("cho mình 2 vé đêm nhạc Trịnh").card();
        say(sessions.options().get(0).value());
        ActionCard.BookingProposal card =
                (ActionCard.BookingProposal) say("Hạng VIP").card();
        BookingProposalUseCase.Confirmed confirmed = proposalUseCase.confirm(userId, card.id());
        ordering.put(new OrderSummary(
                confirmed.order().orderId(),
                "NT-240010",
                UUID.randomUUID(),
                matinee,
                "PAID",
                3_600_000L,
                null,
                NOW,
                List.of()));

        ChatBookingFlow.Turn status = say("mình thanh toán chưa vậy");

        ActionCard.Payment payment = (ActionCard.Payment) status.card();
        assertThat(payment.status()).isEqualTo("PAID");
        assertThat(payment.orderNumber()).isEqualTo("NT-240010");
        assertThat(status.answer()).contains("đã thanh toán");
    }

    @Test
    void doi_lua_chon_huy_de_xuat_va_hoi_lai_khu() {
        ActionCard.Choices sessions =
                (ActionCard.Choices) say("cho mình 2 vé đêm nhạc Trịnh").card();
        say(sessions.options().get(0).value());
        ActionCard.BookingProposal card =
                (ActionCard.BookingProposal) say("Hạng VIP").card();

        ActionCard.Choices zones = (ActionCard.Choices) say("Đổi lựa chọn").card();

        assertThat(zones.step()).isEqualTo("ZONE");
        assertThat(proposals.findById(card.id()).map(BookingProposal::status))
                .contains(BookingProposalStatus.CANCELLED);
    }

    @Test
    void catalog_hong_thi_khong_co_the_de_xuat_hay_don() {
        catalog.events.clear();
        FakeCommerce.Catalog broken = new FakeCommerce.Catalog() {
            @Override
            public List<com.nexaticket.aichatbox.domain.model.EventBrief> searchEvents(
                    String query, String city, String category, int limit) {
                throw new com.nexaticket.aichatbox.domain.port.RemoteCallException("catalog-service", "giả", null);
            }
        };
        ChatBookingFlow brokenFlow = new ChatBookingFlow(
                broken,
                sessionId -> Map.of(),
                proposalUseCase,
                proposals,
                drafts,
                ordering,
                new FakeCommerce.Credentials(),
                clock);

        ChatBookingFlow.Turn turn = brokenFlow
                .handle(chatSessionId, userId, "cho mình 2 vé đêm nhạc Trịnh")
                .orElseThrow();

        assertThat(turn.card()).isNull();
        assertThat(turn.answer()).contains("thử lại");
        assertThat(proposals.recentForSession(chatSessionId, userId, 5)).isEmpty();
    }

    @Test
    void su_kien_het_suat_khong_thanh_chip_con_mot_su_kien_thi_chon_luon() {
        FakeCommerce.Catalog twoResults = new FakeCommerce.Catalog() {
            @Override
            public List<EventBrief> searchEvents(String query, String city, String category, int limit) {
                return List.of(
                        new EventBrief(
                                "dem-nhac-trinh-mua-dong",
                                "Đêm nhạc Trịnh — Mùa Đông",
                                null,
                                "Huế",
                                null,
                                null,
                                null,
                                1),
                        new EventBrief(
                                "dem-nhac-trinh",
                                "Đêm nhạc Trịnh",
                                null,
                                "Hà Nội",
                                "Nhà hát Lớn",
                                Instant.parse("2026-11-20T08:00:00Z"),
                                450_000L,
                                2));
            }
        };
        twoResults.put(twoSessionEvent(), ZoneAdmission.SEATED);
        ChatBookingFlow withPastEvent = new ChatBookingFlow(
                twoResults,
                sessionId -> Map.of(),
                proposalUseCase,
                proposals,
                drafts,
                ordering,
                new FakeCommerce.Credentials(),
                clock);

        ChatBookingFlow.Turn turn = withPastEvent
                .handle(chatSessionId, userId, "cho mình 2 vé đêm nhạc Trịnh")
                .orElseThrow();

        // Không hỏi "sự kiện nào": sự kiện Mùa Đông đã hết suất, nên đi thẳng tới chọn suất của sự kiện còn lại.
        assertThat(((ActionCard.Choices) turn.card()).step()).isEqualTo("SESSION");
    }

    @Test
    void cau_khong_phai_dat_ve_thi_de_tro_ly_tra_loi() {
        assertThat(flow.handle(chatSessionId, userId, "vé đứng là gì vậy?")).isEmpty();
        assertThat(flow.handle(chatSessionId, userId, "tôi muốn hoàn vé đơn hôm qua"))
                .isEmpty();
        assertThat(flow.handle(chatSessionId, userId, "giữ chỗ được bao lâu?")).isEmpty();
    }

    @Test
    void qua_sau_ve_thi_bao_tran_va_hoi_lai_so_ve() {
        ChatBookingFlow.Turn turn = say("cho mình 10 vé đêm nhạc Trịnh");
        assertThat(turn.answer()).contains("tối đa 6 vé");
        assertThat(((ActionCard.Choices) turn.card()).step()).isEqualTo("SESSION");
    }

    /** Hai suất: chiều 15:00 (hai hạng, không biết số chỗ) và tối 20:00 (khu đứng đã hết). */
    private EventDetail twoSessionEvent() {
        List<EventDetail.Tier> tiers = List.of(
                new EventDetail.Tier("VIP", 1_800_000L, "A", "Khán đài A"),
                new EventDetail.Tier("Phổ thông", 450_000L, "GA", "Khu đứng"));
        return new EventDetail(
                "dem-nhac-trinh",
                "Đêm nhạc Trịnh",
                null,
                null,
                "nhac-song",
                "Hà Nội",
                "Nhà hát Lớn",
                "1 Tràng Tiền",
                List.of(
                        new EventDetail.Session(
                                matinee,
                                Instant.parse("2026-11-20T08:00:00Z"),
                                Instant.parse("2026-11-20T10:00:00Z"),
                                Instant.parse("2026-11-20T07:00:00Z"),
                                tiers),
                        new EventDetail.Session(
                                evening,
                                Instant.parse("2026-11-20T13:00:00Z"),
                                Instant.parse("2026-11-20T15:00:00Z"),
                                Instant.parse("2026-11-20T12:00:00Z"),
                                tiers)));
    }
}
