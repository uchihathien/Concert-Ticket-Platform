// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lựa chọn đặt vé đang dở trong một phiên chat — khách đã chọn tới đâu, và chip nào đang hiện.
 *
 * <p>Không phải đề xuất: chưa đủ dữ liệu để tính tiền, chưa có hạn giữ chỗ. Đủ sự kiện, suất, khu
 * và số vé thì bản nháp được thay bằng một {@link BookingProposal} và bị xoá.
 *
 * @param choices chip của bước {@code awaiting} — mỗi chip mang sẵn giá trị có cấu trúc mà nó đặt
 *     vào bản nháp, nên khách bấm thì không phải đoán ngược từ chữ
 */
public record BookingDraft(
        UUID chatSessionId,
        UUID userId,
        String eventSlug,
        UUID eventSessionId,
        String zoneCode,
        Integer quantity,
        String prompt,
        List<Choice> choices,
        Step awaiting,
        Instant updatedAt) {

    /** Bản nháp không ai chạm tới quá lâu thì bỏ: khách đã sang chuyện khác. */
    public static final Duration TTL = Duration.ofMinutes(30);

    /** Bước đang chờ khách — khớp CHECK {@code ck_draft_awaiting} của V0111. */
    public enum Step {
        EVENT,
        SESSION,
        ZONE,
        QUANTITY
    }

    /**
     * Một chip. {@code value} là câu gửi lại khi bấm; {@code hint} là dòng phụ ("còn 12 chỗ"); các
     * trường còn lại là thứ chip đặt vào bản nháp ({@code null} = không đổi).
     */
    public record Choice(
            String label,
            String value,
            String hint,
            String eventSlug,
            UUID eventSessionId,
            String zoneCode,
            Integer quantity) {

        public Choice {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
        }
    }

    public BookingDraft {
        Objects.requireNonNull(chatSessionId, "chatSessionId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(awaiting, "awaiting");
        Objects.requireNonNull(updatedAt, "updatedAt");
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public boolean isFresh(Instant now) {
        return updatedAt.plus(TTL).isAfter(now);
    }

    /**
     * Chip khớp với câu khách vừa gửi — so theo nhãn hoặc câu gửi lại, không phân biệt hoa thường và
     * khoảng trắng thừa. Khớp chính xác, không khớp mờ: "khu VIP 2" không được rơi vào chip "Khu VIP".
     */
    public Optional<Choice> match(String message) {
        String said = normalize(message);
        return choices.stream()
                .filter(c -> normalize(c.value()).equals(said)
                        || normalize(c.label()).equals(said))
                .findFirst();
    }

    /**
     * Bản nháp sau khi khách chọn {@code choice}: điền các trường chip mang theo, giữ phần còn lại.
     *
     * <p>Đổi sự kiện thì suất và khu đã chọn không còn nghĩa; đổi suất thì khu đã chọn cũng vậy —
     * mỗi suất có bộ hạng vé riêng. Số vé thì giữ: "2 vé" vẫn là 2 vé ở suất khác.
     */
    public BookingDraft apply(Choice choice, Instant now) {
        String slug = eventSlug;
        UUID session = eventSessionId;
        String zone = zoneCode;
        Integer qty = quantity;
        if (choice.eventSlug() != null && !choice.eventSlug().equals(slug)) {
            slug = choice.eventSlug();
            session = null;
            zone = null;
        }
        if (choice.eventSessionId() != null && !choice.eventSessionId().equals(session)) {
            session = choice.eventSessionId();
            zone = null;
        }
        if (choice.zoneCode() != null) {
            zone = choice.zoneCode();
        }
        if (choice.quantity() != null) {
            qty = choice.quantity();
        }
        return new BookingDraft(chatSessionId, userId, slug, session, zone, qty, prompt, choices, awaiting, now);
    }

    /** Bản nháp với bước kế tiếp và chip mới. */
    public BookingDraft asking(Step step, String newPrompt, List<Choice> newChoices, Instant now) {
        return new BookingDraft(
                chatSessionId, userId, eventSlug, eventSessionId, zoneCode, quantity, newPrompt, newChoices, step, now);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
