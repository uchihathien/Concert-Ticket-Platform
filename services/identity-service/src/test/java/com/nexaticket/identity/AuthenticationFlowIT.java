// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.support.PostgresTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Đăng nhập đi qua <b>toàn bộ chuỗi filter HTTP thật</b>.
 *
 * <p>Đây là lỗ hổng đã để lọt một lỗi chặn cả nền tảng: mọi test khác của identity-service hoặc gọi
 * thẳng handler, hoặc gọi thẳng repository. Không cái nào đi qua Spring Security, nên không cái nào
 * phát hiện được rằng {@code TenantFilter} chạy <i>trước</i> khi JWT được đưa vào SecurityContext —
 * khiến mọi request đã đăng nhập đều nhận 401.
 *
 * <p>{@link JwtDecoder} được thay bằng bản giả để test không cần Keycloak. Phần <b>không</b> giả là
 * thứ đáng kiểm: chuỗi filter thật, thứ tự filter thật, {@code TenantFilter} thật, và
 * {@code LocalMembershipLookup} thật đọc database thật.
 */
@AutoConfigureMockMvc
@Import(AuthenticationFlowIT.StubJwt.class)
class AuthenticationFlowIT extends PostgresTestBase {

    private static final String BEARER = "Bearer test-token";

    /** Người dùng đứng sau token giả; mỗi test tự quyết có tạo bản ghi trong database hay không. */
    static final String IDP_SUBJECT = "keycloak-sub-for-auth-test";

    @TestConfiguration
    static class StubJwt {

        @Bean
        @Primary
        JwtDecoder stubDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(IDP_SUBJECT)
                    .claim("email", "auth-test@example.com")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                    .build();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Mỗi ca bắt đầu từ trạng thái chưa có người dùng.
     *
     * <p>Bốn ca dùng chung một {@code idpSubject}, và {@code upsertByIdpSubject} KHÔNG hạ cờ
     * {@code is_super_admin}. Không dọn thì ca chạy sau kế thừa quyền của ca chạy trước — test
     * xanh hay đỏ tuỳ thứ tự JUnit chọn.
     */
    @BeforeEach
    void removeTestUser() {
        jdbc.update(
                "DELETE FROM organization_members WHERE user_id IN (SELECT id FROM users WHERE idp_subject = ?)",
                IDP_SUBJECT);
        jdbc.update("DELETE FROM users WHERE idp_subject = ?", IDP_SUBJECT);
    }

    @Test
    @DisplayName("JWT hợp lệ + user đã có + là superadmin: tạo được tổ chức")
    void superadmin_tao_duoc_to_chuc() throws Exception {
        provisionSuperAdmin();

        mockMvc.perform(
                        MockMvcRequestBuilders.post("/v1/platform/organizations")
                                .header("Authorization", BEARER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"name":"Nha hat Lon Ha Noi","ownerEmail":"organizer@example.com"}
                                """))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("JWT hợp lệ của superadmin phải tạo được tổ chức, không phải 401")
                        .isEqualTo(201));
    }

    @Test
    @DisplayName("JWT hợp lệ nhưng KHÔNG phải superadmin: 403, không phải 401")
    void nguoi_thuong_bi_tu_choi_dung_ma() throws Exception {
        // Phân biệt 401 với 403 là quan trọng: 401 nghĩa là "hệ thống không biết bạn là ai" —
        // nếu người dùng đã đăng nhập mà vẫn nhận 401 thì đó là lỗi hạ tầng xác thực, không phải
        // thiếu quyền. Chính sự lẫn lộn đó đã che lỗi thứ tự filter.
        users.upsertByIdpSubject(IDP_SUBJECT, "auth-test@example.com", "Nguoi thuong");

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/platform/organizations")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"ownerEmail\":\"o@example.com\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("Người dùng đã đăng nhập nhưng thiếu quyền phải nhận 403")
                        .isEqualTo(403));
    }

    @Test
    @DisplayName("Không có token: 401")
    void khong_co_token() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/me/organizations"))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    @DisplayName("JWT hợp lệ nhưng chưa có bản ghi user: KHÔNG được là 401 câm")
    void chua_provision_thi_khong_duoc_401_cam() throws Exception {
        // Người vừa đăng nhập lần đầu qua Keycloak chưa có dòng nào trong bảng users. Nếu hệ
        // thống trả 401 thì họ mắc kẹt vĩnh viễn: không có đường nào để tự tạo bản ghi, và mọi
        // request sau đó đều 401. Bản ghi phải được tạo ở lần chạm đầu tiên.
        var result = mockMvc.perform(
                        MockMvcRequestBuilders.get("/v1/me/organizations").header("Authorization", BEARER))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("Đăng nhập lần đầu phải dùng được, không phải 401 vĩnh viễn")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("superadmin dùng được route /v1/platform/{id}/... dù không là thành viên tổ chức đó")
    void superadmin_thao_tac_cross_tenant() throws Exception {
        // TenantFilter lấy tổ chức từ đoạn /organizations/{id} trên ĐƯỜNG DẪN và trả 404 nếu người
        // gọi không phải thành viên. Với khu vực nền tảng thì luật đó sai hoàn toàn: superadmin
        // theo thiết kế không là thành viên của tổ chức nào, nên nó tự chặn chính mình khỏi những
        // route sinh ra cho mình — 404 cho người có toàn quyền.
        //
        // Lỗi nằm im rất lâu vì hai route nền tảng đầu tiên không mang id trên đường dẫn.
        provisionSuperAdmin();
        var created = mockMvc.perform(MockMvcRequestBuilders.post("/v1/platform/organizations")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"To chuc cross tenant\",\"ownerEmail\":\"o2@example.com\"}"))
                .andReturn();
        String organizationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .path("id")
                .asText();

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/platform/organizations/" + organizationId + "/suspend")
                        .header("Authorization", BEARER))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("superadmin phải khoá được tổ chức mình không thuộc về")
                        .isEqualTo(200));
    }

    @Test
    @DisplayName("người thường vào route nền tảng: 403, không phải 404")
    void nguoi_thuong_vao_route_nen_tang() throws Exception {
        // 403 nói "bạn không phải superadmin" và KHÔNG xác nhận tổ chức kia có tồn tại — nên bỏ
        // bước lấy tenant ở khu vực nền tảng không mở ra kênh dò tìm tổ chức nào.
        users.upsertByIdpSubject(IDP_SUBJECT, "auth-test@example.com", "Nguoi thuong");

        mockMvc.perform(MockMvcRequestBuilders.post(
                                "/v1/platform/organizations/" + java.util.UUID.randomUUID() + "/suspend")
                        .header("Authorization", BEARER))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));
    }

    private void provisionSuperAdmin() {
        var user = users.upsertByIdpSubject(IDP_SUBJECT, "auth-test@example.com", "Super Admin");
        jdbc.update(
                "UPDATE users SET is_super_admin = TRUE WHERE id = ?", user.id().value());
    }
}
