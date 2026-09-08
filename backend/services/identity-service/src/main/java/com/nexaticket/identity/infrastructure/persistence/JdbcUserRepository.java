// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.id.UserId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository implements UserRepository {

    private static final RowMapper<UserRecord> MAPPER = (rs, i) -> new UserRecord(
            UserId.of(rs.getObject("id", UUID.class)),
            rs.getString("idp_subject"),
            rs.getString("email"),
            rs.getString("full_name"),
            rs.getBoolean("is_super_admin"));

    private static final String SELECT = "SELECT id, idp_subject, email, full_name, is_super_admin FROM users ";

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserRecord> findByIdpSubject(String idpSubject) {
        return jdbc.query(SELECT + "WHERE idp_subject = ?", MAPPER, idpSubject).stream()
                .findFirst();
    }

    @Override
    public Optional<UserRecord> findByEmail(String email) {
        return jdbc.query(SELECT + "WHERE email = ?", MAPPER, email.toLowerCase()).stream()
                .findFirst();
    }

    @Override
    public Optional<UserRecord> findById(UserId id) {
        return jdbc.query(SELECT + "WHERE id = ?", MAPPER, id.value()).stream().findFirst();
    }

    /**
     * Tạo bản ghi user ở lần request đầu tiên sau khi đăng nhập OIDC (auth-oidc.md bước 3).
     *
     * <p>{@code analytics_subject_id} sinh riêng và không bao giờ bằng {@code id} — analytics dùng nó
     * để không join được với PII (legal-constraints-vn.md §4).
     */
    @Override
    public UserRecord upsertByIdpSubject(String idpSubject, String email, String fullName) {
        jdbc.update(
                """
                INSERT INTO users (id, idp_subject, email, full_name, analytics_subject_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (idp_subject) DO UPDATE SET email = excluded.email,
                                                        full_name = COALESCE(excluded.full_name, users.full_name),
                                                        updated_at = now()
                """,
                UUID.randomUUID(),
                idpSubject,
                email == null ? null : email.toLowerCase(),
                fullName,
                UUID.randomUUID());
        return findByIdpSubject(idpSubject).orElseThrow();
    }
}
