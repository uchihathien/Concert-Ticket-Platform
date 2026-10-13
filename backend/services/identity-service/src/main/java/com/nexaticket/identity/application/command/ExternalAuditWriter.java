// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import java.util.Map;
import java.util.UUID;

/**
 * Ghi một dòng nhật ký với người thực hiện và tổ chức được nêu TƯỜNG MINH.
 *
 * <p>Khác {@link AuditLogger}, vốn lấy hai thứ đó từ {@code TenantContext} của request đang chạy.
 * Tách thành cổng riêng để không use case nào trong identity vô tình gọi nhầm bản "tự khai".
 */
public interface ExternalAuditWriter {

    void recordFor(
            UUID actorUserId,
            UUID organizationId,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> details);
}
