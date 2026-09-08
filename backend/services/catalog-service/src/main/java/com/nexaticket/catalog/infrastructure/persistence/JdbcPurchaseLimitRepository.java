// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.persistence;

import com.nexaticket.catalog.domain.model.PurchaseLimits;
import com.nexaticket.catalog.domain.port.PurchaseLimitRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchaseLimitRepository implements PurchaseLimitRepository {

    private final JdbcTemplate jdbc;

    public JdbcPurchaseLimitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PurchaseLimits platformCeiling() {
        return jdbc.queryForObject(
                """
                SELECT max_seated_per_hold, max_standing_per_hold,
                       max_units_per_hold, max_tickets_per_customer
                  FROM platform_purchase_limits WHERE id = 1
                """,
                JdbcPurchaseLimitRepository::map);
    }

    @Override
    public void savePlatformCeiling(PurchaseLimits limits, UUID updatedBy) {
        jdbc.update(
                """
                UPDATE platform_purchase_limits
                   SET max_seated_per_hold = ?, max_standing_per_hold = ?,
                       max_units_per_hold = ?, max_tickets_per_customer = ?,
                       updated_at = now(), updated_by = ?
                 WHERE id = 1
                """,
                limits.maxSeatedPerHold(),
                limits.maxStandingPerHold(),
                limits.maxUnitsPerHold(),
                limits.maxTicketsPerCustomer(),
                updatedBy);
    }

    @Override
    public PurchaseLimits organizationDefaults(UUID organizationId) {
        return jdbc
                .query(
                        """
                        SELECT max_seated_per_hold, max_standing_per_hold,
                               max_units_per_hold, max_tickets_per_customer
                          FROM organization_purchase_limits WHERE organization_id = ?
                        """,
                        JdbcPurchaseLimitRepository::map,
                        organizationId)
                .stream()
                .findFirst()
                // Chưa cấu hình gì thì kế thừa toàn bộ từ nền tảng.
                .orElse(PurchaseLimits.INHERIT_ALL);
    }

    @Override
    public void saveOrganizationDefaults(UUID organizationId, PurchaseLimits limits) {
        jdbc.update(
                """
                INSERT INTO organization_purchase_limits (organization_id, max_seated_per_hold,
                                                          max_standing_per_hold, max_units_per_hold,
                                                          max_tickets_per_customer)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (organization_id) DO UPDATE
                   SET max_seated_per_hold = EXCLUDED.max_seated_per_hold,
                       max_standing_per_hold = EXCLUDED.max_standing_per_hold,
                       max_units_per_hold = EXCLUDED.max_units_per_hold,
                       max_tickets_per_customer = EXCLUDED.max_tickets_per_customer,
                       updated_at = now()
                """,
                organizationId,
                limits.maxSeatedPerHold(),
                limits.maxStandingPerHold(),
                limits.maxUnitsPerHold(),
                limits.maxTicketsPerCustomer());
    }

    private static PurchaseLimits map(ResultSet rs, int rowNum) throws SQLException {
        return new PurchaseLimits(
                rs.getObject("max_seated_per_hold", Integer.class),
                rs.getObject("max_standing_per_hold", Integer.class),
                rs.getObject("max_units_per_hold", Integer.class),
                rs.getObject("max_tickets_per_customer", Integer.class));
    }
}
