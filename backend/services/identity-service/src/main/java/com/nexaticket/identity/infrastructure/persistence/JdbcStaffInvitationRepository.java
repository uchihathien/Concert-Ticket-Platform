// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.port.StaffInvitationRepository;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStaffInvitationRepository implements StaffInvitationRepository {

    private static final String SELECT =
            """
            SELECT id, organization_id, user_id, email, full_name, invited_by, invited_at, last_sent_at,
                   expires_at, activated_at, delivery_status, delivery_error
              FROM staff_invitations
            """;

    private final JdbcTemplate jdbc;

    public JdbcStaffInvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(StaffInvitation invitation) {
        jdbc.update(
                """
                INSERT INTO staff_invitations (id, organization_id, user_id, email, full_name, invited_by,
                                               invited_at, last_sent_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                invitation.id(),
                invitation.organizationId().value(),
                invitation.userId().value(),
                invitation.email(),
                invitation.fullName(),
                invitation.invitedBy() == null ? null : invitation.invitedBy().value(),
                Timestamp.from(invitation.invitedAt()),
                Timestamp.from(invitation.lastSentAt()),
                Timestamp.from(invitation.expiresAt()));
    }

    @Override
    public Optional<StaffInvitation> find(TenantId organizationId, UserId userId) {
        return jdbc
                .query(
                        SELECT + " WHERE organization_id = ? AND user_id = ?",
                        JdbcStaffInvitationRepository::map,
                        organizationId.value(),
                        userId.value())
                .stream()
                .findFirst();
    }

    @Override
    public List<StaffInvitation> findByOrganization(TenantId organizationId) {
        return jdbc.query(
                SELECT + " WHERE organization_id = ? ORDER BY invited_at DESC",
                JdbcStaffInvitationRepository::map,
                organizationId.value());
    }

    @Override
    public void recordDelivery(UUID id, Instant sentAt, Instant expiresAt, String error) {
        jdbc.update(
                """
                UPDATE staff_invitations
                   SET last_sent_at = ?, expires_at = ?, delivery_status = ?, delivery_error = ?
                 WHERE id = ?
                """,
                Timestamp.from(sentAt),
                Timestamp.from(expiresAt),
                error == null ? "SENT" : "FAILED",
                error,
                id);
    }

    @Override
    public void markActivated(UUID id, Instant at) {
        // COALESCE: mốc kích hoạt là lần ĐẦU ghi nhận, không bị lần đọc sau đẩy lùi về sau.
        jdbc.update(
                "UPDATE staff_invitations SET activated_at = COALESCE(activated_at, ?) WHERE id = ?",
                Timestamp.from(at),
                id);
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM staff_invitations WHERE id = ?", id);
    }

    @Override
    public List<TenantId> organizationsOf(UserId userId) {
        return jdbc.query(
                "SELECT organization_id FROM organization_members WHERE user_id = ?",
                (rs, i) -> TenantId.of(rs.getObject(1, UUID.class)),
                userId.value());
    }

    private static StaffInvitation map(ResultSet rs, int rowNum) throws SQLException {
        UUID invitedBy = rs.getObject("invited_by", UUID.class);
        return new StaffInvitation(
                rs.getObject("id", UUID.class),
                TenantId.of(rs.getObject("organization_id", UUID.class)),
                UserId.of(rs.getObject("user_id", UUID.class)),
                rs.getString("email"),
                rs.getString("full_name"),
                invitedBy == null ? null : UserId.of(invitedBy),
                rs.getTimestamp("invited_at").toInstant(),
                rs.getTimestamp("last_sent_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                instant(rs, "activated_at"),
                "FAILED".equals(rs.getString("delivery_status"))
                        ? String.valueOf(rs.getString("delivery_error"))
                        : null);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
