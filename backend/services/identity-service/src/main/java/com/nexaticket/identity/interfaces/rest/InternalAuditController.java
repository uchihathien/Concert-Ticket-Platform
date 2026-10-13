// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.RecordExternalAuditHandler;
import com.nexaticket.platform.security.annotation.PublicEndpoint;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service: service khác nhờ identity ghi một dòng nhật ký.
 *
 * <p>Chỉ gọi được trong mạng nội bộ — api-gateway không có route nào cho {@code /internal/**}, và
 * {@code InternalApiFilter} đòi {@code X-Internal-Token} khi shared secret được cấu hình. Những
 * hành động được phép ghi là một danh sách đóng trong {@link RecordExternalAuditHandler}.
 */
@RestController
@RequestMapping("/internal/audit-logs")
public class InternalAuditController {

    private final RecordExternalAuditHandler record;

    public InternalAuditController(RecordExternalAuditHandler record) {
        this.record = record;
    }

    public record AuditRequest(
            UUID actorUserId,
            UUID organizationId,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> details) {}

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PublicEndpoint(reason = "Open Host Service, chỉ truy cập được trong mạng nội bộ")
    public void record(@RequestBody AuditRequest request) {
        record.handle(new RecordExternalAuditHandler.Command(
                request.actorUserId(),
                request.organizationId(),
                request.action(),
                request.entityType(),
                request.entityId(),
                request.details()));
    }
}
