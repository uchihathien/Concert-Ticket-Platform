// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Ma trận phân quyền, đọc được từ API.
 *
 * <h2>Vì sao frontend cần thứ này</h2>
 *
 * <p>Không có nó, mỗi app phải tự viết {@code if (role === 'ORG_ADMIN' || role === 'ORG_OWNER')} để
 * quyết định hiện hay ẩn một nút. Đó là bản sao thứ hai của ma trận, nằm ở nơi backend không thấy —
 * và khi backend thêm một vai trò hoặc dời một quyền, bốn app sẽ hiện sai cho tới khi có người nhớ
 * ra phải sửa cả bên kia.
 *
 * <p>{@link #myPermissions()} trả về đúng những gì người đang đăng nhập làm được, theo từng tổ
 * chức. Frontend chỉ hỏi "tôi có {@code ORG_MEMBERS_MANAGE} ở tổ chức này không" và không cần biết
 * vai trò nào cho ra quyền đó.
 *
 * <p><b>Đây là tiện ích hiển thị, không phải cửa quyền.</b> Cửa quyền thật nằm ở
 * {@code TenantContext.requirePermission} phía server, và nó vẫn chạy dù frontend có hỏi hay không.
 */
@Service
public class AccessQueries {

    /** Toàn bộ ma trận, cho màn hình quản trị hiện bảng phân quyền. */
    public List<RoleView> allRoles() {
        return java.util.Arrays.stream(Role.values())
                .map(role -> new RoleView(
                        role.name(),
                        role.isOrganizationScoped() ? "ORGANIZATION" : "GLOBAL",
                        sorted(role.permissions())))
                .toList();
    }

    /**
     * Quyền của người đang đăng nhập.
     *
     * <p>Superadmin nhận danh sách quyền nền tảng của họ, và <b>không</b> nhận một danh sách giả
     * gồm mọi tổ chức: họ không phải thành viên của tổ chức nào, và bịa ra membership ở đường đọc
     * sẽ khiến giao diện hiện một danh sách tổ chức mà chính họ không thấy ở
     * {@code GET /v1/me/organizations}.
     */
    public MyPermissions myPermissions() {
        TenantScope scope = TenantContext.requireAuthenticated();

        Map<String, List<String>> byOrganization = new LinkedHashMap<>();
        for (Map.Entry<TenantId, Role> entry : scope.memberships().entrySet()) {
            byOrganization.put(
                    entry.getKey().value().toString(), sorted(entry.getValue().permissions()));
        }

        return new MyPermissions(
                scope.userId().value().toString(),
                scope.superAdmin(),
                scope.superAdmin() ? sorted(Role.SUPER_ADMIN.permissions()) : List.of(),
                byOrganization);
    }

    /** Thứ tự ổn định để so sánh hai lần gọi và để đọc bằng mắt. */
    private static List<String> sorted(Set<Permission> permissions) {
        return List.copyOf(new TreeSet<>(permissions.stream().map(Enum::name).toList()));
    }

    /** @param scope {@code ORGANIZATION} nếu vai trò gắn với một tổ chức, {@code GLOBAL} nếu không */
    public record RoleView(String role, String scope, List<String> permissions) {}

    /**
     * @param platformPermissions quyền phạm vi nền tảng, rỗng với người dùng thường
     * @param organizations quyền theo từng tổ chức người này thuộc về
     */
    public record MyPermissions(
            String userId,
            boolean superAdmin,
            List<String> platformPermissions,
            Map<String, List<String>> organizations) {}
}
