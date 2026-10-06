// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.model;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate root: tổ chức và các thành viên của nó.
 *
 * <p>Bất biến: luôn còn ít nhất một {@code ORG_OWNER}; slug unique (ép bằng unique index).
 *
 * <p>Chỉ superadmin tạo được tổ chức (ADR-1010) — bất biến đó nằm ở tầng application vì nó là luật
 * uỷ quyền, không phải luật của aggregate.
 */
public final class Organization {

    private final TenantId id;
    private final Slug slug;
    private String name;
    private OrganizationStatus status;
    private final List<Membership> members;
    private final Instant createdAt;

    private Organization(
            TenantId id,
            Slug slug,
            String name,
            OrganizationStatus status,
            List<Membership> members,
            Instant createdAt) {
        this.id = id;
        this.slug = slug;
        this.name = name;
        this.status = status;
        this.members = new ArrayList<>(members);
        this.createdAt = createdAt;
    }

    public static Organization create(Slug slug, String name, Instant now) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên tổ chức không được rỗng");
        }
        return new Organization(
                TenantId.of(UUID.randomUUID()), slug, name.strip(), OrganizationStatus.ACTIVE, List.of(), now);
    }

    public static Organization rehydrate(
            TenantId id,
            Slug slug,
            String name,
            OrganizationStatus status,
            List<Membership> members,
            Instant createdAt) {
        return new Organization(id, slug, name, status, members, createdAt);
    }

    public Membership addMember(UserId userId, Role role, Instant now) {
        if (role == Role.SUPER_ADMIN || role == Role.CUSTOMER) {
            throw new IllegalArgumentException("Vai trò không thuộc phạm vi tổ chức: " + role);
        }
        if (findMember(userId).isPresent()) {
            throw new IllegalStateException("Người dùng đã là thành viên của tổ chức này");
        }
        Membership membership = new Membership(UUID.randomUUID(), userId, role, now);
        members.add(membership);
        return membership;
    }

    public void changeRole(UserId userId, Role newRole) {
        Membership current =
                findMember(userId).orElseThrow(() -> new IllegalStateException("Không phải thành viên của tổ chức"));
        if (current.role() == Role.ORG_OWNER && newRole != Role.ORG_OWNER && countOwners() == 1) {
            throw new IllegalStateException("Không thể hạ vai trò của chủ sở hữu cuối cùng");
        }
        members.set(members.indexOf(current), current.withRole(newRole));
    }

    public void removeMember(UserId userId) {
        Membership current =
                findMember(userId).orElseThrow(() -> new IllegalStateException("Không phải thành viên của tổ chức"));
        if (current.role() == Role.ORG_OWNER && countOwners() == 1) {
            throw new IllegalStateException("Không thể xoá chủ sở hữu cuối cùng");
        }
        members.remove(current);
    }

    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("Tên tổ chức không được rỗng");
        }
        this.name = newName.strip();
    }

    public void suspend() {
        this.status = OrganizationStatus.SUSPENDED;
    }

    public void activate() {
        this.status = OrganizationStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == OrganizationStatus.ACTIVE;
    }

    public Optional<Membership> findMember(UserId userId) {
        return members.stream().filter(m -> m.userId().equals(userId)).findFirst();
    }

    private long countOwners() {
        return members.stream().filter(m -> m.role() == Role.ORG_OWNER).count();
    }

    public TenantId id() {
        return id;
    }

    public Slug slug() {
        return slug;
    }

    public String name() {
        return name;
    }

    public OrganizationStatus status() {
        return status;
    }

    public List<Membership> members() {
        return Collections.unmodifiableList(members);
    }

    public Instant createdAt() {
        return createdAt;
    }
}
