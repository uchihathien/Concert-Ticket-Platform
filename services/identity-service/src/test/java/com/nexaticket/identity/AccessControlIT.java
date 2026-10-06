// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.support.MutableJwt;
import com.nexaticket.identity.support.PostgresTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phân quyền theo {@code Permission}, cấp thành viên từ nền tảng, và nhật ký kiểm toán.
 *
 * <p>Ba nhóm này đi cùng một lớp test vì chúng chia chung một câu hỏi: <b>ai được làm gì, ở tổ chức
 * nào</b>. Tách ra ba lớp sẽ là ba context Spring cho cùng một bộ dữ liệu dựng sẵn.
 */
@AutoConfigureMockMvc
@Import(MutableJwt.class)
class AccessControlIT extends PostgresTestBase {

    private static final String BEARER = "Bearer test-token";
    private static final String MANAGER_SUBJECT = "keycloak-sub-manager-access";
    private static final String MANAGER_EMAIL = "manager-access@example.com";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private UUID ownerId;
    private UUID managerId;
    private UUID organizationId;

    /**
     * Keycloak giả ở ranh giới cổng.
     *
     * <p>Không dựng một Keycloak thật: điều đáng kiểm ở đây là <b>ai được bấm nút</b> và service
     * cư xử thế nào khi IdP im lặng — không phải hình dạng HTTP của Admin API, vốn là việc của
     * adapter.
     */
    static final List<String> RESET_EMAILS_SENT = new java.util.ArrayList<>();

    static boolean identityProviderDown;

    @TestConfiguration
    static class StubIdentityProvider {

        @Bean
        @Primary
        IdentityProviderPort stubIdp() {
            return (idpSubject, clientId, redirectUri) -> {
                if (identityProviderDown) {
                    throw new IdentityProviderPort.IdentityProviderUnavailableException(
                            "test: Keycloak Admin API chưa cấu hình", null);
                }
                RESET_EMAILS_SENT.add(idpSubject);
            };
        }
    }

    @BeforeEach
    void setUp() {
        MutableJwt.reset();
        RESET_EMAILS_SENT.clear();
        identityProviderDown = false;
        jdbc.update("DELETE FROM invitations WHERE email LIKE '%-access@example.com'");
        jdbc.update(
                "DELETE FROM organization_members WHERE user_id IN (SELECT id FROM users WHERE email IN (?, ?))",
                MutableJwt.DEFAULT_EMAIL,
                MANAGER_EMAIL);
        jdbc.update("DELETE FROM users WHERE email IN (?, ?)", MutableJwt.DEFAULT_EMAIL, MANAGER_EMAIL);

        ownerId = users.upsertByIdpSubject(MutableJwt.DEFAULT_SUBJECT, MutableJwt.DEFAULT_EMAIL, "Chu So Huu")
                .id()
                .value();
        managerId = users.upsertByIdpSubject(MANAGER_SUBJECT, MANAGER_EMAIL, "Quan Ly Su Kien")
                .id()
                .value();

        organizationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, slug, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                organizationId,
                "to-chuc-" + organizationId.toString().substring(0, 8),
                "To chuc thu");
        addMember(ownerId, "ORG_OWNER");
        addMember(managerId, "EVENT_MANAGER");
    }

    // --- Ma trận đọc được từ API -------------------------------------------

    @Test
    @DisplayName("GET /v1/roles trả cả ma trận, để frontend không phải hard-code tên vai trò")
    void ma_tran_doc_duoc() throws Exception {
        JsonNode roles = json.readTree(perform(get("/v1/roles")).getResponse().getContentAsString());

        assertThat(roles).hasSize(6);
        JsonNode staff = roleNamed(roles, "CHECKIN_STAFF");
        assertThat(staff.path("scope").asText()).isEqualTo("ORGANIZATION");
        assertThat(staff.path("permissions").toString()).isEqualTo("[\"CHECKIN_SCAN\"]");

        assertThat(roleNamed(roles, "SUPER_ADMIN").path("scope").asText()).isEqualTo("GLOBAL");
    }

    @Test
    @DisplayName("GET /v1/me/permissions trả quyền theo từng tổ chức của chính người gọi")
    void quyen_cua_toi() throws Exception {
        actAsManager();

        JsonNode me =
                json.readTree(perform(get("/v1/me/permissions")).getResponse().getContentAsString());

        assertThat(me.path("superAdmin").asBoolean()).isFalse();
        assertThat(me.path("platformPermissions")).isEmpty();

        String permissions =
                me.path("organizations").path(organizationId.toString()).toString();
        assertThat(permissions).contains("CATALOG_MANAGE").contains("EVENT_PUBLISH");
        // EVENT_MANAGER không quản người: frontend đọc đúng chỗ này để ẩn nút "Mời thành viên".
        assertThat(permissions).doesNotContain("ORG_MEMBERS_MANAGE");
    }

    // --- Cửa quyền theo Permission -----------------------------------------

    @Test
    @DisplayName("EVENT_MANAGER không mời được thành viên, ORG_OWNER thì được")
    void chi_quan_tri_moi_duoc_thanh_vien() throws Exception {
        actAsManager();
        assertThat(status(post("/v1/organizations/" + organizationId + "/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", "moi-access@example.com", "role", "CHECKIN_STAFF")))))
                .isEqualTo(403);

        actAsOwner();
        assertThat(status(post("/v1/organizations/" + organizationId + "/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", "moi-access@example.com", "role", "CHECKIN_STAFF")))))
                .isEqualTo(200);
    }

    // --- Nền tảng cấp thành viên -------------------------------------------

    @Test
    @DisplayName("superadmin cấp thẳng ORG_ADMIN cho người đã từng đăng nhập")
    void cap_thang_cho_nguoi_da_ton_tai() throws Exception {
        UUID nguoiMoi = users.upsertByIdpSubject("sub-org-admin", "orgadmin-access@example.com", "Quan Tri Moi")
                .id()
                .value();
        makeSuperAdmin(ownerId);
        actAsOwner();

        JsonNode result = json.readTree(perform(post("/v1/platform/organizations/" + organizationId + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", "orgadmin-access@example.com", "role", "ORG_ADMIN"))))
                .getResponse()
                .getContentAsString());

        assertThat(result.path("outcome").asText()).isEqualTo("GRANTED");
        assertThat(result.path("userId").asText()).isEqualTo(nguoiMoi.toString());
        assertThat(jdbc.queryForObject(
                        "SELECT role FROM organization_members WHERE organization_id = ? AND user_id = ?",
                        String.class,
                        organizationId,
                        nguoiMoi))
                .isEqualTo("ORG_ADMIN");
    }

    @Test
    @DisplayName("người chưa từng đăng nhập: rơi về lời mời, và nói rõ là đã làm thế")
    void chua_dang_nhap_thi_roi_ve_loi_moi() throws Exception {
        // Keycloak giữ danh tính (ADR-0016): bản ghi người dùng chỉ ra đời ở request đầu tiên sau
        // khi đăng nhập, nên chưa có gì để gắn membership vào. Bịa một bản ghi tạm sẽ khoá vĩnh
        // viễn người đó ở lần đăng nhập thật — xem GrantMembershipHandler.
        makeSuperAdmin(ownerId);
        actAsOwner();

        JsonNode result = json.readTree(perform(post("/v1/platform/organizations/" + organizationId + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", "chua-co-access@example.com", "role", "ORG_ADMIN"))))
                .getResponse()
                .getContentAsString());

        assertThat(result.path("outcome").asText()).isEqualTo("INVITED");
        assertThat(result.path("invitationToken").asText()).isNotBlank();
        // Jackson bỏ trường null khỏi JSON, nên đây là MissingNode chứ không phải NullNode.
        assertThat(result.path("userId").isTextual()).isFalse();
    }

    @Test
    @DisplayName("không phải superadmin thì không cấp được thành viên")
    void nguoi_thuong_khong_cap_duoc() throws Exception {
        actAsOwner(); // ORG_OWNER của chính tổ chức này, nhưng đây là thao tác của nền tảng.

        assertThat(status(post("/v1/platform/organizations/" + organizationId + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                json.writeValueAsString(Map.of("email", "x-access@example.com", "role", "ORG_ADMIN")))))
                .isEqualTo(403);
    }

    @Test
    @DisplayName("CUSTOMER không phải vai trò của tổ chức: 400")
    void vai_tro_ngoai_pham_vi_to_chuc() throws Exception {
        makeSuperAdmin(ownerId);
        actAsOwner();

        assertThat(status(post("/v1/platform/organizations/" + organizationId + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "y-access@example.com", "role", "CUSTOMER")))))
                .isEqualTo(400);
    }

    // --- Gửi hộ thư đặt lại mật khẩu ---------------------------------------

    @Test
    @DisplayName("quản trị viên gửi hộ thư đặt lại mật khẩu cho nhân viên của mình")
    void gui_ho_thu_dat_lai_mat_khau() throws Exception {
        // Đường chính vẫn là người dùng tự bấm "Quên mật khẩu?" ở Keycloak. Đường này lo trường hợp
        // họ không tự làm được — gõ sai email lúc đăng ký, hoặc hộp thư chung không ai đọc.
        actAsOwner();

        assertThat(status(post("/v1/organizations/" + organizationId + "/members/" + managerId + "/password-reset")))
                .isEqualTo(202);
        // Gửi tới Keycloak bằng `sub`, không phải bằng email: `sub` mới là danh tính.
        assertThat(RESET_EMAILS_SENT).containsExactly(MANAGER_SUBJECT);

        // Và để lại vết — đây là thao tác động tới tài khoản của người khác.
        JsonNode logs = json.readTree(perform(get("/v1/organizations/" + organizationId + "/audit-logs"))
                .getResponse()
                .getContentAsString());
        assertThat(logs.get(0).path("action").asText()).isEqualTo("PASSWORD_RESET_SENT");
    }

    @Test
    @DisplayName("EVENT_MANAGER không gửi được thư đặt lại mật khẩu cho ai")
    void quan_ly_su_kien_khong_gui_duoc() throws Exception {
        actAsManager();

        assertThat(status(post("/v1/organizations/" + organizationId + "/members/" + ownerId + "/password-reset")))
                .isEqualTo(403);
        assertThat(RESET_EMAILS_SENT).isEmpty();
    }

    @Test
    @DisplayName("không gửi được cho người ngoài tổ chức mình")
    void khong_gui_duoc_cho_nguoi_ngoai() throws Exception {
        // Thiếu bước kiểm thành viên thì ORG_MEMBERS_MANAGE trở thành quyền kích hoạt luồng đặt lại
        // mật khẩu của bất kỳ ai trong hệ thống, chỉ cần biết id của họ.
        UUID nguoiNgoai = users.upsertByIdpSubject("sub-ngoai-reset", "ngoai-reset@example.com", "Nguoi Ngoai")
                .id()
                .value();
        actAsOwner();

        assertThat(status(post("/v1/organizations/" + organizationId + "/members/" + nguoiNgoai + "/password-reset")))
                .isEqualTo(404);
        assertThat(RESET_EMAILS_SENT).isEmpty();
    }

    @Test
    @DisplayName("Keycloak không trả lời: 503 kèm lý do, không phải 500")
    void idp_im_lang_thi_503() throws Exception {
        // 500 nói "hệ thống hỏng" và đẩy người ta đi đọc log của identity, trong khi nguyên nhân
        // nằm ở một phụ thuộc ngoài mà người vận hành sửa được bằng cấu hình.
        actAsOwner();
        identityProviderDown = true;

        assertThat(status(post("/v1/organizations/" + organizationId + "/members/" + managerId + "/password-reset")))
                .isEqualTo(503);
    }

    // --- Nhật ký kiểm toán --------------------------------------------------

    @Test
    @DisplayName("tổ chức đọc được vết thao tác của chính mình")
    void doc_nhat_ky_cua_to_chuc() throws Exception {
        // Bảng audit_logs được ghi từ ngày đầu nhưng chưa từng có đường đọc — một nhật ký không ai
        // đọc được thì chỉ tạo cảm giác đã kiểm soát.
        actAsOwner();
        perform(post("/v1/organizations/" + organizationId + "/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "moi2-access@example.com", "role", "CHECKIN_STAFF"))));

        JsonNode logs = json.readTree(perform(get("/v1/organizations/" + organizationId + "/audit-logs"))
                .getResponse()
                .getContentAsString());

        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).path("action").asText()).isEqualTo("MEMBER_INVITED");
        // Payload trả về đã parse sẵn, không phải JSON lồng trong chuỗi.
        assertThat(logs.get(0).path("afterState").path("email").asText()).isEqualTo("moi2-access@example.com");
    }

    @Test
    @DisplayName("EVENT_MANAGER không đọc được nhật ký kiểm toán")
    void quan_ly_su_kien_khong_doc_nhat_ky() throws Exception {
        actAsManager();

        assertThat(status(get("/v1/organizations/" + organizationId + "/audit-logs")))
                .isEqualTo(403);
    }

    @Test
    @DisplayName("không đọc được nhật ký của tổ chức mình không thuộc")
    void khong_doc_duoc_nhat_ky_to_chuc_khac() throws Exception {
        UUID toChucKhac = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, slug, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                toChucKhac,
                "khac-" + toChucKhac.toString().substring(0, 8),
                "To chuc khac");
        actAsOwner();

        // 404 của TenantFilter, không phải 403: không xác nhận tổ chức của người khác có tồn tại.
        assertThat(status(get("/v1/organizations/" + toChucKhac + "/audit-logs")))
                .isEqualTo(404);
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private static JsonNode roleNamed(JsonNode roles, String name) {
        for (JsonNode role : roles) {
            if (name.equals(role.path("role").asText())) {
                return role;
            }
        }
        throw new AssertionError("Không thấy vai trò " + name);
    }

    private void actAsOwner() {
        MutableJwt.actAs(MutableJwt.DEFAULT_SUBJECT, MutableJwt.DEFAULT_EMAIL, "Chu So Huu");
    }

    private void actAsManager() {
        MutableJwt.actAs(MANAGER_SUBJECT, MANAGER_EMAIL, "Quan Ly Su Kien");
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

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", BEARER)).andReturn();
    }

    private int status(MockHttpServletRequestBuilder builder) throws Exception {
        return perform(builder).getResponse().getStatus();
    }
}
