// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.RevokeSessionsHandler;
import com.nexaticket.identity.application.command.SendPasswordResetHandler;
import com.nexaticket.identity.application.command.UserLifecycleHandler;
import com.nexaticket.identity.application.query.AuditQueries;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản lý tài khoản và phiên đăng nhập — khu vực Tổng công ty.
 *
 * <p>Nằm dưới {@code /v1/platform/} vì đây là thao tác xuyên tổ chức: người bị khoá có thể là thành
 * viên của nhiều tổ chức, hoặc không của tổ chức nào. {@code TenantFilter} bỏ qua bước lấy tenant
 * cho tiền tố này, nên superadmin — vốn không phải thành viên của tổ chức nào — gọi được.
 *
 * <p>{@code @RequiresPermission} ở đây là <b>lớp thứ hai</b>: handler vẫn tự kiểm bằng
 * {@code TenantContext.requirePlatformPermission}. Giá trị của annotation là làm quyền của endpoint
 * đọc được ngay trên chữ ký, thay vì phải lần vào thân handler mới biết ai gọi được.
 */
@RestController
@RequestMapping("/v1/platform")
@Validated
public class PlatformUserController {

    /**
     * Lý do đi vào nhật ký kiểm toán và vào bảng {@code revoked_sessions}, nên nó phải có trần.
     *
     * <p>Không có trần thì một tham số query dài vài megabyte đi thẳng vào một cột TEXT — không
     * phải lỗ hổng, nhưng là một đường ghi không giới hạn mà không ai định mở.
     */
    private static final String REASON_LIMIT = "500";

    private final UserLifecycleHandler lifecycle;
    private final RevokeSessionsHandler revokeSessions;
    private final SendPasswordResetHandler sendPasswordReset;
    private final AuditQueries auditQueries;

    public PlatformUserController(
            UserLifecycleHandler lifecycle,
            RevokeSessionsHandler revokeSessions,
            SendPasswordResetHandler sendPasswordReset,
            AuditQueries auditQueries) {
        this.lifecycle = lifecycle;
        this.revokeSessions = revokeSessions;
        this.sendPasswordReset = sendPasswordReset;
        this.auditQueries = auditQueries;
    }

    /**
     * Nhờ Keycloak gửi thư đặt lại mật khẩu.
     *
     * <p>Đây là đường <b>thứ hai</b>. Đường chính là người dùng tự bấm "Quên mật khẩu?" trên trang
     * đăng nhập của Keycloak — không qua service này, không cần quyền gì.
     *
     * <p>Không trả về liên kết đặt lại: trả về nghĩa là người gọi cầm được chìa khoá vào tài khoản
     * của người khác, khác hẳn việc gửi thư tới hộp thư của họ.
     */
    @PostMapping("/users/{userId}/password-reset")
    @RequiresPermission(Permission.PLATFORM_USER_MANAGE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendPasswordReset(@PathVariable UUID userId) {
        sendPasswordReset.handle(UserId.of(userId), null);
    }

    /**
     * Buộc đăng xuất mọi thiết bị.
     *
     * <p>{@code DELETE} trên tập phiên chứ không phải {@code POST /logout}: người gọi đang xoá tài
     * nguyên "các phiên đang mở của người này", và gọi lại lần hai không đổi gì thêm.
     */
    @DeleteMapping("/users/{userId}/sessions")
    @RequiresPermission(Permission.PLATFORM_USER_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAllSessions(
            @PathVariable UUID userId, @RequestParam(required = false) @Size(max = 500) String reason) {
        revokeSessions.revokeAll(UserId.of(userId), null, reason);
    }

    /** Thu hồi một phiên cụ thể theo claim {@code sid} — chỉ đá một thiết bị ra. */
    @DeleteMapping("/users/{userId}/sessions/{sessionId}")
    @RequiresPermission(Permission.PLATFORM_USER_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(
            @PathVariable UUID userId,
            // `sid` của Keycloak là một UUID; nhận khoảng hẹp nhất mà vẫn đủ cho IdP khác. Đây là
            // khoá chính của `revoked_sessions`, nên chuỗi tuỳ ý đi thẳng vào index của bảng đó.
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+") String sessionId,
            @RequestParam(required = false) @Size(max = 500) String reason) {
        revokeSessions.revokeSession(UserId.of(userId), sessionId, null, reason);
    }

    /**
     * Vô hiệu hoá tài khoản.
     *
     * <p>Không xoá bản ghi: đơn hàng, vé đã mua và nhật ký kiểm toán ở năm service khác đều trỏ vào
     * id này. Và không đụng tới tài khoản bên Keycloak — xem {@code UserLifecycleHandler}.
     */
    @PostMapping("/users/{userId}/disable")
    @RequiresPermission(Permission.PLATFORM_USER_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable UUID userId, @RequestBody(required = false) DisableRequest request) {
        lifecycle.disable(UserId.of(userId), request == null ? null : request.reason());
    }

    @PostMapping("/users/{userId}/enable")
    @RequiresPermission(Permission.PLATFORM_USER_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(@PathVariable UUID userId) {
        lifecycle.enable(UserId.of(userId));
    }

    /** Nhật ký xuyên tổ chức, gồm cả những dòng không gắn tổ chức nào (tạo tổ chức, khoá tài khoản). */
    @GetMapping("/audit-logs")
    @RequiresPermission(Permission.PLATFORM_AUDIT_READ)
    public List<AuditQueries.AuditEntry> auditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return auditQueries.forPlatform(action, limit, offset);
    }

    /** Lý do được ghi vào nhật ký kiểm toán — thao tác này cần giải thích được sau sáu tháng. */
    public record DisableRequest(@NotBlank @Size(max = 500) String reason) {}
}
