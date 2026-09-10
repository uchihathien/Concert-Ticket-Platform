// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Exchange;
import java.util.List;
import java.util.UUID;

/** Lịch sử hội thoại, lưu trong {@code ai_chatbox_db}. */
public interface ChatHistoryPort {

    /**
     * Vài lượt gần nhất, cũ trước mới sau.
     *
     * <p>Chỉ trả lời nói, <b>không</b> trả lại lưu lượng tool của các lượt trước. Kết quả tool là
     * ảnh chụp tại một thời điểm: một đơn "chờ thanh toán" từ mười phút trước có thể đã thanh toán
     * xong, và đưa nó trở lại prompt là dạy mô hình khẳng định một điều đã sai.
     *
     * @throws SessionNotOwnedException khi phiên thuộc về người khác
     */
    List<Exchange> recentTurns(UUID sessionId, UUID userId, int limit);

    /** Ghi một lượt. Tự tạo phiên nếu chưa có. */
    void append(UUID sessionId, UUID userId, ChatRole role, String text);
}
