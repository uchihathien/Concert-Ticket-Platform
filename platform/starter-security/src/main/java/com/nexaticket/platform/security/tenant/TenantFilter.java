// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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

    /**
     * Khu vực nền tảng: superadmin thao tác cross-tenant, nên id trên đường dẫn KHÔNG phải là
     * tenant của người gọi.
     *
     * <p>Thiếu ngoại lệ này thì mọi route dạng {@code /v1/platform/organizations/{id}/...} đều
     * không dùng được: filter thấy {@code /organizations/{id}}, hỏi "người này có phải thành viên
     * không", và superadmin — theo đúng thiết kế — không phải thành viên của tổ chức nào. Kết quả
     * là 404 cho chính người có toàn quyền.
     *
     * <p>Lỗi này nằm im vì hai route nền tảng đầu tiên không mang id trên đường dẫn. Nó chỉ lộ ra
     * khi thêm route thứ ba.
     *
     * <p>Bỏ qua bước lấy tenant KHÔNG phải là bỏ qua kiểm quyền: handler ở khu vực này vẫn gọi
     * {@code TenantContext.requireSuperAdmin()}, nên người thường nhận 403 — và 403 chỉ nói "bạn
     * không phải superadmin", không xác nhận tổ chức kia có tồn tại hay không.
     */
    private static final String PLATFORM_PREFIX = "/v1/platform/";

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
            MembershipLookup.Principal principal = membershipLookup.resolve(claimsOf(jwt));
            if (principal != null) {
                scope = new TenantScope(principal.userId(), principal.memberships(), null, principal.superAdmin());
                TenantPath.Match fromPath = request.getRequestURI().startsWith(PLATFORM_PREFIX)
                        ? new TenantPath.Match(false, null)
                        : TenantPath.organizationOf(request.getRequestURI());

                if (fromPath.malformed()) {
                    // Đường dẫn CÓ mang đoạn tổ chức nhưng đọc không ra. Phải từ chối, không được
                    // rơi về nhánh dự phòng bên dưới.
                    //
                    // Đây từng là một lỗ hổng có thật: mã hoá phần trăm một ký tự của UUID làm mẫu
                    // so khớp trượt, filter kết luận "không có tổ chức trên đường dẫn", còn Spring
                    // vẫn giải mã ra UUID của tổ chức khác cho @PathVariable. GET /members trả 200
                    // kèm email của toàn bộ thành viên tổ chức đó. Xem TenantPath.
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                if (fromPath.present()) {
                    // Superadmin thao tác cross-tenant qua route /v1/platform/**; các route
                    // /organizations/{id} vẫn đòi membership thật.
                    if (!scope.isMemberOf(fromPath.tenant())) {
                        response.sendError(HttpServletResponse.SC_NOT_FOUND);
                        return;
                    }
                    scope = scope.withActiveTenant(fromPath.tenant());
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

    /**
     * Đọc danh tính từ JWT.
     *
     * <p>Keycloak đặt tên hiển thị ở claim {@code name}; một số IdP khác dùng
     * {@code preferred_username}. Lấy cái nào có — tên hiển thị sai chỉ làm email trông xấu, còn
     * thiếu {@code email} thì không tạo được bản ghi người dùng.
     */
    private static MembershipLookup.Claims claimsOf(Jwt jwt) {
        String fullName = jwt.getClaimAsString("name");
        if (fullName == null) {
            fullName = jwt.getClaimAsString("preferred_username");
        }
        // `sid` và `iat` đi kèm để identity kiểm được token này đã bị thu hồi chưa. Thiếu chúng
        // thì việc thu hồi phiên chỉ có hiệu lực khi token hết hạn — tức là tới 15 phút sau khi
        // một nhân viên bị cho nghỉ.
        return new MembershipLookup.Claims(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                fullName,
                jwt.getClaimAsString("sid"),
                jwt.getIssuedAt());
    }
}
