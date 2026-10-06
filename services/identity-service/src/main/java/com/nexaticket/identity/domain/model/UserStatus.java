// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.model;

/**
 * Trạng thái tài khoản.
 *
 * <p>Tách khỏi việc xoá bản ghi có chủ đích: người bị vô hiệu hoá vẫn còn đơn hàng, vé và dòng
 * nhật ký kiểm toán trỏ vào id của họ ở năm service khác. Xoá là làm mồ côi tất cả những thứ đó.
 *
 * <p>Vô hiệu hoá ở đây <b>không</b> đụng tới tài khoản bên Keycloak: người dùng vẫn đăng nhập được
 * và vẫn nhận được token hợp lệ, chỉ là mọi request của họ tới hệ thống này đều bị từ chối. Hai
 * việc đó tách nhau vì Keycloak có thể phục vụ nhiều hệ thống, và khoá tài khoản ở IdP là một
 * quyết định rộng hơn quyết định của riêng sàn vé.
 */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
