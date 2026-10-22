// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.agent.CustomerSupportAgentUseCase;
import com.nexaticket.aichatbox.application.handoff.AbandonedHandoffReaper;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Phiếu ASSIGNED bị bỏ rơi được trả về hàng đợi — trên Postgres thật, vì phần quan trọng nằm trong
 * SQL: điều kiện "không có tin AGENT gần đây", ghi {@code details.lastHandover} mà giữ khoá cũ, và
 * bước đóng phiếu không đóng nhầm phiếu vừa nhả (R-02).
 */
class StaleHandoffReleaseIT extends AiChatboxTestBase {

    @Autowired
    CustomerSupportAgentUseCase agent;

    @Autowired
    HandoffUseCase handoffs;

    @Autowired
    HandoffRepository repository;

    @Autowired
    FakeAiProviders.FakeLlm llm;

    @Autowired
    JdbcClient jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetModel() {
        llm.reset();
    }

    private Handoff claimed(UUID agentId) {
        UUID sessionId = UUID.randomUUID();
        agent.executeAgentProcess(sessionId, UUID.randomUUID(), "cho tôi gặp nhân viên");
        Handoff opened = handoffs.openFor(sessionId).orElseThrow();
        handoffs.claim(opened.id(), agentId, 10);
        return repository.findById(opened.id()).orElseThrow();
    }

    private void backdate(UUID id, Duration requestedAgo, Duration assignedAgo) {
        jdbc.sql(
                        """
                        update chat_handoffs
                           set requested_at = now() - make_interval(secs => :requested),
                               assigned_at  = now() - make_interval(secs => :assigned)
                         where id = :id
                        """)
                .param("requested", requestedAgo.toSeconds())
                .param("assigned", assignedAgo.toSeconds())
                .param("id", id)
                .update();
    }

    private JsonNode lastHandover(UUID id) throws Exception {
        String details = jdbc.sql("select details from chat_handoffs where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        return json.readTree(details).path("lastHandover");
    }

    @Test
    void phieu_nhan_qua_han_khong_tra_loi_ve_hang_doi_va_khong_bi_dong_ngay() throws Exception {
        UUID agentA = UUID.randomUUID();
        Handoff h = claimed(agentA);
        // Khách yêu cầu 30 giờ trước, nhân viên nhận lúc 23 giờ trước rồi bỏ đó.
        backdate(h.id(), Duration.ofHours(30), Duration.ofHours(23));
        Instant now = Instant.now();

        int released = repository.releaseStaleAssigned(now.minus(Duration.ofHours(2)), now);
        // Bước đóng phiếu chạy ngay sau, như reaper làm: phiếu vừa nhả KHÔNG được đóng dù
        // requested_at đã quá 24 giờ.
        repository.closeAbandoned(now.minus(Duration.ofHours(24)), now);

        assertThat(released).isGreaterThanOrEqualTo(1);
        Handoff after = repository.findById(h.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(HandoffStatus.WAITING);
        assertThat(after.assignedAgentId()).isNull();
        assertThat(after.assignedAt()).isNull();
        JsonNode note = lastHandover(h.id());
        assertThat(note.path("action").asText()).isEqualTo("AUTO_RELEASE");
        assertThat(note.path("from").asText()).isEqualTo(agentA.toString());
        assertThat(note.path("by").isNull()).isTrue();
        // Khách vẫn thấy phiếu mở — trợ lý vẫn tắt cho tới khi có người nhận.
        assertThat(handoffs.openFor(after.sessionId())).isPresent();
    }

    @Test
    void phieu_nhan_lau_nhung_vua_tra_loi_thi_giu_nguyen() {
        UUID agentA = UUID.randomUUID();
        Handoff h = claimed(agentA);
        backdate(h.id(), Duration.ofHours(5), Duration.ofHours(3));
        handoffs.reply(h.id(), agentA, "Mình đang xem giúp bạn nhé");
        Instant now = Instant.now();

        repository.releaseStaleAssigned(now.minus(Duration.ofHours(2)), now);

        Handoff after = repository.findById(h.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(HandoffStatus.ASSIGNED);
        assertThat(after.assignedAgentId()).isEqualTo(agentA);
    }

    @Test
    void nha_tay_ghi_ghi_chu_ban_giao_va_giu_khoa_cu_cua_details() throws Exception {
        UUID agentA = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Handoff h = claimed(agentA);
        jdbc.sql("update chat_handoffs set details = :d where id = :id")
                .param("d", "{\"orderId\":\"ord-1\",\"kind\":\"TICKET_NOT_RECEIVED\"}")
                .param("id", h.id())
                .update();

        handoffs.release(h.id(), actor, "Hết ca");

        String details = jdbc.sql("select details from chat_handoffs where id = :id")
                .param("id", h.id())
                .query(String.class)
                .single();
        JsonNode root = json.readTree(details);
        assertThat(root.path("orderId").asText()).isEqualTo("ord-1");
        assertThat(root.path("kind").asText()).isEqualTo("TICKET_NOT_RECEIVED");
        JsonNode note = root.path("lastHandover");
        assertThat(note.path("action").asText()).isEqualTo("RELEASE");
        assertThat(note.path("by").asText()).isEqualTo(actor.toString());
        assertThat(note.path("from").asText()).isEqualTo(agentA.toString());
        assertThat(note.path("reason").asText()).isEqualTo("Hết ca");
        assertThat(note.path("at").asText()).isNotBlank();
    }

    @Test
    void reaper_chay_ca_hai_buoc() {
        UUID agentA = UUID.randomUUID();
        Handoff h = claimed(agentA);
        backdate(h.id(), Duration.ofHours(4), Duration.ofHours(3));
        AbandonedHandoffReaper reaper = new AbandonedHandoffReaper(
                repository, java.time.Clock.systemUTC(), Duration.ofHours(24), Duration.ofHours(2));

        reaper.closeAbandoned();

        assertThat(repository.findById(h.id()).orElseThrow().status()).isEqualTo(HandoffStatus.WAITING);
    }
}
