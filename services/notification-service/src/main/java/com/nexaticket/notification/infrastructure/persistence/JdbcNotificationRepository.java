// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.notification.domain.model.EmailTemplate;
import com.nexaticket.notification.domain.model.Notification;
import com.nexaticket.notification.domain.model.NotificationStatus;
import com.nexaticket.notification.domain.port.NotificationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcNotificationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean queueIfNew(Notification notification) {
        // ON CONFLICT DO NOTHING trên event_id: RabbitMQ giao ít nhất một lần, nên message
        // trùng là chuyện thường xuyên chứ không phải hiếm.
        return jdbc.update(
                        """
                        INSERT INTO notifications (id, event_id, event_type, recipient, template,
                                                   payload, status, next_retry_at)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PENDING', now())
                        ON CONFLICT (event_id) DO NOTHING
                        """,
                        notification.id(),
                        notification.eventId(),
                        notification.eventType(),
                        notification.recipient(),
                        notification.template().name(),
                        write(notification.payload()))
                == 1;
    }

    @Override
    public List<Notification> claimDue(Instant now, int batchSize) {
        // SKIP LOCKED để nhiều instance gửi song song mà không gửi trùng một thư.
        return jdbc.query(
                """
                SELECT id, event_id, event_type, recipient, template, payload, status, attempts
                  FROM notifications
                 WHERE status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= ?)
                 ORDER BY next_retry_at
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """,
                this::map,
                Timestamp.from(now),
                batchSize);
    }

    @Override
    public void update(Notification notification) {
        jdbc.update(
                """
                UPDATE notifications
                   SET status = ?, attempts = ?, last_error = ?, next_retry_at = ?, sent_at = ?
                 WHERE id = ?
                """,
                notification.status().name(),
                notification.attempts(),
                notification.lastError(),
                notification.nextRetryAt() == null ? null : Timestamp.from(notification.nextRetryAt()),
                notification.sentAt() == null ? null : Timestamp.from(notification.sentAt()),
                notification.id());
    }

    @Override
    public int countByStatus(NotificationStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE status = ?", Integer.class, status.name());
        return count == null ? 0 : count;
    }

    private Notification map(ResultSet rs, int rowNum) throws SQLException {
        return Notification.rehydrate(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("recipient"),
                EmailTemplate.valueOf(rs.getString("template")),
                read(rs.getString("payload")),
                NotificationStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"));
    }

    private String write(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Không serialize được payload thư", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String raw) {
        try {
            return json.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
