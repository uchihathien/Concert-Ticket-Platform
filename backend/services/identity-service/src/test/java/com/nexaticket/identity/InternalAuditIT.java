// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.identity.support.PostgresTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class InternalAuditIT extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Service khác ghi được lượt xuất danh sách người mua, với đúng người thực hiện và tổ chức")
    void ghi_luot_xuat() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID org = UUID.randomUUID();

        mockMvc.perform(post("/internal/audit-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(actor, org, "BUYER_LIST_EXPORTED")))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                        "SELECT after_state ->> 'rows' FROM audit_logs WHERE actor_user_id = ? AND organization_id = ?"
                                + " AND action = 'BUYER_LIST_EXPORTED'",
                        String.class,
                        actor,
                        org))
                .isEqualTo("42");
    }

    @Test
    @DisplayName("Đường này không dựng được dòng nhật ký giả cho hành động của identity")
    void khong_ghi_duoc_hanh_dong_ngoai_danh_sach() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID org = UUID.randomUUID();

        mockMvc.perform(post("/internal/audit-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(actor, org, "MEMBER_REMOVED")))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE organization_id = ?", Integer.class, org))
                .isZero();
    }

    private static String body(UUID actor, UUID org, String action) {
        return """
                {"actorUserId":"%s","organizationId":"%s","action":"%s","entityType":"organization",
                 "entityId":"%s","details":{"rows":42}}
                """
                .formatted(actor, org, action, org);
    }
}
