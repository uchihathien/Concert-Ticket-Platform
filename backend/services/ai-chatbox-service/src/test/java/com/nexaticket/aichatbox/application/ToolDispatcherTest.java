// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import com.nexaticket.aichatbox.application.agent.ToolDispatcher;
import com.nexaticket.aichatbox.application.booking.TicketBookingUseCase;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.support.FakeCommerce;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
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
    private final ToolDispatcher tools = new ToolDispatcher(
            ordering,
            catalog,
            new FakeCommerce.Knowledge(),
            credentials,
            profiles,
            new TicketBookingUseCase(catalog, inventory, ordering, credentials),
            new ObjectMapper(),
            new AgentMetrics(new SimpleMeterRegistry()));

    @Test
    void ho_so_va_lich_su_che_bot_email_va_so_dien_thoai() {
        ordering.mine.add(FakeCommerce.paidOrder(UUID.randomUUID(), Instant.now()));
        ordering.mine.add(FakeCommerce.unpaidOrder(UUID.randomUUID()));

        String payload = tools.dispatch(call(SupportAgentTools.GET_CUSTOMER_PROFILE_AND_HISTORY, Map.of()))
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

        var result = tools.dispatch(call(SupportAgentTools.GET_CUSTOMER_PROFILE_AND_HISTORY, Map.of()));

        assertThat(result.outcome().failed()).isFalse();
        assertThat(result.outcome().payload()).contains("CHƯA LẤY ĐƯỢC HỒ SƠ").contains("NT-240001");
    }

    @Test
    void ordering_hong_thi_la_loi_khong_phai_khach_khong_co_don() {
        ordering.down = true;

        var result = tools.dispatch(call(SupportAgentTools.GET_CUSTOMER_PROFILE_AND_HISTORY, Map.of()));

        assertThat(result.outcome().failed()).isTrue();
        assertThat(result.outcome().payload()).contains("ĐỪNG nói là khách không có đơn");
    }

    @Test
    void chi_tiet_su_kien_mang_sessionId_va_zoneCode_cho_tool_dat_ve() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);

        String payload = tools.dispatch(call(SupportAgentTools.GET_EVENT_DETAILS, Map.of("slug", "dem-nhac-trinh")))
                .outcome()
                .payload();

        assertThat(payload).contains("\"sessionId\":\"" + eventSessionId + "\"");
        assertThat(payload).contains("\"zoneCode\":\"A\"").contains("\"zoneCode\":\"GA\"");
    }

    @Test
    void dat_ve_thanh_cong_tra_ve_so_don_han_thanh_toan_va_duong_dan() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        ordering.nextOrder = new PlacedOrder(
                UUID.randomUUID(),
                "NT-240010",
                3_600_000L,
                "https://pay.payos.vn/web/xyz",
                Instant.parse("2026-10-07T03:15:00Z"));

        var result = tools.dispatch(call(
                SupportAgentTools.INITIATE_TICKET_BOOKING,
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
                .contains("\"booked\":true")
                .contains("\"orderNumber\":\"NT-240010\"")
                // Giờ Việt Nam, đã định dạng — mô hình không phải đổi múi giờ.
                .contains("\"paymentExpiresVietnamTime\":\"10:15 ngày 7/10/2026\"")
                .contains("https://pay.payos.vn/web/xyz");
        // KHÔNG kèm EventRef: khách vừa đặt xong thì câu "xem chỗ và mua vé" gắn thêm là mời họ mua
        // lần nữa — đường đi tiếp duy nhất là checkoutUrl ở trên.
        assertThat(result.events()).isEmpty();
    }

    @Test
    void dat_ve_thieu_so_luong_thi_la_ket_qua_thanh_cong_bao_thieu() {
        var result = tools.dispatch(call(
                SupportAgentTools.INITIATE_TICKET_BOOKING,
                Map.of("slug", "dem-nhac-trinh", "sessionId", eventSessionId.toString(), "zoneCode", "A")));

        assertThat(result.outcome().failed()).isFalse();
        assertThat(result.outcome().payload()).contains("\"booked\":false").contains("THIẾU SỐ LƯỢNG");
        assertThat(inventory.holdKeys).isEmpty();
    }

    @Test
    void dat_ve_bi_tu_choi_thi_booked_false_kem_ly_do_khong_phai_loi() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        inventory.holdFailure =
                new com.nexaticket.aichatbox.domain.port.BookingRejectedException("Khu này không còn đủ chỗ trống.");

        var result = tools.dispatch(call(
                SupportAgentTools.INITIATE_TICKET_BOOKING,
                Map.of(
                        "slug",
                        "dem-nhac-trinh",
                        "sessionId",
                        eventSessionId.toString(),
                        "zoneCode",
                        "A",
                        "quantity",
                        2)));

        assertThat(result.outcome().failed()).isFalse();
        assertThat(result.outcome().payload()).contains("\"booked\":false").contains("không còn đủ chỗ");
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
                new TicketBookingUseCase(catalog, inventory, ordering, credentials),
                new ObjectMapper(),
                new AgentMetrics(new SimpleMeterRegistry()));

        String payload = withRules
                .dispatch(call(SupportAgentTools.GET_EVENT_RULES, Map.of("eventId", eventId.toString())))
                .outcome()
                .payload();

        assertThat(payload).contains("48 giờ kể từ lúc thanh toán");
    }

    private static ToolInvocation call(String tool, Map<String, Object> args) {
        return new ToolInvocation(UUID.randomUUID().toString(), tool, args);
    }
}
