// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.application.agent.AgentReply;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffViews;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.port.ChatHistoryPort;
import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Đường chuyển cuộc chat sang người thật, chạy trên database thật. */
class HandoffFlowIT extends AiChatboxTestBase {

    @Autowired
    CustomerSupportAgentUseCase agent;

    @Autowired
    HandoffUseCase handoffs;

    @Autowired
    HandoffRepository repository;

    @Autowired
    ChatHistoryPort history;

    @Autowired
    FakeAiProviders.FakeLlm llm;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void resetModel() {
        llm.reset();
    }

    // --- Lượt đầu tiên: phiên chưa tồn tại --------------------------------
    //
    // Cả ba test dưới đây là cùng một lỗi: phiếu có khoá ngoại trỏ tới `chat_sessions`, mà dòng đó
    // chỉ ra đời khi có tin nhắn đầu tiên. Mở phiếu trước khi ghi tin nhắn thì lượt ĐẦU của một
    // cuộc trò chuyện vỡ ở khoá ngoại và khách nhận 500 thay vì một phiếu.

    @Test
    void khach_xin_gap_nguoi_that_ngay_o_luot_dau_tien() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "cho tôi gặp nhân viên");

        assertThat(reply.toolsUsed()).containsExactly(SupportAgentTools.ESCALATE_TO_HUMAN);
        assertThat(handoffs.openFor(sessionId)).isPresent();
        // Không hỏi mô hình một lượt nào: ý định nhận được bằng so khớp câu chữ.
        assertThat(llm.calls).hasValue(0);
        // Câu khiến khách phải nhờ người vẫn nằm trong hội thoại — người trực phải đọc được nó.
        assertThat(history.transcript(sessionId, userId, 10))
                .extracting(message -> message.role())
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
    }

    @Test
    void mo_hinh_goi_tool_chuyen_tiep_ngay_o_luot_dau_tien() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        llm.willCallTool(SupportAgentTools.ESCALATE_TO_HUMAN, Map.of("reason", "Khách đòi hoàn tiền ngoài chính sách"));

        agent.executeAgentProcess(sessionId, userId, "tôi muốn hoàn vé đã mua tuần trước");

        Handoff opened = handoffs.openFor(sessionId).orElseThrow();
        assertThat(opened.reason()).isEqualTo("Khách đòi hoàn tiền ngoài chính sách");
        assertThat(opened.lastQuestion()).isEqualTo("tôi muốn hoàn vé đã mua tuần trước");
    }

    @Test
    void het_vong_react_ngay_o_luot_dau_tien() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        // Mô hình cứ tra mãi mà không trả lời — hành vi rất thường thấy của mô hình nhỏ.
        llm.alwaysCallsTool(
                SupportAgentTools.GET_EVENT_RULES,
                Map.of("eventId", UUID.randomUUID().toString()));

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "sự kiện này có cho mang nước vào không");

        assertThat(reply.toolsUsed()).containsExactly(SupportAgentTools.ESCALATE_TO_HUMAN);
        assertThat(handoffs.openFor(sessionId)).isPresent();
        // Trần vòng lặp là chốt chặn chi phí: bốn vòng, không hơn.
        assertThat(llm.calls).hasValue(4);
    }

    // --- Quyền trên phiên --------------------------------------------------

    @Test
    void khong_mo_duoc_phieu_tren_phien_cua_nguoi_khac() {
        UUID sessionId = UUID.randomUUID();
        UUID chuPhien = UUID.randomUUID();
        UUID nguoiLa = UUID.randomUUID();
        agent.executeAgentProcess(sessionId, chuPhien, "vé của mình bao giờ tới");

        assertThatThrownBy(() -> handoffs.requestByCustomer(sessionId, nguoiLa))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Không tìm thấy phiên chat");

        // Và điều quan trọng nhất: trợ lý của chủ phiên KHÔNG bị tắt.
        assertThat(handoffs.openFor(sessionId)).isEmpty();
    }

    @Test
    void phien_khong_ton_tai_thi_khong_mo_duoc_phieu() {
        assertThatThrownBy(() -> handoffs.requestByCustomer(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
    }

    // --- Phiếu mở là công tắc tắt trợ lý ----------------------------------

    @Test
    void phieu_mo_thi_tro_ly_khong_goi_mo_hinh_nua() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        agent.executeAgentProcess(sessionId, userId, "cho tôi gặp nhân viên");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "sao lâu thế");

        assertThat(reply.toolsUsed()).isEmpty();
        assertThat(llm.calls).hasValue(0);
        // Lời khách vẫn được ghi để người trực đọc được: 2 lượt của lần chuyển + 1 câu vừa nhắn.
        assertThat(history.transcript(sessionId, userId, 10)).hasSize(3);
    }

    @Test
    void bam_nut_gap_nhan_vien_ba_lan_chi_ra_mot_phieu() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        history.append(sessionId, userId, ChatRole.USER, "chào bạn");

        HandoffViews.HandoffRow first = handoffs.requestByCustomer(sessionId, userId);
        HandoffViews.HandoffRow second = handoffs.requestByCustomer(sessionId, userId);
        HandoffViews.HandoffRow third = handoffs.requestByCustomer(sessionId, userId);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(third.id()).isEqualTo(first.id());
    }

    // --- Đường của người trực ---------------------------------------------

    @Test
    void nhan_phieu_hai_lan_van_la_phieu_cua_minh() {
        UUID handoffId = openHandoff();
        UUID nguoiTruc = UUID.randomUUID();

        HandoffViews.HandoffRow first = handoffs.claim(handoffId, nguoiTruc);
        // F5, hoặc bấm hai lần. 409 ở đây làm màn hình gỡ phiếu khỏi tay người đang trả lời nó.
        HandoffViews.HandoffRow again = handoffs.claim(handoffId, nguoiTruc);

        assertThat(first.status()).isEqualTo("ASSIGNED");
        assertThat(again.assignedAgentId()).isEqualTo(nguoiTruc);
    }

    @Test
    void nguoi_khac_nhan_truoc_thi_409() {
        UUID handoffId = openHandoff();
        handoffs.claim(handoffId, UUID.randomUUID());

        assertThatThrownBy(() -> handoffs.claim(handoffId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("vừa được người khác nhận");
    }

    @Test
    void chua_nhan_phieu_thi_khong_tra_loi_duoc() {
        UUID handoffId = openHandoff();

        assertThatThrownBy(() -> handoffs.reply(handoffId, UUID.randomUUID(), "chào bạn"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nhận phiếu này trước");
    }

    @Test
    void phieu_da_dong_thi_khong_tra_loi_duoc_nua() {
        UUID handoffId = openHandoff();
        UUID nguoiTruc = UUID.randomUUID();
        handoffs.claim(handoffId, nguoiTruc);
        handoffs.resolve(handoffId, nguoiTruc);

        // Từ lượt kế tiếp trợ lý AI đã trả lời trở lại. Thêm một câu của người trực vào lúc này là
        // dựng lại đúng cảnh mà cả tính năng này tồn tại để tránh.
        assertThatThrownBy(() -> handoffs.reply(handoffId, nguoiTruc, "còn gì nữa không bạn"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("đã đóng");
    }

    @Test
    void dong_phieu_hai_lan_khong_doi_moc_thoi_gian() {
        UUID handoffId = openHandoff();
        UUID nguoiTruc = UUID.randomUUID();
        handoffs.claim(handoffId, nguoiTruc);

        handoffs.resolve(handoffId, nguoiTruc);
        var sauLanDau = repository.findById(handoffId).orElseThrow().resolvedAt();
        handoffs.resolve(handoffId, nguoiTruc);

        assertThat(repository.findById(handoffId).orElseThrow().resolvedAt()).isEqualTo(sauLanDau);
    }

    @Test
    void nguoi_truc_tra_loi_thi_khach_doc_duoc_trong_cung_dong_thoi_gian() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        agent.executeAgentProcess(sessionId, userId, "cho tôi gặp nhân viên");
        UUID handoffId = handoffs.openFor(sessionId).orElseThrow().id();
        UUID nguoiTruc = UUID.randomUUID();
        handoffs.claim(handoffId, nguoiTruc);

        handoffs.reply(handoffId, nguoiTruc, "Chào bạn, mình xem đơn giúp bạn ngay.");

        // Ghi dưới vai AGENT nhưng thuộc phiên của KHÁCH: ghi id người trực vào `user_id` sẽ làm
        // chính cuộc hội thoại ấy biến mất khỏi màn hình của khách.
        assertThat(history.transcript(sessionId, userId, 10))
                .extracting(message -> message.role())
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.AGENT);
    }

    @Test
    void dong_phieu_roi_tro_ly_tra_loi_tro_lai() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        agent.executeAgentProcess(sessionId, userId, "cho tôi gặp nhân viên");
        UUID handoffId = handoffs.openFor(sessionId).orElseThrow().id();
        UUID nguoiTruc = UUID.randomUUID();
        handoffs.claim(handoffId, nguoiTruc);
        handoffs.resolve(handoffId, nguoiTruc);
        llm.willAnswer("Vé của bạn đã phát hành rồi nhé.");

        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "vé của mình sao rồi");

        assertThat(reply.answer()).isEqualTo("Vé của bạn đã phát hành rồi nhé.");
        assertThat(llm.calls).hasValue(1);
    }

    @Test
    void hang_doi_cu_nhat_truoc() {
        UUID som = openHandoff();
        UUID muon = openHandoff();

        var queue = handoffs.queue(null, 50, 0).stream()
                .map(HandoffViews.HandoffRow::id)
                .toList();

        assertThat(queue).containsSubsequence(som, muon);
    }

    @Test
    void phieu_bo_quen_duoc_tu_dong() {
        UUID conNguoiCho = openHandoff();
        UUID daBoDi = openHandoff();
        // Đẩy một phiếu về quá khứ — cách duy nhất kiểm được ngưỡng mà không phải chờ 24 giờ.
        repository.save(repository.findById(daBoDi).orElseThrow());
        jdbc.sql("update chat_handoffs set requested_at = now() - interval '48 hours' where id = :id")
                .param("id", daBoDi)
                .update();

        int closed = repository.closeAbandoned(Instant.now().minus(Duration.ofHours(24)), Instant.now());

        assertThat(closed).isEqualTo(1);
        assertThat(repository.findById(daBoDi).orElseThrow().isOpen()).isFalse();
        // Phiếu còn trong hạn không bị đụng tới.
        assertThat(repository.findById(conNguoiCho).orElseThrow().isOpen()).isTrue();
    }

    @Test
    void khong_tu_dong_phieu_da_co_nguoi_nhan() {
        UUID handoffId = openHandoff();
        handoffs.claim(handoffId, UUID.randomUUID());
        jdbc.sql("update chat_handoffs set requested_at = now() - interval '48 hours' where id = :id")
                .param("id", handoffId)
                .update();

        // Người trực chậm vẫn là người trực. Tự đóng một cuộc đang được trả lời là cắt ngang nó.
        assertThat(repository.closeAbandoned(Instant.now().minus(Duration.ofHours(24)), Instant.now()))
                .isZero();
    }

    @Test
    void dong_phieu_bo_quen_thi_tro_ly_tra_loi_tro_lai() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        agent.executeAgentProcess(sessionId, userId, "cho tôi gặp nhân viên");
        jdbc.sql("update chat_handoffs set requested_at = now() - interval '48 hours' where session_id = :id")
                .param("id", sessionId)
                .update();
        repository.closeAbandoned(Instant.now().minus(Duration.ofHours(24)), Instant.now());
        llm.willAnswer("Mình trả lời tiếp nhé.");

        // Đây mới là lý do thật của job dọn: phiếu treo vĩnh viễn nghĩa là khách quay lại sau ba
        // ngày vẫn chỉ nhận được câu "đang chờ nhân viên".
        AgentReply reply = agent.executeAgentProcess(sessionId, userId, "còn đó không");

        assertThat(reply.answer()).isEqualTo("Mình trả lời tiếp nhé.");
    }

    /** Một phiếu đang chờ, trên một phiên có thật. */
    private UUID openHandoff() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        agent.executeAgentProcess(sessionId, userId, "cho tôi gặp nhân viên");
        return handoffs.openFor(sessionId).orElseThrow().id();
    }
}
