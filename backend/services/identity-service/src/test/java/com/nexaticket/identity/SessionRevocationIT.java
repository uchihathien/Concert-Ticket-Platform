// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.support.MutableJwt;
import com.nexaticket.identity.support.PostgresTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Thu hồi phiên và vô hiệu hoá tài khoản, qua chuỗi HTTP thật.
 *
 * <p>Đây là phần <b>không</b> làm được nếu chỉ dựa vào Keycloak: nó huỷ được refresh token, nhưng
 * một access token đã phát thì sống hết 15 phút và trước đây không có gì trong hệ thống này từ chối
 * nó. Bộ test vì thế xoay quanh đúng một câu hỏi — token đang cầm trong tay có còn dùng được không.
 *
 * <p>{@code JwtDecoder} giả điều khiển được {@code iat} và {@code sid} ({@link MutableJwt}), vì đó
 * chính là hai claim mà cơ chế thu hồi dựa vào. Mọi thứ còn lại là thật: {@code TenantFilter} thật,
 * {@code LocalMembershipLookup} thật, ràng buộc database thật.
 */
@AutoConfigureMockMvc
@Import(MutableJwt.class)
class SessionRevocationIT extends PostgresTestBase {

    private static final String BEARER = "Bearer test-token";
    private static final String STAFF_SUBJECT = "keycloak-sub-staff";
    private static final String STAFF_EMAIL = "staff-revoke@example.com";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private UUID adminId;
    private UUID staffId;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        MutableJwt.reset();
        jdbc.update(
                "DELETE FROM revoked_sessions WHERE user_id IN (SELECT id FROM users WHERE email IN (?, ?))",
                MutableJwt.DEFAULT_EMAIL,
                STAFF_EMAIL);
        jdbc.update(
                "DELETE FROM organization_members WHERE user_id IN (SELECT id FROM users WHERE email IN (?, ?))",
                MutableJwt.DEFAULT_EMAIL,
                STAFF_EMAIL);
        jdbc.update("DELETE FROM users WHERE email IN (?, ?)", MutableJwt.DEFAULT_EMAIL, STAFF_EMAIL);

        adminId = users.upsertByIdpSubject(MutableJwt.DEFAULT_SUBJECT, MutableJwt.DEFAULT_EMAIL, "Quan Tri")
                .id()
                .value();
        staffId = users.upsertByIdpSubject(STAFF_SUBJECT, STAFF_EMAIL, "Nhan Vien")
                .id()
                .value();

        organizationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, slug, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                organizationId,
                "to-chuc-" + organizationId.toString().substring(0, 8),
                "To chuc thu");
        addMember(adminId, "ORG_OWNER");
        addMember(staffId, "CHECKIN_STAFF");
    }

    @Test
    @DisplayName("thu hồi toàn bộ phiên: token đang cầm chết ngay, đăng nhập lại thì dùng được")
    void thu_hoi_toan_bo_phien() throws Exception {
        makeSuperAdmin(adminId);
        assertThat(status(get("/v1/me"))).isEqualTo(200);

        // Token của nhân viên được phát trước lời gọi thu hồi.
        MutableJwt.actAs(STAFF_SUBJECT, STAFF_EMAIL, "Nhan Vien");
        MutableJwt.issuedAt(Instant.now().minus(Duration.ofMinutes(5)));
        assertThat(status(get("/v1/me"))).isEqualTo(200);

        actAsAdmin();
        assertThat(status(delete("/v1/platform/users/" + staffId + "/sessions?reason=nghi-viec")))
                .isEqualTo(204);

        // Cùng token đó, giờ bị từ chối. Đây là điều Keycloak một mình không làm được.
        MutableJwt.actAs(STAFF_SUBJECT, STAFF_EMAIL, "Nhan Vien");
        MutableJwt.issuedAt(Instant.now().minus(Duration.ofMinutes(5)));
        assertThat(status(get("/v1/me"))).isEqualTo(401);

        // Đăng nhập lại — token mới, phát sau mốc thu hồi — thì vào được. Thu hồi không phải khoá
        // tài khoản; đó là hai việc khác nhau, và ca dưới kiểm việc kia.
        MutableJwt.issuedAt(Instant.now().plusSeconds(1));
        assertThat(status(get("/v1/me"))).isEqualTo(200);
    }

    @Test
    @DisplayName("thu hồi một phiên chỉ đá một thiết bị, các thiết bị khác vẫn vào được")
    void thu_hoi_mot_phien() throws Exception {
        // Gộp hai mức thu hồi làm một thì người dùng sẽ không dám bấm nút cho tình huống
        // "quên đăng xuất ở máy khác".
        makeSuperAdmin(adminId);
        actAsAdmin();

        assertThat(status(delete("/v1/platform/users/" + staffId + "/sessions/dien-thoai-cu")))
                .isEqualTo(204);

        MutableJwt.actAs(STAFF_SUBJECT, STAFF_EMAIL, "Nhan Vien");
        MutableJwt.onDevice("dien-thoai-cu");
        assertThat(status(get("/v1/me"))).isEqualTo(401);

        MutableJwt.onDevice("may-tinh-o-nha");
        assertThat(status(get("/v1/me"))).isEqualTo(200);
    }

    @Test
    @DisplayName("vô hiệu hoá tài khoản: đăng nhập lại cũng không vào được")
    void vo_hieu_hoa_tai_khoan() throws Exception {
        makeSuperAdmin(adminId);
        actAsAdmin();

        assertThat(status(post("/v1/platform/users/" + staffId + "/disable")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"vi pham quy che\"}")))
                .isEqualTo(204);

        // Khác thu hồi phiên ở đúng chỗ này: token mới tinh vẫn bị từ chối.
        MutableJwt.actAs(STAFF_SUBJECT, STAFF_EMAIL, "Nhan Vien");
        MutableJwt.issuedAt(Instant.now().plusSeconds(60));
        assertThat(status(get("/v1/me"))).isEqualTo(401);

        actAsAdmin();
        assertThat(status(post("/v1/platform/users/" + staffId + "/enable"))).isEqualTo(204);

        // Khôi phục KHÔNG lùi mốc thu hồi: token cũ vẫn chết, token mới thì vào được.
        MutableJwt.actAs(STAFF_SUBJECT, STAFF_EMAIL, "Nhan Vien");
        MutableJwt.issuedAt(Instant.now().plusSeconds(120));
        assertThat(status(get("/v1/me"))).isEqualTo(200);
    }

    @Test
    @DisplayName("không tự vô hiệu hoá chính mình")
    void khong_tu_khoa_minh() throws Exception {
        // Tự khoá mình là sự cố không tự sửa được: sau đó chính người đó không gọi nổi endpoint mở
        // khoá, và cách duy nhất là sửa tay trong database.
        makeSuperAdmin(adminId);
        actAsAdmin();

        assertThat(status(post("/v1/platform/users/" + adminId + "/disable"))).isEqualTo(403);
    }

    @Test
    @DisplayName("quản trị viên tổ chức đăng xuất được nhân viên của mình")
    void org_admin_thu_hoi_phien_nhan_vien() throws Exception {
        // Người gọi KHÔNG phải superadmin ở ca này — quyền ORG_SESSION_REVOKE là đủ.
        actAsAdmin();

        assertThat(status(delete("/v1/organizations/" + organizationId + "/members/" + staffId + "/sessions")))
                .isEqualTo(204);

        MutableJwt.actAs(STAFF_SUBJECT, STAFF_EMAIL, "Nhan Vien");
        MutableJwt.issuedAt(Instant.now().minus(Duration.ofMinutes(1)));
        assertThat(status(get("/v1/me"))).isEqualTo(401);
    }

    @Test
    @DisplayName("không đăng xuất được người ngoài tổ chức mình")
    void khong_thu_hoi_duoc_nguoi_ngoai() throws Exception {
        // Thiếu bước kiểm này thì ORG_SESSION_REVOKE — một quyền nghe rất hẹp — trở thành quyền
        // đăng xuất bất kỳ ai trong hệ thống, kể cả superadmin, chỉ cần biết id của họ.
        UUID nguoiNgoai = users.upsertByIdpSubject("sub-ngoai", "ngoai@example.com", "Nguoi Ngoai")
                .id()
                .value();
        actAsAdmin();

        assertThat(status(delete("/v1/organizations/" + organizationId + "/members/" + nguoiNgoai + "/sessions")))
                .isEqualTo(404);
    }

    @Test
    @DisplayName("nhân viên soát vé không thu hồi được phiên của ai")
    void checkin_staff_khong_co_quyen() throws Exception {
        MutableJwt.actAs(STAFF_SUBJECT, STAFF_EMAIL, "Nhan Vien");

        assertThat(status(delete("/v1/organizations/" + organizationId + "/members/" + adminId + "/sessions")))
                .isEqualTo(403);
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private void actAsAdmin() {
        MutableJwt.actAs(MutableJwt.DEFAULT_SUBJECT, MutableJwt.DEFAULT_EMAIL, "Quan Tri");
        MutableJwt.issuedAt(Instant.now());
        MutableJwt.onDevice("device-admin");
    }

    private void makeSuperAdmin(UUID userId) {
        jdbc.update("UPDATE users SET is_super_admin = TRUE WHERE id = ?", userId);
    }

    private void addMember(UUID userId, String role) {
        jdbc.update(
                "INSERT INTO organization_members (id, organization_id, user_id, role) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                organizationId,
                userId,
                role);
    }

    private int status(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", BEARER))
                .andReturn()
                .getResponse()
                .getStatus();
    }
}
