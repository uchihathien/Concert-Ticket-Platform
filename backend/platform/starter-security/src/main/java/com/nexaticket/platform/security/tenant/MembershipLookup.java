// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.util.Map;

/**
 * Cổng ra identity-service.
 *
 * <p>identity-service tự cài trực tiếp trên database của nó; các service khác cài bằng HTTP client
 * có cache ngắn. Không service nào đọc bảng {@code organization_members} của identity.
 */
public interface MembershipLookup {

    record Principal(UserId userId, Map<TenantId, Role> memberships, boolean superAdmin) {}

    /**
     * Danh tính đọc từ JWT.
     *
     * @param subject claim {@code sub} — khoá định danh bền của người dùng ở IdP
     * @param email claim {@code email}; cần để tạo bản ghi ở lần chạm đầu tiên
     * @param fullName claim {@code name}, có thể null
     * @param sessionId claim {@code sid} của Keycloak — một phiên đăng nhập, tức là một thiết bị.
     *     Cần để thu hồi <b>một</b> phiên mà không đá người dùng ra khỏi mọi máy khác. Có thể null
     *     với IdP không phát claim này; khi đó chỉ thu hồi được toàn bộ phiên.
     * @param issuedAt claim {@code iat}. Đây là thứ làm cho việc thu hồi có hiệu lực <b>ngay</b>:
     *     token phát trước mốc {@code tokens_valid_from} của người dùng bị từ chối, thay vì còn
     *     sống tới khi hết hạn. Có thể null.
     */
    record Claims(String subject, String email, String fullName, String sessionId, java.time.Instant issuedAt) {

        /** Cho những chỗ gọi không có thông tin phiên — chúng chỉ mất khả năng kiểm thu hồi. */
        public Claims(String subject, String email, String fullName) {
            this(subject, email, fullName, null, null);
        }
    }

    /**
     * Tra người dùng, <b>tạo bản ghi nếu đây là lần đầu</b>.
     *
     * <p>Vì sao tạo ở đây chứ không có một bước "đăng ký" riêng: Keycloak là nguồn chân lý của
     * danh tính, nên không có màn hình đăng ký nào ở phía ta để gắn bước đó vào. Người dùng xuất
     * hiện lần đầu ở đúng thời điểm này — request đầu tiên sau khi đăng nhập — và nếu không tạo
     * bản ghi ngay thì họ nhận 401 vĩnh viễn: không endpoint nào chạm tới được, kể cả endpoint
     * dùng để tạo bản ghi.
     *
     * @return null khi claims thiếu dữ liệu bắt buộc, hoặc identity-service không phản hồi
     */
    Principal resolve(Claims claims);
}
