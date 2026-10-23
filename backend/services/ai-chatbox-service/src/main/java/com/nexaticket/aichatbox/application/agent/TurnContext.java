// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import java.util.Objects;
import java.util.UUID;

/**
 * Lượt chat đang chạy: phiên và người hỏi — thứ mô hình KHÔNG được điền và tool KHÔNG tự biết.
 *
 * <p>{@code ToolInvocation} chỉ có tên tool và tham số do mô hình điền; tham số {@code sessionId}
 * của tool đặt vé là <i>suất diễn</i>, không phải phiên chat. Đề xuất đặt vé phải mang
 * {@code userId} (kiểm chủ sở hữu lúc xác nhận) và {@code chatSessionId} (ghi lượt "đã tạo đơn"),
 * mà hai thứ ấy chỉ có ở use case ReAct. Truyền một record nhỏ cho mọi tool rẻ hơn biến
 * {@code proposeBooking} thành tool điều khiển trong use case.
 */
public record TurnContext(UUID sessionId, UUID userId) {

    public TurnContext {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId");
    }
}
