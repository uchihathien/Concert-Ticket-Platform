// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.application.command.StaffAccountHandler;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

/** Tài khoản nhân viên do hệ thống cấp (tên đăng nhập + PIN) — PostgreSQL thật, Keycloak và ticketing giả. */
@AutoConfigureMockMvc
@Import(StaffAccountIT.Fakes.class)
class StaffAccountIT extends PostgresTestBase {

    private static final String OWNER_TOKEN = "owner-token";
    private static final String OTHER_OWNER_TOKEN = "other-owner-token";
    private static final String OWNER_EMAIL = "chu-staffacc@example.com";
    private static final String OTHER_OWNER_EMAIL = "chu-khac-staffacc@example.com";

    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        JwtDecoder stubDecoder() {
            return token -> {
                boolean other = OTHER_OWNER_TOKEN.equals(token);
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject(other ? "sub-other-owner-staffacc" : "sub-owner-staffacc")
                        .claim("email", other ? OTHER_OWNER_EMAIL : OWNER_EMAIL)
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

    /** Keycloak thu nhỏ: tài khoản theo id, kèm PIN đang đặt và cờ bật/tắt. */
    static class FakeIdp implements IdentityProviderPort {

        final Map<String, String> emailById = new HashMap<>();
        final Map<String, String> pinById = new HashMap<>();
        final Map<String, Boolean> enabledById = new HashMap<>();
        final List<String> signedOut = new ArrayList<>();
        final Set<String> takenEmails = new HashSet<>();
        private int counter;

        void reset() {
            emailById.clear();
            pinById.clear();
            enabledById.clear();
            signedOut.clear();
            takenEmails.clear();
        }

        String subjectOf(String loginEmail) {
            return emailById.entrySet().stream()
                    .filter(e -> e.getValue().equals(loginEmail))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public String createStaffAccount(String loginEmail, String fullName, String pin) {
            if (takenEmails.contains(loginEmail) || emailById.containsValue(loginEmail)) {
                throw new AccountAlreadyExistsException(loginEmail);
            }
            String id = "kc-acc-" + (++counter);
            emailById.put(id, loginEmail);
            pinById.put(id, pin);
            enabledById.put(id, true);
            return id;
        }

        @Override
        public void setStaffPin(String idpSubject, String pin) {
            pinById.put(idpSubject, pin);
        }

        @Override
        public void setEnabled(String idpSubject, boolean enabled) {
            enabledById.put(idpSubject, enabled);
        }

        @Override
        public void signOutEverywhere(String idpSubject) {
            signedOut.add(idpSubject);
        }

        @Override
        public void deleteAccount(String idpSubject) {
            emailById.remove(idpSubject);
        }

        @Override
        public void sendPasswordResetEmail(String idpSubject, String clientId, String redirectUri) {}

        @Override
        public String createInvitedAccount(String email, String fullName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendInvitationEmail(String idpSubject, Duration linkLifetime) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Account> findAccount(String idpSubject) {
            return Optional.empty();
        }

        @Override
        public Optional<Account> findAccountByEmail(String email) {
            return Optional.empty();
        }

        @Override
        public void deleteUnactivatedAccount(String idpSubject) {
            throw new UnsupportedOperationException();
        }
    }

    /** ticketing thu nhỏ: sự kiện được gán, và mốc kết thúc suất cuối của từng người. */
    static class FakeAssignments implements CheckinAssignmentPort {

        final Map<String, List<UUID>> events = new HashMap<>();
        final Map<UserId, Instant> lastEnds = new HashMap<>();
        boolean down;

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
            if (down) {
                throw new AssignmentsUnavailableException("ticketing giả đang tắt", null);
            }
            return Map.copyOf(lastEnds);
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

    @Autowired
    StaffAccountHandler handler;

    private UUID organizationId;
    private UUID otherOrganizationId;
    private final UUID eventA = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Tài khoản do chủ tổ chức của lần chạy trước tạo trỏ vào chủ đó (created_by): dọn trước.
        jdbc.update(
                "DELETE FROM staff_accounts WHERE created_by IN (SELECT id FROM users WHERE email LIKE '%-staffacc@example.com')");
        jdbc.update(
                "DELETE FROM organization_members WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%-staffacc@example.com')");
        jdbc.update(
                "DELETE FROM audit_logs WHERE actor_user_id IN (SELECT id FROM users WHERE email LIKE '%-staffacc@example.com')");
        jdbc.update("DELETE FROM users WHERE email LIKE '%-staffacc@example.com'");
        idp.reset();
        assignments.events.clear();
        assignments.lastEnds.clear();
        assignments.down = false;

        organizationId = organization();
        otherOrganizationId = organization();
        addMember(organizationId, users.upsertByIdpSubject("sub-owner-staffacc", OWNER_EMAIL, "Chu To Chuc"));
        addMember(
                otherOrganizationId,
                users.upsertByIdpSubject("sub-other-owner-staffacc", OTHER_OWNER_EMAIL, "Chu Khac"));
    }

    @Test
    @DisplayName("Tạo nhân viên: tên đăng nhập tự sinh, PIN 6 số, tài khoản Keycloak không email thật, gán sự kiện")
    void tao_nhan_vien() throws Exception {
        JsonNode first = create("Trần Thị Lan", "0901234567", eventA);
        JsonNode second = create("Lê Văn Hùng", null);

        String username = first.path("username").asText();
        String pin = first.path("pin").asText();
        assertThat(username).matches("[a-z0-9]{2,12}-nv01");
        assertThat(second.path("username").asText()).isEqualTo(username.substring(0, username.length() - 2) + "02");
        assertThat(pin).matches("\\d{6}");
        assertThat(first.path("loginUri").asText()).isEqualTo("nexaticket-staff://login?u=" + username + "&p=" + pin);

        // Bên Keycloak: email nội bộ không gửi được, đăng nhập được ngay bằng đúng PIN đã trả về.
        String subject = idp.subjectOf(username + "@staff.nexaticket.invalid");
        assertThat(idp.pinById.get(subject)).isEqualTo(pin);
        assertThat(idp.enabledById.get(subject)).isTrue();

        JsonNode row = staffRow(username);
        assertThat(row.path("kind").asText()).isEqualTo("ACCOUNT");
        assertThat(row.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.hasNonNull("email")).isFalse();
        assertThat(row.path("phone").asText()).isEqualTo("0901234567");
        assertThat(row.path("invitedBy").asText()).isEqualTo("Chu To Chuc");
        assertThat(row.path("eventIds")).hasSize(1);

        // PIN không nằm ở đâu trong database — kể cả nhật ký kiểm toán.
        Integer leaked = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE after_state::text LIKE ?", Integer.class, "%" + pin + "%");
        assertThat(leaked).isZero();
    }

    @Test
    @DisplayName("Tên đăng nhập đã có bên Keycloak: lấy số kế tiếp, không báo lỗi")
    void ten_dang_nhap_trung() throws Exception {
        String username = create("Người Một", null).path("username").asText();
        String prefix = username.substring(0, username.indexOf("-nv"));
        idp.takenEmails.add(prefix + "-nv02@staff.nexaticket.invalid");

        assertThat(create("Người Hai", null).path("username").asText()).isEqualTo(prefix + "-nv03");
    }

    @Test
    @DisplayName("Cấp lại PIN: PIN mới bên Keycloak, mọi phiên cũ bị đóng, token cũ hết hiệu lực")
    void cap_lai_pin() throws Exception {
        JsonNode created = create("Trần Thị Lan", null, eventA);
        String userId = created.path("userId").asText();
        String subject = idp.subjectOf(created.path("username").asText() + "@staff.nexaticket.invalid");

        MvcResult result = as(OWNER_TOKEN, post(staffPath() + "/" + userId + "/pin"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode fresh = json.readTree(result.getResponse().getContentAsString());
        assertThat(fresh.path("username").asText())
                .isEqualTo(created.path("username").asText());
        assertThat(idp.pinById.get(subject)).isEqualTo(fresh.path("pin").asText());
        assertThat(fresh.path("eventIds")).hasSize(1);
        assertThat(idp.signedOut).contains(subject);
        assertThat(users.findById(UserId.of(UUID.fromString(userId)))
                        .orElseThrow()
                        .tokensValidFrom())
                .isNotNull();

        // Tổ chức khác không cấp được PIN cho nhân viên của tổ chức này.
        assertThat(as(OTHER_OWNER_TOKEN, post("/v1/organizations/" + otherOrganizationId + "/staff/" + userId + "/pin"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("Khoá / mở khoá: tắt bên Keycloak, và màn đăng nhập hỏi được trạng thái mà không cần token")
    void khoa_mo_khoa() throws Exception {
        JsonNode created = create("Trần Thị Lan", null);
        String userId = created.path("userId").asText();
        String username = created.path("username").asText();
        String subject = idp.subjectOf(username + "@staff.nexaticket.invalid");

        assertThat(publicStatus(username)).isEqualTo("UNKNOWN");
        assertThat(as(OWNER_TOKEN, post(staffPath() + "/" + userId + "/lock"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);

        assertThat(idp.enabledById.get(subject)).isFalse();
        assertThat(staffRow(username).path("status").asText()).isEqualTo("LOCKED");
        assertThat(publicStatus(username)).isEqualTo("LOCKED");
        // Gõ cả email nội bộ cũng ra cùng kết quả; tên không có thì không lộ gì.
        assertThat(publicStatus(username + "@staff.nexaticket.invalid")).isEqualTo("LOCKED");
        assertThat(publicStatus("khong-co-nv99")).isEqualTo("UNKNOWN");

        as(OWNER_TOKEN, post(staffPath() + "/" + userId + "/unlock"));
        assertThat(idp.enabledById.get(subject)).isTrue();
        assertThat(staffRow(username).path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Tự hết hạn: khoá khi suất cuối đã qua 6 giờ; chưa tới hạn, đã gia hạn, hay chưa có lịch thì để yên")
    void tu_het_han() throws Exception {
        UserId late = userIdOf(create("Hết việc", null, eventA));
        UserId soon = userIdOf(create("Còn việc", null, eventA));
        UserId extended = userIdOf(create("Được gia hạn", null, eventA));
        UserId unassigned = userIdOf(create("Chưa có lịch", null));
        Instant now = Instant.now();
        assignments.lastEnds.put(late, now.minus(Duration.ofHours(7)));
        assignments.lastEnds.put(soon, now.minus(Duration.ofHours(5)));
        assignments.lastEnds.put(extended, now.minus(Duration.ofHours(7)));
        as(
                OWNER_TOKEN,
                post(staffPath() + "/" + extended.value() + "/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("until", now.plus(Duration.ofHours(2)).toString()))));

        int expired = handler.expireDue();

        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(late)).isEqualTo("EXPIRED");
        assertThat(statusOf(soon)).isEqualTo("ACTIVE");
        assertThat(statusOf(extended)).isEqualTo("ACTIVE");
        assertThat(statusOf(unassigned)).isEqualTo("ACTIVE");

        // Hết hạn thì không "mở khoá" được — phải gia hạn, và gia hạn bật lại tài khoản.
        MvcResult unlock = as(OWNER_TOKEN, post(staffPath() + "/" + late.value() + "/unlock"));
        assertThat(unlock.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(unlock.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("STAFF_ACCOUNT_EXPIRED");
        assertThat(as(OWNER_TOKEN, post(staffPath() + "/" + late.value() + "/extend"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(statusOf(late)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("ticketing không trả lời: lượt hết hạn không khoá ai")
    void het_han_khi_ticketing_sap() throws Exception {
        UserId late = userIdOf(create("Hết việc", null, eventA));
        assignments.lastEnds.put(late, Instant.now().minus(Duration.ofDays(2)));
        assignments.down = true;

        assertThat(handler.expireDue()).isZero();
        assertThat(statusOf(late)).isEqualTo("ACTIVE");
    }

    // --- dựng dữ liệu ---

    private JsonNode create(String fullName, String phone, UUID... events) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("phone", phone);
        body.put("eventIds", List.of(events));
        MvcResult result = as(
                OWNER_TOKEN,
                post(staffPath() + "/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static UserId userIdOf(JsonNode created) {
        return UserId.of(UUID.fromString(created.path("userId").asText()));
    }

    private String statusOf(UserId userId) {
        return jdbc.queryForObject("SELECT status FROM staff_accounts WHERE user_id = ?", String.class, userId.value());
    }

    private String publicStatus(String username) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/staff-accounts/status").param("username", username))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString())
                .path("status")
                .asText();
    }

    private String staffPath() {
        return "/v1/organizations/" + organizationId + "/staff";
    }

    private JsonNode staffRow(String username) throws Exception {
        MvcResult result = as(OWNER_TOKEN, get(staffPath()));
        for (JsonNode row : json.readTree(result.getResponse().getContentAsString())) {
            if (username.equals(row.path("username").asText())) {
                return row;
            }
        }
        throw new AssertionError("Không thấy " + username + " trong bảng Nhân sự");
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token))
                .andReturn();
    }

    /** Slug ngẫu nhiên ở phần đầu: mã đăng nhập lấy từ đó, và mỗi lần chạy cần một mã chưa ai giữ. */
    private UUID organization() {
        UUID id = UUID.randomUUID();
        String hex = id.toString().replace("-", "");
        jdbc.update(
                "INSERT INTO organizations (id, slug, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                id,
                "a" + hex.substring(0, 7) + "-staffacc",
                "Tổ chức " + hex.substring(0, 4));
        return id;
    }

    private void addMember(UUID organization, UserRepository.UserRecord user) {
        jdbc.update(
                "INSERT INTO organization_members (id, organization_id, user_id, role) VALUES (?, ?, ?, 'ORG_OWNER')",
                UUID.randomUUID(),
                organization,
                user.id().value());
    }
}
