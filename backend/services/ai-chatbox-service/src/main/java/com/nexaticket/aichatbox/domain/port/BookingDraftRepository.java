// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.BookingDraft;
import java.util.Optional;
import java.util.UUID;

/** Lựa chọn đặt vé đang dở, một bản cho một phiên chat (V0111). */
public interface BookingDraftRepository {

    /** Bản nháp của phiên, chỉ khi thuộc đúng người hỏi — không phân biệt "không có" với "của người khác". */
    Optional<BookingDraft> find(UUID chatSessionId, UUID userId);

    /** Ghi đè: một phiên chỉ có một bản nháp. */
    void save(BookingDraft draft);

    void delete(UUID chatSessionId, UUID userId);
}
