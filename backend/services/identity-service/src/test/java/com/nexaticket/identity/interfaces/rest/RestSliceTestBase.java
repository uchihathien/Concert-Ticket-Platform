// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.nexaticket.identity.application.command.AcceptInvitationHandler;
import com.nexaticket.identity.application.command.CreateOrganizationHandler;
import com.nexaticket.identity.application.command.GrantMembershipHandler;
import com.nexaticket.identity.application.command.InviteMemberHandler;
import com.nexaticket.identity.application.command.ManageMembersHandler;
import com.nexaticket.identity.application.command.OrganizationLifecycleHandler;
import com.nexaticket.identity.application.command.RecordExternalAuditHandler;
import com.nexaticket.identity.application.command.RevokeInvitationHandler;
import com.nexaticket.identity.application.command.RevokeSessionsHandler;
import com.nexaticket.identity.application.command.SendPasswordResetHandler;
import com.nexaticket.identity.application.command.SetPurchaseLimitsHandler;
import com.nexaticket.identity.application.command.StaffAccountHandler;
import com.nexaticket.identity.application.command.StaffInvitationHandler;
import com.nexaticket.identity.application.command.UpdateProfileHandler;
import com.nexaticket.identity.application.command.UserLifecycleHandler;
import com.nexaticket.identity.application.query.AccessQueries;
import com.nexaticket.identity.application.query.AuditQueries;
import com.nexaticket.identity.application.query.OrganizationQueries;
import com.nexaticket.identity.application.query.StaffQueries;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.SecurityAutoConfiguration;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Nền cho test lát cắt web của identity: toàn bộ controller, toàn bộ chuỗi bảo mật THẬT.
 *
 * <h2>Thứ chạy thật và thứ được giả</h2>
 *
 * <ul>
 *   <li><b>Thật:</b> chuỗi filter của {@code starter-security} ({@code BearerTokenAuthenticationFilter},
 *       {@code TenantFilter}), bộ chặn {@code PermissionGuard} đọc {@code @RequiresPermission},
 *       bean validation, {@code GlobalExceptionHandler} — tức là mọi thứ quyết định mã trạng thái.
 *   <li><b>Giả:</b> giải mã JWT (token là chính {@code sub}), tra membership (thay cho database), và
 *       mọi handler của tầng application. Luật nghiệp vụ bên trong handler đã có unit test riêng.
 * </ul>
 *
 * <p>{@code SecurityAutoConfiguration} được nạp bằng {@code @Import} chứ không bằng
 * auto-configuration: cấu hình người dùng được xử lý trước mọi auto-config, nên chuỗi filter của
 * starter luôn thắng chuỗi mặc định của Boot — đúng thứ tự mà ứng dụng thật dựa vào.
 */
@WebMvcTest
@Import(RestSliceTestBase.StarterSecurity.class)
abstract class RestSliceTestBase {

    protected static final TenantId ORG = TenantId.of(UUID.fromString("11111111-1111-4111-a111-111111111111"));
    protected static final TenantId OTHER_ORG = TenantId.of(UUID.fromString("22222222-2222-4222-a222-222222222222"));

    @TestConfiguration
    @Import(SecurityAutoConfiguration.class)
    static class StarterSecurity {}

    @Autowired
    protected MockMvc mvc;

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @MockitoBean
    protected MembershipLookup membershipLookup;

    @MockitoBean
    protected AccessQueries accessQueries;

    @MockitoBean
    protected OrganizationQueries organizationQueries;

    @MockitoBean
    protected AuditQueries auditQueries;

    @MockitoBean
    protected StaffQueries staffQueries;

    @MockitoBean
    protected UpdateProfileHandler updateProfile;

    @MockitoBean
    protected AcceptInvitationHandler acceptInvitation;

    @MockitoBean
    protected RecordExternalAuditHandler recordExternalAudit;

    /** StaffAccountStatusController (mobile-v2): màn đăng nhập của app Staff hỏi trạng thái tài khoản. */
    @MockitoBean
    protected StaffAccountHandler staffAccounts;

    @MockitoBean
    protected InviteMemberHandler inviteMember;

    @MockitoBean
    protected ManageMembersHandler manageMembers;

    @MockitoBean
    protected RevokeInvitationHandler revokeInvitation;

    @MockitoBean
    protected OrganizationLifecycleHandler organizationLifecycle;

    @MockitoBean
    protected SetPurchaseLimitsHandler purchaseLimits;

    @MockitoBean
    protected RevokeSessionsHandler revokeSessions;

    @MockitoBean
    protected SendPasswordResetHandler sendPasswordReset;

    @MockitoBean
    protected CreateOrganizationHandler createOrganization;

    @MockitoBean
    protected GrantMembershipHandler grantMembership;

    @MockitoBean
    protected UserLifecycleHandler userLifecycle;

    @MockitoBean
    protected StaffInvitationHandler staffInvitations;

    private final Map<String, MembershipLookup.Principal> principals = new ConcurrentHashMap<>();

    /**
     * Token giả: chuỗi token chính là {@code sub}. Token bắt đầu bằng {@code bad} bị từ chối như
     * chữ ký sai. Người dùng chưa đăng ký bằng {@link #as} thì tra membership ra null — đúng hành
     * vi của tài khoản bị khoá hay phiên đã thu hồi.
     */
    @BeforeEach
    void fakeIdentityProvider() {
        principals.clear();
        doAnswer(invocation -> {
                    String token = invocation.getArgument(0);
                    if (token.startsWith("bad")) {
                        throw new BadJwtException("Chữ ký không hợp lệ");
                    }
                    Instant now = Instant.now();
                    return Jwt.withTokenValue(token)
                            .header("alg", "RS256")
                            .subject(token)
                            .claim("email", token + "@example.com")
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(300))
                            .build();
                })
                .when(jwtDecoder)
                .decode(anyString());
        doAnswer(invocation -> {
                    MembershipLookup.Claims claims = invocation.getArgument(0);
                    return claims == null ? null : principals.get(claims.subject());
                })
                .when(membershipLookup)
                .resolve(any());
    }

    /** Thành viên của một tổ chức; trả header Authorization để gắn vào request. */
    protected String as(Role role, TenantId organization) {
        String subject = "sub-" + UUID.randomUUID();
        principals.put(
                subject,
                new MembershipLookup.Principal(UserId.of(UUID.randomUUID()), Map.of(organization, role), false));
        return "Bearer " + subject;
    }

    protected String asSuperAdmin() {
        String subject = "sub-" + UUID.randomUUID();
        principals.put(subject, new MembershipLookup.Principal(UserId.of(UUID.randomUUID()), Map.of(), true));
        return "Bearer " + subject;
    }

    /** Đã đăng nhập, không thuộc tổ chức nào. */
    protected String asCustomer() {
        String subject = "sub-" + UUID.randomUUID();
        principals.put(subject, new MembershipLookup.Principal(UserId.of(UUID.randomUUID()), Map.of(), false));
        return "Bearer " + subject;
    }

    /** JWT hợp lệ nhưng identity từ chối (tài khoản khoá, phiên thu hồi, chưa có email). */
    protected static String asRejectedIdentity() {
        return "Bearer sub-khong-ton-tai";
    }

    protected static final String AUTH = HttpHeaders.AUTHORIZATION;

    protected static String org(TenantId id) {
        return id.value().toString();
    }
}
