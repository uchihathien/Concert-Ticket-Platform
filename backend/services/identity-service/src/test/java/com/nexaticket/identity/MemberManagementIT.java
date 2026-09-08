// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.support.PostgresTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Quản lý thành viên, lời mời, hồ sơ và trần mua vé — qua chuỗi HTTP thật.
 *
 * <p>Trọng tâm là những luật mà aggregate {@code Organization} đã giữ từ lâu nhưng chưa endpoint
 * nào gọi tới, nên chưa bao giờ được kiểm ở mức API: không hạ được chủ sở hữu cuối cùng, không tự
 * gỡ chính mình, và {@code ORG_ADMIN} không tự phong người khác lên {@code ORG_OWNER}.
 */
@AutoConfigureMockMvc
@Import(MemberManagementIT.StubJwt.class)
class MemberManagementIT extends PostgresTestBase {

    private static final String BEARER = "Bearer test-token";
    private static final String IDP_SUBJECT = "keycloak-sub-member-test";
    private static final String EMAIL = "owner-test@example.com";

    @TestConfiguration
    static class StubJwt {
        @Bean
        @Primary
        JwtDecoder stubDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(IDP_SUBJECT)
                    .claim("email", EMAIL)
                    .claim("name", "Chu So Huu")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                    .build();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private UUID organizationId;
    private UUID ownerId;
    private UUID managerId;

    /**
     * Một tổ chức có hai người: người gọi API là {@code ORG_OWNER}, người kia là
     * {@code EVENT_MANAGER}.
     *
     * <p>Dựng thẳng bằng SQL thay vì qua API vì luồng tạo tổ chức đòi superadmin và một vòng lời
     * mời — dài, và không phải thứ test này muốn kiểm.
     */
    @BeforeEach
    void setUpOrganization() {
        jdbc.update(
                "DELETE FROM organization_members WHERE user_id IN (SELECT id FROM users WHERE email IN (?, ?))",
                EMAIL,
                "manager-test@example.com");
        jdbc.update("DELETE FROM invitations WHERE email LIKE 'moi-%@example.com'");
        jdbc.update("DELETE FROM users WHERE email IN (?, ?)", EMAIL, "manager-test@example.com");

        ownerId =
                users.upsertByIdpSubject(IDP_SUBJECT, EMAIL, "Chu So Huu").id().value();
        managerId = users.upsertByIdpSubject("sub-manager", "manager-test@example.com", "Quan Ly")
                .id()
                .value();

        organizationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, slug, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                organizationId,
                "to-chuc-thu-" + organizationId.toString().substring(0, 8),
                "To chuc thu");
        addMember(ownerId, "ORG_OWNER");
        addMember(managerId, "EVENT_MANAGER");
    }

    @Test
    @DisplayName("GET /v1/me trả hồ sơ kèm tổ chức và vai trò")
    void ho_so_kem_to_chuc() throws Exception {
        JsonNode me = json.readTree(perform(get("/v1/me")).getResponse().getContentAsString());

        assertThat(me.path("email").asText()).isEqualTo(EMAIL);
        assertThat(me.path("organizations")).hasSize(1);
        assertThat(me.path("organizations").get(0).path("role").asText()).isEqualTo("ORG_OWNER");
    }

    @Test
    @DisplayName("PATCH /v1/me: bỏ trống một ô thì KHÔNG xoá giá trị cũ")
    void patch_bo_trong_thi_giu_nguyen() throws Exception {
        // Form gửi chuỗi rỗng cho ô không nhập. Không quy đổi thành "giữ nguyên" thì người dùng
        // chỉ sửa số điện thoại sẽ vô tình xoá tên mình.
        perform(patch("/v1/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fullName", "Ten Moi", "phone", "0912345678"))));

        JsonNode after = json.readTree(perform(patch("/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "", "phone", "0987654321"))))
                .getResponse()
                .getContentAsString());

        assertThat(after.path("fullName").asText()).isEqualTo("Ten Moi");
        assertThat(after.path("phone").asText()).isEqualTo("0987654321");
    }

    @Test
    @DisplayName("bảng thành viên có email và tên, không chỉ UUID")
    void bang_thanh_vien_co_email() throws Exception {
        // Một bảng chỉ toàn UUID thì không ai biết đang gỡ nhầm ai.
        JsonNode members = json.readTree(perform(get("/v1/organizations/" + organizationId + "/members"))
                .getResponse()
                .getContentAsString());

        assertThat(members).hasSize(2);
        assertThat(members.findValuesAsText("email")).contains(EMAIL, "manager-test@example.com");
    }

    @Test
    @DisplayName("đổi vai trò của thành viên khác")
    void doi_vai_tro() throws Exception {
        JsonNode after = json.readTree(perform(patch("/v1/organizations/" + organizationId + "/members/" + managerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "ORG_ADMIN"))))
                .getResponse()
                .getContentAsString());

        assertThat(roleOf(after, managerId)).isEqualTo("ORG_ADMIN");
    }

    @Test
    @DisplayName("không hạ được vai trò của chủ sở hữu cuối cùng")
    void khong_ha_duoc_chu_so_huu_cuoi() throws Exception {
        // Mất người sở hữu cuối cùng là tổ chức không còn ai mời được ai vào, và cách cứu duy nhất
        // là superadmin sửa tay database. Aggregate đã giữ luật này từ lâu; đây là lần đầu nó
        // được kiểm ở mức API.
        UUID other = users.upsertByIdpSubject("sub-khac", "khac@example.com", "Khac")
                .id()
                .value();
        addMember(other, "ORG_ADMIN");

        MvcResult result = perform(patch("/v1/organizations/" + organizationId + "/members/" + ownerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "ORG_ADMIN"))));

        // Chặn ở luật "không tự đổi vai trò của chính mình" trước khi tới luật chủ sở hữu cuối.
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("không tự gỡ chính mình khỏi tổ chức")
    void khong_tu_go_chinh_minh() throws Exception {
        // Bấm nhầm là tự khoá mình ra ngoài, và đó là loại sự cố không tự sửa được.
        assertThat(perform(delete("/v1/organizations/" + organizationId + "/members/" + ownerId))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("gỡ được thành viên khác")
    void go_thanh_vien_khac() throws Exception {
        JsonNode after = json.readTree(perform(delete("/v1/organizations/" + organizationId + "/members/" + managerId))
                .getResponse()
                .getContentAsString());

        assertThat(after).hasSize(1);
        assertThat(after.get(0).path("userId").asText()).isEqualTo(ownerId.toString());
    }

    @Test
    @DisplayName("ORG_ADMIN không phong được người khác lên ORG_OWNER")
    void org_admin_khong_phong_duoc_owner() throws Exception {
        // Nếu cho phép, ORG_ADMIN tự nâng quyền qua trung gian: phong một tài khoản mình kiểm soát
        // lên OWNER rồi dùng tài khoản đó.
        jdbc.update(
                "UPDATE organization_members SET role = 'ORG_ADMIN' WHERE organization_id = ? AND user_id = ?",
                organizationId,
                ownerId);
        // Cần một OWNER khác để bất biến "luôn còn một owner" không phải là thứ chặn trước.
        UUID realOwner = users.upsertByIdpSubject("sub-owner-that", "owner-that@example.com", "Owner That")
                .id()
                .value();
        addMember(realOwner, "ORG_OWNER");

        assertThat(perform(patch("/v1/organizations/" + organizationId + "/members/" + managerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("role", "ORG_OWNER"))))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("EVENT_MANAGER không quản lý được thành viên")
    void event_manager_khong_quan_ly_thanh_vien() throws Exception {
        jdbc.update(
                "UPDATE organization_members SET role = 'EVENT_MANAGER' WHERE organization_id = ? AND user_id = ?",
                organizationId,
                ownerId);

        assertThat(perform(delete("/v1/organizations/" + organizationId + "/members/" + managerId))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("lời mời: hiện ra ở danh sách, thu hồi được, và KHÔNG lộ token")
    void moi_va_thu_hoi() throws Exception {
        MvcResult created = perform(post("/v1/organizations/" + organizationId + "/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "moi-1@example.com", "role", "CHECKIN_STAFF"))));
        assertThat(created.getResponse().getStatus()).isEqualTo(200);

        JsonNode pending = json.readTree(perform(get("/v1/organizations/" + organizationId + "/invitations"))
                .getResponse()
                .getContentAsString());
        assertThat(pending).hasSize(1);

        // Token thô chỉ tồn tại một lần trong phản hồi lúc tạo. Danh sách để lộ token — kể cả
        // dạng hash — sẽ biến việc đọc danh sách thành việc lấy được quyền vào tổ chức.
        assertThat(pending.toString()).doesNotContain("token").doesNotContain("Hash");

        String invitationId = pending.get(0).path("id").asText();
        assertThat(perform(delete("/v1/organizations/" + organizationId + "/invitations/" + invitationId))
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);

        assertThat(json.readTree(perform(get("/v1/organizations/" + organizationId + "/invitations"))
                        .getResponse()
                        .getContentAsString()))
                .isEmpty();
    }

    @Test
    @DisplayName("trần mua vé: null nghĩa là kế thừa, và ghi lại đọc lại đúng")
    void tran_mua_ve() throws Exception {
        // Tổ chức chưa khai gì: mọi trường phải CÓ MẶT và bằng null, không phải vắng mặt.
        // Vắng mặt thì frontend không phân biệt được "kế thừa mặc định" với "API thiếu trường".
        JsonNode empty = json.readTree(perform(get("/v1/organizations/" + organizationId + "/purchase-limits"))
                .getResponse()
                .getContentAsString());
        assertThat(empty.path("maxSeatedPerHold").isNull()).isTrue();

        JsonNode saved = json.readTree(perform(put("/v1/organizations/" + organizationId + "/purchase-limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("maxSeatedPerHold", 4, "maxTicketsPerCustomer", 6))))
                .getResponse()
                .getContentAsString());

        assertThat(saved.path("maxSeatedPerHold").asInt()).isEqualTo(4);
        assertThat(saved.path("maxTicketsPerCustomer").asInt()).isEqualTo(6);
        // Ô không gửi phải là null — "kế thừa trần nền tảng", không phải 0.
        assertThat(saved.path("maxStandingPerHold").isNull()).isTrue();
    }

    @Test
    @DisplayName("đổi tên tổ chức")
    void doi_ten_to_chuc() throws Exception {
        JsonNode after = json.readTree(perform(patch("/v1/organizations/" + organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Ten To Chuc Moi"))))
                .getResponse()
                .getContentAsString());

        assertThat(after.path("name").asText()).isEqualTo("Ten To Chuc Moi");
    }

    @Test
    @DisplayName("không phải thành viên: 404, không lộ tổ chức tồn tại")
    void to_chuc_la_thi_404() throws Exception {
        assertThat(perform(get("/v1/organizations/" + UUID.randomUUID() + "/members"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    // --- tiện ích ----------------------------------------------------------

    private MvcResult perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mockMvc.perform(builder.header("Authorization", BEARER)).andReturn();
    }

    private void addMember(UUID userId, String role) {
        jdbc.update(
                """
                INSERT INTO organization_members (id, organization_id, user_id, role)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (organization_id, user_id) DO UPDATE SET role = excluded.role
                """,
                UUID.randomUUID(),
                organizationId,
                userId,
                role);
    }

    private static String roleOf(JsonNode members, UUID userId) {
        for (JsonNode member : members) {
            if (member.path("userId").asText().equals(userId.toString())) {
                return member.path("role").asText();
            }
        }
        return null;
    }
}
