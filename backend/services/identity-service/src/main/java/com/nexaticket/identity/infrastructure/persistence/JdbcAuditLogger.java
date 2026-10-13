// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.application.command.AuditLogger;
import com.nexaticket.identity.application.command.ExternalAuditWriter;
import com.nexaticket.kernel.id.CorrelationContext;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Ghi audit trong cùng transaction với thay đổi, để không bao giờ có thay đổi thiếu vết. */
@Component
public class JdbcAuditLogger implements AuditLogger, ExternalAuditWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditLogger(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(
            String action, String entityType, UUID entityId, Map<String, Object> before, Map<String, Object> after) {
        TenantScope scope = TenantContext.current();
        jdbc.update(
                """
                INSERT INTO audit_logs (id, actor_user_id, organization_id, action, entity_type,
                                        entity_id, before_state, after_state, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """,
                UUID.randomUUID(),
                scope.userId() == null ? null : scope.userId().value(),
                scope.activeTenant() == null ? null : scope.activeTenant().value(),
                action,
                entityType,
                entityId,
                toJson(before),
                toJson(after),
                CorrelationContext.current());
    }

    @Override
    public void recordFor(
            UUID actorUserId,
            UUID organizationId,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> details) {
        jdbc.update(
                """
                INSERT INTO audit_logs (id, actor_user_id, organization_id, action, entity_type,
                                        entity_id, after_state, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                UUID.randomUUID(),
                actorUserId,
                organizationId,
                action,
                entityType,
                entityId,
                toJson(details),
                CorrelationContext.current());
    }

    private String toJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Không serialize được audit payload", e);
        }
    }
}
