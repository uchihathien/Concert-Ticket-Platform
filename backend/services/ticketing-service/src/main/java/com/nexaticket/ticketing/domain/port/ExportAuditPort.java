// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import java.util.Map;
import java.util.UUID;

/** Để lại dấu vết trong nhật ký của tổ chức mỗi khi dữ liệu cá nhân rời hệ thống. */
public interface ExportAuditPort {

    /**
     * Ghi nhận một lần xuất danh sách người mua.
     *
     * @throws AuditUnavailableException khi không ghi được. Người gọi KHÔNG được trả dữ liệu trong
     *     trường hợp đó: một lần xuất không để lại dấu vết là đúng thứ nhật ký sinh ra để ngăn.
     */
    void buyerListExported(UUID organizationId, UUID actorUserId, Map<String, Object> details);

    class AuditUnavailableException extends RuntimeException {
        public AuditUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
