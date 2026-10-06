// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.aichatbox.domain.model.ChatMessage;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.port.ChatHistoryPort;
import com.nexaticket.aichatbox.domain.port.SessionNotOwnedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lịch sử hội thoại trong bộ nhớ, cho unit test không có database.
 *
 * <p>Giữ đúng hai hợp đồng mà use case dựa vào: kiểm chủ sở hữu khi đọc, và gộp AGENT vào
 * {@code AssistantSaid} khi dựng prompt. Những gì cần database thật (khoá ngoại, unique index) thì
 * {@code HandoffFlowIT} kiểm.
 */
public class InMemoryChatHistory implements ChatHistoryPort {

    private final Map<UUID, UUID> owners = new LinkedHashMap<>();
    private final Map<UUID, List<ChatMessage>> messages = new LinkedHashMap<>();

    @Override
    public Optional<UUID> ownerOf(UUID sessionId) {
        return Optional.ofNullable(owners.get(sessionId));
    }

    @Override
    public List<Exchange> recentTurns(UUID sessionId, UUID userId, int limit) {
        return transcript(sessionId, userId, limit).stream()
                .map(m -> m.role() == ChatRole.USER
                        ? (Exchange) new Exchange.UserSaid(m.content())
                        : new Exchange.AssistantSaid(m.content()))
                .toList();
    }

    @Override
    public void append(UUID sessionId, UUID userId, ChatRole role, String text) {
        owners.putIfAbsent(sessionId, userId);
        messages.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(new ChatMessage(role, text, Instant.now()));
    }

    @Override
    public void appendTurn(UUID sessionId, UUID userId, String userText, ChatRole replyRole, String replyText) {
        append(sessionId, userId, ChatRole.USER, userText);
        append(sessionId, userId, replyRole, replyText);
    }

    @Override
    public List<ChatMessage> transcript(UUID sessionId, UUID userId, int limit) {
        UUID owner = owners.get(sessionId);
        if (owner != null && !owner.equals(userId)) {
            throw new SessionNotOwnedException(sessionId);
        }
        return transcriptForSupport(sessionId, limit);
    }

    @Override
    public List<ChatMessage> transcriptForSupport(UUID sessionId, int limit) {
        List<ChatMessage> all = messages.getOrDefault(sessionId, List.of());
        return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
    }
}
