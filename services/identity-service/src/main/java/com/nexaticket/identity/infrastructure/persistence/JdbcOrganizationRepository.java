// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.OrganizationStatus;
import com.nexaticket.identity.domain.model.Slug;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Adapter persistence.
 *
 * <p>Dùng {@code JdbcTemplate} thay vì JPA có chủ đích: identity là generic subdomain với logic mỏng,
 * và cách này giữ domain hoàn toàn thuần mà không phải viết lớp entity + mapper song song
 * (tactical-ddd.md §1 — đánh đổi có ý thức, chỉ áp dụng ngoài hai core subdomain).
 */
@Repository
public class JdbcOrganizationRepository implements OrganizationRepository {

    private final JdbcTemplate jdbc;

    public JdbcOrganizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Organization> findById(TenantId id) {
        List<Organization> found = jdbc.query(
                """
                SELECT id, slug, name, status, created_at FROM organizations WHERE id = ?
                """,
                (rs, i) -> hydrate(
                        TenantId.of(rs.getObject("id", UUID.class)),
                        new Slug(rs.getString("slug")),
                        rs.getString("name"),
                        OrganizationStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant()),
                id.value());
        return found.stream().findFirst();
    }

    @Override
    public boolean slugExists(Slug slug) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM organizations WHERE slug = ?", Integer.class, slug.value());
        return count != null && count > 0;
    }

    @Override
    public List<Organization> findAllByMember(UserId userId) {
        return jdbc.query(
                """
                SELECT o.id, o.slug, o.name, o.status, o.created_at
                  FROM organizations o
                  JOIN organization_members m ON m.organization_id = o.id
                 WHERE m.user_id = ?
                 ORDER BY o.name
                """,
                (rs, i) -> hydrate(
                        TenantId.of(rs.getObject("id", UUID.class)),
                        new Slug(rs.getString("slug")),
                        rs.getString("name"),
                        OrganizationStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant()),
                userId.value());
    }

    @Override
    public List<Organization> findAll(int limit, int offset) {
        return jdbc.query(
                """
                SELECT id, slug, name, status, created_at
                  FROM organizations ORDER BY created_at DESC LIMIT ? OFFSET ?
                """,
                (rs, i) -> hydrate(
                        TenantId.of(rs.getObject("id", UUID.class)),
                        new Slug(rs.getString("slug")),
                        rs.getString("name"),
                        OrganizationStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant()),
                limit,
                offset);
    }

    @Override
    public void save(Organization organization) {
        jdbc.update(
                """
                INSERT INTO organizations (id, slug, name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET name = excluded.name,
                                               status = excluded.status,
                                               updated_at = now()
                """,
                organization.id().value(),
                organization.slug().value(),
                organization.name(),
                organization.status().name(),
                Timestamp.from(organization.createdAt()));

        // Đồng bộ membership: aggregate là nguồn chân lý cho tập thành viên.
        List<UUID> keep = organization.members().stream().map(Membership::id).toList();
        if (keep.isEmpty()) {
            jdbc.update(
                    "DELETE FROM organization_members WHERE organization_id = ?",
                    organization.id().value());
        } else {
            String placeholders = String.join(",", keep.stream().map(x -> "?").toList());
            List<Object> args = new ArrayList<>();
            args.add(organization.id().value());
            args.addAll(keep);
            jdbc.update(
                    "DELETE FROM organization_members WHERE organization_id = ? AND id NOT IN (" + placeholders + ")",
                    args.toArray());
        }
        for (Membership member : organization.members()) {
            jdbc.update(
                    """
                    INSERT INTO organization_members (id, organization_id, user_id, role, joined_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET role = excluded.role
                    """,
                    member.id(),
                    organization.id().value(),
                    member.userId().value(),
                    member.role().name(),
                    Timestamp.from(member.joinedAt()));
        }
    }

    private Organization hydrate(
            TenantId id, Slug slug, String name, OrganizationStatus status, java.time.Instant createdAt) {
        List<Membership> members = jdbc.query(
                """
                SELECT id, user_id, role, joined_at FROM organization_members WHERE organization_id = ?
                """,
                (rs, i) -> new Membership(
                        rs.getObject("id", UUID.class),
                        UserId.of(rs.getObject("user_id", UUID.class)),
                        Role.valueOf(rs.getString("role")),
                        rs.getTimestamp("joined_at").toInstant()),
                id.value());
        return Organization.rehydrate(id, slug, name, status, members, createdAt);
    }
}
