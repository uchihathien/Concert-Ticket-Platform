// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.id.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tầng 1 của tenant guard: dựng {@link TenantScope} từ claim {@code sub} của JWT.
 *
 * <p>Nếu route mang {@code /organizations/{id}} thì {@code id} chỉ được dùng để <b>chọn</b> trong
 * các membership đã xác thực. Không thuộc membership nào thì trả <b>404</b>, không phải 403 — 403
 * tiết lộ rằng tài nguyên của tổ chức khác tồn tại.
 */
public class TenantFilter extends OncePerRequestFilter {

    private static final Pattern ORG_IN_PATH = Pattern.compile("/organizations/([0-9a-fA-F-]{36})");

    private final MembershipLookup membershipLookup;

    public TenantFilter(MembershipLookup membershipLookup) {
        this.membershipLookup = membershipLookup;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        TenantScope scope = TenantScope.anonymous();

        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            MembershipLookup.Principal principal = membershipLookup.resolve(jwt.getSubject());
            if (principal != null) {
                scope = new TenantScope(principal.userId(), principal.memberships(), null, principal.superAdmin());
                TenantId fromPath = extractOrganization(request.getRequestURI());
                if (fromPath != null) {
                    // Superadmin thao tác cross-tenant qua route /v1/platform/**; các route
                    // /organizations/{id} vẫn đòi membership thật.
                    if (!scope.isMemberOf(fromPath)) {
                        response.sendError(HttpServletResponse.SC_NOT_FOUND);
                        return;
                    }
                    scope = scope.withActiveTenant(fromPath);
                } else if (scope.tenants().size() == 1) {
                    scope = scope.withActiveTenant(scope.tenants().iterator().next());
                }
            }
        }

        TenantContext.set(scope);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static TenantId extractOrganization(String uri) {
        Matcher matcher = ORG_IN_PATH.matcher(uri);
        return matcher.find() ? TenantId.parse(matcher.group(1)) : null;
    }
}
