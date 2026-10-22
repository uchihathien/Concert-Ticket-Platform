// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.interfaces.rest;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.application.query.SeatQueries;
import com.nexaticket.inventory.application.query.SeatStatusView;
import com.nexaticket.inventory.support.WebSliceTestBase;
import com.nexaticket.platform.security.tenant.InternalApiFilter;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Đường đọc tồn kho cho catalog-service: chỉ service nội bộ, 404 là câu trả lời "chưa lên bán". */
@WebMvcTest(
        controllers = InternalSeatStatusController.class,
        properties = "nexaticket.internal.shared-secret=" + WebSliceTestBase.INTERNAL_SECRET)
class InternalSeatStatusControllerTest extends WebSliceTestBase {

    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");

    @MockitoBean
    SeatQueries queries;

    @Test
    @DisplayName("Token đúng: 200 với đúng tên field catalog đọc")
    void tra_so_dem_theo_khu() throws Exception {
        when(queries.seatStatus(SESSION))
                .thenReturn(new SeatStatusView(
                        SESSION, 9L, List.of(new SeatStatusView.ZoneStatus("A", "SEATED", 10, 2, 3, 4, 1))));

        mvc.perform(get("/internal/sessions/{id}/seat-status", SESSION)
                        .header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventSessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.availabilityVersion").value(9))
                .andExpect(jsonPath("$.zones[0].zoneCode").value("A"))
                .andExpect(jsonPath("$.zones[0].admissionType").value("SEATED"))
                .andExpect(jsonPath("$.zones[0].available").value(10))
                .andExpect(jsonPath("$.zones[0].held").value(2))
                .andExpect(jsonPath("$.zones[0].reserved").value(3))
                .andExpect(jsonPath("$.zones[0].sold").value(4))
                .andExpect(jsonPath("$.zones[0].blocked").value(1));
    }

    @Test
    @DisplayName("Suất chưa dựng: 404 có thân SESSION_NOT_FOUND — phân biệt được với 404 của bộ lọc")
    void suat_chua_dung() throws Exception {
        when(queries.seatStatus(SESSION))
                .thenThrow(new ApiException(InventoryErrorCode.SESSION_NOT_FOUND, "Event session not found"));

        mvc.perform(get("/internal/sessions/{id}/seat-status", SESSION)
                        .header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("Không token nội bộ: 404 không thân, không chạm truy vấn")
    void khong_token_noi_bo() throws Exception {
        mvc.perform(get("/internal/sessions/{id}/seat-status", SESSION))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verifyNoInteractions(queries);
    }
}
