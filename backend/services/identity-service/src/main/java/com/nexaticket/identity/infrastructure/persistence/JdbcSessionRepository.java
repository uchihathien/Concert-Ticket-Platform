// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.port.SessionRepository;
import com.nexaticket.kernel.id.UserId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Adapter persistence cho phiên bị thu hồi. */
@Repository
public class JdbcSessionRepository implements SessionRepository {

    private static final RowMapper<RevokedSession> MAPPER = (rs, i) -> new RevokedSession(
            rs.getString("sid"),
            UserId.of(rs.getObject("user_id", UUID.class)),
            rs.getObject("revoked_by", UUID.class) == null ? null : UserId.of(rs.getObject("revoked_by", UUID.class)),
            rs.getString("reason"),
            rs.getTimestamp("revoked_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Thu hồi lại một phiên đã thu hồi là chuyện bình thường, không phải lỗi.
     *
     * <p>Hai quản trị viên cùng bấm nút, hoặc một người bấm hai lần vì lần đầu chưa thấy phản hồi.
     * {@code ON CONFLICT DO UPDATE} giữ lý do mới nhất; ném lỗi ở đây sẽ khiến một thao tác an toàn
     * trông như hỏng.
     */
    @Override
    public void revoke(String sid, UserId userId, UserId revokedBy, String reason, Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO revoked_sessions (sid, user_id, revoked_by, reason, expires_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (sid) DO UPDATE SET reason     = EXCLUDED.reason,
                                                revoked_by = EXCLUDED.revoked_by,
                                                revoked_at = now(),
                                                expires_at = GREATEST(revoked_sessions.expires_at,
                                                                      EXCLUDED.expires_at)
                """,
                sid,
                userId.value(),
                revokedBy == null ? null : revokedBy.value(),
                reason,
                Timestamp.from(expiresAt));
    }

    /**
     * Không lọc theo {@code expires_at}.
     *
     * <p>Hàng quá hạn thì phiên tương ứng cũng đã chết ở Keycloak, nên trả lời "đã thu hồi" cho nó
     * là vô hại — còn thêm một điều kiện vào đường nóng thì không. Việc dọn là của
     * {@link #purgeExpired}.
     */
    @Override
    public boolean isRevoked(String sid) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM revoked_sessions WHERE sid = ?", Integer.class, sid);
        return count != null && count > 0;
    }

    @Override
    public List<RevokedSession> findByUser(UserId userId) {
        return jdbc.query(
                """
                SELECT sid, user_id, revoked_by, reason, revoked_at
                  FROM revoked_sessions
                 WHERE user_id = ?
                 ORDER BY revoked_at DESC
                """,
                MAPPER,
                userId.value());
    }

    @Override
    public int purgeExpired(Instant now) {
        return jdbc.update("DELETE FROM revoked_sessions WHERE expires_at < ?", Timestamp.from(now));
    }
}
