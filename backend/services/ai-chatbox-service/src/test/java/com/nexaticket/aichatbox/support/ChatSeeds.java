// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.aichatbox.domain.model.ChatRole;
import java.util.UUID;

/**
 * Gieo hội thoại vào {@link InMemoryChatHistory} cho test lớp interfaces.
 *
 * <p>Nằm ở {@code support} vì ArchUnit cấm lớp interfaces chạm vào domain — kể cả trong test — và
 * {@link ChatRole} là kiểu của domain.
 */
public final class ChatSeeds {

    private ChatSeeds() {}

    public static void userSaid(InMemoryChatHistory history, UUID sessionId, UUID userId, String text) {
        history.append(sessionId, userId, ChatRole.USER, text);
    }

    public static void assistantSaid(InMemoryChatHistory history, UUID sessionId, UUID userId, String text) {
        history.append(sessionId, userId, ChatRole.ASSISTANT, text);
    }
}
