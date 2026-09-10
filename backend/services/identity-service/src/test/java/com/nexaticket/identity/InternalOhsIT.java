// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Open Host Service — cửa mà <b>mọi</b> request đã đăng nhập của toàn hệ thống đi qua.
 *
 * <p>Chưa có test nào chạm tới nó, và đó là lý do hai lỗi dưới đây sống được lâu:
 *
 * <ul>
 *   <li>Endpoint được gọi thật ({@code /internal/users/provision}) UPSERT vô điều kiện và ghi đè
 *       {@code full_name} bằng claim của Keycloak, nên hồ sơ vừa sửa quay về bản cũ trong vòng một
 *       phút — không có lỗi nào, chỉ có một giá trị âm thầm đổi.
 *   <li>Người dùng không có claim {@code email} vẫn được tạo với email rỗng, vì tham số null đi qua
 *       query string thành chuỗi rỗng và lọt mọi phép kiểm null.
 * </ul>
 */
@AutoConfigureMockMvc
class InternalOhsIT extends PostgresTestBase {

    private static final String SUBJECT = "keycloak-sub-ohs-test";
    private static final String EMAIL = "ohs-test@example.com";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void removeTestUser() {
        jdbc.update(
                "DELETE FROM organization_members WHERE user_id IN (SELECT id FROM users WHERE idp_subject = ?)",
                SUBJECT);
        jdbc.update("DELETE FROM users WHERE idp_subject = ?", SUBJECT);
    }

    @Test
    @DisplayName("lần chạm đầu tiên tạo bản ghi người dùng")
    void lan_dau_tao_ban_ghi() throws Exception {
        String body = resolve(EMAIL, "Ten Tu Keycloak");

        assertThat(json.readTree(body).path("userId").asText())
                .as("người vừa đăng nhập lần đầu phải có bản ghi ngay, nếu không họ 401 vĩnh viễn")
                .isNotBlank();
        assertThat(users.findByIdpSubject(SUBJECT)).isPresent();
    }

    @Test
    @DisplayName("lần chạm sau KHÔNG ghi đè tên người dùng đã tự sửa")
    void khong_ghi_de_ho_so() throws Exception {
        resolve(EMAIL, "Ten Tu Keycloak");

        // Người dùng sửa hồ sơ ở PATCH /v1/me.
        var user = users.findByIdpSubject(SUBJECT).orElseThrow();
        users.updateProfile(user.id(), "Ten Tu Sua", null);

        // Rồi họ bấm sang một trang bất kỳ: service khác tra lại membership qua đúng endpoint này.
        resolve(EMAIL, "Ten Tu Keycloak");

        assertThat(users.findByIdpSubject(SUBJECT).orElseThrow().fullName())
                .as("tên người dùng tự đặt không được bị claim của Keycloak ghi đè")
                .isEqualTo("Ten Tu Sua");
    }

    @Test
    @DisplayName("không có email thì không tạo người dùng, và cũng không 500")
    void thieu_email_thi_khong_tao() throws Exception {
        // Đúng hình dạng URL mà một tham số null sinh ra: `?email` không có dấu bằng, đọc ra thành
        // chuỗi rỗng chứ không phải null.
        String body = mockMvc.perform(get("/internal/memberships")
                        .param("idpSubject", SUBJECT)
                        .param("email", ""))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // `default-property-inclusion: non_null` bỏ hẳn trường null khỏi JSON, nên "vắng mặt" mới
        // là hình dạng thật của principal rỗng — `HttpMembershipLookup` đọc nó ra null và trả về
        // null, tức là request nhận 401 kèm log nói rõ lý do.
        assertThat(json.readTree(body).path("userId").asText(""))
                .as("thiếu email thì trả principal rỗng — người gọi tự hiểu là chưa xác thực được")
                .isBlank();
        assertThat(users.findByIdpSubject(SUBJECT))
                .as("không bao giờ tạo người dùng không liên lạc được")
                .isEmpty();
    }

    private String resolve(String email, String fullName) throws Exception {
        return mockMvc.perform(get("/internal/memberships")
                        .param("idpSubject", SUBJECT)
                        .param("email", email)
                        .param("fullName", fullName))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
