// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi vào nhật ký một việc xảy ra ở SERVICE KHÁC.
 *
 * <p>Nhật ký nằm ở identity vì nó gắn với người và tổ chức; nhưng có những việc cần để lại dấu vết
 * mà identity không tự thấy được — ví dụ ticketing vừa xuất danh sách người mua.
 *
 * <h3>Danh sách hành động được phép là đóng</h3>
 *
 * Đường này không đi qua {@code TenantContext}: người gọi tự khai ai làm và ở tổ chức nào. Nếu nó
 * nhận mọi tên hành động thì bất kỳ service nào (hoặc bất kỳ ai lọt vào mạng nội bộ) cũng dựng được
 * một dòng "MEMBER_REMOVED" giả. Nên chỉ những hành động liệt kê ở đây mới được ghi, và thêm một
 * hành động mới là một thay đổi có chủ đích ở chính file này.
 */
@Service
public class RecordExternalAuditHandler {

    static final Set<String> ALLOWED_ACTIONS = Set.of("BUYER_LIST_EXPORTED");

    private static final int MAX_DETAIL_ENTRIES = 20;

    private final ExternalAuditWriter writer;

    public RecordExternalAuditHandler(ExternalAuditWriter writer) {
        this.writer = writer;
    }

    public record Command(
            UUID actorUserId,
            UUID organizationId,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> details) {}

    @Transactional
    public void handle(Command command) {
        if (command.actorUserId() == null || command.organizationId() == null) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, "Thiếu người thực hiện hoặc tổ chức");
        }
        if (command.action() == null || !ALLOWED_ACTIONS.contains(command.action())) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, "Hành động không được phép ghi từ bên ngoài");
        }
        if (command.entityType() == null || command.entityType().isBlank()) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, "Thiếu loại đối tượng");
        }
        if (command.details() != null && command.details().size() > MAX_DETAIL_ENTRIES) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, "Chi tiết quá dài");
        }
        writer.recordFor(
                command.actorUserId(),
                command.organizationId(),
                command.action(),
                command.entityType(),
                command.entityId(),
                command.details());
    }
}
