// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import com.nexaticket.aichatbox.application.agent.ToolDispatcher;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeBaseUseCase;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeViews;
import com.nexaticket.aichatbox.domain.model.KnowledgeChunk;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import com.nexaticket.aichatbox.support.FakeAiProviders;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

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

        knowledge.upsertRules(eventId, "Đêm nhạc Hạ", "Cấm mang chai thuỷ tinh.", false, 0, false);

        // Người soạn thấy bản nháp...
        assertThat(knowledge.rules(eventId).published()).isFalse();
        // ...còn tool của trợ lý thì không. Mặc định `published = false` là sai theo hướng an toàn.
        assertThat(store.findRules(eventId)).isEmpty();
    }

    @Test
    void cong_bo_roi_thi_tool_doc_duoc() {
        UUID eventId = UUID.randomUUID();
        knowledge.upsertRules(eventId, "Đêm nhạc Hạ", "Cửa mở trước 60 phút.", false, 0, true);

        ToolOutcome outcome = tools.dispatch(new ToolInvocation(
                        "call-1", SupportAgentTools.GET_EVENT_RULES, Map.of("eventId", eventId.toString())))
                .outcome();

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.payload()).contains("Đêm nhạc Hạ").contains("Cửa mở trước 60 phút");
    }

    @Test
    void ghi_de_thi_thay_ca_noi_dung_va_trang_thai_cong_bo() {
        UUID eventId = UUID.randomUUID();
        knowledge.upsertRules(eventId, "Đêm nhạc Hạ", "Bản nháp đầu.", false, 0, false);

        knowledge.upsertRules(eventId, "Đêm nhạc Hạ 2025", "Bản đã duyệt.", false, 0, true);

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

    @Test
    void chinh_sach_hoan_ve_di_cung_quy_dinh_va_tool_doc_duoc() {
        UUID eventId = UUID.randomUUID();

        knowledge.upsertRules(eventId, "Đêm nhạc Hạ", "Cửa mở trước 60 phút.", true, 48, true);

        // Người soạn thấy đúng hai giá trị vừa nhập...
        KnowledgeViews.RulesRow row = knowledge.rules(eventId);
        assertThat(row.refundAllowed()).isTrue();
        assertThat(row.refundWindowHours()).isEqualTo(48);
        // ...và tool đọc được chính sách dưới dạng có cấu trúc, không phải tự đọc trong văn bản.
        assertThat(store.findRules(eventId).orElseThrow().refundPolicy().allowed())
                .isTrue();
        assertThat(store.findRules(eventId).orElseThrow().refundPolicy().windowHours())
                .isEqualTo(48);
    }

    @Test
    void chua_khai_chinh_sach_thi_mac_dinh_khong_hoan_ve() {
        UUID eventId = UUID.randomUUID();
        knowledge.upsertRules(eventId, "Đêm nhạc Hạ", "Cửa mở trước 60 phút.", false, 0, true);

        // Sai theo hướng an toàn: quên khai thì tool nói "không nhận hoàn vé", không nói "có".
        assertThat(store.findRules(eventId).orElseThrow().refundPolicy().allowed())
                .isFalse();
    }

    /** AC #5: tiêu đề là khoá của tri thức chung — trùng thì 409, không phải 500. */
    @Test
    void trung_tieu_de_tri_thuc_chung_thi_409_con_doan_su_kien_thi_khong() {
        String title = "Tiêu đề " + UUID.randomUUID();
        knowledge.addChunk(null, title, "Bản đầu.");

        assertThatThrownBy(() -> knowledge.addChunk(null, title, "Bản thứ hai."))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(AiChatboxErrorCode.KNOWLEDGE_TITLE_TAKEN));

        UUID eventId = UUID.randomUUID();
        knowledge.addChunk(eventId, title, "Đoạn riêng của sự kiện.");
        knowledge.addChunk(UUID.randomUUID(), title, "Đoạn riêng của sự kiện khác.");
    }

    /**
     * R-29: 2.000 đoạn của sự kiện Y gần câu hỏi hơn — đoạn của sự kiện X vẫn phải có trong kết quả.
     *
     * <p>Buộc planner đi index HNSW ({@code enable_seqscan = off}, đặt trong transaction bao ngoài mà
     * {@code searchSimilar} tham gia): ở 2.000 dòng planner có thể tự chọn quét tuần tự — chính xác
     * tuyệt đối — và ca này sẽ xanh mà không chứng minh gì.
     */
    @Test
    void loc_theo_su_kien_khong_bi_doan_cua_su_kien_khac_day_khoi_ket_qua() {
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        // Y: hướng e1 cộng nhiễu ngẫu nhiên nhỏ ở các chiều 2..1001 → khoảng cách cosine ≤ ~0,02.
        // Nhiễu phải NGẪU NHIÊN: 2.000 vector gần như trùng nhau dựng ra một đồ thị HNSW hỏng (đo
        // được: không lọc gì, index chỉ với tới 591/2.003 dòng), và ca này sẽ đo đồ thị hỏng chứ
        // không đo ef_search. `where g > 0` buộc truy vấn con chạy lại cho từng dòng.
        jdbc.sql(
                        """
                        insert into event_knowledge_embeddings (id, event_id, title, content, embedding)
                        select gen_random_uuid(), :y, 'Y ' || g, 'đoạn của sự kiện khác',
                               (select ('[' || string_agg(case when i = 1 then '1'
                                                               when i <= 1001 then (random() * 0.01)::text
                                                               else '0' end, ',' order by i) || ']')::vector
                                from generate_series(1, 1024) i
                                where g > 0)
                        from generate_series(1, 2000) g
                        """)
                .param("y", y)
                .update();
        // X: xa hơn một chút (~0,04), ở những chiều Y không dùng.
        for (int k = 0; k < 3; k++) {
            float[] v = new float[1024];
            v[0] = 1f;
            v[1010 + k] = 0.3f;
            store.addChunk(x, "X " + k, "đoạn của chính sự kiện", v);
        }
        float[] q = new float[1024];
        q[0] = 1f;

        // Tái hiện: ef_search mặc định, không iterative scan → index trả 40 ứng viên toàn Y, lọc xong
        // thì X không còn dòng nào.
        Long before = tx.execute(status -> {
            jdbc.sql("select set_config('enable_seqscan', 'off', true)")
                    .query(String.class)
                    .single();
            jdbc.sql("select set_config('hnsw.ef_search', '40', true)")
                    .query(String.class)
                    .single();
            return jdbc.sql(
                            """
                            select count(*) from (
                                select event_id from event_knowledge_embeddings
                                where event_id is null or event_id = :x
                                order by embedding <=> cast(:q as vector) limit 5) t
                            where event_id = :x
                            """)
                    .param("x", x)
                    .param("q", literal(q))
                    .query(Long.class)
                    .single();
        });
        assertThat(before).as("tái hiện R-29 trước khi nới ef_search").isLessThan(3);

        List<KnowledgeChunk> found = tx.execute(status -> {
            jdbc.sql("select set_config('enable_seqscan', 'off', true)")
                    .query(String.class)
                    .single();
            return store.searchSimilar(q, x, 5);
        });

        assertThat(found).filteredOn(chunk -> x.equals(chunk.eventId())).hasSize(3);
        assertThat(found).allSatisfy(chunk -> assertThat(chunk.eventId()).isIn(x, null));
    }

    private static String literal(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
    }
}
