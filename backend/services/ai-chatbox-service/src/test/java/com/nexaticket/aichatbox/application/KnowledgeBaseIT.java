// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import com.nexaticket.aichatbox.application.agent.ToolDispatcher;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeBaseUseCase;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeViews;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import com.nexaticket.platform.web.error.ApiException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Soạn kho tri thức, rồi kiểm rằng trợ lý đọc được đúng những gì được phép đọc.
 *
 * <p>Test này đi hết một vòng mà trước đây không tồn tại: <b>ghi</b> vào kho, rồi đọc lại bằng đúng
 * đường mà agent đi. Không có đường ghi thì mọi test đọc chỉ kiểm được một bảng rỗng.
 */
class KnowledgeBaseIT extends AiChatboxTestBase {

    @Autowired
    KnowledgeBaseUseCase knowledge;

    @Autowired
    VectorStorePort store;

    @Autowired
    ToolDispatcher tools;

    @Autowired
    FakeAiProviders.FakeEmbeddings embeddings;

    @AfterEach
    void repairEmbeddings() {
        embeddings.broken = false;
    }

    @Test
    void doan_vua_soan_thi_tim_lai_duoc_bang_dung_duong_agent_di() {
        knowledge.addChunk(null, "Chính sách hoàn vé", "Vé đã thanh toán được hoàn tiền trong bảy ngày đầu.");
        knowledge.addChunk(null, "Giờ mở cửa", "Cửa soát vé mở trước giờ diễn sáu mươi phút.");

        var retrieved = knowledge.preview(null, "chính sách hoàn vé thế nào");

        assertThat(retrieved).isNotEmpty();
        assertThat(retrieved.get(0).title()).isEqualTo("Chính sách hoàn vé");
        assertThat(retrieved.get(0).used()).isTrue();
        // Đoạn lạc đề vẫn được pgvector trả về — nó luôn trả đủ top-k. Thứ chặn nó là NGƯỠNG, và
        // đó là lý do cờ `used` có mặt trong bản xem trước.
        assertThat(retrieved)
                .filteredOn(row -> row.title().equals("Giờ mở cửa"))
                .allSatisfy(row -> assertThat(row.distance())
                        .isGreaterThan(retrieved.get(0).distance()));
    }

    @Test
    void kho_rong_thi_khong_tra_ve_gi() {
        assertThat(knowledge.preview(null, "một câu hỏi về thứ chưa ai soạn bao giờ"))
                .allSatisfy(row -> assertThat(row.used()).isFalse());
    }

    @Test
    void xoa_doan_roi_thi_khong_con_tim_thay() {
        UUID id = knowledge.addChunk(null, "Quy định tạm", "Nội dung sẽ bị xoá.");

        knowledge.deleteChunk(id);

        assertThat(knowledge.listChunks(null, 100, 0))
                .extracting(KnowledgeViews.ChunkRow::id)
                .doesNotContain(id);
    }

    @Test
    void xoa_doan_khong_ton_tai_thi_404() {
        assertThatThrownBy(() -> knowledge.deleteChunk(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Không tìm thấy đoạn tri thức");
    }

    @Test
    void loc_theo_su_kien() {
        UUID eventId = UUID.randomUUID();
        knowledge.addChunk(eventId, "Quy định riêng", "Không mang chai thuỷ tinh.");
        knowledge.addChunk(null, "Tri thức chung", "Thanh toán bằng chuyển khoản.");

        assertThat(knowledge.listChunks(eventId, 100, 0))
                .extracting(KnowledgeViews.ChunkRow::title)
                .containsExactly("Quy định riêng");
    }

    @Test
    void doan_cua_su_kien_khac_khong_lot_vao_cau_tra_loi_chung() {
        UUID eventId = UUID.randomUUID();
        knowledge.addChunk(eventId, "Quy định Đêm nhạc Hạ", "Đêm nhạc Hạ cấm mang chai thuỷ tinh.");

        // Không có ngữ cảnh sự kiện ⇒ CHỈ tri thức chung. Trước khi có phạm vi, đoạn trên là kết
        // quả gần nhất cho câu hỏi này và trợ lý sẽ trả lời quy định của một sự kiện mà khách
        // không hề nhắc tới — sai, mà nghe rất hợp lý.
        assertThat(knowledge.preview(null, "cấm mang chai thuỷ tinh à"))
                .extracting(KnowledgeViews.RetrievedRow::title)
                .doesNotContain("Quy định Đêm nhạc Hạ");

        // Khai đúng sự kiện thì thấy.
        assertThat(knowledge.preview(eventId, "cấm mang chai thuỷ tinh à"))
                .extracting(KnowledgeViews.RetrievedRow::title)
                .contains("Quy định Đêm nhạc Hạ");
    }

    @Test
    void tri_thuc_chung_luon_nam_trong_pham_vi() {
        UUID eventId = UUID.randomUUID();
        knowledge.addChunk(null, "Chính sách chung", "Vé đã thanh toán được hoàn trong bảy ngày.");

        // Hỏi trong ngữ cảnh một sự kiện vẫn phải thấy chính sách của nền tảng — nếu không thì mọi
        // câu hỏi gắn sự kiện đều mất phần tri thức quan trọng nhất.
        assertThat(knowledge.preview(eventId, "chính sách hoàn vé"))
                .extracting(KnowledgeViews.RetrievedRow::title)
                .contains("Chính sách chung");
    }

    @Test
    void ban_nhap_khong_lo_cho_khach() {
        UUID eventId = UUID.randomUUID();

        knowledge.upsertRules(eventId, "Đêm nhạc Hạ", "Cấm mang chai thuỷ tinh.", false);

        // Người soạn thấy bản nháp...
        assertThat(knowledge.rules(eventId).published()).isFalse();
        // ...còn tool của trợ lý thì không. Mặc định `published = false` là sai theo hướng an toàn.
        assertThat(store.findRules(eventId)).isEmpty();
    }

    @Test
    void cong_bo_roi_thi_tool_doc_duoc() {
        UUID eventId = UUID.randomUUID();
        knowledge.upsertRules(eventId, "Đêm nhạc Hạ", "Cửa mở trước 60 phút.", true);

        ToolOutcome outcome = tools.dispatch(
                new ToolInvocation("call-1", SupportAgentTools.GET_EVENT_RULES, Map.of("eventId", eventId.toString())));

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.payload()).contains("Đêm nhạc Hạ").contains("Cửa mở trước 60 phút");
    }

    @Test
    void ghi_de_thi_thay_ca_noi_dung_va_trang_thai_cong_bo() {
        UUID eventId = UUID.randomUUID();
        knowledge.upsertRules(eventId, "Đêm nhạc Hạ", "Bản nháp đầu.", false);

        knowledge.upsertRules(eventId, "Đêm nhạc Hạ 2025", "Bản đã duyệt.", true);

        KnowledgeViews.RulesRow rules = knowledge.rules(eventId);
        assertThat(rules.eventTitle()).isEqualTo("Đêm nhạc Hạ 2025");
        assertThat(rules.content()).isEqualTo("Bản đã duyệt.");
        assertThat(rules.published()).isTrue();
        assertThat(store.findRules(eventId)).isPresent();
    }

    @Test
    void chua_soan_quy_dinh_thi_404() {
        assertThatThrownBy(() -> knowledge.rules(UUID.randomUUID())).isInstanceOf(ApiException.class);
    }

    @Test
    void khong_nhung_duoc_thi_bao_ngay_luc_luu() {
        embeddings.broken = true;

        // Ngược hẳn đường đọc của agent, nơi mất embedding chỉ làm trợ lý kém thông tin. Lưu một
        // đoạn không có vector là lưu một đoạn không bao giờ tìm ra được.
        assertThatThrownBy(() -> knowledge.addChunk(null, "Tiêu đề", "Nội dung"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Không nhúng được văn bản");
    }
}
