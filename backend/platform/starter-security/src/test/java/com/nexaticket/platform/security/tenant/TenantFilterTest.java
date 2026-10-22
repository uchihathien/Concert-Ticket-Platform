// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * {@link TenantFilter}: tầng 1 của tenant guard.
 *
 * <p>Luật cốt lõi (ADR-0008): tổ chức đang thao tác chỉ được <b>chọn</b> trong các membership đã
 * xác thực, theo đoạn {@code /organizations/{id}} của đường dẫn. Header, query hay body do client
 * gửi không bao giờ được tin. Người ngoài tổ chức nhận 404, không phải 403.
 */
class TenantFilterTest {

    private static final String ORG_A_RAW = "aaaaaaaa-0000-4000-a000-000000000001";
    private static final String ORG_B_RAW = "bbbbbbbb-0000-4000-a000-000000000002";
    private static final TenantId ORG_A = TenantId.parse(ORG_A_RAW);
    private static final TenantId ORG_B = TenantId.parse(ORG_B_RAW);
    private static final UserId USER = UserId.of(UUID.fromString("cccccccc-0000-4000-a000-000000000003"));

    private final List<MembershipLookup.Claims> lookedUp = new ArrayList<>();
    private MembershipLookup.Principal principal =
            new MembershipLookup.Principal(USER, Map.of(ORG_A, Role.ORG_ADMIN), false);
    private final MembershipLookup lookup = claims -> {
        lookedUp.add(claims);
        return principal;
    };
    private final TenantFilter filter = new TenantFilter(lookup);

    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<TenantScope> seen = new AtomicReference<>();
    private boolean chainCalled;
    private final FilterChain chain = (req, res) -> {
        chainCalled = true;
        seen.set(TenantContext.current());
    };

    @AfterEach
    void don_dep() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-sub-1")
                .claim("email", "an@example.com")
                .claim("name", "Nguyễn Văn An")
                .claim("sid", "sid-1")
                .issuedAt(Instant.parse("2026-10-01T00:00:00Z"));
    }

    private static void authenticate(Jwt token) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    private void run(String uri) throws Exception {
        run(new MockHttpServletRequest("GET", uri));
    }

    private void run(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, response, chain);
    }

    @Nested
    @DisplayName("Chưa xác thực")
    class Anonymous {

        @Test
        @DisplayName("không có authentication: scope ẩn danh, không gọi identity, request đi tiếp")
        void khong_co_auth_la_an_danh() throws Exception {
            run("/v1/organizations/" + ORG_A_RAW + "/events");

            assertThat(chainCalled).isTrue();
            assertThat(seen.get().isAuthenticated()).isFalse();
            assertThat(lookedUp).isEmpty();
        }

        @Test
        @DisplayName("authentication không phải JWT: bị bỏ qua, scope ẩn danh")
        void auth_khong_phai_jwt() throws Exception {
            SecurityContextHolder.getContext()
                    .setAuthentication(UsernamePasswordAuthenticationToken.authenticated("x", "y", List.of()));

            run("/v1/events");

            assertThat(seen.get().isAuthenticated()).isFalse();
            assertThat(lookedUp).isEmpty();
        }

        @Test
        @DisplayName("identity không trả principal: scope ẩn danh — controller sẽ trả 401")
        void identity_khong_tra_principal() throws Exception {
            principal = null;
            authenticate(jwt().build());

            run("/v1/organizations/" + ORG_A_RAW + "/events");

            assertThat(chainCalled).isTrue();
            assertThat(seen.get().isAuthenticated()).isFalse();
        }
    }

    @Nested
    @DisplayName("Tổ chức trên đường dẫn")
    class OrganizationFromPath {

        @Test
        @DisplayName("thành viên của tổ chức trên đường dẫn: tổ chức đó thành activeTenant")
        void thanh_vien_duoc_chon_to_chuc() throws Exception {
            authenticate(jwt().build());

            run("/v1/organizations/" + ORG_A_RAW + "/events");

            assertThat(chainCalled).isTrue();
            assertThat(seen.get().activeTenant()).isEqualTo(ORG_A);
            assertThat(seen.get().userId()).isEqualTo(USER);
        }

        @Test
        @DisplayName("không phải thành viên: 404 và request KHÔNG tới controller")
        void nguoi_ngoai_nhan_404() throws Exception {
            authenticate(jwt().build());

            run("/v1/organizations/" + ORG_B_RAW + "/members");

            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(chainCalled).isFalse();
        }

        @Test
        @DisplayName("superadmin trên route /organizations/{id} vẫn phải là thành viên thật: 404")
        void superadmin_tren_route_to_chuc_van_404() throws Exception {
            principal = new MembershipLookup.Principal(USER, Map.of(), true);
            authenticate(jwt().build());

            run("/v1/organizations/" + ORG_B_RAW + "/members");

            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(chainCalled).isFalse();
        }

        @Test
        @DisplayName("UUID mã hoá phần trăm: 404, không rơi về nhánh 'chỉ một tổ chức'")
        void ma_hoa_phan_tram_bi_chan() throws Exception {
            authenticate(jwt().build());

            // Người dùng chỉ thuộc ORG_A: bản cũ rơi về nhánh dự phòng, chọn ORG_A và cho đi tiếp,
            // trong khi Spring giải mã @PathVariable thành ORG_B.
            run("/v1/organizations/%62bbbbbbb-0000-4000-a000-000000000002/members");

            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(chainCalled).isFalse();
        }

        @Test
        @DisplayName("đoạn tổ chức không phải UUID: 404 chứ không phải 500")
        void doan_khong_phai_uuid_404() throws Exception {
            authenticate(jwt().build());

            run("/v1/organizations/------------------------------------/members");

            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(chainCalled).isFalse();
        }
    }

    @Nested
    @DisplayName("Không bao giờ tin tổ chức do client tự khai")
    class NeverTrustClient {

        @Test
        @DisplayName("header X-Organization-Id trỏ sang tổ chức khác: bị bỏ qua")
        void header_to_chuc_bi_bo_qua() throws Exception {
            principal = new MembershipLookup.Principal(USER, Map.of(ORG_A, Role.ORG_ADMIN), false);
            authenticate(jwt().build());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/me/dashboard");
            request.addHeader("X-Organization-Id", ORG_B_RAW);
            request.addHeader("X-Tenant-Id", ORG_B_RAW);

            run(request);

            assertThat(seen.get().activeTenant()).isEqualTo(ORG_A);
            assertThat(seen.get().isMemberOf(ORG_B)).isFalse();
        }

        @Test
        @DisplayName("query organizationId trỏ sang tổ chức khác: bị bỏ qua")
        void query_to_chuc_bi_bo_qua() throws Exception {
            principal = new MembershipLookup.Principal(
                    USER, Map.of(ORG_A, Role.ORG_ADMIN, TenantId.of(UUID.randomUUID()), Role.CHECKIN_STAFF), false);
            authenticate(jwt().build());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/reports");
            request.setParameter("organizationId", ORG_B_RAW);
            request.setQueryString("organizationId=" + ORG_B_RAW);

            run(request);

            assertThat(seen.get().activeTenant()).isNull();
        }

        @Test
        @DisplayName("claim superAdmin/roles tự nhét trong JWT không cấp quyền — chỉ identity quyết định")
        void claim_tu_nhet_khong_cap_quyen() throws Exception {
            authenticate(jwt().claim("superAdmin", true)
                    .claim("realm_access", Map.of("roles", List.of("SUPER_ADMIN")))
                    .build());

            run("/v1/events");

            assertThat(seen.get().superAdmin()).isFalse();
        }
    }

    @Nested
    @DisplayName("Route không mang tổ chức")
    class NoOrganizationInPath {

        @Test
        @DisplayName("chỉ thuộc một tổ chức: tổ chức đó là activeTenant")
        void mot_to_chuc_tu_chon() throws Exception {
            authenticate(jwt().build());

            run("/v1/me/staff-assignments");

            assertThat(seen.get().activeTenant()).isEqualTo(ORG_A);
        }

        @Test
        @DisplayName("thuộc nhiều tổ chức: không đoán, activeTenant null")
        void nhieu_to_chuc_khong_doan() throws Exception {
            principal =
                    new MembershipLookup.Principal(USER, Map.of(ORG_A, Role.ORG_ADMIN, ORG_B, Role.ORG_OWNER), false);
            authenticate(jwt().build());

            run("/v1/me");

            assertThat(seen.get().activeTenant()).isNull();
            assertThat(seen.get().tenants()).containsExactlyInAnyOrder(ORG_A, ORG_B);
        }

        @Test
        @DisplayName("route nền tảng /v1/platform/organizations/{id}: superadmin đi tiếp, không có activeTenant")
        void route_nen_tang_cho_superadmin() throws Exception {
            principal = new MembershipLookup.Principal(USER, Map.of(), true);
            authenticate(jwt().build());

            run("/v1/platform/organizations/" + ORG_B_RAW + "/suspend");

            assertThat(chainCalled).isTrue();
            assertThat(seen.get().superAdmin()).isTrue();
            assertThat(seen.get().activeTenant()).isNull();
        }

        @Test
        @DisplayName("route nền tảng với người thường: đi tiếp nhưng KHÔNG nhận tổ chức trên đường dẫn")
        void route_nen_tang_nguoi_thuong_khong_nhan_to_chuc() throws Exception {
            principal =
                    new MembershipLookup.Principal(USER, Map.of(ORG_A, Role.ORG_ADMIN, ORG_B, Role.ORG_ADMIN), false);
            authenticate(jwt().build());

            run("/v1/platform/organizations/" + ORG_B_RAW + "/suspend");

            assertThat(seen.get().activeTenant()).isNull();
            assertThat(seen.get().superAdmin()).isFalse();
        }
    }

    @Nested
    @DisplayName("Claims gửi sang identity")
    class ClaimsMapping {

        @Test
        @DisplayName("sub, email, name, sid, iat đi đủ")
        void claims_di_du() throws Exception {
            authenticate(jwt().build());

            run("/v1/me");

            assertThat(lookedUp)
                    .singleElement()
                    .isEqualTo(new MembershipLookup.Claims(
                            "kc-sub-1",
                            "an@example.com",
                            "Nguyễn Văn An",
                            "sid-1",
                            Instant.parse("2026-10-01T00:00:00Z")));
        }

        @Test
        @DisplayName("không có claim name: dùng preferred_username")
        void khong_co_name_dung_preferred_username() throws Exception {
            authenticate(Jwt.withTokenValue("t")
                    .header("alg", "none")
                    .subject("kc-sub-2")
                    .claim("preferred_username", "an.nguyen")
                    .build());

            run("/v1/me");

            MembershipLookup.Claims claims = lookedUp.get(0);
            assertThat(claims.fullName()).isEqualTo("an.nguyen");
            assertThat(claims.email()).isNull();
            assertThat(claims.sessionId()).isNull();
            assertThat(claims.issuedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("Dọn ngữ cảnh")
    class Cleanup {

        @Test
        @DisplayName("sau request, TenantContext về ẩn danh")
        void sau_request_ve_an_danh() throws Exception {
            authenticate(jwt().build());

            run("/v1/organizations/" + ORG_A_RAW + "/events");

            assertThat(TenantContext.current().isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("controller ném lỗi: TenantContext vẫn được dọn")
        void loi_van_don() {
            authenticate(jwt().build());
            FilterChain failing = (req, res) -> {
                throw new ServletException("boom");
            };

            assertThatThrownBy(() -> filter.doFilter(
                            new MockHttpServletRequest("GET", "/v1/organizations/" + ORG_A_RAW), response, failing))
                    .isInstanceOf(ServletException.class);

            assertThat(TenantContext.current().isAuthenticated()).isFalse();
        }
    }
}
