// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import com.nexaticket.aichatbox.application.agent.ToolDispatcher;
import com.nexaticket.aichatbox.application.agent.TurnContext;
import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.support.FakeCommerce;
import com.nexaticket.aichatbox.support.InMemoryBookingProposalRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Hình dạng kết quả của các tool mới — thứ mô hình đọc. */
class ToolDispatcherTest {

    private final UUID eventSessionId = UUID.randomUUID();
    private final FakeCommerce.Ordering ordering = new FakeCommerce.Ordering();
    private final FakeCommerce.Catalog catalog = new FakeCommerce.Catalog();
    private final FakeCommerce.Inventory inventory = new FakeCommerce.Inventory();
    private final FakeCommerce.Profiles profiles = new FakeCommerce.Profiles();
    private final FakeCommerce.Credentials credentials = new FakeCommerce.Credentials();
    private static final TurnContext TURN = new TurnContext(UUID.randomUUID(), UUID.randomUUID());
    private final InMemoryBookingProposalRepository proposals = new InMemoryBookingProposalRepository();
    private final ToolDispatcher tools = new ToolDispatcher(
            ordering,
            catalog,
            new FakeCommerce.Knowledge(),
            credentials,
            profiles,
            new BookingProposalUseCase(
                    catalog, proposals, Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC)),
            new ObjectMapper(),
            new AgentMetrics(new SimpleMeterRegistry()));

    @Test
    void ho_so_va_lich_su_che_bot_email_va_so_dien_thoai() {
        ordering.mine.add(FakeCommerce.paidOrder(UUID.randomUUID(), Instant.now()));
        ordering.mine.add(FakeCommerce.unpaidOrder(UUID.randomUUID()));

        String payload = tools.dispatch(TURN, call(SupportAgentTools.GET_CUSTOMER_PROFILE_AND_HISTORY, Map.of()))
                .outcome()
                .payload();

        assertThat(payload).contains("\"fullName\":\"Lữ Đình Thiện\"");
        assertThat(payload).contains("\"emailMasked\":\"th***@gmail.com\"").doesNotContain("thien.lu@");
        assertThat(payload).contains("\"phoneMasked\":\"09*****678\"").doesNotContain("0912345678");
        assertThat(payload).contains("\"orderCount\":2").contains("NT-240001").contains("NT-240002");
    }

    @Test
    void identity_hong_thi_van_tra_don_kem_co_bao_thieu_ho_so() {
        profiles.down = true;
        ordering.mine.add(FakeCommerce.paidOrder(UUID.randomUUID(), Instant.now()));

        var result = tools.dispatch(TURN, call(SupportAgentTools.GET_CUSTOMER_PROFILE_AND_HISTORY, Map.of()));

        assertThat(result.outcome().failed()).isFalse();
        assertThat(result.outcome().payload()).contains("CHƯA LẤY ĐƯỢC HỒ SƠ").contains("NT-240001");
    }

    @Test
    void ordering_hong_thi_la_loi_khong_phai_khach_khong_co_don() {
        ordering.down = true;

        var result = tools.dispatch(TURN, call(SupportAgentTools.GET_CUSTOMER_PROFILE_AND_HISTORY, Map.of()));

        assertThat(result.outcome().failed()).isTrue();
        assertThat(result.outcome().payload()).contains("ĐỪNG nói là khách không có đơn");
    }

    @Test
    void chi_tiet_su_kien_mang_sessionId_va_zoneCode_cho_tool_dat_ve() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);

        String payload = tools.dispatch(
                        TURN, call(SupportAgentTools.GET_EVENT_DETAILS, Map.of("slug", "dem-nhac-trinh")))
                .outcome()
                .payload();

        assertThat(payload).contains("\"sessionId\":\"" + eventSessionId + "\"");
        assertThat(payload).contains("\"zoneCode\":\"A\"").contains("\"zoneCode\":\"GA\"");
    }

    @Test
    void de_xuat_thanh_cong_tra_tom_tat_va_dan_dung_noi_da_dat() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);

        var result = tools.dispatch(
                TURN,
                call(
                        SupportAgentTools.PROPOSE_BOOKING,
                        Map.of(
                                "slug",
                                "dem-nhac-trinh",
                                "sessionId",
                                eventSessionId.toString(),
                                "zoneCode",
                                "A",
                                "quantity",
                                "2")));

        assertThat(result.outcome().failed()).isFalse();
        assertThat(result.outcome().payload())
                .contains("\"proposed\":true")
                .contains("\"proposalId\"")
                .contains("\"tierName\":\"VIP\"")
                .contains("\"quantity\":2")
                .contains("\"totalVnd\":3600000")
                .contains("ĐỪNG nói là đã đặt");
        // Chỉ là đề xuất: không giữ chỗ, không tạo đơn, không gắn thêm link mua vé.
        assertThat(inventory.holdKeys).isEmpty();
        assertThat(ordering.placedHolds).isEmpty();
        assertThat(result.events()).isEmpty();
        assertThat(proposals.countByStatus()).containsEntry(BookingProposalStatus.PROPOSED, 1L);
    }

    @Test
    void de_xuat_thieu_so_luong_thi_la_ket_qua_thanh_cong_bao_thieu() {
        var result = tools.dispatch(
                TURN,
                call(
                        SupportAgentTools.PROPOSE_BOOKING,
                        Map.of("slug", "dem-nhac-trinh", "sessionId", eventSessionId.toString(), "zoneCode", "A")));

        assertThat(result.outcome().failed()).isFalse();
        assertThat(result.outcome().payload()).contains("\"proposed\":false").contains("THIẾU SỐ LƯỢNG");
        assertThat(proposals.countByStatus()).containsEntry(BookingProposalStatus.PROPOSED, 0L);
    }

    @Test
    void de_xuat_bi_tu_choi_thi_proposed_false_kem_ly_do_khong_phai_loi() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);

        var result = tools.dispatch(
                TURN,
                call(
                        SupportAgentTools.PROPOSE_BOOKING,
                        Map.of(
                                "slug",
                                "dem-nhac-trinh",
                                "sessionId",
                                eventSessionId.toString(),
                                "zoneCode",
                                "A",
                                "quantity",
                                7)));

        assertThat(result.outcome().failed()).isFalse();
        assertThat(result.outcome().payload()).contains("\"proposed\":false").contains("tối đa 6 vé");
        assertThat(inventory.holdKeys).isEmpty();
    }

    @Test
    void quy_dinh_su_kien_noi_chinh_sach_hoan_ve_bang_cau() {
        FakeCommerce.Knowledge knowledge = new FakeCommerce.Knowledge();
        UUID eventId = UUID.randomUUID();
        knowledge.publishRules(
                eventId, "Đêm nhạc Trịnh", new com.nexaticket.aichatbox.domain.model.RefundPolicy(true, 48));
        ToolDispatcher withRules = new ToolDispatcher(
                ordering,
                catalog,
                knowledge,
                credentials,
                profiles,
                new BookingProposalUseCase(
                        catalog, proposals, Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC)),
                new ObjectMapper(),
                new AgentMetrics(new SimpleMeterRegistry()));

        String payload = withRules
                .dispatch(TURN, call(SupportAgentTools.GET_EVENT_RULES, Map.of("eventId", eventId.toString())))
                .outcome()
                .payload();

        assertThat(payload).contains("48 giờ kể từ lúc thanh toán");
    }

    private static ToolInvocation call(String tool, Map<String, Object> args) {
        return new ToolInvocation(UUID.randomUUID().toString(), tool, args);
    }
}
