// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.persistence;

import com.nexaticket.catalog.domain.model.Promotion;
import com.nexaticket.catalog.domain.port.PromotionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPromotionRepository implements PromotionRepository {

    private final JdbcTemplate jdbc;

    public JdbcPromotionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Promotion> findByCode(UUID organizationId, String code) {
        return jdbc
                .query(
                        """
                        SELECT id, organization_id, code, kind, value, max_discount_vnd,
                               starts_at, ends_at, usage_limit, used_count, active
                          FROM promotions WHERE organization_id = ? AND code = ?
                        """,
                        JdbcPromotionRepository::map,
                        organizationId,
                        code)
                .stream()
                .findFirst();
    }

    @Override
    public void save(Promotion promotion) {
        jdbc.update(
                """
                INSERT INTO promotions (id, organization_id, code, kind, value, max_discount_vnd,
                                        starts_at, ends_at, usage_limit, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                promotion.id(),
                promotion.organizationId(),
                promotion.code(),
                promotion.kind().name(),
                promotion.value(),
                promotion.maxDiscountVnd(),
                promotion.startsAt() == null ? null : Timestamp.from(promotion.startsAt()),
                promotion.endsAt() == null ? null : Timestamp.from(promotion.endsAt()),
                promotion.usageLimit(),
                promotion.active());
    }

    private static Promotion map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp startsAt = rs.getTimestamp("starts_at");
        Timestamp endsAt = rs.getTimestamp("ends_at");
        return new Promotion(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("code"),
                Promotion.Kind.valueOf(rs.getString("kind")),
                rs.getLong("value"),
                rs.getObject("max_discount_vnd", Long.class),
                startsAt == null ? null : startsAt.toInstant(),
                endsAt == null ? null : endsAt.toInstant(),
                rs.getObject("usage_limit", Integer.class),
                rs.getInt("used_count"),
                rs.getBoolean("active"));
    }
}
