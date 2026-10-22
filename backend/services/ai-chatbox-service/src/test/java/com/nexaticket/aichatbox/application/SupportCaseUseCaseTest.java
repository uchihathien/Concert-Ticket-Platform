// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.SupportCaseUseCase;
import com.nexaticket.aichatbox.application.handoff.SupportCaseUseCase.RefundOutcome;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.model.IncidentKind;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.RefundPolicy;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.support.FakeCommerce;
import com.nexaticket.aichatbox.support.InMemoryChatHistory;
import com.nexaticket.aichatbox.support.InMemoryHandoffRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Hoàn vé và sự cố: chính sách được thực thi ở đâu, phiếu mang gì cho người trực.
 *
 * <p>{@code HandoffUseCase} là bản thật trên kho trong bộ nhớ — thứ cần kiểm là use case này có
 * mở phiếu đúng nhãn, đúng dữ liệu, và ghi đủ hội thoại hay không.
 */
class SupportCaseUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    private final FakeCommerce.Ordering ordering = new FakeCommerce.Ordering();
    private final FakeCommerce.Knowledge knowledge = new FakeCommerce.Knowledge();
    private final InMemoryHandoffRepository handoffRepository = new InMemoryHandoffRepository();
    private final InMemoryChatHistory history = new InMemoryChatHistory();
    private final HandoffUseCase handoffs = new HandoffUseCase(
            handoffRepository,
            history,
            id -> Optional.empty(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new AgentMetrics(new SimpleMeterRegistry()));
    private final SupportCaseUseCase cases = new SupportCaseUseCase(
            ordering,
            knowledge,
            new FakeCommerce.Credentials(),
            handoffs,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ObjectMapper());

    // --- Hoàn vé -------------------------------------------------------------

    @Test
    void du_dieu_kien_thi_mo_phieu_hoan_ve_kem_du_lieu_cho_nguoi_truc() {
        knowledge.publishRules(eventId, "Đêm nhạc Trịnh", new RefundPolicy(true, 48));
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(2))));

        RefundOutcome outcome = cases.requestRefund(sessionId, userId, order.orderId(), "bận đột xuất", "hoàn vé giúp");

        assertThat(outcome).isInstanceOf(RefundOutcome.Opened.class);
        Handoff opened = ((RefundOutcome.Opened) outcome).handoff();
        assertThat(opened.intent()).isEqualTo(SupportIntent.REFUND);
        assertThat(opened.trigger()).isEqualTo(HandoffTrigger.CUSTOMER_REQUEST);
        assertThat(opened.reason())
                .startsWith("[Hoàn vé] đơn NT-240001 · 1.800.000đ")
                .contains("bận đột xuất");
        assertThat(opened.details()).contains("\"orderNumber\":\"NT-240001\"").contains("\"policy\":\"ALLOWED\"");
        // Lượt khách + câu báo đều nằm trong hội thoại, để người trực đọc được đúng câu mở đầu.
        assertThat(history.transcript(sessionId, userId, 10))
                .extracting(m -> m.role())
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
    }

    @Test
    void su_kien_khong_nhan_hoan_ve_thi_tu_choi_va_khong_mo_phieu() {
        knowledge.publishRules(eventId, "Đêm nhạc Trịnh", RefundPolicy.NONE);
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(2))));

        RefundOutcome outcome = cases.requestRefund(sessionId, userId, order.orderId(), null, "hoàn vé giúp");

        assertThat(outcome).isInstanceOf(RefundOutcome.Denied.class);
        assertThat(((RefundOutcome.Denied) outcome).explanation())
                .contains("Đêm nhạc Trịnh")
                .contains("không nhận hoàn vé");
        assertThat(handoffRepository.openBySession(sessionId)).isEmpty();
        // Không ghi gì vào hội thoại: lượt vẫn đang chạy, mô hình sẽ trả lời và lượt được ghi sau.
        assertThat(history.transcript(sessionId, userId, 10)).isEmpty();
    }

    @Test
    void qua_han_thi_tu_choi_va_neu_ro_moc() {
        knowledge.publishRules(eventId, "Đêm nhạc Trịnh", new RefundPolicy(true, 24));
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(30))));

        RefundOutcome outcome = cases.requestRefund(sessionId, userId, order.orderId(), null, "hoàn vé");

        assertThat(((RefundOutcome.Denied) outcome).explanation())
                .contains("24 giờ")
                .contains("NT-240001");
    }

    @Test
    void don_chua_thanh_toan_thi_chi_cach_huy_don() {
        knowledge.publishRules(eventId, "Đêm nhạc Trịnh", new RefundPolicy(true, 0));
        OrderSummary order = ordering.put(FakeCommerce.unpaidOrder(eventId));

        RefundOutcome outcome = cases.requestRefund(sessionId, userId, order.orderId(), null, "hoàn vé");

        assertThat(((RefundOutcome.Denied) outcome).explanation())
                .contains("chưa thanh toán")
                .contains("Huỷ đơn");
        assertThat(handoffRepository.openBySession(sessionId)).isEmpty();
    }

    /**
     * Sự kiện CHƯA khai chính sách thì mở phiếu, không từ chối. Mặc định "không hoàn" là cho tool
     * đọc quy định; ở đây từ chối một yêu cầu chưa ai quyết là sai theo hướng mất khách.
     */
    @Test
    void chua_co_quy_dinh_thi_mo_phieu_de_nguoi_quyet() {
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofDays(30))));

        RefundOutcome outcome = cases.requestRefund(sessionId, userId, order.orderId(), null, "hoàn vé");

        assertThat(outcome).isInstanceOf(RefundOutcome.Opened.class);
        Handoff opened = ((RefundOutcome.Opened) outcome).handoff();
        assertThat(opened.details()).contains("\"policy\":\"UNKNOWN\"");
        assertThat(opened.reason()).contains("chưa khai chính sách");
    }

    @Test
    void don_khong_phai_cua_khach_thi_tu_choi_nhu_khong_ton_tai() {
        RefundOutcome outcome = cases.requestRefund(sessionId, userId, UUID.randomUUID(), null, "hoàn vé");

        assertThat(((RefundOutcome.Denied) outcome).explanation()).contains("Không có đơn nào");
    }

    /** R-13: đã trả tiền chưa đủ — đơn đã hoàn/huỷ/hết hạn/đang đối soát thì không có gì để xin hoàn. */
    @ParameterizedTest
    @ValueSource(strings = {"REFUNDED", "CANCELLED", "EXPIRED", "MANUAL_REVIEW", "XYZ", "paid"})
    void don_khong_o_trang_thai_PAID_thi_tu_choi_va_khong_mo_phieu(String status) {
        knowledge.publishRules(eventId, "Đêm nhạc Trịnh", new RefundPolicy(true, 48));
        OrderSummary order =
                ordering.put(withStatus(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(2))), status));

        RefundOutcome outcome = cases.requestRefund(sessionId, userId, order.orderId(), null, "hoàn vé");

        assertThat(outcome).isInstanceOf(RefundOutcome.Denied.class);
        assertThat(((RefundOutcome.Denied) outcome).explanation()).contains("NT-240001");
        assertThat(handoffRepository.openBySession(sessionId)).isEmpty();
        assertThat(history.transcript(sessionId, userId, 10)).isEmpty();
    }

    @Test
    void moi_trang_thai_co_loi_giai_thich_rieng() {
        var explanations = java.util.stream.Stream.of("REFUNDED", "CANCELLED", "EXPIRED", "MANUAL_REVIEW", "XYZ")
                .map(status -> {
                    OrderSummary order = ordering.put(
                            withStatus(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(2))), status));
                    return ((RefundOutcome.Denied)
                                    cases.requestRefund(sessionId, userId, order.orderId(), null, "hoàn"))
                            .explanation();
                })
                .toList();

        assertThat(explanations).doesNotHaveDuplicates();
        assertThat(explanations.get(0)).contains("đã được hoàn tiền");
        assertThat(explanations.get(4)).contains("XYZ");
    }

    /** R-13: một đơn chỉ có một phiếu hoàn vé đang mở, dù khách xin lại ở phiên khác. */
    @Test
    void da_co_phieu_hoan_dang_mo_cho_don_thi_tu_choi_phieu_thu_hai() {
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(2))));
        RefundOutcome first = cases.requestRefund(sessionId, userId, order.orderId(), null, "hoàn vé");
        assertThat(first).isInstanceOf(RefundOutcome.Opened.class);

        UUID otherSession = UUID.randomUUID();
        RefundOutcome second = cases.requestRefund(otherSession, userId, order.orderId(), null, "hoàn vé lần nữa");

        assertThat(second).isInstanceOf(RefundOutcome.Denied.class);
        assertThat(((RefundOutcome.Denied) second).explanation())
                .contains("NT-240001")
                .contains("đang được xem xét")
                .contains("17:00 ngày 07/10/2026")
                .contains("không cần gửi yêu cầu mới");
        assertThat(handoffRepository.openBySession(otherSession)).isEmpty();
        assertThat(history.transcript(otherSession, userId, 10)).isEmpty();

        // Đóng phiếu cũ rồi thì xin lại được.
        Handoff opened = ((RefundOutcome.Opened) first).handoff();
        handoffs.resolve(opened.id(), UUID.randomUUID());
        assertThat(cases.requestRefund(otherSession, userId, order.orderId(), null, "hoàn vé"))
                .isInstanceOf(RefundOutcome.Opened.class);
    }

    @Test
    void phieu_hoan_cua_don_khac_hoac_khach_khac_khong_tinh_la_trung() {
        OrderSummary mine = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(2))));
        OrderSummary another = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(2))));
        cases.requestRefund(sessionId, userId, mine.orderId(), null, "hoàn vé");

        assertThat(cases.requestRefund(UUID.randomUUID(), userId, another.orderId(), null, "hoàn vé"))
                .isInstanceOf(RefundOutcome.Opened.class);
        assertThat(cases.requestRefund(UUID.randomUUID(), UUID.randomUUID(), mine.orderId(), null, "hoàn vé"))
                .isInstanceOf(RefundOutcome.Opened.class);
    }

    private static OrderSummary withStatus(OrderSummary o, String status) {
        return new OrderSummary(
                o.orderId(),
                o.orderNumber(),
                o.eventId(),
                o.eventSessionId(),
                status,
                o.totalVnd(),
                o.paymentExpiresAt(),
                o.paidAt(),
                o.items());
    }

    // --- Sự cố ---------------------------------------------------------------

    @Test
    void phieu_su_co_theo_khung_mau_kem_loai_de_nguoi_truc_co_checklist() {
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(1))));

        Handoff opened = cases.reportIncident(
                sessionId,
                userId,
                IncidentKind.TICKET_NOT_RECEIVED,
                "Thanh toán lúc 9h mà ví vé trống",
                order.orderId(),
                "chưa thấy vé đâu");

        assertThat(opened.intent()).isEqualTo(SupportIntent.INCIDENT);
        assertThat(opened.reason())
                .isEqualTo("[Sự cố] Chưa nhận được vé · đơn NT-240001: Thanh toán lúc 9h mà ví vé trống");
        assertThat(opened.details()).contains("\"kind\":\"TICKET_NOT_RECEIVED\"");
        // Người trực nhận phiếu thì có ngay checklist của loại sự cố này.
        assertThat(handoffs.claim(opened.id(), UUID.randomUUID(), 50).checklist())
                .isNotEmpty()
                .anyMatch(step -> step.contains("ticketing"));
    }

    @Test
    void khong_tra_duoc_don_van_mo_phieu_voi_ma_tho() {
        ordering.down = true;
        UUID orderId = UUID.randomUUID();

        Handoff opened = cases.reportIncident(
                sessionId, userId, IncidentKind.PAYMENT_NOT_CONFIRMED, "Đã chuyển 450k", orderId, "chuyển rồi mà");

        // Sự cố thật không bị chặn bởi một lời gọi phụ hỏng; mã đơn thô vẫn tới tay người trực —
        // nhưng ở khoá riêng, gắn cờ chưa xác minh, không bao giờ ở `orderId`.
        assertThat(opened.details())
                .contains("\"claimedOrderId\":\"" + orderId + "\"")
                .doesNotContain("\"orderId\"")
                .contains("\"orderVerified\":false")
                .contains("\"orderLookup\":\"UNAVAILABLE\"");
        assertThat(opened.reason())
                .contains("đơn chưa xác minh " + orderId.toString().substring(0, 8) + "…");
    }

    /** R-12: UUID đơn của người khác kèm "mất vé" không được nằm trên phiếu dưới bất kỳ khoá nào. */
    @Test
    void don_khong_thuoc_khach_thi_phieu_su_co_khong_mang_ma_don() {
        UUID someoneElsesOrder = UUID.randomUUID();

        Handoff opened = cases.reportIncident(
                sessionId,
                userId,
                IncidentKind.TICKET_NOT_RECEIVED,
                "mất vé, phát lại giúp",
                someoneElsesOrder,
                "mất vé");

        assertThat(opened.details())
                .doesNotContain(someoneElsesOrder.toString())
                .contains("\"orderVerified\":false")
                .contains("\"orderLookup\":\"NOT_IN_ACCOUNT\"");
        assertThat(opened.reason())
                .doesNotContain(someoneElsesOrder.toString().substring(0, 8))
                .isEqualTo("[Sự cố] Chưa nhận được vé: mất vé, phát lại giúp");
    }

    @Test
    void don_cua_khach_thi_phieu_su_co_mang_ma_da_xac_minh() {
        OrderSummary order = ordering.put(FakeCommerce.paidOrder(eventId, NOW.minus(Duration.ofHours(1))));

        Handoff opened = cases.reportIncident(
                sessionId, userId, IncidentKind.TICKET_NOT_RECEIVED, "mất vé", order.orderId(), "mất vé");

        assertThat(opened.details())
                .contains("\"orderId\":\"" + order.orderId() + "\"")
                .contains("\"orderVerified\":true")
                .doesNotContain("claimedOrderId");
        assertThat(opened.reason()).contains("NT-240001");
    }

    @Test
    void su_co_khong_gan_don_thi_khong_co_phan_don_trong_ly_do() {
        Handoff opened = cases.reportIncident(
                sessionId, userId, IncidentKind.EVENT_CHANGED, "Nghe nói dời sang tháng sau", null, "dời à");

        assertThat(opened.reason()).isEqualTo("[Sự cố] Sự kiện thay đổi hoặc huỷ: Nghe nói dời sang tháng sau");
        assertThat(opened.details()).doesNotContain("orderVerified");
    }
}
