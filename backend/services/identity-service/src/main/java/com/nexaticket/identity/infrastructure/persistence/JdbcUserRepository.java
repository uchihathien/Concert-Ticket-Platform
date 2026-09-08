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
            rs.getString("phone"),
            rs.getBoolean("is_super_admin"));

    private static final String SELECT = "SELECT id, idp_subject, email, full_name, phone, is_super_admin FROM users ";

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

    /**
     * Chỉ sửa được tên và số điện thoại.
     *
     * <p>{@code COALESCE} chứ không phải gán thẳng: trong một lệnh PATCH, {@code null} nghĩa là
     * "không gửi trường này", không phải "xoá trường này". Gán thẳng sẽ khiến form chỉ đổi số điện
     * thoại lại xoá mất tên người dùng.
     */
    @Override
    public void updateProfile(UserId id, String fullName, String phone) {
        jdbc.update(
                """
                UPDATE users SET full_name = COALESCE(?, full_name),
                                 phone     = COALESCE(?, phone),
                                 updated_at = now()
                 WHERE id = ?
                """,
                fullName,
                phone,
                id.value());
    }

    @Override
    public java.util.List<UserRecord> findAllByIds(java.util.Collection<UserId> ids) {
        if (ids.isEmpty()) {
            return java.util.List.of();
        }
        // Một câu với mảng UUID thay vì N câu trong vòng lặp: bảng thành viên của một tổ chức lớn
        // sẽ là hàng chục truy vấn nối tiếp nếu làm cách kia.
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        Object[] args = ids.stream().map(UserId::value).toArray();
        return jdbc.query(SELECT + "WHERE id IN (" + placeholders + ")", MAPPER, args);
    }
}
