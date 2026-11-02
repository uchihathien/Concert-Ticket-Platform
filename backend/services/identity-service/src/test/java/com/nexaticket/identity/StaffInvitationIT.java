// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.application.command.StaffInvitationHandler;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort;
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.support.PostgresTestBase;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
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

/**
 * Nhân viên soát vé — với PostgreSQL thật, Keycloak và ticketing giả.
 *
 * <p>Hai phần: luồng MỚI (nhân viên tự đăng ký, ban tổ chức thêm ở mục Thành viên rồi gán sự kiện)
 * và luồng mời CŨ đã ngừng. Lời mời cũ không tạo mới được qua API nữa ({@code POST /staff} trả 410),
 * nên ở đây chúng được dựng thẳng bằng {@code StaffInvitationHandler} — đúng như dữ liệu đã có sẵn
 * trong database — để chứng minh kích hoạt, gửi lại, huỷ và soát vé của dữ liệu cũ vẫn chạy.
 */
@AutoConfigureMockMvc
@SuppressWarnings("removal")
@Import(StaffInvitationIT.Fakes.class)
class StaffInvitationIT extends PostgresTestBase {

    private static final String OWNER_TOKEN = "owner-token";
    private static final String OTHER_OWNER_TOKEN = "other-owner-token";
    private static final String STAFF_TOKEN = "staff-token";
    private static final String LAN_TOKEN = "lan-token";

    private static final String OWNER_EMAIL = "chu-staffit@example.com";
    private static final String OTHER_OWNER_EMAIL = "chu-khac-staffit@example.com";
    private static final String CUSTOMER_EMAIL = "khach-staffit@example.com";
    private static final String MINH = "minh-staffit@example.com";
    private static final String LAN = "lan-staffit@example.com";

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
                } else if (LAN_TOKEN.equals(token)) {
                    // Nhân viên theo luồng mới: tự đăng ký bằng tài khoản của chính mình.
                    subject = "sub-lan-staffit";
                    email = LAN;
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
    }

    /** ticketing thu nhỏ: sự kiện theo (tổ chức, người). Mọi phân công coi như còn hiệu lực. */
    static class FakeAssignments implements CheckinAssignmentPort {

        final Map<String, List<UUID>> events = new HashMap<>();

        @Override
        public Map<UserId, List<Assignment>> byOrganization(TenantId organizationId) {
            Map<UserId, List<Assignment>> result = new HashMap<>();
            events.forEach((key, value) -> {
                String[] parts = key.split("/");
                if (parts[0].equals(organizationId.value().toString()) && !value.isEmpty()) {
                    result.put(
                            UserId.of(UUID.fromString(parts[1])),
                            value.stream().map(FakeAssignments::of).toList());
                }
            });
            return result;
        }

        @Override
        public List<Assignment> assignmentsOf(TenantId organizationId, UserId userId) {
            return events.getOrDefault(organizationId.value() + "/" + userId.value(), List.of()).stream()
                    .map(FakeAssignments::of)
                    .toList();
        }

        @Override
        public void assign(TenantId organizationId, UserId userId, Collection<Target> targets, UserId assignedBy) {
            events.put(
                    organizationId.value() + "/" + userId.value(),
                    targets.stream().map(Target::eventId).distinct().toList());
        }

        @Override
        public void revokeActive(TenantId organizationId, UserId userId) {
            events.remove(organizationId.value() + "/" + userId.value());
        }

        private static Assignment of(UUID eventId) {
            return new Assignment(eventId, null, null, null, null, "UPCOMING");
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
    StaffInvitationHandler legacyInvitations;

    private UserRepository.UserRecord owner;

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
        owner = users.upsertByIdpSubject("sub-owner-staffit", OWNER_EMAIL, "Chu To Chuc");
        addMember(organizationId, owner, "ORG_OWNER");
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
    @DisplayName("POST /staff đã ngừng: 410 STAFF_INVITE_RETIRED, không tạo tài khoản Keycloak, không gửi thư")
    void tao_tai_khoan_nhan_vien_da_ngung() throws Exception {
        MvcResult result = as(OWNER_TOKEN, inviteRequest(MINH, "Nguyễn Văn Minh", eventA));

        assertThat(result.getResponse().getStatus()).isEqualTo(410);
        assertThat(json.readTree(result.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("STAFF_INVITE_RETIRED");
        assertThat(idp.emailById).isEmpty();
        assertThat(idp.mailedTo).isEmpty();
        assertThat(users.findByEmail(MINH)).isEmpty();
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

    // --- luồng mới: tự đăng ký, thêm ở mục Thành viên, gán sự kiện ---

    @Test
    @DisplayName(
            "Thêm người đã tự đăng ký với vai Staff: có quyền soát vé ngay, không tạo tài khoản hộ, có sự kiện báo")
    void them_staff_da_dang_ky() throws Exception {
        UserRepository.UserRecord lan = users.upsertByIdpSubject("sub-lan-staffit", LAN, "Trần Thị Lan");

        MvcResult found = as(
                OWNER_TOKEN,
                get("/v1/organizations/" + organizationId + "/members/lookup").param("contact", LAN));
        assertThat(found.getResponse().getStatus()).isEqualTo(200);
        JsonNode candidate = json.readTree(found.getResponse().getContentAsString());
        assertThat(candidate.path("fullName").asText()).isEqualTo("Trần Thị Lan");
        assertThat(candidate.path("maskedEmail").asText()).isEqualTo("la*********@example.com");
        assertThat(candidate.path("alreadyMember").asBoolean()).isFalse();

        assertThat(addByContact(OWNER_TOKEN, LAN, "CHECKIN_STAFF").getResponse().getStatus())
                .isEqualTo(201);

        assertThat(roleOf(LAN)).isEqualTo("CHECKIN_STAFF");
        assertThat(idp.emailById).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox WHERE event_type = 'member.added' AND payload::text LIKE ?",
                        Integer.class,
                        "%" + lan.id().value() + "%"))
                .isEqualTo(1);

        JsonNode permissions = json.readTree(
                as(LAN_TOKEN, get("/v1/me/permissions")).getResponse().getContentAsString());
        assertThat(permissions
                        .path("organizations")
                        .path(organizationId.toString())
                        .toString())
                .contains("CHECKIN_SCAN");
    }

    @Test
    @DisplayName("Email chưa đăng ký: 404 USER_NOT_REGISTERED ở cả tìm lẫn thêm, không tạo gì")
    void chua_dang_ky() throws Exception {
        MvcResult found = as(
                OWNER_TOKEN,
                get("/v1/organizations/" + organizationId + "/members/lookup")
                        .param("contact", "chua-co-staffit@example.com"));
        MvcResult added = addByContact(OWNER_TOKEN, "chua-co-staffit@example.com", "CHECKIN_STAFF");

        for (MvcResult result : List.of(found, added)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(json.readTree(result.getResponse().getContentAsString())
                            .path("code")
                            .asText())
                    .isEqualTo("USER_NOT_REGISTERED");
        }
        assertThat(users.findByEmail("chua-co-staffit@example.com")).isEmpty();
    }

    @Test
    @DisplayName("Thêm trùng: 409 ALREADY_A_MEMBER")
    void them_trung() throws Exception {
        users.upsertByIdpSubject("sub-lan-staffit", LAN, "Trần Thị Lan");
        addByContact(OWNER_TOKEN, LAN, "CHECKIN_STAFF");

        MvcResult again = addByContact(OWNER_TOKEN, LAN, "EVENT_MANAGER");

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(roleOf(LAN)).isEqualTo("CHECKIN_STAFF");
    }

    @Test
    @DisplayName("Gán sự kiện rồi gỡ khỏi tổ chức: phân công còn hiệu lực bị gỡ theo, membership mất")
    void go_thanh_vien_huy_phan_cong() throws Exception {
        UserRepository.UserRecord lan = users.upsertByIdpSubject("sub-lan-staffit", LAN, "Trần Thị Lan");
        addByContact(OWNER_TOKEN, LAN, "CHECKIN_STAFF");
        UUID session = UUID.randomUUID();

        MvcResult assigned = as(
                OWNER_TOKEN,
                put(staffPath() + "/" + lan.id().value() + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("assignments", List.of(Map.of("eventId", eventA, "eventSessionId", session))))));
        assertThat(assigned.getResponse().getStatus()).isEqualTo(200);
        assertThat(staffRow(LAN).path("assignments")).hasSize(1);

        MvcResult removed = as(
                OWNER_TOKEN,
                delete("/v1/organizations/" + organizationId + "/members/"
                        + lan.id().value()));

        assertThat(removed.getResponse().getStatus()).isEqualTo(200);
        assertThat(roleOf(LAN)).isNull();
        assertThat(assignments.assignmentsOf(TenantId.of(organizationId), lan.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("Không gán sự kiện được cho người không phải Staff")
    void gan_nguoi_khong_phai_staff() throws Exception {
        MvcResult result = as(
                OWNER_TOKEN,
                put(staffPath() + "/" + owner.id().value() + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("eventIds", List.of(eventA)))));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(assignments.events).isEmpty();
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

    /** Dựng một lời mời theo luồng cũ, như dữ liệu đã có trong database trước khi đổi quy trình. */
    private JsonNode invite(String email, String fullName, UUID... events) {
        TenantContextFixture.member(owner.id(), TenantId.of(organizationId), Role.ORG_OWNER);
        try {
            StaffInvitationHandler.Result result =
                    legacyInvitations.invite(TenantId.of(organizationId), fullName, email, List.of(events));
            return json.createObjectNode()
                    .put("outcome", result.outcome())
                    .put("userId", result.userId().toString());
        } finally {
            TenantContextFixture.clear();
        }
    }

    private MvcResult addByContact(String token, String contact, String role) throws Exception {
        return as(
                token,
                post("/v1/organizations/" + organizationId + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("contact", contact, "role", role))));
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
