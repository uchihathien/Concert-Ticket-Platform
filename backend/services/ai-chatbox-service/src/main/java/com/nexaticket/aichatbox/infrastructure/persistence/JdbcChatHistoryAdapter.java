// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.port.ChatHistoryPort;
import com.nexaticket.aichatbox.domain.port.SessionNotOwnedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lịch sử hội thoại trong {@code ai_chatbox_db}. */
@Repository
public class JdbcChatHistoryAdapter implements ChatHistoryPort {

    private final JdbcClient db;

    public JdbcChatHistoryAdapter(JdbcClient db) {
        this.db = db;
    }

    /**
     * <p><b>Kiểm chủ sở hữu trước, đọc sau.</b> Phiên chưa tồn tại là hợp lệ — đó là lượt đầu tiên
     * của một cuộc trò chuyện mới. Phiên tồn tại nhưng của người khác thì dừng: không kiểm thì bất
     * kỳ ai đoán ra một UUID cũng đọc được toàn bộ hội thoại hỗ trợ của người đó, trong đó có mã
     * đơn hàng và số tiền.
     *
     * <p>Truy vấn lấy N dòng <b>mới nhất</b> rồi đảo lại: {@code order by ... limit} không có cách
     * nào lấy N dòng cuối theo thứ tự tăng dần, và cắt ở đầu thì lịch sử mất phần gần nhất — đúng
     * phần mô hình cần nhất.
     */
    @Override
    public List<Exchange> recentTurns(UUID sessionId, UUID userId, int limit) {
        Optional<UUID> owner = db.sql("select user_id from chat_sessions where id = :id")
                .param("id", sessionId)
                .query((rs, rowNum) -> rs.getObject("user_id", UUID.class))
                .optional();
        if (owner.isPresent() && !owner.get().equals(userId)) {
            throw new SessionNotOwnedException(sessionId);
        }
        if (owner.isEmpty()) {
            return List.of();
        }

        List<Exchange> newestFirst = db.sql(
                        """
                        select role, content
                        from chat_messages
                        where session_id = :sessionId
                        order by created_at desc, id desc
                        limit :limit
                        """)
                .param("sessionId", sessionId)
                .param("limit", limit)
                .query((rs, rowNum) -> {
                    String content = rs.getString("content");
                    return ChatRole.valueOf(rs.getString("role")) == ChatRole.USER
                            ? (Exchange) new Exchange.UserSaid(content)
                            : new Exchange.AssistantSaid(content);
                })
                .list();

        List<Exchange> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    /**
     * <p>Tạo phiên nếu chưa có, trong cùng một lệnh. {@code on conflict do nothing} chứ không phải
     * "kiểm rồi chèn": hai request của cùng một người tới song song (bấm gửi hai lần) sẽ cùng thấy
     * phiên chưa tồn tại, và lệnh chèn thứ hai vi phạm khoá chính.
     */
    @Override
    public void append(UUID sessionId, UUID userId, ChatRole role, String text) {
        db.sql(
                        """
                        insert into chat_sessions (id, user_id) values (:id, :userId)
                        on conflict (id) do nothing
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .update();

        db.sql(
                        """
                        insert into chat_messages (session_id, role, content)
                        values (:sessionId, :role, :content)
                        """)
                .param("sessionId", sessionId)
                .param("role", role.name())
                .param("content", text)
                .update();
    }
}
