// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.persistence;

import com.nexaticket.catalog.application.query.SessionDirectory;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSessionDirectoryReader implements SessionDirectory.Reader {

    /**
     * Trần cứng. Một tổ chức có hơn hai nghìn suất thì trang thống kê cần phân trang thật chứ không
     * phải một danh sách dài hơn; suất mới nhất được giữ lại.
     */
    private static final int MAX_SESSIONS = 2000;

    private final JdbcTemplate jdbc;

    public JdbcSessionDirectoryReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SessionDirectory.Entry> sessionsOf(UUID organizationId) {
        return jdbc.query(
                """
                SELECT s.id AS event_session_id, e.id AS event_id, e.title, e.status, s.starts_at, s.ends_at
                  FROM events e
                  JOIN event_sessions s ON s.event_id = e.id
                 WHERE e.organization_id = ?
                 ORDER BY s.starts_at DESC, s.id
                 LIMIT ?
                """,
                (rs, i) -> {
                    Timestamp endsAt = rs.getTimestamp("ends_at");
                    return new SessionDirectory.Entry(
                            rs.getObject("event_session_id", UUID.class),
                            rs.getObject("event_id", UUID.class),
                            rs.getString("title"),
                            rs.getString("status"),
                            rs.getTimestamp("starts_at").toInstant(),
                            endsAt == null ? null : endsAt.toInstant());
                },
                organizationId,
                MAX_SESSIONS);
    }
}
