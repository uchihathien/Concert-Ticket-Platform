// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.SessionRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buộc một tài khoản đăng xuất.
 *
 * <h2>Vì sao việc này phải tồn tại ở phía ta, dù Keycloak quản phiên</h2>
 *
 * <p>Keycloak huỷ được refresh token, nhưng <b>access token đã phát thì sống hết 15 phút</b> và
 * không có gì trong hệ thống này từ chối nó. Với một nhân viên vừa bị cho nghỉ hoặc một chiếc laptop
 * vừa bị mất, mười lăm phút là quá dài — và không ai muốn hạ tuổi thọ token xuống vài giây chỉ để
 * xử lý tình huống hiếm.
 *
 * <p>Gỡ thành viên khỏi tổ chức thì <b>không</b> cần tới đây: membership được tra lại ở mỗi request
 * nên nó có hiệu lực ngay. Lệnh này lo phần khác — chính danh tính.
 *
 * <h2>Hai mức, và vì sao cần cả hai</h2>
 *
 * <ul>
 *   <li>{@link #revokeSession} — một phiên, tức là một thiết bị. "Tôi quên đăng xuất ở máy khác."
 *   <li>{@link #revokeAll} — mọi phiên. "Cho nghỉ việc", "mất máy".
 * </ul>
 *
 * <p>Gộp hai thứ làm một thì người dùng sẽ không dám bấm nút cho tình huống đầu.
 *
 * <h2>Ai được làm</h2>
 *
 * <p>Superadmin làm được với bất kỳ ai. Quản trị viên tổ chức chỉ làm được với <b>thành viên của
 * chính tổ chức mình</b> — và đó là ràng buộc phải kiểm tường minh: không có nó, một
 * {@code ORG_ADMIN} bất kỳ đá được cả superadmin ra khỏi hệ thống.
 */
@Service
public class RevokeSessionsHandler {

    private static final Logger log = LoggerFactory.getLogger(RevokeSessionsHandler.class);

    /**
     * Hàng thu hồi phiên sống lâu hơn phiên SSO của Keycloak.
     *
     * <p>Realm dev đặt {@code ssoSessionIdleTimeout} 30 ngày; lấy dư một chút để một phiên vừa được
     * làm mới sát mốc vẫn nằm trong tầm chặn. Dư quá thì bảng phình, thiếu thì việc thu hồi hết
     * hiệu lực sớm hơn phiên nó chặn — nên thà dư.
     */
    private static final Duration REVOCATION_LIFETIME = Duration.ofDays(45);

    private final UserRepository users;
    private final SessionRepository sessions;
    private final OrganizationRepository organizations;
    private final AuditLogger audit;
    private final Clock clock;

    public RevokeSessionsHandler(
            UserRepository users,
            SessionRepository sessions,
            OrganizationRepository organizations,
            AuditLogger audit,
            Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.organizations = organizations;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Thu hồi toàn bộ phiên của một người.
     *
     * @param organizationId tổ chức mà người gọi đang thao tác, hoặc {@code null} khi đây là thao
     *     tác của nền tảng. Khác {@code null} thì mục tiêu bắt buộc phải là thành viên của tổ chức
     *     đó.
     */
    @Transactional
    public void revokeAll(UserId targetUserId, TenantId organizationId, String reason) {
        authorize(targetUserId, organizationId);
        users.findById(targetUserId).orElseThrow(() -> new ApiException(IdentityErrorCode.USER_NOT_FOUND, "User"));

        // Mốc là "bây giờ": token phát ra sau lời gọi này vẫn hợp lệ, và đó là điều đúng — người
        // dùng đăng nhập lại là chuyện bình thường. Cái bị chặn là những token đang cầm trong tay.
        Instant now = clock.instant();
        users.revokeTokensIssuedBefore(targetUserId, now);

        audit.record("SESSIONS_REVOKED", "user", targetUserId.value(), null, payload(reason, null));
        log.info("Đã thu hồi toàn bộ phiên của {}", targetUserId);
    }

    /** Thu hồi một phiên cụ thể theo claim {@code sid} của token. */
    @Transactional
    public void revokeSession(UserId targetUserId, String sessionId, TenantId organizationId, String reason) {
        authorize(targetUserId, organizationId);
        users.findById(targetUserId).orElseThrow(() -> new ApiException(IdentityErrorCode.USER_NOT_FOUND, "User"));

        Instant now = clock.instant();
        UserId actor = TenantContext.requireAuthenticated().userId();
        sessions.revoke(sessionId, targetUserId, actor, reason, now.plus(REVOCATION_LIFETIME));

        // Dọn cơ hội, ngay trên đường ghi. Bảng này chỉ lớn lên khi có người bấm thu hồi, nên đúng
        // lúc đó là lúc dọn hợp lý nhất — rẻ hơn nhiều so với một job định kỳ phải vận hành.
        int purged = sessions.purgeExpired(now);
        if (purged > 0) {
            log.debug("Đã dọn {} hàng thu hồi phiên đã quá hạn", purged);
        }

        audit.record("SESSION_REVOKED", "user", targetUserId.value(), null, payload(reason, sessionId));
        log.info("Đã thu hồi phiên {} của {}", sessionId, targetUserId);
    }

    /**
     * Superadmin thao tác được với mọi người; quản trị viên tổ chức chỉ với thành viên của mình.
     *
     * <p>Bước kiểm "có phải thành viên không" là bước quan trọng nhất ở đây. Thiếu nó thì
     * {@code ORG_SESSION_REVOKE} — một quyền nghe rất hẹp — trở thành quyền đăng xuất bất kỳ ai
     * trong hệ thống, kể cả superadmin, chỉ cần biết id của họ.
     */
    private void authorize(UserId targetUserId, TenantId organizationId) {
        if (organizationId == null) {
            TenantContext.requirePlatformPermission(Permission.PLATFORM_USER_MANAGE);
            return;
        }
        TenantContext.requirePermission(Permission.ORG_SESSION_REVOKE, organizationId);

        Organization organization = organizations
                .findById(organizationId)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
        if (organization.findMember(targetUserId).isEmpty()) {
            // 404 chứ không 403: với quản trị viên của tổ chức này thì một người ngoài tổ chức là
            // người không tồn tại, và 403 sẽ xác nhận ngược lại rằng id đó có thật.
            throw new ApiException(IdentityErrorCode.NOT_A_MEMBER, "Not a member of this organization");
        }
    }

    /** {@code HashMap} chứ không {@code Map.of}: lý do có thể null, và {@code Map.of} từ chối null. */
    private static Map<String, Object> payload(String reason, String sessionId) {
        Map<String, Object> map = new HashMap<>();
        map.put("reason", reason);
        map.put("sessionId", sessionId);
        return map;
    }
}
