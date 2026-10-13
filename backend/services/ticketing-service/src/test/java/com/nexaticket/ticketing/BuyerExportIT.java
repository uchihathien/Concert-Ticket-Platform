// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.application.query.BuyerExportQuery;
import com.nexaticket.ticketing.application.query.GateStats;
import com.nexaticket.ticketing.application.query.GateStatsQuery;
import com.nexaticket.ticketing.domain.port.ExportAuditPort;
import com.nexaticket.ticketing.support.TicketingTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class BuyerExportIT extends TicketingTestBase {

    @Autowired
    private BuyerExportQuery export;

    @Autowired
    private GateStatsQuery stats;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ExportAuditPort audit;

    private final UUID org = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();

    @Test
    @DisplayName("Mỗi lượt xuất ghi một dòng nhật ký với đúng người thực hiện; vé đã thu hồi không có mặt")
    void xuat_co_ghi_nhat_ky() {
        ticket("Nguyễn Văn An", "VALID", 0);
        ticket("Trần Thị Bình", "CHECKED_IN", 1);
        ticket("Lê Văn Cường", "REVOKED", 0);

        GateStats.BuyerExport result = export.export(org, actor, List.of(session));

        assertThat(result.rows())
                .extracting(GateStats.BuyerExportRow::holderName)
                .containsExactly("Nguyễn Văn An", "Trần Thị Bình");
        verify(audit).buyerListExported(eq(org), eq(actor), any());
    }

    @Test
    @DisplayName("Không ghi được nhật ký thì không trả dữ liệu")
    void khong_ghi_duoc_nhat_ky_thi_khong_xuat() {
        ticket("Nguyễn Văn An", "VALID", 0);
        doThrow(new ExportAuditPort.AuditUnavailableException("identity im lặng", null))
                .when(audit)
                .buyerListExported(any(), any(), any());

        assertThatThrownBy(() -> export.export(org, actor, List.of(session))).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Tổ chức khác xuất ra danh sách rỗng, kể cả khi truyền đúng mã suất")
    void to_chuc_khac_khong_xuat_duoc() {
        ticket("Nguyễn Văn An", "VALID", 0);

        assertThat(export.export(UUID.randomUUID(), actor, List.of(session)).rows())
                .isEmpty();
    }

    @Test
    @DisplayName("Vắng mặt: vé còn hiệu lực chưa từng vào cửa, gom theo loại vé; không ghi nhật ký")
    void vang_mat() {
        ticket("Nguyễn Văn An", "VALID", 0);
        ticket("Trần Thị Bình", "CHECKED_IN", 1);
        ticket("Lê Văn Cường", "REVOKED", 0);

        assertThat(stats.noShows(org, List.of(session)).rows())
                .containsExactly(new GateStats.NoShowRow(session, "Thường", 1));
        assertThat(stats.noShows(UUID.randomUUID(), List.of(session)).rows()).isEmpty();
        verify(audit, never()).buyerListExported(any(), any(), any());
    }

    private void ticket(String holder, String status, int entries) {
        jdbc.update(
                """
                INSERT INTO tickets (id, order_id, order_item_id, event_session_id, organization_id, user_id,
                                     session_seat_id, seat_code, zone_code, admission_type, ticket_type_name,
                                     holder_name, status, entry_count, checked_in_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'A-1', 'A', 'STANDING', 'Thường', ?, ?, ?,
                        CASE WHEN ? = 'CHECKED_IN' THEN now() END, CASE WHEN ? = 'REVOKED' THEN now() END)
                """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                session,
                org,
                UUID.randomUUID(),
                UUID.randomUUID(),
                holder,
                status,
                entries,
                status,
                status);
    }
}
