// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.web;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.SecurityAutoConfiguration;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import com.nexaticket.platform.web.WebAutoConfiguration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Nền cho test lát cắt HTTP: chuỗi bảo mật THẬT của starter-security ({@code TenantFilter},
 * {@code PermissionGuard}, {@code InternalApiFilter}) và bộ xử lý lỗi RFC 7807 thật của
 * starter-web, chỉ thay identity-service bằng một {@link MembershipLookup} giả.
 *
 * <p>Nằm ngoài bốn tầng hexagonal (gói {@code web}) vì nó chạm cả controller lẫn port của domain —
 * thứ {@code ArchitectureTest} không cho tầng {@code interfaces} làm.
 */
@ImportAutoConfiguration({SecurityAutoConfiguration.class, WebAutoConfiguration.class})
@TestPropertySource(properties = "nexaticket.internal.shared-secret=" + WebSliceTest.INTERNAL_SECRET)
abstract class WebSliceTest {

    static final String INTERNAL_SECRET = "bi-mat-noi-bo-cho-test";

    @Autowired
    protected MockMvc mvc;

    /** Identity-service giả: subject của JWT là chuỗi UUID của người dùng. */
    @MockitoBean
    protected MembershipLookup memberships;

    /** Không ai được đi giải mã token thật — {@code jwt()} của spring-security-test đã dựng sẵn. */
    @MockitoBean
    protected JwtDecoder jwtDecoder;

    /** Người dùng là thành viên của đúng một tổ chức với một vai trò. */
    protected RequestPostProcessor member(UUID userId, UUID organizationId, Role role) {
        return as(userId, Map.of(TenantId.of(organizationId), role), false);
    }

    /** Khách mua vé: đăng nhập được nhưng không thuộc tổ chức nào. */
    protected RequestPostProcessor customer(UUID userId) {
        return as(userId, Map.of(), false);
    }

    protected RequestPostProcessor superAdmin(UUID userId) {
        return as(userId, Map.of(), true);
    }

    /**
     * JWT hợp lệ nhưng identity-service không trả lời được — TenantScope rỗng.
     *
     * <p>Không khai stub nào: mock trả {@code null} cho mọi subject chưa khai.
     */
    protected RequestPostProcessor identityDown(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()));
    }

    protected RequestPostProcessor as(UUID userId, Map<TenantId, Role> roles, boolean superAdmin) {
        String subject = userId.toString();
        when(memberships.resolve(argThat(claims -> claims != null && subject.equals(claims.subject()))))
                .thenReturn(new MembershipLookup.Principal(UserId.of(userId), roles, superAdmin));
        return jwt().jwt(token -> token.subject(subject));
    }
}
