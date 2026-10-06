// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.application.agent.AgentReply;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import com.nexaticket.aichatbox.application.agent.ToolDispatcher;
import com.nexaticket.aichatbox.application.booking.TicketBookingUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.SupportCaseUseCase;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.RefundPolicy;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import com.nexaticket.aichatbox.support.FakeCommerce;
import com.nexaticket.aichatbox.support.InMemoryChatHistory;
import com.nexaticket.aichatbox.support.InMemoryHandoffRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Vòng ReAct với các tool mới — <b>không</b> có Spring, không có database, không có mô hình thật.
 *
 * <p>Thứ cần kiểm là <i>chính sách</i> của vòng lặp: tool nào kết thúc lượt, tool nào trả dữ liệu
 * cho mô hình đọc tiếp, trần vòng còn được giữ không. Những thứ cần database (khoá ngoại, unique
 * index) nằm ở {@code HandoffFlowIT}.
 */
class CustomerSupportAgentUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID eventSessionId = UUID.randomUUID();

    private final FakeAiProviders.FakeLlm llm = new FakeAiProviders.FakeLlm();
    private final FakeCommerce.Ordering ordering = new FakeCommerce.Ordering();
    private final FakeCommerce.Catalog catalog = new FakeCommerce.Catalog();
    private final FakeCommerce.Inventory inventory = new FakeCommerce.Inventory();
    private final FakeCommerce.Knowledge knowledge = new FakeCommerce.Knowledge();
    private final InMemoryHandoffRepository handoffRepository = new InMemoryHandoffRepository();
    private final InMemoryChatHistory history = new InMemoryChatHistory();

    private final CustomerSupportAgentUseCase agent = build();

    private CustomerSupportAgentUseCase build() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());
        ObjectMapper json = new ObjectMapper();
        FakeCommerce.Credentials credentials = new FakeCommerce.Credentials();
        HandoffUseCase handoffs =
                new HandoffUseCase(handoffRepository, history, id -> Optional.empty(), clock, metrics);
        SupportCaseUseCase cases = new SupportCaseUseCase(ordering, knowledge, credentials, handoffs, clock, json);
        TicketBookingUseCase booking = new TicketBookingUseCase(catalog, inventory, ordering, credentials);
        ToolDispatcher tools = new ToolDispatcher(
                ordering, catalog, knowledge, credentials, new FakeCommerce.Profiles(), booking, json, metrics);
        return new CustomerSupportAgentUseCase(
                history,
                new FakeAiProviders.FakeEmbeddings(),
                knowledge,
                llm,
                tools,
                handoffs,
                cases,
                new AgentProperties(null, 4, 10, 5, 0.55, 16, 32, 2),
                metrics,
                json);
    }

    // --- Hoàn vé -------------------------------------------------------------

    @Test
    void hoan_ve_du_dieu_kien_thi_ket_thuc_luot_bang_phieu_REFUND() {
        knowledge.publishRules(eventId, "Đêm nhạc Trịnh", new RefundPolicy(true, 48));
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(1))));
        llm.willCallTool(
                SupportAgentTools.REQUEST_TICKET_REFUND,
                Map.of("orderId", order.orderId().toString(), "reason", "bận việc"));

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "tôi muốn hoàn vé đơn này");

        assertThat(reply.toolsUsed()).containsExactly(SupportAgentTools.REQUEST_TICKET_REFUND);
        assertThat(reply.answer()).contains("NT-240001").contains("chưa phải xác nhận hoàn tiền");
        Handoff opened = handoffRepository.openBySession(sessionId).orElseThrow();
        assertThat(opened.intent()).isEqualTo(SupportIntent.REFUND);
        // Một lượt gọi mô hình, không hơn: mở phiếu là điểm dừng.
        assertThat(llm.calls).hasValue(1);
        assertThat(history.transcript(sessionId, userId, 10))
                .extracting(m -> m.role())
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
    }

    @Test
    void hoan_ve_bi_tu_choi_theo_chinh_sach_thi_mo_hinh_doc_ly_do_va_tra_loi_tiep() {
        knowledge.publishRules(eventId, "Đêm nhạc Trịnh", RefundPolicy.NONE);
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(1))));
        llm.willCallTool(
                        SupportAgentTools.REQUEST_TICKET_REFUND,
                        Map.of("orderId", order.orderId().toString()))
                .willAnswer("Rất tiếc, sự kiện này không nhận hoàn vé theo quy định của ban tổ chức.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "hoàn vé giúp mình");

        // Từ chối là DỮ LIỆU, không phải điểm dừng: vòng lặp đi tiếp và mô hình trả lời.
        assertThat(reply.answer()).contains("không nhận hoàn vé");
        assertThat(reply.toolsUsed()).containsExactly(SupportAgentTools.REQUEST_TICKET_REFUND);
        assertThat(llm.calls).hasValue(2);
        assertThat(handoffRepository.openBySession(sessionId)).isEmpty();
    }

    @Test
    void hoan_ve_thieu_ma_don_thi_khong_mo_phieu_ma_chi_cach_lay_ma() {
        llm.willCallTool(SupportAgentTools.REQUEST_TICKET_REFUND, Map.of("reason", "bận"))
                .willAnswer("Bạn cho mình biết đơn nào nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "hoàn vé");

        assertThat(reply.answer()).isEqualTo("Bạn cho mình biết đơn nào nhé.");
        assertThat(handoffRepository.openBySession(sessionId)).isEmpty();
    }

    @Test
    void ordering_hong_luc_xet_hoan_ve_thi_la_loi_tool_khong_phai_tu_choi() {
        ordering.down = true;
        llm.willCallTool(
                        SupportAgentTools.REQUEST_TICKET_REFUND,
                        Map.of("orderId", UUID.randomUUID().toString()))
                .willAnswer("Hệ thống đang bận, bạn thử lại sau nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "hoàn vé");

        assertThat(reply.answer()).contains("đang bận");
        assertThat(handoffRepository.openBySession(sessionId)).isEmpty();
    }

    // --- Sự cố ---------------------------------------------------------------

    @Test
    void bao_su_co_thi_ket_thuc_luot_bang_phieu_INCIDENT_theo_khung() {
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(1))));
        llm.willCallTool(
                SupportAgentTools.REPORT_INCIDENT,
                Map.of(
                        "kind", "TICKET_NOT_RECEIVED",
                        "description", "Trả tiền lúc 9h mà ví vé trống",
                        "orderId", order.orderId().toString()));

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "chưa thấy vé đâu");

        assertThat(reply.answer()).contains("Chưa nhận được vé").contains("NT-240001");
        Handoff opened = handoffRepository.openBySession(sessionId).orElseThrow();
        assertThat(opened.intent()).isEqualTo(SupportIntent.INCIDENT);
        assertThat(opened.reason()).startsWith("[Sự cố] Chưa nhận được vé · đơn NT-240001");
        assertThat(llm.calls).hasValue(1);
    }

    @Test
    void su_co_can_don_ma_thieu_ma_thi_khong_mo_phieu() {
        llm.willCallTool(
                        SupportAgentTools.REPORT_INCIDENT,
                        Map.of("kind", "QR_NOT_SCANNABLE", "description", "Quét không được"))
                .willAnswer("Bạn cho mình mã đơn để mình mở phiếu nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "QR không quét được");

        assertThat(reply.answer()).contains("mã đơn");
        assertThat(handoffRepository.openBySession(sessionId)).isEmpty();
        assertThat(llm.calls).hasValue(2);
    }

    @Test
    void loai_su_co_la_thi_khong_mo_phieu_bua() {
        llm.willCallTool(SupportAgentTools.REPORT_INCIDENT, Map.of("kind", "CHAY_NHA", "description", "x"))
                .willAnswer("Mình chưa rõ sự cố, bạn mô tả thêm nhé.");

        agent.executeAgentProcess(sessionId, userId, "có sự cố");

        assertThat(handoffRepository.openBySession(sessionId)).isEmpty();
    }

    // --- Đặt vé qua dispatcher ----------------------------------------------

    @Test
    void dat_ve_di_qua_dispatcher_va_mo_hinh_doc_duoc_duong_dan_thanh_toan() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        ordering.nextOrder = new PlacedOrder(
                UUID.randomUUID(),
                "NT-240010",
                3_600_000L,
                "https://pay.payos.vn/web/xyz",
                NOW.plus(Duration.ofMinutes(15)));
        Map<String, Object> args = new HashMap<>();
        args.put("slug", "dem-nhac-trinh");
        args.put("sessionId", eventSessionId.toString());
        args.put("zoneCode", "A");
        args.put("quantity", 2);
        llm.willCallTool(SupportAgentTools.INITIATE_TICKET_BOOKING, args)
                .willAnswer("Đã đặt đơn NT-240010, bạn thanh toán tại https://pay.payos.vn/web/xyz nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "ok đặt 2 vé VIP đi");

        assertThat(reply.toolsUsed()).containsExactly(SupportAgentTools.INITIATE_TICKET_BOOKING);
        assertThat(inventory.holdKeys).hasSize(1);
        assertThat(ordering.placedHolds).hasSize(1);
        // Không gắn thêm "xem chỗ và mua vé": khách vừa đặt xong, đường đi tiếp duy nhất là link
        // thanh toán mà mô hình đã chép lại từ kết quả tool.
        assertThat(reply.answer()).contains("NT-240010").doesNotContain("/events/");
        assertThat(handoffRepository.openBySession(sessionId)).isEmpty();
    }

    // --- Trần vòng vẫn giữ --------------------------------------------------

    @Test
    void tool_dieu_khien_khong_pha_tran_bon_vong() {
        // Mô hình cứ thiếu mã đơn mãi: mỗi vòng là một kết quả "thiếu mã", không bao giờ trả lời.
        llm.alwaysCallsTool(SupportAgentTools.REQUEST_TICKET_REFUND, Map.of("reason", "x"));

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "hoàn vé");

        assertThat(llm.calls).hasValue(4);
        // Hết vòng thì chuyển người thật, như mọi lần hết vòng khác.
        assertThat(reply.toolsUsed()).containsExactly(SupportAgentTools.ESCALATE_TO_HUMAN);
        assertThat(handoffRepository.openBySession(sessionId)).isPresent();
    }
}
