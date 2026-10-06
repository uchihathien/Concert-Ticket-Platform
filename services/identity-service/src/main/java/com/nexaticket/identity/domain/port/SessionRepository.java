// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
import java.util.List;

/**
 * Phiên đăng nhập bị thu hồi.
 *
 * <p>Chỉ lưu phần <b>bị thu hồi</b>, không lưu danh sách phiên đang hoạt động: phiên là tài sản của
 * Keycloak, và giữ bản sao ở đây sẽ là một bản sao luôn lệch — Keycloak tạo và huỷ phiên mà không
 * hỏi ta. Ngoại lệ duy nhất là những phiên ta chủ động từ chối, và tập đó thì ta biết đủ.
 */
public interface SessionRepository {

    /**
     * Thu hồi một phiên cụ thể.
     *
     * @param sid claim {@code sid} của token — một phiên SSO, tức là một thiết bị
     * @param expiresAt mốc dọn được hàng này: sau khi phiên đã hết hạn ở Keycloak thì nó vô nghĩa
     */
    void revoke(String sid, UserId userId, UserId revokedBy, String reason, Instant expiresAt);

    /**
     * Phiên này đã bị thu hồi chưa.
     *
     * <p>Một lần tra theo khoá chính trên một bảng nhỏ. Nằm trên đường nóng của mọi request đã đăng
     * nhập tới identity-service, nên nó phải rẻ — và chỉ được gọi khi token thật sự mang
     * {@code sid}.
     */
    boolean isRevoked(String sid);

    /** Các phiên đang bị thu hồi của một người — để màn hình quản trị hiện được đã làm gì. */
    List<RevokedSession> findByUser(UserId userId);

    /**
     * Xoá hàng đã quá hạn.
     *
     * <p>Gọi cơ hội từ chính đường ghi thay vì dựng một job định kỳ: bảng này chỉ lớn lên khi có
     * người bấm thu hồi, nên đúng lúc đó là lúc dọn hợp lý nhất. Một job chạy mỗi giờ để dọn một
     * bảng vài chục hàng là thêm một thứ phải vận hành mà không đổi lại gì.
     *
     * @return số hàng đã xoá
     */
    int purgeExpired(Instant now);

    record RevokedSession(String sid, UserId userId, UserId revokedBy, String reason, Instant revokedAt) {}
}
