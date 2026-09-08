// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.port.PurchaseLimitsRepository;
import com.nexaticket.kernel.id.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchaseLimitsRepository implements PurchaseLimitsRepository {

    private final JdbcTemplate jdbc;

    public JdbcPurchaseLimitsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Limits> find(TenantId organizationId) {
        return jdbc
                .query(
                        """
                        SELECT max_seated_per_hold, max_standing_per_hold,
                               max_units_per_hold, max_tickets_per_customer
                          FROM organization_purchase_limits WHERE organization_id = ?
                        """,
                        (rs, i) -> new Limits(
                                nullableInt(rs, "max_seated_per_hold"),
                                nullableInt(rs, "max_standing_per_hold"),
                                nullableInt(rs, "max_units_per_hold"),
                                nullableInt(rs, "max_tickets_per_customer")),
                        organizationId.value())
                .stream()
                .findFirst();
    }

    @Override
    public void save(TenantId organizationId, Limits limits) {
        jdbc.update(
                """
                INSERT INTO organization_purchase_limits (organization_id, max_seated_per_hold,
                        max_standing_per_hold, max_units_per_hold, max_tickets_per_customer, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (organization_id) DO UPDATE
                    SET max_seated_per_hold      = excluded.max_seated_per_hold,
                        max_standing_per_hold    = excluded.max_standing_per_hold,
                        max_units_per_hold       = excluded.max_units_per_hold,
                        max_tickets_per_customer = excluded.max_tickets_per_customer,
                        updated_at               = now()
                """,
                organizationId.value(),
                limits.maxSeatedPerHold(),
                limits.maxStandingPerHold(),
                limits.maxUnitsPerHold(),
                limits.maxTicketsPerCustomer());
    }

    /**
     * {@code getInt} trả 0 cho NULL, mà ở đây NULL và 0 có nghĩa hoàn toàn khác nhau: NULL là "kế
     * thừa trần nền tảng", còn 0 là "không mua được vé nào" — và cột này thậm chí không cho phép 0.
     */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
