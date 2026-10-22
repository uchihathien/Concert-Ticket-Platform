// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort;
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.support.PostgresTestBase;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Ban tổ chức mời nhân viên soát vé — với PostgreSQL thật, Keycloak và ticketing giả. */
@AutoConfigureMockMvc
@Import(StaffInvitationIT.Fakes.class)
class StaffInvitationIT extends PostgresTestBase {

    private static final String OWNER_TOKEN = "owner-token";
    private static final String OTHER_OWNER_TOKEN = "other-owner-token";
    private static final String STAFF_TOKEN = "staff-token";

    private static final String OWNER_EMAIL = "chu-staffit@example.com";
    private static final String OTHER_OWNER_EMAIL = "chu-khac-staffit@example.com";
    private static final String CUSTOMER_EMAIL = "khach-staffit@example.com";
    private static final String MINH = "minh-staffit@example.com";

    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        JwtDecoder stubDecoder(FakeIdp idp) {
            return token -> {
                String subject;
                String email;
                if (OWNER_TOKEN.equals(token)) {
                    subject = "sub-owner-staffit";
                    email = OWNER_EMAIL;
                } else if (OTHER_OWNER_TOKEN.equals(token)) {
                    subject = "sub-other-owner-staffit";
                    email = OTHER_OWNER_EMAIL;
                } else {
                    // Nhân viên đăng nhập bằng đúng tài khoản Keycloak mà lời mời đã tạo.
                    subject = idp.subjectOf(MINH);
                    email = MINH;
                }
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject(subject)
                        .claim("email", email)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                        .build();
            };
        }

        @Bean
        @Primary
        FakeIdp fakeIdp() {
            return new FakeIdp();
        }

        @Bean
        @Primary
        FakeAssignments fakeAssignments() {
            return new FakeAssignments();
        }
    }

    /** Keycloak thu nhỏ: tài khoản theo id, kèm trạng thái đã đặt mật khẩu hay chưa. */
    static class FakeIdp implements IdentityProviderPort {

        final Map<String, String> emailById = new LinkedHashMap<>();
        final Map<String, Boolean> activated = new HashMap<>();
        final List<String> mailedTo = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean mailBroken;
        private int counter;

        String subjectOf(String email) {
            return emailById.entrySet().stream()
                    .filter(e -> e.getValue().equals(email))
                    .map(Map.Entry::getKey)
                    .reduce((first, second) -> second)
                    .orElse("sub-unknown-" + email);
        }

        void activate(String email) {
            activated.put(subjectOf(email), true);
        }

        void reset() {
            emailById.clear();
            activated.clear();
            mailedTo.clear();
            deleted.clear();
            mailBroken = false;
        }

        @Override
        public void sendPasswordResetEmail(String idpSubject, String clientId, String redirectUri) {}

        @Override
        public String createInvitedAccount(String email, String fullName) {
            String id = "kc-" + (++counter);
            emailById.put(id, email);
            activated.put(id, false);
            return id;
        }

        @Override
        public void sendInvitationEmail(String idpSubject, Duration linkLifetime) {
            if (mailBroken) {
                throw new IdentityProviderUnavailableException("SMTP hỏng giả lập", null);
            }
            mailedTo.add(idpSubject);
        }

        @Override
        public Optional<Account> findAccount(String idpSubject) {
            return emailById.containsKey(idpSubject)
                    ? Optional.of(new Account(idpSubject, activated.getOrDefault(idpSubject, false)))
                    : Optional.empty();
        }

        @Override
        public Optional<Account> findAccountByEmail(String email) {
            return emailById.entrySet().stream()
                    .filter(e -> e.getValue().equals(email))
                    .map(e -> new Account(e.getKey(), activated.getOrDefault(e.getKey(), false)))
                    .findFirst();
        }

        @Override
        public void deleteUnactivatedAccount(String idpSubject) {
            if (activated.getOrDefault(idpSubject, false)) {
                throw new AccountAlreadyActivatedException(idpSubject);
            }
            emailById.remove(idpSubject);
            deleted.add(idpSubject);
        }

        // Tài khoản PIN có test riêng (StaffAccountIT); ở đây chỉ cần đủ để biên dịch.
        @Override
        public String createStaffAccount(String loginEmail, String fullName, String pin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setStaffPin(String idpSubject, String pin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEnabled(String idpSubject, boolean enabled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void signOutEverywhere(String idpSubject) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAccount(String idpSubject) {
            emailById.remove(idpSubject);
            deleted.add(idpSubject);
        }
    }

    /** ticketing thu nhỏ: sự kiện theo (tổ chức, người). */
    static class FakeAssignments implements CheckinAssignmentPort {

        final Map<String, List<UUID>> events = new HashMap<>();

        @Override
        public Map<UserId, List<UUID>> byOrganization(TenantId organizationId) {
            Map<UserId, List<UUID>> result = new HashMap<>();
            events.forEach((key, value) -> {
                String[] parts = key.split("/");
                if (parts[0].equals(organizationId.value().toString()) && !value.isEmpty()) {
                    result.put(UserId.of(UUID.fromString(parts[1])), value);
                }
            });
            return result;
        }

        @Override
        public List<UUID> eventsOf(TenantId organizationId, UserId userId) {
            return events.getOrDefault(organizationId.value() + "/" + userId.value(), List.of());
        }

        @Override
        public void replace(TenantId organizationId, UserId userId, Collection<UUID> eventIds, UserId assignedBy) {
            events.put(organizationId.value() + "/" + userId.value(), List.copyOf(eventIds));
        }

        @Override
        public Map<UserId, Instant> lastSessionEnds(TenantId organizationId) {
            return Map.of();
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

    @Autowired
    FakeIdp idp;

    @Autowired
    FakeAssignments assignments;

    private UUID organizationId;
    private UUID otherOrganizationId;
    private final UUID eventA = UUID.randomUUID();
    private final UUID eventB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM staff_invitations WHERE email LIKE '%-staffit@example.com'");
        jdbc.update(
                "DELETE FROM organization_members WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%-staffit@example.com')");
        jdbc.update(
                "DELETE FROM audit_logs WHERE actor_user_id IN (SELECT id FROM users WHERE email LIKE '%-staffit@example.com')");
        jdbc.update("DELETE FROM users WHERE email LIKE '%-staffit@example.com'");
        idp.reset();
        assignments.events.clear();

        organizationId = organization("staffit-a");
        otherOrganizationId = organization("staffit-b");
        addMember(
                organizationId, users.upsertByIdpSubject("sub-owner-staffit", OWNER_EMAIL, "Chu To Chuc"), "ORG_OWNER");
        addMember(
                otherOrganizationId,
                users.upsertByIdpSubject("sub-other-owner-staffit", OTHER_OWNER_EMAIL, "Chu Khac"),
                "ORG_OWNER");
    }

    @Test
    @DisplayName("Mời email mới: tạo sẵn tài khoản chưa kích hoạt, gán sự kiện, gửi đúng một thư")
    void moi_email_moi() throws Exception {
        JsonNode invited = invite(MINH, "Nguyễn Văn Minh", eventA);

        assertThat(invited.path("outcome").asText()).isEqualTo("INVITED");
        assertThat(idp.mailedTo).hasSize(1);
        // Tài khoản ra đời chưa có mật khẩu.
        assertThat(idp.findAccountByEmail(MINH))
                .get()
                .extracting(IdentityProviderPort.Account::activated)
                .isEqualTo(false);

        JsonNode row = staffRow(MINH);
        assertThat(row.path("status").asText()).isEqualTo("INVITED");
        assertThat(row.path("fullName").asText()).isEqualTo("Nguyễn Văn Minh");
        assertThat(row.path("invitedBy").asText()).isEqualTo("Chu To Chuc");
        assertThat(row.path("eventIds")).hasSize(1);
        // Chủ tổ chức tự soát được nên không nằm trong bảng Nhân sự.
        assertThat(staff().toString()).doesNotContain(OWNER_EMAIL);
        assertThat(roleOf(MINH)).isEqualTo("CHECKIN_STAFF");
    }

    @Test
    @DisplayName("Nhân viên đặt mật khẩu xong: trạng thái thành đã kích hoạt, và chỉ thấy sự kiện được gán")
    void kich_hoat_va_su_kien_duoc_gan() throws Exception {
        invite(MINH, "Nguyễn Văn Minh", eventA);
        idp.activate(MINH);

        assertThat(staffRow(MINH).path("status").asText()).isEqualTo("ACTIVE");

        JsonNode mine = json.readTree(
                as(STAFF_TOKEN, get(staffPath() + "/me/events")).getResponse().getContentAsString());
        assertThat(mine.path("restricted").asBoolean()).isTrue();
        assertThat(mine.path("eventIds")).hasSize(1);
        assertThat(mine.path("eventIds").get(0).asText()).isEqualTo(eventA.toString());

        // Người quản lý được sự kiện của tổ chức không bị giới hạn theo việc gán.
        JsonNode owner = json.readTree(
                as(OWNER_TOKEN, get(staffPath() + "/me/events")).getResponse().getContentAsString());
        assertThat(owner.path("restricted").asBoolean()).isFalse();

        // Nhân viên soát vé không đọc được bảng Nhân sự, không mời được ai.
        assertThat(as(STAFF_TOKEN, get(staffPath())).getResponse().getStatus()).isEqualTo(403);
        assertThat(as(STAFF_TOKEN, inviteRequest("ai-do-staffit@example.com", "Ai Đó", eventA))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("Mời lại email đang chờ kích hoạt: không tạo trùng, không gửi thư trùng, chỉ gán thêm sự kiện")
    void moi_trung_khi_dang_cho() throws Exception {
        String first = invite(MINH, "Nguyễn Văn Minh", eventA).path("userId").asText();

        JsonNode again = invite(MINH, "Nguyễn Văn Minh", eventB);

        assertThat(again.path("outcome").asText()).isEqualTo("PENDING");
        assertThat(again.path("userId").asText()).isEqualTo(first);
        assertThat(idp.mailedTo).hasSize(1);
        assertThat(idp.emailById).hasSize(1);
        assertThat(staffRow(MINH).path("eventIds")).hasSize(2);
        assertThat(count("staff_invitations", MINH)).isEqualTo(1);
    }

    @Test
    @DisplayName("Mời email đã là nhân viên soát vé của chính tổ chức: chỉ gán thêm sự kiện")
    void moi_nguoi_da_la_staff() throws Exception {
        invite(MINH, "Nguyễn Văn Minh", eventA);
        idp.activate(MINH);
        staff();

        JsonNode again = invite(MINH, "Nguyễn Văn Minh", eventB);

        assertThat(again.path("outcome").asText()).isEqualTo("ALREADY_STAFF");
        assertThat(idp.mailedTo).hasSize(1);
        assertThat(staffRow(MINH).path("eventIds")).hasSize(2);
    }

    @Test
    @DisplayName("Email đã là khách, hoặc thuộc tổ chức khác: cùng một lỗi chung, không lộ lý do")
    void email_khong_moi_duoc() throws Exception {
        // Khách đã từng đăng nhập: có bản ghi người dùng, không thuộc tổ chức nào.
        users.upsertByIdpSubject("sub-khach-staffit", CUSTOMER_EMAIL, "Khach A");
        // Khách mới tự đăng ký bên Keycloak, chưa từng đăng nhập vào hệ thống.
        idp.createInvitedAccount("khach-moi-staffit@example.com", "Khach Moi");

        MvcResult customer = as(OWNER_TOKEN, inviteRequest(CUSTOMER_EMAIL, "Khach A", eventA));
        MvcResult registered = as(OWNER_TOKEN, inviteRequest("khach-moi-staffit@example.com", "Khach Moi", eventA));
        MvcResult otherOrg = as(OWNER_TOKEN, inviteRequest(OTHER_OWNER_EMAIL, "Chu Khac", eventA));
        // Người đang giữ vai trò khác ở chính tổ chức này cũng không "mời làm nhân viên" được.
        MvcResult self = as(OWNER_TOKEN, inviteRequest(OWNER_EMAIL, "Chu To Chuc", eventA));

        for (MvcResult result : List.of(customer, registered, otherOrg, self)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            String body = result.getResponse().getContentAsString();
            assertThat(json.readTree(body).path("code").asText()).isEqualTo("EMAIL_NOT_INVITABLE");
            // Không nhắc tới tổ chức, vai trò hay việc tài khoản có tồn tại.
            assertThat(body)
                    .doesNotContain("Chu Khac")
                    .doesNotContain("staffit-b")
                    .doesNotContain("ORG_OWNER");
        }
        // Khách và người của tổ chức khác nhận ĐÚNG cùng một câu: không suy ra được ai là ai.
        assertThat(json.readTree(customer.getResponse().getContentAsString())
                        .path("detail")
                        .asText())
                .isEqualTo(json.readTree(otherOrg.getResponse().getContentAsString())
                        .path("detail")
                        .asText());
        assertThat(idp.mailedTo).isEmpty();
        assertThat(roleOf(CUSTOMER_EMAIL)).isNull();
    }

    @Test
    @DisplayName("Gửi lại lời mời: tài khoản Keycloak cũ bị xoá, id người dùng và việc gán giữ nguyên")
    void gui_lai_loi_moi() throws Exception {
        String userId = invite(MINH, "Nguyễn Văn Minh", eventA).path("userId").asText();
        String oldSubject = idp.subjectOf(MINH);

        assertThat(as(OWNER_TOKEN, post(staffPath() + "/" + userId + "/invitation/resend"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);

        String newSubject = idp.subjectOf(MINH);
        // Liên kết gắn với id tài khoản: id cũ biến mất là liên kết cũ hết dùng.
        assertThat(idp.deleted).containsExactly(oldSubject);
        assertThat(newSubject).isNotEqualTo(oldSubject);
        assertThat(idp.mailedTo).containsExactly(oldSubject, newSubject);
        // Không có gì mồ côi: cùng một người dùng, giờ trỏ sang tài khoản mới.
        assertThat(users.findByEmail(MINH)).get().satisfies(user -> {
            assertThat(user.id().value().toString()).isEqualTo(userId);
            assertThat(user.idpSubject()).isEqualTo(newSubject);
        });
        assertThat(count("staff_invitations", MINH)).isEqualTo(1);
        assertThat(staffRow(MINH).path("eventIds")).hasSize(1);
    }

    @Test
    @DisplayName("Tài khoản ĐÃ kích hoạt: gửi lại và huỷ đều bị từ chối, không tài khoản nào bị xoá")
    void khong_xoa_tai_khoan_da_kich_hoat() throws Exception {
        String userId = invite(MINH, "Nguyễn Văn Minh", eventA).path("userId").asText();
        // Kích hoạt xong mà bảng Nhân sự chưa kịp đọc lại: identity vẫn tưởng đang chờ.
        idp.activate(MINH);

        MvcResult resend = as(OWNER_TOKEN, post(staffPath() + "/" + userId + "/invitation/resend"));
        MvcResult cancel = as(OWNER_TOKEN, delete(staffPath() + "/" + userId + "/invitation"));

        assertThat(resend.getResponse().getStatus()).isEqualTo(409);
        assertThat(cancel.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(cancel.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("STAFF_ALREADY_ACTIVATED");
        assertThat(idp.deleted).isEmpty();
        assertThat(roleOf(MINH)).isEqualTo("CHECKIN_STAFF");
        assertThat(staffRow(MINH).path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Huỷ lời mời chưa kích hoạt: không còn tài khoản, tư cách thành viên, hay sự kiện nào")
    void huy_loi_moi() throws Exception {
        String userId = invite(MINH, "Nguyễn Văn Minh", eventA).path("userId").asText();

        assertThat(as(OWNER_TOKEN, delete(staffPath() + "/" + userId + "/invitation"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);

        assertThat(idp.findAccountByEmail(MINH)).isEmpty();
        assertThat(users.findByEmail(MINH)).isEmpty();
        assertThat(count("staff_invitations", MINH)).isZero();
        assertThat(assignments.eventsOf(TenantId.of(organizationId), UserId.of(UUID.fromString(userId))))
                .isEmpty();
        assertThat(staff()).isEmpty();
        // Email được giải phóng: mời lại từ đầu được.
        assertThat(invite(MINH, "Nguyễn Văn Minh", eventA).path("outcome").asText())
                .isEqualTo("INVITED");
    }

    @Test
    @DisplayName("Gỡ nhân viên khỏi sự kiện: giữ tài khoản, danh sách sự kiện rỗng")
    void go_khoi_su_kien() throws Exception {
        String userId = invite(MINH, "Nguyễn Văn Minh", eventA).path("userId").asText();
        idp.activate(MINH);

        MvcResult result = as(
                OWNER_TOKEN,
                put(staffPath() + "/" + userId + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("eventIds", List.of()))));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(staffRow(MINH).path("eventIds")).isEmpty();
        assertThat(roleOf(MINH)).isEqualTo("CHECKIN_STAFF");
        // Tổ chức khác không đặt được sự kiện cho nhân viên của tổ chức này.
        assertThat(as(
                                OTHER_OWNER_TOKEN,
                                put(staffPath() + "/" + userId + "/events")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(Map.of("eventIds", List.of(eventB)))))
                        .getResponse()
                        .getStatus())
                .isIn(403, 404);
    }

    @Test
    @DisplayName("Thư không gửi được: lời mời vẫn còn ở trạng thái lỗi gửi, và gửi lại thì đi")
    void loi_gui_thu() throws Exception {
        idp.mailBroken = true;

        JsonNode invited = invite(MINH, "Nguyễn Văn Minh", eventA);

        assertThat(invited.path("outcome").asText()).isEqualTo("SEND_FAILED");
        assertThat(staffRow(MINH).path("status").asText()).isEqualTo("SEND_FAILED");

        idp.mailBroken = false;
        as(OWNER_TOKEN, post(staffPath() + "/" + invited.path("userId").asText() + "/invitation/resend"));

        assertThat(staffRow(MINH).path("status").asText()).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("Luồng lời mời bằng mã KHÔNG cấp được vai trò nhân viên soát vé, kể cả gọi thẳng API")
    void luong_cu_khong_cap_duoc_staff() throws Exception {
        MvcResult result = as(
                OWNER_TOKEN,
                post("/v1/organizations/" + organizationId + "/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", MINH, "role", "CHECKIN_STAFF"))));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(result.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("STAFF_INVITE_REQUIRED");
        assertThat(count("invitations", MINH)).isZero();
    }

    // --- dựng dữ liệu ---

    private String staffPath() {
        return "/v1/organizations/" + organizationId + "/staff";
    }

    private MockHttpServletRequestBuilder inviteRequest(String email, String fullName, UUID... events)
            throws Exception {
        return post(staffPath())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("email", email, "fullName", fullName, "eventIds", List.of(events))));
    }

    private JsonNode invite(String email, String fullName, UUID... events) throws Exception {
        MvcResult result = as(OWNER_TOKEN, inviteRequest(email, fullName, events));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode staff() throws Exception {
        MvcResult result = as(OWNER_TOKEN, get(staffPath()));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode staffRow(String email) throws Exception {
        for (JsonNode row : staff()) {
            if (email.equals(row.path("email").asText())) {
                return row;
            }
        }
        throw new AssertionError("Không thấy " + email + " trong bảng Nhân sự");
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private UUID organization(String slugPrefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, slug, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                id,
                slugPrefix + "-" + id.toString().substring(0, 8),
                slugPrefix);
        return id;
    }

    private void addMember(UUID organization, UserRepository.UserRecord user, String role) {
        jdbc.update(
                "INSERT INTO organization_members (id, organization_id, user_id, role) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                organization,
                user.id().value(),
                role);
    }

    private String roleOf(String email) {
        List<String> roles = jdbc.queryForList(
                """
                SELECT m.role FROM organization_members m JOIN users u ON u.id = m.user_id
                 WHERE u.email = ? AND m.organization_id = ?
                """,
                String.class,
                email,
                organizationId);
        return roles.isEmpty() ? null : roles.get(0);
    }

    private int count(String table, String email) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE email = ?", Integer.class, email);
        return n == null ? 0 : n;
    }
}
