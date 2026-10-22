// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.interfaces.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.inventory.application.query.SeatStats;
import com.nexaticket.inventory.application.query.SeatStatsQuery;
import com.nexaticket.inventory.support.WebSliceTestBase;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Thống kê tồn kho cho ban tổ chức: {@code TenantFilter} chặn người ngoài bằng 404,
 * {@code PermissionGuard} chặn người trong nhưng thiếu {@code CATALOG_MANAGE} bằng 403.
 */
@WebMvcTest(OrganizationSeatStatsController.class)
class OrganizationSeatStatsControllerTest extends WebSliceTestBase {

    private static final UUID ORG = UUID.fromString("00000000-0000-4000-a000-0000000000f1");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-4000-a000-0000000000f2");
    private static final UUID USER = UUID.fromString("00000000-0000-4000-a000-0000000000b1");
    private static final String PATH = "/v1/organizations/{org}/stats/inventory/stock";

    @MockitoBean
    SeatStatsQuery stats;

    private static SeatStats.Stock sampleStock() {
        return new SeatStats.Stock(
                List.of(new SeatStats.TicketTypeStock(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Ve ngoi",
                        1_500_000L,
                        100,
                        40,
                        10,
                        45,
                        5)),
                List.of(new SeatStats.ExpiringHolds(UUID.randomUUID(), 3)),
                SeatStats.EXPIRING_MINUTES);
    }

    private String memberToken(Role role) {
        return tokenFor(USER, Map.of(TenantId.of(ORG), role), false);
    }

    @Nested
    @DisplayName("Được phép")
    class DuocPhep {

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"ORG_OWNER", "ORG_ADMIN", "EVENT_MANAGER"})
        @DisplayName("Vai trò có CATALOG_MANAGE: 200, truy vấn đúng tổ chức trên đường dẫn")
        void vai_tro_co_quyen(Role role) throws Exception {
            when(stats.stock(ORG)).thenReturn(sampleStock());

            mvc.perform(get(PATH, ORG).header(HttpHeaders.AUTHORIZATION, bearer(memberToken(role))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ticketTypes[0].sold").value(40))
                    .andExpect(jsonPath("$.ticketTypes[0].held").value(10))
                    .andExpect(jsonPath("$.expiringHolds[0].seats").value(3))
                    .andExpect(jsonPath("$.expiringWithinMinutes").value(5));

            verify(stats).stock(ORG);
        }
    }

    @Nested
    @DisplayName("Bị chặn")
    class BiChan {

        @Test
        @DisplayName("Nhân viên soát vé (thiếu CATALOG_MANAGE): 403 FORBIDDEN dạng RFC 7807")
        void nhan_vien_soat_ve() throws Exception {
            mvc.perform(get(PATH, ORG).header(HttpHeaders.AUTHORIZATION, bearer(memberToken(Role.CHECKIN_STAFF))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            verifyNoInteractions(stats);
        }

        @Test
        @DisplayName("Thành viên tổ chức KHÁC: 404 — không xác nhận tổ chức này tồn tại")
        void to_chuc_khac() throws Exception {
            String token = tokenFor(USER, Map.of(TenantId.of(OTHER_ORG), Role.ORG_OWNER), false);

            mvc.perform(get(PATH, ORG).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(stats);
        }

        @Test
        @DisplayName("Superadmin không phải thành viên: 404 — route tổ chức vẫn đòi membership thật")
        void superadmin_khong_phai_thanh_vien() throws Exception {
            String token = tokenFor(USER, Map.of(), true);

            mvc.perform(get(PATH, ORG).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(stats);
        }

        @Test
        @DisplayName("Mã tổ chức mã hoá phần trăm để lách bộ lọc: 404, không lọt sang tổ chức khác")
        void ma_hoa_phan_tram() throws Exception {
            String token = memberToken(Role.ORG_ADMIN);
            String encoded =
                    "%3" + ORG.toString().substring(0, 1) + ORG.toString().substring(1);

            mvc.perform(get(java.net.URI.create("/v1/organizations/" + encoded + "/stats/inventory/stock"))
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(stats);
        }

        @Test
        @DisplayName("Không đăng nhập: 401")
        void khong_dang_nhap() throws Exception {
            mvc.perform(get(PATH, ORG)).andExpect(status().isUnauthorized());

            verifyNoInteractions(stats);
        }
    }
}
