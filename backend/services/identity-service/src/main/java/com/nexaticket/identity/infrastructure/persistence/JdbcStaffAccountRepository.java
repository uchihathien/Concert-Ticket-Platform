// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.port.StaffAccountRepository;
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
public class JdbcStaffAccountRepository implements StaffAccountRepository {

    private static final String SELECT =
            """
            SELECT user_id, organization_id, username, status, access_until, created_by, created_at,
                   pin_issued_at, status_changed_at
              FROM staff_accounts
            """;

    private final JdbcTemplate jdbc;

    public JdbcStaffAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> prefixOf(TenantId organizationId) {
        return jdbc
                .queryForList(
                        "SELECT prefix FROM staff_login_prefixes WHERE organization_id = ?",
                        String.class,
                        organizationId.value())
                .stream()
                .findFirst();
    }

    @Override
    public boolean reservePrefix(TenantId organizationId, String prefix) {
        // ON CONFLICT DO NOTHING trên CẢ hai khoá: tổ chức đã có mã (lần gọi trùng), hoặc mã đã của
        // tổ chức khác. Cả hai trả 0 dòng; người gọi đọc lại prefixOf để biết là trường hợp nào.
        return jdbc.update(
                        """
                        INSERT INTO staff_login_prefixes (organization_id, prefix)
                        VALUES (?, ?)
                        ON CONFLICT DO NOTHING
                        """,
                        organizationId.value(),
                        prefix)
                > 0;
    }

    @Override
    public int takeNextNumber(TenantId organizationId) {
        Integer taken = jdbc.queryForObject(
                """
                UPDATE staff_login_prefixes SET next_number = next_number + 1
                 WHERE organization_id = ?
                RETURNING next_number - 1
                """,
                Integer.class,
                organizationId.value());
        if (taken == null) {
            throw new IllegalStateException("Tổ chức " + organizationId.value() + " chưa có mã đăng nhập nhân viên");
        }
        return taken;
    }

    @Override
    public void create(StaffAccount account) {
        jdbc.update(
                """
                INSERT INTO staff_accounts (user_id, organization_id, username, status, access_until, created_by,
                                            created_at, pin_issued_at, status_changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                account.userId().value(),
                account.organizationId().value(),
                account.username(),
                account.status().name(),
                timestamp(account.accessUntil()),
                account.createdBy() == null ? null : account.createdBy().value(),
                Timestamp.from(account.createdAt()),
                Timestamp.from(account.pinIssuedAt()),
                Timestamp.from(account.statusChangedAt()));
    }

    @Override
    public Optional<StaffAccount> find(TenantId organizationId, UserId userId) {
        return jdbc
                .query(
                        SELECT + " WHERE organization_id = ? AND user_id = ?",
                        JdbcStaffAccountRepository::map,
                        organizationId.value(),
                        userId.value())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<StaffAccount> findByUsername(String username) {
        return jdbc.query(SELECT + " WHERE username = ?", JdbcStaffAccountRepository::map, username).stream()
                .findFirst();
    }

    @Override
    public List<StaffAccount> findByOrganization(TenantId organizationId) {
        return jdbc.query(
                SELECT + " WHERE organization_id = ? ORDER BY username",
                JdbcStaffAccountRepository::map,
                organizationId.value());
    }

    @Override
    public List<StaffAccount> findActive() {
        return jdbc.query(
                SELECT + " WHERE status = 'ACTIVE' ORDER BY organization_id", JdbcStaffAccountRepository::map);
    }

    @Override
    public void updateStatus(UserId userId, StaffAccountStatus status, Instant at) {
        jdbc.update(
                "UPDATE staff_accounts SET status = ?, status_changed_at = ? WHERE user_id = ?",
                status.name(),
                Timestamp.from(at),
                userId.value());
    }

    @Override
    public void updateAccessUntil(UserId userId, Instant accessUntil) {
        jdbc.update(
                "UPDATE staff_accounts SET access_until = ? WHERE user_id = ?", timestamp(accessUntil), userId.value());
    }

    @Override
    public void markPinIssued(UserId userId, Instant at) {
        jdbc.update(
                "UPDATE staff_accounts SET pin_issued_at = ? WHERE user_id = ?", Timestamp.from(at), userId.value());
    }

    private static StaffAccount map(ResultSet rs, int rowNum) throws SQLException {
        UUID createdBy = rs.getObject("created_by", UUID.class);
        return new StaffAccount(
                UserId.of(rs.getObject("user_id", UUID.class)),
                TenantId.of(rs.getObject("organization_id", UUID.class)),
                rs.getString("username"),
                StaffAccountStatus.valueOf(rs.getString("status")),
                instant(rs, "access_until"),
                createdBy == null ? null : UserId.of(createdBy),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("pin_issued_at").toInstant(),
                rs.getTimestamp("status_changed_at").toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
