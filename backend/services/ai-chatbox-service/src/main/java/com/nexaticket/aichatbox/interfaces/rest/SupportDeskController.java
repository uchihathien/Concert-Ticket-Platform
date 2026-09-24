// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffViews;
import com.nexaticket.aichatbox.application.handoff.SupportDeskAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bàn hỗ trợ: nơi người thật nhận và trả lời những cuộc chat mà trợ lý AI chuyển sang.
 *
 * <p>Mọi endpoint ở đây đòi {@code PLATFORM_SUPPORT_HANDLE}. Quyền phạm vi nền tảng, không phạm vi
 * tổ chức — xem {@code SupportDeskAccess}.
 *
 * <p><b>Đọc bằng cách hỏi lại, không bằng đẩy.</b> Realtime-gateway của hệ thống đang phục vụ
 * fan-out tồn kho lúc mở bán, nơi một giây chậm là bán trùng ghế. Bàn hỗ trợ không có ràng buộc
 * ấy: vài giây chậm là vài giây, và một hàng đợi vài chục phiếu thì hỏi lại mỗi 5 giây rẻ hơn hẳn
 * việc dựng thêm một kênh đẩy cùng với phần dò kết nối lại của nó. Đây là đánh đổi có chủ đích,
 * không phải bước còn thiếu.
 */
@RestController
@RequestMapping("/v1/support/handoffs")
public class SupportDeskController {

    /** Trần cứng của hàng đợi: không ai xử lý 500 phiếu một lúc, nhưng có người sẽ thử tải về. */
    private static final int MAX_QUEUE_SIZE = 100;

    /** Số tin nhắn tối đa trả về cho một hội thoại. Đủ dài cho một ca hỗ trợ, không tải cả lịch sử. */
    private static final int TRANSCRIPT_LIMIT = 200;

    private final HandoffUseCase handoffs;
    private final SupportDeskAccess access;

    public SupportDeskController(HandoffUseCase handoffs, SupportDeskAccess access) {
        this.handoffs = handoffs;
        this.access = access;
    }

    /**
     * Hàng đợi, cũ nhất trước.
     *
     * @param mine chỉ lấy phiếu của chính mình. Hai câu hỏi khác nhau trên cùng màn hình: "còn ai
     *     đang chờ" và "tôi đang cầm những cuộc nào".
     */
    @GetMapping
    public List<HandoffViews.HandoffRow> queue(
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        UUID agentId = access.requireSupportAgent();
        int limit = Math.clamp(size, 1, MAX_QUEUE_SIZE);

        return handoffs.queue(mine ? agentId : null, limit, Math.max(page, 0) * limit);
    }

    /** Cả hội thoại kèm phiếu — một request cho cả màn hình, không phải hai. */
    @GetMapping("/{handoffId}")
    public HandoffViews.HandoffThread thread(@PathVariable UUID handoffId) {
        access.requireSupportAgent();
        return handoffs.thread(handoffId, TRANSCRIPT_LIMIT);
    }

    /** Nhận phiếu. Người khác nhận trước thì 409 — xem {@code HandoffUseCase.claim}. */
    @PostMapping("/{handoffId}/claim")
    public HandoffViews.HandoffRow claim(@PathVariable UUID handoffId) {
        UUID agentId = access.requireSupportAgent();
        return handoffs.claim(handoffId, agentId);
    }

    /**
     * Trả lời khách.
     *
     * <p>Trả về cả hội thoại sau khi ghi, không trả 204: người trực vừa gõ xong cần thấy tin nhắn
     * của mình nằm đúng chỗ trong dòng thời gian — và nếu khách vừa nhắn thêm trong lúc đó thì
     * cùng một phản hồi mang luôn tin ấy về.
     */
    @PostMapping("/{handoffId}/messages")
    public HandoffViews.HandoffThread reply(@PathVariable UUID handoffId, @Valid @RequestBody ReplyRequest request) {

        UUID agentId = access.requireSupportAgent();
        handoffs.reply(handoffId, agentId, request.text());
        return thread(handoffId);
    }

    /** Đóng phiếu. Từ lượt kế tiếp trợ lý AI trả lời trở lại. */
    @PostMapping("/{handoffId}/resolve")
    public HandoffViews.HandoffRow resolve(@PathVariable UUID handoffId) {
        UUID agentId = access.requireSupportAgent();
        return handoffs.resolve(handoffId, agentId);
    }

    /**
     * @param text giới hạn độ dài là chốt chặn thực tế, không phải kỹ thuật: một tin nhắn hỗ trợ
     *     dài 4.000 ký tự là một tài liệu, và nó thuộc về email chứ không thuộc về khung chat
     */
    public record ReplyRequest(@NotBlank @Size(max = 4000) String text) {}
}
