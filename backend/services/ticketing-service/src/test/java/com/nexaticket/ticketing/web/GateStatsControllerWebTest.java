// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.ticketing.application.query.BuyerExportQuery;
import com.nexaticket.ticketing.application.query.GateStats;
import com.nexaticket.ticketing.application.query.GateStatsQuery;
import com.nexaticket.ticketing.domain.port.ExportAuditPort;
import com.nexaticket.ticketing.interfaces.rest.OrganizationGateStatsController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Thống kê soát vé và xuất danh sách người mua qua HTTP — query thật, chỉ phần đọc database là giả.
 *
 * <p>Hai mức quyền trên cùng một controller: {@code CATALOG_MANAGE} để xem số liệu,
 * {@code ORG_MEMBERS_MANAGE} để mang tên đầy đủ của khách ra ngoài.
 */
@WebMvcTest(controllers = OrganizationGateStatsController.class)
@Import({GateStatsQuery.class, BuyerExportQuery.class})
class GateStatsControllerWebTest extends WebSliceTest {

    private static final UUID ORG = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final String BASE = "/v1/organizations/" + ORG + "/stats/checkins";

    @MockitoBean
    GateStats.Reader reader;

    @MockitoBean
    ExportAuditPort audit;

    @Nested
    @DisplayName("Quyền xem số liệu")
    class QuyenXem {

        @Test
        @DisplayName("Người quản lý sự kiện xem được số khách đang ở trong")
        void quan_ly_xem_duoc() throws Exception {
            UUID session = UUID.randomUUID();
            when(reader.presenceBySession(ORG))
                    .thenReturn(List.of(new GateStats.SessionPresence(session, 100, 40, 35, 52)));

            mvc.perform(get(BASE + "/presence").with(member(USER, ORG, Role.EVENT_MANAGER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions[0].eventSessionId").value(session.toString()))
                    .andExpect(jsonPath("$.sessions[0].inside").value(35));
        }

        @Test
        @DisplayName("Nhân viên soát vé KHÔNG xem được số liệu của cả tổ chức: 403")
        void nhan_vien_soat_ve_403() throws Exception {
            mvc.perform(get(BASE + "/presence").with(member(USER, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            verifyNoInteractions(reader);
        }

        @Test
        @DisplayName("Không đăng nhập: 401")
        void khong_dang_nhap() throws Exception {
            mvc.perform(get(BASE + "/presence")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Thành viên tổ chức khác: 404")
        void to_chuc_khac() throws Exception {
            mvc.perform(get(BASE + "/presence").with(member(USER, UUID.randomUUID(), Role.ORG_OWNER)))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(reader);
        }
    }

    @Nested
    @DisplayName("Tham số kỳ thống kê")
    class ThamSo {

        @Test
        @DisplayName("Ngày sai định dạng: 400 VALIDATION_FAILED nói rõ tham số nào")
        void ngay_sai_dinh_dang() throws Exception {
            mvc.perform(get(BASE + "/activity")
                            .param("from", "12/10/2026")
                            .param("to", "2026-10-12")
                            .with(member(USER, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.detail").value("from phải có dạng YYYY-MM-DD"));
        }

        @Test
        @DisplayName("Ngày kết thúc trước ngày bắt đầu: 400")
        void ngay_nguoc() throws Exception {
            mvc.perform(get(BASE + "/activity")
                            .param("from", "2026-10-12")
                            .param("to", "2026-10-11")
                            .with(member(USER, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(reader);
        }

        @Test
        @DisplayName("Mã suất trong bộ lọc không phải UUID: 400")
        void ma_suat_hong() throws Exception {
            mvc.perform(get(BASE + "/activity")
                            .param("from", "2026-10-12")
                            .param("to", "2026-10-12")
                            .param("sessionId", "khong-phai-uuid")
                            .with(member(USER, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Mã trong bộ lọc không hợp lệ"));
        }

        @Test
        @DisplayName("Thiếu tham số bắt buộc 'from': 400, không phải 500")
        void thieu_tham_so_bat_buoc() throws Exception {
            mvc.perform(get(BASE + "/activity").param("to", "2026-10-12").with(member(USER, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(reader);
        }

        @Test
        @DisplayName("Hợp lệ: trả kỳ đã hỏi và các khối số liệu")
        void hop_le() throws Exception {
            when(reader.totals(any())).thenReturn(new GateStats.LogTotals(10, 8, 7, 2, 2));

            mvc.perform(get(BASE + "/activity")
                            .param("from", "2026-10-01")
                            .param("to", "2026-10-12")
                            .with(member(USER, ORG, Role.EVENT_MANAGER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.from").value("2026-10-01"))
                    .andExpect(jsonPath("$.totals.entries").value(8))
                    .andExpect(jsonPath("$.timelineTruncated").value(false));
        }

        @Test
        @DisplayName("Tên người mua trả về đã bị che")
        void ten_da_che() throws Exception {
            UUID order = UUID.randomUUID();
            when(reader.holderNames(ORG, List.of(order)))
                    .thenReturn(List.of(new GateStats.Buyer(order, "Nguyễn Văn An")));

            mvc.perform(get(BASE + "/buyers")
                            .param("orderId", order.toString())
                            .with(member(USER, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.buyers[0].name").value("Nguyễn V. A."));
        }
    }

    @Nested
    @DisplayName("Xuất danh sách người mua")
    class XuatDanhSach {

        @Test
        @DisplayName("Người quản lý sự kiện xem được thống kê nhưng KHÔNG xuất được tên khách: 403")
        void quan_ly_su_kien_khong_xuat_duoc() throws Exception {
            mvc.perform(post(BASE + "/buyers/export")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(member(USER, ORG, Role.EVENT_MANAGER)))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(reader, audit);
        }

        @Test
        @DisplayName("Quản trị tổ chức xuất được; nhật ký ghi đúng người thực hiện lấy từ token")
        void quan_tri_xuat_duoc() throws Exception {
            UUID session = UUID.randomUUID();
            when(reader.buyersForExport(eq(ORG), eq(List.of(session)), anyInt()))
                    .thenReturn(List.of(new GateStats.BuyerExportRow(
                            "ABCDEF12", "Nguyễn Văn An", session, "VIP", "A", "A-1", "VALID", Instant.EPOCH)));

            mvc.perform(post(BASE + "/buyers/export")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sessionIds\":[\"" + session + "\"]}")
                            .with(member(USER, ORG, Role.ORG_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0].holderName").value("Nguyễn Văn An"));

            ArgumentCaptor<UUID> actor = ArgumentCaptor.forClass(UUID.class);
            verify(audit).buyerListExported(eq(ORG), actor.capture(), any());
            assertThat(actor.getValue()).isEqualTo(USER);
        }

        @Test
        @DisplayName("Không ghi được nhật ký: 503 AUDIT_UNAVAILABLE, không có dòng dữ liệu nào trong thân")
        void nhat_ky_hong() throws Exception {
            when(reader.buyersForExport(any(), anyList(), anyInt()))
                    .thenReturn(List.of(new GateStats.BuyerExportRow(
                            "ABCDEF12",
                            "Nguyễn Văn An",
                            UUID.randomUUID(),
                            "VIP",
                            "A",
                            "A-1",
                            "VALID",
                            Instant.EPOCH)));
            doThrow(new ExportAuditPort.AuditUnavailableException("sập", null))
                    .when(audit)
                    .buyerListExported(any(), any(), any());

            mvc.perform(post(BASE + "/buyers/export")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(member(USER, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                    .andExpect(jsonPath("$.rows").doesNotExist());
        }

        @Test
        @DisplayName("Không có thân request: 400")
        void khong_co_than() throws Exception {
            mvc.perform(post(BASE + "/buyers/export")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(member(USER, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(audit);
        }
    }
}
