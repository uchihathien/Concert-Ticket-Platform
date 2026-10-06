// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Một phiếu chuyển cuộc chat sang người thật.
 *
 * @param intent khách đang cần gì — nhãn để bàn hỗ trợ chia việc. Xem {@link SupportIntent}.
 * @param details dữ liệu có cấu trúc đi kèm phiếu, dạng JSON, do tầng application dựng: mã đơn,
 *     loại sự cố, lý do hoàn vé… {@code null} với phiếu không có gì ngoài câu hỏi. Giữ ở dạng chuỗi
 *     để domain không phải biết Jackson; người trực đọc nó qua màn hình, không phải qua mô hình.
 * @param lastQuestion câu hỏi đang treo, chụp lại lúc chuyển. Người trực cần biết ngay khách đang
 *     hỏi gì mà không phải mở cả hội thoại — với hàng đợi 40 phiếu thì đó là 40 lần mở.
 */
public record Handoff(
        UUID id,
        UUID sessionId,
        UUID userId,
        HandoffStatus status,
        HandoffTrigger trigger,
        SupportIntent intent,
        String reason,
        String details,
        String lastQuestion,
        UUID assignedAgentId,
        Instant requestedAt,
        Instant assignedAt,
        Instant resolvedAt) {

    public Handoff {
        // Phiếu cũ trong database không có cột này — đọc lên là GENERAL, không phải null.
        intent = intent == null ? SupportIntent.GENERAL : intent;
    }

    public static Handoff request(
            UUID sessionId,
            UUID userId,
            HandoffTrigger trigger,
            SupportIntent intent,
            String reason,
            String details,
            String lastQuestion,
            Instant now) {
        return new Handoff(
                UUID.randomUUID(),
                sessionId,
                userId,
                HandoffStatus.WAITING,
                trigger,
                intent,
                reason,
                details,
                lastQuestion,
                null,
                now,
                null,
                null);
    }

    /**
     * Một người trực nhận phiếu.
     *
     * <p>Chặn nhận lại một phiếu đã có người: hai người trực cùng trả lời một cuộc hội thoại là
     * thứ khách nhìn thấy, không phải một lỗi nội bộ âm thầm. Việc <b>tranh</b> phiếu thì được
     * chặn ở database bằng một lệnh UPDATE có điều kiện — xem {@code HandoffRepository.claim}.
     */
    public Handoff assignTo(UUID agentId, Instant now) {
        if (status != HandoffStatus.WAITING) {
            throw new IllegalStateException("Phiếu đang ở trạng thái " + status);
        }
        return new Handoff(
                id,
                sessionId,
                userId,
                HandoffStatus.ASSIGNED,
                trigger,
                intent,
                reason,
                details,
                lastQuestion,
                agentId,
                requestedAt,
                now,
                null);
    }

    /** Đóng phiếu. Trợ lý AI trả lời trở lại từ lượt kế tiếp. */
    public Handoff resolve(Instant now) {
        return new Handoff(
                id,
                sessionId,
                userId,
                HandoffStatus.RESOLVED,
                trigger,
                intent,
                reason,
                details,
                lastQuestion,
                assignedAgentId,
                requestedAt,
                assignedAt,
                now);
    }

    public boolean isOpen() {
        return status.isOpen();
    }
}
