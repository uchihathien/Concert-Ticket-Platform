// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.aichatbox.domain.model.BookingDraft;
import com.nexaticket.aichatbox.domain.port.BookingDraftRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Bản nháp đặt vé trong bộ nhớ — cùng hợp đồng với {@code JdbcBookingDraftRepository}. */
public class InMemoryBookingDraftRepository implements BookingDraftRepository {

    private final Map<UUID, BookingDraft> drafts = new HashMap<>();

    @Override
    public synchronized Optional<BookingDraft> find(UUID chatSessionId, UUID userId) {
        return Optional.ofNullable(drafts.get(chatSessionId))
                .filter(d -> d.userId().equals(userId));
    }

    @Override
    public synchronized void save(BookingDraft draft) {
        BookingDraft existing = drafts.get(draft.chatSessionId());
        if (existing == null || existing.userId().equals(draft.userId())) {
            drafts.put(draft.chatSessionId(), draft);
        }
    }

    @Override
    public synchronized void delete(UUID chatSessionId, UUID userId) {
        BookingDraft existing = drafts.get(chatSessionId);
        if (existing != null && existing.userId().equals(userId)) {
            drafts.remove(chatSessionId);
        }
    }
}
