// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kết nối tới Admin API của Keycloak.
 *
 * <p><b>{@code clientSecret} có giá trị mặc định cho dev, và PHẢI đổi ở production.</b> Nó khớp với
 * {@code deploy/keycloak/nexaticket-realm.json} — cùng cách mà bí mật của bốn client app đang làm,
 * nên máy trắng chạy được ngay sau khi import realm. Khác {@code nexaticket.internal.shared-secret}
 * ở chỗ đó, và khác vì lý do: bí mật kia bảo vệ một endpoint đã có sẵn, còn bí mật này chỉ mở một
 * tính năng mà không có nó thì tính năng đơn giản là không tồn tại.
 *
 * <p>Đây vẫn là bí mật cho phép quản lý mọi tài khoản trong realm. Ở production đặt bằng biến môi
 * trường và xoay vòng như một mật khẩu database. Để trống thì tính năng gửi thư đặt lại mật khẩu
 * tắt hẳn và trả 503 kèm lý do, thay vì hỏng bằng một 500 vô nghĩa.
 *
 * @param baseUrl gốc của Keycloak, không kèm {@code /realms/...}
 * @param realm realm chứa người dùng của hệ thống
 * @param clientId client có service account, được cấp vai trò {@code realm-management:manage-users}
 * @param defaultResetClientId client mà người dùng quay về sau khi đặt lại mật khẩu — quyết định
 *     giao diện và đường quay lại. Mặc định là app của ban tổ chức, vì đường quản trị viên gửi hộ
 *     gần như luôn dành cho nhân viên nội bộ.
 * @param defaultResetRedirectUri nơi quay về; phải nằm trong redirect URI đã khai của client đó,
 *     nếu không Keycloak từ chối cả lời gọi
 */
@ConfigurationProperties(prefix = "nexaticket.identity.keycloak")
public record KeycloakAdminProperties(
        String baseUrl,
        String realm,
        String clientId,
        String clientSecret,
        String defaultResetClientId,
        String defaultResetRedirectUri,
        Duration timeout) {

    public KeycloakAdminProperties {
        if (realm == null || realm.isBlank()) {
            realm = "nexaticket";
        }
        if (clientId == null || clientId.isBlank()) {
            clientId = "identity-admin";
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            // Đường này nằm sau một thao tác của con người trên màn hình quản trị, không phải trên
            // đường nóng. Vẫn phải hữu hạn: mặc định của Java là chờ vô hạn, và một Keycloak treo
            // sẽ giữ luồng của identity cho tới khi hết heap.
            timeout = Duration.ofSeconds(5);
        }
    }

    /** Đủ thông tin để gọi Admin API chưa. */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
