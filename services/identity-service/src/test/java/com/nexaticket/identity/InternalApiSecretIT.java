// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.nexaticket.identity.support.PostgresTestBase;
import com.nexaticket.platform.security.tenant.InternalApiFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bí mật dùng chung của Open Host Service.
 *
 * <p>"Gateway không route {@code /internal/**} ra ngoài" là một tính chất của cấu hình gateway, chứ
 * không phải của service. Ai đứng được trong mạng nội bộ vẫn gọi thẳng được — và
 * {@code /internal/memberships} tạo người dùng với {@code email} do người gọi tự đặt, còn
 * {@code SuperAdminBootstrap} thì cấp {@code SUPER_ADMIN} theo email.
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = "nexaticket.internal.shared-secret=bi-mat-cua-test")
class InternalApiSecretIT extends PostgresTestBase {

    private static final String SECRET = "bi-mat-cua-test";

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("không có header thì 404, không phải 401")
    void thieu_header_thi_404() throws Exception {
        // 401 xác nhận endpoint tồn tại và chỉ thiếu thông tin xác thực — một lời mời dò tìm.
        mockMvc.perform(get("/internal/memberships").param("idpSubject", "bat-ky"))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
    }

    @Test
    @DisplayName("sai bí mật thì cũng 404")
    void sai_bi_mat_thi_404() throws Exception {
        mockMvc.perform(get("/internal/memberships")
                        .header(InternalApiFilter.HEADER, "sai")
                        .param("idpSubject", "bat-ky"))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
    }

    @Test
    @DisplayName("đúng bí mật thì đi qua")
    void dung_bi_mat_thi_qua() throws Exception {
        mockMvc.perform(get("/internal/memberships")
                        .header(InternalApiFilter.HEADER, SECRET)
                        .param("idpSubject", "khong-ton-tai"))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
    }

    @Test
    @DisplayName("chỉ chắn /internal/**: đường của người dùng vẫn đi tới lớp xác thực")
    void khong_cham_duong_khac() throws Exception {
        // Nhầm phạm vi ở đây thì hoặc lộ Open Host Service, hoặc chặn cả API thật của người dùng.
        //
        // Phép thử là 401 chứ không phải 200: `/v1/me` vốn đòi đăng nhập, nên 401 chứng minh
        // request đã đi qua InternalApiFilter và tới được lớp xác thực. Nếu filter chắn nhầm cả
        // đường này thì kết quả sẽ là 404 — mã mà nó cố ý dùng để không xác nhận endpoint có tồn
        // tại hay không.
        //
        // Bản trước dò `/actuator/health` và chờ 200. Không dùng được nữa: actuator đã chuyển sang
        // cổng quản trị riêng (`management.server.port`), nên trên cổng ứng dụng nó là 404 thật —
        // và một ca test đỏ vì lý do đó chỉ dạy người ta bỏ qua nó.
        mockMvc.perform(get("/v1/me"))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }
}
