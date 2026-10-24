// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.domain.model.BookingDraft;
import com.nexaticket.aichatbox.domain.port.BookingDraftRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bảng {@code chat_booking_drafts} (V0111). Chip lưu dạng JSONB — chúng chỉ được đọc lại nguyên khối. */
@Repository
public class JdbcBookingDraftRepository implements BookingDraftRepository {

    private static final TypeReference<List<BookingDraft.Choice>> CHOICES = new TypeReference<>() {};

    private final JdbcClient db;
    private final ObjectMapper json;

    public JdbcBookingDraftRepository(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public Optional<BookingDraft> find(UUID chatSessionId, UUID userId) {
        return db.sql(
                        """
                        select chat_session_id, user_id, event_slug, event_session_id, zone_code, quantity,
                               prompt, choices::text as choices, awaiting, updated_at
                        from chat_booking_drafts
                        where chat_session_id = :sessionId and user_id = :userId
                        """)
                .param("sessionId", chatSessionId)
                .param("userId", userId)
                .query(this::map)
                .optional();
    }

    @Override
    public void save(BookingDraft d) {
        db.sql(
                        """
                        insert into chat_booking_drafts
                            (chat_session_id, user_id, event_slug, event_session_id, zone_code, quantity,
                             prompt, choices, awaiting, updated_at)
                        values (:sessionId, :userId, :slug, :eventSessionId, :zoneCode, :quantity,
                                :prompt, cast(:choices as jsonb), :awaiting, :updatedAt)
                        on conflict (chat_session_id) do update set
                            event_slug = excluded.event_slug,
                            event_session_id = excluded.event_session_id,
                            zone_code = excluded.zone_code,
                            quantity = excluded.quantity,
                            prompt = excluded.prompt,
                            choices = excluded.choices,
                            awaiting = excluded.awaiting,
                            updated_at = excluded.updated_at
                        where chat_booking_drafts.user_id = excluded.user_id
                        """)
                .param("sessionId", d.chatSessionId())
                .param("userId", d.userId())
                .param("slug", d.eventSlug())
                .param("eventSessionId", d.eventSessionId())
                .param("zoneCode", d.zoneCode())
                .param("quantity", d.quantity())
                .param("prompt", d.prompt())
                .param("choices", write(d.choices()))
                .param("awaiting", d.awaiting().name())
                .param("updatedAt", Timestamp.from(d.updatedAt()))
                .update();
    }

    @Override
    public void delete(UUID chatSessionId, UUID userId) {
        db.sql("delete from chat_booking_drafts where chat_session_id = :sessionId and user_id = :userId")
                .param("sessionId", chatSessionId)
                .param("userId", userId)
                .update();
    }

    private BookingDraft map(ResultSet rs, int row) throws SQLException {
        return new BookingDraft(
                rs.getObject("chat_session_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("event_slug"),
                rs.getObject("event_session_id", UUID.class),
                rs.getString("zone_code"),
                rs.getObject("quantity", Integer.class),
                rs.getString("prompt"),
                read(rs.getString("choices")),
                BookingDraft.Step.valueOf(rs.getString("awaiting")),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String write(List<BookingDraft.Choice> choices) {
        try {
            return json.writeValueAsString(choices);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không tuần tự hoá được chip của bản nháp", e);
        }
    }

    private List<BookingDraft.Choice> read(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, CHOICES);
        } catch (JsonProcessingException e) {
            // Chip hỏng thì coi như không có chip: khách gõ lại là xong, không đáng một lỗi 500.
            return List.of();
        }
    }
}
