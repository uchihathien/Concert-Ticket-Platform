// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.EventRules;
import com.nexaticket.aichatbox.domain.model.KnowledgeChunk;
import com.nexaticket.aichatbox.domain.model.KnowledgeEntry;
import com.nexaticket.aichatbox.domain.model.RulesEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Kho tri thức của context này, nằm trong {@code ai_chatbox_db}. */
public interface VectorStorePort {

    /**
     * Tìm các đoạn gần câu hỏi nhất.
     *
     * <p>Luôn trả về đủ {@code topK} nếu kho có đủ dữ liệu, <b>kể cả khi chẳng đoạn nào liên
     * quan</b> — lọc theo ngưỡng khoảng cách là việc của người gọi.
     */
    List<KnowledgeChunk> searchSimilar(float[] queryEmbedding, int topK);

    /**
     * Tra quy định của một sự kiện. Tra chính xác theo id, không phải tìm ngữ nghĩa.
     *
     * <p>Chỉ trả bản <b>đã công bố</b>. Bản nháp là đường của người soạn — xem
     * {@link #findRulesForCurator}.
     */
    Optional<EventRules> findRules(UUID eventId);

    // --- Đường ghi: người soạn kho tri thức -------------------------------
    //
    // Không có phần này thì cả cơ chế RAG ở trên là một truy vấn trên bảng rỗng: `searchSimilar`
    // luôn trả danh sách rỗng, `findRules` luôn trả Optional.empty(), và trợ lý — vốn bị prompt hệ
    // thống buộc "chỉ nói những gì có trong ngữ cảnh tham khảo hoặc kết quả tool" — chỉ còn làm
    // được đúng một việc: tra đơn hàng. Đó là một tính năng chạy đúng và vô dụng.

    /**
     * Thêm một đoạn tri thức <b>đã nhúng</b>.
     *
     * <p>Nhúng ở tầng application chứ không ở đây: chọn nhúng bằng mô hình nào là việc của
     * {@link EmbeddingPort}, còn kho này chỉ biết lưu vector đúng số chiều mà cột đã khai.
     *
     * @param eventId {@code null} cho tri thức chung của nền tảng
     * @return id của đoạn vừa thêm
     */
    UUID addChunk(UUID eventId, String title, String content, float[] embedding);

    /** @return {@code false} nếu không có đoạn nào mang id ấy */
    boolean deleteChunk(UUID id);

    /**
     * Danh mục đoạn tri thức để rà soát, <b>không kèm vector</b>.
     *
     * @param eventId {@code null} lấy tất cả; khác {@code null} chỉ lấy đoạn của sự kiện đó
     */
    List<KnowledgeEntry> listChunks(UUID eventId, int limit, int offset);

    /** Ghi hoặc ghi đè quy định của một sự kiện. Ghi đè hoàn toàn, không trộn. */
    void upsertRules(UUID eventId, String eventTitle, String content, boolean published);

    /** Quy định kể cả bản nháp — đường của người soạn. */
    Optional<RulesEntry> findRulesForCurator(UUID eventId);
}
