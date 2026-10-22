// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Tài khoản nhân viên soát vé do hệ thống cấp (tên đăng nhập + PIN). Không lưu PIN. */
public interface StaffAccountRepository {

    /** Mã ngắn đã cấp cho tổ chức, nếu có. */
    Optional<String> prefixOf(TenantId organizationId);

    /**
     * Giữ một mã ngắn cho tổ chức.
     *
     * @return false nếu mã đã thuộc tổ chức khác — người gọi thử mã khác
     */
    boolean reservePrefix(TenantId organizationId, String prefix);

    /** Lấy số thứ tự kế tiếp của tổ chức và tăng bộ đếm, nguyên tử. */
    int takeNextNumber(TenantId organizationId);

    void create(StaffAccount account);

    Optional<StaffAccount> find(TenantId organizationId, UserId userId);

    Optional<StaffAccount> findByUsername(String username);

    List<StaffAccount> findByOrganization(TenantId organizationId);

    /** Mọi tài khoản còn ACTIVE — cho tác vụ tự hết hạn. */
    List<StaffAccount> findActive();

    void updateStatus(UserId userId, StaffAccountStatus status, Instant at);

    void updateAccessUntil(UserId userId, Instant accessUntil);

    void markPinIssued(UserId userId, Instant at);

    enum StaffAccountStatus {
        ACTIVE,
        LOCKED,
        EXPIRED
    }

    /**
     * @param username phần nhân viên gõ, không gồm miền email nội bộ
     * @param accessUntil mốc gia hạn tay; {@code null} = chỉ theo lịch suất
     */
    record StaffAccount(
            UserId userId,
            TenantId organizationId,
            String username,
            StaffAccountStatus status,
            Instant accessUntil,
            UserId createdBy,
            Instant createdAt,
            Instant pinIssuedAt,
            Instant statusChangedAt) {}
}
