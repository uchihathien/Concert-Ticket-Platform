// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import com.nexaticket.aichatbox.domain.model.ChatMessage;
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
import org.springframework.transaction.annotation.Transactional;

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
        Optional<UUID> owner = ownerOf(sessionId);
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
                    // AGENT gộp vào AssistantSaid: với mô hình thì lời của người trực cũng là
                    // "phía hỗ trợ đã nói", và tách ra sẽ thêm một vai mà prompt không định nghĩa.
                    // Màn hình thì ngược lại — xem `transcript`.
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
     * Cả hội thoại cho màn hình của khách, giữ nguyên vai.
     *
     * <p>Cùng phép kiểm chủ sở hữu với {@link #recentTurns}: đoán ra một UUID phiên không được
     * phép là đọc được toàn bộ hội thoại hỗ trợ của người khác, trong đó có mã đơn và số tiền.
     */
    @Override
    public List<ChatMessage> transcript(UUID sessionId, UUID userId, int limit) {
        Optional<UUID> owner = ownerOf(sessionId);
        if (owner.isPresent() && !owner.get().equals(userId)) {
            throw new SessionNotOwnedException(sessionId);
        }
        return owner.isEmpty() ? List.of() : readTranscript(sessionId, limit);
    }

    /** Không kiểm chủ sở hữu — cửa quyền {@code PLATFORM_SUPPORT_HANDLE} đứng trước lời gọi này. */
    @Override
    public List<ChatMessage> transcriptForSupport(UUID sessionId, int limit) {
        return readTranscript(sessionId, limit);
    }

    /** Cũ trước mới sau, cắt ở phần MỚI nhất — cùng lý do với {@code recentTurns}. */
    private List<ChatMessage> readTranscript(UUID sessionId, int limit) {
        List<ChatMessage> newestFirst = db.sql(
                        """
                        select role, content, created_at
                        from chat_messages
                        where session_id = :sessionId
                        order by created_at desc, id desc
                        limit :limit
                        """)
                .param("sessionId", sessionId)
                .param("limit", limit)
                .query((rs, rowNum) -> new ChatMessage(
                        ChatRole.valueOf(rs.getString("role")),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();

        List<ChatMessage> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    @Override
    public Optional<UUID> ownerOf(UUID sessionId) {
        return db.sql("select user_id from chat_sessions where id = :id")
                .param("id", sessionId)
                .query((rs, rowNum) -> rs.getObject("user_id", UUID.class))
                .optional();
    }

    /**
     * <p>Tạo phiên nếu chưa có, trong cùng một lệnh. {@code on conflict do nothing} chứ không phải
     * "kiểm rồi chèn": hai request của cùng một người tới song song (bấm gửi hai lần) sẽ cùng thấy
     * phiên chưa tồn tại, và lệnh chèn thứ hai vi phạm khoá chính.
     *
     * <p><b>{@code @Transactional} không phải để trang trí.</b> Đây là HAI lệnh, và lệnh đầu là thứ
     * duy nhất trong cả service tạo ra dòng {@code chat_sessions} — thứ mà {@code chat_handoffs}
     * trỏ tới bằng khoá ngoại. Hỏng giữa hai lệnh mà không có transaction thì để lại một phiên rỗng
     * không có tin nhắn nào.
     */
    @Override
    @Transactional
    public void append(UUID sessionId, UUID userId, ChatRole role, String text) {
        ensureSession(sessionId, userId);
        insertMessage(sessionId, role, text);
    }

    /** Cả lượt trong một transaction — xem {@link ChatHistoryPort#appendTurn}. */
    @Override
    @Transactional
    public void appendTurn(UUID sessionId, UUID userId, String userText, ChatRole replyRole, String replyText) {
        ensureSession(sessionId, userId);
        insertMessage(sessionId, ChatRole.USER, userText);
        insertMessage(sessionId, replyRole, replyText);
    }

    private void ensureSession(UUID sessionId, UUID userId) {
        db.sql(
                        """
                        insert into chat_sessions (id, user_id) values (:id, :userId)
                        on conflict (id) do nothing
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .update();
    }

    private void insertMessage(UUID sessionId, ChatRole role, String text) {
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
