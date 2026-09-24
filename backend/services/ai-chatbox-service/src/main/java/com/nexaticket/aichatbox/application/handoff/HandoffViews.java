// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.handoff;

import com.nexaticket.aichatbox.domain.model.ChatMessage;
import com.nexaticket.aichatbox.domain.model.Handoff;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTO của bàn hỗ trợ và của màn hình chat. */
public final class HandoffViews {

    private HandoffViews() {}

    /**
     * Một phiếu trong hàng đợi.
     *
     * @param lastQuestion câu khách đang hỏi, chụp lúc chuyển. Có mặt ở <b>danh sách</b> chứ không
     *     chỉ ở trang chi tiết: với hàng đợi 40 phiếu, bắt người trực mở từng cái để biết nội dung
     *     là bắt họ mở 40 lần.
     * @param waitingSeconds thời gian đã chờ. Tính ở backend chứ không để màn hình tự trừ: đồng hồ
     *     máy khách lệch vài phút là chuyện thường, và một phiếu chờ 12 phút hiện thành "chờ 3 phút"
     *     sẽ được xử lý sai thứ tự.
     */
    public record HandoffRow(
            UUID id,
            UUID sessionId,
            String status,
            String trigger,
            String reason,
            String lastQuestion,
            UUID assignedAgentId,
            Instant requestedAt,
            long waitingSeconds) {

        public static HandoffRow of(Handoff handoff, Instant now) {
            return new HandoffRow(
                    handoff.id(),
                    handoff.sessionId(),
                    handoff.status().name(),
                    handoff.trigger().name(),
                    handoff.reason(),
                    handoff.lastQuestion(),
                    handoff.assignedAgentId(),
                    handoff.requestedAt(),
                    java.time.Duration.between(handoff.requestedAt(), now).toSeconds());
        }
    }

    /** Hội thoại đầy đủ kèm phiếu đang mở — một request cho cả màn hình của người trực. */
    public record HandoffThread(HandoffRow handoff, List<MessageRow> messages) {}

    /**
     * @param role USER · ASSISTANT · AGENT. Ba vai, không phải hai: khách phải biết mình đang nói
     *     với máy hay với người, và giao diện chỉ phân biệt được nếu dữ liệu phân biệt.
     */
    public record MessageRow(String role, String content, Instant at) {

        public static MessageRow of(ChatMessage message) {
            return new MessageRow(message.role().name(), message.content(), message.at());
        }
    }

    /**
     * Màn hình chat của khách.
     *
     * @param handoff {@code null} nghĩa là trợ lý AI đang trả lời. Khác {@code null} thì giao diện
     *     phải đổi hẳn: ẩn gợi ý của bot, hiện "đang chờ nhân viên", và KHÔNG hứa trả lời tức thì.
     */
    public record ChatThread(UUID sessionId, HandoffRow handoff, List<MessageRow> messages) {}
}
