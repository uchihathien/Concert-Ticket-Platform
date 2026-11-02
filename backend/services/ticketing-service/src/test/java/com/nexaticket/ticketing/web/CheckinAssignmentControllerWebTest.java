// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.ticketing.application.CheckinAssignments;
import com.nexaticket.ticketing.interfaces.rest.CheckinAssignmentController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** "Sự kiện tôi được gán" — màn đầu tiên của app Staff. */
@WebMvcTest(controllers = CheckinAssignmentController.class)
class CheckinAssignmentControllerWebTest extends WebSliceTest {

    private static final UUID ORG = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ORG = UUID.fromString("70000000-0000-4000-8000-000000000009");
    private static final UUID STAFF = UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final String URL = "/v1/organizations/" + ORG + "/checkin-assignments/me";

    @MockitoBean
    CheckinAssignments assignments;

    @Test
    @DisplayName("Staff: chỉ phân công còn hiệu lực của chính mình, kèm các suất trong phạm vi")
    void staff_thay_phan_cong_cua_minh() throws Exception {
        UUID event = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        Instant start = Instant.parse("2026-12-01T12:00:00Z");
        when(assignments.activeOf(ORG, STAFF))
                .thenReturn(List.of(new CheckinAssignments.Assigned(
                        STAFF,
                        event,
                        null,
                        "Đêm nhạc",
                        "Nhà hát",
                        start,
                        start.plusSeconds(12 * 3600),
                        "UPCOMING",
                        List.of(new CheckinAssignments.SessionTime(session, start, null)))));

        mvc.perform(get(URL).with(member(STAFF, ORG, Role.CHECKIN_STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(true))
                .andExpect(jsonPath("$.assignments[0].eventTitle").value("Đêm nhạc"))
                .andExpect(jsonPath("$.assignments[0].status").value("UPCOMING"))
                .andExpect(
                        jsonPath("$.assignments[0].sessions[0].eventSessionId").value(session.toString()));
    }

    @Test
    @DisplayName("Quản lý sự kiện: không bị giới hạn theo phân công, không đọc bảng phân công")
    void quan_ly_khong_gioi_han() throws Exception {
        mvc.perform(get(URL).with(member(STAFF, ORG, Role.EVENT_MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(false))
                .andExpect(jsonPath("$.assignments").isEmpty());
        verifyNoInteractions(assignments);
    }

    @Test
    @DisplayName("Người của tổ chức khác: 404, không lộ gì")
    void to_chuc_khac() throws Exception {
        mvc.perform(get(URL).with(member(STAFF, OTHER_ORG, Role.CHECKIN_STAFF))).andExpect(status().isNotFound());
        verifyNoInteractions(assignments);
    }
}
