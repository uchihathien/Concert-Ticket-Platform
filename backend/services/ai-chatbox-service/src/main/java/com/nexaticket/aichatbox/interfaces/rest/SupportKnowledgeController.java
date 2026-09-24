// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import com.nexaticket.aichatbox.application.handoff.SupportDeskAccess;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeBaseUseCase;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeViews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Soạn kho tri thức mà trợ lý đọc.
 *
 * <p><b>Đây là nửa còn thiếu của cơ chế RAG.</b> Hai bảng tri thức đã có từ migration đầu tiên,
 * truy vấn lấy ra cũng có, nhưng không có đường nào ghi vào — nên trợ lý chạy đúng mà không biết gì
 * ngoài đơn hàng. Xem {@code KnowledgeBaseUseCase}.
 *
 * <p><b>Quyền: dùng lại {@code PLATFORM_SUPPORT_HANDLE}, không thêm quyền mới.</b> Người soạn câu
 * trả lời cho trợ lý và người trực bàn hỗ trợ là cùng một nhóm việc: cả hai đều trả lời câu hỏi của
 * khách, chỉ khác là một bên trả lời trước và một bên trả lời sau. Tách ra thành quyền riêng chỉ có
 * nghĩa khi có hai nhóm người thật sự khác nhau — lúc đó thêm một quyền là một dòng ở {@code
 * Permission} và một dòng ở {@code Role}, không chỗ gọi nào phải sửa.
 *
 * <p>Nằm dưới {@code /v1/support/**} nên gateway đã route sẵn — không cần thêm route nào.
 */
@RestController
@RequestMapping("/v1/support/knowledge")
public class SupportKnowledgeController {

    /** Trần cứng một trang. Danh mục để rà soát, không phải để tải cả kho về. */
    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeBaseUseCase knowledge;
    private final SupportDeskAccess access;

    public SupportKnowledgeController(KnowledgeBaseUseCase knowledge, SupportDeskAccess access) {
        this.knowledge = knowledge;
        this.access = access;
    }

    /**
     * @param eventId {@code null} cho tri thức chung của nền tảng — chính sách hoàn vé, cách đặt
     *     chỗ, những thứ không thuộc sự kiện nào. Phần lớn kho tri thức nên nằm ở đây.
     * @param content giới hạn 8.000 ký tự là giới hạn của <b>chất lượng tìm kiếm</b>, không phải của
     *     cột: một đoạn quá dài cho ra một vector trung bình hoá và không còn gần với câu hỏi nào
     *     cụ thể. Tài liệu dài thì cắt thành nhiều đoạn, mỗi đoạn một ý.
     */
    public record ChunkRequest(
            UUID eventId, @NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 8000) String content) {}

    public record RulesRequest(
            @NotBlank @Size(max = 200) String eventTitle,
            @NotBlank @Size(max = 20000) String content,
            boolean published) {}

    @PostMapping("/chunks")
    public KnowledgeViews.ChunkRow addChunk(@Valid @RequestBody ChunkRequest request) {
        access.requireSupportAgent();
        UUID id = knowledge.addChunk(request.eventId(), request.title(), request.content());
        return new KnowledgeViews.ChunkRow(id, request.eventId(), request.title(), request.content(), null);
    }

    /**
     * @param eventId bỏ trống lấy cả kho; khác {@code null} chỉ lấy đoạn của sự kiện đó
     */
    @GetMapping("/chunks")
    public List<KnowledgeViews.ChunkRow> listChunks(
            @RequestParam(required = false) UUID eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        access.requireSupportAgent();
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return knowledge.listChunks(eventId, limit, Math.max(page, 0) * limit);
    }

    @DeleteMapping("/chunks/{id}")
    public void deleteChunk(@PathVariable UUID id) {
        access.requireSupportAgent();
        knowledge.deleteChunk(id);
    }

    /**
     * Thử một câu hỏi, xem trợ lý lấy ra được đoạn nào.
     *
     * <p>Không phải tiện ích gỡ lỗi mà là <b>đường phản hồi duy nhất</b> của người soạn: một kho
     * tri thức "trông đầy" nhưng mọi đoạn đều nằm trên ngưỡng khoảng cách thì trợ lý không đọc đoạn
     * nào, và cách phát hiện khác duy nhất là đọc câu trả lời tệ của nó với khách thật. Kết quả có
     * kèm cờ {@code used} nói rõ đoạn nào vượt được ngưỡng.
     */
    @GetMapping("/preview")
    public List<KnowledgeViews.RetrievedRow> preview(
            @RequestParam @NotBlank @Size(max = 2000) String q, @RequestParam(required = false) UUID eventId) {
        access.requireSupportAgent();
        return knowledge.preview(eventId, q);
    }

    /** Ghi đè trọn bản quy định của một sự kiện. {@code published=false} là bản nháp. */
    @PutMapping("/rules/{eventId}")
    public KnowledgeViews.RulesRow upsertRules(@PathVariable UUID eventId, @Valid @RequestBody RulesRequest request) {
        access.requireSupportAgent();
        return knowledge.upsertRules(eventId, request.eventTitle(), request.content(), request.published());
    }

    /** Kèm cả bản nháp — khác đường của tool {@code getEventRules}, vốn chỉ thấy bản đã công bố. */
    @GetMapping("/rules/{eventId}")
    public KnowledgeViews.RulesRow rules(@PathVariable UUID eventId) {
        access.requireSupportAgent();
        return knowledge.rules(eventId);
    }
}
