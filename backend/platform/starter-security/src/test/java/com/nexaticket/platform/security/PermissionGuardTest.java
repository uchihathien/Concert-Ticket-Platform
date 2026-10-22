// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantFilter;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * {@link PermissionGuard} và {@link RequiresPermission}: lớp kiểm quyền thứ hai ở tầng web.
 *
 * <p>Chạy sau {@link TenantFilter}, nên ở đây {@code TenantContext} được dựng bằng chính filter
 * thật với một {@link MembershipLookup} giả — đúng thứ tự như lúc chạy, thay vì tự set một scope
 * mà filter không bao giờ sinh ra.
 */
class PermissionGuardTest {

    private static final String ORG_A_RAW = "aaaaaaaa-0000-4000-a000-000000000001";
    private static final TenantId ORG_A = TenantId.parse(ORG_A_RAW);
    private static final UserId USER = UserId.of(UUID.fromString("cccccccc-0000-4000-a000-000000000003"));

    private final PermissionGuard guard = new PermissionGuard();

    @AfterEach
    void don_dep() {
        SecurityContextHolder.clearContext();
    }

    /** Controller mẫu: mỗi method là một cách khai quyền. */
    static class SampleController {
        @RequiresPermission(Permission.CATALOG_MANAGE)
        public void manageCatalog() {}

        @RequiresPermission(Permission.PLATFORM_ORG_MANAGE)
        public void suspendOrganization() {}

        public void open() {}
    }

    @RequiresPermission(Permission.ORG_METRICS_VIEW)
    static class ClassLevelController {
        public void metrics() {}

        @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
        public void members() {}
    }

    private static HandlerMethod handler(Object bean, String name) throws NoSuchMethodException {
        return new HandlerMethod(bean, bean.getClass().getMethod(name));
    }

    /**
     * Chạy {@link TenantFilter} với principal cho trước, rồi gọi guard ở BÊN TRONG chuỗi filter —
     * nơi {@code TenantContext} còn sống.
     */
    private Object runAs(MembershipLookup.Principal principal, String uri, HandlerMethod handler) throws Exception {
        if (principal != null) {
            Jwt jwt =
                    Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        }
        AtomicReference<Object> outcome = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TenantFilter(claims -> principal).doFilter(request, response, (req, res) -> {
            try {
                outcome.set(guard.preHandle(request, response, handler));
            } catch (ApiException e) {
                outcome.set(e);
            }
        });
        if (outcome.get() == null) {
            // TenantFilter đã chặn trước (404) — guard không chạy.
            return response.getStatus();
        }
        return outcome.get();
    }

    private static MembershipLookup.Principal member(Role role) {
        return new MembershipLookup.Principal(USER, Map.of(ORG_A, role), false);
    }

    private static MembershipLookup.Principal superAdmin() {
        return new MembershipLookup.Principal(USER, Map.of(), true);
    }

    private static ErrorCode codeOf(Object outcome) {
        assertThat(outcome).isInstanceOf(ApiException.class);
        return ((ApiException) outcome).errorCode();
    }

    @Nested
    @DisplayName("Quyền phạm vi tổ chức")
    class OrgScoped {

        @Test
        @DisplayName("vai trò có quyền trong tổ chức trên đường dẫn: cho qua")
        void co_quyen_cho_qua() throws Exception {
            Object outcome = runAs(
                    member(Role.EVENT_MANAGER),
                    "/v1/organizations/" + ORG_A_RAW + "/events",
                    handler(new SampleController(), "manageCatalog"));

            assertThat(outcome).isEqualTo(true);
        }

        @Test
        @DisplayName("thành viên thiếu quyền: FORBIDDEN")
        void thieu_quyen_bi_chan() throws Exception {
            Object outcome = runAs(
                    member(Role.CHECKIN_STAFF),
                    "/v1/organizations/" + ORG_A_RAW + "/events",
                    handler(new SampleController(), "manageCatalog"));

            assertThat(codeOf(outcome)).isEqualTo(ErrorCode.Common.FORBIDDEN);
        }

        @Test
        @DisplayName("quyền tổ chức khai trên route không có /organizations/{id}: FORBIDDEN, không âm thầm cho qua")
        void route_khong_co_to_chuc_bi_chan() throws Exception {
            Object outcome =
                    runAs(member(Role.ORG_OWNER), "/v1/events", handler(new SampleController(), "manageCatalog"));

            ApiException ex = (ApiException) outcome;
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.Common.FORBIDDEN);
            assertThat(ex.getMessage()).contains("/organizations/{id}");
        }

        @Test
        @DisplayName("superadmin trên route tổ chức mình không thuộc: TenantFilter chặn 404 trước guard")
        void superadmin_bi_filter_chan_truoc() throws Exception {
            Object outcome = runAs(
                    superAdmin(),
                    "/v1/organizations/" + ORG_A_RAW + "/events",
                    handler(new SampleController(), "manageCatalog"));

            assertThat(outcome).isEqualTo(404);
        }

        @Test
        @DisplayName("ẩn danh: UNAUTHENTICATED")
        void an_danh_401() throws Exception {
            Object outcome = runAs(
                    null,
                    "/v1/organizations/" + ORG_A_RAW + "/events",
                    handler(new SampleController(), "manageCatalog"));

            assertThat(codeOf(outcome)).isEqualTo(ErrorCode.Common.UNAUTHENTICATED);
        }
    }

    @Nested
    @DisplayName("Quyền phạm vi nền tảng")
    class PlatformScoped {

        @Test
        @DisplayName("superadmin: cho qua, không cần tổ chức trên đường dẫn")
        void superadmin_cho_qua() throws Exception {
            Object outcome = runAs(
                    superAdmin(),
                    "/v1/platform/organizations/" + ORG_A_RAW + "/suspend",
                    handler(new SampleController(), "suspendOrganization"));

            assertThat(outcome).isEqualTo(true);
        }

        @Test
        @DisplayName("chủ sở hữu tổ chức: FORBIDDEN, kể cả với chính tổ chức của mình")
        void org_owner_bi_chan() throws Exception {
            Object outcome = runAs(
                    member(Role.ORG_OWNER),
                    "/v1/platform/organizations/" + ORG_A_RAW + "/suspend",
                    handler(new SampleController(), "suspendOrganization"));

            assertThat(codeOf(outcome)).isEqualTo(ErrorCode.Common.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("Khai báo annotation")
    class Declaration {

        @Test
        @DisplayName("method không khai quyền: guard không can thiệp, kể cả với người ẩn danh")
        void khong_khai_quyen_cho_qua() throws Exception {
            assertThat(runAs(null, "/v1/events", handler(new SampleController(), "open")))
                    .isEqualTo(true);
        }

        @Test
        @DisplayName("annotation ở cấp lớp áp cho method không tự khai")
        void annotation_cap_lop() throws Exception {
            Object denied = runAs(
                    member(Role.CHECKIN_STAFF),
                    "/v1/organizations/" + ORG_A_RAW + "/metrics",
                    handler(new ClassLevelController(), "metrics"));
            Object allowed = runAs(
                    member(Role.EVENT_MANAGER),
                    "/v1/organizations/" + ORG_A_RAW + "/metrics",
                    handler(new ClassLevelController(), "metrics"));

            assertThat(codeOf(denied)).isEqualTo(ErrorCode.Common.FORBIDDEN);
            assertThat(allowed).isEqualTo(true);
        }

        @Test
        @DisplayName("annotation ở method thắng annotation ở lớp")
        void method_thang_lop() throws Exception {
            // EVENT_MANAGER có ORG_METRICS_VIEW (của lớp) nhưng không có ORG_MEMBERS_MANAGE (của method).
            Object outcome = runAs(
                    member(Role.EVENT_MANAGER),
                    "/v1/organizations/" + ORG_A_RAW + "/members",
                    handler(new ClassLevelController(), "members"));

            assertThat(codeOf(outcome)).isEqualTo(ErrorCode.Common.FORBIDDEN);
        }

        @Test
        @DisplayName("handler không phải HandlerMethod (tài nguyên tĩnh): cho qua")
        void handler_khong_phai_method() {
            assertThat(guard.preHandle(
                            new MockHttpServletRequest("GET", "/favicon.ico"),
                            new MockHttpServletResponse(),
                            new ResourceHttpRequestHandler()))
                    .isTrue();
        }

        @Test
        @DisplayName("annotation giữ tới runtime và đặt được trên method lẫn lớp")
        void annotation_runtime() throws Exception {
            assertThat(RequiresPermission.class.getAnnotation(Retention.class).value())
                    .isEqualTo(RetentionPolicy.RUNTIME);
            assertThat(RequiresPermission.class.getAnnotation(Target.class).value())
                    .containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
            assertThat(SampleController.class
                            .getMethod("manageCatalog")
                            .getAnnotation(RequiresPermission.class)
                            .value())
                    .isEqualTo(Permission.CATALOG_MANAGE);
        }
    }

    @Test
    @DisplayName("guard không để lại ngữ cảnh: sau chuỗi filter TenantContext là ẩn danh")
    void khong_de_lai_ngu_canh() throws Exception {
        runAs(
                member(Role.ORG_OWNER),
                "/v1/organizations/" + ORG_A_RAW + "/events",
                handler(new SampleController(), "open"));

        assertThat(TenantContext.current().isAuthenticated()).isFalse();
        assertThatThrownBy(TenantContext::requireAuthenticated).isInstanceOf(ApiException.class);
    }
}
