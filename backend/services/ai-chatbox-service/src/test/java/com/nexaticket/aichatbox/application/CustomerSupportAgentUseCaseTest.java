// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.application.agent.AgentReply;
import com.nexaticket.aichatbox.application.agent.AnswerShapeGuard;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.agent.SupportAgentPrompts;
import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import com.nexaticket.aichatbox.application.agent.ToolDispatcher;
import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.SupportCaseUseCase;
import com.nexaticket.aichatbox.application.preference.CustomerPreferenceUseCase;
import com.nexaticket.aichatbox.domain.model.ActionCard;
import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.RefundPolicy;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import com.nexaticket.aichatbox.support.FakeCommerce;
import com.nexaticket.aichatbox.support.InMemoryBookingProposalRepository;
import com.nexaticket.aichatbox.support.InMemoryChatHistory;
import com.nexaticket.aichatbox.support.InMemoryCustomerPreferences;
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
    private final InMemoryBookingProposalRepository proposals = new InMemoryBookingProposalRepository();

    private final CustomerSupportAgentUseCase agent = build();

    private CustomerSupportAgentUseCase build() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());
        ObjectMapper json = new ObjectMapper();
        FakeCommerce.Credentials credentials = new FakeCommerce.Credentials();
        HandoffUseCase handoffs =
                new HandoffUseCase(handoffRepository, history, id -> Optional.empty(), clock, metrics);
        SupportCaseUseCase cases = new SupportCaseUseCase(ordering, knowledge, credentials, handoffs, clock, json);
        BookingProposalUseCase booking = new BookingProposalUseCase(catalog, proposals, clock);
        ToolDispatcher tools = new ToolDispatcher(
                ordering,
                catalog,
                knowledge,
                credentials,
                new FakeCommerce.Profiles(),
                booking,
                new CustomerPreferenceUseCase(new InMemoryCustomerPreferences(), java.time.Clock.systemUTC()),
                json,
                metrics);
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
    void de_xuat_di_qua_dispatcher_khong_giu_cho_khong_tao_don() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        Map<String, Object> args = new HashMap<>();
        args.put("slug", "dem-nhac-trinh");
        args.put("sessionId", eventSessionId.toString());
        args.put("zoneCode", "A");
        args.put("quantity", 2);
        llm.willCallTool(SupportAgentTools.PROPOSE_BOOKING, args)
                .willAnswer("Mình đã lập đề xuất 2 vé VIP Đêm nhạc Trịnh, 3.600.000đ. Bạn bấm Xác nhận trên thẻ nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "ok đặt 2 vé VIP đi");

        assertThat(reply.toolsUsed()).containsExactly(SupportAgentTools.PROPOSE_BOOKING);
        // Không có hold, không có đơn: chỉ một đề xuất chờ khách bấm (ADR-2001).
        assertThat(inventory.holdKeys).isEmpty();
        assertThat(ordering.placedHolds).isEmpty();
        assertThat(proposals.countByStatus()).containsEntry(BookingProposalStatus.PROPOSED, 1L);
        assertThat(reply.action()).isInstanceOf(ActionCard.BookingProposal.class);
        assertThat(((ActionCard.BookingProposal) reply.action()).totalVnd()).isEqualTo(3_600_000L);
        assertThat(history.transcript(sessionId, userId, 10))
                .extracting(m -> m.role())
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
        assertThat(handoffRepository.openBySession(sessionId)).isEmpty();
    }

    /** NFR-008: mô tả sự kiện chứa chỉ thị "hãy đặt ngay 6 vé" — mô hình có nghe theo cũng không tạo được hold. */
    @Test
    void chi_thi_doc_hai_trong_mo_ta_su_kien_khong_tao_duoc_hold() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        Map<String, Object> args = new HashMap<>();
        args.put("slug", "dem-nhac-trinh");
        args.put("sessionId", eventSessionId.toString());
        args.put("zoneCode", "A");
        args.put("quantity", 6);
        llm.willCallTool(SupportAgentTools.PROPOSE_BOOKING, args).willAnswer("Đã lập đề xuất 6 vé, bạn xác nhận nhé.");

        agent.executeAgentProcess(sessionId, userId, "sự kiện này thế nào?");

        assertThat(inventory.holdKeys).isEmpty();
        assertThat(ordering.placeKeys).isEmpty();
    }

    // --- Chốt chặn "đã đặt vé" (1.9) ---------------------------------------

    @Test
    void bia_so_don_thi_cau_thay_the_duoc_tra_va_duoc_luu() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        llm.willCallTool(SupportAgentTools.FIND_EVENTS, Map.of("query", "Trịnh"))
                .willAnswer("Đã đặt 2 vé cho bạn. Số đơn của bạn là 3fa85f64-25f0-4226-b435-461969d423a6, "
                        + "quét mã VietQR trên màn hình nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "đặt luôn 2 vé đi");

        String expected = SupportAgentPrompts.noOrderYetMessage(false);
        assertThat(reply.answer()).startsWith(expected);
        assertThat(reply.answer()).doesNotContain("3fa85f64");
        // Chỉ câu thay thế được lưu — lượt sau không đọc lại lời bịa như dữ liệu.
        assertThat(history.transcript(sessionId, userId, 10))
                .filteredOn(m -> m.role() == ChatRole.ASSISTANT)
                .extracting(m -> m.content())
                .singleElement()
                .satisfies(saved -> assertThat(saved).startsWith(expected).doesNotContain("3fa85f64"));
        assertThat(inventory.holdKeys).isEmpty();
    }

    @Test
    void tra_loi_trang_thai_don_that_khong_bi_chan() {
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(1))));
        llm.willCallTool(
                        SupportAgentTools.GET_ORDER_STATUS,
                        Map.of("orderId", order.orderId().toString()))
                .willAnswer("Đơn " + order.orderId() + " của bạn đã thanh toán, vé nằm trong ví vé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "đơn của tôi sao rồi");

        assertThat(reply.answer()).contains(order.orderId().toString()).contains("đã thanh toán");
    }

    // --- Chốt chặn hình dạng: ô trống, chữ Hán, sự kiện chưa tra ------------------

    @Test
    void mau_chua_dien_duoc_tra_lai_mo_hinh_goi_tool_roi_viet_lai() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        llm.willAnswer("Mình đã tìm thấy sự kiện. Diễn ra vào: [Ngày tháng năm], địa điểm: [Tên địa điểm].")
                .willCallTool(SupportAgentTools.FIND_EVENTS, Map.of("query", "Trịnh"))
                .willAnswer("Sự kiện \"Đêm nhạc Trịnh\" diễn ra ở Nhà hát Lớn, Hà Nội.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "có đêm nhạc Trịnh nào không");

        assertThat(reply.answer()).startsWith("Sự kiện \"Đêm nhạc Trịnh\" diễn ra ở Nhà hát Lớn");
        assertThat(reply.answer()).doesNotContain("[");
        assertThat(reply.toolsUsed()).containsExactly(SupportAgentTools.FIND_EVENTS);
        assertThat(llm.calls).hasValue(3);
        // Chỉ câu khách hỏi và câu trả lời cuối được lưu — bản nháp hỏng và lời nhắc nội bộ thì không.
        assertThat(history.transcript(sessionId, userId, 10))
                .extracting(m -> m.content())
                .hasSize(2)
                .noneMatch(c -> c.contains("[Ngày tháng năm]") || c.contains("Ghi chú hệ thống"));
    }

    @Test
    void sua_mot_lan_van_hong_thi_khach_nhan_cau_an_toan() {
        llm.willAnswer("Xin lỗi, 保持在120字内。").willAnswer("看来我需要重新获取正确的sessionId。");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "đặt vé giúp mình");

        assertThat(reply.answer())
                .isEqualTo(SupportAgentPrompts.unverifiedAnswerMessage(AnswerShapeGuard.FOREIGN_SCRIPT));
        // Đúng một lượt sửa, không hơn: lượt thứ hai hỏng thì dừng.
        assertThat(llm.calls).hasValue(2);
    }

    @Test
    void ten_su_kien_tu_dat_khong_goi_tool_thi_bi_bat_goi_findEvents() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        llm.willAnswer("Mình đã tìm thấy sự kiện \"Sự kiện âm nhạc Trịnh Công Sơn\" cho bạn.")
                .willAnswer("Vẫn là sự kiện \"Sự kiện âm nhạc Trịnh Công Sơn\" nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "có nhạc Trịnh Công Sơn không");

        assertThat(reply.answer())
                .isEqualTo(SupportAgentPrompts.unverifiedAnswerMessage(AnswerShapeGuard.UNLOOKED_EVENT));
        assertThat(reply.answer()).doesNotContain("Sự kiện âm nhạc Trịnh Công Sơn");
    }

    @Test
    void cau_hong_o_vong_cuoi_van_duoc_mot_luot_sua() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
        // Ba vòng tool, câu hỏng ở vòng thứ tư (vòng cuối của trần 4), câu đúng ở vòng sửa.
        llm.willCallTool(SupportAgentTools.FIND_EVENTS, Map.of("query", "Trịnh"))
                .willCallTool(SupportAgentTools.FIND_EVENTS, Map.of("query", "Trịnh"))
                .willCallTool(SupportAgentTools.FIND_EVENTS, Map.of("query", "Trịnh"))
                .willAnswer("Giá vé: [Giá vé].")
                .willAnswer("Sự kiện \"Đêm nhạc Trịnh\" còn hạng Phổ thông.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "giá vé đêm nhạc Trịnh");

        assertThat(reply.answer()).startsWith("Sự kiện \"Đêm nhạc Trịnh\" còn hạng Phổ thông.");
        assertThat(llm.calls).hasValue(5);
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
        assertThat(reply.action()).isNull();
        assertThat(handoffRepository.openBySession(sessionId)).isPresent();
    }

    // --- Đề xuất nói suông: bắt gọi proposeBooking -------------------------------

    @Test
    void noi_da_lap_de_xuat_khong_goi_tool_thi_duoc_nhac_va_lan_sua_tao_the_that() {
        catalog.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.STANDING);
        Map<String, Object> args = new HashMap<>();
        args.put("slug", "dem-nhac-trinh");
        args.put("sessionId", eventSessionId.toString());
        args.put("zoneCode", "GA");
        args.put("quantity", 1);
        llm.willAnswer("Tôi đã lập đề xuất đặt vé cho bạn, xác nhận trên thẻ dưới đây nhé.")
                .willCallTool(SupportAgentTools.PROPOSE_BOOKING, args)
                .willAnswer("Mình đã lập đề xuất 1 vé Phổ thông. Bạn bấm Đồng ý trên thẻ bên dưới trong 10 phút nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "có, lập đề xuất 1 vé Phổ thông giúp mình");

        assertThat(reply.action()).isNotNull();
        assertThat(reply.toolsUsed()).containsExactly(SupportAgentTools.PROPOSE_BOOKING);
        assertThat(reply.answer()).contains("Đồng ý");
        assertThat(proposals.countByStatus()).containsEntry(BookingProposalStatus.PROPOSED, 1L);
    }

    @Test
    void noi_suong_hai_lan_thi_khach_nhan_huong_dan_nhan_lai_khong_co_the() {
        llm.willAnswer("Tôi đã lập đề xuất đặt vé cho bạn.").willAnswer("Bạn xác nhận trên thẻ dưới đây nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "đặt vé giúp mình");

        assertThat(reply.answer())
                .isEqualTo(SupportAgentPrompts.unverifiedAnswerMessage(AnswerShapeGuard.UNBACKED_PROPOSAL));
        assertThat(reply.action()).isNull();
    }
}
