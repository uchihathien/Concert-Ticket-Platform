// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lời mời nhân viên soát vé: ai mời, lúc nào, thư đi chưa, đã kích hoạt chưa. */
public interface StaffInvitationRepository {

    void create(StaffInvitation invitation);

    Optional<StaffInvitation> find(TenantId organizationId, UserId userId);

    List<StaffInvitation> findByOrganization(TenantId organizationId);

    /** Ghi kết quả của một lần gửi thư: thành công thì gia hạn link, hỏng thì giữ lại lý do. */
    void recordDelivery(UUID id, Instant sentAt, Instant expiresAt, String error);

    void markActivated(UUID id, Instant at);

    void delete(UUID id);

    /** Những tổ chức mà một người đang là thành viên — để phân biệt "người của tổ chức khác". */
    List<TenantId> organizationsOf(UserId userId);

    /**
     * @param deliveryError {@code null} khi thư gần nhất đã gửi được
     * @param activatedAt {@code null} cho tới khi nhân viên đặt xong mật khẩu
     */
    record StaffInvitation(
            UUID id,
            TenantId organizationId,
            UserId userId,
            String email,
            String fullName,
            UserId invitedBy,
            Instant invitedAt,
            Instant lastSentAt,
            Instant expiresAt,
            Instant activatedAt,
            String deliveryError) {

        public boolean isActivated() {
            return activatedAt != null;
        }
    }
}
