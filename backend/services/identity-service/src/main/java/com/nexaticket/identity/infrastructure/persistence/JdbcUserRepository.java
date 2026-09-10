// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.model.UserStatus;
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
            rs.getBoolean("is_super_admin"),
            UserStatus.valueOf(rs.getString("status")),
            // getTimestamp trả null cho cột NULL, và null ở đây có nghĩa: "chưa từng thu hồi".
            rs.getTimestamp("tokens_valid_from") == null
                    ? null
                    : rs.getTimestamp("tokens_valid_from").toInstant());

    private static final String SELECT =
            "SELECT id, idp_subject, email, full_name, phone, is_super_admin, status, tokens_valid_from FROM users ";

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
     *
     * <p><b>Nhánh cập nhật KHÔNG ghi đè {@code full_name}.</b> Chiều đúng là
     * {@code COALESCE(users.full_name, excluded.full_name)}: lấy tên từ IdP khi phía ta chưa có,
     * và giữ nguyên khi đã có. Viết ngược lại — như bản trước — thì mỗi lần gọi là một lần trả tên
     * về bản của Keycloak, và {@code PATCH /v1/me} trở thành nút "đổi tên trong vài giây".
     *
     * <p>Email thì ngược lại: Keycloak là nguồn chân lý, và email cũng là khoá khớp lời mời, nên
     * bản mới thắng — trừ khi nó rỗng, vì khi đó bản mới không mang thông tin gì.
     */
    @Override
    public UserRecord upsertByIdpSubject(String idpSubject, String email, String fullName) {
        jdbc.update(
                """
                INSERT INTO users (id, idp_subject, email, full_name, analytics_subject_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (idp_subject) DO UPDATE SET email = COALESCE(excluded.email, users.email),
                                                        full_name = COALESCE(users.full_name, excluded.full_name),
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
    public void setStatus(UserId id, UserStatus status) {
        jdbc.update("UPDATE users SET status = ?, updated_at = now() WHERE id = ?", status.name(), id.value());
    }

    /**
     * Chỉ đẩy mốc VỀ PHÍA TRƯỚC, không bao giờ lùi.
     *
     * <p>{@code GREATEST} chứ không gán thẳng: hai lần thu hồi đồng thời — một của superadmin, một
     * của quản trị viên tổ chức — mà lần ghi sau mang mốc cũ hơn thì nó sẽ <b>khôi phục</b> lại
     * những token vừa bị lần trước vô hiệu. Một lệnh thu hồi không được phép cấp lại quyền cho ai.
     */
    @Override
    public void revokeTokensIssuedBefore(UserId id, java.time.Instant cutoff) {
        jdbc.update(
                """
                UPDATE users
                   SET tokens_valid_from = GREATEST(COALESCE(tokens_valid_from, ?), ?),
                       updated_at = now()
                 WHERE id = ?
                """,
                java.sql.Timestamp.from(cutoff),
                java.sql.Timestamp.from(cutoff),
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
