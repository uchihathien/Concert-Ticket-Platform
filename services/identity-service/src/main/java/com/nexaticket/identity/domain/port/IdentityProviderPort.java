// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

/**
 * Những việc chỉ Keycloak làm được.
 *
 * <p>Cổng này cố ý <b>rất hẹp</b>. Keycloak Admin API cho phép đọc và sửa gần như mọi thứ về một
 * tài khoản — đổi mật khẩu thẳng, đọc thông tin đăng nhập, mạo danh người dùng. Mở một client
 * chung cho cả API đó rồi tự nhắc nhau "chỉ dùng đúng phần cần" là cách bảo vệ tệ nhất. Ở đây chỉ
 * có đúng một phương thức, và nó không đặt được mật khẩu — nó chỉ nhờ Keycloak gửi thư.
 *
 * <h2>Vì sao không tự làm luồng đặt lại mật khẩu</h2>
 *
 * <p>Hệ thống này <b>không lưu mật khẩu</b> (ADR-0016). Tự làm nghĩa là dựng bảng token đặt lại,
 * tự chống dò, tự hết hạn, tự chống dùng lại — rồi cuối cùng vẫn phải gọi Keycloak để ghi mật khẩu
 * mới. Toàn bộ phần đó Keycloak đã có, đã được kiểm chứng, và người dùng đặt mật khẩu trên đúng
 * tên miền mà họ vẫn đăng nhập — thứ duy nhất giúp họ phân biệt trang thật với trang giả.
 *
 * <p>Nên phần "quên mật khẩu" tự phục vụ đi thẳng qua Keycloak, không qua service này. Cổng này chỉ
 * lo trường hợp còn lại: <b>quản trị viên gửi hộ</b> khi một nhân viên không tự làm được.
 */
public interface IdentityProviderPort {

    /**
     * Nhờ Keycloak gửi thư đặt lại mật khẩu cho một tài khoản.
     *
     * <p>Thư chứa một liên kết dùng một lần dẫn tới màn hình đặt mật khẩu của Keycloak. Service này
     * không thấy mật khẩu mới, không thấy token, và không đặt được mật khẩu thay người dùng.
     *
     * @param idpSubject claim {@code sub} — id của tài khoản bên Keycloak
     * @param clientId client mà người dùng sẽ quay về sau khi đặt xong (quyết định giao diện và
     *     đường quay lại); {@code null} thì Keycloak dùng mặc định của realm
     * @param redirectUri nơi quay về sau khi đặt xong; phải khớp redirect URI đã khai của client,
     *     nếu không Keycloak từ chối
     * @throws IdentityProviderUnavailableException khi không cấu hình, hoặc Keycloak không trả lời
     */
    void sendPasswordResetEmail(String idpSubject, String clientId, String redirectUri);

    /** Cổng chưa được cấu hình, hoặc Keycloak không trả lời. Tách khỏi lỗi nghiệp vụ. */
    class IdentityProviderUnavailableException extends RuntimeException {

        public IdentityProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
