// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import java.util.Map;
import java.util.UUID;

/**
 * Ghi audit cho thay đổi admin.
 *
 * <p>ADR-1010 hệ quả #3: tổ chức không tự kiểm chứng được số liệu, nên mọi thao tác của superadmin
 * lên tổ chức và lên tiền phải có vết đầy đủ.
 */
public interface AuditLogger {

    void record(String action, String entityType, UUID entityId, Map<String, Object> before, Map<String, Object> after);
}
