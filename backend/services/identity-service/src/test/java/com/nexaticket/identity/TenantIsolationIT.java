// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.support.MutableJwt;
import com.nexaticket.platform.test.PostgresSingleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Cách ly giữa các tổ chức — lớp bảo vệ mà mọi endpoint org-scoped đều dựa vào.
 *
 * <h2>Vì sao lớp này chạy Tomcat thật, không dùng MockMvc</h2>
 *
 * <p>{@code TenantFilter} lấy tổ chức bằng cách so khớp <b>chuỗi</b> {@code /organizations/{uuid}}
 * trên {@code getRequestURI()}, còn Spring lấy {@code @PathVariable} bằng cách <b>giải mã</b> cùng
 * đường dẫn đó. Hai cách đọc khác nhau trên cùng một chuỗi là chỗ kinh điển để một bộ lọc bảo mật
 * và một controller nhìn thấy hai giá trị khác nhau.
 *
 * <p>MockMvc không trả lời được câu đó: nó tự dựng {@code MockHttpServletRequest} và không đi qua
 * bộ phân tích URI của Tomcat — vốn là nơi quyết định {@code getRequestURI()} trả về chuỗi thô hay
 * chuỗi đã chuẩn hoá. Một bộ test dùng MockMvc sẽ báo "đã chặn" cho một lỗ hổng có thật ở
 * production, và đó là kiểu sai tệ nhất mà một bộ test bảo mật có thể mắc.
 *
 * <p>Nên lớp này gửi HTTP thật, bằng {@code HttpClient} của JDK, với URI dựng bằng
 * {@code URI.create} để không thư viện nào chuẩn hoá hộ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MutableJwt.class)
class TenantIsolationIT {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("identity_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }

    @LocalServerPort
    int port;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private UUID myOrg;
    private UUID otherOrg;

    @BeforeEach
    void setUp() {
        MutableJwt.reset();
        jdbc.update(
                "DELETE FROM organization_members WHERE user_id IN (SELECT id FROM users WHERE email = ?)",
                MutableJwt.DEFAULT_EMAIL);
        jdbc.update("DELETE FROM users WHERE email = ?", MutableJwt.DEFAULT_EMAIL);

        UUID me = users.upsertByIdpSubject(MutableJwt.DEFAULT_SUBJECT, MutableJwt.DEFAULT_EMAIL, "Toi")
                .id()
                .value();

        myOrg = createOrg("To chuc cua toi");
        otherOrg = createOrg("To chuc nguoi khac");
        jdbc.update(
                "INSERT INTO organization_members (id, organization_id, user_id, role) VALUES (?, ?, ?, 'ORG_OWNER')",
                UUID.randomUUID(),
                myOrg,
                me);
    }

    @Test
    @DisplayName("đường dẫn thẳng: tổ chức của mình 200, của người khác 404")
    void duong_dan_thang() throws Exception {
        assertThat(status("/v1/organizations/" + myOrg + "/members")).isEqualTo(200);
        assertThat(status("/v1/organizations/" + otherOrg + "/members")).isEqualTo(404);
    }

    @Test
    @DisplayName("UUID mã hoá phần trăm KHÔNG lọt qua tenant filter")
    void uuid_ma_hoa_phan_tram() throws Exception {
        // Mã hoá một ký tự hex thành `%xx` làm regex của filter trượt. Nếu Tomcat trả chuỗi thô cho
        // `getRequestURI()` nhưng Spring vẫn giải mã ra UUID thật cho `@PathVariable`, thì filter
        // và controller nhìn thấy hai tổ chức khác nhau — và bảng thành viên của người khác lọt ra.
        String encoded = percentEncodeFirstChar(otherOrg.toString());

        assertThat(status("/v1/organizations/" + encoded + "/members"))
                .as("Đọc được thành viên của tổ chức khác qua đường dẫn mã hoá — đây là IDOR")
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("đoạn đường dẫn thừa không đánh lừa được bộ lọc")
    void doan_duong_dan_thua() throws Exception {
        // `matcher.find()` lấy lần khớp ĐẦU TIÊN. Nếu ghép hai đoạn `/organizations/{id}` thì filter
        // kiểm tổ chức thứ nhất còn controller đọc tổ chức thứ hai.
        assertThat(status("/v1/organizations/" + myOrg + "/organizations/" + otherOrg + "/members"))
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("UUID viết hoa vẫn bị chặn")
    void uuid_viet_hoa() throws Exception {
        // Postgres so UUID theo giá trị chứ không theo chuỗi; regex của filter có sẵn `A-F` nên
        // khớp. Ca này chốt lại điều đó thay vì để nó đúng một cách tình cờ.
        assertThat(status("/v1/organizations/" + otherOrg.toString().toUpperCase() + "/members"))
                .isNotEqualTo(200);
    }

    private UUID createOrg(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, slug, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                id,
                "org-" + id.toString().substring(0, 8),
                name);
        return id;
    }

    /** `1` → `%31`: cùng một UUID sau khi giải mã, khác hẳn về mặt chuỗi. */
    private static String percentEncodeFirstChar(String uuid) {
        return "%%%02X%s".formatted((int) uuid.charAt(0), uuid.substring(1));
    }

    private int status(String path) throws Exception {
        // `URI.create` giữ nguyên chuỗi đã mã hoá. Dùng `new URI(scheme, host, path, …)` thì nó mã
        // hoá lại dấu `%` thành `%25` và ca test mất hết ý nghĩa.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer test-token")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
