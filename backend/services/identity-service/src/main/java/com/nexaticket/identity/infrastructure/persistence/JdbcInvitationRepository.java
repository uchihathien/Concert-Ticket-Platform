// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvitationRepository implements InvitationRepository {

    private static final RowMapper<Invitation> MAPPER = (rs, i) -> Invitation.rehydrate(
            rs.getObject("id", UUID.class),
            TenantId.of(rs.getObject("organization_id", UUID.class)),
            rs.getString("email"),
            Role.valueOf(rs.getString("role")),
            rs.getString("token_hash"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("accepted_at") == null
                    ? null
                    : rs.getTimestamp("accepted_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcInvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Invitation> findByTokenHash(String tokenHash) {
        return jdbc
                .query(
                        """
                        SELECT id, organization_id, email, role, token_hash, expires_at, accepted_at
                          FROM invitations WHERE token_hash = ?
                        """,
                        MAPPER,
                        tokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public List<Invitation> findPending(TenantId organizationId) {
        return jdbc.query(
                """
                SELECT id, organization_id, email, role, token_hash, expires_at, accepted_at
                  FROM invitations
                 WHERE organization_id = ? AND accepted_at IS NULL
                 ORDER BY created_at DESC
                """,
                MAPPER,
                organizationId.value());
    }

    @Override
    public Optional<Invitation> findById(UUID id) {
        return jdbc
                .query(
                        """
                        SELECT id, organization_id, email, role, token_hash, expires_at, accepted_at
                          FROM invitations WHERE id = ?
                        """,
                        MAPPER,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM invitations WHERE id = ?", id);
    }

    @Override
    public void save(Invitation invitation) {
        jdbc.update(
                """
                INSERT INTO invitations (id, organization_id, email, role, token_hash, expires_at, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET accepted_at = excluded.accepted_at
                """,
                invitation.id(),
                invitation.organizationId().value(),
                invitation.email(),
                invitation.role().name(),
                invitation.tokenHash(),
                Timestamp.from(invitation.expiresAt()),
                invitation.acceptedAt() == null ? null : Timestamp.from(invitation.acceptedAt()));
    }
}
