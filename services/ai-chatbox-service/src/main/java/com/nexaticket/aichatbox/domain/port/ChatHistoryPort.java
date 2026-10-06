// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.ChatMessage;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Exchange;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lịch sử hội thoại, lưu trong {@code ai_chatbox_db}. */
public interface ChatHistoryPort {

    /**
     * Chủ của một phiên; rỗng nghĩa là phiên chưa tồn tại.
     *
     * <p>Có mặt ở port chứ không chỉ là chi tiết bên trong adapter vì <b>đường mở phiếu cũng cần
     * nó</b>: {@code sessionId} do client gửi lên, và một đường ghi không kiểm chủ sở hữu thì ai
     * đoán ra một UUID cũng mở được phiếu trên hội thoại của người khác. Hai đường đọc đã kiểm
     * điều này từ đầu; đường ghi thì chưa, và đó là một lỗ hổng thật chứ không phải chuyện đối
     * xứng cho đẹp.
     */
    Optional<UUID> ownerOf(UUID sessionId);

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

    /**
     * Ghi <b>trọn một lượt</b>: câu của khách và câu trả lời, cùng nhau hoặc không gì cả.
     *
     * <p>Tách khỏi hai lần gọi {@link #append} vì hai lệnh riêng là hai transaction riêng. Hỏng ở
     * giữa để lại một câu hỏi không có câu trả lời, và lượt sau mô hình đọc lại hội thoại ấy như
     * thể khách đã bị bỏ qua — rồi xin lỗi về một việc chưa từng xảy ra.
     */
    void appendTurn(UUID sessionId, UUID userId, String userText, ChatRole replyRole, String replyText);

    /**
     * Cả hội thoại như màn hình đọc nó — giữ nguyên vai và mốc thời gian.
     *
     * <p>Tách khỏi {@link #recentTurns} chứ không thêm tham số: hai đường này trả lời hai câu hỏi
     * khác nhau và sẽ tách xa nhau. Đường prompt cố ý bỏ lưu lượng tool và gộp AGENT với ASSISTANT;
     * đường màn hình thì phải giữ cả hai thứ đó. Một hàm làm cả hai việc sẽ có một tham số boolean
     * mà không ai nhớ nghĩa.
     *
     * @throws SessionNotOwnedException khi phiên thuộc về người khác
     */
    List<ChatMessage> transcript(UUID sessionId, UUID userId, int limit);

    /**
     * Cả hội thoại, <b>không</b> kiểm chủ sở hữu — dành cho người trực bàn hỗ trợ.
     *
     * <p>Tách thành một phương thức riêng thay vì thêm một cờ "bỏ qua kiểm quyền": một tham số như
     * thế sẽ bị truyền {@code true} ở chỗ không nên, và không có gì trong tên hàm cảnh báo. Ở đây
     * tên hàm tự nói ra điều đó, và cửa quyền {@code PLATFORM_SUPPORT_HANDLE} đứng ngay trước nó.
     */
    List<ChatMessage> transcriptForSupport(UUID sessionId, int limit);
}
