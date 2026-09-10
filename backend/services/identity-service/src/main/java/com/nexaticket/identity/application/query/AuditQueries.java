// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc nhật ký kiểm toán.
 *
 * <p>Bảng {@code audit_logs} được ghi từ ngày đầu nhưng <b>chưa từng có đường đọc</b>: mọi thao tác
 * quản trị đều để lại vết, và cách duy nhất để xem là mở database bằng tay. Một nhật ký không ai
 * đọc được thì không khác gì không có — nó chỉ tạo cảm giác đã kiểm soát.
 *
 * <p>Đọc thẳng bằng {@code JdbcTemplate}, không qua aggregate: đây là dữ liệu chỉ-ghi-thêm, không
 * có bất biến nào để giữ, và màn hình cần lọc cùng phân trang chứ không cần một cây đối tượng.
 *
 * <h2>Hai phạm vi, hai quyền</h2>
 *
 * <ul>
 *   <li>{@link #forOrganization} — tổ chức đọc vết của chính mình. Câu truy vấn lọc
 *       {@code organization_id} <b>ngay trong mệnh đề WHERE</b>, không phải bằng một câu if ở tầng
 *       trên: đó là cùng nguyên tắc mà repository của catalog và của venue đang theo.
 *   <li>{@link #forPlatform} — superadmin đọc xuyên tổ chức, gồm cả những dòng không gắn tổ chức
 *       nào (tạo tổ chức, vô hiệu hoá tài khoản).
 * </ul>
 */
@Service
public class AuditQueries {

    /** Trần cứng: một màn hình không hiển thị nổi hơn thế, và không có trần thì một tham số gõ nhầm kéo cả bảng về. */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Bộ lọc {@code action} viết bằng {@code CAST(? AS TEXT) IS NULL}, không phải {@code ? IS NULL}.
     *
     * <p>PostgreSQL suy kiểu tham số từ ngữ cảnh, và {@code ? IS NULL} không cho nó ngữ cảnh nào —
     * câu lệnh hỏng với {@code could not determine data type of parameter}. Ép kiểu tường minh là
     * cách diễn đạt "bộ lọc này có thể vắng mặt" mà không phải nối chuỗi SQL theo điều kiện, vốn là
     * con đường ngắn nhất tới SQL injection.
     */
    private static final String SELECT =
            """
            SELECT id, actor_user_id, organization_id, action, entity_type, entity_id,
                   before_state::text AS before_state, after_state::text AS after_state,
                   correlation_id, created_at
              FROM audit_logs
            """;

    private final RowMapper<AuditEntry> mapper = (rs, i) -> new AuditEntry(
            rs.getObject("id", UUID.class),
            rs.getObject("actor_user_id", UUID.class),
            rs.getObject("organization_id", UUID.class),
            rs.getString("action"),
            rs.getString("entity_type"),
            rs.getObject("entity_id", UUID.class),
            parse(rs.getString("before_state")),
            parse(rs.getString("after_state")),
            rs.getString("correlation_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditQueries(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> forOrganization(TenantId organizationId, String action, int limit, int offset) {
        TenantContext.requirePermission(Permission.ORG_AUDIT_READ, organizationId);
        return jdbc.query(
                SELECT
                        + """
                         WHERE organization_id = ?
                           AND (CAST(? AS TEXT) IS NULL OR action = ?)
                         ORDER BY created_at DESC
                         LIMIT ? OFFSET ?
                        """,
                mapper,
                organizationId.value(),
                action,
                action,
                clampLimit(limit),
                Math.max(offset, 0));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> forPlatform(String action, int limit, int offset) {
        TenantContext.requirePlatformPermission(Permission.PLATFORM_AUDIT_READ);
        return jdbc.query(
                SELECT
                        + """
                         WHERE (CAST(? AS TEXT) IS NULL OR action = ?)
                         ORDER BY created_at DESC
                         LIMIT ? OFFSET ?
                        """,
                mapper,
                action,
                action,
                clampLimit(limit),
                Math.max(offset, 0));
    }

    /**
     * JSONB đọc lên dưới dạng chuỗi rồi parse lại thành cây.
     *
     * <p>Trả chuỗi thô thì frontend nhận JSON lồng trong JSON và phải {@code JSON.parse} lần nữa —
     * một chi tiết nhỏ nhưng ai cũng quên đúng một lần.
     *
     * <p>Hỏng thì trả null chứ không ném: một dòng nhật ký có payload lạ không được phép làm trắng
     * cả màn hình nhật ký, vốn là thứ người ta mở ra đúng lúc đang đi tìm sự cố.
     */
    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static int clampLimit(int limit) {
        return limit <= 0 ? 50 : Math.min(limit, MAX_PAGE_SIZE);
    }

    /**
     * @param beforeState trạng thái trước. Kiểu {@code JsonNode} chứ không phải một record cụ thể:
     *     hình dạng của nó khác nhau theo từng loại hành động, và ép tất cả vào một kiểu chung sẽ
     *     mất chính những chi tiết mà người đọc nhật ký đang tìm.
     */
    public record AuditEntry(
            UUID id,
            UUID actorUserId,
            UUID organizationId,
            String action,
            String entityType,
            UUID entityId,
            JsonNode beforeState,
            JsonNode afterState,
            String correlationId,
            Instant createdAt) {}
}
