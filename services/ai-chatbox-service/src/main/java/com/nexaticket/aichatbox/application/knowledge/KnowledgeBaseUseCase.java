// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.knowledge;

import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.domain.port.EmbeddingPort;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soạn kho tri thức mà trợ lý đọc.
 *
 * <h2>Vì sao đường này phải tồn tại</h2>
 *
 * <p>Cả cơ chế RAG của service dựa trên hai bảng, và trước khi có lớp này <b>không có một lệnh
 * INSERT nào</b> ghi vào chúng: không migration seed, không endpoint, không script. Hệ quả không
 * phải "kho tri thức còn ít" mà là một tính năng chạy đúng và vô dụng — {@code searchSimilar} luôn
 * trả rỗng, {@code getEventRules} luôn trả "chưa có quy định", và prompt hệ thống thì buộc trợ lý
 * "chỉ nói những gì có trong ngữ cảnh tham khảo hoặc kết quả tool". Còn lại đúng một việc nó làm
 * được: tra đơn hàng.
 *
 * <h2>Nhúng lúc GHI, không nhúng lúc đọc</h2>
 *
 * <p>Vector được sinh ở đây rồi lưu cùng đoạn văn. Đó là lý do {@link EmbeddingPort#embedDocument}
 * tồn tại và tách khỏi {@code embedQuery}: nhà cung cấp phân biệt hai loại đầu vào, và khai sai
 * không gây lỗi nào — chỉ làm chất lượng tìm kiếm tệ đi theo cách không truy nguyên được.
 *
 * <h2>Đổi mô hình nhúng là nhúng lại tất cả</h2>
 *
 * <p>Vector của hai mô hình nằm trong hai không gian khác nhau kể cả khi cùng số chiều. Trộn chúng
 * trong một bảng thì truy vấn vẫn chạy, vẫn trả top-k, và kết quả vô nghĩa. Ở đây chưa có đường
 * nhúng lại hàng loạt — đổi {@code AI_PROVIDER} hoặc đổi mô hình nhúng thì phải xoá và soạn lại,
 * và đó là việc còn thiếu đã biết, không phải chuyện bỏ qua được.
 */
@Service
public class KnowledgeBaseUseCase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseUseCase.class);

    private final VectorStorePort knowledge;
    private final EmbeddingPort embeddings;
    private final AgentProperties agent;

    public KnowledgeBaseUseCase(VectorStorePort knowledge, EmbeddingPort embeddings, AgentProperties agent) {
        this.knowledge = knowledge;
        this.embeddings = embeddings;
        this.agent = agent;
    }

    /**
     * Thêm một đoạn tri thức.
     *
     * <p>Nhúng cả tiêu đề cùng nội dung: tiêu đề thường mang đúng những từ khách dùng để hỏi ("chính
     * sách hoàn vé"), trong khi nội dung diễn đạt lại bằng câu đầy đủ. Bỏ tiêu đề ra ngoài là bỏ đi
     * phần dễ khớp nhất.
     *
     * @param eventId {@code null} cho tri thức chung của nền tảng
     */
    public UUID addChunk(UUID eventId, String title, String content) {
        // Nhúng NGOÀI transaction, và thứ tự này là bắt buộc.
        //
        // Không có LazyConnectionDataSourceProxy trong hệ thống, nên @Transactional lấy kết nối
        // Hikari NGAY khi mở transaction. Gói lời gọi nhúng vào trong đó nghĩa là giữ một trong
        // MƯỜI kết nối của service suốt 30 giây chờ mạng — mười lần bấm nút là pool cạn, và thứ
        // chết không chỉ là kho tri thức mà là mọi lượt chat của mọi khách, với một thông báo
        // ("Connection is not available") không hề nói ra rằng nguyên nhân nằm ở đây.
        // Và KHÔNG có @Transactional ở đây: phần ghi là đúng một câu INSERT, vốn đã nguyên tử tự
        // thân. Thêm transaction chỉ để bọc một câu lệnh là thêm đúng cái vòng giữ kết nối vừa nói.
        float[] embedding = embed(title + "\n\n" + content);
        UUID id = knowledge.addChunk(eventId, title, content, embedding);
        log.info("Thêm đoạn tri thức {} (sự kiện {}): {}", id, eventId, title);
        return id;
    }

    @Transactional
    public void deleteChunk(UUID id) {
        if (!knowledge.deleteChunk(id)) {
            throw new ApiException(AiChatboxErrorCode.KNOWLEDGE_CHUNK_NOT_FOUND, "Không tìm thấy đoạn tri thức");
        }
        log.info("Xoá đoạn tri thức {}", id);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeViews.ChunkRow> listChunks(UUID eventId, int limit, int offset) {
        return knowledge.listChunks(eventId, limit, offset).stream()
                .map(KnowledgeViews.ChunkRow::of)
                .toList();
    }

    /**
     * Thử một câu hỏi và xem trợ lý lấy ra được gì.
     *
     * <p>Đi qua <b>đúng</b> đường mà agent đi — cùng mô hình nhúng, cùng {@code topK}, cùng ngưỡng —
     * và trả về cả những đoạn bị ngưỡng loại, có gắn cờ. Không có nó thì người soạn không có cách
     * nào biết một đoạn vừa viết có lấy ra được không; họ chỉ biết qua câu trả lời của trợ lý với
     * khách thật, tức là biết muộn.
     */
    public List<KnowledgeViews.RetrievedRow> preview(UUID eventId, String question) {
        // Cùng lý do với addChunk: nhúng là lời gọi mạng, và nó không được đứng trong một
        // transaction đang giữ kết nối database.
        float[] embedding = embed(question);
        return knowledge.searchSimilar(embedding, eventId, agent.retrievalTopK()).stream()
                .map(chunk -> KnowledgeViews.RetrievedRow.of(chunk, agent.maxRetrievalDistance()))
                .toList();
    }

    @Transactional
    public KnowledgeViews.RulesRow upsertRules(UUID eventId, String eventTitle, String content, boolean published) {
        knowledge.upsertRules(eventId, eventTitle, content, published);
        log.info("Ghi quy định sự kiện {} (đã công bố: {})", eventId, published);
        return rules(eventId);
    }

    @Transactional(readOnly = true)
    public KnowledgeViews.RulesRow rules(UUID eventId) {
        return knowledge
                .findRulesForCurator(eventId)
                .map(KnowledgeViews.RulesRow::of)
                .orElseThrow(() -> new ApiException(
                        AiChatboxErrorCode.EVENT_RULES_NOT_FOUND, "Chưa có quy định nào cho sự kiện này"));
    }

    /**
     * Nhúng, và hỏng thì nói rõ là hỏng.
     *
     * <p>Ngược hẳn với đường đọc của agent, nơi mất embedding chỉ làm trợ lý kém thông tin nên nó ghi
     * log rồi đi tiếp. Ở đây im lặng đi tiếp nghĩa là lưu một đoạn không có vector — không lưu được,
     * hoặc tệ hơn, lưu được rồi không bao giờ tìm ra. Người soạn phải biết ngay lúc bấm Lưu.
     */
    private float[] embed(String text) {
        try {
            return embeddings.embedDocument(text);
        } catch (RuntimeException e) {
            log.error("Không nhúng được văn bản cho kho tri thức", e);
            throw new ApiException(
                    AiChatboxErrorCode.KNOWLEDGE_EMBEDDING_FAILED,
                    "Không nhúng được văn bản — kiểm tra mô hình nhúng (Ollama hoặc Voyage) có đang chạy không");
        }
    }
}
