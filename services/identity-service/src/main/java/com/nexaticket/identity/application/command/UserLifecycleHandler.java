// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.UserStatus;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vô hiệu hoá và khôi phục tài khoản — chỉ nền tảng.
 *
 * <p>Không phải quyền của tổ chức, và đó là một ranh giới có chủ đích: một người có thể là thành
 * viên của nhiều tổ chức, nên "khoá tài khoản" là một quyết định vượt ra ngoài phạm vi bất kỳ tổ
 * chức nào. Thứ mà tổ chức làm được là gỡ người đó khỏi tổ chức mình — và việc đó có hiệu lực ngay,
 * vì membership được tra lại ở mỗi request.
 *
 * <p><b>Không đụng tới tài khoản bên Keycloak.</b> Người bị vô hiệu hoá vẫn đăng nhập được ở IdP và
 * vẫn nhận token hợp lệ; chỉ là mọi request tới hệ thống này đều bị từ chối. Tách như vậy vì
 * Keycloak có thể phục vụ nhiều hệ thống, và khoá tài khoản ở IdP là một quyết định rộng hơn quyết
 * định của riêng sàn vé.
 */
@Service
public class UserLifecycleHandler {

    private static final Logger log = LoggerFactory.getLogger(UserLifecycleHandler.class);

    private final UserRepository users;
    private final AuditLogger audit;
    private final Clock clock;

    public UserLifecycleHandler(UserRepository users, AuditLogger audit, Clock clock) {
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Vô hiệu hoá, và thu hồi luôn mọi phiên đang mở.
     *
     * <p>Hai việc đi liền nhau chứ không tách: trạng thái {@code DISABLED} đã đủ để chặn ở
     * identity-service, nhưng các service khác cache kết quả tra membership 60 giây. Đẩy luôn mốc
     * thu hồi thì không đổi gì về độ trễ đó — nhưng nó khiến hai cơ chế nói cùng một câu, và người
     * đọc log không phải tự hỏi vì sao chỉ một trong hai được ghi.
     */
    @Transactional
    public void disable(UserId targetUserId, String reason) {
        TenantContext.requirePlatformPermission(Permission.PLATFORM_USER_MANAGE);

        UserRepository.UserRecord target = require(targetUserId);
        if (TenantContext.requireAuthenticated().userId().equals(targetUserId)) {
            // Tự khoá mình là loại sự cố không tự sửa được: sau đó chính người đó không gọi nổi
            // endpoint mở khoá, và cách duy nhất là sửa tay trong database.
            throw ApiException.forbidden("Không tự vô hiệu hoá tài khoản của chính mình");
        }
        if (!target.isActive()) {
            return; // Idempotent: bấm hai lần không phải lỗi.
        }

        users.setStatus(targetUserId, UserStatus.DISABLED);
        users.revokeTokensIssuedBefore(targetUserId, clock.instant());

        audit.record(
                "USER_DISABLED",
                "user",
                targetUserId.value(),
                Map.of("status", UserStatus.ACTIVE.name()),
                Map.of("status", UserStatus.DISABLED.name(), "reason", reason == null ? "" : reason));
        log.warn("Đã vô hiệu hoá tài khoản {}", targetUserId);
    }

    /**
     * Khôi phục.
     *
     * <p>Cố ý <b>không</b> lùi mốc {@code tokens_valid_from}: những token bị vô hiệu lúc khoá vẫn
     * phải chết. Người dùng đăng nhập lại và nhận token mới — một thao tác vài giây, đổi lấy việc
     * một lệnh khôi phục không bao giờ hồi sinh một token đang nằm trong tay ai đó.
     */
    @Transactional
    public void enable(UserId targetUserId) {
        TenantContext.requirePlatformPermission(Permission.PLATFORM_USER_MANAGE);

        UserRepository.UserRecord target = require(targetUserId);
        if (target.isActive()) {
            return;
        }

        users.setStatus(targetUserId, UserStatus.ACTIVE);
        audit.record(
                "USER_ENABLED",
                "user",
                targetUserId.value(),
                Map.of("status", UserStatus.DISABLED.name()),
                Map.of("status", UserStatus.ACTIVE.name()));
        log.info("Đã khôi phục tài khoản {}", targetUserId);
    }

    private UserRepository.UserRecord require(UserId userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(IdentityErrorCode.USER_NOT_FOUND, "User not found"));
    }
}
