// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.model;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Lời mời tham gia tổ chức.
 *
 * <p>Chỉ hash được lưu; token thô chỉ tồn tại trong email gửi đi. Dùng một lần, hết hạn 7 ngày.
 */
public final class Invitation {

    public static final Duration LIFETIME = Duration.ofDays(7);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UUID id;
    private final TenantId organizationId;
    private final String email;
    private final Role role;
    private final String tokenHash;
    private final Instant expiresAt;
    private Instant acceptedAt;

    private Invitation(
            UUID id,
            TenantId organizationId,
            String email,
            Role role,
            String tokenHash,
            Instant expiresAt,
            Instant acceptedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.acceptedAt = acceptedAt;
    }

    /** Kết quả tạo lời mời: token thô chỉ trả về đúng một lần, để gửi email. */
    public record Issued(Invitation invitation, String rawToken) {}

    public static Issued issue(TenantId organizationId, String email, Role role, Instant now) {
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("Email không hợp lệ: " + email);
        }
        if (role == Role.SUPER_ADMIN || role == Role.CUSTOMER) {
            throw new IllegalArgumentException("Vai trò không thuộc phạm vi tổ chức: " + role);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Invitation invitation = new Invitation(
                UUID.randomUUID(),
                organizationId,
                email.strip().toLowerCase(),
                role,
                hash(rawToken),
                now.plus(LIFETIME),
                null);
        return new Issued(invitation, rawToken);
    }

    public static Invitation rehydrate(
            UUID id,
            TenantId organizationId,
            String email,
            Role role,
            String tokenHash,
            Instant expiresAt,
            Instant acceptedAt) {
        return new Invitation(id, organizationId, email, role, tokenHash, expiresAt, acceptedAt);
    }

    public static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }

    public void accept(Instant now) {
        if (acceptedAt != null) {
            throw new IllegalStateException("Lời mời đã được sử dụng");
        }
        if (now.isAfter(expiresAt)) {
            throw new IllegalStateException("Lời mời đã hết hạn");
        }
        this.acceptedAt = now;
    }

    public UUID id() {
        return id;
    }

    public TenantId organizationId() {
        return organizationId;
    }

    public String email() {
        return email;
    }

    public Role role() {
        return role;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }
}
