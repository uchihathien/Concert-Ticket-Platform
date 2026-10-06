// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản trị viên gửi hộ thư đặt lại mật khẩu.
 *
 * <h2>Đây là đường thứ hai, không phải đường chính</h2>
 *
 * <p>Đường chính là người dùng tự bấm "Quên mật khẩu?" trên trang đăng nhập của Keycloak. Nó không
 * đi qua service này, không cần quyền gì, và là cách đúng cho gần như mọi trường hợp.
 *
 * <p>Đường này lo phần còn lại, và nó có thật: nhân viên đổi số điện thoại nên không nhận được thư,
 * hộp thư chung không ai đọc, hoặc người dùng gõ sai email lúc đăng ký nên chính họ không tự đặt
 * lại được. Khi đó quản trị viên — người ngồi cạnh và xác nhận được danh tính bằng miệng — bấm một
 * nút thay họ.
 *
 * <h2>Ba điều lệnh này KHÔNG làm</h2>
 *
 * <ul>
 *   <li>Không đặt mật khẩu. Nó nhờ Keycloak gửi một liên kết dùng một lần; mật khẩu mới do chính
 *       người dùng gõ, trên tên miền của Keycloak.
 *   <li>Không trả về liên kết đó cho người gọi. Trả về nghĩa là quản trị viên cầm được chìa khoá
 *       vào tài khoản của nhân viên — khác hẳn việc gửi thư tới hộp thư của họ.
 *   <li>Không xác nhận email có tồn tại hay không cho người ngoài tổ chức, xem {@link #authorize}.
 * </ul>
 */
@Service
public class SendPasswordResetHandler {

    private static final Logger log = LoggerFactory.getLogger(SendPasswordResetHandler.class);

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final IdentityProviderPort identityProvider;
    private final AuditLogger audit;

    public SendPasswordResetHandler(
            UserRepository users,
            OrganizationRepository organizations,
            IdentityProviderPort identityProvider,
            AuditLogger audit) {
        this.users = users;
        this.organizations = organizations;
        this.identityProvider = identityProvider;
        this.audit = audit;
    }

    /**
     * @param organizationId tổ chức mà người gọi đang thao tác, hoặc {@code null} khi đây là thao
     *     tác của nền tảng
     */
    @Transactional
    public void handle(UserId targetUserId, TenantId organizationId) {
        authorize(targetUserId, organizationId);

        UserRepository.UserRecord target = users.findById(targetUserId)
                .orElseThrow(() -> new ApiException(IdentityErrorCode.USER_NOT_FOUND, "User not found"));

        if (!target.isActive()) {
            // Gửi thư đặt lại mật khẩu cho một tài khoản đã bị vô hiệu hoá là làm người ta đặt lại
            // mật khẩu rồi vẫn không vào được — một vòng hỗ trợ vô ích. Mở khoá trước.
            throw new ApiException(
                    IdentityErrorCode.USER_DISABLED,
                    "Tài khoản đang bị vô hiệu hoá; khôi phục trước khi đặt lại mật khẩu");
        }

        try {
            identityProvider.sendPasswordResetEmail(target.idpSubject(), null, null);
        } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
            // Chi tiết đi vào LOG, không đi vào response.
            //
            // Thông điệp của ngoại lệ này chứa nguyên văn thân lỗi của Keycloak — realm, đường dẫn
            // nội bộ, đôi khi cả cấu hình client. Người gọi endpoint này là quản trị viên tổ chức,
            // tức là người ngoài đối với hạ tầng; họ cần biết "chưa gửi được, thử lại sau", không
            // cần biết Keycloak đang phàn nàn điều gì.
            //
            // 503 chứ không 500: đây là một phụ thuộc ngoài không sẵn sàng và người gọi thử lại
            // được. Người vận hành lần theo correlationId trong log để lấy chi tiết.
            log.warn("Không gửi được thư đặt lại mật khẩu cho {}: {}", targetUserId, e.getMessage(), e);
            throw new ApiException(
                    IdentityErrorCode.PASSWORD_RESET_UNAVAILABLE,
                    "Không gửi được thư đặt lại mật khẩu. Thử lại sau ít phút, "
                            + "hoặc liên hệ quản trị nền tảng nếu vẫn không được.");
        }

        // Ghi vết nhưng KHÔNG ghi email vào payload: nhật ký kiểm toán của tổ chức đọc được bởi
        // nhiều người, và ở đây id người dùng đã đủ để lần ra ai.
        audit.record("PASSWORD_RESET_SENT", "user", targetUserId.value(), null, payload(organizationId));
    }

    /**
     * Superadmin gửi được cho bất kỳ ai; quản trị viên tổ chức chỉ cho thành viên của mình.
     *
     * <p>Cùng ràng buộc với việc thu hồi phiên, và cùng lý do: không có bước kiểm thành viên thì
     * {@code ORG_MEMBERS_MANAGE} trở thành quyền kích hoạt luồng đặt lại mật khẩu của bất kỳ ai
     * trong hệ thống, chỉ cần biết id của họ.
     */
    private void authorize(UserId targetUserId, TenantId organizationId) {
        if (organizationId == null) {
            TenantContext.requirePlatformPermission(Permission.PLATFORM_USER_MANAGE);
            return;
        }
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);

        Organization organization = organizations
                .findById(organizationId)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
        if (organization.findMember(targetUserId).isEmpty()) {
            throw new ApiException(IdentityErrorCode.NOT_A_MEMBER, "Not a member of this organization");
        }
    }

    private static Map<String, Object> payload(TenantId organizationId) {
        Map<String, Object> map = new HashMap<>();
        map.put("scope", organizationId == null ? "PLATFORM" : "ORGANIZATION");
        return map;
    }
}
