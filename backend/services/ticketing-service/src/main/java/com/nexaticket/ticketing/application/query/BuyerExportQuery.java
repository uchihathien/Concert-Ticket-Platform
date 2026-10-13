// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.query;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import com.nexaticket.ticketing.application.TicketingErrorCode;
import com.nexaticket.ticketing.domain.port.ExportAuditPort;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xuất danh sách người mua (tên đầy đủ của người giữ vé) cho ban tổ chức.
 *
 * <h3>Ghi nhật ký TRƯỚC khi trả dữ liệu, và không ghi được thì không trả</h3>
 *
 * Đây là đường duy nhất trong các trang thống kê đưa tên đầy đủ ra khỏi hệ thống. Thứ tự là: đọc →
 * ghi nhật ký → trả. Nếu identity im lặng thì lượt xuất thất bại (503) — "xuất được nhưng không ai
 * biết" tệ hơn "tạm thời không xuất được".
 *
 * <h3>Có trần</h3>
 *
 * Quá {@link #MAX_ROWS} vé thì từ chối thay vì âm thầm cắt: một danh sách khách thiếu người mà
 * trông như đủ là một danh sách sai ở cổng.
 */
@Service
public class BuyerExportQuery {

    static final int MAX_ROWS = 20_000;
    static final int MAX_SESSIONS = 200;

    private final GateStats.Reader reader;
    private final ExportAuditPort audit;
    private final Clock clock;

    public BuyerExportQuery(GateStats.Reader reader, ExportAuditPort audit, Clock clock) {
        this.reader = reader;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GateStats.BuyerExport export(UUID organizationId, UUID actorUserId, List<UUID> sessionIds) {
        List<UUID> sessions = sessionIds == null ? List.of() : List.copyOf(sessionIds);
        if (sessions.size() > MAX_SESSIONS) {
            throw new ApiException(
                    ErrorCode.Common.VALIDATION_FAILED, "Tối đa " + MAX_SESSIONS + " suất cho mỗi lần xuất");
        }
        // Hỏi thừa một dòng: đúng MAX_ROWS + 1 nghĩa là còn nữa.
        List<GateStats.BuyerExportRow> rows = reader.buyersForExport(organizationId, sessions, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new ApiException(
                    TicketingErrorCode.EXPORT_TOO_LARGE,
                    "Danh sách vượt " + MAX_ROWS + " vé. Hãy chọn một sự kiện hoặc một suất rồi xuất lại.");
        }

        Map<String, Object> details = new HashMap<>();
        details.put("rows", rows.size());
        details.put("sessions", sessions.isEmpty() ? "ALL" : sessions.size());
        details.put("fields", List.of("ticketCode", "holderName", "ticketType", "zone", "seat", "status"));
        try {
            audit.buyerListExported(organizationId, actorUserId, details);
        } catch (ExportAuditPort.AuditUnavailableException e) {
            throw new ApiException(
                    TicketingErrorCode.AUDIT_UNAVAILABLE,
                    "Chưa ghi được nhật ký nên chưa thể xuất danh sách. Thử lại sau ít phút.");
        }
        return new GateStats.BuyerExport(rows, clock.instant());
    }
}
