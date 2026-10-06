// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.handoff;

import com.nexaticket.aichatbox.domain.model.ChatMessage;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.port.IdentityLookupPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTO của bàn hỗ trợ và của màn hình chat. */
public final class HandoffViews {

    private HandoffViews() {}

    /**
     * Một phiếu trong hàng đợi.
     *
     * @param intent khách cần gì — GENERAL · ORDER_STATUS · BOOKING · REFUND · INCIDENT · COMPLAINT
     *     · EVENT_INFO. Để màn hình lọc và tô màu; người trực chuyên một mảng chỉ nhìn mảng ấy.
     * @param details JSON có cấu trúc đi kèm (mã đơn, loại sự cố, kết quả xét chính sách…), hoặc
     *     {@code null}. Màn hình tự đọc; không có hình dạng cố định vì mỗi ý định mang dữ liệu khác.
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
            String intent,
            String reason,
            String details,
            String lastQuestion,
            /**
             * AI đang hỏi — không phải "khách hàng" chung chung.
             *
             * <p>Trước đây phiếu không mang gì về người hỏi, nên người trực mở hàng chờ ra thấy một
             * danh sách phiếu giống hệt nhau và không biết đang trả lời ai, cũng không có cách nào
             * liên hệ lại khi khách đóng tab. Phiếu bị bỏ dở sống 24 giờ; trong 24 giờ đó email là
             * đường duy nhất chạm tới khách.
             *
             * <p>Rỗng khi identity-service không trả lời — bàn hỗ trợ vẫn phải làm việc được, chỉ
             * thiếu một cái tên.
             */
            UUID customerId,
            String customerName,
            String customerEmail,
            UUID assignedAgentId,
            String assignedAgentName,
            Instant requestedAt,
            Instant assignedAt,
            Instant resolvedAt,
            long waitingSeconds) {

        /**
         * Không kèm tên người trực.
         *
         * <p>Dùng ở những chỗ cái tên không nói thêm gì: phiếu vừa mở thì chưa ai nhận, nên không có
         * tên nào để tra. Gọi identity-service để nhận về rỗng là một lời gọi mạng vô ích nằm ngay
         * trong đường đi của khách.
         */
        public static HandoffRow of(Handoff handoff, Instant now) {
            return of(handoff, now, null, null);
        }

        public static HandoffRow of(Handoff handoff, Instant now, String assignedAgentName) {
            return of(handoff, now, assignedAgentName, null);
        }

        public static HandoffRow of(
                Handoff handoff, Instant now, String assignedAgentName, IdentityLookupPort.Contact customer) {
            return new HandoffRow(
                    handoff.id(),
                    handoff.sessionId(),
                    handoff.status().name(),
                    handoff.trigger().name(),
                    handoff.intent().name(),
                    handoff.reason(),
                    handoff.details(),
                    handoff.lastQuestion(),
                    handoff.userId(),
                    customer == null ? null : customer.fullName(),
                    customer == null ? null : customer.email(),
                    handoff.assignedAgentId(),
                    assignedAgentName,
                    handoff.requestedAt(),
                    handoff.assignedAt(),
                    handoff.resolvedAt(),
                    // Thời gian CHỜ, nên nó dừng ở lúc được nhận. Phiếu nhận từ ba ngày trước mà vẫn
                    // hiện "chờ 72 giờ" là con số nói sai chuyện đã xảy ra — người đọc bảng lịch sử
                    // sẽ tưởng khách bị bỏ quên ba ngày.
                    java.time.Duration.between(
                                    handoff.requestedAt(), handoff.assignedAt() != null ? handoff.assignedAt() : now)
                            .toSeconds());
        }
    }

    /**
     * Hội thoại đầy đủ kèm phiếu — một request cho cả màn hình của người trực.
     *
     * <p>Cũng là thứ {@code claim} trả về: người trực vừa nhận phiếu cần đọc ngay toàn bộ những gì
     * khách, trợ lý và (nếu có) người trực trước đã nói, không phải nhận xong rồi hỏi thêm một lần.
     *
     * @param checklist việc người trực nên kiểm theo khung mẫu của loại sự cố; rỗng với phiếu
     *     không phải sự cố
     */
    public record HandoffThread(HandoffRow handoff, List<MessageRow> messages, List<String> checklist) {
        public HandoffThread {
            checklist = checklist == null ? List.of() : List.copyOf(checklist);
        }
    }

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
